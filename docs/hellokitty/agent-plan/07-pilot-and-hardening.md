# Этап 07 — полевой пилот, нагрузка и устранение отказов

Статус реализации этапа: **NOT RUN**. Ни реальные российские SIM, ни перечисленные физические модели, ни 24-часовой soak пока не проверены. Ранее пройденные unit/lint/emulator проверки относятся к [очистке клиента](../app-cleanup.md). [22 опубликованных случая](../research-cases.md) — чужие наблюдения с явно отмеченными пропусками; они не являются результатами этого пилота.

## Цель и измеримый результат

Проверить весь собственный сервис на конкретных устройствах и сетях, воспроизвести известные классы отказов, закрыть security/recovery дефекты и определить реальные limits. Передать в релиз точный supported scope, а не обещание работы «в РФ». Выход: обезличенный run registry, test/fault/load/recovery evidence, исправленные критические проблемы и release gate по каждому capability.

Проектные цели: ≥95% полезных соединений в **подтверждённых** ячейках, regular median≤5s/p95≤15s, experimental relay p95≤45s, network recovery p95≤20s при доступном transport. Это критерии будущего испытания, не нынешние результаты и не SLA. Полный blackout, unavailable VK API и отсутствие transport классифицируются отдельно; denominator не подменять исключением неудобных отказов. Для малой выборки показывать sample count/интервал неопределённости, а не уверенную общую долю.

## Входы и независимая работа

- Gates этапов [02](02-backend-control-plane.md), [03](03-node-provisioning.md), [04](04-android-integration.md), [05](05-ru-routing-and-networking.md) должны пройти для regular VPN. [06](06-vk-whitelist-transport.md) нужен только для VK capability; непроверенный VK не блокирует проверку regular transport.
- Нужны immutable candidate build/hash либо задокументированный local snapshot, отдельные staging API/DB/nodes, authorized pilot invitations, независимые контрольные HTTPS/UDP endpoints, packet capture без payload user sites, физические телефоны и добровольные SIM/города. Shared server inventory повторяется read-only; не брать аккаунты/порты/секреты соседних сервисов.
- Главный агент заранее утверждает observation schema, expected policy каждого сценария и list of critical failures. Worker не меняет expected result, чтобы «закрыть» failing test.
- При отсутствии добровольцев, VK-account или зарубежного exit собирать lab harness, API/node load, backup/restore, emulator lifecycle, security vectors. Недоступные field cells остаются **NOT RUN**, конкретная необходимая внешняя проверка — **UNABLE TO RUN** с причиной. Не выводить успех Вологды из успеха Казани или SIM-регистрацию из IP геолокации.

## Контракт наблюдения

Планируемые файлы: `tests/field/schema/run-v1.schema.json`, `tests/field/templates/run-v1.json`, `tests/field/results/`, `docs/hellokitty/pilot/coverage.md`. JSON schema strict, timestamps RFC3339 UTC, enums фиксированы, numeric units обязательны. Размер export ограничен; free-text redacted. Raw diagnostic evidence хранится вне Git в ограниченном pilot storage, в Git только обезличенные fixtures/hashes/aggregates. Отсутствующее поле задаётся `null` плюс `missingReason`, не произвольной строкой «Samsung».

