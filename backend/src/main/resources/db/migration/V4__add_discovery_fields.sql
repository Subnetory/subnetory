-- =============================================================
-- Subnetory — Migration V4 : champs d'enrichissement Discovery
-- Ajout de last_seen_at et discovery_source sur la table addresses.
-- Ces champs sont utilisés par le bulk-upsert et les imports scan réseau.
-- =============================================================

ALTER TABLE addresses
    ADD COLUMN last_seen_at     TIMESTAMPTZ,
    ADD COLUMN discovery_source VARCHAR(20) NOT NULL DEFAULT 'manual'
        CONSTRAINT chk_addresses_discovery_source
            CHECK (discovery_source IN ('manual', 'api', 'csv', 'nmap', 'arp-scan', 'dns'));

-- Index sur last_seen_at pour faciliter la détection des IPs fantômes
-- (ex: WHERE last_seen_at < now() - interval '90 days')
CREATE INDEX idx_addresses_last_seen_at ON addresses (last_seen_at)
    WHERE last_seen_at IS NOT NULL;

-- Index sur discovery_source pour les rapports d'origine
CREATE INDEX idx_addresses_discovery_source ON addresses (discovery_source);
