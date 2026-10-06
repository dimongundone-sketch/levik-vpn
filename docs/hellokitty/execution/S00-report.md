# S00 / Baseline and Handoff — итоговый отчёт координатора

Дата UTC: 2026-10-06 (обновлено после устранения замечаний приёмки G00)
Главный агент / исполнитель: Главный координатор
Source baseline HEAD: `0d05186a3436028f9392e22cbbc30781b0e59129`
Working snapshot: dirty working tree (197 удалённых путей legacy com.leviknet, 17 изменённых файлов, 147 новых untracked файлов).
Source baseline manifest: [`baseline-source.sha256`](baseline-source.sha256) (372 файла, SHA256 каждого файла зафиксирован).
Scope / разрешённые paths: `docs/hellokitty/execution/`
Prerequisites / gate evidence: Приёмка документов планирования, устранение замечаний G00, фиксация source baseline, параллельные аудиты S00-ANDROID, S00-NODE, S00-ENV.

## Что изменено

1. **Фиксация настоящего Source Baseline**:
   - Создан эталонный файл контрольных сумм [`docs/hellokitty/execution/baseline-source.sha256`](baseline-source.sha256), охватывающий ровно 372 актуальных исходных и конфигурационных файла:
     - 225 сохранённых отслеживаемых файлов из HEAD;
     - 147 новых файлов (124 файла очищенного Android-клиента в пакете `org.hellokittyvpn.android`, 23 файла документации `docs/hellokitty/`);
     - 197 файлов устаревшего пакета `com.leviknet.vpn` и неиспользуемых ресурсов удалены из рабочей копии;
     - 17 отслеживаемых файлов скорректированы (скрипты сборки Gradle, lock-файлы, verification metadata, стили/ресурсы).
   - Из снимка строго исключены артефакты сборки (`build/`, `*.apk`, `*.so`), временные кэши (`.gradle/`, `.cache/`) и любые секреты/ключи (`.env`, `*.key`, `*.jks`, `*.pem`).
   - Чексумма каждого файла проверена через `sha256sum -c baseline-source.sha256` (100% совпадение).
2. **Исправление выводов аудита сервера**:
   - `ip addr` и `ip route` показывают локальную конфигурацию интерфейсов и шлюзов, но не подтверждают внешнюю геолокацию или ASN без внешних запросов к базам BGP/GeoIP.
   - Отсутствие listener на TCP 443 свидетельствует лишь о незанятости локального порта, но не доказывает внешнюю сетевую доступность со стороны сети Internet или провайдера.
   - `ss` не проверяет правила сетевого экрана (firewall). 2026-10-06 21:16 UTC проведена прямая read-only проверка firewall: обнаружено 13 правил DROP в IPv4 filter rules (изоляция Docker bridge подсетей и ограничение порта 6768 интерфейсом `tailscale0`) и 1 правило DROP в IPv6. Данные DROP правила не относятся к порту 443, однако подтверждают необходимость проверки правил фаервола, а не только сокетов.
3. **Исправление топологии VK-транспорта**:
   - Устранено ошибочное требование размещения собственного exit-узла на территории РФ.
   - Доступный первый узел (VK/TURN relay) и собственный exit-узел — это раздельные архитектурные компоненты. Географическое размещение сервера само по себе не доказывает работоспособность или невозможность транспорта.
4. **Исправление требований к порту PostgreSQL**:
   - Использование изолированной контейнерной сети Docker без публикации порта БД на хост полностью исключает конфликт со сторонним сервисом на `127.0.0.1:5432`. Порт хоста `25432` опционален, а не обязателен.
5. **Корректировка гарантий Android-аудита**:
   - В `DeviceIdentity` подтверждено наличие программного fallback при недоступности StrongBox (TEE/software Keystore), а также fallback с `MODERN` (PS256) на `LEGACY` (RS256).
   - В `HybridProfileDecryptor` зафиксировано, что компонент выполняет исключительно расшифровку (RSA-OAEP + AES-GCM), но **не проверяет цифровую подпись сервера** (`signature`) и не проверяет семантическую привязку профиля (`deviceId`, `accessId`, `credentialExpiresAt`, anti-rollback). Эти гарантии перенесены как требования к этапу 01.
6. **Синхронизация очереди этапа 01**:
   - Очередь возвращена к согласованной основе публичного API `/v1` (`/v1/devices/challenges`, `/v1/devices/complete`, `/v1/tokens/refresh`, `/v1/servers`, `/v1/profiles` и др.), устранены неподтверждённые пути `/api/v2/devices/*`.
   - Зафиксировано строгое разделение протоколов и версий:
     - Публичный мобильный API: `/v1`
     - Каноническая подпись запросов: `v1` (`RequestSigner`)
     - Защищённый конверт профиля: `v2` (`HKVPN-PROFILE-V2`)
     - Новый внутренний API нод Xray: `/internal/v2/xray/*`
     - Существующий API нод WDTT: `/internal/v1/leases/*`
     - Версия спецификаций: OpenAPI 3.1.0

