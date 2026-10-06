# Этап 06 — экспериментальный VK-транспорт для белых списков

Статус реализации этапа: **NOT RUN**. Наличие исходников и успешная native-сборка из [отчёта очистки](../app-cleanup.md) не подтверждают соединение через VK или российскую SIM. План предназначен для будущего главного агента; сейчас VK-аккаунты, API и серверы не подключаются.

## Цель и результат

Встроить альтернативный transport в существующий Android VPN и собственный provisioning, проверить его на реальных разрешённых сетях. Сначала довести согласованную пару **WDTT Plus v15 client/server из репозитория**. Сравнение внешних проектов должно объяснить конкретный технический выбор, а не заменить работающий код без измерений. Результат — управляемый short-lived relay-профиль, корректная авторизация, защищённый полезный трафик и отчёт с подтверждёнными и неподтверждёнными ячейками.

Один SNI, список «разрешённых» IP и успешный TLS handshake не являются доказательствами обхода. Полное отсутствие IP-связности transport не исправляет. Нельзя обещать общероссийскую доступность или отсутствие будущей блокировки exit IP.

## Входы и зависимости

- [Этап 01](01-contracts-and-security.md): утверждённые profile schema, capability, signing/expiry policy и threat model. Названия ниже относятся к согласованным контрактам; при расхождении агент сначала обновляет контракт через главного агента.
- [Этап 03](03-node-provisioning.md): работающие apply/status/revoke, ревизии, локальный expiry, private control plane и read-back. [Этап 04](04-android-integration.md): Android получает собственный encrypted профиль. [Этап 05](05-ru-routing-and-networking.md): сетевые инварианты и packet-capture стенд.
- Исходная пара: `levik_whitelist_relay/source/upstream.lock.json`, WDTT commit `3038b8ddc0306feb21d3c3624e2bc1c3c14639ad`, GPL-3.0-only. Native toolchain зафиксирован в `source/tools.lock`: Go 1.26.5, NDK 29.0.14206865, API 26, три ABI, alignment 16384. Версии повторно сверяются с lock при выполнении; «latest» не подставлять.
- Существующие private node contracts: `levik_whitelist_relay/contracts/openapi.yaml`, `node-profile.schema.json`, `golden-hmac-v1.json`. Они исторические v1; переименование заголовков и profile vocabulary требует versioned rollout обеих сторон.
- Человеческие входы для live gate: собственный VK-аккаунт владельца/добровольца, подтверждённое право использовать выбранную интеграцию, разрешённая тестовая сессия/звонок, тестовый зарубежный exit и собственные российские SIM. Не получать чужие cookies, не покупать неизвестные tokens, не подключать соседний WDTT или чужой кабинет.
- Если этих входов нет, выполнять source audit, unit/fault tests, local IPC, build, fake signaling на внешней границе и review. Отметить live-проверки **UNABLE TO RUN** с конкретным отсутствующим входом; не имитировать результат SIM-теста.

## Контракты и инварианты

