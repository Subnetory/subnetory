CREATE TABLE auth_audit_log (
    id BIGSERIAL PRIMARY KEY,

    event_type VARCHAR(50) NOT NULL,

    username VARCHAR(100),
    target_username VARCHAR(100),

    ip_address VARCHAR(100),
    user_agent VARCHAR(255),

    success BOOLEAN NOT NULL DEFAULT TRUE,
    message VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_auth_audit_log_created_at
    ON auth_audit_log (created_at DESC);

CREATE INDEX idx_auth_audit_log_event_type
    ON auth_audit_log (event_type);

CREATE INDEX idx_auth_audit_log_username
    ON auth_audit_log (username);

CREATE INDEX idx_auth_audit_log_target_username
    ON auth_audit_log (target_username);