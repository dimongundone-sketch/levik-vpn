# Hello Kitty VPN: подробный промпт исправлений после повторного ревью G01

Дата: 2026-10-07 UTC. Основание — независимое ревью текущей рабочей копии и десять замечаний F01–F10. Это задание следующему агенту; создание файла не означает, что дефекты исправлены или gate повторно принят.

Передай агенту весь текст ниже. [Первый corrective prompt](STAGE_01_FIX_PROMPT.md) и старые отчёты сохраняются как история. Этот промпт задаёт оставшиеся исправления. [Промпт этапа 02](STAGE_02_AGENT_PROMPT.md) применяется после их проверенной приёмки.

## 1. Задание, ожидаемый результат и границы

Работай в `/root/projects/hellokittyvpn`. Исправь все десять замечаний F01–F10 в контрактах, SQL-спецификации, Python/Go reference models, Kotlin/JVM harness и доказательствах этапа 01. Добавь воспроизводимые regressions, выполни независимую проверку и подготовь достоверную передачу этапу 02.

Результат — согласованный G01 contract freeze, который действительно запрещает восстановление отозванного grant, потерю действующих refresh-токенов при cleanup и выдачу профиля без принятого observed state. Каждый дефект должен иметь конкретный изменённый артефакт и проверку поведения.

Полный HTTP backend, production migrations, worker/provisioner, подключение настоящих VPN-нод, Android API integration, RU direct, VK и deployment остаются этапам 02–08. Здесь нужны исправленные нормативные спецификации и исполняемые contract/model probes, включая собственный временный PostgreSQL. Такая SQL-проверка не означает выполнение G02.

Сохрани RequestSigner v1, публичный префикс `/v1`, profile envelope v2, бесплатную модель приглашений и очищенный Android UI. Не возвращай Levik backend, платежи, поддержку или подписки. Уточнения незавершённого challenge contract согласуй между потребителями и зафиксируй вместе с golden vectors.

## 2. Сначала зафиксируй факты и переоткрой приёмку

Прочитай user instructions, `/root/projects/AGENTS.md` и все применимые локальные `AGENTS.md`/`CLAUDE.md`, затем:

- `docs/hellokitty/README.md` и `docs/hellokitty/agent-plan/README.md`;
- `docs/hellokitty/agent-plan/01-contracts-and-security.md`, `02-backend-control-plane.md`, `03-node-provisioning.md`, `REPORT_TEMPLATE.md`;
- `docs/hellokitty/implementation-plan.md`, `STAGE_01_COMPLETION_REPORT.md`;
- `docs/hellokitty/execution/status.md`, `S01-fix-report.md` и три `S01-fix-*.md`;
- `contracts/security-contract.md`, mobile/node OpenAPI, profile/routing schemas, signing/envelope vectors;
- `backend/docs/data-model.md`, все существенные probes и реальные Kotlin consumers;
- `docs/hellokitty/agent-plan/STAGE_02_AGENT_PROMPT.md`.

На момент подготовки HEAD — `61b4c8fb1f63fd00efc629ae5117632edbf2dbe5`. В рабочем дереве уже изменены два documentation indexes и находится untracked `STAGE_02_AGENT_PROMPT.md`. Это существующая работа: не удаляй, не перезаписывай целиком и не присваивай себе её выполнение. Перепроверь фактическое состояние перед началом; если появился настоящий backend, учти его consumers и tests, сохрани чужие изменения.

Сделай snapshot HEAD/status/input hashes и ownership plan. В `execution/status.md` отметь G01 `IN PROGRESS` с причиной повторной приёмки, G02 `NOT RUN` с зависимостью от G01. Старый `VERIFIED` не переносится автоматически. Если независимый агент уже выполнил исправления, проверь фактический diff и evidence, а не воспроизводи изменения вслепую.

Историческое evidence последнего ревью:

- Семь freeze hashes, штатный conformance, crypto runner и `go test -race -count=1 ./...` прошли. Эти тесты не обнаруживали перечисленные ниже дефекты.
- Python reauth для revoked, expired и действующего grant возвращал `200`, создавал новый grant и продлевал expiry.
- Два дополнительных Go regressions — отказ reauth для revoked grant и сохранение существующего grant — завершились `FAILED` по ожидаемому дефекту.
- На собственном PostgreSQL `17.11 (Debian 17.11-1.pgdg13+2)` действующие refresh records: `1` до удаления старой операции и `0` после.
- SQL-шаги finalization позволили получить `cas_rows=0`, `credential_status=desired`, но `operation_status=ready` и один профиль.
- Расширенная проверка 17 обычных OpenAPI examples прошла; декодированный profile example не содержал девяти обязательных metadata fields.
- В Kotlin negative loop были только две исполняемые ветки из одиннадцати. Gradle при последнем ревью повторно не запускался.

