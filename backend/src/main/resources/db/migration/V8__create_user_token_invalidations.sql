-- Sprint 2.27 - User-wide JWT invalidation
-- Stores a per-subject JWT not-before threshold.

CREATE TABLE user_token_invalidations (
    username VARCHAR(100) PRIMARY KEY,
    not_before TIMESTAMPTZ NOT NULL,
    invalidated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    invalidated_by VARCHAR(100) NOT NULL,
    reason VARCHAR(50) NOT NULL
);

CREATE INDEX idx_user_token_invalidations_not_before
    ON user_token_invalidations(not_before);
