-- =============================================================
-- Subnetory — Migration V1 : schéma initial
-- PostgreSQL 14+ requis (utilise les types cidr, inet et macaddr natifs)
-- =============================================================

-- -------------------------------------------------------------
-- Contextes de routage (VRF)
-- -------------------------------------------------------------
CREATE TABLE contexts (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- -------------------------------------------------------------
-- Sites
-- -------------------------------------------------------------
CREATE TABLE sites (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(20)  NOT NULL UNIQUE,
    context_id  BIGINT       NOT NULL REFERENCES contexts(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_sites_context ON sites(context_id);

-- -------------------------------------------------------------
-- VLANs
-- -------------------------------------------------------------
CREATE TABLE vlans (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100),
    vid         SMALLINT     NOT NULL CHECK (vid BETWEEN 0 AND 4094),
    site_id     BIGINT       NOT NULL REFERENCES sites(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (vid, site_id)
);

CREATE INDEX idx_vlans_site ON vlans(site_id);

-- -------------------------------------------------------------
-- Sous-réseaux
-- -------------------------------------------------------------
CREATE TABLE subnets (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    network     CIDR         NOT NULL,
    description TEXT,
    gateway     INET,
    context_id  BIGINT       NOT NULL REFERENCES contexts(id),
    site_id     BIGINT       NOT NULL REFERENCES sites(id),
    vlan_id     BIGINT       REFERENCES vlans(id),
    parent_id   BIGINT       REFERENCES subnets(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (network, site_id)
);

CREATE INDEX idx_subnets_network_gist ON subnets USING GIST (network inet_ops);
CREATE INDEX idx_subnets_site ON subnets(site_id);
CREATE INDEX idx_subnets_vlan ON subnets(vlan_id);

-- -------------------------------------------------------------
-- Adresses IP
-- -------------------------------------------------------------
CREATE TABLE addresses (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    address      INET         NOT NULL UNIQUE,
    mac          MACADDR,
    hostname     VARCHAR(100),
    description  TEXT,
    context_id   BIGINT       NOT NULL REFERENCES contexts(id),
    site_id      BIGINT       NOT NULL REFERENCES sites(id),
    subnet_id    BIGINT       NOT NULL REFERENCES subnets(id),
    modified_by  VARCHAR(100),
    is_temporary BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_addresses_host_prefix CHECK (
        (family(address) = 4 AND masklen(address) = 32)
        OR
        (family(address) = 6 AND masklen(address) = 128)
    )
);

CREATE INDEX idx_addresses_address_gist ON addresses USING GIST (address inet_ops);
CREATE INDEX idx_addresses_subnet ON addresses(subnet_id);
CREATE INDEX idx_addresses_hostname ON addresses(hostname);

-- -------------------------------------------------------------
-- Rôles & utilisateurs
-- -------------------------------------------------------------
CREATE TABLE roles (
    id   BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE users (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username   VARCHAR(100) NOT NULL UNIQUE,
    password   VARCHAR(255),
    email      VARCHAR(255),
    auth_type  VARCHAR(20)  NOT NULL DEFAULT 'LOCAL'
                   CHECK (auth_type IN ('LOCAL', 'LDAP')),
    enabled    BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
