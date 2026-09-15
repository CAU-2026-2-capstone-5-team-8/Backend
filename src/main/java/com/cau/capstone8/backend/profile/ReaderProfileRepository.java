package com.cau.capstone8.backend.profile;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReaderProfileRepository extends JpaRepository<ReaderProfile, Long> {
    Optional<ReaderProfile> findBySessionId(long sessionId);

    @Query(value = """
            SELECT p.*
            FROM backend.reader_profile p
            JOIN backend.assessment_session s ON s.id = p.session_id
            WHERE s.user_id = :userId AND s.topic_id = :topicId AND s.status = 'COMPLETED'
            ORDER BY s.completed_at DESC, p.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ReaderProfile> findLatestCompleted(
            @Param("userId") long userId,
            @Param("topicId") long topicId);
}
