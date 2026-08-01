-- Verrouillage optimiste (audit du 31/07/2026) : sans colonne de version,
-- deux administrateurs qui modifient la meme adresse ou le meme sous-reseau
-- en meme temps s'ecrasent silencieusement -- le dernier "save" gagne, sans
-- detection, sans message, sans trace. Ajoute sur Address et Subnet
-- uniquement (les entites les plus disputees), pas sur Site/Vlan/Context
-- (edition beaucoup plus rare en pratique).
--
-- DEFAULT 0 : les lignes existantes demarrent a la version 0, compatible
-- avec @Version qui incremente a chaque UPDATE reussi. Colonne NOT NULL
-- des la creation, pas de migration en deux temps necessaire (DEFAULT
-- s'applique aux lignes deja presentes sur ALTER TABLE ... ADD COLUMN).

ALTER TABLE addresses ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE subnets   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
