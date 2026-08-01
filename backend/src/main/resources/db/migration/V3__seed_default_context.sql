-- =============================================================
-- Subnetory — Migration V3 : contexte de routage par défaut
-- Crée un contexte "Default" utilisable pour démarrer rapidement
-- sans configuration préalable.
-- =============================================================

INSERT INTO contexts (name, description) VALUES
    ('Default', 'Contexte par défaut');
