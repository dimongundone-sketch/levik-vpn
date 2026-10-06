# S01 / Contracts and Security Freeze — итоговый отчёт координатора

Дата UTC: 2026-10-06
Главный агент / координатор: Главный координатор (роль `S01-REVIEW`)
Source baseline HEAD: `0d05186a3436028f9392e22cbbc30781b0e59129`
Working baseline: [`docs/hellokitty/execution/baseline-source.sha256`](baseline-source.sha256) (372 файла подтверждены)
Scope / разрешённые paths:
- `contracts/**`
- `backend/docs/**`
- `docs/hellokitty/execution/**`
Prerequisites / gate evidence: G00 VERIFIED (замечания устранены, baseline зафиксирован).

---

## 1. Что сделано

Этап 01 «Контракты и безопасность» успешно выполнен в полном объёме тремя независимыми субагентами без пересечения владельцев файлов:

1. **S01-MOBILE (`contracts`)**:
   - `contracts/mobile-v1.openapi.yaml`: спецификация OpenAPI 3.1.0 публичного мобильного API `/v1` (12 путей, 16 схем, строгие лимиты размеров, enum vocabulary, RFC 7807 Error model, все 16 примеров валидированы).
   - `contracts/profile-v2.schema.json`: JSON Schema (Draft 2020-12) для шифрованного конверта `TunnelProfileEnvelopeV2` и расшифрованного тела `TunnelProfilePayloadV2`.
   - `contracts/routing-rules-v1.schema.json`: JSON Schema (Draft 2020-12) для подписанного манифеста правил маршрутизации `RoutingRulesManifest` с защитными капами.
   - Детальный отчёт: [`docs/hellokitty/execution/S01-mobile.md`](S01-mobile.md).

2. **S01-CRYPTO (`crypto-security`)**:
   - `contracts/security-contract.md`: модель угроз (STRIDE), канонический формат `RequestSigner v1` (8 строк, LF, без trailing newline, lowercase hex, query строго запрещён), спецификация конверта `HKVPN-PROFILE-V2` (AES-256-GCM + RSA-OAEP + ECDSA P-256 подпись), протокол Lost-Response Refresh Recovery (окно 120с, reuse detection с отзывом семьи, 409 conflict, REFRESH_RETRY_EXPIRED -> reauth), естественная идемпотентность `DELETE /v1/devices/me` (без Idempotency-Key), разделение 6 классов ключей.
   - `contracts/signing-vectors.json`: 10 позитивных векторов (PS256, RS256, с/без токена, JSON/empty body, percent-encoding, RSA-4096) и 11 негативных векторов.
   - `contracts/envelope-vectors.json`: 2 позитивных вектора (Modern OAEP-SHA256, Legacy OAEP-SHA1) и 10 негативных векторов.
   - Исполняемые probes: Python (`test_signing_probe.py`, `test_envelope_probe.py`, `test_refresh_state_probe.py`) и Go stdlib (`go/signing_probe_test.go`, `go/envelope_probe_test.go`, `go/refresh_state_test.go`).
   - Детальный отчёт: [`docs/hellokitty/execution/S01-crypto.md`](S01-crypto.md).

3. **S01-DATA (`storage-design`)**:
   - `backend/docs/data-model.md`: схема PostgreSQL 16+/17 на базе упорядоченных первичных ключей **UUIDv7** (RFC 9562); 17 таблиц; транзакционные границы enrollment, refresh, outbox worker (`FOR UPDATE SKIP LOCKED`), retention & tombstone grace policy.
   - `contracts/node-xray-v2.openapi.yaml`: спецификация OpenAPI 3.1.0 для частного API ноды Xray `/internal/v2/xray/*` с mTLS + HMAC аутентификацией и требованием Durable Replay Cache.
   - Isolated Spike по Xray Core Proto / Command APIs: зафиксирован критический факт **отсутствия RPC `ListUsers`** в Xray-core; исследовано поведение активных сессий при `RemoveUser`; формализовано Authoritative Observed Evidence; probe `xray_proto_spike.py` (8 тестов: генерации узла, tombstones пула WDTT на 249 IP, 48ч grace window).
   - Детальный отчёт: [`docs/hellokitty/execution/S01-data.md`](S01-data.md).

---

## 2. Задачи и владельцы

