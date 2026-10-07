# S01-REVIEW-FIX-REPORT — Итоговый координирующий отчёт по устранению дефектов независимого ревью G01 (F01–F10)

**Дата UTC**: 2026-10-07  
**Роль / исполнитель**: Главный координатор (Coordinator S01-R2-E)  
**Рабочая копия**: `/root/projects/hellokittyvpn`  
**Git HEAD**: `61b4c8fb1f63fd00efc629ae5117632edbf2dbe5` (с проверенными рабочими диффами)  
**Статус ворот G01**: `VERIFIED`  
**Статус готовности к этапу 02 (G02)**: `READY`  

> [!NOTE]
> Настоящий отчёт полностью актуализирует и замещает предыдущие отчёты первой коррекции (`S01-fix-report.md`, `S01-fix-auth.md`, `S01-fix-data.md`, `S01-fix-mobile.md`), которые сохраняются в репозитории как `superseded historical evidence`.

---

## 1. Сводная матрица устранения дефектов F01–F10

Все десять дефектов F01–F10 независимого аудита устранены, покрыты строгими регрессионными тестами и верифицированы на исполняемых средах:

| ID | Приоритет | Исходный дефект | Изменённые артефакты | Исполняемые регрессии (Before -> After) | Команда / Среда / Код возврата | Статус |
|---|---|---|---|---|---|---|
| **F01** | P1 | `reauth` (`mode=reauth`) вызывал enrollment, продлевал срок действия grant и позволял воскрешать отозванные и просроченные гранты | `contracts/security-contract.md`<br>`contracts/probes/crypto/test_refresh_state_probe.py`<br>`contracts/probes/crypto/go/refresh_state_test.go` | До: HTTP 200, новый grant ID, продление expiry.<br>После: строгий отказ на revoked/expired/missing grants; сохранение неизменного grant ID и исходного expiry; атомарная ротация family под мьютексом. 9 новых тестов. | `python3 contracts/probes/crypto/test_refresh_state_probe.py`<br>`go test -race -v -run TestLostResponseRefreshStateProbe`<br>Exit code: 0 (100% PASS) | **VERIFIED** |
| **F02** | P1 | Каскадное удаление `refresh_tokens.issuance_operation_id ON DELETE CASCADE` уничтожало действующие токены при очистке операций через 48 часов | `backend/docs/data-model.md`<br>`contracts/probes/persistence/schema.sql`<br>`contracts/probes/persistence/test_data_invariants.py` | До: удаление операции старше 48ч удаляло активный refresh token.<br>После: `ON DELETE SET NULL`, сохранение долгоживущих `issuance_client_operation_id` и `request_body_sha256` непосредственно в записи токена. Токен активен. | `python3 contracts/probes/persistence/test_data_invariants.py`<br>PostgreSQL 17.11 (Debian)<br>Exit code: 0 (100% PASS) | **VERIFIED** |
| **F03** | P1 | Финализация провижининга в SQL вставляла профиль и переводила операцию в `ready` при `cas_rows=0` и не проверяла поколение ноды (`node_generation`) | `backend/docs/data-model.md`<br>`contracts/probes/persistence/schema.sql`<br>`contracts/probes/persistence/test_data_invariants.py` | До: при несовпадении ревизии/поколения создавался профиль и статус `ready`.<br>После: строгий CAS с `RETURNING id`, проверка 0 строк, привязка `node_generation`, откат транзакции, 0 профилей создано, операция `failed`. Повтор идемпотентен. | `python3 contracts/probes/persistence/test_data_invariants.py`<br>PostgreSQL 17.11 (Debian)<br>Exit code: 0 (100% PASS) | **VERIFIED** |
| **F04** | P1 | В транзакции рефреша после COMMIT присутствовал дублирующий INSERT в кэш восстановления с внешним `client_operation_id` вместо `operations.id` | `backend/docs/data-model.md`<br>`contracts/probes/persistence/schema.sql`<br>`contracts/probes/persistence/test_data_invariants.py` | До: лишний post-commit INSERT с ошибочным ID, сбой которого ломал идемпотентный рефреш.<br>После: единственный предкоммитный INSERT с привязкой к внутреннему суррогатному `operations(id)` (UUIDv7). | `python3 contracts/probes/persistence/test_data_invariants.py`<br>PostgreSQL 17.11 (Debian)<br>Exit code: 0 (100% PASS) | **VERIFIED** |
| **F05** | P1 | Таблица `operations` не сохраняла хеш тела запроса, что делало невозможным распознавание 409 Conflict при повторе ключа с измененным телом | `backend/docs/data-model.md`<br>`contracts/security-contract.md`<br>`contracts/probes/persistence/schema.sql`<br>`contracts/probes/persistence/test_data_invariants.py` | До: отсутствовали столбцы `http_method, request_path, request_body_sha256`.<br>После: добавлены столбцы, check constraint на 64 hex символа, глобальный для устройства `UNIQUE(device_id, client_operation_id)`. Повтор с измененным телом вызывает конфликт. | `python3 contracts/probes/persistence/test_data_invariants.py`<br>PostgreSQL 17.11 (Debian)<br>Exit code: 0 (100% PASS) | **VERIFIED** |
| **F06** | P1 | Персональные UUID VLESS хранились открытым текстом в таблице `credentials` и в payload таблицы `outbox` | `backend/docs/data-model.md`<br>`contracts/security-contract.md`<br>`contracts/probes/persistence/schema.sql`<br>`contracts/probes/persistence/test_data_invariants.py` | До: открытый `uuid UUID NOT NULL` в БД и outbox.<br>После: хранение только в виде шифротекста AES-256-GCM (`aead_nonce, aead_ciphertext`), AAD связывание с контекстом, слепой индекс `fingerprint_sha256`, маскирование секрета в outbox. Отсутствие открытого UUID в дампах БД. | `python3 contracts/probes/persistence/test_data_invariants.py`<br>PostgreSQL 17.11 (Debian)<br>Exit code: 0 (100% PASS) | **VERIFIED** |
| **F07** | P2 | В `GoldenVectorsHarnessTest.kt` проверялись только 2 негативных вектора из 11; позитивная проверка пропускала серверную подпись ECDSA | `levik_vpn_android/app/src/test/java/org/hellokittyvpn/android/core/network/GoldenVectorsHarnessTest.kt` | До: ветвление по ID обрабатывало только neg_01 и neg_04; 9 векторов пропускались без валидации.<br>После: единый конвейер `verifyEnvelopePipeline` проверяет все 2 позитивных и все 11 негативных векторов (строгий DER, low-$S$, RSA-OAEP SHA-256/SHA-1, AAD, GCM, метаданные). | `./gradlew testDirectDebugUnitTest`<br>`./gradlew testPlayDebugUnitTest`<br>Exit code: 0 (100% PASS) | **VERIFIED** |
| **F08** | P2 | Эндпоинт `/v1/devices/challenges` возвращал неподписанное время `serverTime` без криптографического контракта доверия | `contracts/security-contract.md`<br>`contracts/mobile-v1.openapi.yaml`<br>`contracts/challenge-vectors.json`<br>`contracts/probes/crypto/test_challenge_probe.py`<br>`contracts/probes/crypto/go/challenge_probe_test.go`<br>`GoldenVectorsHarnessTest.kt` | До: `serverTime` не имело подписи, отсутствовал клиентский нонс.<br>После: специфицирован 9-строчный канонический формат `HKVPN-CHALLENGE-V1`, `clientNonce` (16B), `serverNonce` (32B), подпись ES256 P-256 с low-$S$, проверка дрейфа времени ($\le 86400$с). Кросс-языковые тесты в Python, Go, Kotlin на 2 позитивных и 11 негативных векторах. | `python3 contracts/probes/crypto/test_challenge_probe.py`<br>`go test -race -v -run TestChallengeProbe`<br>`./gradlew testDirectDebugUnitTest`<br>Exit code: 0 (100% PASS) | **VERIFIED** |
| **F09** | P2 | Рассинхронизация статуса ответа при истечении окна повтора рефреша: OpenAPI задавал 410, спецификация безопасности — 401 | `contracts/security-contract.md`<br>`contracts/mobile-v1.openapi.yaml`<br>`contracts/probes/crypto/test_refresh_state_probe.py`<br>`contracts/probes/crypto/go/refresh_state_test.go` | До: конфликт спецификаций (401 vs 410).<br>После: везде зафиксирован единый статус HTTP `410 REFRESH_RETRY_EXPIRED` для повторов старше 120 с. Обновлены тексты, таблицы, модели и тесты на Python и Go. | `python3 contracts/probes/crypto/test_refresh_state_probe.py`<br>`go test -race -v -run TestLostResponseRefreshStateProbe`<br>Exit code: 0 (100% PASS) | **VERIFIED** |
| **F10** | P2 | Скрипт `conformance_test.py` пропускал фиктивный пример профиля из OpenAPI, содержавший неполные метаданные и невалидный шифротекст | `contracts/mobile-v1.openapi.yaml`<br>`contracts/probes/conformance/conformance_test.py` | До: фиктивный пример принимался поверхностной проверкой.<br>После: в OpenAPI внедрен подлинный расшифровываемый конверт `pos_01_modern_oaep_sha256`. В `conformance_test.py` добавлена полная расшифровка RSA-OAEP + AES-GCM, проверка JSON Schema Draft 2020-12, всех обязательных полей и регрессионный отказ на старом фиктивном примере. | `python3 contracts/probes/conformance/conformance_test.py`<br>Exit code: 0 (100% PASS) | **VERIFIED** |

