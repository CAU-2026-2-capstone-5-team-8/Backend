package com.cau.capstone8.backend.learning;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface LearningRecommendationRepository extends JpaRepository<LearningRecommendation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LearningRecommendation> findByUserIdAndRequestKey(long userId, String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LearningRecommendation r where r.id = :id")
    Optional<LearningRecommendation> findByIdForUpdate(long id);
}
