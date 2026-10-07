# S01-REVIEW-FIX-INPUTS — Входное состояние, хеши и решения повторного ревью G01

Дата: 2026-10-07 UTC  
Основание: Независимое ревью G01 contract freeze, выявившее 10 дефектов (F01–F10).  
Текущий HEAD: `61b4c8fb1f63fd00efc629ae5117632edbf2dbe5`  
Статус рабочей копии: модифицированы `docs/hellokitty/README.md`, `docs/hellokitty/agent-plan/README.md`; untracked `STAGE_01_REVIEW_FIX_PROMPT.md`, `STAGE_02_AGENT_PROMPT.md`.

---

## 1. Контрольные суммы текущих артефактов на входе

| Путь к файлу | SHA-256 на входе | Примечание |
|---|---|---|
| `contracts/mobile-v1.openapi.yaml` | `fe768ae152df21bb6b7677511cd27f7ea758dbca719d36877caf96c62ea863f1` | Требует исправлений (F08, F09, F10) |
| `contracts/node-xray-v2.openapi.yaml` | `15cf56ebdbdfe03a8ec08d418aa847deb5c5b05eedb56f90c8d46b4ff2a481ea` | Без изменений |
| `contracts/profile-v2.schema.json` | `ceefe91ebaad44c4d4da0ee6ab043a9562785181c69e2be1dacaa0e248afae35` | Без изменений |
| `contracts/routing-rules-v1.schema.json` | `6e87b0865467ea542284d45beb8860c65e387b575feaa4f410fdd8af1cdd3944` | Без изменений |
| `contracts/security-contract.md` | `cb2c5699062bcf184a54ab81a971f82e8c19f4199f0f3dac8095f2f09d7df7d9` | Требует исправлений (F01, F04, F05, F06, F08, F09) |
| `contracts/signing-vectors.json` | `c9055079124a2ec97e978202af6047cfbe7584fe85702d1cf7bd17187f71b06e` | Без изменений |
| `contracts/envelope-vectors.json` | `21be97992f62e09951570408af4c067191bdc0c11213d8b03390a3298cbcd7ff` | Сохраняется (F07/F10 валидируют его полностью) |
| `backend/docs/data-model.md` | — | Требует исправлений (F02, F03, F04, F05, F06) |
| `contracts/challenge-vectors.json` | — | Новый артефакт (F08) |

---

## 2. Перечень выявленных дефектов (F01–F10)

1. **F01 (P1)**: Reauth в референсных моделях (`test_refresh_state_probe.py`, `refresh_state_test.go`) вызывает enrollment, восстанавливает отозванные/истёкшие grants и продлевает срок вместо сохранения прежнего grant ID и expiry.
2. **F02 (P1)**: Очистка таблицы `operations` (retention 48ч) через `ON DELETE CASCADE` на `refresh_tokens.issuance_operation_id` каскадно удаляет действующие refresh токены (срок 30 дней).
3. **F03 (P1)**: Переход в статус `ready` и генерация профиля происходят безусловно, даже если CAS `UPDATE credentials` затронул 0 строк (несовпадение generation/revision, истёкший grant, отозванное устройство).
4. **F04 (P1)**: Двойная запись в кэш восстановления после COMMIT в `backend/docs/data-model.md` с подменой wire `clientOperationId` вместо `operation_id`.
5. **F05 (P1)**: В таблице `operations` отсутствует хеш тела запроса (`request_body_sha256`), что не позволяет надёжно отличить повтор (exact retry) от конфликта изменённого запроса (409 Conflict).
6. **F06 (P1)**: Персональный VLESS UUID хранится в открытом виде (`UUID NOT NULL`) в БД и в открытом виде в очереди `outbox`.
7. **F07 (P2)**: `GoldenVectorsHarnessTest.kt` проверяет только 2 негативных вектора из 11 (`neg_01`, `neg_04`), пропуская остальные 9 без ассертов, а позитивный тест не проверяет ECDSA подпись сервера.
8. **F08 (P2)**: Эндпоинт `POST /v1/devices/challenges` возвращает `serverTime` и `keyId`, но подпись не специфицирована (`additionalProperties: false`), отсутствует wire-контракт проверки времени.
9. **F09 (P2)**: Рассогласование HTTP статуса для просроченного повтора refresh-токена (> 120s): в OpenAPI указан `410 REFRESH_RETRY_EXPIRED`, в security-contract и probes — `401`.
10. **F10 (P2)**: Conformance suite пропускает фиктивный OpenAPI пример профиля без обязательных полей метаданных и не расшифровывает реальный envelope.

