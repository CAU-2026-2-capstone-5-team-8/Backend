package com.cau.capstone8.backend.book;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookFeatureRepository extends JpaRepository<BookFeature, Long> {
    @Query(value = "SELECT p.* FROM backend.book_feature p WHERE p.topic_id=:topicId AND p.active AND EXISTS (SELECT 1 FROM backend.catalog_visible_book_topic v WHERE v.book_id=p.book_id AND v.topic_id=p.topic_id) ORDER BY p.book_id", nativeQuery = true)
    List<BookFeature> findByTopicIdAndActiveTrueOrderByBookId(@Param("topicId") long topicId);

    @Query(value = "SELECT p.* FROM backend.book_feature p WHERE p.book_id=:bookId AND p.topic_id=:topicId AND p.active AND EXISTS (SELECT 1 FROM backend.catalog_visible_book_topic v WHERE v.book_id=p.book_id AND v.topic_id=p.topic_id)", nativeQuery = true)
    Optional<BookFeature> findByBookIdAndTopicIdAndActiveTrue(@Param("bookId") long bookId, @Param("topicId") long topicId);
}
