package com.cau.capstone8.backend.book;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookFeatureRepository extends JpaRepository<BookFeature, Long> {
    // Candidate pool for Top-K: books directly linked to the topic that also carry an active feature.
    @Query(value = """
            SELECT bf.* FROM backend.book_feature bf
            JOIN backend.book_topic bt ON bt.book_id = bf.book_id AND bt.topic_id = bf.topic_id
            WHERE bf.topic_id = :topicId AND bf.active
            ORDER BY bf.book_id
            """, nativeQuery = true)
    List<BookFeature> findActiveCandidates(@Param("topicId") long topicId);

    // Total books directly linked to the topic, feature or not, to report how many candidates were excluded.
    @Query(value = "SELECT count(*) FROM backend.book_topic WHERE topic_id = :topicId", nativeQuery = true)
    long countBooksInTopic(@Param("topicId") long topicId);

    Optional<BookFeature> findByBookIdAndTopicIdAndActiveTrue(long bookId, long topicId);
}
