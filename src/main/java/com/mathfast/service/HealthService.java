package com.mathfast.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class HealthService {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public Map<String, String> performHealthCheck() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "UP");

        // Check MySQL
        try {
            Query query = entityManager.createNativeQuery("SELECT 1");
            query.getSingleResult();
            status.put("mysql", "CONNECTED");
        } catch (Exception e) {
            status.put("status", "DOWN");
            status.put("mysql", "FAILED: " + e.getMessage());
        }

        // Check Redis
        try {
            redisTemplate.opsForValue().set("health_check_key", "ok");
            String val = redisTemplate.opsForValue().get("health_check_key");
            if ("ok".equals(val)) {
                status.put("redis", "CONNECTED");
            } else {
                throw new RuntimeException("Value mismatch when reading from Redis");
            }
        } catch (Exception e) {
            status.put("status", "DOWN");
            status.put("redis", "FAILED: " + e.getMessage());
        }

        return status;
    }
}
