-- Nouveau role dedie aux operations de sauvegarde (etude de faisabilite du
-- 01/08/2026, backend/docs/DB_PASSWORD_ROTATION_FEASIBILITY.md) : permet un
-- compte limite a /admin/backup et /api/v1/admin/backup, sans le reste de
-- l'administration (comptes, LDAP, journal d'audit) reserve a ROLE_ADMIN.
INSERT INTO roles (name)
VALUES ('ROLE_BACKUP')
ON CONFLICT (name) DO NOTHING;
