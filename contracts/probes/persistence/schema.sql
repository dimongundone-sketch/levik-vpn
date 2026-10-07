-- HelloKittyVPN Relational Schema (PostgreSQL 16+/17)
-- Contract: backend/docs/data-model.md
-- Freeze: Gate G01 Contract & Security Freeze

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- UUIDv7 deterministic function (RFC 9562)
CREATE OR REPLACE FUNCTION uuid_generate_v7() RETURNS uuid AS $$
DECLARE
    v_time timestamptz := clock_timestamp();
    v_epoch_ms bigint := floor(extract(epoch from v_time) * 1000)::bigint;
    v_bytes bytea := gen_random_bytes(16);
BEGIN
    -- 48-bit timestamp (ms)
    v_bytes := set_byte(v_bytes, 0, ((v_epoch_ms >> 40) & 255)::int);
    v_bytes := set_byte(v_bytes, 1, ((v_epoch_ms >> 32) & 255)::int);
    v_bytes := set_byte(v_bytes, 2, ((v_epoch_ms >> 24) & 255)::int);
    v_bytes := set_byte(v_bytes, 3, ((v_epoch_ms >> 16) & 255)::int);
    v_bytes := set_byte(v_bytes, 4, ((v_epoch_ms >> 8) & 255)::int);
    v_bytes := set_byte(v_bytes, 5, (v_epoch_ms & 255)::int);
    -- 4-bit version 7 in high nibble of byte 6
    v_bytes := set_byte(v_bytes, 6, ((get_byte(v_bytes, 6) & 15) | 112)::int);
    -- 2-bit variant 1 (0b10) in high bits of byte 8
    v_bytes := set_byte(v_bytes, 8, ((get_byte(v_bytes, 8) & 63) | 128)::int);
    RETURN encode(v_bytes, 'hex')::uuid;
END;
$$ LANGUAGE plpgsql VOLATILE;

-- 1. Devices
CREATE TABLE devices (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id               CHAR(64) NOT NULL,
    spki_der                BYTEA NOT NULL,
    key_algorithm           VARCHAR(32) NOT NULL DEFAULT 'RSA-3072',
    app_version             VARCHAR(32) NOT NULL,
    os_version              VARCHAR(32) NOT NULL,
    device_model            VARCHAR(64),
    status                  VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_seen_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_devices_device_id UNIQUE (device_id),
    CONSTRAINT ck_devices_status CHECK (status IN ('active', 'revoked', 'blocked')),
    CONSTRAINT ck_devices_device_id_hex CHECK (device_id ~ '^[0-9a-f]{64}$')
);

-- 2. Invitations
CREATE TABLE invitations (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    code_hash   CHAR(64) NOT NULL,
    max_devices INT NOT NULL DEFAULT 1,
    used_count  INT NOT NULL DEFAULT 0,
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_invitations_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_invitations_status CHECK (status IN ('active', 'exhausted', 'expired', 'revoked')),
    CONSTRAINT ck_invitations_counts CHECK (used_count >= 0 AND used_count <= max_devices),
    CONSTRAINT ck_invitations_code_hash_hex CHECK (code_hash ~ '^[0-9a-f]{64}$')
);

-- 3. Access Grants
CREATE TABLE access_grants (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id      UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    invitation_id  UUID REFERENCES invitations(id) ON DELETE RESTRICT,
    status         VARCHAR(20) NOT NULL DEFAULT 'active',
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revocation_reason VARCHAR(64),

    CONSTRAINT ck_access_grants_status CHECK (status IN ('active', 'expired', 'revoked'))
);

-- 4. Challenges
CREATE TABLE challenges (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id           UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    challenge_nonce     CHAR(64) NOT NULL,
    purpose             VARCHAR(32) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'pending',
    invitation_id       UUID REFERENCES invitations(id) ON DELETE RESTRICT,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    consumed_at         TIMESTAMPTZ,

    CONSTRAINT uq_challenges_nonce UNIQUE (challenge_nonce),
    CONSTRAINT ck_challenges_status CHECK (status IN ('pending', 'consumed', 'expired')),
    CONSTRAINT ck_challenges_nonce_hex CHECK (challenge_nonce ~ '^[0-9a-f]{64}$')
);

