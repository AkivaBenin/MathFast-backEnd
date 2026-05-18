package com.mathfast.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.data.redis.core.StringRedisTemplate;

@Service
public class SseStreamService {

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> roomEmitters = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;

    public SseStreamService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public SseEmitter createConnection(UUID roomId, String jwtToken) {
        SseEmitter emitter = new SseEmitter(3600000L); // 1-hour timeout

        roomEmitters.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(roomId, emitter));
        emitter.onTimeout(() -> {
            removeEmitter(roomId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> {
            removeEmitter(roomId, emitter);
            emitter.completeWithError(e);
        });

        try {
            emitter.send(SseEmitter.event().name("CONNECTED").data("Connected successfully"));
            
            // 2. Player Loading Synchronization: Immediate Snapshot Dispatch
            String currentState = redisTemplate.opsForValue().get("room_state:" + roomId);
            if (currentState != null) {
                emitter.send(SseEmitter.event().name("STATE_CHANGE").data(currentState));
            }
            
            String winScoreStr = redisTemplate.opsForValue().get("race:" + roomId + ":win_score");
            int winScore = winScoreStr != null ? Integer.parseInt(winScoreStr) : 100;
            emitter.send(SseEmitter.event().name("WIN_SCORE").data(Map.of("winScore", winScore)));
            
            java.util.Set<String> roster = redisTemplate.opsForSet().members("roster:" + roomId);
            if (roster != null && !roster.isEmpty()) {
                java.util.List<Map<String, Object>> userList = new java.util.ArrayList<>();
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
                    
                    userList.add(uMap);
                }
                emitter.send(SseEmitter.event().name("ROSTER_UPDATE").data(Map.of("users", userList)));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private void removeEmitter(UUID roomId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = roomEmitters.get(roomId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                roomEmitters.remove(roomId);
            }
        }
    }

    public void broadcastToRoom(UUID roomId, String eventName, Object data) {
        CopyOnWriteArrayList<SseEmitter> emitters = roomEmitters.get(roomId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                removeEmitter(roomId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * Mod 9 — Cascade Room Closure.
     * Broadcasts a ROOM_CLOSED event to every emitter in the room, then invokes
     * emitter.complete() on each one so browsers immediately release their connection
     * slot (bypassing the 6-connection-per-origin browser pool limit).
     */
    public void terminateRoomConnections(UUID roomId) {
        CopyOnWriteArrayList<SseEmitter> emitters = roomEmitters.remove(roomId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("ROOM_CLOSED").data("ROOM_CLOSED"));
            } catch (IOException ignored) {
                // Best-effort — the client may already be gone
            } finally {
                emitter.complete();
            }
        }
    }

    @Scheduled(fixedRate = 15000)
    public void sendKeepAlive() {
        roomEmitters.forEach((roomId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException e) {
                    removeEmitter(roomId, emitter);
                    emitter.completeWithError(e);
                }
            }
        });
    }
}
