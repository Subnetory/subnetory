-- Sauvegardes (Phase 7 audit, 31/07/2026) : journal des restaurations.
-- Table separee de backup_runs (une restauration n'est pas une sauvegarde),
-- afin de tracer distinctement qui a restaure quoi, quand, et a partir de
-- quel fichier — traçabilite complete exigee pour une operation aussi
-- sensible (cf. backend/docs/RESTORE_OPERATIONS.md).
CREATE TABLE backup_restores (
    id                      BIGSERIAL PRIMARY KEY,
    -- Sauvegarde source utilisee pour la restauration.
    backup_run_id           BIGINT NOT NULL REFERENCES backup_runs (id),
    -- Sauvegarde de securite prise automatiquement juste avant la
    -- restauration (permet un retour arriere en cas d'erreur).
    safety_backup_run_id    BIGINT REFERENCES backup_runs (id),
    -- RUNNING | SUCCESS | FAILED
    status                  VARCHAR(20) NOT NULL,
    started_at              TIMESTAMPTZ NOT NULL,
    finished_at             TIMESTAMPTZ,
    error_message           VARCHAR(1000),
    performed_by            VARCHAR(100) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_backup_restores_started_at ON backup_restores (started_at DESC);

COMMENT ON TABLE backup_restores IS
    'Journal des operations de restauration de la base (qui, quand, a partir de quelle sauvegarde).';