---

## 2. Комплексная проверка мульти-языкового окружения

Все компоненты проверены соответствующими компиляторами и тестовыми раннерами без пропусков:

### 2.1. Python 3.12 (Криптография, Конформность, Персистентность)
```bash
python3 contracts/probes/conformance/conformance_test.py
# Результат: 6/6 наборов пройдены со 100% успехом (схемы Draft 2020-12, OpenAPI 3.1.0, Envelope v2, RequestSigner v1, HKVPN-CHALLENGE-V1, аутентичный пример OpenAPI).

python3 contracts/probes/crypto/test_signing_probe.py
# Результат: 10 позитивных и 11 негативных векторов RequestSigner v1 пройдены.

python3 contracts/probes/crypto/test_envelope_probe.py
# Результат: 2 позитивных и 11 негативных векторов Envelope v2 пройдены.

python3 contracts/probes/crypto/test_refresh_state_probe.py
# Результат: Все сценарии рефреша, 10-поточный concurrent race, 9 регрессий F01 reauth и 410 статус пройдены.

python3 contracts/probes/crypto/test_challenge_probe.py
# Результат: 2 позитивных и 11 негативных векторов HKVPN-CHALLENGE-V1 пройдены.
```

### 2.2. Go 1.26.5 Toolchain (Стандартная библиотека, Concurrency, Race Detector)
```bash
cd contracts/probes/crypto/go
go test -v -race -count=1 ./...
go vet ./...
# Результат: 0 race conditions, 0 warnings.
# Проверены: TestLostResponseRefreshStateProbe, TestConcurrentFirstRefreshRace, TestConcurrentRefreshRaces,
# TestProfileEnvelopeGoldenVectors, TestRequestSignerGoldenVectors, TestChallengeProbe.
```

