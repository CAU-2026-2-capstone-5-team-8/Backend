package com.cau.capstone8.backend.learning;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningRecommendationRepository extends JpaRepository<LearningRecommendation, Long> {
    Optional<LearningRecommendation> findByUserIdAndRequestKey(long userId, String requestKey);
}
