package com.cau.capstone8.backend.assessment;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("question-import")
@Order(100)
public class ApprovedQuestionImportRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ApprovedQuestionImportRunner.class);

    private final ApprovedQuestionImportService importer;
    private final Path manifestPath;
    private final Path generatedQuestionPath;
    private final Path reviewsPath;
    private final Path groundingPath;

    public ApprovedQuestionImportRunner(
            ApprovedQuestionImportService importer,
            @Value("${question-import.manifest-path:}") String manifestPath,
            @Value("${question-import.generated-path:}") String generatedQuestionPath,
            @Value("${question-import.reviews-path:}") String reviewsPath,
            @Value("${question-import.grounding-path:}") String groundingPath) {
        this.importer = importer;
        this.manifestPath = optionalPath(manifestPath);
        this.generatedQuestionPath = optionalPath(generatedQuestionPath);
        this.reviewsPath = optionalPath(reviewsPath);
        this.groundingPath = optionalPath(groundingPath);
        if (this.manifestPath != null) {
            if (this.generatedQuestionPath != null || this.reviewsPath != null
                    || this.groundingPath != null) {
                throw new IllegalStateException(
                        "question-import.manifest-path cannot be combined with single-file paths");
            }
        } else if (this.generatedQuestionPath == null || this.reviewsPath == null) {
            throw new IllegalStateException(
                    "question-import requires manifest-path, or generated-path and reviews-path");
        }
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (manifestPath != null) {
            var results = importer.importManifest(manifestPath);
            long created = results.stream()
                    .filter(ApprovedQuestionImportService.ImportResult::created)
                    .count();
            LOG.info(
                    "Approved question manifest completed entries={} created={} unchanged={}",
                    results.size(), created, results.size() - created);
            return;
        }
        ApprovedQuestionImportService.ImportResult result = groundingPath == null
                ? importer.importApproved(generatedQuestionPath, reviewsPath)
                : importer.importApproved(generatedQuestionPath, reviewsPath, groundingPath);
        LOG.info(
                "Approved question handoff completed generatedQuestionId={} questionId={} created={}",
                result.generatedQuestionId(), result.questionId(), result.created());
    }

    private static Path optionalPath(String value) {
        return value.isBlank() ? null : Path.of(value);
    }
}
