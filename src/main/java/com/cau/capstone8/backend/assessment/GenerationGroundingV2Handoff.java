package com.cau.capstone8.backend.assessment;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

record GenerationGroundingV2Handoff(
        int schemaVersion,
        String groundingVersion,
        String questionSpecId,
        String questionSpecHash,
        String topicId,
        String questionType,
        String cognitiveOperation,
        int targetDifficulty,
        String primaryConcept,
        List<String> relatedConcepts,
        String sourceDocumentId,
        String bookId,
        String documentType,
        String documentContentHash,
        String sourcePassageText,
        String sourcePassageHash,
        String sourcePassageExtractionPolicy,
        String displayPassageText,
        String displayPassageHash,
        String displayNormalizationPolicy,
        String sourceId,
        String provider,
        String sourceType,
        String sourceUrl,
        String sourceRetrievedAt,
        String license,
        String rightsNote,
        String sourceContentHash,
        String editionRelation,
        Map<String, String> canonicalFileHashes,
        String blueprintHash) {
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "preface", "introduction", "preview", "sample_chapter", "other");
    private static final Set<String> EDITION_RELATIONS = Set.of(
            "exact", "same_work", "unspecified");
    private static final Set<String> CANONICAL_FILES = Set.of(
            "books.jsonl", "documents.jsonl", "sources.jsonl", "toc.jsonl");

    GenerationGroundingV2Handoff {
        GeneratedQuestionHandoffSupport.required(groundingVersion, "grounding_version");
        GeneratedQuestionHandoffSupport.required(questionSpecId, "question_spec_id");
        GeneratedQuestionHandoffSupport.required(questionSpecHash, "question_spec_hash");
        GeneratedQuestionHandoffSupport.required(topicId, "topic_id");
        GeneratedQuestionHandoffSupport.required(questionType, "question_type");
        GeneratedQuestionHandoffSupport.required(cognitiveOperation, "cognitive_operation");
        GeneratedQuestionHandoffSupport.required(primaryConcept, "primary_concept");
        GeneratedQuestionHandoffSupport.required(sourceDocumentId, "source_document_id");
        GeneratedQuestionHandoffSupport.required(bookId, "book_id");
        GeneratedQuestionHandoffSupport.required(documentType, "document_type");
        GeneratedQuestionHandoffSupport.required(documentContentHash, "document_content_hash");
        GeneratedQuestionHandoffSupport.required(sourcePassageText, "source_passage_text");
        GeneratedQuestionHandoffSupport.required(sourcePassageHash, "source_passage_hash");
        GeneratedQuestionHandoffSupport.required(
                sourcePassageExtractionPolicy, "source_passage_extraction_policy");
        GeneratedQuestionHandoffSupport.required(displayPassageText, "display_passage_text");
        GeneratedQuestionHandoffSupport.required(displayPassageHash, "display_passage_hash");
        GeneratedQuestionHandoffSupport.required(
                displayNormalizationPolicy, "display_normalization_policy");
        GeneratedQuestionHandoffSupport.required(sourceId, "source_id");
        GeneratedQuestionHandoffSupport.required(provider, "provider");
        GeneratedQuestionHandoffSupport.required(sourceType, "source_type");
        GeneratedQuestionHandoffSupport.required(sourceUrl, "source_url");
        GeneratedQuestionHandoffSupport.required(sourceRetrievedAt, "source_retrieved_at");
        GeneratedQuestionHandoffSupport.required(license, "license");
        GeneratedQuestionHandoffSupport.required(sourceContentHash, "source_content_hash");
        GeneratedQuestionHandoffSupport.required(editionRelation, "edition_relation");
        GeneratedQuestionHandoffSupport.required(blueprintHash, "blueprint_hash");

        relatedConcepts = GeneratedQuestionHandoffSupport.immutableTrimmedStrings(
                relatedConcepts, "related_concepts", true);

        if (schemaVersion != 2 || !"generation-grounding-v2".equals(groundingVersion)) {
            GeneratedQuestionHandoffSupport.fail("unsupported grounding schema or version");
        }
        GeneratedQuestionHandoffSupport.specId(questionSpecId);
        if (!"comprehension".equals(questionType)
                || !"apply".equals(cognitiveOperation)
                || targetDifficulty != 2
                || !relatedConcepts.isEmpty()) {
            GeneratedQuestionHandoffSupport.fail(
                    "generation-grounding-v2 supports only single-concept comprehension/apply difficulty 2");
        }
        if (!"first-concept-sentence-window-v1".equals(sourcePassageExtractionPolicy)
                || !"pdf-display-normalization-v1".equals(displayNormalizationPolicy)) {
            GeneratedQuestionHandoffSupport.fail("unsupported grounding passage policy");
        }
        if (!DOCUMENT_TYPES.contains(documentType)
                || !EDITION_RELATIONS.contains(editionRelation)) {
            GeneratedQuestionHandoffSupport.fail("grounding document provenance is invalid");
        }
        try {
            OffsetDateTime.parse(sourceRetrievedAt);
        } catch (DateTimeParseException exception) {
            throw new QuestionImportException("source_retrieved_at is invalid", exception);
        }
        if (sourcePassageText.length() < 600 || sourcePassageText.length() > 1800) {
            GeneratedQuestionHandoffSupport.fail("source passage length is outside the contract");
        }
        if (displayPassageText.length() < 600 || displayPassageText.length() > 2000) {
            GeneratedQuestionHandoffSupport.fail("display passage length is outside the contract");
        }

        GeneratedQuestionHandoffSupport.hash(questionSpecHash, "question_spec_hash");
        GeneratedQuestionHandoffSupport.hash(documentContentHash, "document_content_hash");
        GeneratedQuestionHandoffSupport.hash(sourcePassageHash, "source_passage_hash");
        GeneratedQuestionHandoffSupport.hash(displayPassageHash, "display_passage_hash");
        GeneratedQuestionHandoffSupport.hash(sourceContentHash, "source_content_hash");
        GeneratedQuestionHandoffSupport.hash(blueprintHash, "blueprint_hash");
        if (canonicalFileHashes == null
                || !canonicalFileHashes.keySet().equals(CANONICAL_FILES)) {
            GeneratedQuestionHandoffSupport.fail(
                    "canonical file hashes must cover all four JSONL inputs");
        }
        canonicalFileHashes = Map.copyOf(canonicalFileHashes);
        canonicalFileHashes.forEach(
                (name, hash) -> GeneratedQuestionHandoffSupport.hash(
                        hash, "canonical_file_hashes[" + name + "]"));

        if (!sourcePassageHash.equals(GeneratedQuestionHandoffSupport.sha256(sourcePassageText))) {
            GeneratedQuestionHandoffSupport.fail(
                    "source passage hash does not match source passage text");
        }
        if (!displayPassageHash.equals(GeneratedQuestionHandoffSupport.sha256(displayPassageText))) {
            GeneratedQuestionHandoffSupport.fail(
                    "display passage hash does not match display passage text");
        }
    }

    Map<String, Object> safeSourceProvenance() {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("sourceId", sourceId);
        values.put("provider", provider);
        values.put("sourceType", sourceType);
        values.put("sourceUrl", sourceUrl);
        values.put("sourceRetrievedAt", sourceRetrievedAt);
        values.put("license", license);
        if (rightsNote != null) {
            values.put("rightsNote", rightsNote);
        }
        values.put("bookId", bookId);
        values.put("documentType", documentType);
        values.put("documentContentHash", documentContentHash);
        values.put("sourceContentHash", sourceContentHash);
        values.put("questionSpecHash", questionSpecHash);
        values.put("blueprintHash", blueprintHash);
        values.put("canonicalFileHashes", canonicalFileHashes);
        return Map.copyOf(values);
    }
}
