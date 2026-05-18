package com.mathfast.controller;

import com.mathfast.service.HealthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Autowired
    private HealthService healthService;

    @GetMapping
    public ResponseEntity<Map<String, String>> checkHealth() {
        Map<String, String> status = healthService.performHealthCheck();
        
        if ("DOWN".equals(status.get("status"))) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(status);
        }
        
        return ResponseEntity.ok(status);
    }
}
