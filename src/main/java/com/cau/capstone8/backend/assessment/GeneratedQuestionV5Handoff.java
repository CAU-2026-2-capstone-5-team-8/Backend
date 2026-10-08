package com.cau.capstone8.backend.assessment;

import java.util.List;
import java.util.Map;

/** Concept assessment contract: legacy areas are storage compatibility only. */
record GeneratedQuestionV5Handoff(
        String generatedQuestionId, String generatedQuestionVersion, String questionSpecId,
        String topicId, String primaryConcept, String ability, String cognitiveOperation,
        String measurementContext, int targetDifficulty, String assessmentObjective,
        List<TocReference> evidenceReferences, String questionSpecHash, String inputArtifactHash,
        String stem, List<String> choices, int correctChoiceIndex, String explanation,
        String generationModel, String outputLanguage, String promptVersion,
        String generationConfigVersion, GeneratedQuestionHandoff.TokenUsage usage
) implements GeneratedQuestionHandoff {
    record TocReference(String bookId, String tocEntryId) {
        TocReference {
            GeneratedQuestionHandoffSupport.required(bookId, "book_id");
            GeneratedQuestionHandoffSupport.required(tocEntryId, "toc_entry_id");
        }
    }

    GeneratedQuestionV5Handoff {
        for (String text : List.of(generatedQuestionId, generatedQuestionVersion, questionSpecId,
                topicId, primaryConcept, ability, cognitiveOperation, measurementContext,
                assessmentObjective, stem, explanation, generationModel, outputLanguage,
                promptVersion, generationConfigVersion)) {
            GeneratedQuestionHandoffSupport.required(text, "v5 text");
        }
        GeneratedQuestionHandoffSupport.generatedId(generatedQuestionId);
        GeneratedQuestionHandoffSupport.specId(questionSpecId);
        GeneratedQuestionHandoffSupport.hash(questionSpecHash, "question_spec_hash");
        GeneratedQuestionHandoffSupport.hash(inputArtifactHash, "input_artifact_hash");
        if (!"generated-question-v5".equals(generatedQuestionVersion)
                || !"prior-knowledge".equals(measurementContext)
                || !(List.of("concept-question-generation-prompt-v1", "concept-question-generation-prompt-v2").contains(promptVersion)
                        && "concept-question-generation-config-v1".equals(generationConfigVersion)
                    || "concept-question-generation-prompt-v3".equals(promptVersion)
                        && java.util.Set.of("concept-question-generation-config-v2","concept-question-generation-config-v3").contains(generationConfigVersion))
                || !outputLanguage.matches("en(?:-[A-Za-z]{2,8})?")
                || targetDifficulty < 1 || targetDifficulty > 3) {
            GeneratedQuestionHandoffSupport.fail("unsupported v5 contract");
        }
        String operation = Map.of("meaning", "recognize", "application", "apply",
                "reasoning", "infer").get(ability);
        if (operation == null || !operation.equals(cognitiveOperation)) {
            GeneratedQuestionHandoffSupport.fail("ability and operation disagree");
        }
        choices = GeneratedQuestionHandoffSupport.immutableTrimmedStrings(choices, "choices", false);
        GeneratedQuestionHandoffSupport.validateChoices(choices, correctChoiceIndex);
        if (usage == null || evidenceReferences == null || evidenceReferences.isEmpty()
                || evidenceReferences.stream().distinct().count() != evidenceReferences.size()) {
            GeneratedQuestionHandoffSupport.fail("unique TOC evidence and usage are required");
        }
        evidenceReferences = List.copyOf(evidenceReferences);
    }

    @Override public String passage() { return null; }
    @Override public List<String> relatedConcepts() { return List.of(); }
    @Override public List<String> prerequisiteConcepts() { return List.of(); }
    @Override public List<String> sourceDocumentIds() { return List.of(); }
    @Override public String questionType() {
        return switch (ability) {
            case "meaning" -> "vocabulary";
            case "application" -> "background_knowledge";
            case "reasoning" -> "comprehension";
            default -> throw new QuestionImportException("unsupported ability");
        };
    }

    @Override public Map<String, Object> provenance() {
        return Map.ofEntries(
                Map.entry("generatedQuestionVersion", generatedQuestionVersion),
                Map.entry("questionSpecVersion", "concept-question-spec-v2"),
                Map.entry("ability", ability), Map.entry("cognitiveOperation", cognitiveOperation),
                Map.entry("measurementContext", measurementContext),
                Map.entry("assessmentObjective", assessmentObjective),
                Map.entry("questionSpecHash", questionSpecHash),
                Map.entry("inputArtifactHash", inputArtifactHash),
                Map.entry("generationModel", generationModel),
                Map.entry("outputLanguage", outputLanguage), Map.entry("promptVersion", promptVersion),
                Map.entry("generationConfigVersion", generationConfigVersion),
                Map.entry("evidenceReferences", evidenceReferences.stream().map(r -> Map.of(
                        "bookId", r.bookId(), "tocEntryId", r.tocEntryId())).toList()));
    }
}
