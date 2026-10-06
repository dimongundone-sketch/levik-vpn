# Этап 02. Go API, PostgreSQL и выдача устройств

**Статус выполнения: NOT RUN.** Этот файл передаёт будущую работу агенту; наличие плана не означает, что API, БД или endpoints запущены. Текущий Android принимает локальные профили; результаты его очистки — [app-cleanup.md](../app-cleanup.md). Архитектура и исходные решения — [общий план, разделы 6–10](../implementation-plan.md).

## Цель и наблюдаемый результат

Создать независимый бесплатный control plane: устройство доказывает владение Android Keystore ключом, получает персональную сессию и право доступа, видит реальные кандидаты узлов и запрашивает асинхронную выдачу профиля. Чужое устройство не получает его профиль, секрет или состояние операции. Запрос выдачи создаёт атомарную операцию и outbox, возвращает `202`, а не вымышленный работающий VPN. До подтверждения data plane этапом 03 профиль остаётся `pending` и не выдаётся клиенту как пригодный для подключения.

В scope входят Go HTTP API, PostgreSQL persistence, enrollment, token lifecycle, access grants, server catalogue, операции выдачи/обновления/отзыва, безопасный profile envelope, локальный admin CLI и тесты. Worker и управление настоящим Xray/WDTT находятся в [этапе 03](03-node-provisioning.md). Публичный ingress, production Compose, домен, firewall и релиз относятся к этапу 08. В этом этапе нет платежей, аккаунтов Levik, Telegram login, пользовательского admin UI и публикации порта в интернет.

## Входной gate

Координатор начинает реализацию только после принятия [этапа 01](01-contracts-and-security.md):

- Зафиксированы `contracts/mobile-v1.openapi.yaml`, `profile-v2.schema.json`, signing/envelope golden vectors, схема БД, state machine и политика версий. Генерация DTO не меняет контракт самовольно.
- Проверены реальные `RequestSigner.kt`, `DeviceIdentity.kt`, `HybridProfileDecryptor.kt` в `levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/`. Сервер использует уже согласованные bytes/algorithms, а не похожую схему из другого VPN.
- Утверждены эксплуатационные значения: refresh absolute TTL30дней, idempotency retention48часов, recovery window120секунд; envelope AAD/signature bytes, профильная anti-rollback version и отображение public UUID ↔ wire fingerprint. Это предложенные defaults этапа01; до его crypto/DB review и freeze они остаются проектными решениями.
- В01 выбран bodyless `DELETE /v1/devices/me` без Idempotency-Key: proof подписывает empty body; повтор после отзыва может вернуть401/410 без нового side effect. Для refresh и остальных idempotent write подписанный `clientOperationId` равен header key. Refresh/complete с потерянным ответом восстанавливают encrypted cached result без plaintext bearer и ложного family revoke.01 проверяет state/probe contract; реальные HTTP+Postgres тесты выполняет02.
- Data model/constraints из01 приняты; создание SQL baseline и применение к пустой изолированной test DB — первая задача02, не отсутствующий prerequisite. Координатор02 выбирает поддерживаемый patch PostgreSQL, Go toolchain и `pgx` version и закрепляет версии/хеши. Кандидат PostgreSQL17.11 в общем плане — исследовательский snapshot, не разрешение тянуть mutable `latest`.
- Зафиксирован read-only inventory сервера и изолированный ресурсный бюджет. Staging DB/API используют только пространство проекта. Никакие соседние PostgreSQL instances, nginx, Xray и WDTT не используются как test fixture.

Если решение отсутствует, координатор оформляет конкретный contract issue и останавливает зависимую задачу; независимые store/validation/tests продолжаются. Не заполнять недостающие поля выдуманными адресами или секретами.

## Интерфейсы, defaults и инварианты

### Идентичность и подпись

Внешний `deviceId` — lowercase hex SHA256 DER SPKI; internal UUID в БД — отдельное поле. Допускаются RSA 3072/4096, exponent 65537 и объявленные проверенные PS256/RS256. PS256: SHA256, MGF1-SHA256, salt32; RS256: PKCS#1 v1.5 SHA256. Изменение header algorithm не меняет policy сохранённого ключа. Private key, EC key, malformed DER, trailing DER bytes и неоднозначные параметры отклоняются.

Canonical RequestSigner v1:

```text
v1\nMETHOD\nencodedPath\nepochSeconds\nnonce\ndeviceId\nSHA256(token)\nSHA256(rawBody)
```

