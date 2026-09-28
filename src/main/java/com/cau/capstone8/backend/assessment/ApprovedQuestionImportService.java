package com.cau.capstone8.backend.assessment;

import com.cau.capstone8.backend.topic.Topic;
import com.cau.capstone8.backend.topic.TopicRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ApprovedQuestionImportService {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    private final QuestionRepository questions;
    private final TopicRepository topics;

    public ApprovedQuestionImportService(QuestionRepository questions, TopicRepository topics) {
        this.questions = questions;
        this.topics = topics;
    }

    @Transactional
    public ImportResult importApproved(Path generatedQuestionPath, Path reviewsPath) {
        try {
            return importApproved(
                    Files.readString(generatedQuestionPath),
                    Files.readString(reviewsPath));
        } catch (IOException exception) {
            throw new QuestionImportException("approved question handoff files could not be read", exception);
        }
    }

    @Transactional
    public ImportResult importApproved(String generatedQuestionJson, String reviewsJsonl) {
        GeneratedQuestionHandoff generated = parseGeneratedQuestion(generatedQuestionJson);
        HumanQuestionReviewHandoff review = matchingReview(
                reviewsJsonl, generated.generatedQuestionId());
        requireApproval(review);

        String contentHash = contentHash(generated);
        var existing = questions.findByGeneratedQuestionId(generated.generatedQuestionId());
        if (existing.isPresent()) {
            if (!contentHash.equals(existing.get().getGeneratedContentHash())) {
                throw new QuestionImportException(
                        "generated_question_id already exists with different immutable content");
            }
            return new ImportResult(existing.get().getId(), generated.generatedQuestionId(), false);
        }

        Topic topic = topics.findByMlTopicId(generated.topicId())
                .orElseThrow(() -> new QuestionImportException(
                        "no Backend topic matches generated topic_id: " + generated.topicId()));
        Map<String, Object> provenance = new LinkedHashMap<>(generated.provenance());
        provenance.put("humanReview", reviewProvenance(review));
        Question saved = questions.save(Question.generated(
                topic.getId(),
                generated.measurementArea(),
                generated.targetDifficulty(),
                generated.stem(),
                generated.primaryConcept(),
                generated.generatedQuestionVersion(),
                generated.generatedQuestionId(),
                generated.questionSpecId(),
                generated.choices(),
                generated.correctChoiceIndex(),
                generated.explanation(),
                contentHash,
                provenance));
        return new ImportResult(saved.getId(), generated.generatedQuestionId(), true);
    }

    private GeneratedQuestionHandoff parseGeneratedQuestion(String json) {
        try {
            return JSON.readValue(json, GeneratedQuestionHandoff.class);
        } catch (QuestionImportException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new QuestionImportException("GeneratedQuestion artifact is malformed", exception);
        }
    }

    private HumanQuestionReviewHandoff matchingReview(String jsonl, String generatedQuestionId) {
        List<HumanQuestionReviewHandoff> matching;
        try {
            matching = jsonl.lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> JSON.readValue(line, HumanQuestionReviewHandoff.class))
                    .filter(review -> review.generatedQuestionId().equals(generatedQuestionId))
                    .toList();
        } catch (QuestionImportException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new QuestionImportException("HumanQuestionReview JSONL is malformed", exception);
        }
        if (matching.size() != 1) {
            throw new QuestionImportException(
                    "exactly one matching HumanQuestionReview is required");
        }
        return matching.getFirst();
    }

    private void requireApproval(HumanQuestionReviewHandoff review) {
        if (!"approve".equals(review.status()) || !review.correct()) {
            throw new QuestionImportException(
                    "only HumanQuestionReview status=approve and correct=true may be imported");
        }
    }

    private String contentHash(GeneratedQuestionHandoff generated) {
        Map<String, Object> values = new TreeMap<>(JSON.convertValue(generated, OBJECT_MAP));
        values.remove("usage");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(values));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Map<String, Object> reviewProvenance(HumanQuestionReviewHandoff review) {
        return Map.of(
                "status", review.status(),
                "correct", review.correct(),
                "conceptAlignment", review.conceptAlignment(),
                "difficultyAppropriate", review.difficultyAppropriate(),
                "distractorQuality", review.distractorQuality(),
                "explanationQuality", review.explanationQuality(),
                "notes", review.notes());
    }

    public record ImportResult(long questionId, String generatedQuestionId, boolean created) {}
}