PostgreSQL ревью использовал уже существующий image ID `sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f`, `--network none` и tmpfs без опубликованных портов. Контейнер был удалён. Это историческое описание, не permission использовать соседнюю БД и не обещание наличия того же image сейчас.

## 3. Распредели задачи между субагентами

Максимум координатор и три активных исполнителя. Каждый файл имеет одного writer. До первой волны координатор фиксирует решения о HTTP status, idempotency namespace, retention, signed time и at-rest key domains.

| Task ID | Writer | Разрешённые paths относительно корня | Ответственность |
|---|---|---|---|
| S01-R2-00 | Координатор | `docs/hellokitty/execution/S01-review-fix-inputs.md`, `S01-review-fix-ownership.md`, общие plans/status, `contracts/security-contract.md` | Preflight, решения, scope, единый security contract |
| S01-R2-A | `auth-state` | `contracts/probes/crypto/test_refresh_state_probe.py`, `contracts/probes/crypto/go/refresh_state_test.go`, `docs/hellokitty/execution/S01-review-fix-auth.md` | F01; согласованные auth transitions для F02/F04/F05/F09 |
| S01-R2-B | `persistence` | `backend/docs/data-model.md`, `contracts/probes/persistence/**`, `docs/hellokitty/execution/S01-review-fix-data.md` | F02–F06, executable SQL/AEAD probes и реальные PostgreSQL проверки |
| S01-R2-C | `wire-conformance` | `contracts/mobile-v1.openapi.yaml`, `contracts/probes/conformance/**`, `contracts/challenge-vectors.json`, `contracts/probes/crypto/test_challenge_probe.py`, `contracts/probes/crypto/go/challenge_probe_test.go`, `docs/hellokitty/execution/S01-review-fix-wire.md` | F08–F10, challenge vectors и межъязыковая проверка signed bytes |
| S01-R2-D | `kotlin-crypto`, вторая волна | `levik_vpn_android/app/src/test/java/org/hellokittyvpn/android/core/network/GoldenVectorsHarnessTest.kt`, выделенные новые contract test/helper files в этом test package, `docs/hellokitty/execution/S01-review-fix-kotlin.md` | F07, Kotlin envelope/challenge interoperability после фиксации C |
| S01-R2-E | Координатор | согласованные crypto corrections, `contracts/probes/crypto/run_all_probes.sh`, freeze manifests, общие reports/indexes/plan и completion report | Независимая интегрированная приёмка, freeze, передача02 |

В таблице сокращённые имена Markdown-файлов `S01-review-fix-*.md` относятся к `docs/hellokitty/execution/`. Coordinator-owned security contract исполнители меняют через предложенный diff/сообщение. `envelope-vectors.json` и существующие Python/Go envelope verifiers первоначально read-only; если новые проверки находят дефект, координатор выделяет конкретному исполнителю точные файлы и интегрирует изменение. Общий crypto runner и Go/Gradle manifests имеют единственного writer.

Волна 1: A/B/C параллельно после согласования ключевых решений. Волна 2: D занимает освободившийся слот, A/B исправляют интеграционные замечания. Финальные Gradle и тяжёлые PostgreSQL проверки сериализуются. Ни один субагент не получает право одновременно переписывать все `contracts/**`.

Каждый исполнитель сначала сохраняет reproducer/expected failure, затем исправляет поведение и повторяет тот же check. Его отчёт содержит command, actual output/counts, file evidence, ограничения и связь с F-ID. Координатор проверяет результат самостоятельно.

## 4. F01 / P1 — reauth сохраняет grant и не восстанавливает отозванный доступ

Источники: `contracts/probes/crypto/test_refresh_state_probe.py`, `handle_reauth` около строки240; `contracts/probes/crypto/go/refresh_state_test.go`, `handleReauth` около257. Сейчас оба вызывают enrollment; Go дополнительно использует фиксированное время.

Воспроизведи три отдельных случая: active device + revoked grant, active device + expired grant и active device + короткий действующий grant. На старом коде reauth возвращает200 и создаёт новый grant. Это security defect модели, а не доказательство существующего публичного backend.

Исправь transitions:

1. Reauth находит существующие device/grant и проверяет ownership, active status, expiry относительно одного переданного/injected clock.
2. Revoked/expired/missing grant либо disabled/revoked device — отказ; никакой новой family, grant, device или cache.
3. Для действующего grant сохраняются **тот же grant ID и исходный expiry**. Invitation и его usage не изменяются.
4. В одной атомарной границе отзываются прежние token families/access tokens и создаётся одна новая family, привязанная к тому же grant.
5. Family absolute TTL30d; access15min и refresh expiry ограничены family/grant. Храни необходимые expiry/bindings в моделях явно.
6. Не освобождай lock между revoke и issuance. Вынеси внутренние helpers, если нужен общий код, без повторного захвата обычного mutex и deadlock. Reauth не вызывает публичный enrollment.
7. Exact lost-response retry одного complete/reauth operation в120s возвращает тот же result без ещё одной family rotation.

