package com.mathfast.controller;

import com.mathfast.dto.MoveRequest;
import com.mathfast.service.MoveValidationService;
import com.mathfast.util.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/race")
public class MoveController {

    private final MoveValidationService moveValidationService;
    private final JwtUtil jwtUtil;

    public MoveController(MoveValidationService moveValidationService, JwtUtil jwtUtil) {
        this.moveValidationService = moveValidationService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/{id}/move")
    public ResponseEntity<Map<String, Object>> submitMove(
            @PathVariable UUID id, 
            @RequestBody MoveRequest request,
            @CookieValue(value = "JWT", required = false) String jwtToken) {
        
        String playerName = (jwtToken != null && jwtUtil.validateTokenAndGetClaims(jwtToken) != null) 
                ? jwtUtil.validateTokenAndGetClaims(jwtToken).getSubject() 
                : (request.playerId != null ? request.playerId : "Guest");
                
        MoveValidationService.ValidationResult res = moveValidationService.validateAndScoreMove(
            id, playerName, request.nonce, request.answer, request.difficulty != null ? request.difficulty : "REGULAR"
        );
        
        if (res.success) {
            Map<String, Object> map = new HashMap<>();
            map.put("status", "CORRECT");
            map.put("success", true);
            map.put("score", res.newScore);
            if (res.nextQuestion != null) {
                map.put("nextQuestion", res.nextQuestion);
            }
            return ResponseEntity.ok(map);
        } else if (res.expired) {
            Map<String, Object> map = new HashMap<>();
            map.put("status", "EXPIRED");
            map.put("success", false);
            map.put("expired", true);
            map.put("score", res.newScore);
            if (res.nextQuestion != null) {
                map.put("nextQuestion", res.nextQuestion);
            }
            return ResponseEntity.ok(map);
        } else {
            Map<String, Object> map = new HashMap<>();
            map.put("status", "INCORRECT_OR_INVALID");
            map.put("success", false);
            map.put("score", res.newScore);
            if (res.nextQuestion != null) {
                map.put("nextQuestion", res.nextQuestion);
            }
            return ResponseEntity.ok(map);
        }
    }
}
