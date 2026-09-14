package com.cau.capstone8.backend.book;

import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BookRepository extends JpaRepository<Book, Long> {
    // Native LIMIT/OFFSET accepts a long offset, including the largest valid page number.
    @Query(value = """
            SELECT b.* FROM backend.book b
            WHERE (:topicId IS NULL OR EXISTS (
              SELECT 1 FROM backend.book_topic bt WHERE bt.book_id=b.id AND bt.topic_id=:topicId))
            ORDER BY b.id LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Book> findCatalog(@Param("topicId") Long topicId, @Param("size") int size, @Param("offset") long offset);

    @Query(value = """
            SELECT count(*) FROM backend.book b
            WHERE (:topicId IS NULL OR EXISTS (
              SELECT 1 FROM backend.book_topic bt WHERE bt.book_id=b.id AND bt.topic_id=:topicId))
            """, nativeQuery = true)
    long countCatalog(@Param("topicId") Long topicId);

    @Query(value = """
            SELECT bt.book_id AS "bookId", t.id AS "topicId", t.code AS "code", t.name AS "name",
                   bt.is_primary AS "primary", bt.topic_weight AS "weight",
                   EXISTS(SELECT 1 FROM backend.book_feature f
                          WHERE f.book_id=bt.book_id AND f.topic_id=bt.topic_id AND f.active) AS "featureAvailable"
            FROM backend.book_topic bt JOIN backend.topic t ON t.id=bt.topic_id
            WHERE bt.book_id IN (:bookIds) ORDER BY bt.book_id,t.id
            """, nativeQuery = true)
    List<TopicMembership> findMemberships(@Param("bookIds") List<Long> bookIds);

    interface TopicMembership {
        Long getBookId();
        Long getTopicId();
        String getCode();
        String getName();
        boolean getPrimary();
        double getWeight();
        boolean getFeatureAvailable();
    }
}
