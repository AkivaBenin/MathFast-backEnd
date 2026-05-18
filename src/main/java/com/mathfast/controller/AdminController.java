package com.mathfast.controller;

import com.mathfast.service.RaceTerminationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * AdminController — Audit log endpoint permanently removed (Mod 3).
 * Only the room reset action is retained as an administrative tool.
 */
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api")
public class AdminController {

    private final RaceTerminationService raceTerminationService;

    public AdminController(RaceTerminationService raceTerminationService) {
        this.raceTerminationService = raceTerminationService;
    }

    @PreAuthorize("hasRole('TEACHER')")
    @PostMapping("/rooms/{id}/reset")
    public ResponseEntity<String> resetRoom(@PathVariable UUID id) {
        raceTerminationService.executeAdminReset(id);
        return ResponseEntity.ok("ROOM_CLEARED_AND_RESET");
    }

    @PreAuthorize("hasRole('TEACHER')")
    @PostMapping("/rooms/{id}/lobby-return")
    public ResponseEntity<String> returnToLobby(@PathVariable UUID id) {
        raceTerminationService.executeSoftLobbyReturn(id);
        return ResponseEntity.ok("ROOM_RETURNED_TO_LOBBY");
    }
}
