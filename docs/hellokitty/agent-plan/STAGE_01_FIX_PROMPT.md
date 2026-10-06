# Hello Kitty VPN: промпт исправления этапа 01

Дата: 2026-10-06 UTC. Подготовлен после независимой проверки `STAGE_01_COMPLETION_REPORT.md`. Этот документ задаёт исправления и повторную приёмку; их выполнение данным файлом не подтверждается.

Передай следующему главному агенту весь текст ниже.

## Задание агенту

Работай в `/root/projects/hellokittyvpn` над Hello Kitty VPN. Твоя задача — исправить все перечисленные недостатки контрактов, тестовых моделей и отчётов этапа 01, выполнить проверку исправлений и подготовить достоверную передачу этапу 02.

Сначала исправь приёмку G01. Полный backend, SQL migrations, production provisioner, интеграция приложения с API и deployment относятся к последующим этапам. В этом задании нужны согласованные контракты и исполняемые проверки потребителей и моделей состояния.

### 1. Прими рабочую копию и переоткрой приёмку

1. Прочитай действующие user instructions и применимые `AGENTS.md`/`CLAUDE.md`, затем:
   - `docs/hellokitty/README.md`;
   - `docs/hellokitty/agent-plan/README.md`;
   - `docs/hellokitty/agent-plan/00-baseline-and-handoff.md`;
   - `docs/hellokitty/agent-plan/01-contracts-and-security.md`;
   - `docs/hellokitty/agent-plan/02-backend-control-plane.md`;
   - `docs/hellokitty/agent-plan/REPORT_TEMPLATE.md`;
   - `docs/hellokitty/implementation-plan.md` и `app-cleanup.md`;
   - `docs/hellokitty/STAGE_01_COMPLETION_REPORT.md`;
   - `docs/hellokitty/execution/status.md`, `S00-report.md`, `S01-report.md` и отчёты трёх исполнителей S01;
   - все созданные `contracts/**`, `backend/docs/data-model.md` и реальные Android consumers.
2. Проверь `git status`, HEAD, source/dependency locks и существующие изменения. Очистка Android и контрактные файлы уже находятся в рабочем дереве. Сохрани их; reset, clean, замена checkout и автоматический commit исключены.
3. Отметь G01 как `IN PROGRESS`: предыдущий `VERIFIED` не прошёл независимую приёмку. Для G02 оставь `NOT RUN` и явно укажи зависимость от повторной приёмки G01. Публичный deploy этим заданием не разрешается.
4. Зафиксируй дату пересмотра и причины. Прежние выполненные тесты остаются историческими результатами конкретных моделей; они не подтверждают отсутствующие проверки.
5. Сохрани совместимость `/v1`, RequestSigner v1, profile envelope v2 и отдельных private node API. Номер profile/IPC/OpenAPI не меняет версию публичных HTTP endpoints.

### 2. Распредели работу между субагентами

Максимум четыре активных слота: координатор и три исполнителя. Каждый файл имеет одного writer. Координатор интегрирует результаты, назначает общие интерфейсы, проверяет независимые доказательства и ведёт общий журнал.

| Task ID | Исполнитель | Разрешённая запись | Ответственность |
|---|---|---|---|
| S01-FIX-MOBILE | `contract-conformance` | `contracts/mobile-v1.openapi.yaml`, `contracts/profile-v2.schema.json`, `contracts/routing-rules-v1.schema.json`, `contracts/probes/conformance/**`, `docs/hellokitty/execution/S01-fix-mobile.md` | Согласование schemas/examples и сквозная проверка криптовекторов по схемам |
| S01-FIX-CRYPTO | `crypto-state` | `contracts/security-contract.md`, `contracts/signing-vectors.json`, `contracts/envelope-vectors.json`, `contracts/probes/crypto/**`, `docs/hellokitty/execution/S01-fix-crypto.md` | Исправление auth/refresh reference models, Kotlin/Go совместимость, реальные проверки конкуренции |
| S01-FIX-DATA | `storage-node-evidence` | `backend/docs/data-model.md`, `contracts/node-xray-v2.openapi.yaml`, `contracts/probes/node/**`, `docs/hellokitty/execution/S01-fix-data.md` | Транзакционные алгоритмы, удержание IP, фактический Xray proto и evidence semantics |
| S01-FIX-REVIEW | Координатор | Общие reports/indexes/plans, baseline manifests, freeze record, `docs/hellokitty/execution/S01-fix-report.md` | Приёмка, исправление отчётов, пересчёт хешей и передача02 |

