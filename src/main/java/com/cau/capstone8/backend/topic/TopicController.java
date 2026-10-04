package com.cau.capstone8.backend.topic;

import java.util.List;
import com.cau.capstone8.backend.book.CatalogSummaryService;
import com.cau.capstone8.backend.assessment.ConceptQuestionSelection;
import com.cau.capstone8.backend.assessment.Question;
import com.cau.capstone8.backend.assessment.QuestionRepository;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/topics")
public class TopicController {
    private final TopicRepository topics;
    private final CatalogSummaryService catalog;
    private final QuestionRepository questions;
    public TopicController(TopicRepository topics, CatalogSummaryService catalog, QuestionRepository questions) {
        this.topics = topics; this.catalog = catalog; this.questions = questions;
    }

    @GetMapping
    public List<TopicResponse> list() {
        var ready = catalog.readyTopicIds();
        var banks = questions.findByActiveTrueOrderById().stream().collect(Collectors.groupingBy(Question::getTopicId));
        return topics.findAllByOrderByIdAsc().stream()
                .map(t -> new TopicResponse(
                        t.getId(), t.getCode(), t.getName(), t.getMlTopicId(), t.getParentId(),ready.contains(t.getId()),
                        ConceptQuestionSelection.select(banks.getOrDefault(t.getId(), List.of()), 9).size() == 9)).toList();
    }
    public record TopicResponse(long id, String code, String name, String mlTopicId, Long parentId,
                                boolean assessmentReady, boolean conceptAssessmentReady) {}
}
