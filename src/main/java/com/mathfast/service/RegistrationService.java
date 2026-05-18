package com.mathfast.service;

import com.mathfast.dto.AuthRequest;
import com.mathfast.entity.Teacher;
import com.mathfast.repository.TeacherRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class RegistrationService {

    private final TeacherRepository teacherRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(TeacherRepository teacherRepository, PasswordEncoder passwordEncoder) {
        this.teacherRepository = teacherRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Map<String, String> registerTeacher(AuthRequest.TeacherRegister request) {
        if (request == null || request.email == null || request.password == null || request.name == null) {
            throw new IllegalArgumentException("All registration fields (name, email, password) are mandatory.");
        }

        String email = request.email.trim();

        if (request.password.length() < 8
                || !request.password.matches(".*[A-Z].*")
                || !request.password.matches(".*[a-z].*")
                || !request.password.matches(".*\\d.*")) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, and one number.");
        }

        if (teacherRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email is already registered.");
        }

        Teacher teacher = new Teacher();
        teacher.setId(UUID.randomUUID());
        teacher.setNickname(request.name.trim());
        teacher.setEmail(email);
        teacher.setPasswordHash(passwordEncoder.encode(request.password));

        teacherRepository.save(teacher);

        return Map.of(
                "status", "SUCCESS",
                "message", "Teacher registered successfully.",
                "teacherId", teacher.getId().toString()
        );
    }
}
