# Hello Kitty VPN: статус исполнения

Дата: 2026-10-06 UTC
Координатор: Главный координатор
Текущий этап: 01 — Контракты и безопасность (Contracts and Security Freeze) -> Передача этапу 02

## Общее состояние ворот (Gates)

| Gate | Название этапа | Статус | Доказательства / Блокеры |
|---|---|---|---|
| **G00** | Исходное состояние и передача | **VERIFIED** | Замечания приёмки устранены. Закреплён source baseline [`baseline-source.sha256`](baseline-source.sha256) (372 файла). Исправлены выводы о сервере (проверен firewall: 13 DROP IPv4 / 1 DROP IPv6), сетевой изоляции БД PostgreSQL и топологии VK. Уточнены границы StrongBox fallback в `DeviceIdentity` и отсутствие валидации серверной подписи в `HybridProfileDecryptor`. Доказательства в [`S00-report.md`](S00-report.md), [`S00-android.md`](S00-android.md), [`S00-node.md`](S00-node.md), [`S00-environment.md`](S00-environment.md). |
| **G01** | Контракты и безопасность | **VERIFIED** | **ИСПРАВЛЕНИЯ ПРИНЯТЫ** (2026-10-06 UTC). Устранены все дефекты: схема `profile-v2.schema.json` (purpose: "profile", $defs серверов и протоколов); пересчитаны тестовые векторы `envelope-vectors.json` (profileRevision, валидные UUID, строгий пайплайн из 11 негативных векторов без bypasses, low-S нормализация ECDSA); разделены поля `issuance` и `consumed_by` в SQL refresh tokens; зафиксирован предикат `WHERE lease_status != 'released'` для резервирования IP через tombstones в WDTT; подтверждены `GetInboundUsers` и `GetInboundUsersCount` в коде Xray (`5ca6f4b`); реализован `GoldenVectorsHarnessTest` в Kotlin/JVM (100% pass) и горутинные тесты под детектором гонок Go (`go test -race`). Доказательства: [`S01-fix-report.md`](S01-fix-report.md), [`S01-fix-mobile.md`](S01-fix-mobile.md), [`S01-fix-crypto.md`](S01-fix-crypto.md), [`S01-fix-data.md`](S01-fix-data.md). |
| **G02** | Бэкэнд и control plane | **READY TO RUN** | Входные требования и контракты полностью заморожены в [`contracts-freeze.sha256`](contracts-freeze.sha256). Публичный deploy запрещён. Очередь задач S02-01..09 готова к диспетчеризации. |
| **G03** | Выдача на нодах и reconcile | NOT RUN | Зависит от закрытия G02 |
| **G04** | Интеграция Android с API | NOT RUN | Зависит от закрытия G03 |
| **G05** | Российские маршруты, DNS и IPv6 | NOT RUN | Зависит от закрытия G04 |
| **G06** | VK и белые списки | NOT RUN | Зависит от G05 (lab spike возможен после G01) |
| **G07** | Полевой пилот и надёжность | NOT RUN | Зависит от G05 (и G06 для VK заявлений) |
| **G08** | Релиз и развёртывание | NOT RUN | Зависит от G07 |

## Фактическая база репозитория

