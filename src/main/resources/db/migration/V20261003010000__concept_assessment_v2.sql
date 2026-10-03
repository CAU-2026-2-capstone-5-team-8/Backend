-- Preserve historical snapshots; admit explicit prior-knowledge concept diagnostics.
ALTER TABLE question
    DROP CONSTRAINT question_answer_mode_shape;

ALTER TABLE question ADD CONSTRAINT question_answer_mode_shape CHECK (
    COALESCE((
        answer_mode = 'SELF_REPORT'
        AND passage IS NULL
        AND generated_question_id IS NULL
        AND question_spec_id IS NULL
        AND choices IS NULL
        AND correct_choice_index IS NULL
        AND explanation IS NULL
        AND generated_content_hash IS NULL
        AND upstream_provenance IS NULL
    ), false)
    OR
    COALESCE((
        answer_mode = 'MULTIPLE_CHOICE'
        AND generated_question_id ~ '^gq_[0-9a-f]{32}$'
        AND question_spec_id ~ '^q_[0-9a-f]{20}$'
        AND jsonb_typeof(choices) = 'array'
        AND jsonb_array_length(choices) = 4
        AND correct_choice_index BETWEEN 0 AND 3
        AND btrim(explanation) <> ''
        AND generated_content_hash ~ '^sha256:[0-9a-f]{64}$'
        AND jsonb_typeof(upstream_provenance) = 'object'
        AND (
            (version = 'generated-question-v2' AND passage IS NULL)
            OR
            (version = 'generated-question-v5' AND passage IS NULL
                AND upstream_provenance->>'measurementContext' = 'prior-knowledge'
                AND upstream_provenance->>'questionSpecVersion' = 'concept-question-spec-v2'
                AND (
                    (upstream_provenance->>'ability' = 'meaning' AND upstream_provenance->>'cognitiveOperation' = 'recognize')
                    OR (upstream_provenance->>'ability' = 'application' AND upstream_provenance->>'cognitiveOperation' = 'apply')
                    OR (upstream_provenance->>'ability' = 'reasoning' AND upstream_provenance->>'cognitiveOperation' = 'infer')
                ))
            OR
            (version = 'generated-question-v4' AND passage IS NOT NULL AND btrim(passage) <> '')
        )
    ), false)
);

ALTER TABLE assessment_question
    DROP CONSTRAINT assessment_question_answer_mode_shape;

ALTER TABLE assessment_question ADD CONSTRAINT assessment_question_answer_mode_shape CHECK (
    COALESCE((
        answer_mode_snapshot = 'SELF_REPORT'
        AND passage_snapshot IS NULL
        AND generated_question_id_snapshot IS NULL
        AND question_spec_id_snapshot IS NULL
        AND choices_snapshot IS NULL
        AND correct_choice_index_snapshot IS NULL
        AND explanation_snapshot IS NULL
        AND generated_content_hash_snapshot IS NULL
        AND upstream_provenance_snapshot IS NULL
    ), false)
    OR
    COALESCE((
        answer_mode_snapshot = 'MULTIPLE_CHOICE'
        AND generated_question_id_snapshot ~ '^gq_[0-9a-f]{32}$'
        AND question_spec_id_snapshot ~ '^q_[0-9a-f]{20}$'
        AND jsonb_typeof(choices_snapshot) = 'array'
        AND jsonb_array_length(choices_snapshot) = 4
        AND correct_choice_index_snapshot BETWEEN 0 AND 3
        AND btrim(explanation_snapshot) <> ''
        AND generated_content_hash_snapshot ~ '^sha256:[0-9a-f]{64}$'
        AND jsonb_typeof(upstream_provenance_snapshot) = 'object'
        AND (
            (version_snapshot = 'generated-question-v2' AND passage_snapshot IS NULL)
            OR
            (version_snapshot = 'generated-question-v5' AND passage_snapshot IS NULL
                AND upstream_provenance_snapshot->>'measurementContext' = 'prior-knowledge'
                AND upstream_provenance_snapshot->>'questionSpecVersion' = 'concept-question-spec-v2'
                AND (
                    (upstream_provenance_snapshot->>'ability' = 'meaning' AND upstream_provenance_snapshot->>'cognitiveOperation' = 'recognize')
                    OR (upstream_provenance_snapshot->>'ability' = 'application' AND upstream_provenance_snapshot->>'cognitiveOperation' = 'apply')
                    OR (upstream_provenance_snapshot->>'ability' = 'reasoning' AND upstream_provenance_snapshot->>'cognitiveOperation' = 'infer')
                ))
            OR
            (version_snapshot = 'generated-question-v4'
                AND passage_snapshot IS NOT NULL
                AND btrim(passage_snapshot) <> '')
        )
    ), false)
);
