# Этап 01 — контракты, модель данных и безопасность

Статус: **NOT RUN**. Вход: G00. Выход: **G01 contract freeze** перед параллельной реализацией backend/Android/node. Следующий этап: [02](02-backend-control-plane.md); [03](03-node-provisioning.md) и [04](04-android-integration.md) потребляют те же schemas/vectors. Ниже проектные defaults, требующие проверки и фиксации в G01; это не существующие API.

## Задачи и владельцы

| ID | Субагент / writer | Разрешённые будущие files | Выход | Зависимость |
|---|---|---|---|---|
| S01-MOBILE | `contracts` | `contracts/mobile-v1.openapi.yaml`, `contracts/profile-v2.schema.json`, `contracts/routing-rules-v1.schema.json` | Endpoints, error/state enums, headers, strict schemas/examples | G00 |
| S01-CRYPTO | `crypto-security` | `contracts/signing-vectors.json`, `contracts/envelope-vectors.json`, `contracts/security-contract.md`, `contracts/probes/crypto/` | Kotlin/Go byte vectors, purpose/rotation, independent refresh state probe, negative corpus | G00 |
| S01-DATA | `storage-design` | `backend/docs/data-model.md`, `contracts/node-xray-v2.openapi.yaml`, `contracts/probes/node/` | DB invariants, pinned proto/probe spike и accepted observed evidence, WDTT mapping | G00 |
| S01-REVIEW | Главный + read-only security review | `execution/S01-report.md`, общий freeze record | Cross-consumer agreement, unresolved decisions closed, scope/code ownership02 | Все три outputs |

Первая волна — три writers в разных paths. HTTP schema references и node/data именование согласовать сообщениями до финальных правок. Основной агент — единственный writer freeze report и общий docs index. Без G01 допустимы маленькие throwaway/isolated validation experiments, не production implementation несовместимых contracts.

## HTTP и state contracts

`/v1` только TLS с bounded JSON UTF-8: обычное request body64 KiB, абсолютный верх1 MiB; подписанный encrypted profile response максимум2 MiB, decrypted payload максимум1 MiB, protected metadata16 KiB, серверов≤200. Response cap отличается от request cap: base64 envelope увеличивает размер. MVP не сжимает encrypted profile. Это предложенные ограничения, freeze проверяет actual base64 overhead/arrays/native conversion. Невалидные unknown/duplicate critical fields, нецелые counters, NaN, бесконечные/deep inputs и invalid enum не допускаются. Errors `application/problem+json`: `code`, `title`, `status`, `retryable`, `retryAfterSeconds`, `requestId`. Даты RFC3339 UTC, IDs opaque UUID, wire deviceId — fingerprint SHA256(SPKI), не внутренний UUID БД. Retryable и permanent errors различаются в UI.

| Endpoint | Request / ответ / инвариант |
|---|---|
| `POST /devices/challenges` | `mode=enroll` или `mode=reauth`, candidate SPKI/capabilities; enroll invitation. Response challengeId, nonce32B, expiresAt120s, signed serverTime/keyId; reauth только известный device с действующим grant |
| `POST /devices/complete` | Подписанное raw body с challengeId/nonce/SPKI/capabilities/clientOperationId. Enroll consume invite+challenge/create device+grant atomic; reauth не расходует invitation снова. Response личные tokens/grant; disabled/revoked device не возрождается |
| `POST /tokens/refresh` | Signed body `refreshToken`, `clientOperationId`; access Authorization отсутствует. Atomic rotation bound device/family. Lost-response retry описан ниже |
| `GET /servers` | Access proof; capability/version/region/capacity, pagination через следующий signed POST/search contract либо path cursor, **query запрещён в signing v1**; первый MVP bounded catalog |
| `POST /profiles` | Access proof, selected node/capability/rulesVersion/clientOperationId →202 operation. No ready profile before actual apply |
| `GET /operations/{id}` | Ownership; pending/ready/failed, stable request ID/retry hint, no чужие credentials |
| `GET /profiles/{id}` | Ownership; encrypted signed envelope. Expires/superseded/revoked profile semantics явно заданы |
| `POST /credentials/{id}/renew` | Access proof, expected revision/clientOperationId; безопасная rotation/overlap, conflict409 |
| `DELETE /devices/me` | Access proof с пустым body, **без Idempotency-Key**; естественно idempotent revoke device/grant/credentials + outbox, repeated valid proof не создаёт новые revoke events |
| `GET /routing-rules/manifest` | Public signed bytes, ETag, bounded cache; без device history |
| `/livez`, `/readyz` | Internal; DB и backlog разные health/metrics сигналы, не публичная admin panel |