Это восемь строк без завершающего newline. Hash — lowercase hex, signature — base64url без padding. Подпись проверяется по исходным байтам до JSON parse; JSON нельзя reserialize ради hash. Query в v1 запрещён, включая pagination query: ограниченный список серверов MVP возвращается целиком; расширенная пагинация требует согласованной подписанной схемы новой версии или отдельного POST search. Ingress и Go router не должны по-разному нормализовать путь.

Headers: `Authorization: Bearer`, `X-HKVPN-Device-Id`, `X-HKVPN-Timestamp`, `X-HKVPN-Nonce`, `X-HKVPN-Signature`, `X-HKVPN-Algorithm`. Nonce —16 случайных байт, timestamp±120s, uniqueness `(device_id,nonce)` в PostgreSQL5min. Повторные auth headers, неоднозначные encoded separators/percent encoding, query, неподписанный body/token owner mismatch отклоняются. Валидный однозначный encoded path проверяется по golden vectors, не запрещается blanket. У idempotent write `Idempotency-Key` равен signed `clientOperationId`; bodyless DELETE — явное исключение без key. Retry подписывает новый nonce и прежнее operation body.

Challenge: 32 случайных байта, TTL120 секунд, max5 попыток. Initial enrollment закрепляется за SPKI и invitation; reauth — за существующими device/key/действующим grant и принятым mode. Initial challenge и invitation потребляются атомарно; reauth challenge также одноразовый, но не тратит invitation. Proof при `devices/complete` использует тот же v1 signer с пустым token. Signed server time имеет собственную утверждённую envelope/signature схему; обычный `Date` header не даёт права выключить TLS time validation.

### API и токены

| Endpoint | Результат/обязательное ограничение |
|---|---|
| `POST /v1/devices/challenges` | initial invitation либо утверждённый `mode=reauth` существующего device; challengeId/nonce/expiry/serverTime; rate limit, не oracle invitation |
| `POST /v1/devices/complete` | атомарное initial enrollment либо Keystore proof reauth только действующего device+grant; reauth не тратит invitation повторно |
| `POST /v1/tokens/refresh` | proof-of-possession, atomic rotation, family reuse detection по принятой retry policy |
| `GET /v1/servers` | только capability-tested candidates; health/capacity freshness; без private keys и node management endpoints |
| `POST /v1/profiles` | owner + capability + idempotency; `202` operation; никакого синхронного remote apply внутри SQL transaction |
| `GET /v1/operations/{id}` | только владелец; `pending/ready/failed`, bounded retry hint |
| `GET /v1/profiles/{id}` | только владелец; пригодный encrypted signed envelope после подтверждения узла |
| `POST /v1/credentials/{id}/renew` | владелец, expiry/revision, асинхронная безопасная renewal/rotation |
| `DELETE /v1/devices/me` | немедленно запрещает новую выдачу/refresh и атомарно ставит отзыв в outbox; не обещает мгновенное закрытие offline sessions |
| `GET /v1/routing-rules/manifest` | публичный signed, cacheable; publication rules появится в этапе05; отсутствие bundle не заменять фиктивным version |
| `/livez`, `/readyz` | management-only; readiness БД/schema; backlog и data-plane readiness видны отдельными метриками |

Opaque access/refresh — независимые random256bit. Access TTL15 минут; в auth tables только hash, device/family binding и expiry/revocation. Refresh absolute TTL30дней, без бесконечного sliding prolongation. Refresh передаётся в подписанном JSON body, `Authorization` отсутствует, RequestSigner token hash — SHA256 пустой строки; остальные защищённые endpoints используют access bearer. Подписанный body содержит `clientOperationId`, равный header `Idempotency-Key`.

Точный retry consumed refresh с тем же bound device/op/body в первые120s возвращает **тот же** encrypted cached response после повторной проверки device/grant/family/expiry; второй rotation нет. Precedence01: same op+changed body →409 без rotation/revoke; consumed refresh+новый op →reuse/family revoke. Binding consumed token→op/body сохраняется до family/token retention, даже после purge idempotency48h. Exact retry вне120s →`REFRESH_RETRY_EXPIRED`, затем Keystore reauth действующего device/grant без расхода invitation. `devices/complete` имеет аналогичный bound exact-retry recovery consumed challenge; reauth атомарно оставляет одну active family/device. Token expiry не превышает family/grant expiry. Encrypted response cache очищается отдельно; общий middleware не хранит произвольные bearer responses. Plaintext token не попадает в logs/outbox/SQL snapshots; cache key внеDB.

