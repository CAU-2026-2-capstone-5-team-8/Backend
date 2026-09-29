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
    private final Path generatedQuestionPath;
    private final Path reviewsPath;
    private final Path groundingPath;

    public ApprovedQuestionImportRunner(
            ApprovedQuestionImportService importer,
            @Value("${question-import.generated-path}") String generatedQuestionPath,
            @Value("${question-import.reviews-path}") String reviewsPath,
            @Value("${question-import.grounding-path:}") String groundingPath) {
        this.importer = importer;
        this.generatedQuestionPath = Path.of(generatedQuestionPath);
        this.reviewsPath = Path.of(reviewsPath);
        this.groundingPath = groundingPath.isBlank() ? null : Path.of(groundingPath);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        ApprovedQuestionImportService.ImportResult result = groundingPath == null
                ? importer.importApproved(generatedQuestionPath, reviewsPath)
                : importer.importApproved(generatedQuestionPath, reviewsPath, groundingPath);
        LOG.info(
                "Approved question handoff completed generatedQuestionId={} questionId={} created={}",
                result.generatedQuestionId(), result.questionId(), result.created());
    }
}
