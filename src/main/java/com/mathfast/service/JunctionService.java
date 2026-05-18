package com.mathfast.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class JunctionService {

    private final StringRedisTemplate redisTemplate;
    private final SseStreamService sseStreamService;

    public JunctionService(StringRedisTemplate redisTemplate, SseStreamService sseStreamService) {
        this.redisTemplate = redisTemplate;
        this.sseStreamService = sseStreamService;
    }

    public boolean updatePlayerPath(UUID raceId, UUID playerId, String pathChoice) {
        if (!pathChoice.matches("DIRT|REGULAR|HIGHWAY")) return false;
        
        String pathKey = "player:" + playerId + ":path";
        redisTemplate.opsForValue().set(pathKey, pathChoice);
        
        // Resume clock and stream
        sseStreamService.broadcastToRoom(raceId, "JUNCTION_RESOLVED", playerId);
        return true;
    }

    public boolean clearStallPenalty(UUID raceId, UUID playerId) {
        String stallKey = "player:" + playerId + ":stall";
        if (Boolean.TRUE.equals(redisTemplate.hasKey(stallKey))) {
            redisTemplate.delete(stallKey);
            sseStreamService.broadcastToRoom(raceId, "STALL_CLEARED_EVENT", playerId);
            return true;
        }
        return false;
    }

    public boolean processSwapToken(UUID raceId, UUID playerId) {
        String swapKey = "player:" + playerId + ":swaps";
        String val = redisTemplate.opsForValue().get(swapKey);
        int swaps = val != null ? Integer.parseInt(val) : 0;

        // Check if under dog criteria met (abstracted logic)
        boolean isUnderdog = true; 

        if (swaps > 0 && isUnderdog) {
            redisTemplate.opsForValue().decrement(swapKey);
            sseStreamService.broadcastToRoom(raceId, "QUESTION_SWAPPED_EVENT", playerId);
            return true;
        }
        return false;
    }
}
