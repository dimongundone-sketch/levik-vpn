# S01-DATA / storage-design — краткий результат

Дата UTC: 2026-10-06  
Главный агент / исполнитель: S01-DATA (роль `storage-design`)  
Source baseline HEAD + working snapshot hash: `0d05186a3436028f9392e22cbbc30781b0e59129`  
Scope / разрешённые paths:  
- `backend/docs/data-model.md`  
- `contracts/node-xray-v2.openapi.yaml`  
- `contracts/probes/node/**`  
- `docs/hellokitty/execution/S01-data.md`  
Prerequisites / gate evidence: Входной gate G00 закрыт (аудит S00-BASE, S00-NODE, S00-ANDROID). Зависимости и правила этапа 01 зафиксированы в `docs/hellokitty/agent-plan/01-contracts-and-security.md`.

---

## Что изменено

1. **Модель данных PostgreSQL (`backend/docs/data-model.md`)**:
   - Полная DDL-спецификация схемы PostgreSQL 16+/17 на базе упорядоченных по времени первичных ключей **UUIDv7** (RFC 9562).
   - Определены все 17 обязательных таблиц:
     * `devices`: идентичность устройств по SHA256(DER_SPKI) (wire deviceId), ключи Keystore, алгоритмы подписи/шифрования (`PS256`/`RS256`, `RSA-OAEP-SHA256`/`SHA1`).
     * `invitations`: хэши кодов инвайтов, лимит использования `max_uses = 1` для пилота, счетчик `uses_count`.
     * `access_grants`: связь устройства с инвайтом, частичный уникальный индекс ровно на 1 активный грант на устройство.
     * `challenges`: одноразовые вызовы CSPRNG (32 байта, TTL 120с), режимы `enroll` и `reauth`.
     * `token_families`: отслеживание семейств токенов с детекцией reuse атаки, частичный уникальный индекс на 1 активную семью на устройство.
     * `refresh_tokens`: хеши 256-битных токенов, привязка к `bound_operation_id` и `bound_body_hash`, абсолютный TTL 30 дней.
     * `access_tokens`: хеши bearer токенов, TTL 15 минут.
     * `request_nonces`: защита от replay атак, PK(`device_fingerprint`, `nonce`), TTL 5 минут.
     * `nodes`: реестр узлов Xray и WDTT, `generation`, квоты емкости.
     * `node_capabilities`: поддерживаемые протоколы и транспорты.
     * `credentials`: VLESS UUID и ссылки на пароли релея, монотонные `desired_revision` и `observed_revision`, TTL 24ч, ротация 12ч, лимит <= 2 активных на устройство.
     * `leases`: внутритуннельные IP адреса (WDTT пул `10.66.66.0/24`, 249 IP), срок 24ч, **48ч retention grace window** (`purge_after = expires_at + 48h`), частичный уникальный индекс `UNIQUE(node_id, ip_address) WHERE lease_status != 'tombstone'` для предотвращения коллизий IP.
     * `profiles`: готовые зашифрованные конверты (Profile Envelope v2). Запись создается **только после подтверждения data plane**.
     * `operations`: журнал клиентских операций и идемпотентности, уникальность `(device_id, client_operation_id)`, статусы `pending/ready/failed`.
     * `encrypted_response_cache`: зашифрованный серверным ключом кэш для восстановления ответа при обрыве связи (Lost Response Retry) в течение 120 секунд.
     * `outbox`: транзакционная очередь задач провижининга узлов (`SELECT ... FOR UPDATE SKIP LOCKED`).
     * `node_observations`: сырые факты наблюдений состояния узлов (`raw_evidence` JSONB, `generation`).
   - Детально формализованы транзакционные границы и инварианты:
     * **Enrollment**: атомарная транзакция (потребление challenge, проверка invitation, создание device, grant, family, refresh_token, access_token, cache).
     * **Refresh**: атомарная транзакция (детекция точного повтора в окне 120с, детекция reuse с отзывом всего семейства, легитимная ротация).
     * **Outbox worker**: выборка батча `FOR UPDATE SKIP LOCKED`, сетевой RPC к ноде **строго вне транзакции БД**, CAS-финализация и переход операции в `ready` только при совпадении ревизий.
     * **Retention & Deletion**: разделение soft deletion (devices, grants, families, credentials) и bounded batch hard deletion (nonces 5m, cache 120s, challenges 48h, outbox 7d, leases tombstone purge строго после 48h grace).

