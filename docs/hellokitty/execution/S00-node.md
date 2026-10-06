# S00-NODE / Relay node-agent and contracts audit — краткий результат

Дата UTC: 2026-10-06
Главный агент / исполнитель: node-audit (субагент слота 2)
Source baseline HEAD + working snapshot hash: `0d05186a3436028f9392e22cbbc30781b0e59129` (дерево `levik_whitelist_relay/` полностью чистое; dirty изменения изолированы в Android клиенте и `docs/hellokitty/`)
Scope / разрешённые paths: `/root/projects/hellokittyvpn/docs/hellokitty/execution/S00-node.md`
Prerequisites / gate evidence: `S00-BASE` snapshot taken, зависимости закрыты, строгий read-only аудит кода `levik_whitelist_relay/` без мутаций.

## Что изменено

Код `levik_whitelist_relay/` и системные файлы НЕ изменялись (read-only аудит).
Создан отчёт аудита `docs/hellokitty/execution/S00-node.md` с детальным анализом API-контрактов, параметров безопасности, ограничений ёмкости и архитектурных границ между `node-agent`, `WDTT Plus` и `Xray`.

### 1. Анализ Private API и контрактов (`contracts/openapi.yaml`)
- **Спецификация**: OpenAPI 3.1.0 (`contracts/openapi.yaml`, SHA256 `91d446071d389e002f2a01a69fbf43da3e0f6bd889a3cc8216ffeadcc41eabd5`), AGPL-3.0-only.
- **Сетевое размещение**: Агент слушает исключительно локальный loopback (`127.0.0.1:9088`, валидация через `requireLoopback()`). Доступ снаружи разрешён строго через mTLS reverse proxy (`https://relay-node.invalid`).
- **Служебные эндпоинты (без аутентификации, только loopback)**:
  - `GET /livez`: проверка живости процесса агента. Возвращает `{"ok": true, "protocolVersion": 1}` (HTTP 200).
  - `GET /readyz`: проверка доступности WDTT admin socket (`Ping()` через Unix-сокет с таймаутом 2с). Возвращает `{"ok": true, "protocolVersion": 1}` (HTTP 200) или `{"ok": false, "code": "wdtt_unavailable"}` (HTTP 503).
- **Управляющие эндпоинты (`/internal/v1/leases/*`, требуют HMAC аутентификации)**:
  - `POST /internal/v1/leases/apply`: Создание новой или продление существующей аренды подписки/устройства. Возвращает HTTP 201 (при создании) или HTTP 200 (при продлении/идемпотентном повторе) с объектом `LeaseEnvelope`. Учётные данные (пароль 16 символов) возвращаются только при создании, продлении или точных идемпотентных повторах.
  - `POST /internal/v1/leases/rotate`: Ротация пароля для существующей аренды. Возвращает HTTP 200 с новым паролем.
  - `POST /internal/v1/leases/revoke`: Отзыв аренды. Никогда не возвращает секретные данные. Переводит статус в `revoked` (если запись была в WDTT) или `absent` (если запись отсутствовала, фиксируя tombstone revision).
  - `POST /internal/v1/leases/status`: Чтение статуса аренды (`active`, `expired`, `revoked`, `absent`, `unknown`). Не возвращает учётные данные и не инкрементирует revision.
