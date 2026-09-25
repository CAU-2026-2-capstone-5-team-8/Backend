package com.cau.capstone8.backend.book;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRankingV2ProjectionRepository
        extends JpaRepository<BookRankingV2Projection, Long> {
    List<BookRankingV2Projection> findByTopicIdAndActiveTrueOrderByBookId(long topicId);

    Optional<BookRankingV2Projection> findByBookIdAndTopicIdAndActiveTrue(long bookId, long topicId);
}
