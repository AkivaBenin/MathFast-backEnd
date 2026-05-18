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
            
            String jPendingStr = redisTemplate.opsForValue().get(prefix + "junctionPending");
            boolean junctionPending = "true".equalsIgnoreCase(jPendingStr);
            uMap.put("junctionPending", junctionPending);
            
            String isActiveStr = (String) redisTemplate.opsForHash().get("room:" + raceId + ":player:" + nickname + ":status", "isActive");
            boolean isActive = isActiveStr == null || Boolean.parseBoolean(isActiveStr);
            uMap.put("isActive", isActive);
            
            userList.add(uMap);
        }
        return userList;
    }

    @Transactional
    public ValidationResult validateAndScoreMove(UUID raceId, String playerName, String nonce, int submittedAnswer, String pathDifficulty) {
        if ("FINISHED".equals(redisTemplate.opsForValue().get("room_state:" + raceId)) ||
            Boolean.TRUE.equals(redisTemplate.hasKey("race:" + raceId + ":terminated_lock"))) {
            return new ValidationResult(false, null, false, "FINISHED", null);
        }

        String prefix = "room:" + raceId + ":player:" + playerName + ":";
        String lockKey = prefix + "lock";
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "LOCKED", 3, TimeUnit.SECONDS);
        
        if (Boolean.FALSE.equals(locked)) {
            throw new IllegalStateException("Concurrent submission blocked");
        }

        try {
            String qPrefix = prefix + "q:" + nonce + ":";
            String cachedAnswer = redisTemplate.opsForValue().get(qPrefix + "answer");
            String qExpStr = redisTemplate.opsForValue().get(qPrefix + "expires_at");
            String qTimeStr = redisTemplate.opsForValue().get(qPrefix + "q_time");

            if (cachedAnswer == null) {
                cachedAnswer = redisTemplate.opsForValue().get(prefix + "answer");
                qExpStr = redisTemplate.opsForValue().get(prefix + "expires_at");
                qTimeStr = redisTemplate.opsForValue().get(prefix + "q_time");
                String cachedNonce = redisTemplate.opsForValue().get(prefix + "nonce");
                if (cachedNonce == null || !cachedNonce.equals(nonce)) {
                    MathEngineService.QuestionDTO freshQ = mathEngineService.generateQuestion(raceId, playerName, pathDifficulty != null ? pathDifficulty : "REGULAR");
                    Double currScore = redisTemplate.opsForZSet().score("race_leaderboard:" + raceId, playerName);
                    return new ValidationResult(false, freshQ, false, "INVALID_NONCE", currScore != null ? currScore : 0.0);
                }
            }

            if (qExpStr != null) {
                long expTime = Long.parseLong(qExpStr);
                if (System.currentTimeMillis() > expTime) {
                    redisTemplate.opsForValue().set(prefix + "streak", "0");
                    MathEngineService.QuestionDTO freshQ = mathEngineService.generateQuestion(raceId, playerName, pathDifficulty != null ? pathDifficulty : "REGULAR");
                    Double currScore = redisTemplate.opsForZSet().score("race_leaderboard:" + raceId, playerName);
                    return new ValidationResult(false, freshQ, true, "EXPIRED", currScore != null ? currScore : 0.0);
                }
            }

            redisTemplate.delete(java.util.Arrays.asList(qPrefix + "answer", qPrefix + "expires_at", qPrefix + "q_time", prefix + "nonce"));

            if (Integer.parseInt(cachedAnswer) == submittedAnswer) {
                String luckKey = prefix + "luck";
                String cachedLuck = redisTemplate.opsForValue().get(luckKey);
                int currentLuck = cachedLuck != null ? Integer.parseInt(cachedLuck) : 0;
                int newLuck = Math.min(100, currentLuck + 10);
                redisTemplate.opsForValue().set(luckKey, String.valueOf(newLuck));
                if (newLuck >= 100) {
                    redisTemplate.opsForValue().set(prefix + "item", "OIL_SLICK");
                }

                String safeDiff = pathDifficulty != null ? pathDifficulty.trim().toUpperCase() : "REGULAR";
                int minPoints = getMinPoints(safeDiff);
                int maxPoints = getMaxPoints(safeDiff);
                int basePoints = minPoints == maxPoints ? minPoints : minPoints + (int)((maxPoints - minPoints) * (new java.util.Random().nextInt(101) / 100.0));

                String stallVal = redisTemplate.opsForValue().get(prefix + "stall");
                double stallMultiplier = stallVal != null ? Double.parseDouble(stallVal) : 1.0;
                int earnedPoints = (int) Math.round(basePoints * stallMultiplier);

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

                if ("HIGHWAY".equalsIgnoreCase(pathDifficulty) || "HARD".equalsIgnoreCase(pathDifficulty)) {
                    Long hwCount = redisTemplate.opsForHash().increment("race:" + raceId + ":highway_counts", playerName, 1);
                    String maxHwStr = redisTemplate.opsForValue().get("race:" + raceId + ":max_highway_count");
                    long maxHw = maxHwStr != null ? Long.parseLong(maxHwStr) : 0;
                    if (hwCount != null && hwCount > maxHw) {
                        redisTemplate.opsForValue().set("race:" + raceId + ":max_highway_count", String.valueOf(hwCount));
                        redisTemplate.opsForValue().set("race:" + raceId + ":highway_hero", playerName);
                    }
                }

                Double oldScore = redisTemplate.opsForZSet().score("race_leaderboard:" + raceId, playerName);
                double prevScore = oldScore != null ? oldScore : 0.0;
                Double currentScore = redisTemplate.opsForZSet().incrementScore("race_leaderboard:" + raceId, playerName, earnedPoints);

                String winScoreStr = redisTemplate.opsForValue().get("race:" + raceId + ":win_score");
                int winScore = winScoreStr != null ? Integer.parseInt(winScoreStr) : 100;

                double prevProgress = prevScore / (double) winScore;
                double currProgress = (currentScore != null ? currentScore : prevScore) / (double) winScore;

                String jFiredStr = redisTemplate.opsForValue().get(prefix + "junctionsFired");
                int junctionsFired = jFiredStr != null ? Integer.parseInt(jFiredStr) : 0;

                if (junctionsFired < 2 && ((prevProgress < 0.3 && currProgress >= 0.3) || (prevProgress < 0.6 && currProgress >= 0.6))) {
                    redisTemplate.opsForValue().set(prefix + "junctionPending", "true");
                    redisTemplate.opsForValue().set(prefix + "junctionsFired", String.valueOf(junctionsFired + 1));
                }

                if (currentScore != null && currentScore >= winScore) {
                    redisTemplate.opsForSet().add("race:" + raceId + ":finishers", playerName);
                }

                List<Map<String, Object>> userList = getRoomUsers(raceId);
                sseStreamService.broadcastToRoom(raceId, "ROSTER_UPDATE", Map.of("users", userList));

                raceTerminationService.checkWinCondition(raceId);

                MathEngineService.QuestionDTO nextQuestion = mathEngineService.generateQuestion(raceId, playerName, pathDifficulty != null ? pathDifficulty : "REGULAR");

                return new ValidationResult(true, nextQuestion, false, "SUCCESS", currentScore);
            } else {
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
        if (diff == null || diff.trim().isEmpty()) return 10;
        String d = diff.trim().toUpperCase();
        if (d.equals("REGULAR") || d.equals("EASY")) return 10;
        return d.equals("HIGHWAY") || d.equals("HARD") ? 25 : d.equals("DIRT") ? 7 : 10;
    }

    private int getMaxPoints(String diff) {
        if (diff == null || diff.trim().isEmpty()) return 10;
        String d = diff.trim().toUpperCase();
        if (d.equals("REGULAR") || d.equals("EASY")) return 10;
        return d.equals("HIGHWAY") || d.equals("HARD") ? 35 : d.equals("DIRT") ? 7 : 10;
    }
}
