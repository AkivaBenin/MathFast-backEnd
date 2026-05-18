package com.mathfast.controller;

import com.mathfast.service.AuthService;
import com.mathfast.service.GameStateService;
import com.mathfast.service.SseStreamService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final GameStateService gameStateService;
    private final AuthService authService;
    private final StringRedisTemplate redisTemplate;
    private final SseStreamService sseStreamService;

    public RoomController(GameStateService gameStateService, AuthService authService, StringRedisTemplate redisTemplate, SseStreamService sseStreamService) {
        this.gameStateService = gameStateService;
        this.authService = authService;
        this.redisTemplate = redisTemplate;
        this.sseStreamService = sseStreamService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createRoom(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(authService.createRoom(request));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Void> startGame(@PathVariable UUID id, @CookieValue(value = "JWT", required = false) String jwtToken) {
        gameStateService.triggerGameStart(id, jwtToken);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leaveRoom(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> payload) {
        String nickname = payload != null && payload.get("nickname") != null ? payload.get("nickname") : "Guest";
        gameStateService.leaveRoom(id, nickname);
        String prefix = "room:" + id + ":player:" + nickname + ":";
        redisTemplate.delete(Arrays.asList(
            prefix + "color", prefix + "luck", prefix + "stall", prefix + "path", prefix + "item", prefix + "underdog", prefix + "swaps", prefix + "status", prefix + "streak", prefix + "nonce", prefix + "answer", prefix + "expires_at", prefix + "q_time"
        ));
        List<Map<String, Object>> userList = gameStateService.getRoomUsers(id);
        sseStreamService.broadcastToRoom(id, "ROSTER_UPDATE", Map.of("users", userList));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/color")
    public ResponseEntity<Void> updateColor(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        String nickname = payload.getOrDefault("nickname", "Guest");
        String color = payload.getOrDefault("color", "neon-blue");
        gameStateService.updatePlayerColor(id, nickname, color);
        return ResponseEntity.ok().build();
    }
}