Все paths в таблице относительно `/v1`, кроме внутреннего health. Полный public API не состоит из списков endpoint names: в OpenAPI задаются request/response fields, lengths, statuses, nullable semantics и примеры. Auth challenge rate limit/enumeration guard проверяются без выдачи подробностей об известном device. Admin — отдельный CLI/management role, не публичные APK endpoints.

States: operation pending/ready/failed; credential desired/observed revision + expiry/revoked; lease requested/applying/active/revoking/expired/failed; client DISCONNECTED/PREPARING/CONNECTING/VERIFYING/CONNECTED/DEGRADED/RECONNECTING/PAUSED/BLOCKED_BY_NETWORK/CREDENTIAL_EXPIRED/ERROR. Главный фиксирует единую точную enum vocabulary, переходы и приоритет terminal states в schema; адаптация legacy runtime через один boundary mapper.

## Proof of possession и replay

Существующий `RequestSigner` остаётся базой. Canonical bytes UTF-8:

```text
v1\nMETHOD\nencodedPath\nepochSeconds\nnonce\ndeviceId\nSHA256(token)\nSHA256(body)
```

Здесь `\n` обозначает newline byte, не два символа. Method uppercase; path именно encoded path без query/fragment. Hash lowercase hex; nonce16 random bytes base64url без padding; подпись base64url. Raw body хешируется **до parse**, server не пересериализует JSON. Не допускать нормализацию path ingress до проверки, повторные auth headers и неоднозначный encoded separator. Golden vectors включают percent encoding и запрет query; clocks допускают±120s, uniqueness(deviceId,nonce) хранится5min и переживает relevant restart.

Headers: `Authorization: Bearer <access>`, `X-HKVPN-Device-Id`, `X-HKVPN-Timestamp`, `X-HKVPN-Nonce`, `X-HKVPN-Signature`, `X-HKVPN-Algorithm`. Для enroll/reauth/refresh accessToken в RequestSigner null: hash(empty); refresh secret входит в подписанный raw body. Algorithm сверяется с device key policy, не выбирается одним входным header.

RSA current3072, exponent65537; server accepts declared3072/4096 RSA SPKI, rejects malformed/private/oversized/weak keys. PS256: SHA256, MGF1 SHA256, salt32; RS256: PKCS#1v1.5 SHA256. Нельзя объявить PSS, а проверить PKCS1 fallback. Native/Keystore capability probe текущего Android — authoritative; API35 modern OAEP/PSS и legacy RSA проверяются реальными vectors. Attestation optional; Direct без GMS не теряет доступ по этой причине.

## Tokens, idempotency и потерянный refresh response

Pilot default access opaque random256bit TTL15min; refresh random256bit absolute TTL30d для family, без бесконечного sliding renewal. Expiry каждого token ограничен family/grant expiry. Одна активная token family/device; успешный reauth атомарно отзывает прежние семьи этого device, сохраняя действующий grant. В БД hashes, device binding, created/used/revoked/expiry. Никаких shared UUID/token в APK и plaintext refresh response в логах/БД.

Idempotency-Key равен **подписанному** `clientOperationId` в body, record TTL48h; owner+operation namespace+key unique. Precedence: same bound key+different body →409 **без** rotation/family revoke; consumed refresh с **новым** operationId →reuse/family revoke. Bodyless DELETE не принимает этот header и query parameters. Retry всегда с новым request nonce/signature, но прежним operationId. После успешного DELETE access отозван: повтор может вернуть401/410 без новых side effects. Идемпотентность здесь означает неизменность удалённого состояния, не обещание одинакового HTTP ответа; отдельного обхода token revocation нет.

Refresh атомарно помечает old token used и создаёт successor. Для повторного exact request после потерянного ответа:

1. В одной транзакции записать новый token hash и response cache, зашифрованный server storage key с purpose/keyId/operation binding. TTL восстановления120s; idempotency state живёт48h. Долговременный plaintext нового token не хранится.
2. В этот recover window тот же device proof, consumed token, operationId и body hash возвращают **тот же** encrypted-cache result; ни второй rotation, ни family revoke не выполняются. Перед возвратом повторно проверить device/grant/family/expiry: cache не обходит отзыв и не продлевает lifetime.
3. Consumed token с новым operationId — reuse, отзыв family; тот же operationId с изменённым body →409 согласно precedence. Binding consumed token к operationId/bodyHash сохраняется в refresh-token record до family/token retention, не только48h в idempotency table, иначе поздний exact retry не отличить от reuse. Параллельный легитимный refresh предотвращается client single-flight; concurrent tests обязательны.
4. Exact retry вне recover window — `REFRESH_RETRY_EXPIRED`, не ложный reuse. Клиент запускает `mode=reauth` challenge+complete с существующим Keystore key и действующим grant, отзывает потерянную token family и получает новую. Reauth не продлевает отозванный/истёкший grant и не создаёт новое устройство автоматически.

