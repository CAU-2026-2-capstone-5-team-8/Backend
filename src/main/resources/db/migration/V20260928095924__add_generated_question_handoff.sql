ALTER TABLE question
    ADD COLUMN answer_mode varchar(30) NOT NULL DEFAULT 'SELF_REPORT'
        CHECK (answer_mode IN ('SELF_REPORT','MULTIPLE_CHOICE')),
    ADD COLUMN generated_question_id varchar(35),
    ADD COLUMN question_spec_id varchar(22),
    ADD COLUMN choices jsonb,
    ADD COLUMN correct_choice_index int,
    ADD COLUMN explanation text,
    ADD COLUMN generated_content_hash varchar(71),
    ADD COLUMN upstream_provenance jsonb;

CREATE UNIQUE INDEX question_generated_question_id_unique
    ON question(generated_question_id) WHERE generated_question_id IS NOT NULL;

ALTER TABLE question ADD CONSTRAINT question_answer_mode_shape CHECK (
    COALESCE((
        answer_mode = 'SELF_REPORT'
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
    ), false)
);

ALTER TABLE assessment_question
    ADD COLUMN answer_mode_snapshot varchar(30) NOT NULL DEFAULT 'SELF_REPORT'
        CHECK (answer_mode_snapshot IN ('SELF_REPORT','MULTIPLE_CHOICE')),
    ADD COLUMN generated_question_id_snapshot varchar(35),
    ADD COLUMN question_spec_id_snapshot varchar(22),
    ADD COLUMN choices_snapshot jsonb,
    ADD COLUMN correct_choice_index_snapshot int,
    ADD COLUMN explanation_snapshot text,
    ADD COLUMN generated_content_hash_snapshot varchar(71),
    ADD COLUMN upstream_provenance_snapshot jsonb;

ALTER TABLE assessment_question ADD CONSTRAINT assessment_question_answer_mode_shape CHECK (
    COALESCE((
        answer_mode_snapshot = 'SELF_REPORT'
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
    ), false)
);

ALTER TABLE assessment_answer
    ALTER COLUMN knows_concept DROP NOT NULL,
    ADD COLUMN answer_mode varchar(30) NOT NULL DEFAULT 'SELF_REPORT'
        CHECK (answer_mode IN ('SELF_REPORT','MULTIPLE_CHOICE')),
    ADD COLUMN selected_choice_index int,
    ADD COLUMN correct boolean;

ALTER TABLE assessment_answer ADD CONSTRAINT assessment_answer_mode_shape CHECK (
    COALESCE((
        answer_mode = 'SELF_REPORT'
        AND knows_concept IS NOT NULL
        AND selected_choice_index IS NULL
        AND correct IS NULL
    ), false)
    OR
    COALESCE((
        answer_mode = 'MULTIPLE_CHOICE'
        AND knows_concept IS NULL
        AND selected_choice_index BETWEEN 0 AND 3
        AND correct IS NOT NULL
    ), false)
);
