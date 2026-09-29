package com.cau.capstone8.backend.assessment;

import com.cau.capstone8.backend.topic.Topic;
import com.cau.capstone8.backend.topic.TopicRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
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
    // grounding_path is optional per entry, so missing creator properties are allowed here.
    private static final JsonMapper MANIFEST_JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
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
                    Files.readString(reviewsPath),
                    (byte[]) null);
        } catch (IOException exception) {
            throw new QuestionImportException("approved question handoff files could not be read", exception);
        }
    }

    @Transactional
    public ImportResult importApproved(
            Path generatedQuestionPath,
            Path reviewsPath,
            Path groundingPath) {
        try {
            byte[] groundingBytes = Files.readAllBytes(groundingPath);
            return importApproved(
                    Files.readString(generatedQuestionPath),
                    Files.readString(reviewsPath),
                    groundingBytes);
        } catch (IOException exception) {
            throw new QuestionImportException("approved question handoff files could not be read", exception);
        }
    }

    @Transactional
    public ImportResult importApproved(String generatedQuestionJson, String reviewsJsonl) {
        return importApproved(generatedQuestionJson, reviewsJsonl, (byte[]) null);
    }

    @Transactional
    public ImportResult importApproved(
            String generatedQuestionJson,
            String reviewsJsonl,
            String groundingJson) {
        return importApproved(
                generatedQuestionJson,
                reviewsJsonl,
                groundingJson == null ? null : groundingJson.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Imports every entry listed in an explicit manifest in one transaction. Any rejected entry
     * rolls back the whole batch. Relative paths resolve against the manifest's directory.
     */
    @Transactional
    public List<ImportResult> importManifest(Path manifestPath) {
        QuestionImportManifest manifest = parseManifest(manifestPath);
        Path baseDirectory = manifestPath.toAbsolutePath().getParent();
        var results = new ArrayList<ImportResult>();
        for (int index = 0; index < manifest.entries().size(); index++) {
            QuestionImportManifest.Entry entry = manifest.entries().get(index);
            try {
                results.add(importApproved(
                        Files.readString(baseDirectory.resolve(entry.generatedPath())),
                        Files.readString(baseDirectory.resolve(entry.reviewsPath())),
                        entry.groundingPath() == null
                                ? null
                                : Files.readAllBytes(baseDirectory.resolve(entry.groundingPath()))));
            } catch (IOException | QuestionImportException exception) {
                throw new QuestionImportException(
                        "manifest entry " + index + " (" + entry.generatedPath() + ") failed: "
                                + exception.getMessage(),
                        exception);
            }
        }
        return List.copyOf(results);
    }

    private QuestionImportManifest parseManifest(Path manifestPath) {
        QuestionImportManifest manifest;
        try {
            manifest = MANIFEST_JSON.readValue(
                    Files.readAllBytes(manifestPath), QuestionImportManifest.class);
        } catch (IOException | RuntimeException exception) {
            throw new QuestionImportException("question import manifest is malformed", exception);
        }
        if (!QuestionImportManifest.VERSION.equals(manifest.manifestVersion())) {
            throw new QuestionImportException(
                    "manifest_version must be " + QuestionImportManifest.VERSION);
        }
        if (manifest.entries() == null || manifest.entries().isEmpty()) {
            throw new QuestionImportException("manifest entries must not be empty");
        }
        var generatedPaths = new HashSet<String>();
        for (QuestionImportManifest.Entry entry : manifest.entries()) {
            if (entry == null || blank(entry.generatedPath()) || blank(entry.reviewsPath())
                    || (entry.groundingPath() != null && entry.groundingPath().isBlank())) {
                throw new QuestionImportException(
                        "manifest entries require generated_path and reviews_path");
            }
            if (!generatedPaths.add(entry.generatedPath())) {
                throw new QuestionImportException(
                        "manifest lists generated_path more than once: " + entry.generatedPath());
            }
        }
        return manifest;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ImportResult importApproved(
            String generatedQuestionJson,
            String reviewsJsonl,
            byte[] groundingBytes) {
        GeneratedQuestionHandoff generated = parseGeneratedQuestion(generatedQuestionJson);
        GenerationGroundingV2Handoff grounding = validateGrounding(generated, groundingBytes);
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
        if (grounding != null) {
            provenance.put("groundingSource", grounding.safeSourceProvenance());
        }
        Question saved = questions.save(Question.generated(
                topic.getId(),
                generated.measurementArea(),
                generated.targetDifficulty(),
                generated.passage(),
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
            JsonNode root = JSON.readTree(json);
            JsonNode versionNode = root == null ? null : root.get("generated_question_version");
            if (versionNode == null || !versionNode.isString()) {
                throw new QuestionImportException("generated_question_version is required");
            }
            GeneratedQuestionHandoff generated = switch (versionNode.asString()) {
                case "generated-question-v2" -> JSON.treeToValue(
                        root, GeneratedQuestionV2Handoff.class);
                case "generated-question-v4" -> JSON.treeToValue(
                        root, GeneratedQuestionV4Handoff.class);
                default -> throw new QuestionImportException(
                        "unsupported generated_question_version");
            };
            if (generated instanceof GeneratedQuestionV4Handoff v4) {
                validateV4GeneratedId(v4);
            }
            return generated;
        } catch (QuestionImportException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new QuestionImportException("GeneratedQuestion artifact is malformed", exception);
        }
    }

    private GenerationGroundingV2Handoff validateGrounding(
            GeneratedQuestionHandoff generated,
            byte[] groundingBytes) {
        if (generated instanceof GeneratedQuestionV2Handoff) {
            if (groundingBytes != null) {
                throw new QuestionImportException(
                        "generated-question-v2 must not include a grounding artifact");
            }
            return null;
        }
        if (groundingBytes == null) {
            throw new QuestionImportException(
                    "generated-question-v4 requires a generation-grounding-v2 artifact");
        }

        GenerationGroundingV2Handoff grounding;
        try {
            grounding = JSON.readValue(groundingBytes, GenerationGroundingV2Handoff.class);
        } catch (QuestionImportException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new QuestionImportException(
                    "GenerationGrounding v2 artifact is malformed", exception);
        }

        GeneratedQuestionV4Handoff v4 = (GeneratedQuestionV4Handoff) generated;
        requireEqual(v4.groundingVersion(), grounding.groundingVersion(), "grounding_version");
        requireEqual(v4.questionSpecId(), grounding.questionSpecId(), "question_spec_id");
        requireEqual(v4.topicId(), grounding.topicId(), "topic_id");
        requireEqual(v4.questionType(), grounding.questionType(), "question_type");
        requireEqual(v4.cognitiveOperation(), grounding.cognitiveOperation(), "cognitive_operation");
        requireEqual(v4.targetDifficulty(), grounding.targetDifficulty(), "target_difficulty");
        requireEqual(v4.primaryConcept(), grounding.primaryConcept(), "primary_concept");
        requireEqual(v4.relatedConcepts(), grounding.relatedConcepts(), "related_concepts");
        requireEqual(
                v4.sourceDocumentIds(),
                List.of(grounding.sourceDocumentId()),
                "source_document_ids");
        requireEqual(
                v4.sourcePassageExtractionPolicy(),
                grounding.sourcePassageExtractionPolicy(),
                "source_passage_extraction_policy");
        requireEqual(v4.sourcePassageHash(), grounding.sourcePassageHash(), "source_passage_hash");
        requireEqual(v4.displayPassageHash(), grounding.displayPassageHash(), "display_passage_hash");
        requireEqual(
                v4.displayNormalizationPolicy(),
                grounding.displayNormalizationPolicy(),
                "display_normalization_policy");
        requireEqual(v4.passage(), grounding.displayPassageText(), "display_passage_text");
        requireEqual(v4.inputArtifactHash(), sha256(groundingBytes), "input_artifact_hash");
        return grounding;
    }

    private void validateV4GeneratedId(GeneratedQuestionV4Handoff generated) {
        Map<String, Object> identityAndOutput = new TreeMap<>(
                JSON.convertValue(generated, OBJECT_MAP));
        identityAndOutput.remove("generated_question_id");
        identityAndOutput.remove("usage");
        String expected = "gq_" + sha256Digest(JSON.writeValueAsBytes(identityAndOutput))
                .substring(0, 32);
        if (!expected.equals(generated.generatedQuestionId())) {
            throw new QuestionImportException(
                    "generated_question_id does not match v4 identity and output");
        }
    }

    private void requireEqual(Object generated, Object grounding, String field) {
        if (!java.util.Objects.equals(generated, grounding)) {
            throw new QuestionImportException(
                    "GeneratedQuestion and grounding differ for " + field);
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
        return sha256(JSON.writeValueAsBytes(values));
    }

    static String sha256(byte[] value) {
        return "sha256:" + sha256Digest(value);
    }

    private static String sha256Digest(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest);
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
