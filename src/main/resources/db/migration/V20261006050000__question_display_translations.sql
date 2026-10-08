-- Canonical question content and issued assessment snapshots remain unchanged.
CREATE TABLE question_display_translation (
    question_id bigint NOT NULL REFERENCES question(id),
    source_hash varchar(71) NOT NULL,
    language varchar(10) NOT NULL DEFAULT 'ko' CHECK(language='ko'),
    version varchar(50) NOT NULL DEFAULT 'question-translation-ko-v1',
    status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','TRANSLATING','READY','FAILED')),
    source_text jsonb NOT NULL,
    translated_text jsonb,
    model varchar(100),
    claim_token uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    PRIMARY KEY(question_id,source_hash,language),
    CHECK((status='TRANSLATING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
       OR (status<>'TRANSLATING' AND claim_token IS NULL AND lease_until IS NULL)),
    CHECK(status<>'READY' OR (translated_text IS NOT NULL AND model IS NOT NULL))
);
