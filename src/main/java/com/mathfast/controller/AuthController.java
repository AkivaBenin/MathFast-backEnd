package com.mathfast.controller;

import com.mathfast.dto.AuthRequest;
import com.mathfast.dto.AuthResponse;
import com.mathfast.service.AuthService;
import com.mathfast.service.RegistrationService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RegistrationService registrationService;

    public AuthController(AuthService authService, RegistrationService registrationService) {
        this.authService = authService;
        this.registrationService = registrationService;
    }

    @PostMapping("/rooms")
    public ResponseEntity<Map<String, String>> createRoom(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(authService.createRoom(request));
    }

    @PostMapping("/teacher/register")
    public ResponseEntity<Map<String, String>> registerTeacher(@RequestBody AuthRequest.TeacherRegister request) {
        return ResponseEntity.ok(registrationService.registerTeacher(request));
    }

    @PostMapping("/teacher/login")
    public ResponseEntity<AuthResponse> teacherLogin(
            @RequestBody AuthRequest.TeacherLogin request,
            HttpServletResponse response) {

        AuthResponse authResponse = authService.loginTeacher(request);

        // Mod 8: Persistent cookie duration driven by rememberMe flag
        int cookieMaxAge = request.rememberMe
                ? 30 * 24 * 60 * 60   // 30 days
                : 7 * 24 * 60 * 60;   // 7 days

        Cookie cookie = new Cookie("JWT", authResponse.getToken());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(cookieMaxAge);
        response.addCookie(cookie);
        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/guest/join")
    public ResponseEntity<AuthResponse> guestJoin(
            @RequestBody AuthRequest.GuestJoin request,
            HttpServletResponse response) {

        AuthResponse authResponse = authService.joinGuest(request);
        Cookie cookie = new Cookie("JWT", authResponse.getToken());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(24 * 60 * 60);
        response.addCookie(cookie);
        return ResponseEntity.ok(authResponse);
    }
}
