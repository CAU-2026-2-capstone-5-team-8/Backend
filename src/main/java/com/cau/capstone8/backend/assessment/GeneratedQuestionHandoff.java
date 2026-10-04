package com.cau.capstone8.backend.assessment;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

sealed interface GeneratedQuestionHandoff
        permits GeneratedQuestionV2Handoff, GeneratedQuestionV4Handoff, GeneratedQuestionV5Handoff {
    String generatedQuestionId();

    String generatedQuestionVersion();

    String questionSpecId();

    String topicId();

    String questionType();

    String cognitiveOperation();

    String primaryConcept();

    List<String> relatedConcepts();

    List<String> prerequisiteConcepts();

    int targetDifficulty();

    String stem();

    List<String> choices();

    int correctChoiceIndex();

    String explanation();

    List<String> sourceDocumentIds();

    String inputArtifactHash();

    String passage();

    Map<String, Object> provenance();

    default MeasurementArea measurementArea() {
        return MeasurementArea.fromQuestionType(questionType());
    }

    final class TokenUsage {
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer totalTokens;

        public TokenUsage() {}

        public Integer getInputTokens() {
            return inputTokens;
        }

        public void setInputTokens(Integer inputTokens) {
            GeneratedQuestionHandoffSupport.requireNonNegative(inputTokens);
            this.inputTokens = inputTokens;
        }

        public Integer getOutputTokens() {
            return outputTokens;
        }

        public void setOutputTokens(Integer outputTokens) {
            GeneratedQuestionHandoffSupport.requireNonNegative(outputTokens);
            this.outputTokens = outputTokens;
        }

        public Integer getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(Integer totalTokens) {
            GeneratedQuestionHandoffSupport.requireNonNegative(totalTokens);
            this.totalTokens = totalTokens;
        }

        Map<String, Object> provenance() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("inputTokens", inputTokens);
            values.put("outputTokens", outputTokens);
            values.put("totalTokens", totalTokens);
            return values;
        }
    }
}

