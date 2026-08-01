-- Sauvegardes (Phase 7 audit, 31/07/2026) : historique des executions
-- (planifiees, manuelles, ou sauvegarde de securite avant restauration).
CREATE TABLE backup_runs (
    id                  BIGSERIAL PRIMARY KEY,
    -- SCHEDULED | MANUAL | PRE_RESTORE_SAFETY
    trigger_source      VARCHAR(30) NOT NULL,
    -- RUNNING | SUCCESS | FAILED
    status              VARCHAR(20) NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,
    file_name           VARCHAR(255),
    file_size_bytes     BIGINT,
    checksum_sha256     VARCHAR(64),
    error_message       VARCHAR(1000),
    -- Nom d'utilisateur a l'origine du declenchement manuel ; NULL si
    -- declenchee automatiquement par le planificateur.
    triggered_by        VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_backup_runs_started_at ON backup_runs (started_at DESC);
CREATE INDEX idx_backup_runs_status ON backup_runs (status);

COMMENT ON TABLE backup_runs IS
    'Historique des sauvegardes de la base (planifiees, manuelles, ou de securite pre-restauration).';
