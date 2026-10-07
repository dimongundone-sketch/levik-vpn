# Hello Kitty VPN: промпт этапа 02 — реализация бэкэнда

Дата подготовки: 2026-10-06 UTC, обновлено 2026-10-07 UTC после успешного выполнения и полной верификации исправлений повторного независимого ревью G01 (дефекты F01–F10, задачи S01-R2-00..E). Gate G01 официально переведен в статус `VERIFIED`. Этот файл передаёт агенту реализацию Go API и PostgreSQL control plane.

Все 10 замечаний независимого ревью F01–F10 закрыты, реализованы воспроизводимые регрессионные тесты в Python, Go, Kotlin и изолированном PostgreSQL 17 (100% pass), обновлены и согласованы схемы OpenAPI 3.1.0, JSON Schema Draft 2020-12, DDL и контракты безопасности. Контракты заморожены в восьмифайловом манифесте `contracts-freeze.sha256`.

Передай следующему агенту всё задание ниже.

## Задание главному агенту

Работай в `/root/projects/hellokittyvpn`. Реализуй **этап 02: Go API, PostgreSQL и выдача устройств** полностью, с реальной тестовой БД, проверкой поведения и отчётом для этапа 03.

Результат — локально запускаемый бесплатный control plane Hello Kitty VPN: приглашения администратора, Keystore enrollment/reauth, персональные токены, каталог узлов, асинхронные операции выдачи/renew/revoke и защищённые профили. Запрос provisioning атомарно создаёт reservation/desired state/operation/outbox и возвращает `202`. Рабочий профиль появляется только после проверенного observed state.

Worker/reconciler, настоящий Xray/WDTT provisioner, подключение реальных нод, Android API integration, RU routing, VK, публичный ingress и релиз остаются соответствующим последующим этапам. На этапе 02 реализуй persistence/outbox и интерфейс безопасной finalization; node boundary допускается подменять только в тестах. Production/runtime configuration не содержит fake ready nodes.

### 1. Проверь входы и закрепи scope

Прочитай действующие user instructions и применимые `AGENTS.md`/`CLAUDE.md`, затем:

- `docs/hellokitty/README.md`;
- `docs/hellokitty/agent-plan/README.md`;
- `docs/hellokitty/agent-plan/01-contracts-and-security.md`;
- `docs/hellokitty/agent-plan/02-backend-control-plane.md`;
- `docs/hellokitty/agent-plan/03-node-provisioning.md` — только границы следующего этапа;
- `docs/hellokitty/agent-plan/REPORT_TEMPLATE.md`;
- `docs/hellokitty/implementation-plan.md`;
- `docs/hellokitty/execution/status.md`, `S01-fix-report.md`, `S01-fix-mobile.md`, `S01-fix-crypto.md`, `S01-fix-data.md`;
- `contracts/mobile-v1.openapi.yaml`, `profile-v2.schema.json`, `routing-rules-v1.schema.json`, `security-contract.md`, signing/envelope vectors и node contract;
- `backend/docs/data-model.md`;
- реальные `RequestSigner`, `DeviceIdentity`, `HybridProfileDecryptor` и `GoldenVectorsHarnessTest` Android-клиента.

Нормативные входы — исправленные текущие contracts/data model. Исторические completion reports и старые draft snippets при расхождении не заменяют актуальные схемы. Согласуй уточнение через координатора и обнови затронутые документы/проверки, если найден реальный contract gap.

На момент подготовки рабочее дерево было чистым, HEAD — `61b4c8fb1f63fd00efc629ae5117632edbf2dbe5`. Перепроверь HEAD и `git status`: новые пользовательские изменения сохраняются. Upstream `0d05186a...` в старых отчётах является исторической базой. Не заменяй текущую рабочую копию клоном upstream, не выполняй reset/clean и не создавай новый commit автоматически.

Выполни narrow preflight:

```bash
sha256sum -c docs/hellokitty/execution/contracts-freeze.sha256
python3 contracts/probes/conformance/conformance_test.py
bash contracts/probes/crypto/run_all_probes.sh
```

В `contracts/probes/crypto/go`:

```bash
go test -race -count=1 ./...
```

Проверь Kotlin XML и соответствие actual sources/fixtures. Повторный Gradle нужен при изменении потребителей/векторов, отсутствующем evidence или новой обоснованной проблеме; не запускай все тяжёлые сборки автоматически.

