package com.cau.capstone8.backend.book;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookFeatureRepository extends JpaRepository<BookFeature, Long> {
    List<BookFeature> findByTopicIdAndActiveTrueOrderByBookId(long topicId);

    Optional<BookFeature> findByBookIdAndTopicIdAndActiveTrue(long bookId, long topicId);
}
