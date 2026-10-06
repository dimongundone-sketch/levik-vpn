# S01-MOBILE — Контракты OpenAPI 3.1.0, профиль v2 и правила маршрутизации v1

Дата UTC: 2026-10-06
Главный агент / исполнитель: S01-MOBILE (роль `contracts`)
Source baseline HEAD: `0d05186a3436028f9392e22cbbc30781b0e59129`
Working snapshot hash: `dirty working tree (baseline preserved, contracts added)`
Scope / разрешённые paths:
- `contracts/mobile-v1.openapi.yaml`
- `contracts/profile-v2.schema.json`
- `contracts/routing-rules-v1.schema.json`
- `docs/hellokitty/execution/S01-mobile.md`

Prerequisites / gate evidence: Входной gate G00 закрыт (`docs/hellokitty/execution/S00-report.md`, `baseline-source.sha256` проверен на 372 файла, 10 consumers в Android инвентаризованы, audit нод и хоста завершён).

---

## Что изменено

Разработаны и зафиксированы три нормативных контракта этапа 01:

1. **`contracts/mobile-v1.openapi.yaml`** (OpenAPI 3.1.0 спецификация публичного мобильного API `/v1`):
   - **`POST /v1/devices/challenges`**: запрос одноразового CSPRNG-челленджа (32 байта nonce, 120s TTL) для режимов `enroll` (с проверкой `invitationCode`) и `reauth` (для зарегистрированных устройств с действующим грантом). Защита от перебора и rate limiting (5/мин на инвайт, 20/мин на IP). Публичный эндпоинт без заголовков авторизации.
   - **`POST /v1/devices/complete`**: завершение регистрации/переаутентификации с подтверждением владения ключом Android Keystore (`RequestSigner v1` канонические байты, пустой токен). Заголовки `X-HKVPN-*` и `Idempotency-Key` (строго равный `clientOperationId` в теле). Атомарное создание устройства и гранта без повторного расхода инвайта при reauth. Безопасное повторное получение кэшированного ответа при сетевом сбое в окне 120 с.
   - **`POST /v1/tokens/refresh`**: ротация refresh-токена внутри активного семейства. Заголовок `Authorization` отсутствует; refresh-токен подписывается внутри JSON-тела. Поддержка восстановления потерянного ответа при exact retry (тот же `clientOperationId`) в окне 120 с; при попытке использования старого токена с новым `clientOperationId` немедленно отзывается всё семейство токенов устройства.
   - **`GET /v1/servers`**: получение каталога доступных узлов (ограничение до 200 серверов). Строгий запрет query-параметров в signing v1 для защиты от canonicalization mismatch. Требует Bearer access token и подпись `X-HKVPN-*`.
   - **`POST /v1/profiles`**: асинхронный запрос выдачи профиля туннеля. Возвращает статус `202 Accepted` со ссылкой на операцию (`status: pending`). Никакой выдачи готового профиля до observed data-plane apply на ноде.
   - **`GET /v1/operations/{id}`**: опрос статуса асинхронной операции (состояния `pending`, `ready`, `failed`). Проверка владения устройством.
   - **`GET /v1/profiles/{id}`**: получение защищённого конверта `TunnelProfileEnvelopeV2` (шифрование AES256-GCM, ключ обёрнут RSA-OAEP ключом устройства, подписан строгой DER ECDSA P-256 подписью сервера).
   - **`POST /v1/credentials/{id}/renew`**: запрос безопасной ротации ревизии учетных данных с контролем `expectedRevision` (CAS-семантика). Возвращает `202 Accepted`.
   - **`DELETE /v1/devices/me`**: естественно идемпотентный отзыв устройства, гранта, токенов и активных аренд. Пустое тело, **без заголовка `Idempotency-Key`**. Повторный вызов возвращает `401 Unauthorized` или `410 Gone`.
   - **`GET /v1/routing-rules/manifest`**: публичный эндпоинт манифеста правил с поддержкой кэширования (`ETag`, `Cache-Control`) и проверкой подписи ECDSA P-256.
   - **`/livez`, `/readyz`**: служебные пробы liveness и readiness.
   - **RFC 7807 Error Model**: `application/problem+json` с полями `code`, `title`, `status`, `detail`, `retryable`, `retryAfterSeconds`, `requestId`.
   - **Единая система перечислений (Enum vocabulary)**:
     * Статусы операций: `pending`, `ready`, `failed`.
     * Состояния аренды узла (lease): `requested`, `applying`, `active`, `revoking`, `expired`, `failed`.
     * Состояния туннеля клиента: `DISCONNECTED`, `PREPARING`, `CONNECTING`, `VERIFYING`, `CONNECTED`, `DEGRADED`, `RECONNECTING`, `PAUSED`, `BLOCKED_BY_NETWORK`, `CREDENTIAL_EXPIRED`, `ERROR`.
     * Движки и категории серверов: `xray`, `relay`, `regular`, `mobile`, `mobile-allowlist`.
   - **Ограничения размеров**: тело запроса до 64 КиБ (потолок 1 МиБ); профиль до 2 МиБ (дешифрованный до 1 МиБ, метаданные до 16 КиБ); строгий regex для base64url без паддинга `^[A-Za-z0-9_-]+$`.
   - Все эндпоинты снабжены реалистичными примерами (examples), проверенными валидатором.

