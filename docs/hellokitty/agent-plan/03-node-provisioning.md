# Этап 03. Outbox, reconciler, Xray provisioner и WDTT node-agent

**Статус выполнения: NOT RUN.** Worker, новый Xray private v2 API и staging ноды этим документом не реализованы. В репозитории уже есть WDTT-only node-agent; его наличие не доказывает готовность нового сервиса. Общая архитектура — [implementation-plan.md, разделы 7–10](../implementation-plan.md), API/persistence предыдущего этапа — [этап02](02-backend-control-plane.md).

## Цель и наблюдаемый результат

Замкнуть `202 operation → applied data-plane credential → read-back → ready encrypted profile` для настоящего изолированного staging Xray и существующего WDTT. Выдача, renewal, отзыв, restart и reconciliation должны сохранять owner/revision/expiry, не открывать unauthenticated proxy и не восстанавливать отозванные credentials из позднего retry или backup.

Новая regular нода принимает уникальный VLESS credential устройства без перезапуска всего core на каждую выдачу. У WDTT сохраняется совместимость существующего v1: персональный короткий lease, не общий master password. После acknowledgement worker проверяет фактический data plane; local desired JSON или RPC success сами по себе не означают `ready`. Production listener/firewall/nginx, массовый пилот и реальное прохождение VK whitelist относятся к этапам06–08.

## Входной gate

1. Этапы01/02 приняты: SQL/outbox/state machine/ownership/signing/envelope версионированы, API проверен на настоящем test PostgreSQL, профильный finalization CAS не допускает late ack после revoke.
2. Этап01 утвердил `contracts/node-xray-v2.openapi.yaml` (будущий путь), node generation, private HMAC v2 bytes и response observed semantics. v2 не реализуется путём механической замены v1 headers/поля `subscriptionIdHash`.
3. Известны реальная нода/management network и разрешённая staging топология. Перед запуском выполнен read-only inventory портов/pools/CPU/RAM/disk; выбран независимый test namespace. Существующие 56000/9088 или pool `10.66.66.0/24` не считаются свободными.
4. Xray binary, server/client compatibility и proto commit закреплены. Клиентский source lock содержит core commit `5ca6f4b7d4dc20a881d4330e498892697627ec0c`; если server revision иной, нужен измеренный matrix, а не latest API из памяти. Reality private key создан для нашей staging ноды отдельно и не попадает в профиль/план/Git.
5. Доступны собственные staging secret files: отдельные mTLS CA/certs, worker/node identities, HMAC и credential derivation/encryption keys. Нет заимствованных соседних master secrets или VK accounts. Отсутствие собственного VK login не блокирует regular Xray/staging WDTT management, но VK path остаётся NOT RUN.
6. Утверждён способ authoritative Xray read-back и контрольного authenticated payload probe. Если pinned HandlerService не предоставляет нужного user lookup, это отдельное архитектурное решение01/03; durable JSON не подменяет query к core. Уточнены ограничения закрытия существующих sessions при revoke/expiry.

Реальный isolated staging start разрешается только в границах явного задания на реализацию; один этот handoff не разрешает менять production. При невозможности настоящего staging все code/tests независимых задач завершить, а real data-plane gate обозначить `UNABLE TO RUN` с конкретной причиной.

## Grounding: что существует сейчас

| Поверхность | Проверенный исходный контракт |
|---|---|
| `levik_whitelist_relay/contracts/openapi.yaml` | private POST `/internal/v1/leases/{apply,rotate,revoke,status}`; `GET /livez,/readyz` |
| `node-agent/internal/httpapi/server.go` | strict unknown-fields/trailing-document decode; no query; header `Idempotency-Key` равен body `idempotencyKey`; returned `{requestId,lease}` |
| `node-agent/internal/lease/service.go` | `subscriptionIdHash,deviceIdHash,expiresAt,revision,idempotencyKey`; durable revisions, exact retry, revoked reissue при следующей revision |
| `node-agent/internal/authn/authn.go` | HMAC raw method/requestURI/body; existing replay cache — **map в памяти**, restart её очищает |
| `node-agent/internal/statefile/store.go` | durable lease revision state, version2, atomic temp/fsync/rename/dir fsync; root-owned0700 parent/0600 regular file, no symlink, max8MiB |
| `node-agent/cmd/levik-relay-agent/main.go` | HTTP numeric loopback default `127.0.0.1:9088`, WDTT admin Unix socket; mTLS нужен отдельный reverse proxy; HMAC max body16KiB, skew2m, nonce TTL5m,100000entries |
| `node-agent/internal/wdtt/client.go` | фиксированный Unix admin client; не публичный WDTT admin endpoint и не shell user-input execution |

