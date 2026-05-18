package com.mathfast.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "room_code", unique = true, nullable = false)
    private String roomCode;

    @Enumerated(EnumType.STRING)
    private RoomStatus status = RoomStatus.LOBBY;

    private int targetQuestions;

    @ManyToOne
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @Transient
    public int getWinScore() {
        return targetQuestions * 10;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    public int getTargetQuestions() {
        return targetQuestions;
    }

    public void setTargetQuestions(int targetQuestions) {
        this.targetQuestions = targetQuestions;
    }

    public Teacher getTeacher() {
        return teacher;
    }

    public void setTeacher(Teacher teacher) {
        this.teacher = teacher;
    }
}