2. **`contracts/profile-v2.schema.json`** (JSON Schema Draft 2020-12):
   - Задаёт схему конверта `TunnelProfileEnvelopeV2` (`protected`, `wrappedKey`, `iv` (16 символов base64url = 12 байт), `ciphertext`, `signature`).
   - Задаёт схему дешифрованного содержимого `TunnelProfilePayloadV2` (`profileSchemaVersion: 2`, `profileId`, `accessId`, `deviceId`, `issuedAt`, `credentialExpiresAt`, `profileRevision`, `rulesVersion`, `engine`, `servers`, `bootstrap`, `routing`).
   - Строгая валидация: `additionalProperties: false`, обязательные поля, regex для base64url и SHA-256 fingerprint (`^[0-9a-f]{64}$`), лимиты длины ключей и AAD.

3. **`contracts/routing-rules-v1.schema.json`** (JSON Schema Draft 2020-12):
   - Задаёт структуру `RoutingRulesManifest` (`version`, `generatedAt`, `expiresAt`, `minimumClientVersion`, `keyId`, `purpose: routing-rules-v1`, `caps`, `files`, `signature`).
   - Строгие защитные лимиты (`caps`): распакованный пакет до 8 МиБ, не более 8 файлов, суммарно не более 65 536 CIDR и 10 000 доменов; DNS кэш до 8 192 записей / 8 МиБ.
   - Спецификация содержимого файлов пакета (`cidr-list`, `domain-list`, `config`).

---

## Задачи и владельцы

| Task ID | Owner | Write paths | Dependencies | Status | Evidence |
|---|---|---|---|---|---|
| S01-MOBILE | `contracts` (S01-MOBILE) | `contracts/mobile-v1.openapi.yaml`, `contracts/profile-v2.schema.json`, `contracts/routing-rules-v1.schema.json`, `docs/hellokitty/execution/S01-mobile.md` | G00 | VERIFIED | Все 3 схемы созданы, проверены Draft 2020-12 валидатором, все positive/negative тесты пройдены |

---

## Проверки