`devices/complete` enroll/reauth тоже возвращает secrets: после consumed challenge exact bound operation/body/device retry в120s проверяет proof и state, затем возвращает encrypted cached response; не потребляет invitation второй раз. Idempotency recovery проверяется до generic consumed-challenge rejection, но после signature/body/device verification. За пределами120s нужен новый challenge с proof зарегистрированного device, не новый расход invitation и не восстановление revoked grant. Reauth retry не отзывает только что выданную семью повторно. После удаления cache долговременный operation/body binding сохраняет classification позднего exact retry.

120s/30d/48h — предложенные defaults. G01 выполняет независимый crypto/state/race probe harness для потерянного ответа, **не требует готового HTTP API/PostgreSQL из02**. Реальные HTTP/transaction/concurrency integration относятся G02. Если выбран другой recovery, он сохраняет безопасность и не требует сбросить данные при обычном timeout. TTL cache/key rotation согласовать с backup, очистка ciphertext по TTL.

## Profile envelope v2 и совместимость

Текущий `HybridProfileDecryptor` обеспечивает часть encryption compatibility, **не полноценную проверку server signature/ownership/anti-rollback**. Новый внешний контракт проверяется до преобразования в legacy `TunnelProfile`: version/profileRevision, profileId/accessId/deviceId, issuedAt/credentialExpiresAt, rulesVersion, engine/capabilities, servers/bootstrap. Имена `subscriptionId`/`levik-relay` допустимы только внутри согласованного mapper/native v1 boundary, без billing semantics.

Предлагаемая схема: `protected` = base64url точных UTF-8 JSON metadata bytes; `wrappedKey`, `iv`, `ciphertext`, `signature` — base64url без padding. Metadata включает purpose, schemaVersion, keyId/signatureAlgorithm, encAlgorithm, device/access/profile IDs, revision и expiry. Использовать стандартные AES256-GCM (random12B IV, tag16B, ciphertext+tag) и RSA-OAEP варианта **реально объявленной** capability устройства.

AAD и signed bytes фиксируются одним golden contract:

```text
AAD = UTF8("HKVPN-PROFILE-V2\n" + protected)
signed = UTF8("HKVPN-PROFILE-V2\n" + protected + "\n" + wrappedKey
              + "\n" + iv + "\n" + ciphertext + "\n")
```

Подпись ECDSA P256/SHA256 strict DER, проверка допустимых r/s/format текущим поддержанным verifier. Не вводить самодельную криптографию; format — версия envelope, implementation использует platform/standard libraries. Encryption public key принадлежит зарегистрированному device; `deviceId` в protected/payload — именно wire fingerprint. Signature keyId/purpose pinned/rotatable; profile/API challenge, routing и APK/OTA key material различаются. Верифицировать подпись, strict base64url/размеры и равенство metadata/payload до activation; AES key очищать по возможности. Clock adjustment по подписанному serverTime bounded; TLS certificate validation не отключать.

Legacy OAEP-SHA1 только по явно согласованной capability старого Keystore, без downgrade modern device. Parser не выбирает algorithm из произвольной строки вне allowlist. Защищённое хранилище сохраняет last profile/rules revision; replay старого encrypted response не уменьшает accepted revision. Freeze должен подтвердить, что crypto metadata и часть payload не расходятся; same-profile tamper, cross-device, changed expiry и removed signature отклоняются.

## Routing и private node contracts

Routing package v1: manifest version/generatedAt/expiresAt/minimumClientVersion, per-file SHA256, keyId/purpose, ECDSA signature точных exported manifest bytes. Начальные caps: download2 MiB, decoded package8 MiB, ≤8 файлов, ≤65 536 CIDR и≤10 000 domains; decompression только с hard byte/ratio/time limits, archive paths никогда не извлекаются произвольно. DNS cache начально≤8192 entries/8 MiB, TTL0 не кешировать, negative TTL≤300s; измерить на05/07 и менять caps совместимым capability/version rollout. Strict domain labels/IDNA, CNAME/TTL policy, IPv4/IPv6 CIDR bounds; allowlist reachability не RU ownership. Native/core capability version — часть профиля; неподдерживаемый relay/IPv6 format fail-closed. Freeze01 задаёт schema/caps; реальное доказательство native split DNS/dual stack относится05.