До правок согласуй между A/B точный профиль и между B/C точные transitions refresh. Если B нужны тесты в Android test source set, координатор выделяет ему конкретный новый тестовый файл после проверки отсутствия чужих изменений; это расширение ownership записывается в журнал. Production Kotlin-классы сначала используются как потребители read-only.

Общие Gradle/ADB ресурсы сериализуются. Субагент не получает весь `contracts/`, если другие пишут внутри него. В первой волне допустимы параллельные воспроизведения; финальная conformance-проверка запускается после согласования и интеграции всех writers.

### 3. Исправь несовместимость профиля и криптовекторов

**Подтверждённые дефекты:**

- `contracts/profile-v2.schema.json`, `TunnelProfileProtectedHeader.purpose` требует `"profile-v2"`;
- `contracts/security-contract.md` и crypto probes используют `"profile"`;
- оба positive envelope vectors содержат plaintext без обязательного `profileRevision`;
- в них `engine="Xray"`, а внешний schema enum — `"xray"`/`"relay"`;
- их серверы имеют плоские legacy-поля вместо полей `TunnelServerV2`;
- `accessId` и `profileId` в векторах не соответствуют формату UUID, объявленному схемой.

Воспроизведи ошибку: валидируй отдельно envelope, декодированные protected metadata и **фактически расшифрованный payload** обоих positive vectors по соответствующим `$defs`, с проверкой `format`. Простого сравнения plaintext с самим же ожидаемым fixture недостаточно.

Согласованная основа — `purpose="profile"` из действующего security contract. Приведи schema, metadata, vectors и consumers к одному значению. Если иной выбор необходим, оформи решение и обнови все потребители одновременно. Не расширяй схему до принятия двух несовместимых форматов только ради зелёных тестов.

Оставь внешний profile v2 самостоятельным контрактом. Legacy native vocabulary используется за явным mapper boundary. Внешний payload должен содержать необходимые revision/IDs/engine/server fields; профиль из криптовектора обязан приниматься будущим соответствующим schema consumer.

После изменения metadata/payload заново сформируй wrapped key, ciphertext, AAD и серверную подпись. Используй только явно обозначенные тестовые ключи. Не редактируй signed plaintext без пересоздания криптографического fixture.

Добавь исполняемую conformance-проверку всех positive vectors и OpenAPI examples, которая разрешает локальные `$ref` и возвращает ненулевой exit code при расхождении компонентов. Проверь strict unknown/duplicate critical fields, ограничения размеров, owner binding и равенство metadata/payload. Формат signed bytes и алгоритмы берутся из согласованного security contract.

Для negative vectors используй реальный путь верификации. Решение об отказе и код ошибки не должны браться из `type` или `expectedError` самого fixture. Подпись проверяется до расшифровки и активации; произвольная неверная подпись всегда отклоняется. Для проверки семантической ошибки за подписью подготовь корректно подписанный тестовый envelope с этой ошибкой. Не обходи signature check ради достижимости ветки AES-GCM.

Дополнительно синхронизируй требование ECDSA low-S: security contract сейчас требует нормализацию, а verifier использует обычную проверку DER. Если low-S сохраняется как обязательный контракт, проверь producer и отказ для high-S; если требование снимается, оформи согласованное изменение. Используй стандартные криптобиблиотеки и точные ASN.1 DER/algorithm правила.

### 4. Исправь SQL-модель потерянного refresh response

**Подтверждённый дефект:** `bound_operation_id`/`bound_body_hash` описаны как привязка к операции выпуска токена. В refresh flow использованный старый токен получает только `status='consumed'` и `consumed_at`. Retry сравнивается с прежней enrollment/issuance operation и ошибочно классифицируется как token reuse.

Сначала воспроизведи цепочку:

1. Enrollment выпускает R0, связанный с E0.
2. Refresh F1 использует R0 и выпускает R1.
3. Ответ F1 теряется.
4. Тот же device повторяет F1 с новым request nonce/signature, прежним operationId и идентичным raw body.
5. Описанный старый SQL сравнивает F1 с E0 и отзывает family.

Исправь модель и алгоритм: consumed token атомарно получает привязку к **операции, которая его использовала**, и её raw body hash. Раздели issuance и consumption bindings, если нужны оба. Не оставляй двусмысленную семантику колонок.