UTF-8/JSON с explicit strict schema, без unknown/duplicate fields и второго JSON document. Hard cap body1MiB, типичный endpoint cap64KiB; меньшие endpoint limits фиксируются контрактом. `application/problem+json`: `code,title,status,retryable,retryAfterSeconds,requestId`, без SQL, paths и секретов. Время RFC3339 UTC, expiry вычисляет PostgreSQL clock. Запрос клиента никогда не задаёт произвольный outbound URL.

Pilot limits из общего плана: 1 активное устройство на invitation, максимум2 активных node leases/device с учётом резерва/rotation, 20–50 пользователей до измерений. Это не лимит TCP/UDP flows. Challenge5/min/invitation и20/min/source limiter, reads60/min/device, writes10/min/device, конфигурируемый bounded burst; для общих NAT invitation/device важнее одного IP. Источник IP доверяется только известному ingress, forged `X-Forwarded-For` не меняет limiter.

### База и выдача

Таблицы/constraints берутся из раздела7 общего плана: devices/invitations/access_grants/challenges, token_families/refresh_tokens/access_tokens/request_nonces, nodes/node_capabilities, credentials/leases/profiles, operations/idempotency_keys/outbox, node_observations/audit_events. Основные требования:

- invitation use, challenge consume, grant и tokens появляются в одной транзакции; гонка не выдаёт два устройства на invitation;
- unique token hash, wire device fingerprint, owner+idempotency key и lease IP occupancy закреплены БД;
- pending credentials/leases входят в admission reservation, иначе два параллельных запроса обойдут cap2;
- запись desired state, revision, operation и outbox атомарна; outbox содержит opaque refs и encrypted credential material только если нужен, без bearer/VK cookie;
- network acknowledgement не подменяет observed state. `ready` появляется после проверенного read-back для той же node generation/credential/revision/expiry, которого API до этапа03 не получает;
- revoked device/access не получает ready envelope даже если поздний worker ack пришёл после отзыва; revision CAS и ownership проверяются в finalization;
- regular credential TTL24h, renewal в12h, overlap target120s только после подтверждённой новой выдачи. Устаревший profile не становится вновь действительным из backup.

Profile v2 содержит `profileSchemaVersion, profileId, accessId, deviceId, issuedAt, credentialExpiresAt, rulesVersion, engine, servers, bootstrap, signatureKeyId`. AES256-GCM key32bytes, IV12bytes, tag16bytes; RSA-OAEP и точные MGF1 parameters из capability/vectors. Legacy OAEP-SHA1 допускается только как объявленная policy старых устройств. AES-GCM AAD аутентифицирует protected metadata; domain-separated envelope signature охватывает protected metadata, wrapped key, IV и ciphertext по точным byte vectors01. Legacy runtime mapper запускается только после этой верификации. Signing, routing и OTA keys разделены. Secret credentials шифруются at rest; ключ вне DB, Git и process arguments. Public key/device ownership проверяются перед encrypt, размер ответа согласован с client caps.

## Задачи и ownership

Все backend paths ниже **будущие**. Координатор владеет общими manifests, wiring, migrations и контрактами. Названия субагентов — рабочие роли, не разрешение запускать больше трёх работников одновременно.

| ID | Субагент | Входы | Ownership файлов | Outputs | Зависимость |
|---|---|---|---|---|---|
| S02-01 | Координатор | gate01, inventory | `backend/go.mod`, `go.sum`, `cmd/api`, `internal/config`, `docs/hellokitty/execution/S02-report.md` | module/config skeleton, frozen task boundaries, shared interfaces | этап01 |
| S02-02 | `store` | SQL01, interfaces01 | `backend/internal/store/**`, собственные store tests; migrations только coordinator merge | транзакционные repositories, expiry/nonce/idempotency queries, DB fixtures | S02-01 |
| S02-03 | `identity` | signing/challenge vectors01 | `backend/internal/auth/**`, `internal/device/**`, собственные unit tests | request proof, enrollment/token services через store interfaces | S02-01 |
| S02-04 | `profiles` | envelope/state machine01 | `backend/internal/profiles/**`, собственные unit tests | catalogue/issuance/revocation services, serializer, no-ready-before-observed | S02-01 |
| S02-05 | Координатор | результаты02–04 | `backend/internal/httpapi/**`, `cmd/admin/**`, shared config/main, contract changes при необходимости | routes/middleware/admin CLI, integration wiring | S02-02–04 |
| S02-06 | `api-security` | API compiled, contracts | `backend/tests/security/**`; остальные файлы read-only | ownership/replay/token/input attack suite, redaction report | S02-05 |
| S02-07 | `db-integration` | compiled services, test DB | `backend/tests/integration/**`; own fixtures subdir | реальные SQL concurrency/atomicity/rollback tests | S02-05 |
| S02-08 | `contract-client` | vectors + running isolated API | `backend/tests/contract/**`; Android read-only | HTTP/envelope conformance + Kotlin/Go vector report, gaps04 | S02-05 |
| S02-09 | Координатор | reports/tests | только согласованные corrections, report02 | интегрированный diff, commands/results, следующий gate | S02-06–08 |

