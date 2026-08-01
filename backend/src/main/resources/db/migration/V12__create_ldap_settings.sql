CREATE TABLE ldap_settings (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT false,
    url VARCHAR(512) NOT NULL,
    base_dn VARCHAR(512) NOT NULL,
    user_search_base VARCHAR(512) NOT NULL,
    user_search_filter VARCHAR(512) NOT NULL,
    manager_dn VARCHAR(512),
    manager_password_encrypted TEXT,
    default_role VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
