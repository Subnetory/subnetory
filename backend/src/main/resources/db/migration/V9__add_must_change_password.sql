-- Sprint 2.29 - Mandatory initial password change

ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;
