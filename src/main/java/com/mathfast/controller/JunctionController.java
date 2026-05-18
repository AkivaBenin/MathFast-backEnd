package com.mathfast.controller;

import com.mathfast.service.JunctionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/race")
public class JunctionController {

    private final JunctionService junctionService;

    public JunctionController(JunctionService junctionService) {
        this.junctionService = junctionService;
    }

    @PostMapping("/{raceId}/junction/{playerId}")
    public ResponseEntity<String> submitJunctionChoice(
            @PathVariable UUID raceId, 
            @PathVariable UUID playerId, 
            @RequestParam String pathChoice) {
            
        boolean updated = junctionService.updatePlayerPath(raceId, playerId, pathChoice);
        return updated ? ResponseEntity.ok("PATH_UPDATED") : ResponseEntity.badRequest().body("INVALID_CHOICE");
    }

    @PostMapping("/{raceId}/stall/clear/{playerId}")
    public ResponseEntity<String> clearEngineStall(
            @PathVariable UUID raceId, 
            @PathVariable UUID playerId) {
            
        boolean cleared = junctionService.clearStallPenalty(raceId, playerId);
        return cleared ? ResponseEntity.ok("STALL_CLEARED") : ResponseEntity.badRequest().body("NO_ACTIVE_STALL");
    }

    @PostMapping("/{raceId}/swap/{playerId}")
    public ResponseEntity<String> useSwapToken(
            @PathVariable UUID raceId,
            @PathVariable UUID playerId) {
            
        boolean swapped = junctionService.processSwapToken(raceId, playerId);
        return swapped ? ResponseEntity.ok("QUESTION_SWAPPED") : ResponseEntity.badRequest().body("NO_TOKENS_OR_NOT_UNDERDOG");
    }
}