## Волны параллельности

1. Координатор один создаёт module, interfaces, baseline fixture harness и ownership manifest. Только он изменяет `go.mod`, `go.sum`, migrations и общую OpenAPI; dependency requests работников передаются ему текстом.
2. Координатор + три работника `store`, `identity`, `profiles`. Store interfaces заранее заморожены; сервисы могут использовать boundary fakes, но не копировать store implementation. Изменение shared interface проходит координатора и синхронно сообщается всем.
3. Координатор один интегрирует HTTP/router/admin/config. API нельзя объявить готовым на основании отдельных branch/unit suites.
4. Координатор + три независимых test workers из S02-06–08. Каждый имеет отдельную test DB/schema и свой fixture namespace. Нагрузочный benchmark запускается отдельно, чтобы параллельные тесты не искажали результат и не переполняли сервер.
5. Координатор последовательно принимает corrections; один владелец меняет общий файл. Не запускать несколько миграций на одной DB или `go mod tidy` одновременно.

## Порядок реализации

1. **Bootstrap.** Config валидирует env/secret-file paths, listen address, DB DSN и limits; примеры содержат placeholders. По умолчанию API loopback, test DB закрыта внутри test network. Startup проверяет schema version, secrets, capabilities и graceful shutdown. Новый backend toolchain не заменяет глобальный Go.
2. **Persistence.** Применить baseline SQL к отдельной DB, реализовать параметризованные repositories. Business transaction принимает context и использует один connection/transaction. DB clock определяет expiry; cleanup bounded batches, не full-table rewrite. Миграции проверяются на empty DB и previous schema; rollback не удаляет пользовательские данные.
3. **Identity.** Валидация SPKI/capabilities/invitation, генерация challenge CSPRNG, limiter, challenge proof, atomic enrollment. Проверка подписи/nonce/token binding precedes mutation. Failed attempts ограничены, неизвестная invitation не раскрывает состояние.
4. **Sessions.** Opaque token issue/hash, expiry/grant status, refresh rotation/reuse detection, family-wide revoke и обработка сетевой неопределённости по contract01. Revoke проверяется и на уже выданных access tokens, не только на новом refresh.
5. **Async profile API.** Проверка capability и свежести node catalogue, atomic admission reservation, owner-key/body-hash idempotency, `202` operation. Нет ready до read-back. Для renew создаётся новая desired revision; старый профиль не переписывается раньше успешной замены. DELETE отзывает device/access/family и ставит node revoke event.
6. **Envelope.** Реализовать exact vector bytes/AAD/algorithms и key selection; ciphertext нельзя активировать для другого device. Результат finalization атомарен с observed revision и status check. На этапе02 использовать строго ограниченный fake observation fixture в тестах; production config не включает fake nodes/ready flags.
7. **HTTP/admin.** Настроить body/header/timeouts, raw body buffering once, strict decode, stable problems, owner checks и no-store для secrets. CLI выдаёт invitation локально без секретов в command args/logs; не создаёт публичный admin endpoint.
8. **Privacy/operations.** RequestId/status/duration/error stage, без browsing domains, UUID/token/cookie/body. Retention7д logs,30д anonymous aggregates,90д admin audit из общего плана, configurable cleanup. Metrics без device/IP/domain labels; readiness API/DB не выдаётся за payload readiness узла.
9. **Integration.** Выполнить suites ниже, dependency audit доступным tooling, bench100 concurrent registration/renewal requests на отдельной test DB. Цель p95≤500ms только для local DB API path; provisioning latency измеряет этап03.

## File manifest

Будущие files: `backend/cmd/api/main.go`, `cmd/admin/main.go`, `internal/{config,httpapi,auth,device,profiles,store}/**`, `tests/{contract,integration,security}/**`, `go.mod/go.sum`, `.env.example`, изолированный test-DB harness. `backend/migrations/*.sql` создаёт координатор02 по accepted data model01; store worker предлагает SQL через него, единственного writer. `contracts/*` — frozen inputs, изменения через versioned review. Report: `docs/hellokitty/execution/S02-report.md`.

Не менять Android UI, native locks, node-agent, чужие DB/Compose/nginx; это отдельные этапы. Точная разбивка backend пакетов утверждается S02-01, чтобы агенты не создавали дублирующие crypto/store layers.

