-- =============================================================
-- Subnetory - Migration V5 : allow XLSX discovery source
-- =============================================================
-- Sprint 2.11 adds XLSX address import.
-- AddressXlsxParser uses discovery_source = 'xlsx' by default.
-- The database CHECK constraint must therefore allow this value.

ALTER TABLE addresses
    DROP CONSTRAINT chk_addresses_discovery_source;

ALTER TABLE addresses
    ADD CONSTRAINT chk_addresses_discovery_source
        CHECK (discovery_source IN ('manual', 'api', 'csv', 'xlsx', 'nmap', 'arp-scan', 'dns'));