Восьмифайловый freeze включает `mobile-v1.openapi.yaml`, `node-xray-v2.openapi.yaml`, `profile-v2.schema.json`, `routing-rules-v1.schema.json`, `security-contract.md`, `signing-vectors.json`, `envelope-vectors.json`, `challenge-vectors.json`. Для S02-inputs record отдельно закрепи actual HEAD, hashes нормативной модели (`backend/docs/data-model.md` SHA256 `016a0b1c4a1b03b697783e0df8f2c51f521ea129c67441c822f19dc99d1d885c`), Kotlin consumer/test и необходимых probes. Не включай изменяемые status/report в циклический manifest.

Не объявляй G02 начатым на основании старого текста READY. После принятого preflight поставь G02 `IN PROGRESS`; G01 остаётся VERIFIED лишь при сохранении его действительных обязательных условий. При обнаруженном blocking contract defect исправь зависимость, выполняя независимые задачи параллельно.

В `S01-fix-report.md` очередь включает provisioning worker/node client. Сверь её с нормативным этапом02: эти реализации относятся03. Зафиксируй очередь S02-01…09 ниже и единые имена backend packages до запуска исполнителей.

### 2. Организуй субагентов и общие файлы

Максимум главный агент + три активных субагента. Сначала координатор один создаёт module, contracts между пакетами, migration/test harness skeleton и write ownership manifest. Затем три implementation writers работают параллельно. Общие interfaces меняются координатором с уведомлением потребителей.

| Task ID | Writer | Разрешённые paths | Результат / зависимость |
|---|---|---|---|
| S02-01 | Координатор | `backend/go.mod`, `backend/go.sum`, `backend/cmd/api/**`, `backend/internal/config/**`, `backend/internal/contracts/**`, `backend/migrations/**`, `backend/tests/fixtures/**`, `backend/scripts/**`, `backend/.env.example`, `backend/README.md`, `docs/hellokitty/execution/S02-inputs.md`, `docs/hellokitty/execution/S02-ownership.md`, `docs/hellokitty/execution/S02-report.md` | Bootstrap, единственные shared interfaces/migrations/dependency manifests; вход G01 |
| S02-02 | `store` | `backend/internal/store/**`, его package tests, `docs/hellokitty/execution/S02-store.md` | Настоящие PostgreSQL repositories и транзакции; после S02-01 |
| S02-03 | `identity` | `backend/internal/auth/**`, `backend/internal/device/**`, их package tests, `docs/hellokitty/execution/S02-identity.md` | Подпись, enrollment/reauth, tokens/recovery через общие store interfaces; после S02-01 |
| S02-04 | `profiles` | `backend/internal/profiles/**`, его package tests, `docs/hellokitty/execution/S02-profiles.md` | Каталог, admission, операции, envelope/finalization/revoke; после S02-01 |
| S02-05 | Координатор | `backend/internal/httpapi/**`, `backend/cmd/admin/**`, общие config/wiring и interfaces | HTTP, CLI, integration; после S02-02…04 |
| S02-06 | `api-security` | `backend/tests/security/**`, `docs/hellokitty/execution/S02-security.md` | Независимые attack/regression tests; после S02-05 |
| S02-07 | `db-integration` | `backend/tests/integration/**`, собственные fixtures внутри этого пути, `docs/hellokitty/execution/S02-integration.md` | PostgreSQL concurrency/rollback/restart tests; после S02-05 |
| S02-08 | `contract-client` | `backend/tests/contract/**`, `docs/hellokitty/execution/S02-contract.md` | HTTP/schema/crypto conformance; после S02-05 |
| S02-09 | Координатор | Согласованные corrections, общий report/status/index, completion report | Интегрированная приёмка G02 и передача03; после S02-06…08 |

Paths в таблице относительно `/root/projects/hellokittyvpn`. `backend/docs/data-model.md` и frozen `contracts/*` изменяет только координатор при обоснованном согласованном изменении. Migration SQL работники предлагают ему, а не записывают одновременно.

`go.mod`, `go.sum`, migrations, shared fixtures, OpenAPI и main wiring имеют одного writer. Не вводи второй пакет storage или crypto с дублирующей логикой. Если нужна общая криптография, зафиксируй её интерфейс/владельца в S02-01. Каждый test worker получает отдельную DB/schema и namespace fixtures. Нагрузочные измерения выполняются отдельно от параллельных тяжёлых тестов.

### 3. Создай настоящий локальный backend и PostgreSQL harness

