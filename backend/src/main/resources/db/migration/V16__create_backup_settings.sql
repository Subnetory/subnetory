-- Sauvegardes (Phase 7 audit, 31/07/2026) : configuration singleton, meme
-- pattern que ldap_settings (V12) — une seule ligne, id fixe.
CREATE TABLE backup_settings (
    id                BIGINT PRIMARY KEY CHECK (id = 1),
    enabled           BOOLEAN NOT NULL DEFAULT false,
    -- Expression cron a 6 champs (secondes incluses), meme convention que
    -- les autres taches planifiees de l'application (ex: subnetory.jwt.
    -- revocation.purge.cron). Defaut : tous les jours a 2h du matin.
    cron_expression   VARCHAR(100) NOT NULL DEFAULT '0 0 2 * * *',
    -- Nombre de sauvegardes reussies a conserver ; les plus anciennes au-dela
    -- de ce nombre sont purgees automatiquement apres chaque execution.
    retention_count   INT NOT NULL DEFAULT 14,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE backup_settings IS
    'Configuration de la sauvegarde automatique de la base (singleton, id=1).';