1. **Один `HelloKittyVpnService`, один TUN.** Transport реализует существующий `TunnelEngine`/Direct registry и использует relay adapter; отдельное приложение VPN из GitHub не запускается. В Play relay отключён текущей distribution policy; изменение доступности Play — отдельное решение этапа 08.
2. **Profile boundary.** Внешний профиль содержит `profileSchemaVersion`, `profileId`, `accessId`, `deviceId`, `credentialExpiresAt`, `engine`, `bootstrap`, `rulesVersion`, signature. Mapper переводит его в текущий native contract. Relay metadata валидирует `id`, `displayName`, `countryCode`, `host`, `port`, `turnFrontSni`, `transport=turn-dtls`, pinned 32-byte `serverPublicKey`, 1–4 `turnHashes`; клиент не выбирает произвольный endpoint/URL через HTTP API. `turnFrontSni` — имя внешнего TLS front, не адрес собственного exit.
3. **Два разных уровня версии.** Android/native IPC сейчас `RELAY_CONTROL_VERSION=2`, message cap 64 KiB; node HTTP сейчас `/internal/v1/leases/{apply,rotate,revoke,status}` с mTLS/HMAC. Номер IPC не означает совместимость WDTT WRAP, DTLS, SFU или node HTTP. Не смешивать framing, password derivation и encryption внешних проектов.
4. **Startup sequence.** `WAIT_CONTROL_READY → WAIT_PROTECT_LISTENING → WAIT_PROTECT_READY → WAIT_PROXY_PLAN → PREPARED → RUNNING`; неожиданный event/state — fail-closed. TUN не считается рабочим по одному `ready`/`proxy_plan`. Полезный payload проходит после создания внутреннего защищённого канала.
5. **Socket ownership.** Для всех TURN, VK signaling/auth/captcha и physical DNS sockets native передаёт FD через защищённый IPC; Android подтверждает `VpnService.protect()` и `Network.bindSocket()` до connect. Ack идентифицирует текущий request/socket/session. Нет ack/underlying Network — connect не выполняется. Смена сети отменяет старое поколение, закрывает старые FD и создаёт новое; не оставляет foreign traffic на direct fallback.
6. **VK identity отделена от VPN identity.** Cookie/session принадлежат Android private context; backend не хранит пароль VK и не раздаёт общий VK token. TURN credentials минимально живут в памяти, ограничены реально известным сроком; двухминутный текущий cache не заменяет проверку upstream expiry. Нужны очистка на logout, origin allowlist, запрет token/cookie в logs/clipboard/argv/environment/analytics. Cookies профиля не переносить из браузера без явного действия владельца.
7. **WebView isolation.** Аудировать текущий `RelayVkAccountAuth.kt`: CookieManager общий внутри процесса, `bindProcessToNetwork()` затрагивает весь процесс. Зафиксировать ADR: изолированный auth process/data directory, если совместим с minSdk26, либо ограниченный lifecycle/auth mutex без параллельного VPN/API network activity и явная очистка. Не утверждать изоляцию только по отдельному Activity. Все exit/cancel/error/timeout пути восстанавливают предыдущую process binding; восстановление исчезнувшей сети имеет проверенный fallback.
8. **Inner encryption.** В согласованном fork сохранять внутренний WireGuard-подобный защищённый канал с закреплённым server key, независимый от внешнего TURN TLS/DTLS. Документировать фактический handshake и key pin из source/test. Если SFU передаёт иной framing, он обязан переносить выбранный end-to-end encrypted data channel к собственному exit; нельзя принять TLS к VK за защиту от VK. Банк/RU-direct трафик сознательно выходит на physical network согласно правилам этапа05.
9. **Lease capacity.** Текущий отдельный relay node: `10.66.66.0/24`, `.2–.250`, 249 записей максимум; expiry ≤24h, default retention 48h. Retained expired/revoked entries и rotation overlap расходуют capacity. Это не лимит «249 пользователей онлайн». Reconcile не воскресит revoked revision; выдача ограничена ниже измеренной active capacity. Частый live lifecycle smoke расходует slots; обычное health — `/readyz`.
10. **Local proxy.** Только Unix socket с проверкой peer credentials либо loopback random port с per-session authentication, если native bridge требует TCP. `127.0.0.1` доступен другим Android apps, поэтому alone не security boundary. Credentials не совпадают с VK/relay token; reconnect их меняет. Public listener/unauthenticated SOCKS запрещён.

## Распределение задач

Назначить трёх workers; главный агент единолично меняет общие contracts/Gradle/registry после review. Все новые пути ниже планируемые; существующий путь указан без звёздочки. Каждый worker получает fixtures, разрешённый file manifest и запрет side effects вне стенда.

| ID | Субагент | Вход | Владение файлами | Выход | Зависимость |
|---|---|---|---|---|---|
| S06-01 | `relay-native` | pinned fork, native/node contracts | `levik_whitelist_relay/fork/wdtt-plus-v15/`, `source/`, `scripts/`; lock менять только после ADR | Framing/key audit, compile/test report, карта изменений fork | 01,03 |
| S06-02 | `android-relay-auth` | Direct adapter, stage04 mapper, network contract | `app/src/direct/java/org/hellokittyvpn/android/vpn/RelayControlProtocol.kt`, `RelayTunnelEngineAdapter.kt`, `RelayVkAccountAuth.kt`, matching Direct/androidTest tests | Lifecycle/cancel/auth isolation, socket protection evidence | 04,05; IPC freeze S06-01 |
| S06-03 | `transport-security-lab` | audit, источники внешних проектов, source capture fixtures | новые `tests/network/relay/`, `docs/hellokitty/transport/` | Threat tests, comparison ADR, live field protocol без секретов | 01,05 |
| S06-04 | главный | выводы трёх workers | `contracts/profile-v2.schema.json`, Direct registry/mapper integration, общий stage report | Согласованный capability/lease/disabled gate, интеграция | S06-01..03 |
| S06-05 | `relay-native` | ADR конкретного дефекта WDTT | только новый `experiments/vk-sfu/` до решения о production; native dependency metadata — через главного | Условный SFU PoC либо явный NOT RUN: условие эксперимента не наступило | Неудача подтверждённого WDTT gate, S06-03 ADR |
| S06-06 | `transport-security-lab` + главный | APK, authorised node/SIM/VK session | `tests/network/relay/results/` redacted fixtures, итоговый отчёт | Live stage trace, captures, throughput/expiry/migration результаты | S06-04, человеческие входы |