`go.mod` существующего агента объявляет Go1.23.0, module name сохраняет upstream provenance. Native Android build использовал отдельный pinned Go1.26.5/NDK29; это не автоматический upgrade go.mod node-agent. Новую dependency/proto выборку делает координатор, с compatibility/security review.

## Точные интерфейсы и инварианты

### WDTT v1 сохраняется на boundary

Private v1 request:

```json
{
  "subscriptionIdHash": "<64 lower-case hex>",
  "deviceIdHash": "<64 lower-case hex>",
  "expiresAt": 0,
  "revision": 1,
  "idempotencyKey": "<16..128 allowed characters>"
}
```

`expiresAt` — Unix seconds: apply/rotate включительно от node time+60 до node time+86400; status/revoke разрешают0 и не продлевают lease. Idempotency regex `^[A-Za-z0-9._:-]{16,128}$`, hashes отправляются lowercase. API device/access IDs не хешируются произвольным новым способом: mapper/golden leaseRef утверждает этап01. Один access/device pair на одной ноде соответствует одному legacy leaseRef; не создавать второй под новым label для каждого retry.

Response `lease` содержит `leaseRef,state,revision`, optional `expiresAt,created,rotated,credential.password`; states `active/expired/revoked/absent/unknown`. Password ровно16characters возвращается для create/renew/fresh reissue/точного retry и rotate retry. **Status/revoke не возвращают password**; при `absent` нет expiry. Read-back сверяет state/revision/expiry/leaseRef, credentials сохраняются отдельно encrypted либо повторно получаются exact apply retry. `status` не увеличивает revision и не доказывает закрытие sessions.

HMAC v1 headers `X-Levik-Key-Id, X-Levik-Timestamp, X-Levik-Nonce, X-Levik-Signature`. Canonical:

```text
levik-hmac-v1\ntimestamp\nnonce\nUPPERCASE_METHOD\nrequestURI\nSHA256(rawBody)
```

Signature — lowercase hex HMAC-SHA256, key≥32bytes, nonce base64url без padding≥16rawbytes, skew±120s, nonce retention5m≥2×skew. Body cap16KiB. Повторное delivery: прежний idempotency/body/revision, **новый** timestamp/nonce/HMAC. Public mobile RSA signing и private node HMAC — разные контракты, не единый middleware. Новый `X-HKVPN-*` вводится versioned rollout с явным сроком dual acceptance; v1 wire names остаются совместимостью, не адресацией к Levik.

Существующий replay store не durable: S03-05 должен добавить crash-safe nonce persistence или обоснованный restart quarantine минимум полного acceptance lifetime. Предпочтительно durable bounded nonce store с atomic reserve после authentication и до mutation; corrupt/full store fail closed, cleanup не удаляет ещё пригодный nonce. Restart quarantine ухудшает доступность и допустим только как явно принятое и проверенное решение, не молчаливый fallback.

WDTT address pool .2–.250 содержит максимум249слотов; retention48h учитывает expired/revoked tombstones. Максимум2активных node leases/device, но тысячи TCP/UDP flows допустимы в рамках измеренных caps. Allocation не выдаёт IP, ещё занятый tombstone/session. Fixed port tuple задаётся agent config, не присылается устройством.

### Новый Xray private v2

Будущие endpoints: POST `/internal/v2/xray/credentials/{apply,revoke,status}`. Request содержит утверждённые01 `credentialId,inboundTag,UUID,revision,expiry,idempotencyKey` и node generation; запрет unknown inbound/config/script/URL. У apply secret UUID передаётся только защищённому management API и encrypted store; у status/revoke secret не требуется и не возвращается. Ответ включает observed state/revision/generation/expiry и диагностику stage без secret. Точные names/canonical bytes/schema freezes выполняются01; этот документ не выдаёт proposed fields за готовую OpenAPI.

Agent выбирает allowlisted VLESS inbound template. Xray HandlerService gRPC слушает loopback/isolated management; proto/client из pinned core. Reality private key только на ноде, клиенту public parameters. Local record — desired/recovery evidence, не observed. Нужны idempotent mutation journal, authoritative probe/read-back, persisted anti-rollback revision и локальный expiry sweeper, работающий без control plane.