1. Проверь свежие ресурсы, listeners и Docker metadata без secrets. Хост разделяемый. TCP5432/UDP56000 в прежнем inventory заняты; их доступность перепроверяется. Не используй соседние БД, Xray, WDTT, nginx или Compose как fixtures.
2. Используй закреплённый изолированный Go toolchain из `/root/.cache/hellokittyvpn-toolchains/` либо обоснованный совместимый уже доступный toolchain с явной фиксацией. Не заменяй системные Go/JDK/NDK. Выбери поддерживаемый конкретный PostgreSQL patch и `pgx` version, проверь authoritative compatibility metadata и зафиксируй версии/checksums/image digest. Mutable `latest` исключён.
3. Предпочитай стандартный `net/http` и существующие средства. Новая зависимость добавляется по конкретной необходимости; manifests/locks меняет координатор. Redis и отдельная очередь для MVP не обязательны: durable state/outbox находятся в PostgreSQL.
4. Подними только собственную тестовую БД в изолированной project network. БД не публикуется на `0.0.0.0`; API по умолчанию слушает выбранный свободный loopback адрес, tests используют собственные ephemeral endpoints. Порт хоста25432 не является требованием.
5. Зафиксируй names/labels/IDs созданных test resources. Cleanup удаляет только disposable fixtures/resources этого запуска, без общих prune/flush/restarts и без чужих volumes. Не оставляй orphan test services после проверки.
6. Config валидирует limits/listen/DSN/secret-file paths/schema version и корректно завершает процесс. Секреты не передаются в CLI args, Git или diagnostic output. `.env.example` содержит placeholders; sample domains OpenAPI не означают владение доменом или разрешение подключаться к нему.
7. Создай реальные migrations в правильном dependency order. Спецификация data model содержит illustrative SQL: проверяй каждый DDL/query на выбранном PostgreSQL. В частности, ограниченный cleanup делай допустимым PostgreSQL запросом, например через CTE выбранных IDs; не копируй `DELETE ... LIMIT` как исполняемый SQL.
8. Проверь миграции на пустой собственной DB, повторном запуске migration manager и несовместимой schema. Extension для UUIDv7/pgcrypto должна быть явно предусмотрена и проверена; UUIDs не генерируются собственной криптографией. Не делай destructive down migration ради прохождения теста.

### 4. Реализуй proof, приглашения и регистрацию устройств

Публичный API — `/v1` по замороженной OpenAPI. Продукт бесплатный; приглашения и grants — управление доступом, без аккаунтов/платежей/подписок Levik.

Wire deviceId — lowercase SHA256(DER SPKI). Внутренний UUID отдельный. Проверяй публичный RSA SPKI, key size3072/4096, exponent65537, declared capabilities, canonical DER и отсутствие trailing/private/oversized материала. Capability хранится в device policy: входной algorithm header не меняет зарегистрированный алгоритм. Hardware attestation/StrongBox не обязательны для старых Android и Direct без GMS.

RequestSigner v1 — ровно восемь строк UTF-8 без завершающего LF:

```text
v1\nMETHOD\nencodedPath\nepochSeconds\nnonce\ndeviceId\nSHA256(token)\nSHA256(rawBody)
```

Хешируй исходные bytes, не пересериализованный JSON. PS256: SHA256/MGF1-SHA256/salt32; RS256: PKCS#1v1.5 SHA256. Signature/nonce — строгий base64url без padding. Query и ambiguous auth headers отклоняются; encoded path не подменяется decoded/нормализованным router path. Проверяй ±120s signing window и durable unique `(deviceId, nonce)` с retention5min через PostgreSQL, включая разные API processes/restarts.

Реализуй `POST /v1/devices/challenges` с CSPRNG nonce32B, TTL120s, mode enroll/reauth, invitation/capability validation и limiter. Формат signed serverTime/challenge response, key purpose и bytes должны быть определены входным контрактом; отсутствующую деталь согласуй до выдачи новой подписи.

`POST /v1/devices/complete`: proof, ограничение попыток, owner/key/challenge binding и атомарные invitation consume + device/grant/family/tokens + recovery record. Reauth разрешён известному действующему device/grant, не расходует invitation и не продлевает его право доступа. Pending/expired/revoked inputs возвращают согласованную ошибку без частичного создания records.

Двадцать конкурентных complete одного одноразового invitation/challenge не создают дополнительные grants/devices. Exact retries одного operation возвращают согласованный единственный результат; запросы с разными operations конкурируют за то же право. Не требуй, чтобы exact retries обязательно давали только один HTTP success: side effect должен быть один.