### Волны выполнения

- **A: главный + 3 workers.** Native source/framing audit, Android auth/network audit и независимый comparison/security design параллельно, без общих правок. Главный фиксирует incompatibilities и missing inputs.
- **B: главный + 2 workers.** После заморозки IPC native и Android исправляют свои поверхности; security worker готовит fault harness. Изменение shared contracts интегрирует главный последовательно.
- **C: главный + до 3 workers.** Native tests, device IPC/auth tests и local network tests; Gradle invocations в одном checkout последовательны. Linux/Android native builds также ограничить CPU/RAM согласно inventory, не запускать тяжёлые задачи втроём на заполненном swap.
- **D: live owner + workers по доступным устройствам.** Один владелец управляет аккаунтом/стендом; workers не разделяют cookie files. Если WDTT проходит gate, SFU не внедрять «на всякий случай». Если не проходит, сначала воспроизвести конкретную неспособность, затем S06-05 в отдельном experiment с новым protocolId.

## Подробные действия и manifest

1. Повторить locks/hash/source provenance и сопоставить Android `INIT`, `TURN_CREDS`, `STOP`, `PROTECT_SOCKET`, `proxy_plan`, error codes с native codec. Сохранить golden request/event sequences в `levik_whitelist_relay/contracts/` только после решения главного. Legacy wire names не заменять поиском по всей базе.
2. Выполнить `scripts/verify-upstream.sh`, `scripts/test.sh`, `scripts/build-linux.sh`, `scripts/build-android-client.sh` из `levik_whitelist_relay/` с lock-compatible `GO_BIN`/`ANDROID_NDK_HOME`. Test script использует `GOFLAGS=-mod=readonly`; не менять go.sum, чтобы скачать произвольную новую библиотеку. Пересборка native из clean source — отдельно от существующей debug-проверки.
3. Сверить сторонние кандидаты с pinned source и лицензией: [vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy) исследован на `e8a96967dc66f3dbd631596ea6a8b9fe03f9be69` (TURN/DTLS); [whitelist-bypass](https://github.com/kulikov0/whitelist-bypass) на `7c19a7ec40900940fe0c43ea1db7768ee632393d` (SFU/Pion DataChannel/VP8). Это исследованные snapshots, не заявление о текущих latest. На момент реализации повторно получить source/license/security status и закрепить выбранный commit/hash. Не тащить headless Chromium на Android и не смешивать protocol packets.
4. В comparison ADR записать: VK API/session prerequisite, transport TCP/UDP, signaling API, ciphertext layer, framing, MTU/fragmentation, keepalive, overhead, доступность библиотек для Android/API26, reconnect, captcha, account rate limit, source-license obligations. Только работающий ответ signaling и payload на реальной whitelist SIM подтверждает candidate.
5. Привязать provisioning profile к device/access/node/expiry/revision; не показывать relay server если backend не выдал действующий capability. Отзыв распространяется worker/outbox плюс native local expiry. Проверить существующие и новые sessions отдельно. API outage не отменяет действующий offline credential; истечение не «продлевается» клиентскими часами.
6. Ограничить VK WebView origins/navigation/JS bridge; неизвестные origin, `intent:`, `file:`, arbitrary callback URL и unexpected JSON отклонять. Bridge принимает минимальные strict objects только для ожидаемого pending requestId/hash и состояния; не отдаёт cookie/полный JS response в UI. Captcha выполняется владельцем вручную; автоматического обхода captcha и бесконечного повторения нет.
7. Сделать cancellation единым для Back, Disconnect, Activity destruction, notification denial, auth timeout, Network lost и process death. Закрывать native child/FD/listeners, удалять ephemeral credentials; late auth callback предыдущей попытки не запускает новое соединение. Разобрать сохранение VK login отдельно от сохранения TURN token.
8. Протестировать protect/bind для auth/TURN/DNS до connect и после migration. При WebView process binding нельзя параллельно отправить API/foreign запрос наружу в обход tunnel: изолировать процесс либо остановить/заморозить такие операции до restoration.
9. Lab Linux node в отдельной network namespace/VM: только собственные names/subnets/ports; egress запрещает private/reserved/metadata после DNS resolution, включая IPv6/rebinding. Не использовать исторические `deploy/test-vps` IP, пути secrets и глобальный nftables template как текущую конфигурацию сервера.
10. Live проверка: timestamp + физический город + SIM-region + модель/build + оператор/сеть; direct control, фактически активный allowlist, enrollment/offline bootstrap, signaling → TURN allocation → inner handshake → HTTPS/UDP payload. Проверить expiry/renew/revoke и двухсторонний node capture. Повторные runs и 24h soak входят в этап07; минимальный живой trace этого этапа не заменяет пилот.

## Проверки: какой дефект они должны обнаружить

| Сценарий | Реалистичный дефект | Требуемое доказательство |
|---|---|---|
| Wrong control version, oversize/duplicate JSON, out-of-order events | Native input bypass/state confusion | Отказ stable code до TUN ready; ни один secret не в log |
| Protect false, bind false, ack timeout, wrong requestId | Connect до protection либо ack чужому FD | Capture без connect по незащищённому пути; bounded cleanup |
| Disconnect во время captcha/auth и late callback | Старый callback восстанавливает VPN/процессную сеть | Нет restart после stop; binding/FD возвращены; новый request независим |
| VK credentials expired/429/changed signaling body | Бесконечный retry или пустой cache считается успехом | Ограниченный backoff, error stage, fresh auth/ручное действие |
| Wrong inner server key, altered WRAP frame, replay packet | Outer TURN TLS ошибочно считается end-to-end доверием | Inner handshake/payload rejected; не CONNECTED |
| Утраченный lease/expired/revoked credential + node restart | Credential resurrection при reconcile | Новые и уже открытые sessions не продолжают запрещённый доступ согласно revoke contract |
| Other Android app обращается к localhost proxy | Loopback принимается за изоляцию | Unauthorized app не передаёт bytes; новый session secret после reconnect |
| UDP dial есть, DNS response потерян | Fallback проверяет открытие socket вместо ответа | Fallback только по exchange timeout на разрешённый resolver/transport; без system-DNS leak |
| Wi-Fi→LTE при active auth и screen off | Stale physical Network, FD leak, auth direct leak | Новое поколение, bounded recovery, capture и FD/RSS до/после |
| Много expired/tombstone entries + renewal/rotation | 249 объявлено числом active sessions, pool overcommit | Нет повторной IP allocation; admission/capacity metrics включают retained entries |
| Foreign packet при TURN/API outage | Автоматический direct bypass ломает kill switch | Packet blocked либо остаётся в выбранном tunnel; RU-direct соответствует policy |

## Gate, откат и передача

Gate G06: pinned matched server/client воспроизводимо собираются; новые unit/fault/instrumentation tests прошли; socket/auth isolation и key pin доказаны; собственный профиль выдаётся/истекает/отзывается; live payload подтверждён хотя бы для явно описанной ячейки. Недоступные операторы/города/модели остаются **NOT RUN**. До live gate capability только experimental/disabled-by-default и не обещается пользователям. VK-блокер не останавливает regular Xray backend.

Откат: feature flag/capability не выдаёт новые relay profiles, regular transport остаётся доступным; отозвать только созданные тестовые leases через текущую revision/idempotency процедуру. Не удалять tombstones и не очищать весь node store. Native version rollback разрешён только с совместимым IPC/profile; при несовместимости завершить старую сессию и объяснить необходимость обновления. Аккаунт/logout и disposable resources очищает владелец стенда, без удаления чужих данных.

Главный сохраняет `docs/hellokitty/execution/S06-report.md` (планируемый файл): commit/dirty diff snapshot, locks/ABIs, executed commands и статусы **VERIFIED / FAILED / NOT RUN / UNABLE TO RUN**, trace по стадиям, redacted captures/hashes, реализованная isolation policy, capacity/tombstones, открытые defects, подтверждённые cells, live blockers, rollback rehearsal. Credentials/cookies/полные profiles в report не включать. В этап07 передаются APK hashes, exact node build/config revision, schema/rule version и executable regression scenarios; не только фраза «VK работает».
