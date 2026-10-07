# Hello Kitty VPN: статус исполнения

Дата: 2026-10-07 UTC
Координатор: Главный координатор
Текущий этап: 01 — Контракты и безопасность (Contracts and Security Freeze) -> ЗАВЕРШЕН (VERIFIED). Переход к этапу 02 (Control Plane).

## Общее состояние ворот (Gates)

| Gate | Название этапа | Статус | Доказательства / Блокеры |
|---|---|---|---|
| **G00** | Исходное состояние и передача | **VERIFIED** | Замечания приёмки устранены. Закреплён source baseline [`baseline-source.sha256`](baseline-source.sha256) (372 файла). Исправлены выводы о сервере (проверен firewall: 13 DROP IPv4 / 1 DROP IPv6), сетевой изоляции БД PostgreSQL и топологии VK. Уточнены границы StrongBox fallback в `DeviceIdentity` и отсутствие валидации серверной подписи в `HybridProfileDecryptor`. Доказательства в [`S00-report.md`](S00-report.md), [`S00-android.md`](S00-android.md), [`S00-node.md`](S00-node.md), [`S00-environment.md`](S00-environment.md). |
| **G01** | Контракты и безопасность | **VERIFIED** | **ПОВТОРНАЯ ПРИЁМКА УСПЕШНО ЗАВЕРШЕНА** (2026-10-07 UTC). Полностью устранены замечания F01–F10 независимого ревью: reauth сохраняет grant/expiry и не воскрешает отозванный доступ (F01); retention operations не удаляет действующие refresh-токены благодаря `ON DELETE SET NULL` (F02); CAS/finalization переходит в ready только при валидном evidence и generation (F03); однократная предкоммитная запись recovery cache с привязкой к `operations.id` (F04); сохранение `request_body_sha256` и `UNIQUE(device_id, client_operation_id)` в операциях (F05); шифрование VLESS UUID AES-256-GCM at rest и маскирование outbox (F06); полный verifier pipeline для всех 2 позитивных и 11 негативных векторов в Kotlin (F07); подписанный `HKVPN-CHALLENGE-V1` 9-строчный wire contract (F08); унификация HTTP `410 REFRESH_RETRY_EXPIRED` (F09); валидация подлинного profile envelope в OpenAPI examples (F10). Все кросс-языковые тесты (Python, Go `-race`, Kotlin Play/Direct, PostgreSQL 17) пройдены со 100% успехом. Доказательства: [`S01-review-fix-report.md`](S01-review-fix-report.md). |
| **G02** | Бэкэнд и control plane | **READY** | Зависимости этапа G01 закрыты. Все 8 контрактов заморожены в [`contracts-freeze.sha256`](contracts-freeze.sha256). DDL и транзакционная модель проверены на PostgreSQL 17. Готов к старту согласно [`STAGE_02_AGENT_PROMPT.md`](../agent-plan/STAGE_02_AGENT_PROMPT.md). |
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
  * `contracts/mobile-v1.openapi.yaml` (SHA256: `0d23a959f27f956e7738d6e31e0f8fc0bd74e477f47960bd80c37d4d64e3bb0e`)
  * `contracts/node-xray-v2.openapi.yaml` (SHA256: `15cf56ebdbdfe03a8ec08d418aa847deb5c5b05eedb56f90c8d46b4ff2a481ea`)
  * `contracts/profile-v2.schema.json` (SHA256: `ceefe91ebaad44c4d4da0ee6ab043a9562785181c69e2be1dacaa0e248afae35`)
  * `contracts/routing-rules-v1.schema.json` (SHA256: `6e87b0865467ea542284d45beb8860c65e387b575feaa4f410fdd8af1cdd3944`)
  * `contracts/security-contract.md` (SHA256: `214b19bb7f88964059f76b4ea13bc8a9c6a681c0169abebe60d8cd0394588303`)
  * `contracts/signing-vectors.json` (SHA256: `c9055079124a2ec97e978202af6047cfbe7584fe85702d1cf7bd17187f71b06e`)
  * `contracts/envelope-vectors.json` (SHA256: `21be97992f62e09951570408af4c067191bdc0c11213d8b03390a3298cbcd7ff`)
  * `contracts/challenge-vectors.json` (SHA256: `4036fc555adfc2650bf677a67f1c2a4a530c53ed6bc39ce5f249400ca001154d`)
  * `backend/docs/data-model.md` (SHA256: `016a0b1c4a1b03b697783e0df8f2c51f521ea129c67441c822f19dc99d1d885c`)
- **Инструменты и toolchains**: Pinned Go 1.26.5 и NDK 29 в `/root/.cache/hellokittyvpn-toolchains/`; OpenJDK 17; Android SDK API 34-36; PostgreSQL 17.
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