record GeneratedQuestionV2Handoff(
        String generatedQuestionId,
        String generatedQuestionVersion,
        String questionSpecId,
        String topicId,
        String questionType,
        String cognitiveOperation,
        String primaryConcept,
        List<String> relatedConcepts,
        List<String> prerequisiteConcepts,
        int targetDifficulty,
        String difficultyRationale,
        String evidenceSummary,
        String stem,
        List<String> choices,
        int correctChoiceIndex,
        String explanation,
        String generationModel,
        String outputLanguage,
        String promptVersion,
        String generationConfigVersion,
        String questionSpecVersion,
        String questionSpecConfigVersion,
        String questionSpecConfigHash,
        List<String> supportingBookIds,
        List<String> supportingEvidenceIds,
        List<String> sourceDocumentIds,
        String inputArtifactHash,
        GeneratedQuestionHandoff.TokenUsage usage) implements GeneratedQuestionHandoff {

    GeneratedQuestionV2Handoff {
        GeneratedQuestionHandoffSupport.required(generatedQuestionId, "generated_question_id");
        GeneratedQuestionHandoffSupport.required(generatedQuestionVersion, "generated_question_version");
        GeneratedQuestionHandoffSupport.required(questionSpecId, "question_spec_id");
        GeneratedQuestionHandoffSupport.required(topicId, "topic_id");
        GeneratedQuestionHandoffSupport.required(questionType, "question_type");
        GeneratedQuestionHandoffSupport.required(cognitiveOperation, "cognitive_operation");
        GeneratedQuestionHandoffSupport.required(primaryConcept, "primary_concept");
        GeneratedQuestionHandoffSupport.required(difficultyRationale, "difficulty_rationale");
        GeneratedQuestionHandoffSupport.required(evidenceSummary, "evidence_summary");
        GeneratedQuestionHandoffSupport.required(stem, "stem");
        GeneratedQuestionHandoffSupport.required(explanation, "explanation");
        GeneratedQuestionHandoffSupport.required(generationModel, "generation_model");
        GeneratedQuestionHandoffSupport.required(outputLanguage, "output_language");
        GeneratedQuestionHandoffSupport.required(promptVersion, "prompt_version");
        GeneratedQuestionHandoffSupport.required(generationConfigVersion, "generation_config_version");
        GeneratedQuestionHandoffSupport.required(questionSpecVersion, "question_spec_version");
        GeneratedQuestionHandoffSupport.required(
                questionSpecConfigVersion, "question_spec_config_version");
        GeneratedQuestionHandoffSupport.required(
                questionSpecConfigHash, "question_spec_config_hash");
        GeneratedQuestionHandoffSupport.required(inputArtifactHash, "input_artifact_hash");

        GeneratedQuestionHandoffSupport.generatedId(generatedQuestionId);
        GeneratedQuestionHandoffSupport.specId(questionSpecId);
        if (!"generated-question-v2".equals(generatedQuestionVersion)
                || !"question-spec-v1".equals(questionSpecVersion)) {
            GeneratedQuestionHandoffSupport.fail(
                    "unsupported generated question or QuestionSpec version");
        }
        GeneratedQuestionHandoffSupport.hash(questionSpecConfigHash, "question_spec_config_hash");
        GeneratedQuestionHandoffSupport.hash(inputArtifactHash, "input_artifact_hash");
        if (targetDifficulty != 1) {
            GeneratedQuestionHandoffSupport.fail(
                    "generated-question-v2 supports only target_difficulty 1");
        }
        boolean validType = ("vocabulary".equals(questionType)
                        && "recognize".equals(cognitiveOperation))
                || ("background_knowledge".equals(questionType)
                        && "recall".equals(cognitiveOperation));
        if (!validType) {
            GeneratedQuestionHandoffSupport.fail(
                    "unsupported question_type/cognitive_operation pair");
        }

        relatedConcepts = GeneratedQuestionHandoffSupport.immutableStrings(
                relatedConcepts, "related_concepts", true);
        prerequisiteConcepts = GeneratedQuestionHandoffSupport.immutableStrings(
                prerequisiteConcepts, "prerequisite_concepts", true);
        choices = GeneratedQuestionHandoffSupport.immutableStrings(choices, "choices", false);
        supportingBookIds = GeneratedQuestionHandoffSupport.immutableStrings(
                supportingBookIds, "supporting_book_ids", true);
        supportingEvidenceIds = GeneratedQuestionHandoffSupport.immutableStrings(
                supportingEvidenceIds, "supporting_evidence_ids", true);
        sourceDocumentIds = GeneratedQuestionHandoffSupport.immutableStrings(
                sourceDocumentIds, "source_document_ids", true);

        if (!relatedConcepts.isEmpty() || !sourceDocumentIds.isEmpty()) {
            GeneratedQuestionHandoffSupport.fail(
                    "generated-question-v2 handoff does not support relation or prose-grounded targets");
        }
        GeneratedQuestionHandoffSupport.validateChoices(choices, correctChoiceIndex);
        if (usage == null) {
            GeneratedQuestionHandoffSupport.fail("usage is required");
        }
    }

    @Override
    public String passage() {
        return null;
    }

    @Override
    public Map<String, Object> provenance() {
        return Map.ofEntries(
                Map.entry("generatedQuestionVersion", generatedQuestionVersion),
                Map.entry("cognitiveOperation", cognitiveOperation),
                Map.entry("relatedConcepts", relatedConcepts),
                Map.entry("prerequisiteConcepts", prerequisiteConcepts),
                Map.entry("difficultyRationale", difficultyRationale),
                Map.entry("evidenceSummary", evidenceSummary),
                Map.entry("generationModel", generationModel),
                Map.entry("outputLanguage", outputLanguage),
                Map.entry("promptVersion", promptVersion),
                Map.entry("generationConfigVersion", generationConfigVersion),
                Map.entry("questionSpecVersion", questionSpecVersion),
                Map.entry("questionSpecConfigVersion", questionSpecConfigVersion),
                Map.entry("questionSpecConfigHash", questionSpecConfigHash),
                Map.entry("supportingBookIds", supportingBookIds),
                Map.entry("supportingEvidenceIds", supportingEvidenceIds),
                Map.entry("sourceDocumentIds", sourceDocumentIds),
                Map.entry("inputArtifactHash", inputArtifactHash),
                Map.entry("usage", usage.provenance()));
    }
}

