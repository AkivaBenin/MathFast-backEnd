package com.mathfast.service;

import com.mathfast.entity.GameHistory;
import com.mathfast.repository.GameHistoryRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RaceTerminationService {

    private final StringRedisTemplate redisTemplate;
    private final SseStreamService sseStreamService;
    private final GameHistoryRepository gameHistoryRepository;
    private final GameStateService gameStateService;

    public RaceTerminationService(StringRedisTemplate redisTemplate, SseStreamService sseStreamService, GameHistoryRepository gameHistoryRepository, GameStateService gameStateService) {
        this.redisTemplate = redisTemplate;
        this.sseStreamService = sseStreamService;
        this.gameHistoryRepository = gameHistoryRepository;
        this.gameStateService = gameStateService;
    }

    public void checkWinCondition(UUID raceId) {
        String finishKey = "race:" + raceId + ":finishers";
        Long finisherCount = redisTemplate.opsForSet().size(finishKey);
        
        Set<String> roster = redisTemplate.opsForSet().members("roster:" + raceId);
        long totalPlayers = roster != null ? roster.size() : 8;

        if (finisherCount != null && (finisherCount >= 3 || finisherCount >= totalPlayers)) {
            Boolean alreadyFinished = redisTemplate.opsForValue().setIfAbsent("race:" + raceId + ":terminated_lock", "LOCKED", 10, TimeUnit.MINUTES);
            if (Boolean.TRUE.equals(alreadyFinished)) {
                // Forcefully transition room status to FINISHED
                redisTemplate.opsForValue().set("room_state:" + raceId, "FINISHED");
                sseStreamService.broadcastToRoom(raceId, "STATE_CHANGE", "FINISHED");
                // Mod 9 — Cascade terminate all emitters so browsers release connection slots immediately
                sseStreamService.terminateRoomConnections(raceId);

                // Trigger TransactionalBridge batch persistence & data hygiene
                persistGameHistory(raceId);
                scheduleDataHygiene(raceId);
            }
        }
    }

    @Transactional
    public void persistGameHistory(UUID raceId) {
        Set<TypedTuple<String>> leaderBoard = redisTemplate.opsForZSet().reverseRangeWithScores("race_leaderboard:" + raceId, 0, -1);
        if (leaderBoard == null || leaderBoard.isEmpty()) return;

        String speedDemonPlayer = redisTemplate.opsForValue().get("race:" + raceId + ":fastest_player");
        String highwayHeroPlayer = redisTemplate.opsForValue().get("race:" + raceId + ":highway_hero");

        List<GameHistory> historyBatch = new ArrayList<>();
        int rank = 1;

        LocalDateTime now = LocalDateTime.now();

        for (TypedTuple<String> tuple : leaderBoard) {
            String playerName = tuple.getValue();
            int score = tuple.getScore() != null ? tuple.getScore().intValue() : 0;
            boolean isBot = false;
            boolean speedDemon = playerName != null && playerName.equals(speedDemonPlayer);
            boolean highwayHero = playerName != null && playerName.equals(highwayHeroPlayer);

            GameHistory gh = new GameHistory(
                raceId, 
                playerName != null ? playerName : UUID.randomUUID().toString(), 
                playerName != null ? playerName : "Unknown", 
                score, 
                rank++, 
                isBot, 
                speedDemon, 
                highwayHero, 
                now
            );
            historyBatch.add(gh);
        }

        gameHistoryRepository.saveAll(historyBatch);
    }

    private void scheduleDataHygiene(UUID raceId) {
        String[] keys = new String[]{
            "room_state:" + raceId,
            "race_leaderboard:" + raceId,
            "race:" + raceId + ":finishers",
            "roster:" + raceId,
            "room:" + raceId + ":participants",
            "race:" + raceId + ":human_latencies",
            "race:" + raceId + ":fastest_time",
            "race:" + raceId + ":fastest_player",
            "race:" + raceId + ":highway_counts",
            "race:" + raceId + ":max_highway_count",
            "race:" + raceId + ":highway_hero",
            "race:" + raceId + ":win_score",
            "race:" + raceId + ":terminated_lock"
        };
        for (String k : keys) {
            redisTemplate.expire(k, 5, TimeUnit.MINUTES);
        }
    }
    
    public void executeAdminReset(UUID raceId) {
        Set<String> roster = redisTemplate.opsForSet().members("roster:" + raceId);
        if (roster != null) {
            for (String playerName : roster) {
                String prefix = "room:" + raceId + ":player:" + playerName + ":";
                redisTemplate.delete(prefix + "nonce");
                redisTemplate.delete(prefix + "answer");
                redisTemplate.delete(prefix + "lock");
                redisTemplate.delete(prefix + "q_time");
                redisTemplate.delete(prefix + "expires_at");
                redisTemplate.delete(prefix + "streak");
                redisTemplate.delete(prefix + "item");
                redisTemplate.delete(prefix + "underdog");
                redisTemplate.delete(prefix + "junctionsFired");

                redisTemplate.opsForValue().set(prefix + "luck", "0");
                redisTemplate.opsForValue().set(prefix + "stall", "1.0");
                redisTemplate.opsForValue().set(prefix + "swaps", "3");
                redisTemplate.opsForValue().set(prefix + "path", "REGULAR");
                redisTemplate.opsForValue().set(prefix + "junctionPending", "false");
                redisTemplate.opsForHash().put("room:" + raceId + ":player:" + playerName + ":status", "isActive", "true");

                redisTemplate.opsForZSet().add("race_leaderboard:" + raceId, playerName, 0.0);
            }
        }

        String[] keys = new String[]{
            "room_state:" + raceId,
            "race:" + raceId + ":finishers",
            "race:" + raceId + ":human_latencies",
            "race:" + raceId + ":fastest_time",
            "race:" + raceId + ":fastest_player",
            "race:" + raceId + ":highway_counts",
            "race:" + raceId + ":max_highway_count",
            "race:" + raceId + ":highway_hero",
            "race:" + raceId + ":terminated_lock"
        };
        for (String k : keys) {
            redisTemplate.delete(k);
        }

        // Explicitly zero out winScore
        redisTemplate.opsForValue().set("race:" + raceId + ":win_score", "0");

        redisTemplate.opsForValue().set("room:" + raceId, "LOBBY");
        redisTemplate.opsForValue().set("room_state:" + raceId, "LOBBY");
        sseStreamService.broadcastToRoom(raceId, "ROOM_RESET", "LOBBY");
        sseStreamService.broadcastToRoom(raceId, "STATE_CHANGE", "LOBBY");
        List<Map<String, Object>> userList = gameStateService.getRoomUsers(raceId);
        sseStreamService.broadcastToRoom(raceId, "ROSTER_UPDATE", Map.of("users", userList));
    }

    public void executeSoftLobbyReturn(UUID raceId) {
        String[] keys = new String[]{
            "race:" + raceId + ":finishers",
            "race:" + raceId + ":terminated_lock",
            "race:" + raceId + ":fastest_time",
            "race:" + raceId + ":fastest_player",
            "race:" + raceId + ":highway_counts",
            "race:" + raceId + ":max_highway_count",
            "race:" + raceId + ":highway_hero"
        };
        for (String k : keys) {
            redisTemplate.delete(k);
        }

        redisTemplate.opsForValue().set("race:" + raceId + ":win_score", "0");

        Set<String> roster = redisTemplate.opsForSet().members("roster:" + raceId);
        if (roster != null && !roster.isEmpty()) {
            for (String playerName : roster) {
                redisTemplate.opsForZSet().add("race_leaderboard:" + raceId, playerName, 0.0);
                String prefix = "room:" + raceId + ":player:" + playerName + ":";
                redisTemplate.delete(prefix + "answer");
                redisTemplate.delete(prefix + "lock");
                redisTemplate.delete(prefix + "stall");
                redisTemplate.delete(prefix + "nonce");
            }
        }

        redisTemplate.opsForValue().set("room:" + raceId, "LOBBY");
        redisTemplate.opsForValue().set("room_state:" + raceId, "LOBBY");
        sseStreamService.broadcastToRoom(raceId, "STATE_CHANGE", "LOBBY");
    }
}
