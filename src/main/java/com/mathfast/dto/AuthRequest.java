package com.mathfast.dto;

public class AuthRequest {

    public static class TeacherLogin {
        public String email;
        public String password;
        public boolean rememberMe;
    }

    public static class TeacherRegister {
        public String name;
        public String email;
        public String password;
    }

    public static class GuestJoin {
        public String roomId;
        public String roomCode;
        public String nickname;
    }
}