| Task ID | Owner | Write paths | Dependencies | Status | Evidence |
|---|---|---|---|---|---|
| S01-MOBILE | `contracts` | `contracts/mobile-v1.openapi.yaml`, `contracts/profile-v2.schema.json`, `contracts/routing-rules-v1.schema.json`, `docs/hellokitty/execution/S01-mobile.md` | G00 | VERIFIED | OpenAPI 3.1.0 (12 paths), 2 JSON Schema Draft 2020-12 валидированы, 16 примеров проверены |
| S01-CRYPTO | `crypto-security` | `contracts/signing-vectors.json`, `contracts/envelope-vectors.json`, `contracts/security-contract.md`, `contracts/probes/crypto/**`, `docs/hellokitty/execution/S01-crypto.md` | G00 | VERIFIED | Модель угроз, 21 вектор подписи, 12 векторов конверта, 4 набора probes в Python и Go (100% PASS) |
| S01-DATA | `storage-design` | `backend/docs/data-model.md`, `contracts/node-xray-v2.openapi.yaml`, `contracts/probes/node/**`, `docs/hellokitty/execution/S01-data.md` | G00 | VERIFIED | Схема PostgreSQL 17 таблиц на UUIDv7, контракт ноды Xray v2, spike Xray proto + WDTT (8 тестов PASS) |
| S01-REVIEW | Главный координатор | `docs/hellokitty/execution/status.md`, `docs/hellokitty/execution/S01-report.md`, Freeze Record | Все 3 задачи | VERIFIED | Интеграционная верификация контрактов, golden векторов и probes; закрытие ворот G01 |

---

## 3. Матрица проверок поведения (Behavioral Verification)

| Check | Command / environment | Status | Actual result / evidence | Limits |
|---|---|---|---|---|
| OpenAPI 3.1.0 & JSON Schemas Structural Validation | `python3 -c "import yaml, json, jsonschema ..."` | VERIFIED | `mobile-v1.openapi.yaml`: valid OpenAPI 3.1.0, 12 paths.<br>`node-xray-v2.openapi.yaml`: valid OpenAPI 3.1.0, 5 paths.<br>`profile-v2.schema.json`: valid Draft 2020-12.<br>`routing-rules-v1.schema.json`: valid Draft 2020-12.<br>Все 16 примеров OpenAPI прошли валидацию без ошибок. | Python 3.12.3 jsonschema |
| Kotlin/Go RequestSigner canonical bytes & PS256/RS256 vectors | `bash contracts/probes/crypto/run_all_probes.sh` (Python + Go test suites) | VERIFIED | Python: 10 positive, 11 negative PASS.<br>Go: `TestRequestSignerGoldenVectors` PASS (0.02s).<br>Канонизация 8 строк `v1\nMETHOD\nencodedPath...` полностью совпадает с реализацией `RequestSigner.kt`. | Go 1.22 stdlib, PyCryptodome / cryptography |
| Path/Body/Algorithm Tamper & Replay Rejection | Python `test_signing_probe.py` & Go `signing_probe_test.go` | VERIFIED | 11/11 негативных векторов отклонены: изменение метода, injection query, истечение timestamp, дубликат nonce, подмена тела/reserialization, неверный алгоритм, повреждённая подпись, чужой ключ. | Негативный корпус векторов |
| Envelope Tamper, Cross-Device, Expiry, Rollback Rejection | Python `test_envelope_probe.py` & Go `envelope_probe_test.go` | VERIFIED | 10/10 негативных векторов отклонены: повреждённый ciphertext (GCM auth tag fail), IV, AAD/metadata, wrappedKey, cross-device deviceId mismatch, expired credentials, rollback revision, bad signature, unauthorized signing key, missing signature. | Позитивные: Modern OAEP-SHA256 и Legacy OAEP-SHA1 |
| Lost Refresh Response, Retry & Concurrency Probe | Python `test_refresh_state_probe.py` & Go `refresh_state_test.go` | VERIFIED | 8 сценариев подтверждены:<br>1) легитимная ротация;<br>2) повтор в окне 120с возвращает зашифрованный кэш без повторной ротации;<br>3) 5 конкурентных burst-запросов возвращают идентичный ответ;<br>4) тот же opId с изменённым телом -> 409 Conflict;<br>5) token reuse -> немедленный отзыв всей семьи токенов;<br>6) повтор после 120с -> REFRESH_RETRY_EXPIRED;<br>7) Keystore reauth восстанавливает сессию действующего гранта;<br>8) DELETE /v1/devices/me естественно идемпотентен (400 с Idempotency-Key, 204 при первом вызове, 410 при повторах). | В памяти и Go state machine |
| Revoked Grant Reauth Prevention | Go `refresh_state_test.go` & Python `test_refresh_state_probe.py` | VERIFIED | Отозванный грант не восстанавливается через reauth: попытка reauth для revoked device отклоняется (`GRANT_REVOKED`). | Безопасность отзыва |
| Xray Proto Spike & WDTT Pool Model | `python3 contracts/probes/node/xray_proto_spike.py` | VERIFIED | 8/8 тестов прошли успешно:<br>- подтверждено отсутствие `ListUsers` RPC в Xray-core;<br>- подтверждена семантика `AlterInbound(AddUser/RemoveUser)`;<br>- зафиксировано, что `RemoveUser` не разрывает активные TCP/UDP сессии;<br>- подтверждено восстановление состояния через node generation и локальный журнал;<br>- подтверждён лимит WDTT 249 IP (`10.66.66.2`–`.250`);<br>- подтверждено, что 48-часовой retention grace удерживает tombstones и предотвращает коллизии IP. | Xray 26.3.27 command proto analysis |
| Key Separation Verification | Спецификация `contracts/security-contract.md` | VERIFIED | Разделение 6 категорий ключей: TLS Ingress, Profile Signer (ECDSA P-256), Routing Signer (ECDSA P-256), Device Keystore (RSA-3072/4096), Node mTLS/HMAC, Storage Key. Никаких пересечений назначения ключей. | Архитектурный аудит |

