package com.cau.capstone8.backend.recommendation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    Optional<Feedback> findByUserIdAndRecommendationItemId(long userId, long recommendationItemId);
}