В одной транзакции фиксируются consumption binding старого токена, единственный successor, access token и logical recovery record. Долгосрочный operation/body binding сохраняется до соответствующей token/family retention, независимо от 120s ciphertext cache и 48h idempotency TTL.

Перед cache return проверяются device ownership, состояние device/grant/family и их expiry. В текущем SQL consumed/cache ветка стоит раньше этих проверок. Кэш не восстанавливает отозванное право доступа и не продлевает абсолютную жизнь family/grant.

Зафиксируй обязательные outcomes:

- точный retry в 120s — тот же ответ, без второй rotation и family revoke;
- тот же bound operationId с изменённым body — 409 без побочных действий;
- использованный токен с новым operationId — family revoke;
- поздний exact retry — `REFRESH_RETRY_EXPIRED`, без ложного reuse;
- reauth разрешён только известному device с действующим grant;
- после reauth предыдущие families остаются отозванными;
- grant/family/token expiry ограничивают каждый выдаваемый токен;
- enrollment/reauth complete тоже поддерживает exact lost-response recovery, без второго расхода invitation и повторного отзыва только что выданной family.

Согласуй FK/cache/internal operation IDs с wire `clientOperationId`: это разные идентификаторы, если схема задаёт их отдельно. Убедись, что необходимые operation records действительно предусмотрены для refresh и complete, а не только для provisioning. Проверь namespace идемпотентных операций и состояние после очистки кэша.

До появления backend02 допустим исполняемый state probe. Он должен точно воспроизводить исправленные транзакционные переходы и constraints; отличия от SQL указываются явно. Реальную HTTP/PostgreSQL integration не объявляй выполненной по результатам такой модели.

### 5. Исправь дефекты reference models auth/refresh

**Дефект ownership:** `handle_refresh` проверяет существование вызывающего device, но не сопоставляет его с владельцем refresh token/family. В существующей Python-модели устройство B обновляет токен устройства A с ответом 200.

Добавь regression: зарегистрируй два разных device, передай refresh A от имени B с корректной формой request. Требуется отказ без выдачи токенов и без изменения family A. Обязательны binding token → family → device → действующий grant и соответствующее соглашение с RequestSigner proof.

**Дефект reauth:** Python family ID зависит от первых восьми символов deviceId и секунды; Go использует постоянный ID на основе префикса. Повторный enroll внутри reauth перезаписывает отозванную family как активную. Воспроизведено принятие старого refresh после reauth в той же секунде.

Используй уникальный идентификатор каждой новой family. Старые записи не перезаписываются и не оживают. Добавь случаи:

- reauth в ту же секунду;
- два device с совпадающими первыми восемью символами fingerprint;
- старый активный и старый consumed token после reauth;
- отзыв/истечение grant при активном device;
- отзыв family/grant/device перед exact cached retry;
- token expiry/family absolute expiry и cleanup recovery cache.

Существующие probes не моделируют отдельное состояние grant, а отчёт заявляет проверку `GRANT_REVOKED`. Введи grant state/expiry в модель и реальные тесты reauth/refresh против него либо оставь этот пункт приёмки незакрытым. Проверка revoked device не заменяет проверку revoked grant.

Разделяй логическую in-memory модель recovery cache и реальное шифрование хранения. Если payload модели хранится как dict/map, отчёт не должен называть это выполненным encrypted cache. At-rest реализация принадлежит backend02, её контракт и key binding должны быть определены сейчас.

### 6. Исправь резервирование WDTT IP

**Подтверждённый дефект:** индекс `UNIQUE(node_id, ip_address) WHERE lease_status != 'tombstone'` исключает удерживаемый адрес из уникальности. Активная аренда другого устройства может получить тот же IP до `purge_after`.

Воспроизведи случай: для одной node создать tombstone на `10.66.66.2` с будущим `purge_after`, затем попытаться выдать этот адрес другому device. Прежний предикат разрешает вставку; исправленная модель должна её запретить.

Определи явный инвариант резервирования: active, expired, revoked и удерживаемые tombstones занимают слот до освобождения. Выбери согласованную схему — например, уникальную reservation до физического удаления либо явный released state. Нельзя завязывать partial index на текущее время или исключать все tombstones заранее.

Проверь:

- `active + retained <= 249`;
- срок аренды не превышает действующий WDTT cap 24h;
- IP сохраняется при expiry/revoke до согласованного purge_after;
- повторная выдача разрешается после фактического освобождения;
- restore/restart не освобождает reservations раньше времени;
- две конкурирующие попытки взять последний слот не создают двух владельцев.

