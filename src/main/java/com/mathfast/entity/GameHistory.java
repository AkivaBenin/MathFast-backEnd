package com.mathfast.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "game_history")
public class GameHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "race_id", columnDefinition = "binary(16)", nullable = false)
    private UUID raceId;

    @Column(name = "player_id", nullable = false)
    private String playerId;

    @Column(name = "player_name", nullable = false)
    private String playerName;

    @Column(name = "final_score", nullable = false)
    private int finalScore;

    @Column(name = "rank_position")
    private int rankPosition;

    @Column(name = "is_bot")
    private boolean isBot;

    @Column(name = "speed_demon")
    private boolean speedDemon;

    @Column(name = "highway_hero")
    private boolean highwayHero;

    @Column(name = "finished_at", nullable = false)
    private LocalDateTime finishedAt;

    public GameHistory() {}

    public GameHistory(UUID raceId, String playerId, String playerName, int finalScore, int rankPosition, boolean isBot, boolean speedDemon, boolean highwayHero, LocalDateTime finishedAt) {
        this.raceId = raceId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.finalScore = finalScore;
        this.rankPosition = rankPosition;
        this.isBot = isBot;
        this.speedDemon = speedDemon;
        this.highwayHero = highwayHero;
        this.finishedAt = finishedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getRaceId() { return raceId; }
    public void setRaceId(UUID raceId) { this.raceId = raceId; }

    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public int getFinalScore() { return finalScore; }
    public void setFinalScore(int finalScore) { this.finalScore = finalScore; }

    public int getRankPosition() { return rankPosition; }
    public void setRankPosition(int rankPosition) { this.rankPosition = rankPosition; }

    public boolean isBot() { return isBot; }
    public void setBot(boolean bot) { isBot = bot; }

    public boolean isSpeedDemon() { return speedDemon; }
    public void setSpeedDemon(boolean speedDemon) { this.speedDemon = speedDemon; }

    public boolean isHighwayHero() { return highwayHero; }
    public void setHighwayHero(boolean highwayHero) { this.highwayHero = highwayHero; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