Минимальные regressions в Python и Go: revoked grant, expired grant, missing grant, valid short grant без продления, grant ID неизменен, старые tokens остаются revoked, same-second reauth, fingerprints с одинаковым prefix, retry после revoke, конфликт operation/body.

Дополнительно запусти конкурентный **первый** refresh с барьером до rotation; текущий Go test сначала вращает токен последовательно, затем параллельно читает cache. Проверь одну successor branch/rotation и один logical response. Добавь reauth против DELETE/grant revoke: после terminal revoke нельзя получить активное устройство/семью. Детерминированные barriers/hooks предпочтительнее sleeps.

Приёмка F01: обе reference models запрещают обход grant и согласованы с нормативной атомарностью. `go test -race` без failed logical assertions не заменяет эту проверку. Если cache модели — dict/map, называй его logical recovery model, а не реализованным encrypted storage.

## 5. F02 / P1 — retention operations не удаляет живые токены

Источник: `backend/docs/data-model.md`, `refresh_tokens.issuance_operation_id` около267 и retention `operations` около898. FK `ON DELETE CASCADE` удаляет refresh records при purge операций через48h.

В собственном PostgreSQL воспроизведи: device/grant/family, ready issuance operation старше48h, refresh token с оставшимся сроком27d. Удали операцию разрешённым cleanup и проверь, что текущий DDL удаляет токен. Затем зафиксируй regression и исправь модель.

Предпочтительное решение: сделать ссылку на краткоживущую issuance operation nullable с `ON DELETE SET NULL`, сохраняя долговременные `issuance_client_op_id/body_hash` и `consumed_by_client_op_id/body_hash` непосредственно в token record до token/family retention. Consumption/cache algorithms должны корректно работать после исчезновения operation FK. Либо выбери и обоснуй другое решение, которое сохраняет token lifetime, late-retry classification и bounded cleanup; нельзя просто отключить cleanup или сделать токены двухдневными.

Проверь все связанные FK/cache/cleanup зависимости. `RESTRICT` без явного алгоритма очистки только меняет потерю токена на неработающий purge. Recovery ciphertext120s и idempotency state48h остаются отдельными lifetimes.

Regressions: live successor сохранён; owner/family/expiry/bindings не изменились; consumed token после120s и после48h всё ещё отличает exact retry от new-op reuse; changed body остаётся конфликтом; token purge после установленного retention действительно работает. Не используй реальный48h sleep: отдельные fixtures/injected clock.

Приёмка F02: actual PostgreSQL подтверждает сохранение действующих токенов и классификации retries при очистке operations/cache.

## 6. F03 / P1 — ready только после успешно принятого observed evidence

Источник: finalization в `backend/docs/data-model.md` около838–879. Сейчас CAS может затронуть0 строк, но profiles/ready вставляются безусловно. Текущая спецификация также не связывает accepted generation с текущей нодой и не проверяет credential/grant expiry.

Сначала воспроизведи SQL-шаги: credential desired_revision2, ответ revision1, node current generation2, ответ generation1. На старой модели CAS затрагивает0 строк, но возникает профиль/ready. Подготовь исполняемый contract probe, а не тест только ожидаемых JSON fixtures.

Исправь контракт и транзакционный алгоритм:

- operation → credential → device/grant/node связаны явно; входные IDs worker не выбирают произвольного владельца;
- accepted evidence относится нужному credential, node/inbound, revision, expiry и **нынешнему** generation; source/method/read-back semantics определены node contract;
- внутри транзакции перечитываются и защищаются от конкурирующего revoke актуальные device/grant/credential/node states; consistent lock order используется всеми конкурирующими переходами;
- отсутствие active grant обрабатывается явно, в том числе когда JOIN не вернул строку;
- revoked/disabled/expired device/grant, revoked/expired credential, отменённая/terminal operation, неверный node/generation/revision/expiry не допускают ready;
- CAS/`RETURNING` должен подтвердить **ровно нужное принятое изменение**. При0 rows нет нового envelope и нет ready;
- duplicate same accepted evidence не создаёт второй профиль; старый ack после revoke или новой generation не оживляет credential;
- failure evidence можно сохранять отдельно с безопасной диагностикой. Оно не превращается в success и не выдаётся за authoritative core apply.