| Группа | Обязательные поля и смысл |
|---|---|
| Идентификатор | `runId`, `scenarioId`, `startedAt`, `endedAt`, `status`, `repeats`, `expectedBehavior`, `observedBehavior`, `errorStage`, `evidenceRefs` |
| Устройство | `manufacturer`, **`model`**, `osVersion`, `osBuild`, `securityPatch`, `oemSkin`, `androidUserProfile` personal/work, `rootState`; известный model code отдельно от торгового имени |
| Build | `appVersion`, `versionCode`, `flavor`, `apkSha256`, `coreCommit`, `relayCommit`, `rulesVersion`, `apiRevision`, `nodeRevision`, `configRevision` |
| Физическая сеть | **`operator`/ISP**, `networkType` Wi-Fi/LTE/5G, `physicalCity`, coarse country/region добровольно; roaming, hotspot, IPv4/IPv6/NAT64, MTU observation; Wi-Fi ISP и мобильный оператор не смешивать |
| SIM | `simRegistrationRegion` отдельно от `physicalCity`; `simRegistrationKnown`, subscription type при добровольном сообщении. ICCID/IMSI/номер телефона не собирать |
| Ограничения | `restrictionMode` normal/observed-allowlist/throttled/blackout/unknown, `restrictionEvidence`, доступность direct control domains, повтор контроля в то же время; доступный VK не доказывает активный allowlist |
| Android policy | Private DNS off/auto/strict и resolver role, lockdown/always-on, full/per-app/exclusions, battery exemption, screen on/off, boot/permission/work-profile state |
| Transport | engine/capability, node opaque ID/country, transport/fingerprint/template revision, selected physical Network, pre-enrolled/cached/fresh enrollment |
| Результат | stage timestamps, payload success, HTTPS bytes/time, UDP sent/received/loss, recovery seconds, RTT/jitter, app/node RSS/FD, system route count, battery method/interval; единицы bytes/Mbit/s/ms/% явные |

`status` принимает ровно **VERIFIED / FAILED / NOT RUN / UNABLE TO RUN**. `VERIFIED` требует запущенного сценария, ожидаемого поведения, evidence и известного контекста. Частично заполненное чужое сообщение может быть research reference, но не подтверждает field cell. Внутренний pseudonymous device ID допустим в restricted run registry; metrics labels device ID, IP, domains, cookies не содержат. Пользователь отправляет локальный export вручную; фоновый GPS/map upload не добавлять.

## Покрытие: цель, а не готовые результаты

Первые 20–50 добровольно enrolled устройств, concurrency cap ниже measured node capacity. Кандидаты операторов: МТС, МегаФон, Билайн, T2; города Москва/область, Казань, Вологда, Петербург, доступный южный регион. Модельная цель: Samsung S20/S22/S23/S24, Xiaomi/Redmi/POCO HyperOS, Pixel, Infinix, OPPO/Huawei с Android≥8. Каждую реально доступную network cell проверить минимум три раза утром/вечером в разные дни, затем два screen-off и одну migration. Исторический Huawei Android5 из исследования ниже minSdk и в support matrix не входит.

Не требуется выдуманный полный Cartesian product каждого телефона×оператора×города. Главный выделяет риск: (1) LTE full/per-app + sleep, (2) Samsung+large rules+wearable, (3) Android16 work-profile, (4) Private DNS/NAT64, (5) observed allowlist VK. Cells без телефона/SIM добровольца остаются NOT RUN; цель покрытия и фактическая таблица идут раздельно. Tethering проверяется как отдельная capability; VPN телефона автоматически не обещает туннель ноутбуку через hotspot.

## Задачи и владение

| ID | Субагент | Вход | Владение файлами | Выход | Зависимость |
|---|---|---|---|---|---|
| S07-01 | `field-and-oem-qa` | 22 cases, candidate APK, доступные устройства | `tests/field/schema/`, `templates/`, `results/`, `docs/hellokitty/pilot/coverage.md` | Strict run schema, matrix, OEM/lifecycle evidence | 04,05; 06 для VK |
| S07-02 | `load-and-recovery` | staging API/node/DB, caps, metrics | `tests/load/`, `tests/fault/recovery/`, `docs/hellokitty/pilot/capacity.md` | Load/24h/restore report, пределы и alarm thresholds | 02,03 |
| S07-03 | `security-and-privacy-qa` | threat model/contracts, APK/API access | `tests/security/`, `docs/hellokitty/pilot/security.md` | Negative tests, redaction/ACL verification, findings | 01–05 |
| S07-04 | главный + владелец компонента | reproducer конкретного FAILED | Только manifest дефекта: Android → stage04/05/06 owner; API → stage02; node → stage03 | Минимальный fix + regression, согласованный PR/diff | S07-01..03 |
| S07-05 | главный | все reports и executed evidence | `docs/hellokitty/execution/S07-report.md`, сводная coverage/capability decision | Release candidate gate с material limitations | S07-04, rerun affected scenarios |

QA workers не меняют рабочий код одновременно с владельцами компонента. Если fix требует уже занятого файла, главный передаёт exclusive ownership и останавливает пересекающуюся задачу. Общие contracts, migrations, signing policy и runtime configuration меняет только главный/назначенный owner после review.

