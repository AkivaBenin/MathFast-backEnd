package com.mathfast;

import com.mathfast.entity.GameHistory;
import com.mathfast.repository.GameHistoryRepository;
import com.mathfast.service.MoveValidationService;
import com.mathfast.service.RaceTerminationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RaceIntegrationTest {

    @Autowired
    private MoveValidationService moveValidationService;

    @Autowired
    private RaceTerminationService raceTerminationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private GameHistoryRepository gameHistoryRepository;

    private UUID raceId;

    @BeforeEach
    public void setup() {
        raceId = UUID.randomUUID();
        redisTemplate.opsForValue().set("room_state:" + raceId, "ACTIVE");
        redisTemplate.opsForValue().set("race:" + raceId + ":win_score", "10");
    }

    @Test
    public void testConcurrentNonceLockingDropsDuplicatesInstantly() throws InterruptedException, ExecutionException {
        String playerName = "Player_One";
        String nonce = "SECURE_NONCE_888";

        // Setup question & answer expectation in Redis
        setupPlayerMove(raceId, playerName, nonce, 42);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Boolean> task1 = () -> moveValidationService.validateAndScoreMove(raceId, playerName, nonce, 42, "REGULAR").success;
        Callable<Boolean> task2 = () -> moveValidationService.validateAndScoreMove(raceId, playerName, nonce, 42, "REGULAR").success;

        Future<Boolean> future1 = executor.submit(task1);
        Future<Boolean> future2 = executor.submit(task2);

        Boolean result1;
        try {
            result1 = future1.get();
        } catch (Exception e) {
            result1 = false;
        }

        Boolean result2;
        try {
            result2 = future2.get();
        } catch (Exception e) {
            result2 = false;
        }

        executor.shutdown();

        // Exactly one submission must succeed, the other must drop instantly
        assertTrue(result1 ^ result2, "Atomic SETNX lock & nonce deletion successfully prevented double submission.");
    }

    @Test
    public void testThreeWinnersTransitionsRoomToFinished() {
        String p1 = "Racer_1";
        String p2 = "Racer_2";
        String p3 = "Racer_3";
        String p4 = "Racer_4";

        redisTemplate.opsForSet().add("roster:" + raceId, p1, p2, p3, p4);

        // Simulate 3 winners crossing threshold
        setupPlayerMove(raceId, p1, "NONCE_1", 100);
        setupPlayerMove(raceId, p2, "NONCE_2", 100);
        setupPlayerMove(raceId, p3, "NONCE_3", 100);

        moveValidationService.validateAndScoreMove(raceId, p1, "NONCE_1", 100, "HIGHWAY");
        moveValidationService.validateAndScoreMove(raceId, p2, "NONCE_2", 100, "HIGHWAY");
        moveValidationService.validateAndScoreMove(raceId, p3, "NONCE_3", 100, "HIGHWAY");

        // Verify room state transitioned to FINISHED
        assertEquals("FINISHED", redisTemplate.opsForValue().get("room_state:" + raceId), "Room state successfully locked to FINISHED.");

        // Verify 4th player movement is dropped instantly due to lockout
        setupPlayerMove(raceId, p4, "NONCE_4", 100);
        boolean move4Result = moveValidationService.validateAndScoreMove(raceId, p4, "NONCE_4", 100, "REGULAR").success;
        assertFalse(move4Result, "Subsequent movement packets rejected instantly after state termination.");
    }

    @Test
    public void testTransactionalBridgeBatchPersistence() {
        UUID testRaceId = UUID.randomUUID();
        redisTemplate.opsForValue().set("room_state:" + testRaceId, "ACTIVE");
        redisTemplate.opsForValue().set("race:" + testRaceId + ":win_score", "10");

        String p1 = "Alpha_Racer";
        setupPlayerMove(testRaceId, p1, "N1", 50);

        // Before finish line, database must be completely empty for this race (ephemeral speed in Redis)
        List<GameHistory> initialDbQuery = gameHistoryRepository.findByRaceIdOrderByRankPositionAsc(testRaceId);
        assertTrue(initialDbQuery.isEmpty(), "Real-time scores kept strictly within ephemeral storage.");

        // Execute valid move and cross finish line (simulating 1-player test room or triggering termination)
        redisTemplate.opsForSet().add("roster:" + testRaceId, "Alpha_Racer");
        moveValidationService.validateAndScoreMove(testRaceId, p1, "N1", 50, "REGULAR");
        
        // Force termination check
        raceTerminationService.checkWinCondition(testRaceId);

        // After game transitions to final state, TransactionalBridge batch persists to MySQL
        List<GameHistory> finalDbQuery = gameHistoryRepository.findByRaceIdOrderByRankPositionAsc(testRaceId);
        assertFalse(finalDbQuery.isEmpty(), "TransactionalBridge correctly batch-persisted scores to MySQL upon state termination.");
        assertEquals("Alpha_Racer", finalDbQuery.get(0).getPlayerName());
    }

    private void setupPlayerMove(UUID rId, String playerName, String nonce, int answer) {
        String prefix = "room:" + rId + ":player:" + playerName + ":";
        redisTemplate.opsForValue().set(prefix + "nonce", nonce);
        redisTemplate.opsForValue().set(prefix + "answer", String.valueOf(answer));
        redisTemplate.opsForValue().set(prefix + "expires_at", String.valueOf(System.currentTimeMillis() + 30000L));
    }
}
