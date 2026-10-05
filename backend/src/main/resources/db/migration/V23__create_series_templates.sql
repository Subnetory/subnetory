-- =============================================================
-- Subnetory — Migration V23 : templates de reservation en serie
-- Templates personnalises, rattaches a un contexte reseau. Les
-- presets (Nutanix, HPE, Dell, Lenovo...) sont livres avec
-- l'application (classpath:series-templates/*.json) : ils ne sont
-- pas stockes ici.
-- La definition est un document JSON valide par l'application.
-- =============================================================

CREATE TABLE series_templates (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    context_id  BIGINT       NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    definition  TEXT         NOT NULL,
    created_by  VARCHAR(100),
    modified_by VARCHAR(100),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_series_templates_context_name
    ON series_templates (context_id, lower(name));