| Check | Command / environment | Status | Actual result / evidence | Limits |
|---|---|---|---|---|
| YAML parsing & OpenAPI 3.1.0 root structure | `python3 -c "import yaml; oas = yaml.safe_load(open('contracts/mobile-v1.openapi.yaml')); assert oas['openapi'] == '3.1.0'; assert len(oas['paths']) == 12; print('OK')"` | VERIFIED | `OK` (12 paths, openapi 3.1.0, 16 schemas) | Структурная валидность YAML |
| Profile v2 Schema Draft 2020-12 check | `python3 -c "import json; from jsonschema import Draft202012Validator; s = json.load(open('contracts/profile-v2.schema.json')); Draft202012Validator.check_schema(s); print('OK')"` | VERIFIED | `contracts/profile-v2.schema.json is a valid Draft 2020-12 schema!` | Синтаксис мета-схемы |
| Routing Rules v1 Schema Draft 2020-12 check | `python3 -c "import json; from jsonschema import Draft202012Validator; s = json.load(open('contracts/routing-rules-v1.schema.json')); Draft202012Validator.check_schema(s); print('OK')"` | VERIFIED | `contracts/routing-rules-v1.schema.json is a valid Draft 2020-12 schema!` | Синтаксис мета-схемы |
| Envelope & Payload v2 positive validation | `python3 -c "... Draft202012Validator(profile_schema).validate(envelope); Draft202012Validator(profile_schema).validate(payload) ..."` | VERIFIED | `Envelope validation: PASSED`, `Payload validation: PASSED` | Проверка реалистичных инстансов |
| Routing Manifest positive validation | `python3 -c "... Draft202012Validator(routing_schema).validate(manifest) ..."` | VERIFIED | `Manifest validation: PASSED` | Проверка структуры манифеста |
| Negative tests (strict rejection of invalid data) | `python3 -c "... test additional properties, invalid IV length, empty servers, unknown fields ..."` | VERIFIED | 4/4 негативных тестов отвергнуты: дополнительные свойства, невалидный IV, пустые списки серверов | Проверка `additionalProperties: false` |
| Complete OpenAPI examples validation | `python3 -c "... validate all 16 request/response examples against components/schemas using referencing.Registry ..."` | VERIFIED | `ALL OPENAPI EXAMPLES PASSED VALIDATION WITH ZERO ERRORS!` | 16 из 16 примеров соответствуют схемам |

---

## Gate

**G01: Mobile Contracts Component: VERIFIED.**
- `contracts/mobile-v1.openapi.yaml` полностью описывает все требования этапов 01 и 02.
- `contracts/profile-v2.schema.json` фиксирует формат обёрнутого и дешифрованного профиля.
- `contracts/routing-rules-v1.schema.json` фиксирует формат манифеста правил и защитные капы.
- Ограничения размеров, enum vocabulary, RFC 7807 error model зафиксированы.
- Смежные задачи этапа 01 (S01-CRYPTO — тестовые крипто-векторы Go/Kotlin, S01-DATA — модель данных БД и контракт ноды Xray v2) выполняются параллельными субагентами.

---

## Совместимость и риск

- **Wire boundaries**:
  * Внешний `deviceId` на уровне API и протокола подписи строго определён как lowercase hex SHA-256 от DER-кодированного SubjectPublicKeyInfo (64 символа), что согласуется с `DeviceIdentity.kt` в Android (`sha256Hex(keyPair().public.encoded)`). Внутренний ID устройства в базе данных остаётся скрытым UUID.
  * Каноническая строка `RequestSigner v1` содержит 8 строк: `v1\nMETHOD\nencodedPath\nepochSeconds\nnonce\ndeviceId\nSHA256(token)\nSHA256(rawBody)`. Для запросов без токена (`devices/complete`, `tokens/refresh`) токен пустой, и `SHA256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
  * Запрет query-параметров в `GET /v1/servers` предотвращает расхождения в нормализации URL между обратным прокси и бэкендом.
- **Rollback**:
  * Все схемы версионированы (`/v1` в путях API, `profileSchemaVersion: 2`, `version` в манифесте правил).
  * Изменения не затрагивают исполняемый код или базу данных до перехода к этапу 02.

---

## Передача следующему этапу

- **Файлы контрактов**:
  * `contracts/mobile-v1.openapi.yaml` (SHA-256 доступен для включения в общий отчет freeze этапа 01).
  * `contracts/profile-v2.schema.json`.
  * `contracts/routing-rules-v1.schema.json`.
- **Потребители**:
  * **S01-CRYPTO**: использует заголовки `X-HKVPN-*`, канонический формат `RequestSigner v1` и структуру AAD/signature для генерации векторов `signing-vectors.json` и `envelope-vectors.json`.
  * **S01-DATA**: использует структуры операций, учетных данных и серверов для проектирования реляционной схемы (`backend/docs/data-model.md`).
  * **Этап 02 (Backend Go API)**: генерирует DTO и маршруты напрямую из `mobile-v1.openapi.yaml`.
  * **Этап 04 (Android)** и **Этап 05 (Routing)**: используют `profile-v2.schema.json` и `routing-rules-v1.schema.json` для десериализации и валидации.