### Волны

- **A, главный + 3 workers:** field schema/OEM сценарии, load+recovery harness, security negative tests. Согласовать schema до загрузки field runs.
- **B, главный + 3 workers:** испытания независимых staging namespaces. API load не выполнять на public service или соседней DB. OEM tests группируются по телефонам; security worker работает с отдельными synthetic identities.
- **C, главный + до 3 component workers:** каждый получает воспроизводимый defect/fixture и свой file manifest; сначала failing case, затем fix, затем тот же regression. Нельзя параллельно крутить Gradle одного checkout.
- **D:** главный запускает serial интеграционный gate после всех fixes; workers проверяют impacted field cells, нагрузку и security. Не повторять весь набор без причины, но protocol/routing/DB изменения требуют пересмотра связанных security/recovery проверок.

## Порядок испытаний

1. **Контроль baseline.** Проверить ordinary internet без VPN, direct control, API/TLS/time, один regular tunnel и собственный node capture. Handshake/green icon без HTTPS/UDP bytes считается FAILED, stage recorded. Compare server+profile на Wi-Fi/LTE и другой client только с разрешённым config; не отправлять credentials на внешние сервисы.
2. **Routing и leak.** Russian domain/foreign domain/shared CDN/CNAME/IDNA, override, Android app exclusions и lockdown; Private DNS off/auto/strict; IPv4/dual stack/IPv6-only+NAT64. Capture доказывает RU физический source, foreign exit, отсутствие direct foreign DNS/IP. 1k/10k/30k CIDR не должны линейно раздувать system routes: `LinkProperties`, RSS/startup и reboot+wearable из R19.
3. **Lifecycle.** Screen off ≥60min, foreground/background, battery exemption on/off, Doze, Samsung/HyperOS/Infinix/Pixel per-app LTE, VoIP/push под добровольным контролем. Compare TUN TX, native counters и node wire capture: TUN TX без node packets не успех. Wake recovery, app force-stop/process kill, low memory, revoke VPN permission, boot locked/unlocked, app update, always-on и personal/work profile; никаких обещаний background автостарта поверх OS restrictions.
4. **Network changes.** Wi-Fi↔LTE, airplane mode, temporary no network, physical Network lost во время auth/handshake, captive portal и double callbacks. **100 reconnect** в lab/доступной cell с bounded retry/FD/RSS; manual user stop отменяет все попытки. Hotspot TCP/UDP отдельным run и отдельным support claim.
5. **24h soak.** Как минимум regular node; relay — только для реально подтверждённой cell. Каждые интервалы payload+latency, lease renew/expiry boundary, screen-off/wake, node/API availability, RSS/FD/buffer counts, VK credential renewal/429/captcha. Не заполнять user browsing payload logs. Interrupted run записать FAILED/UNABLE TO RUN с длительностью; «запущен 24h timer» не означает завершённый soak.
6. **API/node load.** 100 concurrent registration/renewal операций на synthetic invitations; ownership/idempotency/replay/concurrency и p95≤500ms для local DB path, provisioning separately. Data-plane 1/10/25/50 active clients, TCP up/down, UDP loss, loaded latency, CPU/RSS/FD; overload очереди/DB/relay. Прекратить при достигнутом resource safety threshold, сохранить partial evidence. 249 relay records — pool capacity с tombstones, не performance promise; отдельно users/leases/active connections/flows.
7. **Capacity budget.** Measured useful Mbps + encryption/TURN/SFU overhead + double-hop monthly egress, стоимость и fair-use per credential. Для примера 10 активных×2 Mbps ≈20 Mbps до overhead; это расчёт, не benchmark. Ограничить buffers/connections/FD, lease reservations, rate limiter, bounded outbox. Выдать owner измеренные admission caps, не число установок.
8. **Failure/recovery.** API down, DB down/slow, worker crash после apply до ack, node restart, VK down, DNS response loss, expired mTLS/HMAC key, disk-full/state truncation, wrong clock/VM pause. Проверять existing session vs new issuance отдельно; offline expiry/revoke не ослаблять. Time checks не отключать «для починки».
9. **Backup/restore.** Encrypted backup на независимое approved staging storage, restore в отдельную DB/network; reconciler не активирует revoked/expired credentials. Выборочный восстановленный payload, ownership и replay/revision history; signing keys backup отдельно. Измерить время и потерю данных: цели RPO≤24h/RTO≤2h не подтверждать без executed drill. Migration rollback rehearsal без удаления volumes.
10. **Privacy/security.** Local other app SOCKS attack; import file/listener/environment paths; API cross-device/nonce/replay/tamper, refresh-family reuse; node SSRF/rebinding/IPv6metadata ACL; debug/export/crash/metrics redaction; deleted device no longer issues credentials. Pentest не касается чужих адресов/аккаунтов/публичных сервисов.