---

## 3. Базовые архитектурные решения (Preflight Decisions)

1. **HTTP Status (F09)**:
   - Закреплен единый статус: `410 REFRESH_RETRY_EXPIRED` (Gone) для exact retry после окна 120 секунд.
   - Синхронизируются: `contracts/security-contract.md`, `contracts/mobile-v1.openapi.yaml`, `test_refresh_state_probe.py`, `refresh_state_test.go`, `backend/docs/data-model.md`.

2. **Idempotency Namespace & Binding (F05)**:
   - Единый namespace: `UNIQUE(device_id, client_operation_id)`.
   - Авторитетная привязка: `(device_id, client_operation_id, operation_type, http_method, request_path, request_body_sha256)`.
   - Exact retry: идентичные поля возвращают сохраненный результат.
   - Изменение тела/пути/типа: возврат `409 Conflict`.
   - Попытка доступа чужого устройства: отказ авторизации (401/403).

3. **Retention & Token Preservation (F02)**:
   - В `refresh_tokens`: поле `issuance_operation_id` объявляется `NULLABLE` с `ON DELETE SET NULL`.
   - Долговременные поля привязки `issuance_client_op_id`, `issuance_body_hash`, `consumed_by_client_op_id`, `consumed_by_body_hash` сохраняются в записи токена до его собственного retention.
   - Очистка `operations` через 48 часов не затрагивает живые токены (до 30 дней).
   - Окно кэша восстановления: ровно 120 секунд.

4. **Signed Server Time & Challenge Contract (F08)**:
   - Префикс/версия: `HKVPN-CHALLENGE-V1`.
   - Назначение: `purpose = "challenge"`.
   - Алгоритм: `ES256` (NIST P-256 ECDSA, SHA-256, strict DER, low-$S$).
   - Поля `ChallengeRequest`: добавлен `clientNonce` (16 байт, unpadded Base64URL).
   - Поля `ChallengeResponse`: `challengeId`, `serverNonce` (32 байта Base64URL), `expiresAt`, `serverTime`, `keyId`, `purpose="challenge"`, `signatureAlgorithm="ES256"`, `requestBodyHash` (hex SHA-256 сырого тела запроса), `signature` (Base64URL).
   - Формат канонической строки (ровно 9 строк, каждая оканчивается `\n`):
     ```text
     HKVPN-CHALLENGE-V1\n
     challenge\n
     ES256\n
     <keyId>\n
     <challengeId>\n
     <serverNonce>\n
     <expiresAt>\n
     <serverTime>\n
     <requestBodyHash>\n
     ```

5. **Криптографическая защита учетных данных at rest (F06)**:
   - VLESS UUID и relay-секреты шифруются алгоритмом AES-256-GCM.
   - Назначение ключа: `credential_storage`.
   - AAD привязка: `HKVPN-CREDENTIAL-V1\n<credential_id>\n<device_id>\n<node_id>\n<revision>\n<generation>`.
   - Поиск/проверка уникальности: слепой индекс / хеш `fingerprint_sha256` (SHA-256 от сырого секрета).
   - Outbox содержит только ссылку на `credential_id` и метаданные; воркер подтягивает секрет в память перед RPC.