`RemoveUser` отдельно проверяется для новых и уже установленных TCP/UDP sessions. Не считать успешный RPC мгновенным обрывом старой сессии. Если pinned core не позволяет адресное закрытие, документировать фактическую границу, выбрать проверенный механизм session enforcement/credential isolation и не обещать deadline до теста. Перезапуск всего Xray при обычной выдаче/renewal не допускается: он уничтожит соседние сессии. Expiry в клиентском JSON без server-side enforcement не ограничивает украденный UUID.

### Worker и reconciler

- Outbox at-least-once. Claim transaction короткая: `FOR UPDATE SKIP LOCKED`, записать claim owner/deadline/fencing token, commit; RPC вне SQL transaction. Worker death освобождается по claim timeout, не оставляет row locked навсегда.
- Начальный RPC deadline10s, retry1/2/4/8/15s+jitter/max60s — конфигурируемые staging defaults, затем измерения. Clock-skew, bad signature, permanent input/state conflict не retry бесконечно; transient network/503/429 имеют bounded attempts и next_attempt_at. `Retry-After` bounded, старый event не обходится более новой revision того же aggregate.
- Ревизии сериализуются **по одному credential/lease**: r+1 не применяется прежде r. DB claim fencing и CAS отклоняют stale response от просроченного worker. `revision_gap/stale_revision` требуют status/reconcile, а не слепое увеличение числа.
- Desired revoke побеждает запоздалый apply ack. Агент WDTT v1 допускает fresh reissue revoked lease при revision+1: worker не должен использовать это для resurrection без нового разрешённого grant/event. Cross-device/access mapper проверяется до отправки.
- `apply success → status/read-back expected same credential/generation/revision/expiry → atomic observed write + profile finalization`. После crash на любой границе exact retry сохраняет credential, а не создаёт второй secret/IP. Статус без секретов не превращается в placeholder profile.
- Renew регулярного credential24h выполняется при12h; overlap target120s только когда новый UUID/lease активен и клиент может получить новый signed profile. Legacy WDTT rotate заменяет один password и **не доказывает поддержку двух credentials120s**: нужен явный compatible renewal/rotation design. В MVP можно оставить stable-password renewal и ограничить explicit destructive rotation; нельзя обещать overlap, которого fork не поддерживает.
- Reconciler startup и периодически (pilot30s с jitter) сравнивает desired/observed; controls generation/revision и expiry, repairs missing active, revokes unexpected managed active. Не трогает чужие Xray inbound users/WDTT labels. Схема reconcile защищает от old backup resurrection: boot сначала expiry/revocation validation, затем readiness.
- Alert oldest unapplied outbox>60s; connected-node revoke target≤60s пока проектная цель, а не доказанный SLO. Offline exposure ограничено **фактически enforced** TTL24h и возможной session lifetime; если открытые sessions переживают expiry, отдельный gap gate.

## Задачи и ownership

Backend paths — будущие; node-agent paths существуют. Координатор один изменяет shared contracts/manifests/migrations и agent main/router/store interfaces.

| ID | Субагент | Входы | Ownership файлов | Outputs | Зависимость |
|---|---|---|---|---|---|
| S03-01 | Координатор | reports01/02, source locks | shared API/migrations/module files, task manifest, report03 | frozen worker/provisioner interfaces, staging boundaries, unresolved spec closures | этап02 |
| S03-02 | `outbox-worker` | DB/outbox contract02 | `backend/internal/provisioning/worker*`, own worker tests; DB query изменения через coordinator | claim/fencing/retry/ordered delivery/finalization | S03-01 |
| S03-03 | `node-client` | v1 current + v2 frozen01 | `backend/internal/nodeclient/**`, own contract tests | mTLS/HMAC clients, exact body/retry, bounded endpoints/schema, v1 mapper | S03-01 |
| S03-04 | `xray-agent` | pinned core/proto/v2 | `levik_whitelist_relay/node-agent/internal/xray/**`, own tests | dynamic apply/revoke/status, durable desired journal, expiry enforcement/probes | S03-01 |
| S03-05 | `agent-security` | v1 auth/source, mTLS spec | `node-agent/internal/authn/**`, isolated new replay files/tests; securefile/state shared changes через coordinator | durable replay, header policy, key rotation, root/least privilege compatibility proposal | S03-02–04 wave finish |
| S03-06 | `reconciler` | worker/nodeclient interfaces | `backend/internal/provisioning/reconcile*`, own tests | startup/periodic drift repair, no resurrection, capacity/tombstone model | S03-02–03 |
| S03-07 | `node-isolation` | ingress/egress spec, actual inventory | future `deploy/staging/**` and node templates only; no real infra mutation | namespace/mTLS/egress/resource examples, threat/fault harness | S03-01 |
| S03-08 | Координатор | implementations05–07 | `backend/cmd/reconciler/**`, `node-agent/cmd/.../main.go`, `internal/httpapi/**`, shared state/migrations/config | integrated worker/agent binaries, reviewed isolated staging start | S03-02–07 |
| S03-09 | `crash-integration` | integrated binaries + test DB | `backend/tests/provisioning/**` | actual DB/agent/core crash/fencing/restore tests | S03-08 |
| S03-10 | `transport-security` | isolated Xray/WDTT | `levik_whitelist_relay/tests/provisioning/**`, own captures without secrets | authenticated payload, revoke session semantics, egress/replay tests | S03-08 |
| S03-11 | `capacity-observability` | test node + metrics | `backend/tests/load/**`, own report fixtures | measured capacity/tombstones/FD/RSS, alerts, latency split | S03-08 |
| S03-12 | Координатор | reports/tests | final corrections serially, report03 | acceptance evidence, Android/VK handoff, rollout/rollback facts | S03-09–11 |

