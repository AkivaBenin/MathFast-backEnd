package com.mathfast.config;

import com.mathfast.exception.ApiException;
import com.mathfast.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final HandlerExceptionResolver handlerExceptionResolver;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.jwtUtil = jwtUtil;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = null;

        // 1. Check Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        }

        // 2. If not found in header, check cookies
        if (token == null && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("JWT".equals(cookie.getName()) && cookie.getValue() != null) {
                    token = cookie.getValue().trim();
                    break;
                }
            }
        }

        if (token == null || token.isEmpty() || "null".equalsIgnoreCase(token) || "undefined".equalsIgnoreCase(token) || token.split("\\.").length != 3) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtUtil.validateTokenAndGetClaims(token);
            String role = claims.get("role", String.class);
            if (role != null) {
                SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role.toUpperCase());
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(), null, Collections.singletonList(authority));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        } catch (Exception ex) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        // Intercept room creation and reset and enforce ROLE_TEACHER
        if ("POST".equalsIgnoreCase(request.getMethod()) && 
            (request.getRequestURI().equals("/api/rooms") || 
             request.getRequestURI().equals("/api/auth/rooms") || 
             request.getRequestURI().matches("/api/rooms/[^/]+/reset") || 
             request.getRequestURI().matches("/api/rooms/[^/]+/lobby-return"))) {
            
            boolean isTeacher = SecurityContextHolder.getContext().getAuthentication() != null &&
                    SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_TEACHER"));

            if (!isTeacher) {
                ApiException apiException = new ApiException("Forbidden: Only verified teachers can perform administrative room operations.", 403);
                handlerExceptionResolver.resolveException(request, response, null, apiException);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
