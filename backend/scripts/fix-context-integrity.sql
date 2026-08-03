-- fix-context-integrity.sql
-- Subnetory - Correction des incoherences context_id/site_id detectees par
-- check-context-integrity.sql (categories A, B, E, F uniquement).
--
-- Ne corrige QUE les cas ou l'autorite est sans ambiguite, exactement comme
-- le fait deja le code applicatif a chaque ecriture :
--   - SubnetService : le contexte d'un Subnet doit toujours correspondre a
--     celui de son Site (buildSubnet valide deja cette regle a la creation).
--   - SubnetService : le site d'un Subnet rattache a un VLAN doit toujours
--     correspondre au site de ce VLAN (idem, deja valide a la creation).
--   - AddressService : context_id/site_id d'une Address sont toujours copies
--     depuis son Subnet a chaque create/update (address.setContext(subnet
--     .getContext()), address.setSite(subnet.getSite())).
--
-- Les categories C (contexte du Subnet vs son parent) et D (CIDR du Subnet
-- hors du parent) ne sont PAS corrigees ici : il n'y a pas d'autorite
-- evidente entre un Subnet et son parent (le probleme peut venir d'un cote
-- comme de l'autre), voir check-context-integrity.sql pour ces deux
-- categories. Elles restent a arbitrer manuellement au cas par cas.
--
-- IMPORTANT : faire une sauvegarde (backup-postgres.ps1) avant d'executer ce
-- script sur une instance de production. Transaction unique : tout est
-- annule si une seule instruction echoue.
--
-- Usage :
--   docker exec -i backend-db-1 psql -U subnetory -d subnetory \
--       -f - < backend/scripts/fix-context-integrity.sql
-- ou via le wrapper : backend/scripts/check-context-integrity.ps1 -Fix

BEGIN;

\echo 'A. Realignement du context_id des Subnets sur celui de leur Site...'
WITH fixed AS (
    UPDATE subnets s
    SET context_id = si.context_id
    FROM sites si
    WHERE s.site_id = si.id
      AND s.context_id <> si.context_id
    RETURNING s.id
)
SELECT count(*) AS subnets_corriges_categorie_a FROM fixed;

\echo 'B. Realignement du site_id des Subnets sur celui de leur VLAN...'
WITH fixed AS (
    UPDATE subnets s
    SET site_id = v.site_id
    FROM vlans v
    WHERE s.vlan_id = v.id
      AND s.vlan_id IS NOT NULL
      AND s.site_id <> v.site_id
    RETURNING s.id
)
SELECT count(*) AS subnets_corriges_categorie_b FROM fixed;

-- Reappliquer A apres B : si le site d'un subnet vient de changer (categorie
-- B), son context_id peut necessiter un nouveau realignement sur le contexte
-- du site desormais correct.
\echo 'A (2e passe). Realignement du context_id des Subnets apres correction du site...'
WITH fixed AS (
    UPDATE subnets s
    SET context_id = si.context_id
    FROM sites si
    WHERE s.site_id = si.id
      AND s.context_id <> si.context_id
    RETURNING s.id
)
SELECT count(*) AS subnets_corriges_categorie_a_2 FROM fixed;

\echo 'E. Realignement du context_id des Addresses sur celui de leur Subnet...'
WITH fixed AS (
    UPDATE addresses a
    SET context_id = s.context_id
    FROM subnets s
    WHERE a.subnet_id = s.id
      AND a.context_id <> s.context_id
    RETURNING a.id
)
SELECT count(*) AS addresses_corrigees_categorie_e FROM fixed;

\echo 'F. Realignement du site_id des Addresses sur celui de leur Subnet...'
WITH fixed AS (
    UPDATE addresses a
    SET site_id = s.site_id
    FROM subnets s
    WHERE a.subnet_id = s.id
      AND a.site_id <> s.site_id
    RETURNING a.id
)
SELECT count(*) AS addresses_corrigees_categorie_f FROM fixed;

COMMIT;

\echo ''
\echo 'Termine. Relancer check-context-integrity.sql pour confirmer : les'
\echo 'categories A, B, E, F doivent desormais renvoyer zero ligne (C et D'
\echo 'restent, le cas echeant, a arbitrer manuellement).'