В атомарной finalization находятся accepted observed state, профиль, operation result и outbox state. Network I/O и actual node read-back остаются вне DB transaction и production worker03. Только успешный SQL update тоже не является доказательством Xray apply.

Проверь positive accepted evidence, wrong revision, wrong generation/node/credential/owner, expired grant/credential, no-row JOIN, late ack после DELETE, duplicate ack и out-of-order ack. Два PostgreSQL connections с управляемым interleaving должны подтвердить отсутствие ready-after-revoke. Для отказов проверяй profile count0, operation state и отсутствие resurrection, а не только error code.

Приёмка F03: failure при любом несоответствии не создаёт пригодный профиль; accepted duplicate идемпотентен. Real core evidence проверяется03, текущий probe подтверждает только contract/SQL state machine.

## 7. F04 / P1 — recovery cache записывается один раз до commit

Источник: `backend/docs/data-model.md` около791–801. После правильного cache INSERT/COMMIT находится второй INSERT с wire clientOperationId вместо internal operation FK.

Удали лишний post-commit блок. Согласуй distinct internal `operations.id` и wire `client_operation_id`. Нормативный алгоритм должен атомарно создавать consumption binding, successor, access record, operation и единственный encrypted recovery response, затем commit и только потом success response.

В PostgreSQL probe используй заведомо **разные** UUID этих двух IDs; одинаковые значения в fixture могут скрыть дефект. Проверь FK, unique cache binding и ownership. Response cache должен однозначно ссылаться на операцию и тот input token/body, которые он восстанавливает; определение `token_hash` в cache нельзя оставлять двусмысленным.

Regressions: обычная rotation, потерянный HTTP response после commit, exact retry120s, fault до cache insert и до commit, duplicate request. До commit fault оставляет ни consumption, ни successor, ни half-cache. После commit exact retry возвращает тот же result. В SQL/model после commit не появляется обязательной дополнительной записи, ошибка которой превращает success в ложный failure.

Приёмка F04: одна cache record и одна successor branch на операцию, восстановление без дополнительной rotation.

## 8. F05 / P1 — persisted idempotency связывает operation с исходным запросом

Источник: `operations` в `backend/docs/data-model.md` около492. Сейчас сохранены clientOperationId/type/status, но нет raw body hash. Для profile/renew нельзя надёжно различить exact retry и изменённый запрос.

Сохрани авторитетный binding: owner/internal device, wire clientOperationId, operation type/method/encoded path и lowercase SHA256(raw body). Значения request timestamp/nonce не входят в idempotency binding: retry создаёт новый proof при том же запросе.

Предпочтение — существующий global-per-device namespace `UNIQUE(device_id, client_operation_id)`; повтор ключа на другом endpoint/type/path считается409. Если выбран иной namespace, синхронизируй contracts/models/tests до freeze. Нельзя молча поменять уникальность только в DDL.

Определи связи operation с целевыми resource IDs, expected/desired revision и result profile ID, необходимые для polling/finalization. Не складывай исходный refresh/invitation body с секретами в plaintext DB. Для server-generated revoke events не требуй несуществующий клиентский Idempotency-Key у bodyless DELETE; опиши отдельную естественную дедупликацию terminal revoke.

В одной транзакции reserve/desired/outbox/operation сохраняется единый binding. Exact retry возвращает прежнюю операцию; тот же key с изменённым nodeId/engine/rulesVersion/expectedRevision или другим owner/type/path не создаёт нового side effect. Чужой device не получает чужой result.

Regressions: exact retry; same key changed node/revision/body bytes; cross-endpoint reuse; cross-owner request; concurrent identical/changed-body requests. Проверяй counts operations/reservations/outbox/credentials и неизменность первого запроса. Повтор с новым access token того же действующего owner не меняет body binding.

Приёмка F05: persisted state содержит достаточно данных для точного409; это подтверждено actual SQL/model queries и сохранением одного side effect.

## 9. F06 / P1 — UUID VLESS и relay credentials защищены at rest

Источники: `credentials.uuid UUID NOT NULL` около405 и plaintext `uuid` в outbox payload около565. Персональный VLESS UUID является секретом авторизации. UUID записи БД, public profileId и VLESS secret — разные сущности.

Исправь модель и key contract:

1. Raw tunnel secret хранится как AEAD ciphertext с storage keyId/format version. Выбери стандартный AES256-GCM, fresh nonce12B, tag16B, отдельный key purpose. Key material находится вне DB/Git/logs.
2. AAD связывает ciphertext с назначенными credential/device/node/revision/generation и другим необходимым context. Зафиксируй exact bytes/version; замена контекста или ciphertext должна приводить к отказу.
3. Для uniqueness/read-back сравнения используй безопасный fingerprint/blind index с описанным purpose/доменом. Raw секрет не дублируется в индексируемом plaintext столбце.
4. Outbox содержит credential reference, необходимые nonsensitive IDs/revision и bindings; UUID/password/private material разрешаются доверенным worker в памяти перед RPC. Payload/errors/evidence не сохраняют их открыто.
5. Actual core read-back может содержать персональный UUID: проверяй evidence на доверенной границе, а persisted evidence редактируй/хешируй по согласованной политике. Не принимай контрольную сумму как самостоятельное доказательство actual apply.
6. Cache encryption, credential storage, server profile signing, challenge signing, routing/OTA и node management имеют разные purpose/key material.
7. Backend instances после restart должны читать ещё живой encrypted cache/credential с разрешённым key ring. Определи rotation/retirement/backup сроки; слово «ephemeral memory» не означает случайный новый key на каждый рестарт.

Добавь AEAD probe на стандартной доступной криптобиблиотеке: successful decrypt, wrong key/purpose/context, tamper tag/ciphertext, key rotation с ещё действующим старым ciphertext. Синтетический secret вставляется только через шифрование; проверь dump собственной DB и outbox на отсутствие plaintext и его обычных encoded forms. Ключ в dump не находится. Test-only fixture keys явно маркируются и никогда не становятся production defaults.

Приёмка F06: data model/outbox/security contract согласованы, исполняемая проверка подтверждает шифрование и отказ tampered context. Готовый production secret store принадлежит02/08; его наличие сейчас не заявляется.

## 10. F07 / P2 — Kotlin проверяет весь envelope pipeline и весь negative corpus

Источник: `GoldenVectorsHarnessTest.kt`, loop около192–230. Условия по ID обрабатывают только neg_01 и neg_04, остальные9 проходят без проверки. Позитивное decrypt тоже не проверяет server ECDSA signature.

Убери выбор проверяемой логики по vector ID/type/expectedError. Все envelopes проходят **один общий verifier pipeline**; ожидаемая ошибка используется только в assert. Target device key/capability, deterministic now и lastObservedRevision из test context являются inputs.

Проверь существующие eleven IDs:

- `neg_01_tampered_ciphertext`;
- `neg_02_tampered_iv`;
- `neg_03_tampered_metadata_aad`;
- `neg_04_tampered_wrapped_key`;
- `neg_05_cross_device_mismatch`;
- `neg_06_expired_credential`;
- `neg_07_rollback_revision`;
- `neg_08_bad_server_signature`;
- `neg_09_unauthorized_signing_key`;
- `neg_10_missing_signature`;
- `neg_11_high_s_signature`.

Pipeline: bounded strict wire/base64url/UTF-8 → trusted purpose/key selection и ES256 signature с exact signed bytes/strict DER/low-S → metadata schema/owner/expiry/revision/capability → RSA-OAEP unwrap → AES-GCM AAD/tag → decrypted payload/schema/semantic bindings. Preliminary metadata parsing допускается только для выбора allowlisted key, без доверия её данным до signature verification.

Для обоих positive vectors проверяй **весь** pipeline, equality IDs/revision/issuedAt/credentialExpiresAt header↔decrypted payload и ожидаемую plaintext структуру. Алгоритм не переключается на legacy из произвольного envelope при modern device policy. Missing/untrusted/bad/high-S signature отвергается до activation/decrypt.

Используй реальные RequestSigner canonical bytes и JCA/platform primitives. Если полного verifier ещё нет в production Kotlin, создай один минимальный pure Kotlin contract helper в согласованном test source set; он не должен кодировать expected outcomes fixtures. Явно запиши, что app runtime integration остаётся04. Нельзя объявлять существующий `HybridProfileDecryptor` полноценным v2 signature/anti-rollback verifier по результатам отдельного теста.

Добавь assertion/parameterization, что обработан весь corpus, без silent skip/default success. Новые IDs должны автоматически проверяться либо приводить к явной ошибке, а не исчезать из покрытия.

Запусти обе distribution test tasks с конкретным harness. При новых тестах signed challenge включи их в обе JVM suites. Счётчик JUnit methods3 сам по себе не равен количеству проверенных vectors; report содержит фактические2 positive и минимум11 negative cases.

Приёмка F07: Play/Direct реально выполнили все cases, нет обхода signature gate. Metadata с semantic defect за signature gate использует действительно подписанный тестовый envelope, а не bypass.

## 11. F08 / P2 — signed serverTime имеет complete wire/trust contract

Источник: `ChallengeResponse` в mobile OpenAPI около810. Есть serverTime/keyId, нет signature; additionalProperties=false. Упоминание «signed» в плане не реализует механизм доверия.