- **Upstream HEAD**: `0d05186a3436028f9392e22cbbc30781b0e59129`
- **Рабочее дерево (dirty working tree)**: 197 удалённых путей устаревшего пакета `com.leviknet.vpn`, 17 изменённых файлов сборки и ресурсов, untracked файлы исходников Android и документации (код Android сохранен на 100%).
- **Source baseline**: Исходный эталон контрольных сумм 372 файлов в [`baseline-source.sha256`](baseline-source.sha256). Секреты, артефакты сборки (`build/`, APK) и кэши исключены.
- **Замороженные контракты этапа 01 ([contracts-freeze.sha256](contracts-freeze.sha256))**:
  * `contracts/mobile-v1.openapi.yaml` (SHA256: `fe768ae152df21bb6b7677511cd27f7ea758dbca719d36877caf96c62ea863f1`)
  * `contracts/node-xray-v2.openapi.yaml` (SHA256: `15cf56ebdbdfe03a8ec08d418aa847deb5c5b05eedb56f90c8d46b4ff2a481ea`)
  * `contracts/profile-v2.schema.json` (SHA256: `ceefe91ebaad44c4d4da0ee6ab043a9562785181c69e2be1dacaa0e248afae35`)
  * `contracts/routing-rules-v1.schema.json` (SHA256: `6e87b0865467ea542284d45beb8860c65e387b575feaa4f410fdd8af1cdd3944`)
  * `contracts/security-contract.md` (SHA256: `cb2c5699062bcf184a54ab81a971f82e8c19f4199f0f3dac8095f2f09d7df7d9`)
  * `contracts/signing-vectors.json` (SHA256: `c9055079124a2ec97e978202af6047cfbe7584fe85702d1cf7bd17187f71b06e`)
  * `contracts/envelope-vectors.json` (SHA256: `21be97992f62e09951570408af4c067191bdc0c11213d8b03390a3298cbcd7ff`)
  * `backend/docs/data-model.md` (нормативная спецификация PostgreSQL)
- **Инструменты и toolchains**: Pinned Go 1.26.5 и NDK 29 в `/root/.cache/hellokittyvpn-toolchains/`; OpenJDK 17; Android SDK API 34-36.
- **Среда хоста**: Ubuntu 24.04 LTS (Frankfurt am Main, AS215439 PLAY2GO), Redroid 13 эмулятор на `emulator-5554`.
- **Сетевые параметры**: TCP 443 не занят локальным listener; TCP 5432 на localhost занят сторонним контейнером (закрытая Docker-сеть Hello Kitty снимает конфликт, порт хоста 25432 опционален).

## Очередь задач следующего этапа (Этап 02: Go API, PostgreSQL и выдача устройств)

| Слот / ID | Роль | Задача | Write Path | Статус |
|---|---|---|---|---|
| S02-01 | Главный координатор | Go module bootstrap, config, DTO interfaces | `backend/go.mod`, `backend/go.sum`, `backend/cmd/api/**`, `backend/internal/config/**` | READY TO DISPATCH |
| S02-02 | `store` | Репозитории PostgreSQL (pgx), транзакции, queries, soft/hard cleanup | `backend/internal/store/**`, store tests | Зависит от S02-01 |
| S02-03 | `identity` | Валидация Keystore SPKI, RequestSigner v1, challenge/complete, refresh recovery | `backend/internal/auth/**`, `backend/internal/device/**` | Зависит от S02-01 |
| S02-04 | `profiles` | Каталог серверов, async profile issuance (202 Accepted), Envelope v2 generation | `backend/internal/profiles/**` | Зависит от S02-01 |
| S02-05 | Главный координатор | HTTP роутер, middleware, RFC 7807 problem responses, CLI инвайтов | `backend/internal/httpapi/**`, `backend/cmd/admin/**` | Зависит от S02-02..04 |
| S02-06..08 | `api-security`, `db-integration`, `contract-client` | Наборы тестов безопасности, PostgreSQL интеграции и conformance | `backend/tests/**` | Зависит от S02-05 |
| S02-09 | Главный координатор | Финализация этапа 02, аудит диффов, закрытие Gate G02 | `docs/hellokitty/execution/status.md`, `S02-report.md` | Зависит от S02-06..08 |

## Внешние блокеры и зависимости (не блокируют локальную разработку этапа 02)

1. Доменное имя проекта и публичные TLS-сертификаты (требуются к G03).
2. Зарубежные exit-ноды для регулярного VPN трафика (требуются к G04).
3. Собственные тестовые аккаунты VK и сессии (требуются к G06).
4. Физические устройства и российские SIM-карты операторов для полевых тестов (требуются к G05/G07).
5. Релизный Android keystore для сборки подписанных APK (требуется к G08).
