-- check-context-integrity.sql
-- Subnetory - Diagnostic (lecture seule) des incoherences context_id/site_id
-- residuelles d'une instance mise a jour depuis une version anterieure aux
-- correctifs v0.8.1 (deplacement Site/VLAN) et v0.8.2 (deplacement Subnet).
--
-- Contexte : Subnet et Address stockent leur propre context_id/site_id,
-- jamais resynchronise automatiquement par une contrainte de base de
-- donnees. Avant ces correctifs, rien n'empechait de changer le contexte
-- d'un Site, le site d'un VLAN, ou le contexte/site/CIDR d'un Subnet alors
-- que des enfants (Subnet, Address) existaient encore en dessous : ces
-- enfants gardaient leurs anciennes valeurs, jamais mises a jour.
--
-- Sur une instance dont les donnees n'ont jamais transite par ces bugs (ou
-- qui n'existait pas encore avant le correctif), aucune requete ci-dessous
-- ne doit renvoyer de ligne. Ce script n'ecrit rien ; voir
-- fix-context-integrity.sql pour la correction des categories qui peuvent
-- etre corrigees sans ambiguite.
--
-- Usage :
--   docker exec -i backend-db-1 psql -U subnetory -d subnetory \
--       -f - < backend/scripts/check-context-integrity.sql
-- ou via le wrapper : backend/scripts/check-context-integrity.ps1

\echo '=== A. Subnets dont le context_id ne correspond plus a celui de leur site ==='
\echo '    (residu d''un changement de contexte du Site avant le correctif v0.8.1)'
SELECT s.id AS subnet_id, s.network, s.context_id AS subnet_context_id,
       si.id AS site_id, si.context_id AS site_context_id
FROM subnets s
JOIN sites si ON si.id = s.site_id
WHERE s.context_id <> si.context_id
ORDER BY s.id;

\echo ''
\echo '=== B. Subnets dont le site_id ne correspond plus a celui de leur VLAN ==='
\echo '    (residu d''un changement de site du VLAN avant le correctif v0.8.1)'
SELECT s.id AS subnet_id, s.network, s.site_id AS subnet_site_id,
       v.id AS vlan_id, v.site_id AS vlan_site_id
FROM subnets s
JOIN vlans v ON v.id = s.vlan_id
WHERE s.vlan_id IS NOT NULL
  AND s.site_id <> v.site_id
ORDER BY s.id;

\echo ''
\echo '=== C. Subnets dont le context_id ne correspond plus a celui de leur parent ==='
\echo '    (residu d''un changement de contexte du parent avant le correctif v0.8.2)'
\echo '    ATTENTION : categorie non corrigee automatiquement par fix-context-integrity.sql,'
\echo '    l''ambiguite (corriger l''enfant ou reconsiderer le lien parent) demande un arbitrage manuel.'
SELECT s.id AS subnet_id, s.network, s.context_id AS subnet_context_id,
       p.id AS parent_id, p.network AS parent_network, p.context_id AS parent_context_id
FROM subnets s
JOIN subnets p ON p.id = s.parent_id
WHERE s.parent_id IS NOT NULL
  AND s.context_id <> p.context_id
ORDER BY s.id;

\echo ''
\echo '=== D. Subnets dont le reseau CIDR n''est plus contenu dans celui de leur parent ==='
\echo '    (residu d''un changement de CIDR du parent avant le correctif v0.8.2)'
\echo '    ATTENTION : categorie non corrigee automatiquement, meme raison que C.'
SELECT s.id AS subnet_id, s.network AS subnet_network,
       p.id AS parent_id, p.network AS parent_network
FROM subnets s
JOIN subnets p ON p.id = s.parent_id
WHERE s.parent_id IS NOT NULL
  AND NOT (s.network <<= p.network)
ORDER BY s.id;

\echo ''
\echo '=== E. Addresses dont le context_id ne correspond plus a celui de leur subnet ==='
\echo '    (residu d''un changement de contexte du Subnet avant le correctif v0.8.2)'
SELECT a.id AS address_id, a.address, a.context_id AS address_context_id,
       s.id AS subnet_id, s.context_id AS subnet_context_id
FROM addresses a
JOIN subnets s ON s.id = a.subnet_id
WHERE a.context_id <> s.context_id
ORDER BY a.id;

\echo ''
\echo '=== F. Addresses dont le site_id ne correspond plus a celui de leur subnet ==='
\echo '    (residu d''un changement de site du Subnet avant le correctif v0.8.2)'
SELECT a.id AS address_id, a.address, a.site_id AS address_site_id,
       s.id AS subnet_id, s.site_id AS subnet_site_id
FROM addresses a
JOIN subnets s ON s.id = a.subnet_id
WHERE a.site_id <> s.site_id
ORDER BY a.id;

\echo ''
\echo '=== Resume ==='
SELECT
    (SELECT count(*) FROM subnets s JOIN sites si ON si.id = s.site_id WHERE s.context_id <> si.context_id) AS a_subnet_vs_site,
    (SELECT count(*) FROM subnets s JOIN vlans v ON v.id = s.vlan_id WHERE s.vlan_id IS NOT NULL AND s.site_id <> v.site_id) AS b_subnet_vs_vlan,
    (SELECT count(*) FROM subnets s JOIN subnets p ON p.id = s.parent_id WHERE s.parent_id IS NOT NULL AND s.context_id <> p.context_id) AS c_subnet_vs_parent_context,
    (SELECT count(*) FROM subnets s JOIN subnets p ON p.id = s.parent_id WHERE s.parent_id IS NOT NULL AND NOT (s.network <<= p.network)) AS d_subnet_vs_parent_cidr,
    (SELECT count(*) FROM addresses a JOIN subnets s ON s.id = a.subnet_id WHERE a.context_id <> s.context_id) AS e_address_vs_subnet_context,
    (SELECT count(*) FROM addresses a JOIN subnets s ON s.id = a.subnet_id WHERE a.site_id <> s.site_id) AS f_address_vs_subnet_site;
