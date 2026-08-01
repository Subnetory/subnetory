-- =============================================================
-- Subnetory - Migration V7 : revoked JWT denylist
-- =============================================================
-- Sprint 2.26 adds explicit access-token revocation for the REST API.
-- Tokens are identified by their standard JWT ID claim (jti).

CREATE TABLE revoked_tokens (
    jti        VARCHAR(36)  PRIMARY KEY,
    username   VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason     VARCHAR(50)  NOT NULL
);

CREATE INDEX idx_revoked_tokens_expires_at
    ON revoked_tokens (expires_at);