Координатор до изменений C/D фиксирует конкретные fields/bytes/purpose/bootstrap/rotation/error policy. Рекомендуемая исходная форма, которую можно уточнить только согласованно:

- Сохранить challengeId, server nonce32B, expiresAt120s, serverTime, keyId.
- Добавить `purpose="challenge"`, `signatureAlgorithm="ES256"`, `requestBodyHash` и строгую unpadded base64url `signature`.
- В `ChallengeRequest` определить свежий random `clientNonce`16B, чтобы hash исходного request связывал ответ с уникальным запросом, SPKI, mode, capabilities и invitation context. ClientNonce и server challenge nonce — разные поля.
- Dedicated challenge signer P256/SHA256, strict DER/low-S; никакого profile/routing/OTA signing key reuse.
- Signed UTF-8 bytes, если выбран этот вариант:

```text
HKVPN-CHALLENGE-V1
challenge
ES256
<keyId>
<challengeId>
<serverNonce>
<expiresAt>
<serverTime>
<requestBodyHash>
```

Для этого варианта последняя строка тоже заканчивается одним LF; всего9 строк и9 LF. Поля ASCII, timestamps канонические UTC seconds, hashes lowercase hex. Дай точное формальное определение в security contract и golden intermediate bytes/hash. Request hash считается над фактическими raw request bytes, не reserialized object. Изменение fields/serialization требует одновременных vectors/verifiers/schema edits.

Это предложенная форма **нового незавершённого contract**, а не утверждение о существующей реализации. Если установленный проектный pattern лучше, координатор фиксирует другое столь же конкретное решение до writer work. Не оставляй format на усмотрение будущего backend02.

Trusted keyId разрешается только в локальном purpose-scoped public key ring. Ответ не назначает себе новый доверенный public key. Опиши initial trust bootstrap к04 и последующую authenticated rotation; test public/private keys отделены от будущих operational keys.

Verification сверяет requestBodyHash с активным свежим запросом, подпись/key purpose, TTL/time ordering и request lifecycle. Только затем допускается bounded clock adjustment. Зафиксируй численный допустимый drift/expiry/skew policy, ошибки чрезмерного drift и сохранение anti-rollback floors; не отключай TLS validation и не компенсируй clocks без проверенного serverTime. Replay другого request nonce не принимается как fresh time.

Уточни endpoint exceptions: create challenge является public без RequestSigner proof; complete/refresh требуют proof с empty token hash. Security headers section не должен ошибочно требовать пять signing headers для первичного challenge.

Добавь shared challenge vectors: positive canonical bytes/signature, tampered serverTime/expiresAt/nonce/request hash, wrong purpose/key, unknown keyId, missing signature, high-S, replay response другого request, invalid TTL и excessive clock correction. Проверка Python/Go/Kotlin должна вычислять фактический результат. Wire schemas и examples содержат все необходимые поля.

Приёмка F08: future backend/client могут независимо реализовать один wire contract без догадок о доверии и bytes; fixed-time cross-language tests проходят.

## 12. F09 / P2 — единый REFRESH_RETRY_EXPIRED HTTP status

Mobile OpenAPI около226 задаёт410, security contract/state probes —401. Выбери и зафиксируй один status для **позднего exact retry**, сохранив отдельные invalid/revoked/reuse outcomes.

Предпочтительное решение — `410 REFRESH_RETRY_EXPIRED` как уже объявленный публичный OpenAPI ответ. Тогда обнови security prose/diagram/table, обе reference models/assertions, SQL pseudo-return, связанные планы и stage02 handoff. Если выбран401, обоснуй и синхронно измени OpenAPI; не поддерживай обе трактовки «для совместимости» без реального потребителя.

Проверь elapsed меньше120s, ровно120s и больше120s с одним согласованным boundary rule; cache cleanup использует ту же границу. Отдельно проверь expired grant/family/token и new-op consumed reuse. Поздний exact retry не отзывает family и направляет в reauth существующего действующего grant. Changed body возвращает409 согласно согласованной precedence, независимо от cache removal.

Приёмка F09: точный status/code одинаков во всех contracts, models, vectors/examples/tests и передаче02. Поиск старых значений отделяет исторические reports от текущих нормативных файлов.

## 13. F10 / P2 — conformance проверяет actual envelope, metadata и payload

Источники: fake envelope example mobile OpenAPI около454; `contracts/probes/conformance/conformance_test.py`, `check_openapi_examples` около152. Current check валидирует outer strings и только purpose/schemaVersion декодированной metadata.

Замени example настоящим test envelope из согласованного positive golden vector или воспроизводимо сгенерируй новый с явно test-only keys. Нужны все required metadata fields, настоящие wrappedKey/GCM tag/ECDSA signature и совместимый decrypted payload. Не добавляй placeholder signature, которую verifier никогда не проверяет.

