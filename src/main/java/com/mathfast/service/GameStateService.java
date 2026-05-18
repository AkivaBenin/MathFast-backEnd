package com.mathfast.service;

import com.mathfast.exception.EmptyRoomException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class GameStateService {

    private final StringRedisTemplate redisTemplate;
    private final SseStreamService sseStreamService;

    public GameStateService(StringRedisTemplate redisTemplate, SseStreamService sseStreamService) {
        this.redisTemplate = redisTemplate;
        this.sseStreamService = sseStreamService;
    }

    public List<Map<String, Object>> getRoomUsers(UUID roomId) {
        Set<String> roster = redisTemplate.opsForSet().members("roster:" + roomId);
        if (roster == null || roster.isEmpty()) return Collections.emptyList();
        
        List<Map<String, Object>> userList = new ArrayList<>();
        for (String nickname : roster) {
            Double score = redisTemplate.opsForZSet().score("race_leaderboard:" + roomId, nickname);
            long safeScore = score != null ? Math.round(score) : 0L;
            String prefix = "room:" + roomId + ":player:" + nickname + ":";
            String color = redisTemplate.opsForValue().get(prefix + "color");
            if (color == null) color = "neon-blue";
            
            String luckStr = redisTemplate.opsForValue().get(prefix + "luck");
            int luckPoints = luckStr != null ? Integer.parseInt(luckStr) : 0;
            
            String stallStr = redisTemplate.opsForValue().get(prefix + "stall");
            double stallMultiplier = stallStr != null ? Double.parseDouble(stallStr) : 1.0;
            boolean stalled = stallMultiplier < 1.0 || stallMultiplier > 1.0;
            
            String activePath = redisTemplate.opsForValue().get(prefix + "path");
            if (activePath == null) activePath = "REGULAR";
            
            String item = redisTemplate.opsForValue().get(prefix + "item");
            if (item == null && luckPoints >= 100) item = "OIL_SLICK";
            
            String underdogStr = redisTemplate.opsForValue().get(prefix + "underdog");
            boolean underdog = underdogStr != null && Boolean.parseBoolean(underdogStr);
            
            String swapsStr = redisTemplate.opsForValue().get(prefix + "swaps");
            int swapsRemaining = swapsStr != null ? Integer.parseInt(swapsStr) : 3;

            Map<String, Object> uMap = new java.util.HashMap<>();
            uMap.put("id", nickname);
            uMap.put("nickname", nickname);
            uMap.put("score", safeScore);
            uMap.put("color", color);
            uMap.put("luckPoints", luckPoints);
            uMap.put("stallMultiplier", stallMultiplier);
            uMap.put("speedMultiplier", 1.0);
            uMap.put("stalled", stalled);
            uMap.put("activePath", activePath);
            if (item != null) uMap.put("inventoryItem", item);
            uMap.put("underdog", underdog);
            uMap.put("swapsRemaining", swapsRemaining);
            
            String jPendingStr = redisTemplate.opsForValue().get(prefix + "junctionPending");
            boolean junctionPending = "true".equalsIgnoreCase(jPendingStr);
            uMap.put("junctionPending", junctionPending);
            
            String isActiveStr = (String) redisTemplate.opsForHash().get("room:" + roomId + ":player:" + nickname + ":status", "isActive");
            boolean isActive = isActiveStr == null || Boolean.parseBoolean(isActiveStr);
            uMap.put("isActive", isActive);
            
            userList.add(uMap);
        }
        return userList;
    }

    public void updatePlayerColor(UUID roomId, String nickname, String color) {
        redisTemplate.opsForValue().set("room:" + roomId + ":player:" + nickname + ":color", color);
        List<Map<String, Object>> userList = getRoomUsers(roomId);
        sseStreamService.broadcastToRoom(roomId, "ROSTER_UPDATE", Map.of("users", userList));
    }

    /**
     * Mod 7 — Guard: abort if roster is empty.
     * Mod 10 — Harden countdown: set STARTING in Redis BEFORE broadcasting so any
     * late-joining SSE subscriber immediately sees the correct state on connect.
     */
    public void triggerGameStart(UUID roomId, String jwtToken) {
        Set<String> initialRoster = redisTemplate.opsForSet().members("roster:" + roomId);
        if (initialRoster != null) {
            for (String nickname : new java.util.ArrayList<>(initialRoster)) {
                String isActiveStr = (String) redisTemplate.opsForHash().get("room:" + roomId + ":player:" + nickname + ":status", "isActive");
                if ("false".equalsIgnoreCase(isActiveStr)) {
                    leaveRoom(roomId, nickname);
                }
            }
        }

        // Mod 7: Enforce at least one human player before starting
        Set<String> roster = redisTemplate.opsForSet().members("roster:" + roomId);
        int humanCount = roster != null ? roster.size() : 0;
        if (humanCount == 0) {
            throw new EmptyRoomException(roomId);
        }

        List<Map<String, Object>> activeUsers = getRoomUsers(roomId);
        sseStreamService.broadcastToRoom(roomId, "ROSTER_UPDATE", Map.of("users", activeUsers));

        // Mod 10: Persist STARTING to Redis first so late SSE joiners read correct state
        String stateKey = "room_state:" + roomId;
        redisTemplate.opsForValue().set(stateKey, "STARTING");

        // Broadcast synchronized countdown start payload
        sseStreamService.broadcastToRoom(roomId, "STATE_CHANGE", "STARTING");
        sseStreamService.broadcastToRoom(roomId, "COUNTDOWN", Map.of("count", 5, "serverEpoch", System.currentTimeMillis()));

        // Schedule the ACTIVE transition exactly 5 000 ms later
        scheduleActivation(roomId, stateKey);
    }

    /**
     * Mod 10 — Reliable ACTIVE transition.
     * Runs on the Spring @Async thread pool so it never blocks the HTTP response thread.
     * After sleeping 5 s, writes ACTIVE to Redis and broadcasts to all clients simultaneously,
     * ensuring every viewport transitions at the same instant.
     */
    @Async
    public void scheduleActivation(UUID roomId, String stateKey) {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        redisTemplate.opsForValue().set(stateKey, "ACTIVE");
        sseStreamService.broadcastToRoom(roomId, "STATE_CHANGE", "ACTIVE");
        String winScoreStr = redisTemplate.opsForValue().get("race:" + roomId + ":win_score");
        int winScore = winScoreStr != null ? Integer.parseInt(winScoreStr) : 100;
        sseStreamService.broadcastToRoom(roomId, "WIN_SCORE", Map.of("winScore", winScore));
    }

    public void leaveRoom(UUID roomId, String nickname) {
        String rosterKey = "roster:" + roomId;
        redisTemplate.opsForSet().remove(rosterKey, nickname);
        redisTemplate.opsForZSet().remove("race_leaderboard:" + roomId, nickname);

        List<Map<String, Object>> userList = getRoomUsers(roomId);
        sseStreamService.broadcastToRoom(roomId, "ROSTER_UPDATE", Map.of("users", userList));
    }
}
