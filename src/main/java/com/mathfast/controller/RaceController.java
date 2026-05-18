package com.mathfast.controller;

import com.mathfast.entity.GameHistory;
import com.mathfast.repository.GameHistoryRepository;
import com.mathfast.service.SseStreamService;
import com.mathfast.service.MathEngineService;
import com.mathfast.service.MoveValidationService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequestMapping("/api/race")
public class RaceController {

    private final SseStreamService sseStreamService;
    private final GameHistoryRepository gameHistoryRepository;
    private final StringRedisTemplate redisTemplate;
    private final MathEngineService mathEngineService;
    private final MoveValidationService moveValidationService;

    public RaceController(SseStreamService sseStreamService, GameHistoryRepository gameHistoryRepository, StringRedisTemplate redisTemplate, MathEngineService mathEngineService, MoveValidationService moveValidationService) {
        this.sseStreamService = sseStreamService;
        this.gameHistoryRepository = gameHistoryRepository;
        this.redisTemplate = redisTemplate;
        this.mathEngineService = mathEngineService;
        this.moveValidationService = moveValidationService;
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRace(@PathVariable UUID id, @CookieValue(value = "JWT", required = false) String jwtToken) {
        return sseStreamService.createConnection(id, jwtToken);
    }

    @GetMapping("/{id}/question")
    public ResponseEntity<MathEngineService.QuestionDTO> getQuestion(
            @PathVariable UUID id,
            @RequestParam String playerName,
            @RequestParam(defaultValue = "REGULAR") String difficulty) {
        return ResponseEntity.ok(mathEngineService.generateQuestion(id, playerName, difficulty));
    }

    @PostMapping("/{id}/sabotage")
    public ResponseEntity<Map<String, Object>> deploySabotage(@PathVariable UUID id, @RequestBody Map<String, Object> payload) {
        String attacker = payload.containsKey("attacker") ? String.valueOf(payload.get("attacker")) : (payload.containsKey("playerId") ? String.valueOf(payload.get("playerId")) : "Rival Racer");
        String item = payload.containsKey("item") ? String.valueOf(payload.get("item")) : "Oil Slick";
        moveValidationService.executeSabotage(id, attacker, item);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/item")
    public ResponseEntity<Map<String, Object>> deployItem(@PathVariable UUID id, @RequestBody Map<String, Object> payload) {
        String item = payload.containsKey("item") ? String.valueOf(payload.get("item")) : "TURBO_CHARGE";
        String attacker = payload.containsKey("attacker") ? String.valueOf(payload.get("attacker")) : (payload.containsKey("playerId") ? String.valueOf(payload.get("playerId")) : "Rival Racer");
        
        if ("OIL_SLICK".equalsIgnoreCase(item)) {
            moveValidationService.executeSabotage(id, attacker, "Oil Slick");
        } else {
            sseStreamService.broadcastToRoom(id, "OVERTAKE", Map.of(
                "message", "Turbo Deployed by " + attacker + "!"
            ));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/path")
    public ResponseEntity<Map<String, Object>> updatePath(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        String playerName = payload.getOrDefault("playerId", "Guest");
        String path = payload.getOrDefault("path", "REGULAR");
        redisTemplate.opsForValue().set("room:" + id + ":player:" + playerName + ":path", path);
        return ResponseEntity.ok(Map.of("success", true, "path", path));
    }

    @PostMapping("/{id}/clear-stall")
    public ResponseEntity<Map<String, Object>> clearStall(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> payload) {
        String playerName = payload != null && payload.get("playerId") != null ? payload.get("playerId") : "Guest";
        redisTemplate.delete("room:" + id + ":player:" + playerName + ":stall");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/swap")
    public ResponseEntity<Map<String, Object>> swapQuestion(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/luck")
    public ResponseEntity<Map<String, Object>> injectLuck(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/{id}/results")
    public ResponseEntity<Map<String, Object>> getRaceResults(@PathVariable UUID id) {
        List<GameHistory> history = gameHistoryRepository.findByRaceIdOrderByRankPositionAsc(id);
        
        List<Map<String, Object>> winners = new ArrayList<>();
        String fastestPlayer = redisTemplate.opsForValue().get("race:" + id + ":fastest_player");
        String fastestTimeStr = redisTemplate.opsForValue().get("race:" + id + ":fastest_time");
        String highwayHero = redisTemplate.opsForValue().get("race:" + id + ":highway_hero");
        String maxHwStr = redisTemplate.opsForValue().get("race:" + id + ":max_highway_count");

        if (history != null && !history.isEmpty()) {
            for (GameHistory gh : history) {
                winners.add(Map.of(
                    "name", gh.getPlayerName(),
                    "score", gh.getFinalScore(),
                    "rank", gh.getRankPosition(),
                    "isBot", gh.isBot()
                ));
                if (gh.isSpeedDemon() && fastestPlayer == null) fastestPlayer = gh.getPlayerName();
                if (gh.isHighwayHero() && highwayHero == null) highwayHero = gh.getPlayerName();
            }
        } else {
            // Fallback to Redis if DB replication/transaction still committing
            Set<TypedTuple<String>> leaderBoard = redisTemplate.opsForZSet().reverseRangeWithScores("race_leaderboard:" + id, 0, -1);
            if (leaderBoard != null) {
                int rank = 1;
                for (TypedTuple<String> tuple : leaderBoard) {
                    winners.add(Map.of(
                        "name", tuple.getValue() != null ? tuple.getValue() : "Unknown",
                        "score", tuple.getScore() != null ? Math.round(tuple.getScore()) : 0,
                        "rank", rank++,
                        "isBot", false
                    ));
                }
            }
        }

        Map<String, Object> analyticsData = Map.of(
            "fastestPlayer", fastestPlayer != null ? fastestPlayer : "N/A",
            "fastestTime", fastestTimeStr != null ? fastestTimeStr : "0",
            "highwayHero", highwayHero != null ? highwayHero : "N/A",
            "hardQuestionsCleared", maxHwStr != null ? maxHwStr : "0"
        );

        return ResponseEntity.ok(Map.of("winners", winners, "analyticsData", analyticsData));
    }
}