Расширь conformance:

1. Local refs и format проверяются для всех применимых request/response examples и component references. Remote refs не скачиваются произвольно.
2. Positive envelope vectors **расшифровываются**, после signature verification. Проверяется actual decrypted bytes/schema, не только отдельно записанное `plaintext` в fixture.
3. OpenAPI envelope example проходит outer schema, decoded protected schema со всеми required fields, signature/trusted test key, unwrap/GCM и actual decrypted payload/schema.
4. Metadata/payload deviceId/accessId/profileId/revision/issuedAt/expiry совпадают; algorithm/capability/owner constraints соблюдены.
5. Криптографические примеры входных SPKI используют действительные test public keys, если pipeline заявляет их пригодными для enrollment.
6. Missing keyId/signatureAlgorithm/encAlgorithm/deviceId/accessId/profileId/profileRevision/issuedAt/credentialExpiresAt вызывает ненулевой exit code.
7. Tampered signature/ciphertext, bad UUID/revision, mismatched payload и unresolved refs дают failure. Отключение FormatChecker/подписи/проверки required недопустимо.

Добавь meaningful regression для нынешнего `{"purpose":"profile","schemaVersion":2}` example header и incomplete outer envelope. Mutation-style проверки выполняй на in-memory copies либо отдельных test fixtures: не повреждай нормативные файлы ради негативного теста. Conformance не должен лечить/sanitize вход перед проверкой.

Приёмка F10: нынешний неполный example реально отвергается; исправленный example и оба positive golden vectors проходят полный путь. Code assertions проверяют данные, а не ожидаемый error label из fixture.

## 14. Исполняемые PostgreSQL probes и безопасная среда

Добавь небольшой `contracts/probes/persistence/` harness и documented command. Это contract verification01; `backend/cmd/api`, production migration manager и настоящий provisioner в этой задаче не создаются.

- PostgreSQL — собственный disposable instance, предпочтительно existing pinned image по ID/digest. Зафиксируй actual server_version; не считай label17 доказательством patch.
- Подходит `--network none` с Unix-socket client внутри собственного контейнера, tmpfs, без host mounts и published ports. Remote PostgreSQL, соседние containers/volumes не используются.
- Harness владеет names/labels/container IDs и чистит только своё. Нет global prune/flush/restart/firewall edits.
- DDL должен соответствовать исправленной нормативной модели. Допустима генерация test schema из модели либо один authoritative executable schema artifact, на который модель явно ссылается. Не поддерживай две вручную расходящиеся DDL-копии.
- Все tables создаются в FK dependency order; pgcrypto/UUID helper проверяется фактически. SQL pseudocode `RETURN HTTP` не объявляется runnable SQL.
- Для важных transitions есть воспроизводимые исполняемые queries/functions в probe; текст модели и probe согласованы. Ни fixture engine, ни отдельная «правильная» модель не подменяют неверные SQL instructions.
- Cleanup bounded, допустимый PostgreSQL синтаксис. Существующие `DELETE ... LIMIT` snippets замени CTE/subquery batch purge с корректными keys/locks.
- Suites действительно выполняются, а не skipped при недоступной DB. Недоступная среда означает `UNABLE TO RUN` и незакрытый обязательный критерий.
- Concurrency через разные PostgreSQL sessions и barriers проверяет реальную изоляцию/constraints. Fake Xray observed evidence используется только как явно test-only boundary input; core implementation не заявляется.

Минимум SQL checks: F02 retention/FK, F03 wrong/late/duplicate observed state и competing revoke, F04 cache atomicity/distinct IDs/rollback, F05 idempotency binding/concurrent side effects, F06 отсутствие plaintext secret. Зафиксируй fixture/model limitations и exact run command.

## 15. Общая проверка и синхронизация

Первый запуск каждого важного regression должен показать ожидаемый дефект старого behavior, если он ещё существует. Затем исправление и повтор той же проверки. Если пользователь/другой агент уже исправил участок, проверь meaningful assertions и не создавай искусственный failing state.

Выполни после интеграции:

```bash
python3 contracts/probes/conformance/conformance_test.py
bash contracts/probes/crypto/run_all_probes.sh
```

В `contracts/probes/crypto/go`:

```bash
go test -race -count=1 ./...
go vet ./...
```

В `levik_vpn_android`:

```bash
./gradlew testDirectDebugUnitTest --tests 'org.hellokittyvpn.android.core.network.GoldenVectorsHarnessTest'
./gradlew testPlayDebugUnitTest --tests 'org.hellokittyvpn.android.core.network.GoldenVectorsHarnessTest'
```

