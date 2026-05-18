package com.mathfast.controller;

import com.mathfast.service.AuthService;
import com.mathfast.service.GameStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final GameStateService gameStateService;
    private final AuthService authService;

    public RoomController(GameStateService gameStateService, AuthService authService) {
        this.gameStateService = gameStateService;
        this.authService = authService;
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