### 5. Реализуй tokens, reauth и recovery на PostgreSQL

Access и refresh — независимые CSPRNG256bit opaque secrets. Access TTL15min; refresh family absolute TTL30d. Не продлевай family sliding renewal. Token lifetime ограничен family/grant expiry, действует ровно одна active family/device. Auth tables хранят hashes и bindings; recovery cache содержит AEAD ciphertext, а ключ хранится вне DB.

Для refresh access Authorization отсутствует, signer tokenHash=SHA256(empty); refresh secret находится в подписанном body. Для complete/refresh/idempotent writes header `Idempotency-Key` совпадает с **подписанным** body `clientOperationId`. Request nonce обновляется при каждом retry, operation/body сохраняются.

Применяй исправленную семантику `issuance_*` и `consumed_by_*`. Старый consumed token атомарно связывается с операцией, использовавшей его. Internal operation ID и wire clientOperationId не смешиваются. Long-term consumed binding переживает cache120s и idempotency48h cleanup до token/family retention.

Обязательные outcomes:

- exact same owner/op/body retry в recover window120s возвращает **тот же** ответ, без новой rotation;
- same bound operation + different body — 409, без отзыва family;
- consumed token + новый operation — reuse/family revoke;
- late exact retry — `REFRESH_RETRY_EXPIRED` и reauth по existing key/grant;
- token/family/device/grant ownership, revocation и expiry проверяются до cache return;
- чужой token от имени другого устройства отклоняется без изменения family владельца;
- reauth создаёт новую уникальную family, сохраняет отзыв всех прежних и не оживляет old tokens;
- same-second reauth и одинаковые prefix fingerprints не вызывают коллизий;
- exact retry consumed complete/challenge в120s восстанавливает результат без второго invite consume и без повторного отзыва только что выданной family.

В одной транзакции выполняй consumption binding, единственный successor, access token, encrypted response cache и соответствующую idempotency state. Body/operation conflict precedence и cache cleanup не меняют classification позднего exact retry. Различай transaction rollback/retry и подтверждённый commit: HTTP success выдаётся после commit.

AEAD cache/credential encryption использует отдельный storage purpose/keyId и AAD binding owner/operation/revision. Токены не попадают в plaintext SQL snapshots, outbox или logs. Не сохраняй произвольные HTTP responses middleware в долговременном кэше.

### 6. Реализуй catalogue, operations, outbox и envelope

Реализуй все пути из `mobile-v1.openapi.yaml` с точными request/response/error schemas. Каталог ограничен200 records, query pagination в signing v1 отсутствует. Node management URLs, private keys и raw credentials в catalogue не выдаются. Только зарегистрированные capability/health/capacity candidates; пустой каталог допустим до реальных узлов03.

Для `POST /v1/profiles` и renew проверяй device/grant, ownership, node capability/freshness, expected revision и admission. Учитывай active **и pending** reservations в cap2 leases/device. Desired credential/revision, reservation, operation и outbox записываются атомарно, network I/O находится вне transaction. Повтор same operation/body не создаёт второе credential/outbox; changed body даёт409.

GET operation/profile и renew недоступны чужому device по opaque UUID. `DELETE /v1/devices/me` подписывает empty body, не принимает Idempotency-Key, атомарно запрещает выдачу/refresh и ставит revoke events. Повтор после отзыва может вернуть401/410 без новых side effects. Не вводи обход token revocation ради одинакового HTTP ответа.

Отложенная finalization принимает только доверенное server-side observed evidence для нужных node generation, credential, revision, expiry и actual core state. Проверь её повтор/idempotency/CAS, поздний ack после revoke и старую generation. Это внутренний интерфейс для worker03, не публичный endpoint для клиента. Тестовая fake boundary не может включаться runtime flag в обычном API.

Profile envelope формируется по exact schema/vectors: purpose/profile metadata, личный device SPKI, AES256-GCM key32B/IV12B/tag16B, negotiated RSA-OAEP/MGF1, ECDSA P256/SHA256 strict DER с согласованным low-S. AAD и signed string побайтно совпадают с contract01. Metadata/payload owner/IDs/revision/issue/expiry согласованы. Не ослабляй schema ради legacy mapper, относящегося Android04.

