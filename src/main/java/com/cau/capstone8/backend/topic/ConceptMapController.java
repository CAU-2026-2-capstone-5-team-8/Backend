package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.book.BookRankingV2ProjectionRepository;
import com.cau.capstone8.backend.book.BookRepository;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.integration.ml.MlGateway;
import jakarta.validation.constraints.Positive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/topics/{topicId}/concept-map")
public class ConceptMapController {
    private final TopicRepository topics;
    private final MlGateway ml;
    private final BookRankingV2ProjectionRepository projections;
    private final BookRepository books;

    public ConceptMapController(TopicRepository topics, MlGateway ml,
                                BookRankingV2ProjectionRepository projections, BookRepository books) {
        this.topics = topics;
        this.ml = ml;
        this.projections = projections;
        this.books = books;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable @Positive long topicId,
                                  @RequestParam(required = false) @Positive Long bookId) {
        var topic = topics.findById(topicId)
                .orElseThrow(() -> new ResourceNotFoundException("분야를 찾을 수 없습니다."));
        Map<String, Object> result = new LinkedHashMap<>(ml.conceptGraph(topic.getMlTopicId()));
        result.put("topicDatabaseId", topicId);
        if (bookId != null) {
            var book = books.findById(bookId)
                    .orElseThrow(() -> new ResourceNotFoundException("도서를 찾을 수 없습니다."));
            var projection = projections.findByBookIdAndTopicIdAndActiveTrue(bookId, topicId);
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("id", bookId);
            scope.put("title", book.getTitle());
            scope.put("available", projection.isPresent());
            scope.put("coveredConcepts", projection
                    .map(p -> p.getCoveredConcepts().stream().map(c -> c.get("concept")).toList())
                    .orElse(List.of()));
            scope.put("depthStatus", "unverified");
            projection.ifPresent(p -> {
                scope.put("sourceArtifactVersion", p.getSourceArtifactVersion());
                scope.put("sourceArtifactHash", p.getSourceArtifactHash());
            });
            result.put("book", scope);
        }
        return result;
    }
}
