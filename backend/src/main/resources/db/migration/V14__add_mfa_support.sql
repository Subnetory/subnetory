ALTER TABLE users
    ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN mfa_secret_encrypted TEXT;

CREATE TABLE mfa_recovery_codes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mfa_recovery_codes_user_id ON mfa_recovery_codes(user_id);
