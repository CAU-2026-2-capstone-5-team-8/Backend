package com.cau.capstone8.backend.assessment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local reviewer preview only. This endpoint never persists questions or answers. */
@RestController
@Profile("local")
@ConditionalOnProperty(name = "assessment.preview-dir")
@RequestMapping("/api/assessments/concept-preview")
class ConceptQuestionPreviewController {
    private final Path directory;
    private final ApprovedQuestionImportService importer;
    ConceptQuestionPreviewController(@Value("${assessment.preview-dir}") String directory,
                                     ApprovedQuestionImportService importer) {
        this.directory = Path.of(directory);
        this.importer = importer;
    }

    private List<GeneratedQuestionV5Handoff> candidates() {
        try (var paths = Files.list(directory)) {
            var files = paths.filter(p -> p.getFileName().toString().matches("q_[0-9a-f]{20}\\.json"))
                    .sorted().limit(201).toList();
            if (files.size() > 200) throw new QuestionImportException("too many preview questions");
            return files.stream().map(p -> {
                try {
                    if (Files.size(p) > 2_000_000) throw new QuestionImportException("oversized preview");
                    var question = importer.parseGeneratedQuestion(Files.readString(p));
                    if (!(question instanceof GeneratedQuestionV5Handoff v5))
                        throw new QuestionImportException("preview requires concept questions");
                    return v5;
                } catch (IOException e) { throw new QuestionImportException("preview could not be read", e); }
            }).toList();
        } catch (IOException e) { throw new QuestionImportException("preview directory is unavailable", e); }
    }

    @GetMapping("/summary")
    Map<String, Object> summary() {
        var questions = candidates();
        return Map.of("candidateCount", questions.size(), "topicIds", questions.stream()
                .map(GeneratedQuestionV5Handoff::topicId).distinct().toList());
    }

    @GetMapping
    Map<String, Object> preview() {
        return Map.of("status", "review-pending", "questions", candidates().stream().map(q -> Map.ofEntries(
                Map.entry("id", q.generatedQuestionId()), Map.entry("topicId", q.topicId()),
                Map.entry("conceptId", q.primaryConcept()), Map.entry("ability", q.ability()),
                Map.entry("measurementContext", q.measurementContext()),
                Map.entry("objective", q.assessmentObjective()), Map.entry("prompt", q.stem()),
                Map.entry("choices", q.choices()), Map.entry("correctChoiceIndex", q.correctChoiceIndex()),
                Map.entry("explanation", q.explanation()))).toList());
    }
}
