ALTER TABLE app_user ADD COLUMN bio varchar(500) NOT NULL DEFAULT '';
ALTER TABLE app_user ADD COLUMN avatar_key varchar(16) NOT NULL DEFAULT 'BOOK'
    CHECK (avatar_key IN ('BOOK','LEAF','MOON','SUN'));
CREATE TABLE user_account (
    user_id bigint PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    email varchar(254) NOT NULL UNIQUE CHECK (email=lower(email) AND btrim(email)=email),
    password_hash varchar(512) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_session (
    token_hash varchar(64) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES user_account(user_id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX account_session_user_idx ON account_session(user_id);
CREATE INDEX account_session_expiry_idx ON account_session(expires_at);
CREATE TABLE user_interest (
    user_id bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    topic_id bigint NOT NULL REFERENCES topic(id) ON DELETE CASCADE,
    PRIMARY KEY(user_id,topic_id)
);