До подтверждения узла операция остаётся pending; profile retrieval не возвращает пригодный signed VPN profile. Ready путь проверяется test-only observed fixtures с положительными и отрицательными evidence. Regular TTL24h/renewal12h и overlap120s — целевая политика; фактическое применение/rotation проверяет03. WDTT reserves включают retained tombstones до released/purge_after; cap249 IP и lease≤24h не обходятся.

Публикация routing bundle относится05. Если собственного актуального bundle нет, endpoint возвращает предусмотренное controlled unavailable, а не подписанный вымышленный version. Не подключай прежний backend или чужой feed как fallback.

### 7. Реализуй HTTP, admin CLI и эксплуатационные границы

- Bounded strict UTF-8 JSON: unknown/duplicate critical fields, второе JSON document, неверные enums/numbers и trailing data отклоняются. Обычный request cap64KiB, абсолютный1MiB; envelope response cap2MiB, decrypted payload1MiB, protected metadata16KiB по контракту.
- Read/header/write/idle timeouts, bounded crypto/body work, cancellation и graceful shutdown. Malformed input не приводит к panic или неограниченной памяти.
- `application/problem+json` с согласованными code/status/retryable/retryAfterSeconds/requestId; без SQL, secret paths, body/token или internal stack.
- `Cache-Control: no-store` для secrets; логи requestId/status/duration/error stage без bearer, invitation, UUID credential, cookie, plaintext profile или browsing domains. Metrics без device/IP/domain labels.
- Defaults rate limits из этапа02: challenge5/min/invitation и20/min/source, reads60/min/device, writes10/min/device с bounded burst. Уточнения фиксируются с NAT tradeoff. `X-Forwarded-For` доверяется только явно заданному trusted ingress; клиентский header не выбирает limiter identity.
- Admin только локальный CLI: создание/отзыв invitation/grant, просмотр безопасного состояния и конфигурация собственного node candidate. Нет public admin endpoint. Secret приглашения выдаётся как intentional operator output, не попадает в argv/логи; секретный ввод возможен через stdin/защищённый файл.
- `/livez` и `/readyz` — management surface; readiness различает DB/schema/backlog и не означает готовность VPN payload. API local HTTP/тестовый TLS не подменяет будущее public TLS deployment; certificate checks не отключаются ради probes.
- Cleanup bounded batches: cache120s, nonce5min, idempotency48h, token/family/grant retention и retained IP по спецификации. Logging7d/anonymous aggregates30d/admin audit90d — исходные defaults, согласуй применимость и фактическое отсутствие лишних персональных данных.

### 8. Проверь настоящую реализацию, а не только модели

Вторая волна test writers S02-06…08 использует реальный HTTP API и **настоящий собственный PostgreSQL**. SQLite/in-memory models не заменяют integration tests. Fake только node/external boundary; signature, encryption, SQL и commit проверяются настоящими реализациями.

Обязательные scenarios:

| Сценарий | Что требуется подтвердить |
|---|---|
| 20 concurrent enrollment/complete, раздельные operations и exact retries | Единственное расходование права/invitation, consistent recovery, без orphan device/grant |
| Concurrent refresh с barriers и двумя API processes | Один successor/branch, exact retry recovery; PostgreSQL locking/constraints действительно работают |
| Lost complete/refresh response после commit | Тот же encrypted cached result, без второго consume/rotation/revoke |
| 120s/±120s/5min boundaries и cleanup >48h | Expiry/replay/idempotency classification не зависит от local map либо случайного clock сравнения |
| Replay после API restart и между processes | Nonce state durable и общая для экземпляров |
| Same op/changed body, old token/new op, late exact retry | 409/reuse/expired recovery различаются по контракту, family не отзывается ошибочно |
| Cross-device token/profile/operation/renew и revoked/expired grant | Ownership и доступ проверяются на всех object requests и cache paths |
| Same-second reauth, prefix collision, old families | Уникальные families, старые токены не оживают |
| Десять concurrent profile requests и retries | Pending входят в cap2; нет duplicate reservation/outbox |
| Rollback/fault между desired/reservation/outbox insert | Все записи либо commit together, либо отсутствуют; success не выдан до commit |
| Wrong/late observed generation/revision/expiry после DELETE | Pending/revoked не становятся ready и не получают envelope |
| SQL tombstone occupancy до и после purge | IP не получает второго владельца до освобождения; cap249 сохранён |
| Kotlin↔Go vectors и actual backend envelope | Реальный response/schema/подпись/decrypt совпадают, tamper/cross-device/rollback rejected |
| Duplicate auth headers/JSON keys, query, encoded separators, invalid UTF-8, oversize | Signer/router/parser используют один однозначный запрос |
| Forged forwarded IP, shared NAT, 429 retry | Ограничения не обходятся header и не ошибочно привязаны только к NAT IP |
| Captured logs/errors/cache/DB dumps на тестовых secrets | Нет plaintext token/private credential material в запрещённых surfaces |
| Migration manager, DB/API restart, config/key error и cancellation | Controlled failure; нет открытого API с incompatible schema либо unsafe defaults |