## Волны параллельности

1. Координатор один фиксирует contracts/interfaces, proto/dependency versions, read-only inventory и future test topology; worker writers не начинают по незамороженной схеме.
2. Координатор + `outbox-worker`, `node-client`, `xray-agent`. Общие go.mod/go.sum/schema/router/main не редактируются работниками. Xray worker владеет только новым package и его test fixtures.
3. Координатор + `agent-security`, `reconciler`, `node-isolation`. Предыдущие workers завершены; reconcile файлы отделены от worker, auth не меняет shared main самостоятельно. Schema corrections и root-ownership policy serial coordinator integration.
4. Координатор интегрирует и один запускает согласованный isolated staging. Нельзя поднимать три стенда на одинаковых портах/pools. Назначить каждому test worker отдельный fixture namespace/DB schema; только координатор владеет стартом/остановкой node processes.
5. Координатор + три test workers S03-09–11. Crash tests и capacity bench получают отдельные ноды либо выполняются последовательно: убийство общего core во время benchmark даёт бессмысленный результат. Все fault actions ограничены project-owned test PIDs/namespaces.
6. Final corrections/recorded gate, shutdown только own staging. Не удалять volumes ради зелёного теста; сохранять данные, необходимые для recovery evidence.

## Порядок реализации

1. **Уточнить контракты.** Сверить OpenAPI v1 с actual code limits/normalization/errors. Проверить v1 `absent` revision semantics: неизвестный lease status возвращает input revision, поэтому это не proof прошлой успешной выдачи. Freeze v2 observed generation/read-back and auth bytes; согласовать field boundary и wire mapper01.
2. **Claim/retry.** Реализовать короткие DB transactions, claim deadlines/fencing, per-aggregate ordering, error taxonomy и dedup. Изолировать operation status от transient retry; pending может объяснять outage, не становится false failed/ready из случайного timeout.
3. **Private clients.** Management URL только из trusted node registry, не mobile body; cert SAN identity/CA verification, no redirects, bounded timeout/body/JSON. HMAC по exact bytes, fresh nonce per retry, rotation с key IDs. Не ставить `InsecureSkipVerify`; TLS cert ошибка не лечится HTTP fallback.
4. **WDTT bridge.** Подключить действующий Unix agent, точный mapper/golden leaseRef, idempotent create/renew/revoke/status, capacity/tombstone retention. Master password/credential derivation key только node secret files. Тест lifecycle без VK login доказывает management, а не российский relay path.
5. **Xray provisioner.** Pinned gRPC client, allowed inbound template, unique UUID/device/node, idempotent journal с crash recovery, local expiry sweeper. Проверить core после mutation через выбранный authoritative read-back/probe; слушатели только loopback/isolated interfaces. Обработать non-root execution: существующий statefile/securefile требует root owner; нельзя просто chmod world-readable. Выбрать ограниченный privileged helper/secret-reader или явную versioned owner policy, сохранив symlink/permissions checks.
6. **Replay/mTLS.** Durable nonce reserve и bounded cleanup, fail closed disk-full/corrupt state, reset-safe key/cert rotation. mTLS reverse proxy не принимает spoofed peer headers от public client; plaintext agent доступен только loopback. V1↔v2 compatibility bounded by rollout policy.
7. **Finalize/reconcile.** Ack+read-back+CAS, generation fencing, revoke precedence, rotation failure rollback без старого-secret resurrection. Local node restart восстанавливает только неистёкшие допустимые credentials; cold startup не объявляется ready до sweep/validation. Control plane restore ограничивается совместимой snapshot/revocation policy, не слепо доверяет old outbox.
8. **Isolation/egress.** Dedicated netns/container/network/pool, без Docker socket/host filesystem/management egress. Deny loopback, private/reserved/LAN/link-local/cloud metadata, включая IPv6 и DNS-rebinding resolved destination; не блокировать полезный DNS/HTTP без причины. Не превращать management node URL в SSRF route. Ограничить CPU/memory/pids/FD/buffer/credential traffic после измерения.
9. **Реальные fault tests.** Execute narrow unit/race/contract first; затем isolated DB+node+core, crash points, offline expiry/revoke, unauthenticated closure, backup restore. Native/transport source locks не обновлять ради устранения unrelated failure.
10. **Наблюдаемость.** Metrics active sessions/node transport, claim/outbox age, retries, revision gaps, node generation, expiry/capacity/tombstones, RSS/FD/buffers. Без per-device/IP/domain labels и browsing capture; packet captures используют test hosts/credentials и редактируются перед сохранением.