2. **OpenAPI 3.1.0 контракт API ноды Xray v2 (`contracts/node-xray-v2.openapi.yaml`)**:
   - Частный внутренний API управления узлом Xray `/internal/v2/xray/*`.
   - Сетевое размещение: доступ строго через mTLS reverse proxy.
   - Эндпоинты:
     * `POST /internal/v2/xray/credentials/apply`: применение (создание/продление) VLESS учетной записи.
     * `POST /internal/v2/xray/credentials/revoke`: отзыв VLESS учетной записи.
     * `POST /internal/v2/xray/credentials/status`: чтение наблюдаемого состояния узла без мутации ревизии.
     * `GET /livez`: проверка процесса агента.
     * `GET /readyz`: проверка доступности gRPC HandlerService ядра Xray через loopback сокет.
   - Аутентификация: заголовки `X-HKVPN-Node-*` (`Key-Id`, `Timestamp`, `Nonce`, `Signature`, `Idempotency-Key`).
   - Каноническая строка HMAC-SHA256 v2:
     `hkvpn-node-hmac-v2\n<timestamp>\n<nonce>\n<METHOD>\n<requestURI>\n<sha256Hex(body)>`
   - Требование Durable Replay Cache: персистентное хранение использованных nonces с TTL 5 минут, переживающее перезапуск сервиса.

3. **Isolated Spike и тесты Xray Core Proto / Command APIs (`contracts/probes/node/**`)**:
   - Исследован реальный protobuf `app/proxyman/command/command.proto` и бинарник Xray 26.3.27.
   - **Зафиксирован критический архитектурный факт: В Xray-Core НЕТ RPC `ListUsers`!**
   - Установлено, что ответ `AlterInboundResponse` является пустым сообщением, а динамические пользователи хранятся в RAM Xray-core и теряются при рестарте процесса.
   - Установлено ограничение сессий: вызов `RemoveUser` запрещает новые рукопожатия, но **НЕ завершает уже открытые активные TCP/UDP соединения** в ядре Xray.
   - Сформулировано Authoritative Observed Evidence: успешный gRPC ответ `AlterInbound` + локальный crash-safe журнал node-agent (`/var/lib/hkvpn/xray-state.json`) + поколение узла (`generation`) + автоматическая реконсилиация при старте + локальный expiry sweeper.
   - Реализован исполняемый probe `contracts/probes/node/xray_proto_spike.py` с 8 тестами, покрывающими:
     * Отсутствие `ListUsers` в proto.
     * Жизненный цикл пользователей через `AlterInbound`.
     * Поведение открытых сессий при `RemoveUser`.
     * Восстановление состояния и инкремент `generation` при рестарте Xray.
     * Работу Durable Replay Cache.
     * Проверку монотонности ревизий и идемпотентности.
     * Модель пула WDTT (10.66.66.2–250, 249 IP), 24h lease, 48h retention grace и предотвращение коллизий IP через tombstones.
     * Безопасную очистку tombstones после истечения retention grace.

---

## Задачи и владельцы

| Task ID | Owner | Write paths | Dependencies | Status | Evidence |
|---|---|---|---|---|---|
| S01-DATA-01 | `storage-design` | `backend/docs/data-model.md` | G00, 01-contracts | VERIFIED | Документ создан; SHA256: `a85e68c31426adb01717e16acfd5e6b64af3e5fc88d24803f05b5215ef5e0f24` |
| S01-DATA-02 | `storage-design` | `contracts/node-xray-v2.openapi.yaml` | G00, 01-contracts | VERIFIED | Контракт создан и валидирован; SHA256: `15cf56ebdbdfe03a8ec08d418aa847deb5c5b05eedb56f90c8d46b4ff2a481ea` |
| S01-DATA-03 | `storage-design` | `contracts/probes/node/**` | G00, Xray binary | VERIFIED | README и `xray_proto_spike.py` созданы; 8 unit тестов успешно пройдены |
| S01-DATA-04 | `storage-design` | `docs/hellokitty/execution/S01-data.md` | S01-DATA-01..03 | VERIFIED | Настоящий отчёт сформирован по REPORT_TEMPLATE.md |

---

## Проверки

