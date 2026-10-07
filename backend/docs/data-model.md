# PostgreSQL Data Model & Persistence Specification

**Project**: Hello Kitty VPN  
**Component**: Backend Control Plane Storage Layer  
**Document**: `backend/docs/data-model.md`  
**Owner**: S01-DATA (`storage-design`)  
**Status**: DRAFT / PROPOSED FOR GATE G01 FREEZE  
**Target Engine**: PostgreSQL 16+ / 17 (with native or function-based UUIDv7)

---

## 1. Архитектурный контекст и цели

Спецификация определяет модель данных PostgreSQL для независимого бесплатного Control Plane Hello Kitty VPN в соответствии с [01-contracts-and-security.md](../../docs/hellokitty/agent-plan/01-contracts-and-security.md) и [02-backend-control-plane.md](../../docs/hellokitty/agent-plan/02-backend-control-plane.md).

### Ключевые требования к хранилищу:
1. **Идентичность устройств без учетных записей**: Личность определяется исключительно публичным ключом Android Keystore (RSA 3072/4096 SPKI). Внешний идентификатор `deviceId` — это lowercase hex `SHA256(DER_SPKI)`. Внутренний идентификатор в БД — `UUIDv7`.
2. **Пилотные квоты (Admission Control)**: Ровно 1 активное устройство на приглашение (`invitation`), не более 2 активных аренды узлов (`credentials`/`leases`) на устройство одновременно (включая резерв/ротацию).
3. **Безопасность токенов и защита от повторов (Replay Guard)**:
   - Долговременные секреты (access/refresh bearer) **никогда не хранятся в открытом виде** (только SHA256 хеши).
   - Ротация refresh-токенов с детекцией компрометации семейства (`token_families`).
   - Идемпотентное восстановление потерянного ответа в течение 120 секунд (`encrypted_response_cache`) без ложного отзыва семейства токенов.
   - Дедупликация подписанных запросов клиента через таблицу `request_nonces` (TTL 5 минут).
4. **Разделение Desired State и Observed State**:
   - Запрос на выдачу профиля (`POST /v1/profiles`) сохраняет желаемое состояние (`status = 'desired'`), ставит задачу в `outbox` и возвращает `202 Accepted` со статусом операции `pending`.
   - Профиль переходит в `ready` **только после подтверждения data plane** (наблюдаемое доказательство от ноды Xray/WDTT с совпадающей ревизией и поколением узла).
   - Транзакции базы данных **никогда не удерживаются** во время сетевых RPC-вызовов к нодам.
5. **Предотвращение коллизий IP в WDTT**:
   - Пул адресов WDTT ограничен 249 адресами (`10.66.66.2` – `10.66.66.250`).
   - При истечении или отзыве аренды IP-адрес переходит в состояние `tombstone` и удерживается 48 часов (`purge_after = expires_at + 48h`). Уникальный индекс предотвращает повторное выделение IP до истечения окна удержания.

---

## 2. Стратегия первичных ключей: UUIDv7

Для всех таблиц с суррогатным идентификатором используется **UUIDv7** (RFC 9562).

### Преимущества UUIDv7 перед UUIDv4 и BIGSERIAL:
- **Временная упорядоченность**: первые 48 бит содержат Unix timestamp в миллисекундах (`big-endian`). Новые записи вставляются в конец B-Tree индекса, устраняя фрагментацию страниц памяти, page splits и деградацию кэша буферов PostgreSQL при высоких нагрузках на запись.
- **Глобальная уникальность и криптографическая стойкость**: оставшиеся 74 бита содержат субмиллисекундный счетчик и случайные биты CSPRNG.
- **Безопасность**: идентификатор непроницаем для атак перебором (в отличие от последовательных sequence/BIGINT ID).

### Генерация UUIDv7 в PostgreSQL:
При отсутствии расширения `pg_uuidv7` используется детерминированная функция PostgreSQL:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

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
```

---

## 3. Спецификация таблиц схемы

```
+---------------------------------------------------------------------------------------------------+
|                                      HELLO KITTY VPN SCHEMA                                       |
+---------------------------------------------------------------------------------------------------+
|  [invitations] <----+                                                                             |
|                     |                                                                             |
|                     +-------+ [challenges]                                                        |
|                     |       |                                                                     |
|  [devices] <----+   |       |                                                                     |
|    |            |   |       |                                                                     |
|    +---------[access_grants]                                                                      |
|    |            |                                                                                 |
|    +---------[token_families] <------+                                                            |
|    |            |                    |                                                            |
|    |            +--------------[refresh_tokens]                                                   |
|    |            |                                                                                 |
|    +---------[access_tokens]                                                                      |
|    |                                                                                              |
|    +---------[operations] <------+                                                                |
|    |            |                |                                                                |
|    |            +------------[encrypted_response_cache]                                          |
|    |                                                                                              |
|    +---------[profiles]                                                                           |
|    |                                                                                              |
|    +---------[credentials] <-------+ [nodes] <-----+ [node_capabilities]                          |
|    |                               |    |          |                                              |
|    +---------[leases] <------------+    +----------+-- [node_observations]                        |
|                                                                                                   |
|  [request_nonces] (standalone replay guard, device_fingerprint + nonce)                           |
|  [outbox]         (transactional outbox for node provisioning RPC)                                |
+---------------------------------------------------------------------------------------------------+
```

---

### 3.1. Устройства (`devices`)

Хранит зарегистрированные криптографические личности клиентских устройств Android.
Публичный ключ никогда не меняется: переустановка приложения означает генерацию нового ключа в Keystore и регистрацию нового устройства.

```sql
CREATE TABLE devices (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    fingerprint             CHAR(64) NOT NULL,
    spki_der                BYTEA NOT NULL,
    signing_algorithm       VARCHAR(32) NOT NULL,
    encryption_algorithm    VARCHAR(32) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_devices_fingerprint UNIQUE (fingerprint),
    CONSTRAINT ck_devices_fingerprint_hex CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_devices_signing_algo CHECK (signing_algorithm IN ('PS256', 'RS256')),
    CONSTRAINT ck_devices_encryption_algo CHECK (encryption_algorithm IN ('RSA-OAEP-SHA256', 'RSA-OAEP-SHA1')),
    CONSTRAINT ck_devices_status CHECK (status IN ('active', 'disabled', 'revoked'))
);

