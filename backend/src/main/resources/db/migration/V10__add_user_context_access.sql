-- =============================================================
-- Autorisations explicites par contexte
--
-- ROLE_ADMIN conserve un acces global gere cote serveur.
-- Tous les autres comptes sont refuses par defaut tant qu'aucun
-- contexte ne leur est attribue dans cette table.
-- =============================================================

CREATE TABLE user_contexts (
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    context_id BIGINT      NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, context_id)
);

CREATE INDEX idx_user_contexts_context ON user_contexts(context_id);

