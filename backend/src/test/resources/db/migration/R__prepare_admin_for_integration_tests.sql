-- Integration-test fixture only.
--
-- Production still bootstraps the local admin with
-- must_change_password = true.
--
-- The historical integration tests authenticate with admin/admin.
-- This repeatable test migration provides a BCrypt cost-12 hash
-- and marks that test-only account as already rotated.

UPDATE users
SET password = '$2y$12$ZtK.oqTZe6tduKtsbs22HerVh7qDe/LXqG1ymOYchVHkXbYaawrQy',
    must_change_password = false
WHERE username = 'admin';

-- Les anciens tests Web utilisent ces principaux simulés. On leur attribue
-- explicitement tous les contextes dans l'environnement de test uniquement.
INSERT INTO users (username, auth_type, enabled, must_change_password)
VALUES ('user', 'LOCAL', true, false),
       ('testuser', 'LOCAL', true, false),
       ('ipuser', 'LOCAL', true, false)
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_contexts (user_id, context_id)
SELECT u.id, c.id
FROM users u
CROSS JOIN contexts c
WHERE u.username IN ('user', 'testuser', 'ipuser')
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION grant_context_to_legacy_test_users()
RETURNS trigger AS $$
BEGIN
    INSERT INTO user_contexts (user_id, context_id)
    SELECT id, NEW.id FROM users
    WHERE username IN ('user', 'testuser', 'ipuser')
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_grant_context_to_legacy_test_users ON contexts;
CREATE TRIGGER trg_grant_context_to_legacy_test_users
AFTER INSERT ON contexts
FOR EACH ROW EXECUTE FUNCTION grant_context_to_legacy_test_users();
