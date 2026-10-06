# Hello Kitty VPN: Итоговый отчёт по исправлению G00 и выполнению Этапа 01

**Дата формирования**: 2026-10-06 UTC  
**Проект**: Hello Kitty VPN  
**Статусы ворот**:
- **G00 (Исходное состояние и передача)**: **VERIFIED**
- **G01 (Контракты и безопасность)**: **VERIFIED**
- **G02 (Бэкэнд и control plane)**: **NOT RUN (READY TO START)**

---

## 1. Исправление замечаний приёмки G00

В соответствии с требованиями приёмки были устранены все ранее выявленные архитектурные и фактологические пробелы:

1. **Фиксация эталонного снимка исходного кода (Source Baseline)**:
   - Сформирован и верифицирован файл [`docs/hellokitty/execution/baseline-source.sha256`](file:///root/projects/hellokittyvpn/docs/hellokitty/execution/baseline-source.sha256).
   - Включает ровно **372 файла** (225 отслеживаемых файлов из `HEAD 0d05186a...`, 124 новых исходных файла Android в пакете `org.hellokittyvpn.android`, 23 файла документации `docs/hellokitty/`).
   - Удалены 197 файлов устаревшего пакета `com.leviknet.vpn` и неиспользуемых ресурсов.
   - Из снимка строго исключены артефакты сборки (`build/`, APK), временные кэши (`.gradle/`, `.cache/`) и любые секреты/ключи (`.env`, `*.key`, `*.jks`, `*.pem`).
   - Проверка `sha256sum -c baseline-source.sha256` подтвердила 100% целостность.

2. **Исправление выводов об инфраструктуре хоста**:
   - Зафиксировано, что локальные команды `ip addr` и `ip route` показывают внутреннюю сетевую конфигурацию и шлюзы, но не подтверждают внешнюю геолокацию или ASN без внешних запросов к базам BGP/GeoIP.
   - Отсутствие listener на TCP 443 не является доказательством внешней сетевой доступности порта из интернета (требуется учёт сетевых экранов провайдера).
   - Выполнена прямая read-only проверка сетевого экрана: обнаружено 13 правил `DROP` в IPv4 filter rules (изоляция мостов Docker и ограничение порта 6768 интерфейсом `tailscale0`) и 1 правило `DROP` в IPv6. Данные правила не блокируют порт 443, однако подтверждают, что `ss` не заменяет проверку firewall.

3. **Исправление топологии VK-транспорта**:
   - Устранено ошибочное требование обязательного размещения собственного exit-узла в РФ. Доступный первый узел (VK/TURN relay) и собственный exit — раздельные архитектурные компоненты. География сервера сама по себе не определяет работоспособность транспорта.

4. **Корректировка требований к порту PostgreSQL**:
   - Размещение БД Hello Kitty в изолированной контейнерной сети Docker без публикации порта на хост полностью исключает конфликт со сторонней службой на `127.0.0.1:5432`. Порт хоста 25432 опционален.

5. **Уточнение гарантий безопасности Android-клиента**:
   - В [`DeviceIdentity.kt`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/DeviceIdentity.kt) зафиксировано наличие программного fallback при сбое StrongBox (TEE/software Keystore), а также fallback с `MODERN` на `LEGACY`.
   - В [`HybridProfileDecryptor.kt`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/HybridProfileDecryptor.kt) зафиксировано, что компонент выполняет исключительно расшифровку шифротекста (AES-GCM + RSA-OAEP), но **не валидирует цифровую подпись сервера**, `deviceId`, срок годности и anti-rollback ревизии. Эти проверки формализованы как обязательные в этапе 01.

6. **Синхронизация очереди этапа 01**:
   - Публичный API возвращён к согласованной базе `/v1` (`/v1/devices/challenges`, `/v1/devices/complete`, `/v1/tokens/refresh`, `/v1/servers`, `/v1/profiles` и др.) вместо неподтверждённых путей `/api/v2/devices/*`.

---

## 2. Результаты выполнения Этапа 01 «Контракты и безопасность»

Работа выполнена через три независимых потока без взаимного пересечения изменяемых файлов:

### Направление 1: Мобильные контракты (S01-MOBILE)
- **`contracts/mobile-v1.openapi.yaml`**: Полная спецификация OpenAPI 3.1.0 публичного Mobile API `/v1`.
  * Эндпоинты: `/v1/devices/challenges` (enroll/reauth, CSPRNG nonce 32B, 120s TTL), `/v1/devices/complete` (подпись RequestSigner v1, idempotency key), `/v1/tokens/refresh` (ротация, recovery потерянного ответа), `/v1/servers` (каталог до 200 узлов, **query строго запрещён**), `/v1/profiles` (202 Accepted, pending статус), `/v1/operations/{id}`, `/v1/profiles/{id}` (Envelope v2), `/v1/credentials/{id}/renew`, `DELETE /v1/devices/me` (естественно идемпотентный, без Idempotency-Key), `/v1/routing-rules/manifest`, `/livez`, `/readyz`.
  * Ограничения размеров: тело запроса до 64 KiB (потолок 1 MiB); профиль до 2 MiB (дешифрованный до 1 MiB, метаданные до 16 KiB).
  * Единая система перечислений (Enum vocabulary) для статусов операций, аренд и состояний клиента.
  * Модель ошибок RFC 7807 (`application/problem+json`).
  * 16 детальных примеров запросов/ответов, прошедших валидацию без ошибок.
- **`contracts/profile-v2.schema.json`**: JSON Schema Draft 2020-12 для `TunnelProfileEnvelopeV2` и дешифрованного `TunnelProfilePayloadV2`. Строгая валидация `additionalProperties: false`, ограничения base64url и SHA-256 fingerprint.
- **`contracts/routing-rules-v1.schema.json`**: JSON Schema Draft 2020-12 для `RoutingRulesManifest` с лимитами (распакованный пакет до 8 MiB, до 8 файлов, до 65 536 CIDR, до 10 000 доменов).

### Направление 2: Криптография и безопасность (S01-CRYPTO)
- **`contracts/security-contract.md`**:
  * Модель угроз (STRIDE): 5 классов нарушителей, защита от MITM, replay, credential reuse, cross-device injection, rollback и timing атак.
  * Канонический формат `RequestSigner v1`: ровно 8 строк, разделитель LF (`\n`), без завершающего newline, uppercase HTTP метод, encodedPath без query/fragment, epochSeconds ($\pm 120$с), 16-байтный base64url nonce (уникальность в БД 5 мин), hex SHA256(SPKI), hex SHA256(token), hex SHA256 сырого тела до JSON-парсинга. Алгоритмы: PS256 (RSA-PSS, salt 32) и RS256 (PKCS#1 v1.5).
  * Конверт `HKVPN-PROFILE-V2`: AES-256-GCM + RSA-OAEP (Modern: SHA-256; Legacy: SHA-1) + серверная подпись ECDSA P-256 (strict DER). Точные побайтовые формулы для AAD и signed string.
  * Протокол Lost-Response Refresh Recovery: окно восстановления 120 с для точных повторов без повторной ротации и отзыва семьи; детекция token reuse с немедленным отзывом семьи; `409 Conflict` при том же operationId с изменённым телом; `401 REFRESH_RETRY_EXPIRED` по истечении 120 с с переходом на Keystore reauth.
  * Разделение 6 классов ключей: TLS Ingress, Server Profile Signer, Routing Signer, Device Keystore, Node mTLS/HMAC, Storage Key.
- **`contracts/signing-vectors.json`**: 10 позитивных и 11 негативных векторов RequestSigner v1.
- **`contracts/envelope-vectors.json`**: 2 позитивных (Modern/Legacy) и 10 негативных векторов Profile Envelope v2.
- **Исполняемые probes (`contracts/probes/crypto/`)**:
  * Python: `test_signing_probe.py`, `test_envelope_probe.py`, `test_refresh_state_probe.py`.
  * Go (stdlib): `signing_probe_test.go`, `envelope_probe_test.go`, `refresh_state_test.go`.
  * Мастер-скрипт `run_all_probes.sh` — 100% успешное прохождение.

### Направление 3: Модель данных и ноды (S01-DATA)
- **`backend/docs/data-model.md`**: Спецификация реляционной схемы PostgreSQL 16+/17 на базе упорядоченных по времени первичных ключей **UUIDv7** (RFC 9562).
  * 17 таблиц: `devices`, `invitations`, `access_grants`, `challenges`, `token_families`, `refresh_tokens`, `access_tokens`, `request_nonces`, `nodes`, `node_capabilities`, `credentials`, `leases`, `profiles`, `operations`, `encrypted_response_cache`, `outbox`, `node_observations`.
  * Транзакционные границы: атомарный enrollment, атомарная ротация refresh, outbox worker (`SELECT ... FOR UPDATE SKIP LOCKED`), разделение soft/hard deletion.
  * Модель пула WDTT (`10.66.66.0/24`, 249 IP) с частичным уникальным индексом `UNIQUE(node_id, ip_address) WHERE lease_status != 'tombstone'` и обязательным 48-часовым окном удержания (`purge_after = expires_at + 48h`).
- **`contracts/node-xray-v2.openapi.yaml`**: OpenAPI 3.1.0 для частного API управления узлом Xray `/internal/v2/xray/*` с аутентификацией mTLS + HMAC и требованием Durable Replay Cache.
- **Isolated Spike Xray Core Proto (`contracts/probes/node/`)**:
  * Зафиксирован критический факт: **в Xray-core отсутствует RPC `ListUsers`**! Ответ `AlterInboundResponse` пуст, пользователи хранятся в RAM ядра и сбрасываются при рестарте.
  * Установлено, что `RemoveUser` запрещает новые рукопожатия, но **не разрывает активные TCP/UDP сессии**.
  * Разработана модель Authoritative Observed Evidence: успешный ответ `AlterInbound` + инкремент поколения ноды (`generation`) + монотонная ревизия + локальный crash-safe журнал на ноде + реконсилиация при старте + expiry sweeper.
  * Исполняемый probe `xray_proto_spike.py` (8/8 тестов успешно пройдено).

---

## 3. Запись фиксации контрактов (Freeze Record)

| Путь к файлу контракта | Назначение и состав | SHA-256 контрольная сумма |
|---|---|---|
| `contracts/mobile-v1.openapi.yaml` | Спецификация публичного Mobile API /v1 (OpenAPI 3.1.0) | `f3e399befc4dea543eceb69336d94bfea36b908126dd1182b64911e70314e311` |
| `contracts/profile-v2.schema.json` | JSON Schema (Draft 2020-12) TunnelProfileEnvelopeV2 | `f8c00d6edd8bccc9eb827bfe87b932a1b20cb2ac373b6a528457235c61598440` |
| `contracts/routing-rules-v1.schema.json` | JSON Schema (Draft 2020-12) RoutingRulesManifest v1 | `6e87b0865467ea542284d45beb8860c65e387b575feaa4f410fdd8af1cdd3944` |
| `contracts/security-contract.md` | Модель угроз STRIDE, канонизация, Envelope v2, Lost Refresh Recovery | `7b97a981eea29c10e67b3bab6a959a1cdda8697e2c37324a6d34724db724edbf` |
| `contracts/signing-vectors.json` | Golden векторы подписи RequestSigner v1 (10 pos, 11 neg) | `c9055079124a2ec97e978202af6047cfbe7584fe85702d1cf7bd17187f71b06e` |
| `contracts/envelope-vectors.json` | Golden векторы Profile Envelope v2 (2 pos, 10 neg) | `dead712628ae40ab9631c531e10f85a973cd223d1f659861706f42af83bbf34a` |
| `contracts/node-xray-v2.openapi.yaml` | Спецификация частного API ноды Xray v2 (OpenAPI 3.1.0) | `15cf56ebdbdfe03a8ec08d418aa847deb5c5b05eedb56f90c8d46b4ff2a481ea` |
| `backend/docs/data-model.md` | Спецификация схемы PostgreSQL 16+/17 на UUIDv7 (17 таблиц) | `a85e68c31426adb01717e16acfd5e6b64af3e5fc88d24803f05b5215ef5e0f24` |
| `contracts/probes/crypto/run_all_probes.sh` | Мастер-скрипт криптографических тестов | `323a77192346e3ae4cc4a1264f45879a34223597df5b28ae9ad62d7ebcb1a1b4` |
| `contracts/probes/node/xray_proto_spike.py` | Исполняемый spike Xray proto и пула WDTT | `8ff08d6099c146f16b5d4e58523426830a51e1f965aefd2d992fd3ed51bca82e` |

---

## 4. Сводная матрица верификации поведения

| Проверка | Команда / Среда | Статус | Результат / Доказательство |
|---|---|---|---|
| Сквозная конформность схем и OpenAPI | `python3 contracts/probes/conformance/conformance_test.py` | **VERIFIED** | Спецификации OpenAPI 3.1.0 валидны. Схемы Draft 2020-12 валидны (local $ref resolution). Все 16 примеров эндпоинтов проверены. |
| Канонизация и подпись RequestSigner v1 | `go test -v -race ./...` + `GoldenVectorsHarnessTest.kt` | **VERIFIED** | Python, Go и Kotlin наборы тестов прошли все 10 позитивных и 11 негативных векторов подписей PS256 и RS256. Каноническая строка полностью идентична в Android и Go. |
| Проверка шифрования и подписи Envelope v2 | Python `test_envelope_probe.py` + Go `envelope_probe_test.go` | **VERIFIED** | Все 2 позитивных (Modern/Legacy) и 11 негативных векторов проверены: строгий пайплайн без bypasses, low-$S$ нормализация, повреждения шифротекста, IV, AAD, завернутого ключа и high-$S$ отклоняются. |
| Потерянный ответ Refresh и гонки данных | Python `test_refresh_state_probe.py` + Go `go test -race` | **VERIFIED** | Разделены `issuance` и `consumed_by`, проверка device ownership (чужой вызов не отзывает family жертвы), моделирование grant, отсутствие data race при конкурентном exact retry (20 workers). |
| Запрет восстановления отозванного доступа | Go `refresh_state_test.go` | **VERIFIED** | Отозванный грант не восстанавливается через reauth (`GRANT_REVOKED`). |
| Spike по Xray Core Proto и пул WDTT | `python3 contracts/probes/node/xray_proto_spike.py -v` | **VERIFIED** | 8/8 тестов пройдены: подтверждены RPC `GetInboundUsers` и `GetInboundUsersCount`, `proxy.UserManager` в VLESS, семантика сессий при RemoveUser, поколение узла, лимит 249 IP и 48-часовой retention grace. |
| Android Kotlin/JVM Test Harness | `./gradlew testDirectDebugUnitTest` | **VERIFIED** | `GoldenVectorsHarnessTest` успешно дешифрует тестовые конверты RSA-OAEP + AES-GCM и валидирует подписи RequestSigner. Все unit-тесты Direct и Play вариантов зеленые. |
| Разделение ключей | Аудит `contracts/security-contract.md` | **VERIFIED** | Разделены 6 независимых классов ключей без взаимного пересечения. |

---

## 5. Очередь задач этапа 02 (Control Plane Backend)

| Task ID | Роль / Исполнитель | Входные данные | Владелец файлов | Задачи и результаты | Зависимость |
|---|---|---|---|---|---|
| **S02-01** | Главный координатор | Gate G01, Freeze Record | `backend/go.mod`, `backend/go.sum`, `backend/cmd/api/**`, `backend/internal/config/**` | Инициализация модуля Go, конфигурация, DTO интерфейсы по замороженным контрактам | G01 VERIFIED |
| **S02-02** | `store` | `backend/docs/data-model.md` | `backend/internal/store/**`, изолированные store tests | Репозитории PostgreSQL (pgx), транзакции, queries, soft/hard cleanup, idempotency/nonce store | S02-01 |
| **S02-03** | `identity` | `contracts/signing-vectors.json`, `security-contract.md` | `backend/internal/auth/**`, `backend/internal/device/**`, unit tests | Валидация Keystore SPKI, RequestSigner v1 verifier, challenge/complete, token service, refresh recovery | S02-01 |
| **S02-04** | `profiles` | `contracts/profile-v2.schema.json`, `envelope-vectors.json` | `backend/internal/profiles/**`, unit tests | Каталог узлов, асинхронная выдача (202 Accepted -> pending), генерация Profile Envelope v2 | S02-01 |
| **S02-05** | Главный координатор | Результаты S02-02..04 | `backend/internal/httpapi/**`, `backend/cmd/admin/**`, shared config/main | Интеграция HTTP роутера, middleware аутентификации/подписи/лимитов, RFC 7807 problem responses, CLI инвайтов | S02-02..04 |
| **S02-06** | `api-security` | Скомпилированный API, контракты | `backend/tests/security/**` | Тесты безопасности: подделка подписи, replay, подмена тела, token reuse attacks, envelope tampering | S02-05 |
| **S02-07** | `db-integration` | Скомпилированные сервисы, test DB | `backend/tests/integration/**` | Интеграционные тесты PostgreSQL: конкурентный enrollment (20 concurrent), concurrency refresh, CAS renewals | S02-05 |
| **S02-08** | `contract-client` | Golden векторы, запущенный API | `backend/tests/contract/**` | Conformance тесты HTTP API против `contracts/mobile-v1.openapi.yaml` | S02-05 |
| **S02-09** | Главный координатор | Отчёты тестов S02-06..08 | `docs/hellokitty/execution/status.md`, `S02-report.md` | Финализация этапа 02, аудит диффов, закрытие Gate G02 | S02-06..08 |

---

## 6. Текущие ограничения и внешние зависимости

1. **Разделяемый сервер**: На хосте на `127.0.0.1:5432` запущен сторонний сервис (`popcorn`). Новая база данных Hello Kitty для этапа 02 функционирует строго внутри изолированной сети Docker без публикации порта на хост. На `0.0.0.0:56000` работает сторонний WDTT-сервер.
2. **Особенности Xray Core API**: В Xray-core присутствуют RPC селективного чтения `GetInboundUsers` (по email) и `GetInboundUsersCount`, но отсутствует нефильтрованный дамп `ListUsers`. Удаление пользователя через `RemoveUser` запрещает новые соединения, но не прерывает уже активные потоки.
3. **Лимиты WDTT Relay**: Пул ограничен 249 IP (`10.66.66.2`–`.250`), а удержание записей в течение 48 часов (`retentionGrace`) блокирует повторное использование адресов до очистки через статус `released`.
4. **Внешние блокеры** (не блокируют разработку этапа 02): публичное доменное имя, публичные TLS-сертификаты, зарубежные exit-ноды, VK-аккаунты, физические SIM-карты РФ, релизный keystore Android.
4. **Внешние блокеры** (не блокируют разработку этапа 02): публичное доменное имя, публичные TLS-сертификаты, зарубежные exit-ноды, VK-аккаунты, физические SIM-карты РФ, релизный keystore Android.