record GeneratedQuestionV4Handoff(
        String generatedQuestionId,
        String generatedQuestionVersion,
        String questionSpecId,
        String topicId,
        String questionType,
        String cognitiveOperation,
        String primaryConcept,
        List<String> relatedConcepts,
        List<String> prerequisiteConcepts,
        int targetDifficulty,
        String difficultyRationale,
        String evidenceSummary,
        String passage,
        String stem,
        List<String> choices,
        int correctChoiceIndex,
        String explanation,
        String generationModel,
        String outputLanguage,
        String promptVersion,
        String generationConfigVersion,
        String questionSpecVersion,
        String questionSpecConfigVersion,
        String questionSpecConfigHash,
        List<String> supportingBookIds,
        List<String> supportingEvidenceIds,
        List<String> sourceDocumentIds,
        String groundingVersion,
        String sourcePassageExtractionPolicy,
        String sourcePassageHash,
        String displayPassageHash,
        String displayNormalizationPolicy,
        String inputArtifactHash,
        GeneratedQuestionHandoff.TokenUsage usage) implements GeneratedQuestionHandoff {

    GeneratedQuestionV4Handoff {
        GeneratedQuestionHandoffSupport.required(generatedQuestionId, "generated_question_id");
        GeneratedQuestionHandoffSupport.required(generatedQuestionVersion, "generated_question_version");
        GeneratedQuestionHandoffSupport.required(questionSpecId, "question_spec_id");
        GeneratedQuestionHandoffSupport.required(topicId, "topic_id");
        GeneratedQuestionHandoffSupport.required(questionType, "question_type");
        GeneratedQuestionHandoffSupport.required(cognitiveOperation, "cognitive_operation");
        GeneratedQuestionHandoffSupport.required(primaryConcept, "primary_concept");
        GeneratedQuestionHandoffSupport.required(difficultyRationale, "difficulty_rationale");
        GeneratedQuestionHandoffSupport.required(evidenceSummary, "evidence_summary");
        GeneratedQuestionHandoffSupport.required(passage, "passage");
        GeneratedQuestionHandoffSupport.required(stem, "stem");
        GeneratedQuestionHandoffSupport.required(explanation, "explanation");
        GeneratedQuestionHandoffSupport.required(generationModel, "generation_model");
        GeneratedQuestionHandoffSupport.required(outputLanguage, "output_language");
        GeneratedQuestionHandoffSupport.required(promptVersion, "prompt_version");
        GeneratedQuestionHandoffSupport.required(generationConfigVersion, "generation_config_version");
        GeneratedQuestionHandoffSupport.required(questionSpecVersion, "question_spec_version");
        GeneratedQuestionHandoffSupport.required(
                questionSpecConfigVersion, "question_spec_config_version");
        GeneratedQuestionHandoffSupport.required(
                questionSpecConfigHash, "question_spec_config_hash");
        GeneratedQuestionHandoffSupport.required(groundingVersion, "grounding_version");
        GeneratedQuestionHandoffSupport.required(
                sourcePassageExtractionPolicy, "source_passage_extraction_policy");
        GeneratedQuestionHandoffSupport.required(sourcePassageHash, "source_passage_hash");
        GeneratedQuestionHandoffSupport.required(displayPassageHash, "display_passage_hash");
        GeneratedQuestionHandoffSupport.required(
                displayNormalizationPolicy, "display_normalization_policy");
        GeneratedQuestionHandoffSupport.required(inputArtifactHash, "input_artifact_hash");

        GeneratedQuestionHandoffSupport.generatedId(generatedQuestionId);
        GeneratedQuestionHandoffSupport.specId(questionSpecId);
        if (!"generated-question-v4".equals(generatedQuestionVersion)
                || !"question-spec-v1".equals(questionSpecVersion)) {
            GeneratedQuestionHandoffSupport.fail(
                    "unsupported generated question or QuestionSpec version");
        }
        if (!"comprehension".equals(questionType)
                || !"apply".equals(cognitiveOperation)
                || targetDifficulty != 2) {
            GeneratedQuestionHandoffSupport.fail(
                    "generated-question-v4 supports only comprehension/apply target_difficulty 2");
        }
        if (!"generation-grounding-v2".equals(groundingVersion)
                || !"first-concept-sentence-window-v1".equals(sourcePassageExtractionPolicy)
                || !"pdf-display-normalization-v1".equals(displayNormalizationPolicy)) {
            GeneratedQuestionHandoffSupport.fail("unsupported v4 grounding contract");
        }
        GeneratedQuestionHandoffSupport.hash(questionSpecConfigHash, "question_spec_config_hash");
        GeneratedQuestionHandoffSupport.hash(sourcePassageHash, "source_passage_hash");
        GeneratedQuestionHandoffSupport.hash(displayPassageHash, "display_passage_hash");
        GeneratedQuestionHandoffSupport.hash(inputArtifactHash, "input_artifact_hash");

        relatedConcepts = GeneratedQuestionHandoffSupport.immutableTrimmedStrings(
                relatedConcepts, "related_concepts", true);
        prerequisiteConcepts = GeneratedQuestionHandoffSupport.immutableTrimmedStrings(
                prerequisiteConcepts, "prerequisite_concepts", true);
        choices = GeneratedQuestionHandoffSupport.immutableTrimmedStrings(
                choices, "choices", false);
        supportingBookIds = GeneratedQuestionHandoffSupport.immutableTrimmedStrings(
                supportingBookIds, "supporting_book_ids", true);
        supportingEvidenceIds = GeneratedQuestionHandoffSupport.immutableTrimmedStrings(
                supportingEvidenceIds, "supporting_evidence_ids", true);
        sourceDocumentIds = GeneratedQuestionHandoffSupport.immutableTrimmedStrings(
                sourceDocumentIds, "source_document_ids", false);

        if (!relatedConcepts.isEmpty() || sourceDocumentIds.size() != 1) {
            GeneratedQuestionHandoffSupport.fail(
                    "generated-question-v4 supports exactly one source and no related concepts");
        }
        if (passage.length() < 600 || passage.length() > 2000) {
            GeneratedQuestionHandoffSupport.fail("passage length is outside the v4 contract");
        }
        GeneratedQuestionHandoffSupport.validateChoices(choices, correctChoiceIndex);
        if (!displayPassageHash.equals(GeneratedQuestionHandoffSupport.sha256(passage))) {
            GeneratedQuestionHandoffSupport.fail("display passage hash does not match passage");
        }
        if (usage == null) {
            GeneratedQuestionHandoffSupport.fail("usage is required");
        }
    }

    @Override
    public Map<String, Object> provenance() {
        return Map.ofEntries(
                Map.entry("generatedQuestionVersion", generatedQuestionVersion),
                Map.entry("cognitiveOperation", cognitiveOperation),
                Map.entry("relatedConcepts", relatedConcepts),
                Map.entry("prerequisiteConcepts", prerequisiteConcepts),
                Map.entry("difficultyRationale", difficultyRationale),
                Map.entry("evidenceSummary", evidenceSummary),
                Map.entry("generationModel", generationModel),
                Map.entry("outputLanguage", outputLanguage),
                Map.entry("promptVersion", promptVersion),
                Map.entry("generationConfigVersion", generationConfigVersion),
                Map.entry("questionSpecVersion", questionSpecVersion),
                Map.entry("questionSpecConfigVersion", questionSpecConfigVersion),
                Map.entry("questionSpecConfigHash", questionSpecConfigHash),
                Map.entry("supportingBookIds", supportingBookIds),
                Map.entry("supportingEvidenceIds", supportingEvidenceIds),
                Map.entry("sourceDocumentIds", sourceDocumentIds),
                Map.entry("groundingVersion", groundingVersion),
                Map.entry("sourcePassageExtractionPolicy", sourcePassageExtractionPolicy),
                Map.entry("sourcePassageHash", sourcePassageHash),
                Map.entry("displayPassageHash", displayPassageHash),
                Map.entry("displayNormalizationPolicy", displayNormalizationPolicy),
                Map.entry("inputArtifactHash", inputArtifactHash),
                Map.entry("usage", usage.provenance()));
    }
}

