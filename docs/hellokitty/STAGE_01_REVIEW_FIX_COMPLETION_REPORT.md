# Hello Kitty VPN: Итоговый отчёт о закрытии ревью G01 (Дефекты F01–F10)

**Дата формирования**: 2026-10-07 UTC  
**Проект**: Hello Kitty VPN  
**Координатор**: Главный координатор (Coordinator S01-R2-E)  
**Репозиторий**: `/root/projects/hellokittyvpn`  
**Git HEAD**: `61b4c8fb1f63fd00efc629ae5117632edbf2dbe5`  

## Статусы ворот (Gates)
- **G00 (Исходное состояние и передача)**: **VERIFIED**
- **G01 (Контракты и безопасность)**: **VERIFIED** (Все замечания F01–F10 повторного ревью закрыты)
- **G02 (Бэкэнд и control plane)**: **READY** (Все предварительные условия этапа 01 выполнены)

---

## 1. Резюме выполнения повторного ревью G01

На основании независимого аудита промежуточной сдачи этапа 01 и нормативного промпта [`STAGE_01_REVIEW_FIX_PROMPT.md`](agent-plan/STAGE_01_REVIEW_FIX_PROMPT.md) была сформирована волновая структура исполнителей (S01-R2-00..E). Все десять выявленных дефектов F01–F10 устранены, формализованы в контрактах и спецификациях, а их корректность подтверждена воспроизводимыми тестами во всех поддерживаемых языковых средах (Python, Go, Kotlin) и на изолированном сервере PostgreSQL 17.

### Ключевые решения и архитектурные изменения:
1. **F01 (Re-authentication Security)**: Протокол повторной аутентификации `mode=reauth` на аппаратных ключах (Keystore) больше не вызывает процедуру enrollment. Он находит действующее устройство и существующий грант, сохраняет неизменными `grant_id` и первоначальный срок его действия (`expires_at`), атомарно отзывает все предыдущие семейства токенов под мьютексом и выдает одно новое семейство. Попытка reauth для отозванных (`revoked`), просроченных (`expired`) или отсутствующих грантов строго блокируется с кодом 401/403 без создания новых записей.
2. **F02 (Retention Decoupling)**: Внешний ключ `refresh_tokens.issuance_operation_id` переведён на `ON DELETE SET NULL`. В запись токена добавлены долговременные поля `issuance_client_operation_id` и `request_body_sha256`. Очистка краткоживущих операций (через 48 часов) больше не удаляет действующие 30-дневные refresh-токены.
3. **F03 (Strict CAS Finalization)**: В транзакции финализации провижининга внедрена проверка строгой атомарной мутации (CAS) `UPDATE credentials ... WHERE id = $1 AND desired_revision = $2 AND status = 'desired' RETURNING id`. При несовпадении ревизии или поколения узла (`node_generation`) CAS затрагивает 0 строк, транзакция фиксирует сбой, профиль не создается, статус операции переводится в `failed`. Повторные подтверждения идемпотентны.
4. **F04 (Single Recovery Cache Write)**: Удален дублирующий некорректный post-commit `INSERT` в кэш восстановления. Единственная зашифрованная запись кэша формируется и сохраняется строго в пределах транзакции рефреша до `COMMIT` с корректной ссылкой на суррогатный `operations.id` (UUIDv7).
5. **F05 (Persisted Idempotency Binding)**: В таблицу `operations` добавлены поля `http_method`, `request_path`, `request_body_sha256` (с проверкой формата 64 hex символа) и уникальный ключ `UNIQUE(device_id, client_operation_id)`. Повтор того же ключа с измененным телом запроса возвращает 409 Conflict без побочных эффектов.
6. **F06 (At-Rest Credential Encryption)**: Персональный UUID VLESS защищен шифрованием AES-256-GCM (`aead_nonce`, `aead_ciphertext`) с привязкой authenticated data (AAD) к контексту учетной записи. В таблице создан слепой детерминированный индекс `fingerprint_sha256` для поиска без расшифровки. В очереди `outbox` персональный секрет маскируется. Проверено отсутствие открытого UUID в дампах базы данных.
7. **F07 (Comprehensive Kotlin Verification)**: Тестовый набор `GoldenVectorsHarnessTest.kt` на Kotlin переписан на единый строгий конвейер `verifyEnvelopePipeline`. Проверены все 2 позитивных и все 11 негативных векторов без условных пропусков (обязательная проверка подписи сервера ECDSA P-256 с low-$S$, проверка метаданных, распаковка ключа RSA-OAEP SHA-256/SHA-1, дешифрование AES-GCM и валидация полезной нагрузки).
8. **F08 (Signed Challenge Contract)**: Формализован канонический 9-строчный wire-контракт `HKVPN-CHALLENGE-V1` для эндпоинта `/v1/devices/challenges`. Добавлены клиентский нонс (`clientNonce`, 16B), серверный нонс (`serverNonce`, 32B), подпись сервера ES256 P-256 с low-$S$, проверка дрейфа локальных часов ($\le 86400$с). Создан корпус векторов `challenge-vectors.json` (2 positive, 11 negative), проверенный на Python, Go и Kotlin.
9. **F09 (Unified 410 REFRESH_RETRY_EXPIRED)**: Полностью унифицирован код ответа для повторного запроса рефреша после истечения окна восстановления (120 с) — HTTP `410 REFRESH_RETRY_EXPIRED`. Устранены разночтения в OpenAPI, спецификации безопасности и тестовых моделях.
10. **F10 (Authentic Conformance & Payload Decryption)**: Фиктивный пример ответа профиля в OpenAPI заменен на подлинный шифрованный конверт `pos_01_modern_oaep_sha256`. В `conformance_test.py` внедрена полная расшифровка конверта и валидация расшифрованного содержимого против JSON Schema Draft 2020-12, а также регрессионный тест на отказ прежнего фиктивного примера.

