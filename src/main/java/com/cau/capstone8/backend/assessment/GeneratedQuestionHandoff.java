package com.cau.capstone8.backend.assessment;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

record GeneratedQuestionHandoff(
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
        TokenUsage usage) {
    private static final Pattern GENERATED_ID = Pattern.compile("^gq_[0-9a-f]{32}$");
    private static final Pattern SPEC_ID = Pattern.compile("^q_[0-9a-f]{20}$");
    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    GeneratedQuestionHandoff {
        required(generatedQuestionId, "generated_question_id");
        required(generatedQuestionVersion, "generated_question_version");
        required(questionSpecId, "question_spec_id");
        required(topicId, "topic_id");
        required(questionType, "question_type");
        required(cognitiveOperation, "cognitive_operation");
        required(primaryConcept, "primary_concept");
        required(difficultyRationale, "difficulty_rationale");
        required(evidenceSummary, "evidence_summary");
        required(stem, "stem");
        required(explanation, "explanation");
        required(generationModel, "generation_model");
        required(outputLanguage, "output_language");
        required(promptVersion, "prompt_version");
        required(generationConfigVersion, "generation_config_version");
        required(questionSpecVersion, "question_spec_version");
        required(questionSpecConfigVersion, "question_spec_config_version");
        required(questionSpecConfigHash, "question_spec_config_hash");
        required(inputArtifactHash, "input_artifact_hash");

        if (!GENERATED_ID.matcher(generatedQuestionId).matches()) {
            fail("generated_question_id has an invalid format");
        }
        if (!SPEC_ID.matcher(questionSpecId).matches()) {
            fail("question_spec_id has an invalid format");
        }
        if (!"generated-question-v2".equals(generatedQuestionVersion)
                || !"question-spec-v1".equals(questionSpecVersion)) {
            fail("unsupported generated question or QuestionSpec version");
        }
        if (!HASH.matcher(questionSpecConfigHash).matches()
                || !HASH.matcher(inputArtifactHash).matches()) {
            fail("question provenance hash has an invalid format");
        }
        if (targetDifficulty != 1) {
            fail("generated-question-v2 supports only target_difficulty 1");
        }
        validateQuestionType(questionType, cognitiveOperation);

        relatedConcepts = immutableStrings(relatedConcepts, "related_concepts", true);
        prerequisiteConcepts = immutableStrings(
                prerequisiteConcepts, "prerequisite_concepts", true);
        choices = immutableStrings(choices, "choices", false);
        supportingBookIds = immutableStrings(supportingBookIds, "supporting_book_ids", true);
        supportingEvidenceIds = immutableStrings(
                supportingEvidenceIds, "supporting_evidence_ids", true);
        sourceDocumentIds = immutableStrings(sourceDocumentIds, "source_document_ids", true);

        if (!relatedConcepts.isEmpty() || !sourceDocumentIds.isEmpty()) {
            fail("generated-question-v2 handoff does not support relation or prose-grounded targets");
        }
        if (choices.size() != 4 || normalizedChoiceCount(choices) != 4) {
            fail("choices must contain exactly four distinct values");
        }
        if (correctChoiceIndex < 0 || correctChoiceIndex >= choices.size()) {
            fail("correct_choice_index is outside choices");
        }
        if (usage == null) {
            fail("usage is required");
        }
    }

    MeasurementArea measurementArea() {
        return MeasurementArea.fromQuestionType(questionType);
    }

    Map<String, Object> provenance() {
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

    private static void validateQuestionType(String questionType, String cognitiveOperation) {
        boolean valid = ("vocabulary".equals(questionType) && "recognize".equals(cognitiveOperation))
                || ("background_knowledge".equals(questionType)
                && "recall".equals(cognitiveOperation));
        if (!valid) {
            fail("unsupported question_type/cognitive_operation pair");
        }
    }

    private static List<String> immutableStrings(
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

    private static void required(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            fail(field + " must be nonblank and trimmed");
        }
    }

    private static void fail(String message) {
        throw new QuestionImportException(message);
    }

    static final class TokenUsage {
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer totalTokens;

        public TokenUsage() {}

        public Integer getInputTokens() {
            return inputTokens;
        }

        public void setInputTokens(Integer inputTokens) {
            requireNonNegative(inputTokens);
            this.inputTokens = inputTokens;
        }

        public Integer getOutputTokens() {
            return outputTokens;
        }

        public void setOutputTokens(Integer outputTokens) {
            requireNonNegative(outputTokens);
            this.outputTokens = outputTokens;
        }

        public Integer getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(Integer totalTokens) {
            requireNonNegative(totalTokens);
            this.totalTokens = totalTokens;
        }

        private static void requireNonNegative(Integer value) {
            if (value != null && value < 0) {
                fail("usage token counts must be non-negative when present");
            }
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