## Задачи и владельцы

| Task ID | Owner | Write paths | Dependencies | Status | Evidence |
|---|---|---|---|---|---|
| S00-BASE | Главный координатор | `docs/hellokitty/execution/status.md`, `S00-report.md`, `baseline-source.sha256` | — | VERIFIED | `baseline-source.sha256` на 372 файла создан, 197 deleted, 17 modified, 147 untracked зафиксированы |
| S00-ANDROID | `android-audit` | `docs/hellokitty/execution/S00-android.md` | S00-BASE | VERIFIED | Инвентаризация 10 consumers, уточнены границы StrongBox fallback и отсутствие валидации подписи в HybridProfileDecryptor |
| S00-NODE | `node-audit` | `docs/hellokitty/execution/S00-node.md` | S00-BASE | VERIFIED | OpenAPI 3.1.0, лимит 249 IP, 24ч аренда, 48ч retention grace, mTLS на Nginx, in-memory replay gap зафиксирован |
| S00-ENV | `environment-audit` | `docs/hellokitty/execution/S00-environment.md` | S00-BASE | VERIFIED | Инвентаризация хоста: 13 DROP в IPv4/1 в IPv6 зафиксированы; уточнены требования к PostgreSQL порту и VK exit |
| S00-REVIEW | Главный координатор | `docs/hellokitty/execution/status.md`, `S00-report.md` | Все 3 аудита | VERIFIED | Замечания G00 устранены, source baseline утверждён, очередь этапа 01 синхронизирована |

## Проверки

| Check | Command / environment | Status | Actual result / evidence | Limits |
|---|---|---|---|---|
| Source baseline verification | `sha256sum -c docs/hellokitty/execution/baseline-source.sha256` | VERIFIED | 372 файла прошли проверку (100% match) | Полный снимок исходников и доков |
| Baseline HEAD & dirty state | `git rev-parse HEAD && git status --short` | VERIFIED | HEAD `0d05186a...`, 197 deleted, 17 modified, 147 untracked сохранены | Рабочая копия защищена |
| Pinned toolchains presence | `ls -la /root/.cache/hellokittyvpn-toolchains/` | VERIFIED | `go-1.26.5` (269M) и `ndk/29.0.14206865` (2.4G) на месте | Системные toolchains не затронуты |
| Historical debug APKs | `sha256sum app-direct-debug.apk app-play-debug.apk` | VERIFIED | Direct `276cfd4f...`, Play `8c228252...` побайтово соответствуют `app-cleanup.md` | Исторические артефакты очистки |
| Host resources & ports | `free -h && df -h && ss -lntup` | VERIFIED | RAM 15G (6.1G avail), Swap 2.5G (91% used), Disk 99G (15G avail). TCP 443 свободен; TCP 5432 занят popcorn; UDP 56000 занят | Хост разделяемый, требует осторожности |
| Firewall DROP rules audit | `iptables -S \| grep DROP; ip6tables -S \| grep DROP` | VERIFIED | 2026-10-06 21:16 UTC: 13 правил DROP в IPv4, 1 в IPv6. Относятся к Docker bridge и Tailscale0. Порт 443 не блокируют | ss не проверяет firewall; правила зафиксированы |
| Relay contracts & limits | Чтение `contracts/openapi.yaml`, code audit | VERIFIED | OpenAPI 3.1.0, pool 10.66.66.0/24 (249 IP), 24h lease, 48h retention grace | Read-only |
| Node-agent role | Исходный код `node-agent` | VERIFIED | Node-agent управляет только WDTT, НЕ является provisioner Xray | Подтверждено кодом |
| Client consumers audit | Чтение `AppContainer`, `AppRepository`, `VpnService` | VERIFIED | 10 потребителей описаны; dormant API/Keystore клиенты зафиксированы | Read-only |
| Emulator / ADB target | `/opt/android-sdk/platform-tools/adb devices -l` | VERIFIED | `emulator-5554` (Redroid Android 13, API 33, x86_64) доступен | Требует строгой сериализации тестов |
| Absence of secrets in reports | `grep -Ei "password\|secret\|token\|private" S00-*.md` | VERIFIED | Конфиденциальные данные, токены, ключи и cookies пользователей отсутствуют | Защита данных соблюдена |

## Gate

**G00: VERIFIED**
- Все замечания к приёмке G00 устранены:
  1. Создан и проверен полный эталонный снимок исходников `docs/hellokitty/execution/baseline-source.sha256` (372 файла).
  2. Исправлены выводы об окружении: проверен firewall (13 DROP в IPv4, 1 в IPv6), устранено ложное доказательство доступности TCP 443 через `ss`, сняты неверные привязки ASN/геолокации к локальному `ip route`.
  3. Снято требование размещения собственного exit-узла VK в РФ.
  4. Снято жесткое требование порта PostgreSQL 25432 (закрытая Docker-сеть решает конфликт с localhost:5432).
  5. Скорректированы гарантии Android: зафиксирован StrongBox fallback в `DeviceIdentity` и отсутствие валидации серверной подписи/семантики в `HybridProfileDecryptor`.
  6. Очередь этапа 01 приведена в строгое соответствие с архитектурным планом (публичный API `/v1`, строгое разделение версий протоколов).