CREATE INDEX idx_devices_status ON devices (status);
```

- **`fingerprint`**: SHA-256 хеш байт DER SPKI в lowercase hex. Служит публичным идентификатором `deviceId` в заголовках HTTP и канонической строке подписи `RequestSigner`.
- **`signing_algorithm`**: объявленный алгоритм подписи ключа Keystore (`PS256` по умолчанию для Android 9+, `RS256` для совместимости).
- **`encryption_algorithm`**: объявленный алгоритм расшифровки envelope профиля (`RSA-OAEP-SHA256` по умолчанию).
- **`status`**: `active` (исправно), `disabled` (временно заморожено администратором), `revoked` (отозвано навсегда через `DELETE /v1/devices/me`).

---

### 3.2. Пригласительные коды (`invitations`)

Ограничивает регистрацию в пилотной фазе. Создается локальной CLI-утилитой администратора.
Сам открытый код в базе не сохраняется — хранится только SHA-256 хеш нормализованного кода.

```sql
CREATE TABLE invitations (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    code_hash   CHAR(64) NOT NULL,
    max_uses    INTEGER NOT NULL DEFAULT 1,
    uses_count  INTEGER NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_invitations_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_invitations_code_hash_hex CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_invitations_uses CHECK (uses_count >= 0 AND uses_count <= max_uses),
    CONSTRAINT ck_invitations_status CHECK (status IN ('active', 'exhausted', 'revoked', 'expired'))
);

CREATE INDEX idx_invitations_status ON invitations (status) WHERE status = 'active';
```

- **Квота пилота**: по умолчанию `max_uses = 1` (одно устройство на один инвайт).

---

### 3.3. Права доступа устройств (`access_grants`)

Связывает конкретное устройство с основанием для доступа (инвайтом).
Одно устройство может иметь только один действующий грант.

```sql
CREATE TABLE access_grants (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id      UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    invitation_id  UUID REFERENCES invitations(id) ON DELETE RESTRICT,
    status         VARCHAR(20) NOT NULL DEFAULT 'active',
    expires_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at     TIMESTAMPTZ,

    CONSTRAINT ck_access_grants_status CHECK (status IN ('active', 'revoked', 'expired'))
);

CREATE UNIQUE INDEX uq_access_grants_device_active 
    ON access_grants (device_id) 
    WHERE status = 'active';

