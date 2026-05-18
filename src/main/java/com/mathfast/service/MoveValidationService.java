package com.mathfast.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MoveValidationService {

    private final StringRedisTemplate redisTemplate;
    private final SseStreamService sseStreamService;
    private final RaceTerminationService raceTerminationService;
    private final MathEngineService mathEngineService;

    public MoveValidationService(StringRedisTemplate redisTemplate, SseStreamService sseStreamService, RaceTerminationService raceTerminationService, MathEngineService mathEngineService) {
        this.redisTemplate = redisTemplate;
        this.sseStreamService = sseStreamService;
        this.raceTerminationService = raceTerminationService;
        this.mathEngineService = mathEngineService;
    }

    public static class ValidationResult {
        public boolean success;
        public MathEngineService.QuestionDTO nextQuestion;
        public boolean expired;
        public String message;
        public Double newScore;

        public ValidationResult(boolean success, MathEngineService.QuestionDTO nextQuestion) {
            this(success, nextQuestion, false, success ? "SUCCESS" : "FAIL", null);
        }

        public ValidationResult(boolean success, MathEngineService.QuestionDTO nextQuestion, boolean expired, String message, Double newScore) {
            this.success = success;
            this.nextQuestion = nextQuestion;
            this.expired = expired;
            this.message = message;
            this.newScore = newScore;
        }
    }

    public List<Map<String, Object>> getRoomUsers(UUID raceId) {
        Set<String> roster = redisTemplate.opsForSet().members("roster:" + raceId);
        if (roster == null || roster.isEmpty()) return Collections.emptyList();
        
        List<Map<String, Object>> userList = new ArrayList<>();
        for (String nickname : roster) {
            Double score = redisTemplate.opsForZSet().score("race_leaderboard:" + raceId, nickname);
            long safeScore = score != null ? Math.round(score) : 0L;
            String prefix = "room:" + raceId + ":player:" + nickname + ":";
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
        return userList;
    }

    @Transactional
    public ValidationResult validateAndScoreMove(UUID raceId, String playerName, String nonce, int submittedAnswer, String pathDifficulty) {
        // Absolute Lockout: Drop all subsequent movement packets instantly upon state termination
        if ("FINISHED".equals(redisTemplate.opsForValue().get("room_state:" + raceId)) ||
            Boolean.TRUE.equals(redisTemplate.hasKey("race:" + raceId + ":terminated_lock"))) {
            return new ValidationResult(false, null, false, "FINISHED", null);
        }

        String prefix = "room:" + raceId + ":player:" + playerName + ":";
        String lockKey = prefix + "lock";
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "LOCKED", 2, TimeUnit.SECONDS);
        
        if (Boolean.FALSE.equals(locked)) {
            throw new IllegalStateException("Concurrent submission blocked");
        }

        try {
            // Check question expiration timer threshold
            String expStr = redisTemplate.opsForValue().get(prefix + "expires_at");
            if (expStr != null) {
                long expTime = Long.parseLong(expStr);
                if (System.currentTimeMillis() > expTime) {
                    // Expiration failure! Break active answer streaks, wipe streak multipliers, serve fresh question
                    redisTemplate.opsForValue().set(prefix + "streak", "0");
                    MathEngineService.QuestionDTO freshQ = mathEngineService.generateQuestion(raceId, playerName, pathDifficulty != null ? pathDifficulty : "REGULAR");
                    Double currScore = redisTemplate.opsForZSet().score("race_leaderboard:" + raceId, playerName);
                    return new ValidationResult(false, freshQ, true, "EXPIRED", currScore != null ? currScore : 0.0);
                }
            }

            String nonceKey = prefix + "nonce";
            String cachedNonce = redisTemplate.opsForValue().get(nonceKey);
            
            if (cachedNonce == null || !cachedNonce.equals(nonce)) {
                MathEngineService.QuestionDTO freshQ = mathEngineService.generateQuestion(raceId, playerName, pathDifficulty != null ? pathDifficulty : "REGULAR");
                Double currScore = redisTemplate.opsForZSet().score("race_leaderboard:" + raceId, playerName);
                return new ValidationResult(false, freshQ, false, "INVALID_NONCE", currScore != null ? currScore : 0.0);
            }
            redisTemplate.delete(nonceKey);

            String cachedAnswer = redisTemplate.opsForValue().get(prefix + "answer");
            if (cachedAnswer != null && Integer.parseInt(cachedAnswer) == submittedAnswer) {
                // Correct answer - Increment luck points
                String luckKey = prefix + "luck";
                String cachedLuck = redisTemplate.opsForValue().get(luckKey);
                int currentLuck = cachedLuck != null ? Integer.parseInt(cachedLuck) : 0;
                int newLuck = Math.min(100, currentLuck + 10);
                redisTemplate.opsForValue().set(luckKey, String.valueOf(newLuck));
                if (newLuck >= 100) {
                    redisTemplate.opsForValue().set(prefix + "item", "OIL_SLICK");
                }

                int minPoints = getMinPoints(pathDifficulty);
                int maxPoints = getMaxPoints(pathDifficulty);
                int luckVal = new java.util.Random().nextInt(101);
                int basePoints = minPoints + (int)((maxPoints - minPoints) * (luckVal / 100.0));

                // Check active stall penalty multiplier
                String stallVal = redisTemplate.opsForValue().get(prefix + "stall");
                double stallMultiplier = stallVal != null ? Double.parseDouble(stallVal) : 1.0;
                int earnedPoints = (int) Math.round(basePoints * stallMultiplier);

                // Latency calculation & Speed Demon evaluation
                String qTimeStr = redisTemplate.opsForValue().get(prefix + "q_time");
                long latency = qTimeStr != null ? (System.currentTimeMillis() - Long.parseLong(qTimeStr)) : (1500L + new java.util.Random().nextInt(2000));
                redisTemplate.opsForList().rightPush("race:" + raceId + ":human_latencies", String.valueOf(latency));
                if (redisTemplate.opsForList().size("race:" + raceId + ":human_latencies") > 50) {
                    redisTemplate.opsForList().leftPop("race:" + raceId + ":human_latencies");
                }

                String fastestStr = redisTemplate.opsForValue().get("race:" + raceId + ":fastest_time");
                long fastestTime = fastestStr != null ? Long.parseLong(fastestStr) : 999999L;
                if (latency < fastestTime) {
                    redisTemplate.opsForValue().set("race:" + raceId + ":fastest_time", String.valueOf(latency));
                    redisTemplate.opsForValue().set("race:" + raceId + ":fastest_player", playerName);
                }

                // Highway Hero evaluation
                if ("HIGHWAY".equalsIgnoreCase(pathDifficulty) || "HARD".equalsIgnoreCase(pathDifficulty)) {
                    Long hwCount = redisTemplate.opsForHash().increment("race:" + raceId + ":highway_counts", playerName, 1);
                    String maxHwStr = redisTemplate.opsForValue().get("race:" + raceId + ":max_highway_count");
                    long maxHw = maxHwStr != null ? Long.parseLong(maxHwStr) : 0;
                    if (hwCount != null && hwCount > maxHw) {
                        redisTemplate.opsForValue().set("race:" + raceId + ":max_highway_count", String.valueOf(hwCount));
                        redisTemplate.opsForValue().set("race:" + raceId + ":highway_hero", playerName);
                    }
                }

                // Update score in leaderboard
                Double currentScore = redisTemplate.opsForZSet().incrementScore("race_leaderboard:" + raceId, playerName, earnedPoints);

                // Check finish line threshold
                String winScoreStr = redisTemplate.opsForValue().get("race:" + raceId + ":win_score");
                int winScore = winScoreStr != null ? Integer.parseInt(winScoreStr) : 100;

                if (currentScore != null && currentScore >= winScore) {
                    redisTemplate.opsForSet().add("race:" + raceId + ":finishers", playerName);
                }

                // Broadcast synchronized roster update including individual player scores and vehicle colors
                List<Map<String, Object>> userList = getRoomUsers(raceId);
                sseStreamService.broadcastToRoom(raceId, "ROSTER_UPDATE", Map.of("users", userList));

                // Execute win condition evaluation
                raceTerminationService.checkWinCondition(raceId);

                // Forge fresh math question atomically
                MathEngineService.QuestionDTO nextQuestion = mathEngineService.generateQuestion(raceId, playerName, pathDifficulty != null ? pathDifficulty : "REGULAR");

                return new ValidationResult(true, nextQuestion, false, "SUCCESS", currentScore);
            } else {
                // Mistake penalty - Atomic decrement on luck points bounded by 0 floor
                String luckKey = prefix + "luck";
                String cachedLuck = redisTemplate.opsForValue().get(luckKey);
                int currentLuck = cachedLuck != null ? Integer.parseInt(cachedLuck) : 0;
                int newLuck = Math.max(0, currentLuck - 10);
                redisTemplate.opsForValue().set(luckKey, String.valueOf(newLuck));

                List<Map<String, Object>> userList = getRoomUsers(raceId);
                sseStreamService.broadcastToRoom(raceId, "ROSTER_UPDATE", Map.of("users", userList));

                MathEngineService.QuestionDTO freshQ = mathEngineService.generateQuestion(raceId, playerName, pathDifficulty != null ? pathDifficulty : "REGULAR");
                Double currScore = redisTemplate.opsForZSet().score("race_leaderboard:" + raceId, playerName);
                return new ValidationResult(false, freshQ, false, "INCORRECT", currScore != null ? currScore : 0.0);
            }
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    public Map<String, Object> executeSabotage(UUID raceId, String casterName, String itemType) {
        Set<String> roster = redisTemplate.opsForSet().members("roster:" + raceId);
        int totalPlayers = roster != null ? roster.size() : 1;

        if (totalPlayers <= 1) {
            return Map.of("success", false, "solo", true, "message", "No players found - item missed!");
        }

        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> top = 
            redisTemplate.opsForZSet().reverseRangeWithScores("race_leaderboard:" + raceId, 0, -1);
        String targetPlayer = null;
        if (top != null) {
            List<String> list = top.stream().map(t -> t.getValue()).toList();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) != null && list.get(i).equals(casterName)) {
                    if (i > 0) {
                        targetPlayer = list.get(i - 1);
                    } else if (list.size() > 1) {
                        for (String p : list) {
                            if (!p.equals(casterName)) { targetPlayer = p; break; }
                        }
                    }
                    break;
                }
            }
            if (targetPlayer == null && !list.isEmpty()) {
                for (String p : list) {
                    if (!p.equals(casterName)) { targetPlayer = p; break; }
                }
            }
        }
        if (targetPlayer == null) targetPlayer = "Rival Racer";

        // Reset caster's luck points to 0 after casting and remove item
        redisTemplate.opsForValue().set("room:" + raceId + ":player:" + casterName + ":luck", "0");
        redisTemplate.delete("room:" + raceId + ":player:" + casterName + ":item");

        // Mutate target's condition hash with stall penalty
        if (!"Rival Racer".equals(targetPlayer)) {
            redisTemplate.opsForValue().set("room:" + raceId + ":player:" + targetPlayer + ":stall", "0.25", 10, TimeUnit.SECONDS);
        }

        Map<String, Object> sabPayload = Map.of(
            "casterName", casterName,
            "from", casterName,
            "target", targetPlayer,
            "targetId", targetPlayer,
            "item", itemType != null ? itemType : "OIL_SLICK"
        );
        sseStreamService.broadcastToRoom(raceId, "PLAYER_SABOTAGED", sabPayload);
        sseStreamService.broadcastToRoom(raceId, "SABOTAGE_DEPLOYED", sabPayload);

        List<Map<String, Object>> userList = getRoomUsers(raceId);
        sseStreamService.broadcastToRoom(raceId, "ROSTER_UPDATE", Map.of("users", userList));

        return Map.of("success", true, "solo", false, "target", targetPlayer, "message", "You hit " + targetPlayer + "!");
    }
    
    private int getMinPoints(String diff) {
        if (diff == null) return 10;
        return diff.equalsIgnoreCase("HIGHWAY") || diff.equalsIgnoreCase("HARD") ? 25 : diff.equalsIgnoreCase("DIRT") ? 7 : 10;
    }

    private int getMaxPoints(String diff) {
        if (diff == null) return 12;
        return diff.equalsIgnoreCase("HIGHWAY") || diff.equalsIgnoreCase("HARD") ? 35 : diff.equalsIgnoreCase("DIRT") ? 7 : 12;
    }
}
