package com.cau.capstone8.backend.assessment;

import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    // Evenly samples active questions per measurement area so a session always
    // covers vocabulary, background knowledge and comprehension together.
    @Query(value = """
            SELECT * FROM (
              SELECT q.*, row_number() OVER (
                PARTITION BY q.measurement_area ORDER BY random()) AS rn
              FROM backend.question q
              WHERE q.topic_id = :topicId AND q.active
            ) ranked
            WHERE rn <= :perArea
            ORDER BY measurement_area, rn
            """, nativeQuery = true)
    List<Question> sampleActiveByTopic(@Param("topicId") long topicId, @Param("perArea") int perArea);

    long countByTopicIdAndActiveTrue(long topicId);
}