Для важных найденных defects сначала воспроизведи expected failing regression, затем исправь и повтори. Test assertions проверяют side effects, counts/bindings/outbox и состояния БД, а не только HTTP200. Отдельно сопоставь backend outputs с положительными/отрицательными fixtures через conformance pipeline, включая decoded metadata и decrypted payload.

Выполни из `backend/` соответствующие новой реализации команды:

```bash
go test ./...
go test -race ./...
go vet ./...
```

Убедись, что DB suites реально исполнились, а не skipped из-за отсутствия DSN/build tag. Если tests требуют специального harness/flags, запиши точные воспроизводимые команды и counts. Ограничь race/integration concurrency по ресурсу shared host. Security/dependency audit — доступным штатным инструментом; не устанавливай scanner лишь ради формальной отметки. Запиши materially unverified boundaries.

Проведи отдельное измерение100 concurrent registration/renewal API requests на собственных fixtures. Опиши нагрузку, identity mix, лимиты/429, latency successful/failed requests, throughput и ресурсы. Pilot target p95≤500ms относится local API/DB path; node provisioning latency измеряется03. Не подменяй результат отключением security checks или включением test shortcuts в runtime.

### 9. Заверши G02 и передай этап03

Ожидаемые артефакты: реальные `backend/cmd/{api,admin}/**`, `internal/**`, migrations, dependency metadata, `.env.example`, README, test-DB harness и suites. Старый Android UI, native/source locks, node-agent и чужая инфраструктура остаются вне scope02.

Обнови `docs/hellokitty/execution/status.md`, `S02-report.md`, создай `docs/hellokitty/STAGE_02_COMPLETION_REPORT.md` и ссылки документационного index. Не удаляй historical evidence. При требовании действующих repository instructions создай architecture log для реально добавленных компонентов; запись о будущем плане не заменяет implementation evidence.

G02 становится `VERIFIED`, когда:

1. API/CLI реально собираются и локально запускаются с собственной PostgreSQL.
2. Миграции и DB constraints/transactions подтверждены integration/concurrency tests.
3. Auth, token lifecycle, recovery, ownership, durable replay и revocation работают на actual backend.
4. Profile/crypto conformance совпадает с Kotlin consumer и frozen contracts.
5. Desired/reservation/operation/outbox атомарны, finalization не выдаёт ready без accepted observed evidence.
6. Unit/race/vet/contract/security/DB checks выполнены и outputs прочитаны; ограничения перечислены.
7. Нет fake-ready runtime, public exposure или изменений соседних сервисов.
8. Actual diff, dependency/migration changes, rollback и reproducible local run reviewed; данные и secrets защищены.

Если обязательный gate check failed или недоступен, исправь вызванный задачей defect; оставь G02 `IN PROGRESS` и укажи точный blocker, завершив независимые доступные части. Существование .go файлов, конфигов и успешных probes01 не является завершением02.

В отчётах используй `VERIFIED`, `FAILED`, `NOT RUN`, `UNABLE TO RUN`, точную команду, tool versions, counts, exit code и file evidence. Тестовая БД не доказывает production reliability. API readiness не доказывает подключение VPN, RU direct, VK bypass или реальный data-plane traffic.

Передача03 содержит contract/model/input hashes, выбранные versions, migrations/schema version, actual operation/outbox payload formats, finalization interfaces, revision/generation ownership, encrypted material/key-purpose handling, exact local run/test commands без secrets, ресурсный budget, известные gaps и rollback. Worker03 должен подтверждать core state через фактически доступный Xray read-back; read-back выбранного inbound и generation проверяются настоящей интеграцией03.

Пользователю в финале сообщи конкретное реализованное поведение, реальные проверки и статусы G01/G02/G03, путь к completion report и ограничения. Домен, зарубежные ноды, VK и SIM относятся последующим этапам и не должны останавливать доступную локальную реализацию02. Публичный deploy, публикация APK и изменение production этим промптом не запускаются.