| Check | Command / environment | Status | Actual result / evidence | Limits |
|---|---|---|---|---|
| OpenAPI YAML syntax validation | `python3 -c "import yaml; yaml.safe_load(open('contracts/node-xray-v2.openapi.yaml'))"` | VERIFIED | YAML 100% валиден; пути `/livez`, `/readyz`, `/internal/v2/xray/credentials/{apply,revoke,status}` подтверждены | Синтаксическая валидация Python |
| Xray proto analysis & binary inspection | `strings /usr/local/bin/xray \| grep -E "HandlerService\|AlterInbound\|AddUserOperation"` | VERIFIED | Подтверждены методы `AddInbound`, `RemoveInbound`, `AlterInbound`, `AddUserOperation`, `RemoveUserOperation`; отсутствие `ListUsers` RPC подтверждено | Чтение символов бинарника Xray 26.3.27 |
| Node probe test suite execution | `python3 contracts/probes/node/xray_proto_spike.py` | VERIFIED | 8 тестов выполнено за 0.122s, статус `OK` (100% passed) | Автономный исполняемый probe |
| File checksum integrity check | `sha256sum backend/docs/data-model.md contracts/node-xray-v2.openapi.yaml contracts/probes/node/*` | VERIFIED | Все хеши зафиксированы и перепроверены | SHA-256 |
| Scope boundaries verification | `git status --porcelain` | VERIFIED | Изменения изолированы строго в `backend/docs/data-model.md`, `contracts/node-xray-v2.openapi.yaml`, `contracts/probes/node/`, `docs/hellokitty/execution/S01-data.md` | Read-only инвариант чужих файлов соблюден |

---

## Gate

G01: **IN PROGRESS** (Вклад субагента S01-DATA полностью закрыт со статусом **VERIFIED**).  
- Спецификация модели данных PostgreSQL и транзакционных инвариантов завершена (`backend/docs/data-model.md`).  
- Спецификация OpenAPI для Xray v2 ноды создана (`contracts/node-xray-v2.openapi.yaml`).  
- Pinned proto/probe spike исполнен, подтверждены отсутствие `ListUsers`, поведение сессий Xray, требования к generation/reconciliation и модель пула WDTT (`contracts/probes/node/**`).  
- Ожидаются результаты параллельных субагентов S01-MOBILE (`contracts/mobile-v1.openapi.yaml`) и S01-CRYPTO (`contracts/signing-vectors.json`, `contracts/security-contract.md`) для финального совместного freeze этапа 01 главным координатором.

---

## Совместимость и риск

1. **Отсутствие ListUsers RPC в Xray-core**:
   - *Риск*: Невозможность прямого аудита активных пользователей через ядро.
   - *Решение*: Node-agent обязан вести локальный персистентный журнал (`xray-state.json`) с атомарной записью и выполнять реконсилиацию при каждом рестарте, инкрементируя `generation`.
2. **Переживание активных соединений после RemoveUser**:
   - *Риск*: Клиент с отозванным или истекшим доступом может продолжать передавать данные по уже установленному TCP-туннелю.
   - *Решение*: Максимальное время жизни сессии ограничено TTL аренды (24ч). Для принудительного мгновенного разрыва требуется дополнительный механизм изоляции соединений (iptables/nftables socket drop или ротация сессионных параметров инбаунда) на этапе 03.
3. **Емкость пула WDTT (249 IP)**:
   - *Риск*: При частой перерегистрации пул 249 IP быстро исчерпывается из-за обязательного 48-часового окна удержания tombstones.
   - *Решение*: Control plane обязан балансировать запросы между несколькими релейными нодами при достижении порога `active + tombstone >= 200`.

---

## Передача следующему этапу

### Артефакты и контрольные суммы:
- `backend/docs/data-model.md`: `a85e68c31426adb01717e16acfd5e6b64af3e5fc88d24803f05b5215ef5e0f24`
- `contracts/node-xray-v2.openapi.yaml`: `15cf56ebdbdfe03a8ec08d418aa847deb5c5b05eedb56f90c8d46b4ff2a481ea`
- `contracts/probes/node/README.md`: `7f46ff271194160a2e3f3aa683865d8c118f07edb8b8eb9714f18ca169553cab`
- `contracts/probes/node/xray_proto_spike.py`: `8ff08d6099c146f16b5d4e58523426830a51e1f965aefd2d992fd3ed51bca82e`
- `docs/hellokitty/execution/S01-data.md`: текущий файл отчета.

### Входные данные для Этапа 02 (Backend Control Plane) и Этапа 03 (Node Provisioning):
1. **Для S02-02 (Store Worker)**: использовать DDL и транзакционные сценарии из `backend/docs/data-model.md` для написания миграций PostgreSQL и репозиториев Go (`pgx`).
2. **Для S03-04 (Xray Provisioner)**: использовать OpenAPI контракт `contracts/node-xray-v2.openapi.yaml` и архитектуру observed evidence/journal из `contracts/probes/node/`.
3. **Для S03-05 (Agent Security)**: реализовать спецификацию Durable Replay Cache (срок жизни 5м) для защиты от replay при перезапусках ноды.