- **Спецификация профиля ноды**: `contracts/node-profile.schema.json` (SHA256 `11beaf19f9fa6f9cfaa5f42ea954f83c4db8a3c9cecf0078508356288c63a17c`). Поля: `id`, `displayName`, `countryCode`, `host`, `port`, `turnFrontSni`, `transport` (`"turn-dtls"`), `serverPublicKey` (base64url unpadded, 43 символа = 32 байта), `turnHashes` (массив 1–4 хешей). Потребитель в Android: [TunnelProfileParser.kt](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/TunnelProfileParser.kt#L178-L208).

### 2. Лимиты и сетевой пул
- **Подсеть и адресация**:
  - Подсеть туннеля WireGuard/WDTT: `10.66.66.0/24` (интерфейс `wdtt0`).
  - Адрес сервера: `10.66.66.1`.
  - Пул клиентов: `10.66.66.2` – `10.66.66.250` (`wgPoolFirstHost = 2`, `wgPoolLastHost = 250`).
  - **Жёсткий лимит ёмкости ноды**: ровно **249 IP-адресов** (`maxNodeDeviceCapacity = 249`).
- **Сроки аренды и удержания (Lease & Retention)**:
  - Максимальный срок действия аренды: **24 часа** (`maxLeaseDuration = 24 * time.Hour`, `expiresAt` валидируется в диапазоне от `now + 60s` до `now + 86400s`).
  - Срок удержания после истечения: **48 часов** (`DefaultRetentionGrace = 48 * time.Hour`, настраивается от 1ч до 7 дней через флаг `--retention-grace`).
  - **КРИТИЧЕСКОЕ ВЛИЯНИЕ НА ЁМКОСТЬ**: В WDTT Plus при истечении аренды запись переводится в статус `expired`, но физически удаляется (`delete(db.Passwords, p)` и `delete(db.Devices, deviceID)`) только по наступлении `PurgeAfter = expiresAt + retentionGrace` (то есть через 48 часов после истечения). На протяжении этих 48 часов IP-адрес в `db.Devices` остаётся зарезервированным за устройством и **учитывается в лимите 249 IP**! Реальная динамическая ёмкость пула при частой ротации устройств ограничена 249 активными + удержанными записями.
- **Сетевые и системные квоты**:
  - На ноде открываются фиксированные порты WDTT: `56000,56001,9000` (`--wdtt-ports`).
  - WDTT сервер (`levik-relay-server`): `--max-passwords=249`, `--max-client-mbps=100`, `--max-workers-per-access=36`, `--max-handshakes=128`, `--handshake-rate=200`.

### 3. Source Pins и зависимости сборки
- **Инструменты (`source/tools.lock`, SHA256 `b97c561842ca6eb41b5370628e31340a46ac6ebd7cb42c26da8217c4a16156c3`)**:
  - `GO_VERSION=1.26.5` (Linux amd64 SHA256: `5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053`).
  - `ANDROID_NDK_VERSION=29.0.14206865`.
  - `ANDROID_API=26`.
  - `ANDROID_ABIS=arm64-v8a,armeabi-v7a,x86_64`.
  - `ANDROID_PAGE_SIZE=16384` (обязательное 16 KiB выравнивание ELF LOAD-сегментов для Android 15/16).
- **Upstream фиксации (`source/upstream.lock.json`, SHA256 `4e375125fd2944e17820fb274aee434ae3b64a95e48b8c6df2392b69e70b2801`)**:
  - `WDTT Plus`: tag `v15`, commit `3038b8ddc0306feb21d3c3624e2bc1c3c14639ad`, tarball SHA256 `07c6a4c200c87c636a6d0855385e96284e73ddcc5b80c912a463b068ef964223`, лицензия `GPL-3.0-only`.
  - `qWDTT Android client`: commit `fae121efc3ef57b633516601d3c0d6b1be1fde7c`, tarball SHA256 `1a2b4f559890e0688ea608c6890a7794131acd583acc612d23e30f59e8c53e9c` (использован исключительно паттерн передачи TUN FD через `SCM_RIGHTS`).
  - Исключённый проект: `CSQTT` (`amurcanov/csqtt`) — полностью исключён, код и зависимости не заимствовались.
- **Скрипты нативной сборки**:
  - [scripts/build-android-client.sh](file:///root/projects/hellokittyvpn/levik_whitelist_relay/scripts/build-android-client.sh): компилирует `liblevikrelay.so` для 3 ABI с проверкой отсутствия директив `//go:linkname` в anet, флагами `-buildmode=pie`, `-trimpath`, `-extldflags=-Wl,-z,max-page-size=16384` и верификацией выравнивания `llvm-readelf`.
  - [scripts/build-linux.sh](file:///root/projects/hellokittyvpn/levik_whitelist_relay/scripts/build-linux.sh): компилирует статические бинарники `levik-wdtt-server` и `levik-relay-agent` с `CGO_ENABLED=0` и `-trimpath`.

### 4. Архитектура безопасности
- **Где реально находится mTLS**:
  - Сам Go-процесс `levik-relay-agent` НЕ выполняет TLS/mTLS рукопожатия и слушает незашифрованный HTTP исключительно на `127.0.0.1:9088`.
  - mTLS реализован **исключительно на внешнем обратном прокси Nginx** (`deploy/test-vps/nginx-levik-relay.conf`): порт 8443, директивы `ssl_client_certificate /etc/levik-relay/tls/ca.crt; ssl_verify_client on; ssl_verify_depth 1;`. Nginx проксирует запросы на `http://127.0.0.1:9088`.
  - Сетевой экран nftables дополнительно ограничивает входящий TCP 8443 доверенным IP-адресом control plane (`ip saddr 94.156.114.70 tcp dport 8443 accept`).
- **HMAC-аутентификация (`internal/authn/authn.go`)**:
  - Заголовки запроса:
    - `X-Levik-Key-Id`: идентификатор ключа (`^[A-Za-z0-9._-]{1,64}$`).
    - `X-Levik-Timestamp`: Unix timestamp секунды (`^[0-9]{10}$`), допустимый перекос времени `--max-clock-skew` (по умолчанию ±2 минуты).
    - `X-Levik-Nonce`: криптографический unpadded base64url nonce (`^[A-Za-z0-9_-]{22,128}$`, не менее 16 байт энтропии).
    - `X-Levik-Signature`: lowercase hex HMAC-SHA256 (`^[0-9a-f]{64}$`).
    - `Idempotency-Key`: ключ идемпотентности (`^[A-Za-z0-9._:-]{16,128}$`), обязан побайтово совпадать с `body.idempotencyKey`.
  - Каноническая строка (`levik-hmac-v1`):
    `levik-hmac-v1\n<timestamp>\n<nonce>\n<METHOD>\n<requestURI>\n<sha256Hex(body)>`.
    Верифицировано golden-тестом `contracts/golden-hmac-v1.json`.
  - Сравнение подписи выполняется за постоянное время (`hmac.Equal()`).
  - Ограничение размера тела: 16 KiB (`16 << 10`).
  - Rate limiting (token bucket): 20 rps / 40 burst на ключ, применяется **только после успешной проверки подписи** для предотвращения DoS-атак на легитимные ключи.
- **Replay Cache (In-Memory сейчас vs Durable Replay Requirement)**:
  - **ТЕКУЩАЯ РЕАЛИЗАЦИЯ**: `ReplayCache` реализован **строго in-memory** (`entries map[string]replayEntry` под `sync.Mutex`, TTL 5 минут, лимит 100 000 записей).
  - **УЯЗВИМОСТЬ / АРХИТЕКТУРНЫЙ GAP**: При перезапуске сервиса `levik-relay-agent` (или падении процесса) кеш nonce полностью обнуляется! Если агент перезапустился в пределах окна допустимого перекоса часов (±2 минуты), перехваченный подписанный запрос может быть повторно применён.
  - Состояние `statefile/store.go` защищает от мутаций аренды с той же revision благодаря проверке `idempotencyRef`, но не защищает повторные запросы чтения (`status`) или сценарии до фиксации состояния.
  - **ТРЕБОВАНИЕ К ЦЕЛЕВОЙ АРХИТЕКТУРЕ**: Для закрытия replay-окна при рестартах необходим durable replay cache (персистентное хранилище использованных nonce со временем жизни `2 * maxClockSkew`) либо монотонный счетчик последовательности на стороне control plane.
- **Хранилище состояния и безопасность секретов**:
  - `statefile/store.go`: атомарная запись через временный файл в том же каталоге, `chmod 0600`, `fsync` файла, замена через `rename`, `fsync` директории. Проверка прав: файл `0600`, родительская директория `0700`, владелец root (`Uid == 0`).
  - `securefile/securefile.go`: чтение мастер-ключей через `O_NOFOLLOW | O_CLOEXEC`, валидация симлинков через `EvalSymlinks`, запрет битов группы/остальных (`perm & 0077 == 0`), лимит 4096 байт, проверка `Uid == 0 && Nlink == 1`.
  - Детерминированная деривация учетных данных: `deriveCredential()` использует HMAC-SHA256 с сидом `levik-wdtt-credential-v2\n<leaseRef>\n<credentialRevision>`, формируя 16-значный алфавитно-цифровой пароль без двусмысленных символов (`passwordChars`).
- **Границы процессов и подпроцессов (Subprocess Boundaries)**:
  - В `levik-relay-agent` **полностью отсутствует вызов подпроцессов**: пакет `os/exec` НЕ импортируется и НЕ используется.
  - Взаимодействие с data plane (WDTT сервером) выполняется строго через IPC по Unix domain socket (`/run/levik-relay/admin.sock`).
  - Валидация сокета перед подключением (`validateSocket()`): проверка типа `ModeSocket`, прав `0600`, `Uid == 0`, `Nlink == 1`, родительского каталога `0700` и `Uid == 0`.
  - Изоляция systemd: `levik-relay-agent.service` сбрасывает все Linux capabilities (`CapabilityBoundingSet=`), блокирует сетевые вызовы вне loopback (`IPAddressDeny=any`, `IPAddressAllow=localhost`), включает жесткие ограничения ядра (`ProtectSystem=strict`, `ProtectHome=true`, `MemoryDenyWriteExecute=true`, `NoNewPrivileges=true`).

### 5. Фиксация роли Node-Agent по отношению к Xray
- **КРИТИЧЕСКИЙ ФАКТ**: `node-agent` в текущей кодовой базе **НЕ является provisioner Xray**!
- В `node-agent` полностью отсутствуют эндпоинты, конфигурации, библиотеки или вызовы для управления Xray-сервером, inbound/outbound правилами, протоколами VLESS/VMess/Trojan или маршрутизацией Xray.
- `node-agent` управляет **исключительно жизненным циклом учетных записей WDTT** (Wireguard-DTLS-Tunnel) через локальный Unix-сокет администрирования WDTT.
- Control plane бэкенда не должен рассчитывать на наличие Xray API на релейных нодах: для Xray требуется отдельный механизм провижининга или выделенный сервис.

## Задачи и владельцы

| Task ID | Owner | Write paths | Dependencies | Status | Evidence |
|---|---|---|---|---|---|
| S00-NODE | `node-audit` | `docs/hellokitty/execution/S00-node.md` | S00-BASE | VERIFIED | Полный аудит `levik_whitelist_relay/`: контракты OpenAPI, auth, limits, replay, build scripts, WDTT IPC |

## Проверки

| Check | Command / environment | Status | Actual result / evidence | Limits |
|---|---|---|---|---|
| Read-only repo invariant | `git status --short levik_whitelist_relay/` | VERIFIED | Каталог `levik_whitelist_relay/` чист (0 изменений, 0 untracked файлов) | Read-only проверка |
| Contracts & Schema audit | `sha256sum contracts/openapi.yaml contracts/node-profile.schema.json contracts/golden-hmac-v1.json` | VERIFIED | Хеши подтверждены: openapi.yaml `91d446...`, schema `11beaf...`, golden `59b36f...` | Чтение файлов |
| Source locks audit | `sha256sum source/tools.lock source/upstream.lock.json` | VERIFIED | Хеши подтверждены: tools.lock `b97c56...`, upstream.lock `4e3751...` | Чтение файлов |
| Subprocess execution check | `grep -rn "os/exec" levik_whitelist_relay/node-agent/` | VERIFIED | 0 совпадений; вызовы внешних бинарников и подпроцессов отсутствуют | Статический анализ Go кода |
| IP Pool & Capacity audit | Анализ `fork/wdtt-plus-v15/server.go:45-55,724-736` | VERIFIED | Пул `10.66.66.0/24`, диапазон `.2`–`.250`, ёмкость 249 IP; retention grace 48ч удерживает IP в пуле | Анализ исходного кода |
| Security architecture audit | Анализ `nginx-levik-relay.conf`, `authn.go`, `service.go`, `client.go` | VERIFIED | mTLS на Nginx (порт 8443), HMAC на агенте (127.0.0.1:9088), in-memory replay gap выявлен, сокет `/run/levik-relay/admin.sock` | Анализ конфигураций и кода |
| Xray provisioner check | `grep -rni "xray" levik_whitelist_relay/node-agent/` | VERIFIED | 0 упоминаний Xray в агенте; агент управляет только WDTT | Статический анализ |

## Gate

G00: IN PROGRESS (вклад S00-NODE закрыт со статусом VERIFIED).
- Критерии S00-NODE закрыты: предоставлены точные спецификации private API, сетевых лимитов, source locks, анализ безопасности (mTLS, HMAC, replay cache gap, IPC socket) и зафиксировано отсутствие роли Xray provisioner.
- Изменения кода в `levik_whitelist_relay/` не производились.

## Совместимость и риск

- **Wire protocol & IPC имена**:
  - Имена `levik-relay`, `liblevikrelay.so`, протокол `levik-relay-v1`, заголовки `X-Levik-*`, префиксы меток `lr1-`, seed HMAC `levik-wdtt-credential-v2` и схема `levik-hmac-v1` зафиксированы в коде агента, клиента Android и native library. Их произвольное переименование сломает межкомпонентную совместимость.
- **Ёмкость пула (249 IP)**:
  - При активном подключении пользователей пул `10.66.66.0/24` может быть исчерпан, если темп создания новых сессий превышает темп очистки после удержания (48 часов). Требуется балансировка между несколькими нодами на уровне control plane.
- **In-memory replay cache gap**:
  - При перезапуске агента существует временное окно уязвимости к replay перехваченных запросов в пределах `maxClockSkew` (2 минуты). Требуется учесть в архитектуре control plane (монотонные ревизии и идемпотентность защищают от дубликатов мутаций, но durable replay cache желателен на этапе G01/G03).

## Передача следующему этапу

- **Артефакты и контрольные суммы**:
  - OpenAPI: `levik_whitelist_relay/contracts/openapi.yaml` (`91d446071d389e002f2a01a69fbf43da3e0f6bd889a3cc8216ffeadcc41eabd5`).
  - Node profile schema: `levik_whitelist_relay/contracts/node-profile.schema.json` (`11beaf19f9fa6f9cfaa5f42ea954f83c4db8a3c9cecf0078508356288c63a17c`).
  - Golden HMAC: `levik_whitelist_relay/contracts/golden-hmac-v1.json` (`59b36f92f81d7a8d13432d495f96aa7fbbb976876dfc2ea1cdfd57c69df9ad12`).
  - Tools lock: `levik_whitelist_relay/source/tools.lock` (`b97c561842ca6eb41b5370628e31340a46ac6ebd7cb42c26da8217c4a16156c3`).
  - Upstream lock: `levik_whitelist_relay/source/upstream.lock.json` (`4e375125fd2944e17820fb274aee434ae3b64a95e48b8c6df2392b69e70b2801`).
- **Входные данные для этапа 01 (Contracts and Security)**:
  - Использовать спецификацию `openapi.yaml` для генерации/валидации клиентов control plane.
  - Учесть лимит 249 IP на ноду при проектировании распределения клиентов в control plane.
  - Разработать спецификацию Durable Replay Cache для нод.
  - Спроектировать отдельный механизм провижининга для Xray exit узлов, так как `node-agent` управляет только WDTT.