CREATE INDEX idx_access_grants_device_id ON access_grants (device_id);
```

- **Инвариант**: частичный уникальный индекс `uq_access_grants_device_active` гарантирует, что у устройства не может быть двух активных грантов одновременно.

---

### 3.4. Авторизационные челленджи (`challenges`)

Одноразовые криптографические вызовы для защиты от атак воспроизведения при enrollment и reauth.

```sql
CREATE TABLE challenges (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    nonce               CHAR(64) NOT NULL,
    device_fingerprint  CHAR(64) NOT NULL,
    mode                VARCHAR(20) NOT NULL,
    invitation_id       UUID REFERENCES invitations(id) ON DELETE RESTRICT,
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_challenges_nonce UNIQUE (nonce),
    CONSTRAINT ck_challenges_nonce_hex CHECK (nonce ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_challenges_mode CHECK (mode IN ('enroll', 'reauth'))
);

CREATE INDEX idx_challenges_device_fingerprint ON challenges (device_fingerprint);
CREATE INDEX idx_challenges_expires ON challenges (expires_at) WHERE consumed_at IS NULL;
```

- **`nonce`**: 32 байта криптографической случайности CSPRNG в lowercase hex.
- **`expires_at`**: жестко ограничено 120 секундами от момента генерации (`created_at + INTERVAL '120 seconds'`).
- **`consumed_at`**: фиксирует момент атомарного потребления. Повторное потребление отклоняется.

---

### 3.5. Семейства токенов (`token_families`)

Обеспечивает обнаружение кражи и повторного использования refresh-токенов (Token Rotation & Reuse Detection по RFC 6819 / OAuth 2.0 Security BCP).

```sql
CREATE TABLE token_families (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id   UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    grant_id    UUID NOT NULL REFERENCES access_grants(id) ON DELETE CASCADE,
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at  TIMESTAMPTZ,

    CONSTRAINT ck_token_families_status CHECK (status IN ('active', 'revoked'))
);

-- Инвариант: ровно 1 активное семейство токенов на устройство
CREATE UNIQUE INDEX uq_token_families_device_active 
    ON token_families (device_id) 
    WHERE status = 'active';

CREATE INDEX idx_token_families_device_id ON token_families (device_id);
```

- **Инвариант**: успешный reauth устройства атомарно переводит старое семейство токенов в `status = 'revoked'` и открывает ровно одно новое.

---

### 3.6. Refresh-токены (`refresh_tokens`)

Хранит хеши долгоживущих refresh-токенов. Открытый токен никогда не сохраняется в БД.

```sql
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
CREATE INDEX idx_refresh_tokens_issuance_op ON refresh_tokens (issuance_operation_id);
CREATE INDEX idx_refresh_tokens_consumed_op ON refresh_tokens (consumed_by_operation_id);
```

- **`token_hash`**: SHA-256 хеш 256-битного случайного токена.
- **`issuance_operation_id` / `issuance_client_op_id` / `issuance_body_hash`**: привязка токена к операции его **выпуска** (`enroll_complete`, `refresh_token`, `reauth_complete`).
- **`consumed_by_operation_id` / `consumed_by_client_op_id` / `consumed_by_body_hash`**: атомарная привязка использованного токена к операции его **потребления** (`refresh_token`). Разделение исключает ложную детекцию token reuse при повторе запроса ротации (Lost Response Retry).
- **`expires_at`**: абсолютный срок жизни семьи — максимум 30 дней от создания семьи (`token_families.created_at + INTERVAL '30 days'`). Срок не продлевается скользящим окном!

---

### 3.7. Access-токены (`access_tokens`)

Хранит хеши короткоживущих bearer access-токенов (TTL 15 минут).

```sql
CREATE TABLE access_tokens (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    family_id   UUID NOT NULL REFERENCES token_families(id) ON DELETE CASCADE,
    device_id   UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash  CHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_access_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_access_tokens_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_access_tokens_lookup ON access_tokens (token_hash) WHERE revoked_at IS NULL;
CREATE INDEX idx_access_tokens_device_id ON access_tokens (device_id);
CREATE INDEX idx_access_tokens_expires ON access_tokens (expires_at);
```

- **Проверка в Middleware**: токен валиден, если `token_hash` найден, `revoked_at IS NULL` и `expires_at > clock_timestamp()`, а связанное устройство и грант находятся в статусе `active`.

---

### 3.8. Защита от повторов запросов (`request_nonces`)

Обеспечивает строгое отслеживание уникальности пары `(device_fingerprint, nonce)` для подписанных запросов клиентов в течение скользящего окна 5 минут.

```sql
CREATE TABLE request_nonces (
    device_fingerprint  CHAR(64) NOT NULL,
    nonce               VARCHAR(64) NOT NULL,
    seen_at             TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (device_fingerprint, nonce),
    CONSTRAINT ck_req_nonces_fp_hex CHECK (device_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_request_nonces_seen_at ON request_nonces (seen_at);
```

- **Retention & Cleanup**: записи старше 5 минут периодически удаляются батчами фоновым воркером (`seen_at < clock_timestamp() - INTERVAL '5 minutes'`). Хранилище в БД обеспечивает сохранность защиты от replay при перезапусках API-сервера (в отличие от локального map в памяти).

---

### 3.9. Узлы VPN (`nodes`)

Реестр доступных серверов (Xray exit-ноды и WDTT relay-ноды).

```sql
CREATE TABLE nodes (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    node_tag       VARCHAR(64) NOT NULL,
    kind           VARCHAR(20) NOT NULL,
    region         VARCHAR(32) NOT NULL,
    host           VARCHAR(255) NOT NULL,
    port           INTEGER NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'active',
    capacity_max   INTEGER NOT NULL DEFAULT 249,
    capacity_used  INTEGER NOT NULL DEFAULT 0,
    generation     BIGINT NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_nodes_tag UNIQUE (node_tag),
    CONSTRAINT ck_nodes_kind CHECK (kind IN ('xray', 'relay')),
    CONSTRAINT ck_nodes_port CHECK (port > 0 AND port < 65536),
    CONSTRAINT ck_nodes_status CHECK (status IN ('active', 'draining', 'offline')),
    CONSTRAINT ck_nodes_capacity CHECK (capacity_used >= 0 AND capacity_used <= capacity_max),
    CONSTRAINT ck_nodes_generation CHECK (generation >= 1)
);

CREATE INDEX idx_nodes_kind_status ON nodes (kind, status) WHERE status = 'active';
```

- **`generation`**: монотонно возрастающий счетчик жизненного цикла узла. При перезапуске сервиса ноды или ротации инстанса generation инкрементируется, предотвращая принятие устаревших ack от прошлых эпох.

---

### 3.10. Возможности узлов (`node_capabilities`)

Список поддерживаемых протоколов и транспортов для согласования с клиентом.

```sql
CREATE TABLE node_capabilities (
    node_id     UUID NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    capability  VARCHAR(64) NOT NULL,

    PRIMARY KEY (node_id, capability)
);
```

- Примеры: `'vless-reality'`, `'wdtt-dtls'`, `'ipv4'`, `'ipv6'`, `'turn-dtls'`.

---

### 3.11. Учетные данные туннелей (`credentials`)

Хранит учетные данные для доступа к конкретному узлу. Для Xray это персональный client UUID; для WDTT это ссылка на аренду пароля.

```sql
CREATE TABLE credentials (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id          UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    node_id            UUID NOT NULL REFERENCES nodes(id) ON DELETE RESTRICT,
    key_id             VARCHAR(64) NOT NULL DEFAULT 'hkvpn-credential-storage-v1',
    aead_nonce         BYTEA NOT NULL,
    aead_ciphertext    BYTEA NOT NULL,
    fingerprint_sha256 CHAR(64) NOT NULL,
    credential_type    VARCHAR(32) NOT NULL,
    desired_revision   BIGINT NOT NULL DEFAULT 1,
    observed_revision  BIGINT NOT NULL DEFAULT 0,
    status             VARCHAR(20) NOT NULL DEFAULT 'desired',
    expires_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at         TIMESTAMPTZ,

    CONSTRAINT uq_credentials_node_fingerprint UNIQUE (node_id, fingerprint_sha256),
    CONSTRAINT ck_credentials_type CHECK (credential_type IN ('vless', 'relay_password')),
    CONSTRAINT ck_credentials_status CHECK (status IN ('desired', 'applying', 'active', 'revoking', 'revoked', 'expired')),
    CONSTRAINT ck_credentials_revisions CHECK (desired_revision >= observed_revision AND observed_revision >= 0),
    CONSTRAINT ck_credentials_nonce_len CHECK (octet_length(aead_nonce) = 12),
    CONSTRAINT ck_credentials_fingerprint_hex CHECK (fingerprint_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_credentials_device_status ON credentials (device_id, status);
CREATE INDEX idx_credentials_node_status ON credentials (node_id, status);
CREATE INDEX idx_credentials_expires_at ON credentials (expires_at) WHERE status = 'active';
```

- **Защита at rest (`aead_ciphertext`, `aead_nonce`, `key_id`)**: персональный VLESS UUID шифруется алгоритмом AES-256-GCM ключом сервиса с `purpose = 'credential_storage'`. Открытый секрет никогда не хранится в БД, журналах или дампах.
- **Связывание контекста (AAD)**: Authenticated Additional Data жестко связывает шифротекст со средой:
  `HKVPN-CREDENTIAL-V1\n<credential_id>\n<device_id>\n<node_id>\n<desired_revision>\n<generation>`.
- **Слепой индекс (`fingerprint_sha256`)**: SHA-256 от сырого секрета используется для проверки уникальности на узле (`uq_credentials_node_fingerprint`) и сопоставления при read-back без раскрытия секрета.
- **`desired_revision` vs `observed_revision`**: ревизия строго монотонна. `status` переходит в `active` только тогда, когда `observed_revision == desired_revision`.
- **Лимит аренды (TTL)**: 24 часа (`created_at + INTERVAL '24 hours'`). Плановая ротация инициируется через 12 часов.
- **Admission Cap**: не более 2 не-revoked записей на устройство одновременно (`status IN ('desired', 'applying', 'active')`).

---

### 3.12. Сетевые аренды и адресация (`leases`)

Хранит привязку выделенных внутритуннельных IP-адресов узлов (прежде всего для WDTT пула `10.66.66.0/24`).

```sql
CREATE TABLE leases (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    node_id       UUID NOT NULL REFERENCES nodes(id) ON DELETE RESTRICT,
    device_id     UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    ip_address    INET NOT NULL,
    lease_status  VARCHAR(20) NOT NULL DEFAULT 'requested',
    expires_at    TIMESTAMPTZ NOT NULL,
    purge_after   TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT ck_leases_status CHECK (lease_status IN ('requested', 'active', 'expired', 'revoked', 'tombstone', 'released')),
    CONSTRAINT ck_leases_purge_window CHECK (purge_after >= expires_at + INTERVAL '48 hours')
);

-- КРИТИЧЕСКИЙ ЧАСТИЧНЫЙ УНИКАЛЬНЫЙ ИНДЕКС: ВСЕ СТАТУСЫ, ВКЛЮЧАЯ TOMBSTONE, УДЕРЖИВАЮТ IP СЛОТ
CREATE UNIQUE INDEX uq_leases_node_ip_reserved 
    ON leases (node_id, ip_address) 
    WHERE lease_status != 'released';

CREATE INDEX idx_leases_node_status ON leases (node_id, lease_status);
CREATE INDEX idx_leases_device_id ON leases (device_id);
CREATE INDEX idx_leases_purge_after ON leases (purge_after) WHERE lease_status = 'tombstone';
```

- **Retention Grace Инвариант**: при истечении (`expires_at`) статус переводится в `expired` или `revoked`, а затем в `tombstone`. Статусы `requested`, `active`, `expired`, `revoked` и `tombstone` **все удерживают слот IP** благодаря частичному индексу `WHERE lease_status != 'released'`. Адрес не может быть выдан другому устройству до наступления `purge_after` (`expires_at + 48 часов`) и перехода в `released` (либо физического удаления фоновым чистильщиком). Эффективная емкость пула ограничивается инвариантом `active + retained_tombstones <= 249`.

---

### 3.13. Зашифрованные профили (`profiles`)

Хранит зашифрованные конверты (envelopes) профилей подключения, готовые к выдаче клиенту по `GET /v1/profiles/{id}`.

```sql
CREATE TABLE profiles (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    device_id            UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    revision             BIGINT NOT NULL DEFAULT 1,
    envelope_ciphertext  BYTEA NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_profiles_device_revision UNIQUE (device_id, revision)
);

CREATE INDEX idx_profiles_device_id ON profiles (device_id);
```

- **`envelope_ciphertext`**: бинарный блоб JSON структуры Profile Envelope v2 (`protected`, `wrappedKey`, `iv`, `ciphertext`, `signature`), зашифрованный на публичном ключе устройства и подписанный приватным ключом сервера.
- **Инвариант**: запись в `profiles` создается **только после подтверждения data plane** (когда `observed_revision` учетных данных узла сравнялась с `desired_revision`).

---

### 3.14. Операции и идемпотентность (`operations`)

Журнал асинхронных операций запроса профилей и ротации. Связывает клиентский `clientOperationId` (он же заголовок `Idempotency-Key`) с состоянием исполнения.

```sql
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
```

- **Инвариант идемпотентности**: пара `(device_id, client_operation_id)` строго уникальна (глобальный namespace per-device).
- **Авторитетная привязка**: операция связывается с `(device_id, client_operation_id, operation_type, http_method, request_path, request_body_sha256)`. Заголовки `X-HKVPN-Timestamp` и `X-HKVPN-Nonce` намеренно исключены из привязки (повторные попытки клиента используют свежий proof).
- **Классификация повторов**:
  * Exact retry (совпадение всех полей): возвращает результат существующей операции без повторного выполнения side effects.
  * Тот же `client_operation_id` с измененным телом, другим типом операции или другим путем: возвращает `409 Conflict`.
  * Попытка использования ключа другим устройством: отказ авторизации (401/403).

---

### 3.15. Кэш зашифрованных ответов (`encrypted_response_cache`)

Хранит зашифрованный серверным сервисным ключом результат для восстановления после сетевых сбоев ("потерянный ответ" / lost response retry).

```sql
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
```

- **TTL**: ровно 120 секунд. Защищает секреты (токены сессии) при обрыве соединения клиента сразу после отправки запроса. По истечении 120 секунд кэш удаляется, а клиент обязан выполнить reauth с ключом Keystore.

---

### 3.16. Транзакционный Outbox (`outbox`)

Гарантирует надежную асинхронную доставку команд конфигурирования узлов (Xray/WDTT Provisioning) по паттерну Transactional Outbox (At-least-once delivery).

```sql
CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    retry_count     INTEGER NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT ck_outbox_aggregate_type CHECK (aggregate_type IN ('credential', 'lease', 'device')),
    CONSTRAINT ck_outbox_event_type CHECK (event_type IN ('apply_credential', 'revoke_credential', 'apply_lease', 'revoke_lease')),
    CONSTRAINT ck_outbox_status CHECK (status IN ('pending', 'processing', 'delivered', 'failed'))
);

CREATE INDEX idx_outbox_worker_queue 
    ON outbox (next_retry_at) 
    WHERE status IN ('pending', 'processing');
```

- **Содержимое `payload`**: содержит нечувствительные параметры команды к ноде (`credentialId`, `nodeTag`, `inboundTag`, `revision`, `expiresAt`, `idempotencyKey`, `fingerprintSha256`). **Сырой VLESS UUID и секреты авторизации строго исключены из outbox payload!** Доверенный воркер подтягивает запись `credentials` и расшифровывает секрет в эфемерную память непосредственно перед выполнением RPC к ноде.

---

### 3.17. Наблюдения узлов (`node_observations`)

Фиксирует подтвержденные факты состояния узлов (observed state), полученные из ответов gRPC/HTTP API ноды.

```sql
CREATE TABLE node_observations (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    node_id       UUID NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    observed_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    generation    BIGINT NOT NULL,
    raw_evidence  JSONB NOT NULL,

    CONSTRAINT ck_node_obs_generation CHECK (generation >= 1)
);

CREATE INDEX idx_node_obs_node_time ON node_observations (node_id, observed_at DESC);
```

- **`raw_evidence`**: структурный JSON-лог факта (`endpoint`, `status_code`, `observed_revision`, `round_trip_ms`, `error_stage`).

---

## 4. Транзакционные границы и инварианты

Все бизнес-операции, модифицирующие состояние прав и учетных записей, исполняются в строгих транзакционных границах ACID.

---

### 4.1. Транзакция первичной регистрации (Enrollment Flow)

Выполняется при `POST /v1/devices/complete` с `mode = 'enroll'`.

```sql
BEGIN TRANSACTION ISOLATION LEVEL READ COMMITTED;

-- 1. Блокировка и валидация челленджа
SELECT id, device_fingerprint, invitation_id, expires_at, consumed_at 
FROM challenges 
WHERE nonce = :nonce AND mode = 'enroll'
FOR UPDATE;

-- Проверка: consumed_at IS NULL AND expires_at >= clock_timestamp()
-- Иначе: ROLLBACK -> 400 Bad Request / Challenge Expired

-- 2. Блокировка и валидация инвайта
SELECT id, max_uses, uses_count, expires_at, status 
FROM invitations 
WHERE id = :invitation_id
FOR UPDATE;

-- Проверка: status = 'active' AND expires_at >= clock_timestamp() AND uses_count < max_uses
-- Иначе: ROLLBACK -> 403 Forbidden / Invitation Exhausted

-- 3. Инкремент счетчика инвайта
UPDATE invitations 
SET uses_count = uses_count + 1,
    status = CASE WHEN uses_count + 1 >= max_uses THEN 'exhausted' ELSE 'active' END
WHERE id = :invitation_id;

-- 4. Создание устройства (с проверкой на уникальность fingerprint)
INSERT INTO devices (id, fingerprint, spki_der, signing_algorithm, encryption_algorithm, status)
VALUES (:device_id, :fingerprint, :spki_der, :signing_algo, :encryption_algo, 'active');

-- 5. Создание гранта доступа
INSERT INTO access_grants (id, device_id, invitation_id, status, expires_at)
VALUES (:grant_id, :device_id, :invitation_id, 'active', clock_timestamp() + INTERVAL '30 days');

-- 6. Создание активного семейства токенов
INSERT INTO token_families (id, device_id, grant_id, status)
VALUES (:family_id, :device_id, :grant_id, 'active');

-- 6.1. Регистрация операции enrollment
INSERT INTO operations (id, device_id, operation_type, client_operation_id, status)
VALUES (:operation_id, :device_id, 'enroll_complete', :client_operation_id, 'ready');

-- 7. Создание начального refresh-токена (привязан к операции выпуска)
INSERT INTO refresh_tokens (id, family_id, token_hash, issuance_operation_id, issuance_client_op_id, issuance_body_hash, status, expires_at)
VALUES (:refresh_token_id, :family_id, :refresh_token_hash, :operation_id, :client_operation_id, :body_hash, 'active', clock_timestamp() + INTERVAL '30 days');

-- 8. Создание начального access-токена (15 минут)
INSERT INTO access_tokens (id, family_id, device_id, token_hash, expires_at)
VALUES (:access_token_id, :family_id, :device_id, :access_token_hash, clock_timestamp() + INTERVAL '15 minutes');

-- 9. Пометка челленджа как потребленного
UPDATE challenges 
SET consumed_at = clock_timestamp() 
WHERE id = :challenge_id;

-- 10. Запись в кэш восстановления ответа (120 секунд)
INSERT INTO encrypted_response_cache (device_id, operation_id, token_hash, encrypted_payload, expires_at)
VALUES (:device_id, :operation_id, :refresh_token_hash, :encrypted_payload, clock_timestamp() + INTERVAL '120 seconds');

COMMIT;
```

**Инвариант**: гонка из 20 одновременных запросов с одним инвайтом выдаст ровно 1 грант благодаря строгой строковой блокировке `FOR UPDATE` инвайта и ограничению `uses_count < max_uses`.

---

### 4.2. Транзакция ротации токенов (Token Refresh Flow)

Выполняется при `POST /v1/tokens/refresh`. Обрабатывает три сценария: легитимную ротацию, потерю ответа (Lost Response) и попытку повторного использования (Token Reuse Attack).

```sql
BEGIN TRANSACTION ISOLATION LEVEL READ COMMITTED;

-- 1. Поиск токена по хешу с блокировкой
SELECT rt.id, rt.family_id, rt.issuance_operation_id, rt.issuance_client_op_id, rt.issuance_body_hash,
       rt.consumed_by_operation_id, rt.consumed_by_client_op_id, rt.consumed_by_body_hash,
       rt.status, rt.expires_at, rt.consumed_at,
       tf.device_id, tf.grant_id, tf.status AS family_status, tf.created_at AS family_created_at,
       ag.status AS grant_status, ag.expires_at AS grant_expires_at,
       d.status AS device_status
FROM refresh_tokens rt
JOIN token_families tf ON tf.id = rt.family_id
JOIN access_grants ag ON ag.id = tf.grant_id
JOIN devices d ON d.id = tf.device_id
WHERE rt.token_hash = :presented_token_hash
FOR UPDATE OF rt, tf;

-- 2. ОБЯЗАТЕЛЬНЫЕ ПРОВЕРКИ OWNERSHIP, EXPIRY И СТАТУСОВ ДО ВОЗВРАТА ИЗ КЭША:
-- Проверка владения устройством (caller device == token device)
IF tf.device_id != :calling_device_id THEN
    ROLLBACK;
    -- Чужой токен отклоняется без изменения чужого семейства!
    RETURN 401 "CROSS_DEVICE_VIOLATION";
END IF;

-- Проверка статуса устройства
IF d.device_status != 'active' THEN
    ROLLBACK;
    RETURN 401 "DEVICE_NOT_ACTIVE";
END IF;

-- Проверка статуса и срока гранта
IF ag.grant_status != 'active' OR ag.grant_expires_at < clock_timestamp() THEN
    ROLLBACK;
    RETURN 401 "GRANT_REVOKED";
END IF;

-- Проверка статуса и срока семейства
IF tf.family_status != 'active' OR tf.family_created_at + INTERVAL '30 days' < clock_timestamp() THEN
    ROLLBACK;
    RETURN 401 "FAMILY_REVOKED";
END IF;

-- ВЕТВЛЕНИЕ:

-- СЦЕНАРИЙ А: Токен уже CONSUMED (Повторный запрос)
IF rt.status = 'consumed' THEN
    -- Проверка: запрос повторяет именно ту операцию, которая потребила этот токен?
    IF rt.consumed_by_client_op_id = :incoming_client_operation_id THEN
        -- Проверка неизменности тела запроса
        IF rt.consumed_by_body_hash != :incoming_body_hash THEN
            ROLLBACK;
            -- Тот же ключ идемпотентности, но модифицированное тело -> 409 Conflict без отзыва семейства
            RETURN 409 "IDEMPOTENCY_CONFLICT";
        END IF;

        -- Проверка 120-секундного окна восстановления ответа
        IF clock_timestamp() <= rt.consumed_at + INTERVAL '120 seconds' THEN
            -- Выборка зашифрованного ответа из кэша
            SELECT encrypted_payload FROM encrypted_response_cache
            WHERE device_id = tf.device_id AND operation_id = rt.consumed_by_operation_id AND expires_at >= clock_timestamp();
            
            IF FOUND THEN
                COMMIT;
                -- Возврат кэшированного ответа без повторной ротации и без отзыва семейства
                RETURN CACHED_RESPONSE;
            ELSE
                ROLLBACK;
                RETURN 401 "CACHE_UNAVAILABLE";
            END IF;
        ELSE
            ROLLBACK;
            -- Окно 120s истекло: НЕ является атакой reuse, но окно восстановления закрыто
            RETURN 401 "REFRESH_RETRY_EXPIRED";
        END IF;
    ELSE
        -- ВНИМАНИЕ: Consumed токен представлен с НОВЫМ operationId!
        -- Это TOKEN REUSE ATTACK (похищенный использованный токен).
        -- Атомарно отзываем ВСЁ семейство токенов!
        UPDATE token_families 
        SET status = 'revoked', revoked_at = clock_timestamp() 
        WHERE id = tf.id;
        
        UPDATE access_tokens 
        SET revoked_at = clock_timestamp() 
        WHERE family_id = tf.id AND revoked_at IS NULL;
        
        COMMIT;
        RETURN 401 "TOKEN_REUSE_DETECTED";
    END IF;
END IF;

-- СЦЕНАРИЙ Б: Токен ACTIVE (Легитимная ротация)
IF rt.status != 'active' OR rt.expires_at < clock_timestamp() THEN
    ROLLBACK;
    RETURN 401 "TOKEN_EXPIRED";
END IF;

-- 1. Регистрация операции ротации
INSERT INTO operations (id, device_id, operation_type, client_operation_id, http_method, request_path, request_body_sha256, status)
VALUES (:new_op_id, tf.device_id, 'refresh_token', :incoming_client_operation_id, 'POST', '/v1/auth/refresh', :incoming_body_hash, 'ready');

-- 2. Атомарно помечаем старый токен consumed с привязкой к операции потребления
UPDATE refresh_tokens 
SET status = 'consumed', 
    consumed_at = clock_timestamp(),
    consumed_by_operation_id = :new_op_id,
    consumed_by_client_op_id = :incoming_client_operation_id,
    consumed_by_body_hash = :incoming_body_hash
WHERE id = rt.id;

-- 3. Создаем новый refresh-токен в том же семействе
INSERT INTO refresh_tokens (family_id, token_hash, issuance_operation_id, issuance_client_op_id, issuance_body_hash, status, expires_at)
VALUES (tf.id, :new_refresh_token_hash, :new_op_id, :incoming_client_operation_id, :incoming_body_hash, 'active', rt.expires_at);

-- 4. Создаем новый access-токен (15 минут)
INSERT INTO access_tokens (family_id, device_id, token_hash, expires_at)
VALUES (tf.id, tf.device_id, :new_access_token_hash, LEAST(clock_timestamp() + INTERVAL '15 minutes', ag.grant_expires_at));

-- 5. Запись в кэш восстановления ответа (120 секунд)
-- Выполняется строго ОДИН раз внутри транзакции ротации
INSERT INTO encrypted_response_cache (device_id, operation_id, token_hash, encrypted_payload, expires_at)
VALUES (tf.device_id, :new_op_id, :new_refresh_token_hash, :encrypted_payload, clock_timestamp() + INTERVAL '120 seconds');

COMMIT;
```

---

### 4.3. Выборка Outbox воркером (`SELECT ... FOR UPDATE SKIP LOCKED`)

Воркер провижининга периодически опрашивает очередь outbox для отправки команд на узел Xray/WDTT.

```sql
BEGIN TRANSACTION;

SELECT id, aggregate_type, aggregate_id, event_type, payload, retry_count
FROM outbox
WHERE status = 'pending' AND next_retry_at <= clock_timestamp()
ORDER BY next_retry_at ASC
LIMIT 50
FOR UPDATE SKIP LOCKED;

-- Для выбранных записей меняем статус на 'processing':
UPDATE outbox 
SET status = 'processing' 
WHERE id = ANY(:claimed_ids);

COMMIT;
```

**Критический архитектурный инвариант**:
После фиксации статуса `processing` сетевой вызов (gRPC к ноде Xray или HTTP к WDTT node-agent) выполняется **ВНЕ транзакции базы данных**! Таблицы БД не блокируются ожиданием ответа по сети.

---

### 4.4. Фиксация Observed State и переход в Ready (Finalization)

Когда воркер получает подтверждение от узла:

```sql
BEGIN TRANSACTION ISOLATION LEVEL READ COMMITTED;

-- 1. Проверяем, не было ли отозвано устройство или учетные данные за время вызова узла,
-- и проверяем совпадение поколения узла
SELECT c.id, c.desired_revision, c.status, d.status AS device_status, ag.status AS grant_status, n.generation AS current_node_generation
FROM credentials c
JOIN devices d ON d.id = c.device_id
JOIN access_grants ag ON ag.device_id = d.id AND ag.status = 'active'
JOIN nodes n ON n.id = c.node_id
WHERE c.id = :credential_id
FOR UPDATE OF c;

IF c.status = 'revoking' OR c.status = 'revoked' OR d.device_status != 'active' OR ag.grant_status != 'active' THEN
    -- Устройство было отозвано во время выполнения команды ноды!
    -- Не переводим в ready. Инициируем команду отзыва на ноду.
    UPDATE outbox SET status = 'delivered' WHERE id = :outbox_id;
    COMMIT;
    RETURN;
END IF;

-- Проверка поколения узла: если узел перегенерирован/перезапущен, старое доказательство недействительно
IF current_node_generation != :node_generation THEN
    UPDATE outbox SET status = 'failed', error_message = 'NODE_GENERATION_MISMATCH' WHERE id = :outbox_id;
    UPDATE operations SET status = 'failed', error_code = 'NODE_GENERATION_MISMATCH' WHERE id = :operation_id;
    COMMIT;
    RETURN;
END IF;

-- 2. CAS обновление ревизии и статуса учетных данных с RETURNING id
-- Атомарно проверяет, что desired_revision совпадает с подтвержденной ревизией
UPDATE credentials 
SET observed_revision = :response_observed_revision,
    status = 'active',
    updated_at = clock_timestamp()
WHERE id = :credential_id 
  AND desired_revision = :response_observed_revision
  AND node_id = :node_id
RETURNING id INTO updated_id;

-- КРИТИЧЕСКАЯ ПРОВЕРКА CAS: если updated_id IS NULL (rows_updated == 0),
-- значит произошел race condition (например, ревизия устарела или изменилась).
-- В этом случае ЗАПРЕЩЕНО создавать запись в profiles и переводить операцию в ready!
IF updated_id IS NULL THEN
    UPDATE outbox SET status = 'failed', error_message = 'CAS_REVISION_MISMATCH' WHERE id = :outbox_id;
    UPDATE operations SET status = 'failed', error_code = 'PROVISIONING_CAS_FAILED' WHERE id = :operation_id;
    COMMIT;
    RETURN;
END IF;

-- 3. Сохранение сырого доказательства наблюдения (строго привязано к generation и node_id)
INSERT INTO node_observations (node_id, generation, raw_evidence)
VALUES (:node_id, :node_generation, :evidence_jsonb);

-- 4. Генерация зашифрованного конверта профиля и пометка операции 'ready'
-- Выполняется СТРОГО ПОСЛЕ успешного CAS!
INSERT INTO profiles (device_id, revision, envelope_ciphertext)
VALUES (:device_id, :response_observed_revision, :encrypted_profile_envelope)
ON CONFLICT (device_id, revision) DO UPDATE 
SET envelope_ciphertext = EXCLUDED.envelope_ciphertext
RETURNING id INTO new_profile_id;

UPDATE operations 
SET status = 'ready', profile_id = new_profile_id, updated_at = clock_timestamp()
WHERE id = :operation_id;

-- 5. Пометка задачи outbox как успешно завершенной
UPDATE outbox 
SET status = 'delivered' 
WHERE id = :outbox_id;

COMMIT;
```

---

## 5. Политика удаления, удержания (Retention) и очистки данных

| Таблица | Стратегия удаления | Retention / Срок жизни | Механизм очистки |
|---|---|---|---|
| `devices` | **Soft Deletion** | Пожизненно (аудит) | `status = 'revoked'`, `spki_der` и `fingerprint` сохраняются для защиты от перерегистрации |
| `access_grants` | **Soft Deletion** | Пожизненно | `status = 'revoked'`, `revoked_at` |
| `token_families` | **Soft Deletion** | Пожизненно | `status = 'revoked'`, `revoked_at` |
| `refresh_tokens` | **Hard Deletion** | 30 дней после истечения семьи | Bounded batch purge: `DELETE FROM refresh_tokens WHERE id IN (SELECT id FROM refresh_tokens WHERE expires_at < now() - INTERVAL '30 days' LIMIT 5000)` |
| `access_tokens` | **Hard Deletion** | 24 часа после истечения токена | Bounded batch purge: `DELETE FROM access_tokens WHERE id IN (SELECT id FROM access_tokens WHERE expires_at < now() - INTERVAL '24 hours' LIMIT 5000)` |
| `request_nonces` | **Hard Deletion** | 5 минут | Bounded batch purge (каждую минуту): `DELETE FROM request_nonces WHERE id IN (SELECT id FROM request_nonces WHERE seen_at < now() - INTERVAL '5 minutes' LIMIT 5000)` |
| `challenges` | **Hard Deletion** | 48 часов после истечения | Bounded batch purge: `DELETE FROM challenges WHERE id IN (SELECT id FROM challenges WHERE expires_at < now() - INTERVAL '48 hours' LIMIT 1000)` |
| `encrypted_response_cache` | **Hard Deletion** | 120 секунд | Bounded batch purge (каждые 30 секунд): `DELETE FROM encrypted_response_cache WHERE id IN (SELECT id FROM encrypted_response_cache WHERE expires_at < now() LIMIT 1000)` |
| `leases` | **Tombstone -> Purge** | 24ч аренда + **48ч retention grace** | Tombstone удерживает IP до `purge_after = expires_at + 48h`. Удаление строго после наступления `purge_after` |
| `credentials` | **Soft Deletion** | До истечения + 7 дней | `status = 'revoked'` или `'expired'`. Удаление старых версий только после успешной ротации |
| `operations` | **Hard Deletion** | 48 часов | Хранение идемпотентного статуса 48 часов, затем удаление завершенных операций |
| `outbox` | **Hard Deletion** | 7 дней после доставки | Удаление записей со статусом `'delivered'` старше 7 дней |
| `node_observations` | **Hard Deletion** | 30 дней | Ротация операционных логов наблюдений |

---

## 6. Резюме архитектурных гарантий для G01 Freeze

1. **Гарантия защиты от коллизий IP**: подтверждена частичным уникальным индексом `uq_leases_node_ip_active` с обязательным 48-часовым окном удержания tombstone.
2. **Гарантия защиты от потери refresh-ответов**: подтверждена таблицей `encrypted_response_cache` с временем жизни 120с и связкой `bound_operation_id` + `bound_body_hash`.
3. **Гарантия отсутствия преждевременного Ready**: подтверждена двухфазным переходом через Outbox и CAS-верификацией `observed_revision == desired_revision` перед созданием записи в таблице `profiles`.
4. **Гарантия изоляции транзакций от сети**: внешние RPC к узлам data plane выполняются строго за пределами транзакций PostgreSQL.