---

## 2. Результаты верификации и тестовые доказательства

| Тестовый набор / Раннер | Проверенная область | Результат | Доказательство |
|---|---|---|---|
| **Python Conformance** (`conformance_test.py`) | Draft 2020-12 мета-схемы, OpenAPI 3.1.0, Envelope v2 decrypt/schema, RequestSigner v1, HKVPN-CHALLENGE-V1, аутентичный пример OpenAPI | **100% PASS** (6/6 сьютов) | [`S01-review-fix-wire.md`](execution/S01-review-fix-wire.md) |
| **Python Crypto Probes** (`test_signing_probe.py`, `test_envelope_probe.py`, `test_challenge_probe.py`) | Подписи RequestSigner (10 pos, 11 neg), конверты Envelope (2 pos, 11 neg), челленджи (2 pos, 11 neg) | **100% PASS** | [`S01-review-fix-wire.md`](execution/S01-review-fix-wire.md) |
| **Python State Probe** (`test_refresh_state_probe.py`) | Ротация токенов, 120s lost-response кэш, 10-поточный concurrent race, 9 регрессий F01 reauth, HTTP 410 статус | **100% PASS** (18 сценариев) | [`S01-review-fix-auth.md`](execution/S01-review-fix-auth.md) |
| **Go 1.26.5 Crypto & Race** (`go test -race -v ./...`) | Кросс-языковые тесты подписей, конвертов, челленджей и конкурентной ротации токенов | **100% PASS** (0 races, 0 vet warnings) | [`S01-review-fix-auth.md`](execution/S01-review-fix-auth.md), [`S01-review-fix-wire.md`](execution/S01-review-fix-wire.md) |
| **PostgreSQL 17 Invariants** (`test_data_invariants.py`) | Изолированный Docker `--network none`, tmpfs: DDL (17 таблиц), UUIDv7, F02 retention, F03 CAS/generation, F04 cache write, F05 idempotency, F06 AEAD secrets | **100% PASS** (6/6 сьютов) | [`S01-review-fix-data.md`](execution/S01-review-fix-data.md) |
| **Android / Kotlin JVM** (`./gradlew testDirectDebugUnitTest`, `./gradlew testPlayDebugUnitTest`) | Строгий пайплайн `GoldenVectorsHarnessTest` на обоих флейворах Play и Direct (все 26 векторов) | **BUILD SUCCESSFUL** (100% PASS) | [`S01-review-fix-kotlin.md`](execution/S01-review-fix-kotlin.md) |
| **Единый запускаемый раннер** (`bash contracts/probes/crypto/run_all_probes.sh`) | Интегрированный запуск всех криптографических, конформных и персистентных проверок | **100% PASS RATE** (7/7 фаз) | [`S01-review-fix-report.md`](execution/S01-review-fix-report.md) |

---

## 3. Манифест заморозки контрактов (`contracts-freeze.sha256`)

Все 8 файлов контрактов этапа 01 заморожены и согласованы:

```
0d23a959f27f956e7738d6e31e0f8fc0bd74e477f47960bd80c37d4d64e3bb0e  contracts/mobile-v1.openapi.yaml
15cf56ebdbdfe03a8ec08d418aa847deb5c5b05eedb56f90c8d46b4ff2a481ea  contracts/node-xray-v2.openapi.yaml
ceefe91ebaad44c4d4da0ee6ab043a9562785181c69e2be1dacaa0e248afae35  contracts/profile-v2.schema.json
6e87b0865467ea542284d45beb8860c65e387b575feaa4f410fdd8af1cdd3944  contracts/routing-rules-v1.schema.json
214b19bb7f88964059f76b4ea13bc8a9c6a681c0169abebe60d8cd0394588303  contracts/security-contract.md
c9055079124a2ec97e978202af6047cfbe7584fe85702d1cf7bd17187f71b06e  contracts/signing-vectors.json
21be97992f62e09951570408af4c067191bdc0c11213d8b03390a3298cbcd7ff  contracts/envelope-vectors.json
4036fc555adfc2650bf677a67f1c2a4a530c53ed6bc39ce5f249400ca001154d  contracts/challenge-vectors.json
```

Хеш нормативной спецификации модели данных:
- `backend/docs/data-model.md`: `016a0b1c4a1b03b697783e0df8f2c51f521ea129c67441c822f19dc99d1d885c`

---

## 4. Передача управления на Этап 02 (Control Plane)

Входные требования этапа 02 полностью удовлетворены:
1. Контракты и векторы согласованы, проверены и заморожены.
2. Транзакционная модель PostgreSQL выверена и подтверждена на версии 17.
3. Инструкции и промпт для следующего агента актуализированы в [`STAGE_02_AGENT_PROMPT.md`](agent-plan/STAGE_02_AGENT_PROMPT.md).
4. Ворота **G01** переведены в статус **`VERIFIED`**, ворота **G02** готовы к старту (**`READY`**).