final class GeneratedQuestionHandoffSupport {
    private static final Pattern GENERATED_ID = Pattern.compile("^gq_[0-9a-f]{32}$");
    private static final Pattern SPEC_ID = Pattern.compile("^q_[0-9a-f]{20}$");
    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private GeneratedQuestionHandoffSupport() {}

    static void generatedId(String value) {
        if (!GENERATED_ID.matcher(value).matches()) {
            fail("generated_question_id has an invalid format");
        }
    }

    static void specId(String value) {
        if (!SPEC_ID.matcher(value).matches()) {
            fail("question_spec_id has an invalid format");
        }
    }

    static void hash(String value, String field) {
        if (!HASH.matcher(value).matches()) {
            fail(field + " has an invalid SHA-256 format");
        }
    }

    static List<String> immutableStrings(
            List<String> values,
            String field,
            boolean emptyAllowed) {
        if (values == null || (!emptyAllowed && values.isEmpty())) {
            fail(field + " must be a non-empty array");
        }
        List<String> copy = List.copyOf(values);
        if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
            fail(field + " contains a blank value");
        }
        if (new HashSet<>(copy).size() != copy.size()) {
            fail(field + " contains duplicate values");
        }
        return copy;
    }

    static List<String> immutableTrimmedStrings(
            List<String> values,
            String field,
            boolean emptyAllowed) {
        List<String> copy = immutableStrings(values, field, emptyAllowed);
        if (copy.stream().anyMatch(value -> !value.equals(value.trim()))) {
            fail(field + " contains an untrimmed value");
        }
        return copy;
    }

    static void validateChoices(List<String> choices, int correctChoiceIndex) {
        if (choices.size() != 4 || normalizedChoiceCount(choices) != 4) {
            fail("choices must contain exactly four distinct values");
        }
        if (correctChoiceIndex < 0 || correctChoiceIndex >= choices.size()) {
            fail("correct_choice_index is outside choices");
        }
    }

    static String sha256(String value) {
        return ApprovedQuestionImportService.sha256(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static void required(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            fail(field + " must be nonblank and trimmed");
        }
    }

    static void requireNonNegative(Integer value) {
        if (value != null && value < 0) {
            fail("usage token counts must be non-negative when present");
        }
    }

    static void fail(String message) {
        throw new QuestionImportException(message);
    }

    private static int normalizedChoiceCount(List<String> values) {
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            normalized.add(Normalizer.normalize(value, Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT)
                    .trim()
                    .replaceAll("\\s+", " "));
        }
        return normalized.size();
    }
}