---

## 4. Запись фиксации контрактов (Freeze Record)

Следующие файлы нормативно зафиксированы со статусом **FROZEN** для этапа 01. Любые несовместимые изменения на последующих этапах требуют версионирования и согласования:

| Файл контракта | Описание назначения | SHA-256 контрольная сумма |
|---|---|---|
| `contracts/mobile-v1.openapi.yaml` | Спецификация публичного API /v1 (OpenAPI 3.1.0) | `f3e399befc4dea543eceb69336d94bfea36b908126dd1182b64911e70314e311` |
| `contracts/profile-v2.schema.json` | JSON Schema (Draft 2020-12) TunnelProfileEnvelopeV2 и Payload | `f8c00d6edd8bccc9eb827bfe87b932a1b20cb2ac373b6a528457235c61598440` |
| `contracts/routing-rules-v1.schema.json` | JSON Schema (Draft 2020-12) RoutingRulesManifest v1 | `6e87b0865467ea542284d45beb8860c65e387b575feaa4f410fdd8af1cdd3944` |
| `contracts/security-contract.md` | Модель угроз, канонизация, envelope, refresh recovery, разделение ключей | `7b97a981eea29c10e67b3bab6a959a1cdda8697e2c37324a6d34724db724edbf` |
| `contracts/signing-vectors.json` | Golden тестовые векторы RequestSigner v1 (10 pos, 11 neg) | `c9055079124a2ec97e978202af6047cfbe7584fe85702d1cf7bd17187f71b06e` |
| `contracts/envelope-vectors.json` | Golden тестовые векторы Profile Envelope v2 (2 pos, 10 neg) | `dead712628ae40ab9631c531e10f85a973cd223d1f659861706f42af83bbf34a` |
| `contracts/node-xray-v2.openapi.yaml` | Спецификация частного API ноды Xray v2 (OpenAPI 3.1.0) | `15cf56ebdbdfe03a8ec08d418aa847deb5c5b05eedb56f90c8d46b4ff2a481ea` |
| `backend/docs/data-model.md` | Спецификация модели БД PostgreSQL на UUIDv7 (17 таблиц, инварианты) | `a85e68c31426adb01717e16acfd5e6b64af3e5fc88d24803f05b5215ef5e0f24` |
| `contracts/probes/crypto/run_all_probes.sh` | Мастер-скрипт криптографических проб | `323a77192346e3ae4cc4a1264f45879a34223597df5b28ae9ad62d7ebcb1a1b4` |
| `contracts/probes/node/xray_proto_spike.py` | Исполняемый spike Xray proto & WDTT pool model | `8ff08d6099c146f16b5d4e58523426830a51e1f965aefd2d992fd3ed51bca82e` |

---

## 5. Gate

**G01: VERIFIED**
- Все критерии этапа 01 выполнены:
  1. OpenAPI 3.1.0 спецификации (`mobile-v1.openapi.yaml` и `node-xray-v2.openapi.yaml`) полностью валидированы.
  2. JSON-схемы профиля v2 и правил маршрутизации v1 проверены мета-валидатором Draft 2020-12.
  3. Golden векторы подписи и шифрования конверта покрывают все позитивные и негативные сценарии; совместимость доказана на Python и Go.
  4. Протокол Lost-Response Refresh Recovery математически и практически доказан на тестах гонок и повторов.
  5. Модель данных PostgreSQL на UUIDv7 детально специфицирована со всеми 17 таблицами, внешними ключами и ограничениями целостности.
  6. Spike по Xray Core Proto зафиксировал отсутствие `ListUsers`, ограничения разрыва сессий и выработал требования к observed evidence.
  7. Контракты зафиксированы в Freeze Record с точными SHA-256 суммами.

---

