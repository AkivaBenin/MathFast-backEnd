package com.mathfast.service;

import com.mathfast.entity.GameHistory;
import com.mathfast.repository.GameHistoryRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RaceTerminationService {

    private final StringRedisTemplate redisTemplate;
    private final SseStreamService sseStreamService;
    private final GameHistoryRepository gameHistoryRepository;

    public RaceTerminationService(StringRedisTemplate redisTemplate, SseStreamService sseStreamService, GameHistoryRepository gameHistoryRepository) {
        this.redisTemplate = redisTemplate;
        this.sseStreamService = sseStreamService;
        this.gameHistoryRepository = gameHistoryRepository;
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
        // Evict all player-specific ephemeral keys (both room-scoped and legacy)
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
                redisTemplate.delete(prefix + "swaps");
                redisTemplate.delete(prefix + "path");
                redisTemplate.delete(prefix + "stall");

                redisTemplate.delete("player:" + playerName + ":nonce");
                redisTemplate.delete("player:" + playerName + ":answer");
                redisTemplate.delete("player:" + playerName + ":lock");
                redisTemplate.delete("player:" + playerName + ":q_time");
                redisTemplate.delete("player:" + playerName + ":swaps");
                redisTemplate.delete("player:" + playerName + ":path");
                redisTemplate.delete("player:" + playerName + ":stall");
            }
        }

        String[] keys = new String[]{
            "room_state:" + raceId,
            "race_leaderboard:" + raceId,
            "race:" + raceId + ":finishers",
            "race:" + raceId + ":human_latencies",
            "race:" + raceId + ":fastest_time",
            "race:" + raceId + ":fastest_player",
            "race:" + raceId + ":highway_counts",
            "race:" + raceId + ":max_highway_count",
            "race:" + raceId + ":highway_hero",
            "race:" + raceId + ":terminated_lock",
            "race:" + raceId + ":win_score"
        };
        for (String k : keys) {
            redisTemplate.delete(k);
        }

        // Re-seed leaderboard with 0 for all existing roster members
        if (roster != null && !roster.isEmpty()) {
            for (String playerName : roster) {
                redisTemplate.opsForZSet().add("race_leaderboard:" + raceId, playerName, 0.0);
            }
        }

        redisTemplate.opsForValue().set("room_state:" + raceId, "LOBBY");
        sseStreamService.broadcastToRoom(raceId, "ROOM_RESET", "LOBBY");
        sseStreamService.broadcastToRoom(raceId, "STATE_CHANGE", "LOBBY");
    }

    public void executeSoftLobbyReturn(UUID raceId) {
        // Clear ephemeral race progress metrics while keeping roster and participant registries fully intact
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

        // Reset scores in leaderboard to 0 for all existing roster members
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

        redisTemplate.opsForValue().set("room_state:" + raceId, "LOBBY");
        sseStreamService.broadcastToRoom(raceId, "STATE_CHANGE", "LOBBY");
    }
}