-- 5. Nodes
CREATE TABLE nodes (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    node_id        VARCHAR(64) NOT NULL,
    display_name   VARCHAR(128) NOT NULL,
    country_code   CHAR(2) NOT NULL,
    city           VARCHAR(64),
    status         VARCHAR(20) NOT NULL DEFAULT 'online',
    generation     BIGINT NOT NULL DEFAULT 1,
    endpoint_ipv4  INET,
    endpoint_ipv6  INET,
    endpoint_domain VARCHAR(255),
    grpc_endpoint  VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_nodes_node_id UNIQUE (node_id),
    CONSTRAINT ck_nodes_status CHECK (status IN ('online', 'offline', 'draining', 'disabled')),
    CONSTRAINT ck_nodes_country_code CHECK (country_code ~ '^[A-Z]{2}$')
);

-- 6. Node Capabilities
CREATE TABLE node_capabilities (
    node_id     UUID NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    protocol    VARCHAR(32) NOT NULL,
    port        INT NOT NULL,
    is_active   BOOLEAN NOT NULL DEFAULT true,

    PRIMARY KEY (node_id, protocol, port),
    CONSTRAINT ck_node_cap_port CHECK (port > 0 AND port <= 65535)
);

-- 7. Credentials
CREATE TABLE credentials (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id          UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    node_id            UUID NOT NULL REFERENCES nodes(id) ON DELETE RESTRICT,
    protocol           VARCHAR(32) NOT NULL DEFAULT 'vless',
    key_id             VARCHAR(64) NOT NULL,
    aead_nonce         BYTEA NOT NULL,
    aead_ciphertext    BYTEA NOT NULL,
    fingerprint_sha256 CHAR(64) NOT NULL,
    desired_revision   BIGINT NOT NULL DEFAULT 1,
    observed_revision  BIGINT NOT NULL DEFAULT 0,
    status             VARCHAR(20) NOT NULL DEFAULT 'pending',
    expires_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_credentials_device_node UNIQUE (device_id, node_id),
    CONSTRAINT uq_credentials_node_fingerprint UNIQUE (node_id, fingerprint_sha256),
    CONSTRAINT ck_credentials_status CHECK (status IN ('pending', 'active', 'revoking', 'revoked', 'expired')),
    CONSTRAINT ck_credentials_fingerprint_hex CHECK (fingerprint_sha256 ~ '^[0-9a-f]{64}$')
);

-- 8. Leases
CREATE TABLE leases (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    node_id              UUID NOT NULL REFERENCES nodes(id) ON DELETE RESTRICT,
    device_id            UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    allocated_ip         INET NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'active',
    allocated_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at           TIMESTAMPTZ NOT NULL,
    purge_after          TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_leases_status CHECK (status IN ('active', 'tombstone', 'released')),
    CONSTRAINT ck_leases_purge_window CHECK (purge_after >= expires_at + INTERVAL '48 hours')
);

CREATE UNIQUE INDEX uq_leases_node_ip_active ON leases (node_id, allocated_ip) WHERE status IN ('active', 'tombstone');

-- 9. Profiles
CREATE TABLE profiles (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id            UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    revision             BIGINT NOT NULL DEFAULT 1,
    envelope_ciphertext  BYTEA NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_profiles_device_revision UNIQUE (device_id, revision)
);

CREATE INDEX idx_profiles_device_id ON profiles (device_id);

