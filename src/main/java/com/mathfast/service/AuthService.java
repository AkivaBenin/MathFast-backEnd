package com.mathfast.service;

import com.mathfast.dto.AuthRequest;
import com.mathfast.dto.AuthResponse;
import com.mathfast.entity.Teacher;
import com.mathfast.repository.TeacherRepository;
import com.mathfast.util.JwtUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AuthService {

    private final StringRedisTemplate redisTemplate;
    private final SseStreamService sseStreamService;
    private final TeacherRepository teacherRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(StringRedisTemplate redisTemplate, SseStreamService sseStreamService,
                       TeacherRepository teacherRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.redisTemplate = redisTemplate;
        this.sseStreamService = sseStreamService;
        this.teacherRepository = teacherRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public Map<String, String> createRoom(Map<String, Object> request) {
        UUID roomId = UUID.randomUUID();

        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder codeBuilder = new StringBuilder();
        Random rnd = new Random();
        for (int i = 0; i < 6; i++) {
            codeBuilder.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        String roomCode = codeBuilder.toString();

        int targetQuestions = 10;
        if (request != null && request.get("targetQuestions") != null) {
            targetQuestions = Integer.parseInt(request.get("targetQuestions").toString());
        }
        int winScore = targetQuestions * 10;

        redisTemplate.opsForValue().set("room:" + roomId, "LOBBY");
        redisTemplate.opsForValue().set("room_code:" + roomCode, roomId.toString());
        redisTemplate.opsForValue().set("race:" + roomId + ":win_score", String.valueOf(winScore));

        return Map.of("roomId", roomId.toString(), "id", roomId.toString(), "roomCode", roomCode);
    }

    @Transactional(readOnly = true)
    public AuthResponse loginTeacher(AuthRequest.TeacherLogin request) {
        if (request == null || request.email == null || request.password == null) {
            throw new IllegalArgumentException("Email and password are required.");
        }
        Teacher teacher = teacherRepository.findByEmail(request.email.trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password."));

        if (!passwordEncoder.matches(request.password, teacher.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password.");
        }

        // Mod 8 — Remember Me: 30-day token when rememberMe=true, else 7-day default
        long expirationMs = request.rememberMe
                ? 30L * 24 * 3600 * 1000
                : 7L * 24 * 3600 * 1000;

        String token = jwtUtil.generateToken(teacher.getEmail(), "TEACHER", expirationMs);
        return new AuthResponse(token, "TEACHER");
    }

    public AuthResponse joinGuest(AuthRequest.GuestJoin request) {
        String inputCode = request.roomId != null
                ? request.roomId.trim().toUpperCase()
                : (request.roomCode != null ? request.roomCode.trim().toUpperCase() : "");

        String actualRoomIdStr = redisTemplate.opsForValue().get("room_code:" + inputCode);
        if (actualRoomIdStr == null && Boolean.TRUE.equals(redisTemplate.hasKey("room:" + inputCode))) {
            actualRoomIdStr = inputCode;
        }

        if (actualRoomIdStr == null) {
            throw new IllegalArgumentException("No room found with that ID. Please check the code and try again.");
        }

        String rosterKey = "roster:" + actualRoomIdStr;
        Long currentCount = redisTemplate.opsForSet().size(rosterKey);
        if (currentCount != null && currentCount >= 8) {
            throw new IllegalArgumentException("Lobby is full. Maximum 8 players allowed per race.");
        }

        String user = request.nickname != null ? request.nickname : "Player_" + (new Random().nextInt(1000));
        redisTemplate.opsForSet().add(rosterKey, user);

        Set<String> users = redisTemplate.opsForSet().members(rosterKey);
        if (users != null) {
            List<Map<String, Object>> userList = users.stream()
                    .map(u -> Map.<String, Object>of("id", u, "nickname", u, "score", 0))
                    .toList();
            sseStreamService.broadcastToRoom(UUID.fromString(actualRoomIdStr), "ROSTER_UPDATE", Map.of("users", userList));
        }

        String token = jwtUtil.generateToken(user, "GUEST", 24L * 3600 * 1000);
        return new AuthResponse(token, "GUEST", actualRoomIdStr, inputCode);
    }
}