## Fault tests с конкретными дефектами

| Проверка | Дефект, который обязан дать FAILED | Acceptance evidence |
|---|---|---|
| Full vs per-app LTE после 5–15min sleep, 60min wake | Socket привязан к исчезнувшему Network, RX/TX counters маскируют отсутствие wire traffic | Полезный ответ + node capture, явная recovery стадия/время |
| Work-profile always-on | Проверка глобального числа VPN ошибочно запрещает personal VPN | Correct profile behavior и объяснение настоящего конфликта |
| Большой RU list/reboot/watch companion | 30k rules превращены в тысячи Android routes | Bounded route count и отсутствие companion crash/LinkProperties overload |
| Private DNS strict + failed resolver | Движок переключает foreign DNS напрямую | Capture без forbidden direct lookup, bounded DNS error/fallback внутри допустимого пути |
| Kill native или TURN while CONNECTED | Foreign flows автоматически идут напрямую | Leak capture =0 forbidden packets, состояние обновлено |
| 100 migrations/reconnects | Старые workers/FD/обработчики накапливаются | RSS/FD plateau, один TUN/active generation, user stop terminal |
| Crash between node apply и DB ack | Retry создаёт новый credential/duplicate lease | Same idempotency/revision, read-back-before-ready, capacity не удваивается |
| Offline node после revoke + expiry | Restore возвращает старую авторизацию | Новые/открытые sessions obey revoke/expiry semantics; revoked desired state не resurrected |
| 100 parallel renewals и stale revisions | Последний завершившийся запрос затирает новую revision | Exactly expected credential version, duplicate/exhaustion нет |
| Disk full/truncated state/clock drift | Нода принимает пустую DB и выдаёт уже использованные IP либо игнорирует expiry | Fail-closed admission, recoverable diagnostic, no pool collision |
| Log/export/exception с синтетическим секретом | Redaction работает только в обычном response | Secret markers absent во всех output/metrics/crash paths |
| Restore старого backup | Отозванный refresh/device возвращён active | Post-restore revoke/reconcile proves rejection, восстановление audited |

## Gate и откат

G07 проходит только при отсутствии critical security/data integrity defects: foreign leak при kill-switch обещании, unauthenticated proxy, cross-device access, credential resurrection, потеря durable state, corruption restore, ложный CONNECTED. Для supported cells выполнены повторения и lifecycle; измерены нагрузка/admission caps, 24h soak и восстановление. Любой непроверенный VK/operator/device claim убирается из release scope или явно маркируется experimental/NOT RUN.

Откат пилота: прекращать новые invitations/issuance выбранной capability, ограничивать concurrency, возвращать предыдущий проверенный rules package/API/native candidate; test credentials отозвать по ownership/revision. Миграции откатывать только по отрепетированному совместимому runbook; не `down -v`, не очистка всей DB, не global firewall reset. Если выявлен leak, отключить проблемную capability немедленно; безопасность важнее сохранения сессии.

Передача в `docs/hellokitty/execution/S07-report.md`: exact APK/build/node/rules/API hashes, coverage goal vs actual results, missing fields, per-cell counts/denominator, failed+fixed regression IDs, remaining NOT RUN/UNABLE TO RUN, measured capacity/RSS/FD/battery method, restore RPO/RTO, critical findings disposition, redacted evidence references, approved release capabilities и rollback. Главный подписывает вывод по фактам; отсутствие полевых входов не превращается в зелёный релиз для всей РФ.
