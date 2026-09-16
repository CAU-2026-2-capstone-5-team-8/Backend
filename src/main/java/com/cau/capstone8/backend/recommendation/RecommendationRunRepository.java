package com.cau.capstone8.backend.recommendation;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationRunRepository extends JpaRepository<RecommendationRun, Long> {
    Optional<RecommendationRun> findByUserIdAndRequestKey(long userId, String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecommendationRun r where r.userId = :userId and r.requestKey = :requestKey")
    Optional<RecommendationRun> findByUserIdAndRequestKeyForUpdate(
            @Param("userId") long userId, @Param("requestKey") String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecommendationRun r where r.id = :id")
    Optional<RecommendationRun> findByIdForUpdate(@Param("id") long id);
}
