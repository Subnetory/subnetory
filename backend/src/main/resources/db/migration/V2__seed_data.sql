-- =============================================================
-- Subnetory — Migration V2 : données de seed
-- Rôles applicatifs et utilisateur admin initial.
-- Le mot de passe par défaut "admin" DOIT être changé après le premier login.
-- =============================================================

INSERT INTO roles (name) VALUES
    ('ROLE_ADMIN'),
    ('ROLE_NETWORK'),
    ('ROLE_IP'),
    ('ROLE_STOCK'),
    ('ROLE_STKADMIN'),
    ('ROLE_TEMPLATE'),
    ('ROLE_PHOTO');

-- Compte admin créé sans mot de passe — DataInitializer le générera au premier
-- démarrage avec un BCrypt frais (force 12) du mot de passe "admin".
-- Cela évite de hardcoder un hash et permet de réinitialiser le password admin
-- en remettant la colonne à NULL en base.
INSERT INTO users (username, password, email, auth_type, enabled) VALUES
    ('admin', NULL, 'admin@subnetory.local', 'LOCAL', true);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.username = 'admin'
  AND r.name IN ('ROLE_ADMIN', 'ROLE_NETWORK', 'ROLE_IP');