WDTT v1 private contract остаётся согласованным с existing fork. Xray provisioner **новый**: versioned `/internal/v2/xray/credentials/{apply,revoke,status}` с credential/device/node IDs, inboundTag из agent allowlist, UUID, expected/desired revision, expiry, idempotency. S01-DATA выполняет маленький isolated pinned-proto/probe spike и фиксирует, какое evidence будет authoritative; не обещать несуществующий ListUsers RPC. G01 принимает интерфейс и evidence semantics; production adapter, real mutation/restart/fault tests выполняются03, не требуются заранее в01. Status различает durable desired state и реально применённый core state. Internal lease lifecycle не смешивается с WDTT observed `active/expired/revoked/absent/unknown`; absent неизвестного lease не доказывает прошлый revoke. mTLS termination identity, signed method/path/body, bounded TTL/nonce и durable replay — end-to-end контракт; plain loopback нельзя открыть наружу.

## DB model и транзакционные границы

Согласовать таблицы: devices/internalUUID+fingerprint+SPKI; invitations/access_grants; challenges; access_tokens/token_families/refresh_tokens; request_nonces; nodes/capabilities; credentials; leases; profiles; operations/idempotency_keys; encrypted_response_cache; outbox; observations/audit. Модель regular lease может не иметь assigned tunnel IP; /24 pool/tombstones относятся к WDTT, не всем Xray users.

Unique constraints: fingerprint, challenge consume, invitation uses, owner+idempotency key, device+nonce, node+reserved lease IP с tombstones, credential/node revision. Use parameterized SQL. Enrollment и refresh rotation — атомарные transactions; reserve/credential desired/outbox — одна transaction; remote network I/O **вне** DB transaction. Worker claim через bounded `FOR UPDATE SKIP LOCKED`; crash between node apply and DB ack — retry at-least-once, не exactly-once RPC.

Отзыв не выдавать за мгновенное прекращение уже открытого Xray TCP. Pilot TTL regular24h, renewal12h, regular overlap target120s; relay≤24h agent cap с clock-skew safety margin, VK allocation отдельная expiry. WDTT v1 one-password rotate не доказывает overlap: MVP stable-password renewal либо отдельно проверенная compatible rotation из03. Сроки persisted state контролируются server clock; restore не воскрешает revoked/expired grant. At-rest keys отдельно от БД, purpose/keyId/AAD и backup/rotation определены до появления секретов.01 выдаёт data model/constraints/transaction specification; SQL migrations создаёт и проверяет02, не надо ждать готовую production БД в G01.

## Тесты и gate G01

| Проверка | Реалистичный дефект, который должен обнаружить тест |
|---|---|
| Kotlin/Go signing bytes, PSS/RS256 | Один consumer хеширует пересериализованное тело или проверяет другой algorithm |
| Signature path/query/headers | Неподписанный query/двойной header меняет выбранный object/operation |
| Envelope tamper/cross-device/expiry | Внешний attacker увеличил lifetime или перенёс профиль другому устройству |
| Refresh lost response/concurrency | Обычный retry отзывает family или дважды выдаёт successor |
| Grant/reauth revocation | Proof старого device бесконечно восстанавливает уже отозванный доступ |
| DB/outbox state model probe01; реальный SQL02/03 | Contract допускает два владельца IP или ready до observed; настоящие concurrency/crash tests выполняются после реализации |
| Rule manifest rollback/purpose | OTA key/старый package принимается в другой trust domain |
| WDTT current vs target | In-memory nonce cache ошибочно признан защитой после restart |

G01: OpenAPI/schema/examples структурно проверены доступными штатными инструментами; positive+negative vectors проверены независимыми Kotlin/Go implementations/probe harness; consumers согласовали bytes/algorithms/mappers; data model содержит constraints/failure semantics; refresh recovery state probe и Xray observed evidence design закрыты. Полный HTTP+Postgres+node implementation ещё не требуется. Если инструмент/SDK/device отсутствует — конкретный пункт UNABLE TO RUN, не fake VERIFIED. Детали схемы без выполненных compatibility probes — draft, не freeze.

Rollback — правка собственных draft contracts; destructive migrations/production keys на этапе не создаются. Передача02 включает freeze hash/revision, task ownership, подтверждённые vectors, gaps и конфигурационные defaults. Любое последующее изменение обновляет этот документ, schemas и tests вместе.
