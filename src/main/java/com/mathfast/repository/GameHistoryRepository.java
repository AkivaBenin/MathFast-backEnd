package com.mathfast.repository;

import com.mathfast.entity.GameHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GameHistoryRepository extends JpaRepository<GameHistory, UUID> {
    List<GameHistory> findByRaceIdOrderByRankPositionAsc(UUID raceId);
}