Модель allocator и спецификация SQL должны выражать один инвариант. SQLite-проверка предиката допускается как локальное воспроизведение, но не считается PostgreSQL integration. Реальные migrations и DB concurrency tests остаются задачами02/03.

В ходе сверки data model также проверь исполнимость предложенной UUIDv7-функции: объявление времени, операции extract и необходимые расширения должны соответствовать выбранному PostgreSQL. Не вводи новую extension dependency без обоснования и фиксации.

### 7. Замени ошибочные выводы Xray реальным source/proto spike

**Подтверждённая ошибка:** отсутствие RPC с буквальным именем `ListUsers` выдано за отсутствие чтения пользователей. В commit `5ca6f4b7d4dc20a881d4330e498892697627ec0c` есть `GetInboundUsers` и `GetInboundUsersCount`; они присутствуют и в указанном node tag `v26.3.27`.

Первичные источники, проверенные при независимом review:

- [Pinned command.proto](https://raw.githubusercontent.com/XTLS/Xray-core/5ca6f4b7d4dc20a881d4330e498892697627ec0c/app/proxyman/command/command.proto);
- [Pinned command.go](https://raw.githubusercontent.com/XTLS/Xray-core/5ca6f4b7d4dc20a881d4330e498892697627ec0c/app/proxyman/command/command.go);
- [Node tag command.proto](https://raw.githubusercontent.com/XTLS/Xray-core/v26.3.27/app/proxyman/command/command.proto).

`GetInboundUsers` использует `proxy.UserManager`; поддержка выбранным inbound должна проверяться по его реализации. Наличие RPC не означает поддержку любым протоколом.

Исправь `contracts/probes/node/README.md`, модель node evidence, private API description, data model и все отчёты, где заявлена невозможность read-back. Старый список RPC в Python simulator не является доказательством содержимого upstream proto.

Выполни небольшой isolated spike по фактическому закреплённому source/proto: фиксируй repository, commit, source hashes, реальные сообщения/RPC и capability выбранного inbound. Не используй mutable master или другую версию без решения. Если нужен временный локальный core для проверки, используй только собственный изолированный процесс и проверенные свободные loopback endpoints; соседние Xray/WDTT не являются fixture.

Для accepted observed evidence определи проверку фактического credential в нужном inbound, UUID/email mapping, core instance/generation, revision и freshness. Успешный `AlterInbound` и локальный desired journal сами по себе не означают долговременное применение. Обработай core restart независимо от рестарта node-agent и устаревшие acknowledgements.

Сохрани честное ограничение: удаление учётной записи нельзя выдавать за гарантированное закрытие всех уже установленных TCP/UDP сессий. Проанализируй фактический выбранный inbound. Production expiry enforcement/restart/fault integration относятся03.

Python simulator можно оставить как тест проектируемой модели, с соответствующим названием и ограничениями. Его 8 PASS не называй выполненными Xray RPC или crash-safe filesystem tests. На G01 требуется подтверждённый интерфейс и evidence design; полноценный production adapter заранее не требуется.

### 8. Выполни недостающие проверки и исправь их описание

1. **Kotlin/Go compatibility.** Текущий `run_all_probes.sh` запускает Python и Go. Добавь реальный Kotlin/JVM или Android harness, использующий фактический `RequestSigner`/потребителя с согласованными vectors. Проверь canonical bytes, PS256/RS256 и envelope interoperability. Новая копия алгоритма с теми же assumptions не заменяет проверку клиента. Запиши какие platform/Keystore capabilities действительно проверены; недоступные API35/StrongBox проверки не приписывай API33 эмулятору.
2. **Concurrent refresh.** Цикл из пяти последовательных вызовов не проверяет гонку. Добавь overlapping requests с barrier/latch и несколькими goroutines/threads. Проверь единственную rotation/successor и одинаковый результат exact retries. Для Go выполни подходящий `go test -race` изолированного probe module. Race-free reference model не подтверждает DB transaction implementation02.
3. **Revoked grant.** Добавь отдельное моделирование и тесты grant revocation/expiry. Существующий отчёт о `GRANT_REVOKED` без соответствующего теста исправь.
4. **Conformance.** Проверяй одну согласованную цепочку: signed metadata → server signature → key unwrap → decrypted payload → schema → semantic bindings. Positive vectors должны проходить всю цепочку; negative vectors — падать на проверяемом дефекте.
5. **Схемы и примеры.** Выполни структурную проверку OpenAPI/JSON schemas и валидацию всех примеров, с правильным разрешением `$ref` и проверкой форматов. Укажи точные команды и версии инструментов; запись `python3 -c '... ...'` не является воспроизводимой командой.

Для важных regressions сначала получи ожидаемый отказ на старой реализации, затем исправь и повтори тест. Не создавай искусственный failing test. Обновляй negative corpus вместе с исправленными positive fixtures, чтобы тесты ловили неверный branch/comparison/binding.

### 9. Исправь baseline и freeze evidence

При независимой проверке отчёта, до создания этого промпта, запуск `sha256sum -c docs/hellokitty/execution/baseline-source.sha256` завершился с exit code 1: совпали 367 файлов из 372. Не совпали:

- `docs/architecture.md`;
- `docs/hellokitty/execution/S00-android.md`;
- `docs/hellokitty/execution/S00-environment.md`;
- `docs/hellokitty/execution/S00-report.md`;
- `docs/hellokitty/execution/status.md`.

Исходники Android/relay в снимке совпали. Это расхождение документов не является доказательством потери очистки приложения.

Повтори проверку перед исправлением: после обновления документации список несовпадений может измениться. Приведённые числа относятся к датированному результату review.

Раздели immutable source baseline, датированное состояние документов и финальный contract freeze. Не включай постоянно изменяемый status/report в manifest, который должен подтверждать текущие неизменные исходники. Сохрани прежний manifest как датированный historical artifact, укажи исходную и новую revision/назначение; не перезаписывай историю ради зелёной проверки.

Новый source manifest включает фактические current source/config paths, необходимые для воспроизведения, в том числе untracked исходники. Перечень исключений и deleted paths явный; секреты и build caches не включаются. Contract freeze имеет отдельный полный перечень schemas, vectors, models и probe sources/build metadata, существенных для приёмки.

Избеги циклического хеширования: reports/status, содержащие хеш manifest или меняющиеся после freeze, не входят в тот же manifest. Все writers завершают правки до вычисления итоговых хешей. Новые результаты сопроводи датой, командой, exit code и ограничениями.

### 10. Условия повторной приёмки и итоговые файлы

Координатор читает все три corrective reports и проверяет интегрированный результат. Обнови:

- `docs/hellokitty/execution/status.md`;
- `docs/hellokitty/execution/S01-report.md`;
- `docs/hellokitty/STAGE_01_COMPLETION_REPORT.md`;
- затронутые S00/S01 reports с явным erratum и датой;
- планы/архитектурные документы, затронутые изменением принятых решений;
- итоговый `docs/hellokitty/execution/S01-fix-report.md`;
- отдельный freeze record и manifests;
- очередь этапа02 с task IDs, prerequisites, write ownership и acceptance criteria.

Используй статусы `VERIFIED`, `FAILED`, `NOT RUN`, `UNABLE TO RUN`. Каждый VERIFIED подтверждён реально выполненной проверкой соответствующего утверждения. Отсутствие внешнего domain/VK/SIM не препятствует независимым локальным задачам; соответствующие внешние испытания остаются явно невыполненными.

G01 закрывается только когда:

- metadata/payload/schema/security contract не противоречат друг другу;
- positive и negative fixtures проверены независимыми реализациями и реальным Kotlin consumer;
- ownership, family/grant revocation, lost-response recovery и concurrent model tests прошли;
- SQL specification согласована с проверенной state model;
- резервирование IP учитывает удерживаемые записи;
- Xray API/evidence design привязаны к фактическому source/proto;
- baseline/freeze checks воспроизводимы, без ошибочных утверждений о production;
- отчёты и queue02 отражают фактическое состояние.

Если обязательный пункт G01 недоступен, оставь gate `IN PROGRESS`, назови конкретный blocker и заверши все независимые доступные исправления. Не называй stage02 готовым к исполнению через незакрытый prerequisite.

Локальные проверки выполняй с учётом разделяемого сервера и текущих ресурсов. Не меняй соседние services, firewall, public ingress или production data; не публикуй APK и не создавай commits. Секреты устройства, VK и backend не попадают в логи и отчёты. После изменения контрактов сначала выполняются узкие meaningful checks; тяжёлые Android проверки повторяются только при обосновании.

В финале сообщи, какие дефекты устранены и как подтверждены; точные статусы G00/G01/G02; какие реальные проверки не выполнялись; пути к corrective report, freeze record и передаче02. Пользователь должен получить проверяемый результат исправлений, а не только обновлённую отметку VERIFIED.
