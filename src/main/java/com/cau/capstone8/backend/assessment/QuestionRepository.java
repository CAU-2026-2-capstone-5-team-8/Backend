package com.cau.capstone8.backend.assessment;

import java.util.List;
import java.util.Optional;
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

    List<Question> findByTopicIdAndActiveTrueOrderById(long topicId);

    List<Question> findByActiveTrueOrderById();

    Optional<Question> findByGeneratedQuestionId(String generatedQuestionId);
    @org.springframework.data.jpa.repository.Query(value = """
            select aq.question_id, count(*) from backend.assessment_question aq
            join backend.assessment_session s on s.id=aq.session_id
            join backend.assessment_answer a on a.assessment_question_id=aq.id
            where s.user_id=:userId and s.topic_id=:topicId group by aq.question_id
            """, nativeQuery = true)
    List<Object[]> answeredExposure(long userId, long topicId);
}