## 6. Совместимость и риски

1. **Wire Boundaries**:
   - `deviceId` на уровне API строго равен 64-символьному lowercase hex SHA-256 от DER SubjectPublicKeyInfo ключа Android Keystore.
   - Запрет query в signing v1 требует, чтобы каталог серверов возвращался единым батчем (до 200 серверов), а последующая пагинация проектировалась через POST-эндпоинты или cursor-пути.
2. **Сессии Xray Core**:
   - Вызов `RemoveUser` запрещает новые подключения пользователя, но не закрывает уже установленные сокеты. Закрытие существующих TCP/UDP сессий требует принудительного перезапуска inbound или сброса на уровне firewall/nftables (задача этапа 03).
3. **Лимиты WDTT Relay**:
   - Пул адресов ограничен 249 IP (`10.66.66.2`–`.250`). Период удержания 48 часов (`retentionGrace`) блокирует повторное использование адресов до истечения `purge_after`. Control plane обязан балансировать устройства между нодами.
4. **Безопасность Node Replay**:
   - Управление нодами через `node-agent` требует Durable Replay Cache на ноде, сохраняющего nonces на диске не менее 5 минут, чтобы избежать replay атак при рестарте службы.

---

## 7. Передача этапу 02 (Handoff to Stage 02: Go API, PostgreSQL и выдача устройств)

Все входные требования для этапа 02 удовлетворены. Контракты заморожены.

### Очередь задач этапа 02 (согласно `docs/hellokitty/agent-plan/02-backend-control-plane.md`):

| Task ID | Исполнитель / роль | Входные данные | Ownership файлов | Результат (Outputs) | Зависимость |
|---|---|---|---|---|---|
| **S02-01** | Главный координатор | Gate G01, Freeze Record | `backend/go.mod`, `backend/go.sum`, `backend/cmd/api/**`, `backend/internal/config/**`, `docs/hellokitty/execution/S02-report.md` | Инициализация Go модуля, конфигурация, DTO интерфейсы по frozen OpenAPI | G01 VERIFIED |
| **S02-02** | `store` | Спецификация `backend/docs/data-model.md` | `backend/internal/store/**`, изолированные store tests (миграции через координатора) | Репозитории PostgreSQL (pgx), транзакции, queries, soft/hard cleanup, idempotency/nonce store | S02-01 |
| **S02-03** | `identity` | `contracts/signing-vectors.json`, `security-contract.md` | `backend/internal/auth/**`, `backend/internal/device/**`, unit tests | Валидация Keystore SPKI, RequestSigner v1 verifier, challenge/complete, token service, refresh recovery | S02-01 |
| **S02-04** | `profiles` | `contracts/profile-v2.schema.json`, `envelope-vectors.json` | `backend/internal/profiles/**`, unit tests | Каталог узлов, асинхронная выдача (202 Accepted -> pending), генерация Profile Envelope v2 (AES-GCM+RSA-OAEP+ECDSA) | S02-01 |
| **S02-05** | Главный координатор | Результаты S02-02..04 | `backend/internal/httpapi/**`, `backend/cmd/admin/**`, shared config/main | Интеграция HTTP роутера, middleware аутентификации/подписи/лимитов, RFC 7807 problem responses, CLI инвайтов | S02-02, S02-03, S02-04 |
| **S02-06** | `api-security` | Скомпилированный API, контракты | `backend/tests/security/**` | Тесты безопасности: подделка подписи, replay, подмена тела, token reuse attacks, envelope tampering | S02-05 |
| **S02-07** | `db-integration` | Скомпилированные сервисы, test DB | `backend/tests/integration/**` | Интеграционные тесты PostgreSQL: конкурентный enrollment (20 concurrent), concurrency refresh, CAS renewals | S02-05 |
| **S02-08** | `contract-client` | Golden векторы, запущенный API | `backend/tests/contract/**` | Conformance тесты HTTP API против `contracts/mobile-v1.openapi.yaml` | S02-05 |
| **S02-09** | Главный координатор | Отчёты тестов S02-06..08 | `docs/hellokitty/execution/status.md`, `docs/hellokitty/execution/S02-report.md` | Финализация этапа 02, аудит диффов, закрытие Gate G02 | S02-06, S02-07, S02-08 |

### Внешние блокеры (ведутся в журнале, НЕ блокируют локальную разработку этапа 02):
1. Публичное доменное имя проекта и публичные TLS-сертификаты (требуются к G03).
2. Зарубежные выделенные exit-ноды для трафика (требуются к G04).
3. Тестовые аккаунты VK и сессии (требуются к G06).
4. Физические устройства и российские SIM-карты (требуются к G05/G07).
5. Релизный Android keystore (требуется к G08).