- Ворота G00 закрыты.

## Совместимость и риск

1. **Разделяемый хост и конфликты портов**:
   - Локальный порт PostgreSQL `5432` занят сторонним проектом на `127.0.0.1`. Контейнеризованная сеть для Hello Kitty снимает проблему; публикация на порт хоста (например, `25432`) опциональна.
   - Порт UDP `56000` занят сторонним `wdtt-server`.
   - TCP 443 свободен от локальных listener, но требует проверки внешнего сетевого экрана при публичном развёртывании.
2. **Лимиты ресурсов**:
   - Свободно 15 GiB диска и 6.1 GiB RAM, swap заполнен на 91%. Сборки Gradle и Go обязаны запускаться последовательно с лимитами воркеров и памяти.
3. **Безопасность Node Replay**:
   - В текущей реализации `levik-relay-agent` хранит `ReplayCache` исключительно в RAM. Требуется спроектировать durable replay механизм.
4. **Ограничения ёмкости WDTT Relay**:
   - Лимит пула: 249 IP-адресов. Удержание истекших записей в течение 48 часов (`retentionGrace`) удерживает IP в пуле.
5. **Разделение Xray и Relay**:
   - `node-agent` управляет только WDTT. Для Xray exit узлов проектируется независимый API v2 (`/internal/v2/xray/*`).

## Передача следующему этапу (Handoff to Stage 01)

### Готовность к этапу 01 (Contracts and Security Freeze)
- **Цель этапа 01**: согласование OpenAPI спецификаций, JSON-схем, криптографических векторов и модели данных до реализации бэкенда.
- **Входные артефакты**:
  - Source baseline: [`baseline-source.sha256`](baseline-source.sha256).
  - Клиент: [`RequestSigner.kt`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/network/RequestSigner.kt), [`DeviceIdentity.kt`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/DeviceIdentity.kt), [`HybridProfileDecryptor.kt`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/HybridProfileDecryptor.kt).
  - Релей: [`contracts/openapi.yaml`](file:///root/projects/hellokittyvpn/levik_whitelist_relay/contracts/openapi.yaml).

### Задачи этапа 01 и разделение владельцев файлов (3 параллельных субагента):

1. **Субагент A — S01-MOBILE** (роль: `contracts`):
   - Разрешённые файлы:
     - `contracts/mobile-v1.openapi.yaml`
     - `contracts/profile-v2.schema.json`
     - `contracts/routing-rules-v1.schema.json`
     - `docs/hellokitty/execution/S01-mobile.md`
   - Результат: Полные спецификации OpenAPI 3.1.0 для публичного mobile API `/v1` (`/devices/challenges`, `/devices/complete`, `/tokens/refresh`, `/servers`, `/profiles`, `/operations/{id}`, `/profiles/{id}`, `/credentials/{id}/renew`, `DELETE /devices/me`, `/routing-rules/manifest`), JSON-схемы для envelope v2 и routing rules v1, ограничения размеров, enum vocabulary, RFC 7807 problem errors, примеры.

2. **Субагент B — S01-CRYPTO** (роль: `crypto-security`):
   - Разрешённые файлы:
     - `contracts/signing-vectors.json`
     - `contracts/envelope-vectors.json`
     - `contracts/security-contract.md`
     - `contracts/probes/crypto/**`
     - `docs/hellokitty/execution/S01-crypto.md`
   - Результат: Модель угроз, криптографический контракт, golden тестовые векторы подписи (канонический RequestSigner v1: PS256/RS256) и шифрования конвертов (HKVPN-PROFILE-V2: RSA-OAEP + AES-256-GCM + ECDSA P-256 подпись), исполняемые compatibility probes, модель восстановления потерянного ответа refresh (120с окно, Idempotency-Key = clientOperationId, encrypted response cache).

3. **Субагент C — S01-DATA** (роль: `storage-design`):
   - Разрешённые файлы:
     - `backend/docs/data-model.md`
     - `contracts/node-xray-v2.openapi.yaml`
     - `contracts/probes/node/**`
     - `docs/hellokitty/execution/S01-data.md`
   - Результат: Модель сущностей БД PostgreSQL (devices, grants, challenges, token_families, nonces, nodes, credentials, leases, profiles, operations, outbox), транзакционные границы, ограничения уникальности, token rotation semantics, outbox pattern, isolated spike по Xray proto/APIs с фиксацией observed state evidence, спецификация `/internal/v2/xray/credentials/{apply,revoke,status}`.

4. **Координатор — S01-REVIEW**:
   - Разрешённые файлы:
     - `docs/hellokitty/execution/status.md`
     - `docs/hellokitty/execution/S01-report.md`
     - общий freeze record с SHA256 контрактов.
