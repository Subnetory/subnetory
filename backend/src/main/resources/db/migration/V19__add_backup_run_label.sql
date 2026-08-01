-- Sauvegardes (audit du 01/08/2026) : titre/commentaire optionnel saisi par
-- l'administrateur au declenchement d'une sauvegarde manuelle, pour la
-- retrouver facilement dans l'historique (ex: "avant migration V19").
--
-- IF NOT EXISTS (01/08/2026) : sur un environnement de dev ayant subi
-- plusieurs redemarrages de conteneur pendant des essais, la colonne a pu
-- se retrouver deja presente en base sans que Flyway n'ait enregistre V19
-- comme appliquee, provoquant une erreur bloquante ("column already
-- exists") au demarrage. Rendu idempotent ; sans risque de divergence de
-- checksum car V19 n'a encore ete valide avec succes sur aucun
-- environnement (aucune version taguee ne l'inclut).
ALTER TABLE backup_runs ADD COLUMN IF NOT EXISTS label VARCHAR(200);

COMMENT ON COLUMN backup_runs.label IS
    'Titre/commentaire optionnel saisi par l''administrateur (sauvegarde manuelle ou import).';