## File manifest

Будущие: `backend/cmd/reconciler/main.go`, `internal/provisioning/**`, `internal/nodeclient/**`, `tests/{provisioning,load}/**`, `contracts/node-xray-v2.openapi.yaml`, `deploy/staging/**`, `levik_whitelist_relay/node-agent/internal/xray/**`, durable replay package/files и соответствующие tests, `levik_whitelist_relay/tests/provisioning/**`.

Существующие изменяемые только по необходимости: `node-agent/internal/authn/{authn.go,authn_test.go}`, `internal/httpapi/{server.go,server_test.go}`, `cmd/levik-relay-agent/main.go`, state/securefile interfaces и tests, `levik_whitelist_relay/contracts/openapi.yaml`, backend shared store/migrations/go.mod/go.sum. Контракт v1 не ломается без migration path. Root LICENSE/NOTICES/provenance locks сохраняются; corresponding source дополняется для нового adapter.

Report: `docs/hellokitty/execution/S03-report.md`. Deployment examples — только изолированное staging, не изменение существующего production Compose/nginx/systemd. Все точные service/ports/subnets утверждает coordinator по inventory.

## Негативные, integration и security проверки

Каждая строка сейчас **NOT RUN**. Test evidence сохраняет command/version/fixture, actual result и data-plane boundary; mocked node не подтверждает core behavior.

| Проверка | Реалистичный дефект |
|---|---|
| Concurrent workers claim один event, смерть после claim/RPC | row lock держится во время network call, duplicate credentials или event никогда не reclaim |
| Crash после node apply до DB ack, exact retry с новым nonce | worker генерирует новый password/UUID/revision и оставляет orphaned active credential |
| Late r ack после r+1/revoke, expired claim fence | stale worker overwrites observed и снова делает revoked profile ready |
| r+1 доставлена раньше r, revision_gap/stale status | алгоритм не сериализует aggregate или blindly increments revision |
| HMAC same request/body/path altered, duplicate headers | подпись не связывает raw bytes либо middleware last-wins parsing |
| Same nonce на двух процессах/после restart, future timestamp+eviction | replay cache локальная map, срок меньше полного acceptance window |
| Wrong mTLS CA/SAN, expired cert, public plaintext request | generic trusted cert допускает другую ноду; agent доступен без management auth |
| WDTT create/renew/rotate/revoke/status exact fixtures | mapper использует UUID вместо fingerprint/access hash; status выдаёт secret; mismatch expiry |
| WDTT revoked reissue race/old backup | разрешённая v1 reissue функция случайно оживляет отозванный device |
| Pool exhausted при expired/revoked48h tombstones | admission считает лишь active и повторно выдаёт занятый IP |
| Xray AddUser accepted, authenticated TCP/DNS payload failed | local journal/RPC ack выдаётся за actual accepted credential |
| Xray restart/gRPC down/corrupt journal/disk full | restore активирует expired UUID; secret lost between core mutation and durable write |
| Offline node после expiry, new и already-open TCP/UDP | клиент expiry есть, сервер UUID всё ещё принимает либо old session продолжает бесконечно |
| Revoke с живой TCP download/UDP stream и соседней session | RemoveUser закрывает лишь new connections; broad restart убивает чужие sessions |
| Rotation failed apply/read-back/expired overlap | старый ключ удаляется раньше готовности нового; WDTT one-password API ошибочно считается overlap-capable |
| Unauthorized inboundTag/config/endpoint/metadata | mobile payload превращается в root config edit/SSRF/open proxy |
| Egress privateIPv4/IPv6/mappedIPv6/DNS rebinding | фильтр проверяет hostname до resolve либо пропускает AAAA/metadata alias |
| Secret file symlink/hardlink/permissions and least privilege | упрощение root checks открывает keys соседнему пользователю/контейнеру |
|100 reconnect,1/10/25/50 clients,24h soak | неограниченные goroutines/FD/buffers, quota по TCP flows ломает обычные сайты |
| Restore old DB/state while revoke already happened | snapshot восстанавливает старый desired active и reusable credentials |