## Проверки и реалистичные дефекты

Все будущие проверки ниже сейчас **NOT RUN**. Фактический report после реализации должен содержать команду, environment/version, статус, counts и путь отчёта.

| Проверка | Дефект, который она должна поймать |
|---|---|
| Golden RequestSigner Kotlin↔Go, изменённый body/path/token/algorithm | сервер хеширует reserialized JSON, забывает token hash либо принимает другую MGF1 policy |
| Два auth headers, invalid UTF-8/duplicate JSON keys, encoded slash/query, oversize/body2 | ingress и backend проверяют разные запросы или parser использует last-wins поля |
| Replay через два API process и после restart | nonce cache реализован локальной map и повторно принимает уже исполненную запись |
| 20 concurrent complete одного invitation/challenge | check-then-insert вне transaction выдаёт несколько grants вместо одного |
| Exact120s challenge expiry, ±120s signing boundary, nonce retention5m | use `<` вместо `<=`, API clock вместо DB либо nonce удаляется слишком рано |
| Refresh simultaneous/reused/lost-response cases по contract01 | неатомарная rotation создаёт две token branches; retry неверно отзывает законного клиента |
| A пытается получить profile/operation/renew B | query по opaque id пропускает device owner predicate |
| 10 concurrent profile requests + retries | cap2 считает лишь `active` и не учитывает pending reservations; duplicate outbox |
| Same idempotency key/same body/new nonce и key/different body | новая подпись ломает idempotency или другой body получает старый success вместо409 |
| Crash/rollback между lease reservation и outbox insert | lease существует без event либо outbox указывает отсутствующий credential |
| Late observed ack после DELETE, wrong node generation/revision | API активирует отозванный доступ или подписывает pending profile |
| Envelope tamper IV/expiry/device/algorithm/ciphertext + rollback | signature не охватывает metadata, cross-device decryption либо unsafe downgrade |
| Captured logs/errors/DB response cache | bearer, profile UUID, raw private material или SQL попали в diagnostic surface |
| Forged X-Forwarded-For, shared NAT, 429 retry | клиент обходит source limiter либо один пользователь блокирует общий NAT |
| Migration/DB restart/disk error/config-secret failure | success возвращается до commit; startup открывает API с несовместимой schema |

Рекомендуемые исполняемые проверки: `go test ./...`, `go test -race ./...`, `go vet ./...`, repository security tooling для новой зависимости, contract suite, test-DB integration suite. Конкретные команды fixtures фиксирует S02-01; не добавлять command, которого ещё нет, как будто он исполнен. PostgreSQL tests не заменять SQLite/in-memory map. API tests могут fake только node boundary; crypto проверяется настоящими библиотеками и vectors.

## Gate приёмки

- API compiled, unit/race/vet/security/real-PostgreSQL suites исполнены с прочитанным выводом; failed/unrun области перечислены.
- Atomic enrollment/refresh/idempotency/admission/desired+outbox доказаны concurrency tests. Все object requests проверяют owner и grant status.
- Client crypto vectors совпадают побайтно; unknown algorithm/weak key/tamper/replay/cross-device rejected. Нет raw bearer/secrets в DB/log snapshots.
- Имеется `202→pending` contract и usable profile только через проверенное observed event. На этом этапе production-ready VPN и проверенная настоящая нода **не заявлены**.
- Нет public listeners, изменений соседней инфраструктуры и условного Levik API fallback. Admin только management/local, одна invitation не создаёт более одного активного device.
- Final diff ограничен manifest; module versions/hash pinned, migrations reviewed. Bench report различает local API latency и future provisioning latency.

## Риски, rollback и передача этапу03

Основные риски: несогласованный refresh retry, Android8/9 OAEP compatibility, злоупотребление анонимным enrollment, migration несовместимость, ложный observed ack. Contract gaps закрываются до зависимой реализации. При failed gate остановить issuance; возвращать controlled unavailable/pending, не обходить подпись или owner checks. Rollback использует прошлый совместимый binary/schema в изолированном staging; destructive down migration, удаление volumes и очистка чужих DB запрещены. Возврат backup не включает grants/credentials без expiry/reconcile проверки.

Report02 для следующего агента содержит: schema/contracts hash и версии, exact env examples без secrets, module toolchain/dependencies, пути тестов и actual statuses, API state diagram, immutable operation/outbox payload format, ownership ревизий, encrypted secret/key handling, retention/retry defaults, known gaps. Отдельно передать точки finalization, где этап03 подтверждает node generation/revision/state; готовность API не означает готовность узлов. В отчёте не копировать реальные token/invitation/UUID/DSN.
