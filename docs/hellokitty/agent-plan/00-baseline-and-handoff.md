# Этап 00 — проверка базы и запуск команды

Статус: **NOT RUN для следующего исполнителя**. Предыдущая очистка завершена; не повторять её без обнаруженной регрессии. Цель — принять реальную рабочую копию, сохранить чужие изменения и определить исполнимые границы дальнейшего плана.

Вход: [общий порядок](README.md), актуальный user request, repository instructions, [cleanup evidence](../app-cleanup.md). Выход: G00 и рабочий журнал. Следующий этап: [01 — contracts/security](01-contracts-and-security.md).

## Фактическая база

- Workspace `/root/projects/hellokittyvpn`. Upstream HEAD `0d05186a3436028f9392e22cbbc30781b0e59129` **не содержит** всех изменений очистки: они в dirty working tree. Не клонировать HEAD как замену текущим исходникам и не делать reset/clean.
- Android — `levik_vpn_android/`; namespace `org.hellokittyvpn.android`; debug package `.debug`; min26, compile/target36; versionName0.1.0. Kotlin/Compose и Xray AAR закреплены. `levik_whitelist_relay/` — fork/node-agent/contracts/source locks.
- API/DB и новые exit nodes не развёрнуты. Local import работает с native converter; HTTP subscription import не выполняется; relay import до серверной выдачи запрещён. Own signing/release/feed/domain отсутствуют.
- Go1.26.5 и NDK29.0.14206865 установлены отдельно в `/root/.cache/hellokittyvpn-toolchains/`. Системный Go/NDK не заменять. AAR/libs/native outputs игнорируются Git; их наличие и hashes проверить отдельно.
- Сервер используется другими проектами. Исходный аудит RAM/swap/disk/listeners исторический. Не читать чужие credentials/config secrets ради нового проекта.

## Задания субагентам

| ID | Исполнитель | Вход / write ownership | Работа и выход | Зависимость |
|---|---|---|---|---|
| S00-BASE | Главный агент | Repo/status, только `execution/status.md`, `S00-report.md` | Snapshot HEAD + dirty paths/hashes, границы запроса, owners/resource locks | — |
| S00-ANDROID | `android-audit` | Клиент/Gradle/locks read-only; `execution/S00-android.md` | Карта current consumers, исходники/SDK/runtime hashes, список VERIFIED historical vs NOT RUN now | S00-BASE |
| S00-NODE | `node-audit` | Relay/node-agent/contracts read-only; `execution/S00-node.md` | Точные private API/limits/proto/source pins, security gaps и contract consumers | S00-BASE |
| S00-ENV | `environment-audit` | Host metadata read-only; `execution/S00-environment.md` | Listeners/networks/resources/toolchains и список недостающих внешних вводных без secrets | S00-BASE |
| S00-REVIEW | Главный агент | Три отчёта; запись итогового G00 | Согласованные current/target, queue следующей волны и условия внешних действий | Все три audit |

Главный + три audits допускаются параллельно. Audits не меняют приложение, ОС, firewall, nginx, runtime, user data и pins. Каждый пишет свой файл. Общий статус обновляет только главный.

## Порядок исполнения

1. Прочитать актуальные `AGENTS.md`/`CLAUDE.md` и применимые skills, определить действующие инструкции и authorization scope. Если инструкции отменены пользователем, не выдавать старый текст за обязательный; следовать текущему запросу и более высоким правилам.
2. Проверить `git status --short`, `git rev-parse HEAD`, документационный index. Записать baseline список изменений. Source snapshot — только разрешённые source paths и hashes, не архив секретов/всего `/root`.
3. Прочитать current `AppContainer`, `AppRepository`, `AppViewModel`, `HelloKittyVpnService`, `RequestSigner`, `DeviceIdentity`, `HybridProfileDecryptor`, `SecureFileStore`, `XrayConfigBuilder`, `XrayRuntime`; signer/envelope/local import/expiry/server selection — consumers будущего API.
4. Прочитать `levik_whitelist_relay/contracts/openapi.yaml`, agent auth/replay/statefile/lease/WDTT code, source locks и native build scripts. Установить, где реально mTLS, HMAC, nonce cache, expiry и subprocess boundaries. Не предполагать, что node-agent уже provisioner Xray.
5. Инвентаризация без мутаций: OS/tool versions, слушающие порты, подсети, свободные RAM/disk/swap, containers metadata без env/secrets, внешняя доступность только своих согласованных endpoints. Не делать iptables flush/docker prune/service restart. Candidate port — не заявленная доступность с РФ.
6. Проверить наличие локальных build artifacts и сверить pinned SHA256, если они понадобятся. Нельзя mark VERIFIED на основании существования APK. Исторические XML/инструментальные результаты датировать, от новой проверки отделить.
7. Собрать blockers с владельцами: домен/exit/budget/signing material нужны к03/08; VK account к06; physical devices/SIM к04–07. До их получения разрешённые contracts/local code tests продолжаются.
8. В status дать current capabilities и каждому gate NOT RUN. Не заполнять G04/G06 выполненными conversion/unit tests. При необходимости текущего baseline check запускать узко; повторять все тяжёлые проверки лишь при обосновании.

## Команды и ресурсы

Read-only отправные команды: `git status --short`, `git rev-parse HEAD`, `rg --files`, `java -version`, `/opt/android-sdk/platform-tools/adb devices -l`, `ss -lntup`, `free -h`, `df -h`. Логи listeners не публиковать с секретами/ненужными пользовательскими IP. Версии читать сначала из lock/resolved metadata, затем installed metadata, затем constraints.

Android tasks брать из действующего Gradle. Для Direct packaging нужны отдельные GO_BIN/ANDROID_NDK_HOME, [точная команда](../../../README.md). Единственный текущий emulator/ADB target требует serialization. Две Gradle сборки в одном build directory одновременно не запускать; ограничить workers/RAM по текущему свободному ресурсу.

## File manifest

Будущие files: `docs/hellokitty/execution/status.md`, `S00-report.md`, `S00-android.md`, `S00-node.md`, `S00-environment.md`. На этом этапе изменение runtime files не требуется. Не создавать фиктивный `.env` с работающими адресами или secrets.

## Проверки и G00

| Проверка | Какой дефект выявляет | Доказательство |
|---|---|---|
| Baseline HEAD + working hashes | Следующий агент потерял текущую очистку и стартовал с upstream | Список current package files и dirty changes, non-doc source hashes |
| Native pins/toolchains | Случайный latest AAR/Go/NDK вместо согласованной версии | File hash/version against locks, явно unavailable artifacts |
| Node contract trace | Backend вызывает несуществующий Xray API или считает replay durable | Handler/source paths и текущие limitations |
| Resource/port inventory | Конфликт порта/подсети с соседним сервисом | Датированный read-only report; candidate ports отдельно |
| Authorization scope | Docs task принят за разрешение публичного deploy | В отчёте отдельно code, local tests, staging runtime, public mutations |

G00 закрыт, когда главный прочитал audit outputs, согласовал current gaps, закрепил source baseline и owners, создал очередь01 с понятными prerequisites. Отсутствие домена/SIM не препятствует G00 при явном blocker record. Секреты и cookies в отчёте — failure приёмки.

Rollback этого этапа — исправить собственный ошибочный отчёт/документ; инфраструктурных изменений быть не должно. Handoff01 содержит path+hash source baseline, current dependency versions, crypto/network gaps, known missing inputs и список разрешённых действий.