Базовые команды после реализации: `go test ./...`, `go test -race ./...`, `go vet ./...` в backend и node-agent отдельно; contract vectors; project-owned isolated fault harness. Toolchain берётся из metadata, не системного Go1.22 по умолчанию. Нужно читать output, не считать exit0 curl/adb доказательством payload success. Bench и24h soak планируются после функциональных checks; длительность, actual counts и непроведённые ячейки записываются честно.

## Gate приёмки

- `202→pending→ready` доказан реальными DB+agent+core; профиль encrypted/signed именно владельцу; wrong revision/generation/expiry не финализируется.
- Crash recovery, concurrent claims, stale fencing, lease/idempotency/replay restart, no-resurrection и rotation rollback проверены. Нет DB transaction, ожидающей remote node.
- Xray и WDTT выдача подтверждена authenticated полезным payload через собственную staging ноду; open unauthenticated relay и management access закрыты. Это не тест VK allowlist.
- Server-side expiry работает без API; revoke new/open TCP/UDP semantics документированы по pinned core. Если закрытие старых sessions не обеспечено, фактическая exposure boundary известна и production gate не объявлен пройденным.
- Durable replay и mTLS/egress/secret-file tests прошли. Существующее lease state durable отделено от nonce durability; `readyz WDTT admin socket` не подменяет traffic readiness.
- Нет изменений чужих services/keys/ports/pools; собственные captures/logs редактированы, dependency hashes закреплены. Capacity admission учитывает pending/retention/session limits, не обещает249реальных пользователей.
- Полный report actual `VERIFIED/FAILED/NOT RUN/UNABLE TO RUN`. Полевая VK/российские SIM проверка остаётся NOT RUN до этапа06/07.

## Риски, rollback и handoff

Главные стыки: невозможность user lookup в pinned Xray API, длительные sessions после RemoveUser/expiry, root-only legacy storage, WDTT one-password rotation против overlap120s, replay persistence, восстановление старого backup без современной revocation информации. Для каждого есть конкретный spike/test в задачах; не закрывать их предположением. При root-store policy изменениях security tests выполняются повторно.

WDTT hard cap86400s проверить при DB/node clock skew, а не только при одинаковых часах. Предложенный lease safety margin300s при accepted relative skew≤120s оставляет requested expiry ниже24h на обеих сторонах; фактический bound подтвердить signed-time/NTP probe. Profile deadline не позже согласованного observed expiry. Если skew/queue/retry нарушает контракт — controlled error/reconcile, не расширение auth time window. Тесты: node clock позади/впереди, expiry точно на boundary, delayed apply и stale status.24h — максимум, не обещанная точная длина каждой relay аренды.

Rollback в staging: остановить issuance/claims, оставить действующие некомпрометированные data sessions, вернуть compatible API/worker/agent binary и validated state; reconciler сначала проверяет expiry/revoke. Не возвращать revoked secret ради «работоспособности» и не делать global Xray restart, firewall flush, `down -v` или delete соседнего pool. Corrupt/full state fail closed и требует controlled restore, не запускает пустой доверчивый store.

Report03 передаёт этапу04 Android: usable profile fixtures без real secrets, real capability matrix, operation/retry semantics, node TTL/renewal/observed state, собственный payload verification endpoint, error taxonomy и ограничения revoke. Этапу06 VK: exact v1 wire/credential mapper, native compatibility commit, port/pool templates, cancellation/timeouts и фактическая management readiness. Этапам07/08: resource/capacity measurements, ports/subnets reserved только для staging, security/backup/restore evidence, unresolved exposure gaps и rollback sequence. Ни один report не содержит private keys, master passwords, actual UUID/VK cookies или необработанный packet capture.