-- 10. Operations
CREATE TABLE operations (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id            UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    operation_type       VARCHAR(32) NOT NULL,
    client_operation_id  UUID NOT NULL,
    http_method          VARCHAR(10) NOT NULL,
    request_path         VARCHAR(255) NOT NULL,
    request_body_sha256  CHAR(64) NOT NULL,
    credential_id        UUID REFERENCES credentials(id) ON DELETE SET NULL,
    profile_id           UUID REFERENCES profiles(id) ON DELETE SET NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_code           VARCHAR(64),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_operations_device_client_op UNIQUE (device_id, client_operation_id),
    CONSTRAINT ck_operations_type CHECK (operation_type IN ('enroll_complete', 'refresh_token', 'reauth_complete', 'issue_profile', 'renew_credential', 'revoke_device')),
    CONSTRAINT ck_operations_status CHECK (status IN ('pending', 'ready', 'failed')),
    CONSTRAINT ck_operations_body_hash_hex CHECK (request_body_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_operations_status ON operations (status) WHERE status = 'pending';
CREATE INDEX idx_operations_credential ON operations (credential_id);

-- 11. Token Families
CREATE TABLE token_families (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id   UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    grant_id    UUID NOT NULL REFERENCES access_grants(id) ON DELETE CASCADE,
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at  TIMESTAMPTZ,

    CONSTRAINT ck_token_families_status CHECK (status IN ('active', 'revoked'))
);

CREATE INDEX idx_token_families_device ON token_families (device_id);

-- 12. Refresh Tokens
CREATE TABLE refresh_tokens (
    id                          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    family_id                   UUID NOT NULL REFERENCES token_families(id) ON DELETE CASCADE,
    token_hash                  CHAR(64) NOT NULL,
    issuance_operation_id       UUID REFERENCES operations(id) ON DELETE SET NULL,
    issuance_client_op_id       UUID NOT NULL,
    issuance_body_hash          CHAR(64) NOT NULL,
    consumed_by_operation_id    UUID REFERENCES operations(id) ON DELETE SET NULL,
    consumed_by_client_op_id    UUID,
    consumed_by_body_hash       CHAR(64),
    status                      VARCHAR(20) NOT NULL DEFAULT 'active',
    expires_at                  TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    consumed_at                 TIMESTAMPTZ,

    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_tokens_issuance_body_hex CHECK (issuance_body_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_tokens_consumed_body_hex CHECK (consumed_by_body_hash IS NULL OR consumed_by_body_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_tokens_status CHECK (status IN ('active', 'consumed', 'revoked'))
);

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);

-- 13. Access Tokens
CREATE TABLE access_tokens (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    family_id   UUID NOT NULL REFERENCES token_families(id) ON DELETE CASCADE,
    device_id   UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash  CHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_access_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_access_tokens_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_access_tokens_device_id ON access_tokens (device_id);

-- 14. Request Nonces
CREATE TABLE request_nonces (
    nonce       CHAR(64) NOT NULL,
    seen_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (nonce),
    CONSTRAINT ck_request_nonces_hex CHECK (nonce ~ '^[0-9a-f]{64}$')
);

-- 15. Encrypted Response Cache
CREATE TABLE encrypted_response_cache (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id          UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    operation_id       UUID NOT NULL REFERENCES operations(id) ON DELETE CASCADE,
    token_hash         CHAR(64) NOT NULL,
    encrypted_payload  BYTEA NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_enc_cache_device_op UNIQUE (device_id, operation_id),
    CONSTRAINT ck_enc_cache_token_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_enc_cache_expires ON encrypted_response_cache (expires_at);

-- 16. Outbox
CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    aggregate_type  VARCHAR(32) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    retry_count     INT NOT NULL DEFAULT 0,
    max_retries     INT NOT NULL DEFAULT 5,
    next_retry_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    delivered_at    TIMESTAMPTZ,

    CONSTRAINT ck_outbox_status CHECK (status IN ('pending', 'processing', 'delivered', 'failed', 'dead_letter')),
    CONSTRAINT ck_outbox_aggregate_type CHECK (aggregate_type IN ('credential', 'lease', 'device'))
);

CREATE INDEX idx_outbox_pending ON outbox (next_retry_at ASC) WHERE status = 'pending';

-- 17. Node Observations
CREATE TABLE node_observations (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    node_id       UUID NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    generation    BIGINT NOT NULL,
    raw_evidence  JSONB NOT NULL,
    observed_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_node_observations_node_gen ON node_observations (node_id, generation);