### 2.3. PostgreSQL 17.11 (Изолированный Docker контейнер, сетевая изоляция `--network none`, tmpfs)
```bash
python3 contracts/probes/persistence/test_data_invariants.py
# Результат: 6 наборов тестов выполнены на чистом экземпляре PostgreSQL 17:
# 1. Схема DDL (17 таблиц) и генератор UUIDv7 через pgcrypto.
# 2. F02: Retention decoupling (ON DELETE SET NULL), активный токен сохранен при purge операции.
# 3. F03: Provisioning CAS & generation binding (3 сценария: несовпадение ревизии, несовпадение поколения, успешная атомарная финализация).
# 4. F04: Однократная запись recovery cache до коммита с привязкой к operations(id).
# 5. F05: Изоляция пространства имен идемпотентности UNIQUE(device_id, client_operation_id) и проверка SHA256 хеша тела.
# 6. F06: AEAD шифрование секрета VLESS AES-256-GCM, слепой индекс и отсутствие plaintext в дампах БД.
```

### 2.4. Android / Kotlin JVM Harness (Gradle 8.13, OpenJDK 17)
```bash
cd levik_vpn_android
./gradlew testDirectDebugUnitTest --tests 'org.hellokittyvpn.android.core.network.GoldenVectorsHarnessTest'
./gradlew testPlayDebugUnitTest --tests 'org.hellokittyvpn.android.core.network.GoldenVectorsHarnessTest'
# Результат: BUILD SUCCESSFUL на обоих флейворах.
# Выполнены: verifySigningVectors (10 pos, 11 neg), verifyEnvelopePipeline (2 pos, 11 neg),
# verifyChallengePipeline (2 pos, 11 neg). Bypass подписи полностью исключен.
```

### 2.5. Единый запускаемый скрипт верификации
```bash
bash contracts/probes/crypto/run_all_probes.sh
# Результат: Выполнены все 7 фаз проверки, 100% PASS RATE.
```

---

## 3. Обновлённый манифест заморозки контрактов (`contracts-freeze.sha256`)

Все 8 нормативных файлов зафиксированы в манифестах `contracts/execution/contracts-freeze.sha256` и `docs/hellokitty/execution/contracts-freeze.sha256`:

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

Контрольная сумма нормативной модели базы данных:
- `backend/docs/data-model.md`: `016a0b1c4a1b03b697783e0df8f2c51f521ea129c67441c822f19dc99d1d885c`

---

## 4. Ограничения и передача на Этап 02

1. **Границы этапа 01**:
   - Настоящий backend Go API (`backend/cmd/api`), worker, provisioner и сетевые вызовы к узлам Xray остаются за пределами этапа 01 и будут реализованы на этапах 02–03.
   - Проверки PostgreSQL в рамках данного этапа выполнялись на изолированном эфемерном контейнере Docker со схемой контракта для подтверждения инвариантов DDL и транзакций.
2. **Передача на Этап 02**:
   - Промпт [`STAGE_02_AGENT_PROMPT.md`](../agent-plan/STAGE_02_AGENT_PROMPT.md) полностью актуализирован с учетом решений F01–F10.
   - Gate G01 переведён в статус `VERIFIED`.
   - Gate G02 готов к запуску (`READY`).
