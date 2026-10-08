package com.cau.capstone8.backend.book;

import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BookRepository extends JpaRepository<Book, Long> {
    @Query("select b.updatedAt from Book b where b.id = :id")
    java.util.Optional<java.time.Instant> findMetadataVersion(@Param("id") long id);

    // Native LIMIT/OFFSET accepts a long offset, including the largest valid page number.
    @Query(value = """
            SELECT b.* FROM backend.catalog_visible_book b
            WHERE (:topicId IS NULL OR EXISTS (
              SELECT 1 FROM backend.catalog_visible_book_topic bt WHERE bt.book_id=b.id AND bt.topic_id=:topicId))
            ORDER BY b.id LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Book> findCatalog(@Param("topicId") Long topicId, @Param("size") int size, @Param("offset") long offset);

    @Query(value = """
            SELECT count(*) FROM backend.catalog_visible_book b
            WHERE (:topicId IS NULL OR EXISTS (
              SELECT 1 FROM backend.catalog_visible_book_topic bt WHERE bt.book_id=b.id AND bt.topic_id=:topicId))
            """, nativeQuery = true)
    long countCatalog(@Param("topicId") Long topicId);

    @Query(value = """
            SELECT bt.book_id AS "bookId", t.id AS "topicId", t.code AS "code", t.name AS "name",
                   bt.is_primary AS "primary", bt.topic_weight AS "weight",
                   EXISTS(SELECT 1 FROM backend.book_feature f
                          WHERE f.book_id=bt.book_id AND f.topic_id=bt.topic_id AND f.active) AS "featureAvailable",
                   coverage.toc_entry_count AS "tocEntryCount",
                   (p.id IS NOT NULL) AS "rankingCandidate",
                   jsonb_array_length(p.covered_concepts) AS "coveredConceptCount"
            FROM backend.book_topic bt JOIN backend.topic t ON t.id=bt.topic_id
            LEFT JOIN backend.book_ranking_v2_projection p ON p.book_id=bt.book_id AND p.topic_id=bt.topic_id AND p.active
            LEFT JOIN LATERAL (
                SELECT m.toc_entry_count FROM backend.discovery_catalog_member m
                JOIN backend.discovery_catalog_import i ON i.snapshot_id=m.snapshot_id
                WHERE m.book_id=bt.book_id AND m.topic_id=bt.topic_id
                ORDER BY i.created_at DESC,i.snapshot_id DESC LIMIT 1
            ) coverage ON true
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
        Integer getTocEntryCount();
        boolean getRankingCandidate();
        Integer getCoveredConceptCount();
    }
}