Для новых challenge/helper tests запусти соответствующие targeted tests обоих variants; при влиянии на shared production helper расширь проверки по риску. Зафиксируй точные task paths, используемый JDK/SDK/toolchain и actual XML case counts. Нельзя ссылаться на старый XML как на свежий запуск изменённого теста. Общие Gradle/ADB процессы не гоняются одновременно.

Координатор включает новые challenge/persistence checks в documented runner или отдельный reproducible aggregate command; базовый crypto runner не должен молча пропускать новые обязательные checks. `go test -race` с глобальным mutex не доказывает DB isolation; SQL probe не доказывает реальный core apply; Kotlin/JVM не доказывает Keystore на физическом Samsung.

Security audit выполняется доступным настроенным tooling, если затронуты security boundaries/dependencies. Не устанавливай scanner ради формальной отметки и не обновляй зависимости без необходимости. Проверь final dependency/lock diff, если он появился.

## 16. Новая заморозка, отчёты и gate

Сначала интегрируй contracts/models/tests, прочитай фактические outputs и проверь final diff. Только потом обновляй freeze. Пересчёт SHA без исправления behavior не закрывает F-ID.

Сохрани оба существующих `contracts-freeze.sha256` manifests согласованными. Раздели wire/data freeze и source/probe manifest при необходимости. В accepted input coverage входят mobile/node/profile/routing/security contracts, signing/envelope/challenge vectors, data model, необходимые probe sources/build metadata, Kotlin harness/helper и существенные consumers. Paths/version/toolchain однозначны; mutable status/report не входят в самоссылочный manifest.

Новые reports:

- `docs/hellokitty/execution/S01-review-fix-inputs.md`;
- `docs/hellokitty/execution/S01-review-fix-ownership.md`;
- `docs/hellokitty/execution/S01-review-fix-auth.md`;
- `docs/hellokitty/execution/S01-review-fix-data.md`;
- `docs/hellokitty/execution/S01-review-fix-wire.md`;
- `docs/hellokitty/execution/S01-review-fix-kotlin.md`;
- `docs/hellokitty/execution/S01-review-fix-report.md`;
- `docs/hellokitty/STAGE_01_REVIEW_FIX_COMPLETION_REPORT.md`.

Общий report содержит F01–F10 matrix: original defect, files changed, regression before/after, command/tool versions/counts/exit code, concrete evidence path, limits, accepted/unresolved decision. Используй `VERIFIED` / `FAILED` / `NOT RUN` / `UNABLE TO RUN` точно по их значениям. Старые S01 reports пометь как superseded historical evidence ссылкой на новый report; не стирай их и не переписывай старые результаты как будто это новый запуск.

Обнови maintained security/data-model sections, statuses/indexes и handoff02. Из нового accepted input должны следовать конкретные SQL/crypto contracts, а не прежние противоречивые draft snippets. `STAGE_02_AGENT_PROMPT.md` получит новые решения о retention/FK, persisted body binding, safe finalization, encrypted credentials/outbox, challenge signature и HTTP status. Публичные API/production capabilities остаются будущими.

G01 может стать `VERIFIED` только когда:

1. Все F01–F10 исправлены с исполняемыми подтверждениями там, где проверяется поведение.
2. Python/Go reauth сохраняют действующий grant, отказывают revoked/expired и проходят concurrent regressions.
3. Actual PostgreSQL подтверждает retention, idempotency/cache atomicity и запрет ready для rejected observed evidence.
4. At-rest модель/AEAD probe не сохраняют tunnel secrets открыто в DB/outbox.
5. Signed challenge/time contract полностью определён и Python/Go/Kotlin согласованы по bytes/trust/error policy.
6. Kotlin обоих variants реально проверил минимум2 positive/11 negative envelope cases через общий pipeline.
7. Полный conformance отвергает прежний fake example и принимает реальные согласованные fixtures.
8. Outputs/diffs/manifests проверены координатором, reports/limitations/handoff02 актуальны.

Если обязательный критерий failed либо unavailable, G01 остаётся `IN PROGRESS`, G02 `NOT RUN`, blocker описывается конкретно. Исправь все независимые доступные части. Не закрывай gate количеством Markdown-файлов, формальным100% старых probes или автоматически перенесённым статусом субагента.

После успешной фактической работы выполни применимые требования `/root/projects/AGENTS.md` об architecture log: изменённые data contracts/security approach должны быть отражены в Obsidian inbox с реальными результатами и TODO. Не заявляй обработку librarian без evidence.

Не создавай commit/PR/release/deploy автоматически. В финале сообщи список реально закрытых F-ID, ключевые executed checks, G01/G02 status, путь к completion report и оставшиеся ограничения. Заверши передачей к [этапу 02](STAGE_02_AGENT_PROMPT.md); выполнение полноценного backend в это задание не входит.

