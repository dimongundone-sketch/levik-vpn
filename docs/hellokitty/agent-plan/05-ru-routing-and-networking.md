# Этап 05. Российские маршруты, DNS, IPv6 и мобильная сеть

**Статус реализации этапа: NOT RUN.** Ниже целевое поведение и задания для следующего агента. Текущий клиент использует старый routing snapshot и блокирует IPv6; предыдущие тесты очистки не являются испытанием новых правил или dual stack.

## Цель и результат

В пресете Bypass RU известные российские сервисы выходят через физическую сеть **телефона**, иностранные и нераспознанные назначения — через VPN. Решения распространяются на TCP/UDP и поддерживаемые IPv4/IPv6, DNS следует той же классификации. Потеря VPN не отправляет иностранный трафик напрямую. Неподтверждённый адрес shared CDN остаётся tunneled; банкам с собственным DoH/ECH предоставляется явное исключение приложения с объяснением области действия.

Результат — versioned signed rules pipeline, проверенная DNS-модель, защита сокетов/привязка к Network, lifecycle/network-generation, доказанная packet-capture матрица и воспроизводимый rollback правил. Нельзя обещать определение всех российских сервисов, предотвращение блокировки VPN-IP, распространение VPN на hotspot-клиентов или работу полного blackout.

## Предусловия

1. [Этап 04](04-android-integration.md) закрыл real regular payload gate: managed credential, node apply, HTTPS response и truthful CONNECTED. Если этого нет, разрешены только независимые rule/fixture/spike работы; не принимать DNS routing за причину неготового provisioning.
2. [Этап 01](01-contracts-and-security.md) заморозил routing manifest/schema, подпись, purpose/keyId, monotonic version, capability negotiation и правила совместимости. Этап 02 предоставляет signed manifest/API cache semantics. Нельзя переформатировать подпись или downgrade key policy в этапе 05.
3. Есть старые snapshots, источник/лицензия/дата каждого нового набора, тестовый endpoint для определения physical и tunneled egress, безопасный capture на собственных устройствах/узлах и согласованная матрица физической сети. Production rollout относится к этапу 08.
4. Собраны минимальные реальные устройства Android8/13/16 и доступная матрица IPv4-only/dual-stack/IPv6-only/NAT64. Отсутствующие устройства/сети явно остаются `NOT RUN`/`UNABLE TO RUN`; эмулятор не является доказательством Samsung/Xiaomi/оператора РФ.

## Текущее состояние и целевая модель

| Поверхность | Сейчас, по исходникам | Цель этого этапа |
|---|---|---|
| RU data | `RussianRoutingData` читает assets `ru_ipv4.cidr`/`ru_ipv6.cidr`; исторический snapshot отмечен в общем плане; LTE имеет большой отдельный набор | Источники/coverage/дата/подпись/version, curated domains и conservative CIDR |
| DNS | `XrayConfigBuilder`: `UseIPv4`, DoH при выборе + primary/secondary IP, `IPIfNonMatch`; TUN объявляет выбранные resolvers | Явная split-DNS policy с двумя egress contexts и network-scoped caches |
| IPv6 | TUN имеет IPv6 address и `::/0`, но первая engine rule отправляет весь IPv6 в blackhole | Настоящий dual stack только после proof; режим compatibility сохраняет block |
| MTU | `XrayConfigBuilder.TUN_MTU = 1500` | Версионируемый per-transport MTU; старт измерений 1280, дальше 1360/1400/1500 по тесту |
| OS routes | `VpnRoutes`: небольшой fixed local exclusion набор; API33+ `excludeRoute`, старые устройства — public IPv4 complement + `::/0` | Сохранить bounded fixed OS routes; десятки тысяч service CIDR внутри engine |
| Physical binding | `protectTunnelSocket` после protect допускает bind failure для regular ANY; relay/restricted binding строгий | RU direct и bootstrap requests требуют правильной generation/physical Network; direct ошибку не скрывать |
| Lifecycle | Есть reconnect, wake/FGS, network callbacks, health; единая generation для всех DNS/profile/native результатов ещё требует проверки | Network generation, bounded retries и отсутствие stale success/cache/FD reuse |

Наличие `primaryIpv6` в `DnsProvider`, IPv6 assets или address на TUN не означает IPv6 forwarding. `UseIPv4`/blackhole не выдавать в отчёте за dual-stack support.

### Решение о DNS, которое должен реализовать следующий агент

**Цель `dnsPolicyVersion = 2`: локальный управляемый DNS dispatcher в том же engine/TUN, возвращающий реальные A/AAAA ответы, а не fake-IP по умолчанию.** Российские разрешённые имена разрешаются resolver физического Android Network; иностранные и неизвестные — через проверенный tunnel context. Реальные ответы сохраняют поведение банков, CDN и NAT64. Кеш хранит qname/qtype, разрешённую CNAME chain, route class, TTL, network generation и rules version.

Это новая policy, а не настройка нынешнего списка DNS servers. На старте обязательный native spike проверяет, что закреплённый Xray/libXray может обеспечить capture DNS, отдельное outbound DNS routing и bound physical resolution с данной TUN интеграцией. Использовать фактические исходники/lock и config validation; не угадывать поля JSON по latest docs. Если нужная capability не поддержана, отдельно предложить минимальный adapter/native update с новым pin, license/build/ABI/16KiB gate и согласованным контрактом. Пока proof нет, v2 не активируется и задача не считается выполненной. Второй VpnService, свой TLS MITM, root и установка CA запрещены.

Модель намеренно сохраняет conservative fallback: IP-to-domain связь не уникальна. Если приложение скрывает домен за DoH/ECH, наблюдаемые bindings конфликтуют либо IP общий с иностранным сервисом, IP-only классификация не даёт DIRECT. Sniffing HTTP/TLS/QUIC — дополнительный сигнал, не гарантия ECH visibility. Domain-directed native connect должен сохранять исходную классификацию при дополнительном DNS resolution, чтобы engine не резолвил foreign hostname через physical DNS.

**Совместимость:** старый local/prepared профиль без `dnsPolicyVersion` продолжает v1 с прежними настройками и блоком IPv6; существующие selected DNS provider и пользовательские overrides не стираются. Managed profile/rule package v2 требует `minimumClientVersion` и объявленную capability. Миграция user settings явная: новые UI descriptions поясняют, какие DNS проходят directly; custom resolver не превращается в unrestricted URL downloader. Нет тихого обновления v1-профиля до dual stack. Global остаётся tunnel-default; Blocked Only имеет намеренный direct-default по своему пресету и не должен называться полной защитой иностранного трафика.

## Routing contracts и инварианты

### Порядок решений

1. Android per-app inclusion/exclusion применяется до engine domain/IP routing. Исключённое банковское приложение целиком использует physical network, не только его банк-домены; учитывать рекламу/аналитику/внешние ссылки внутри него.
2. Kill-switch/credential-invalid/session-not-ready правила имеют приоритет над сетевыми fallback. Нельзя снять foreign protection для проверки endpoint.
3. Явный пользовательский `force VPN` для домена имеет приоритет над пользовательским `DIRECT`, затем утверждённая политика curated services. Конфликт показывается/валидируется, не зависит от порядка set iteration.
4. Домены нормализуются IDNA и по label boundaries. `example.ru` не совпадает с `evil-example.ru`; точное правило/full и subdomain suffix различаются. User input URL/path/port/wildcard с неоднозначным смыслом отклоняется либо конвертируется по явной схеме, без произвольных Xray expressions.
5. Direct CIDR разрешаются только для подтверждённых сервисных диапазонов без широкого расширения покрытия. RU география/ASN/.ru suffix — вспомогательные признаки, не доказательство. Shared CDN не добавляется целиком потому, что один банк использует его адрес.
6. Unknown/contradictory data в Bypass RU/Global — TUNNEL. Ошибка rules update использует предыдущий валидный пакет/embedded baseline по policy, не `DIRECT all`. Blocked Only direct-default — отдельный осознанный режим.
7. Rule matching и DNS state tied к `(sessionGeneration, networkGeneration, rulesVersion)`. CNAME loops/too long chain/negative caching/TTL zero проверяются. Пересечение RU/foreign chain, DNS rebinding в reserved/private target и запись для другого Network не превращают foreign traffic в direct.
8. Activation нового пакета атомарна для новых flows; старые DIRECT flows к диапазону, который больше не разрешён, не продолжаются бесконечно по старой policy. Определить scoped drain/reconnect с generation guard, сохранив kill switch; активные чужие credentials при этом не ротировать. User override и Global transition проходят тот же тест, без одновременного смешения rules двух versions.

### Protected physical Network

- DIRECT производится Xray/adapter на телефоне через `VpnService.protect(fd)` **и выбранный физический `Network.bindSocket`/network resolver**, а не freedom outbound на зарубежной ноде. Protect failure или wrong/stale binding — ошибка, сокет закрывается до payload. Числовой FD reuse рассматривается вместе с generation/session owner.
- Native bridge должен различать outbound socket purpose: direct, tunnel transport, bootstrap/DNS и локальный authenticated relay SOCKS. Не привязывать loopback SOCKS к LTE и не применять blanket binding, который ломает внутренний relay. Если bridge не передаёт purpose, это gap для spike/контрактного решения, не повод утверждать строгую изоляцию.
- Для direct используется current physical Network. Для cellular-allowlist relay retained cellular Network может отличаться от default Wi-Fi; решение transport Network и direct RU Network явно фиксируется policy, затем проверяется. `setUnderlyingNetworks` само по себе не доказывает правильный egress каждого сокета.
- На NetworkLost callbacks/DoH response/readiness result старой generation отменяются. Нет global `bindProcessToNetwork`, который перенаправляет API/UI/loopback весь процесс и обходит отдельную policy.
- Offline/captive portal Network не считается working VPN по capability `INTERNET`; egress и useful payload проверяются отдельно. Необходимость явной user login для portal не скрывается бесконечным reconnect.

### DNS, Private DNS, DoH/ECH, IPv6

- Разделить **bootstrap VPN**, **VK signaling/TURN** и **пользовательский DNS**. Bootstrap resolves на physical Network с bounded fallback; его endpoint hostname известен заранее. Пользовательские foreign queries никогда не переходят на direct resolver из-за tunnel timeout. VK-специфику передать этапу 06.
- Captured DNS UDP/TCP53 получает v2 class и cache context. Для Android Private DNS off/auto/strict отдельно проверить, какие ответы достигают dispatcher и как проходят DoT: системную настройку не выключать. Если strict DoT/app DoH скрывает имена, использовать tunnel routing resolver connection и conservative destinations, объяснять ограничение split-DNS. Не обещать одновременно видеть невидимый qname и сохранять стороннее end-to-end encryption.
- Валидация custom DoH: только разрешённая HTTPS форма/порты по policy, без credential fragments/file paths; TLS hostname и trusted CA checks, bounded body/timeout, redirect policy, bootstrap loop detection. LAN DNS разрешается только явной локальной настройкой, не из remotely supplied untrusted URL.
- Для Bypass RU direct DNS раскрывает выбранные RU имена провайдеру; это честно объясняется рядом с настройкой. DNS logs не создают историю посещений, qnames/answers не экспортируются автоматически.
- До IPv6 enable удалить engine blanket `::/0` blackhole только одновременно с working tunnel/direct DNS/IPv6 paths и kill-switch. Выдать TUN-local IPv6 prefix, который не выглядит как чужая публичная сеть и не конфликтует с underlying/local routes; schema/address choice сверить с relay03/06. Compatibility path продолжает захватывать `::/0` и блокировать его.
- IPv6-only physical network: bootstrap hostname + AAAA/DNS64, transport address selection и Android network DNS учитываются отдельно от IPv6 возможностей exit. Hardcoded IPv4/DoH-IP bootstrap не считается универсальным. Не конструировать NAT64 prefix `64:ff9b::/96` без проверки actual provider prefix. Пользовательские synthesized AAAA не маршрутизируются напрямую по ложному RU-IP совпадению.
- MTU1280 — стартовый кандидат для IPv6/вложенного транспорта, не измеренный optimum. Тест PMTU/DF/large TLS upload/MSS/UDP/QUIC, потеря ICMP Packet Too Big и overhead; ceiling/base per transport. Не фрагментировать UDP только потому, что TLS anti-DPI включён; нынешний builder уже исключает UDP native transports из fragment dialer, тест сохранить.

## Субагенты, владение файлами и параллельность

Максимум главный + 3 субагента. Единственный writer shared service/ViewModel/Gradle определяется заранее; передача ownership фиксируется явно после завершения волны.

| Роль | File ownership | Входы | Артефакты | Зависимости |
|---|---|---|---|---|
| Главный `network-integrator` | `HelloKittyVpnService.kt`, `NetworkMonitor.kt`, `SocketProtection.kt`, `XrayConfigBuilder.kt`, `XrayTunPlan.kt`, `AppSettings.kt`, UI/ViewModel/resources/Gradle/manifest | Handoff04, результаты A/B/C | Интеграция DNS/binding/lifecycle, feature flags, shared file diff | Только один writer этих файлов |
| A `ru-rules-pipeline` | Новый `routing/sources.yaml`, `routing/build/`, `routing/tests/`, `routing/generated/`; будущие immutable fixtures | Source evidence/license, frozen manifest01 | Curated build, bounded normalization, signed-package format fixture, diff/review и rollback version | Freeze01; signing secret вне рабочего дерева |
| B `android-dns-policy` | Новые `vpn/DnsRoutingPolicy.kt`, `DnsCachePolicy.kt`, `RoutingRulesRepository.kt`, `SignedRoutingPackageVerifier.kt`; свои unit tests; native spike docs | Frozen schema/key purposes, existing Xray/bridge pins | v2 resolver/policy/cache interfaces, native capability proof; patches shared files передаёт главному | Handoff04, interfacesA |
| C `mobile-network-qa` | Новые `src/test/.../routingv2/`, `src/androidTest/.../routingv2/` и `docs/hellokitty/verification/stage-05/`; lab fixtures | Cases research, physical lab inventory, public contracts A/B | Routing/DNS/IPv6/network/lifecycle/route-count tests, sanitized captures и coverage matrix | Fixtures параллельно; устройства/Gradle/ADB только по очереди |

`RussianRoutingData.kt`/`LteRoutingData.kt` меняет главный после принятия A. B не пишет одновременно в config/service, C не переписывает production policy для прохождения тестов. Если native spike требует `levik_whitelist_relay`/AAR update, выделить отдельный scoped writer и дождаться свободного слота; не проводить скрытое обновление core.

| ID | Задача | Owner | Зависимости | Критерий |
|---|---|---|---|---|
| S05-01 | Утвердить v2/v1 compatibility, routing precedence и physical socket purpose | Главный + B | 01, G04-C | Decision + exact interfaces, gaps названы |
| S05-02 | Source curation/normalization/CIDR aggregation и generated package | A | S05-01 | Нет broad shared-CDN DIRECT, repeatable build/hash |
| S05-03 | Native spike DNS capture/separate resolver egress/domain mapping | B | S05-01, actual native pin | Прямой и tunneled DNS доказаны; unsupported gap блокирует v2 |
| S05-04 | Signed package update/cache/anti-rollback/expiry | B; Android composition — главный | S05-02, 01 trust | Tamper/downgrade/interrupted update tests |
| S05-05 | Engine policy/order/strict protect+Network binding | Главный | S05-03/04 | Own endpoint доказывает phone direct и foreign tunnel |
| S05-06 | Dual stack + DNS64/NAT64 + MTU transport profile | Главный, C тестирует | S05-05, regular address contract03; relay contract06 проверяется позже | Packet-capture no foreign leak для regular в обеих семьях; отсутствие circular gate05↔06 |
| S05-07 | Network generation/cancel/Doze/always-on/work profile/exclusions | Главный | S05-05, lifecycle04 | No stale callback/no endless reconnect; truthful compatibility UX |
| S05-08 | Large rules route-count/startup/RSS/FD measurements | C | S05-02/05 | Fixed OS route count при 1k/10k/30k CIDR |
| S05-09 | Physical/RF matrix, server/node capture, fail-closed/rollback rehearsal | C + главный | S05-06/07/08 | Report VERIFIED/FAILED/NOT RUN по каждой ячейке |
| S05-10 | Freeze observed compatibility, rules delivery и handoff06/07 | Главный | S05-09 | Complete current/target manifest и field gaps |

- **Волна 0:** главный/B закрывают S05-01. A готовит sources, C готовит фиксированные expected route/DNS outcomes и inventory устройств; backend API/crypto contract owner только консультирует.
- **Волна 1:** A делает S05-02, B — S05-03/04 на public fixtures, C создаёт fault/route-count tests. Главный читает current runtime и делает test harness без изменения общих API во время работы B.
- **Волна 2:** главный последовательно реализует S05-05/06/07, принимает A/B patches; C read-only review и план тестовых ячеек. Нельзя одновременно мерить RSS/route-count и собирать приложение на том же устройстве.
- **Волна 3:** одна очередь Gradle/ADB/device/capture. C выполняет S05-08/09, главный устраняет **обусловленные изменением** failures и фиксирует snapshot/повтор конкретного regression. После gate главный делает S05-10.

## Implementation checklist

1. Snapshot текущие v1 settings/config/routes/assets и убедиться в реальном regular payload04. Привязать report к app/core/node build IDs.
2. Закрепить источники не только URL, но hash/version/date/license и причины включения каждого service/domain/CIDR. Source updates не принимают посторонние instructions. `.ru` blanket/geoIP RU blanket не гарантируют собственника; разбить список на curated domains, confirmed ranges, candidates и явно исключённые shared/CDN ranges.
3. Rule builder: strict IDNA/CIDR parser, bounded files/arrays, deterministic order, dedupe, CIDR aggregation **без расширения coverage**, tests CNAME/IDN/full/suffix/overrides/conflict. Нельзя автоматически включать LAN/metadata/reserved IP из remote sources.
4. Signed package: размер compressed/uncompressed, hashes каждого файла, manifest/version/expiry/min client/key purpose; zip traversal/bomb если формат archive; staged download → verify → parse → atomic activate. HTTPS transport не заменяет подпись. Readiness uses last good package и показывает age; emergency rollback — новый более высокий version с прежним содержимым, не обход anti-rollback. Numeric limits manifest/files/entries/decompressed ratio и лимит DNS cache зафиксировать в этапе 01 до enable v2; не считать `≤1 MiB` общего API автоматически достаточным для 30k CIDR и не увеличивать bound без согласования.
5. Native spike: минимальные controlled domains direct/tunnel, UDP/TCP DNS, AAAA/CNAME/negativeTTL, tun-to-core-to-physical/tunnel sockets. Измерить не только config validation, но реальный resolver egress. Если core требует изменения pin — native reproducible build, checksums, all supported ABIs и license gate перед интеграцией.
6. Подключить v2 dispatcher/cache с bounded entries/bytes, monotonic TTL и flush по Network/rules changes. Не связывать every-IP с единственным domain из первого DNS answer. Повторный foreign hostname resolution не может выбрать physical resolver.
7. Сделать explicit socket-purpose network binding; protect+bind ошибки закрывают FD. Протестировать обратный порядок callbacks и reuse integer FD старой сессии. Сохранить local SOCKS authenticated endpoint и не pin loopback socket.
8. Сохранить небольшую OS routing topology `VpnRoutes` и `excludeRoute` compatibility fallback. Rules работают в engine; service CIDR не превращаются в `Builder.addRoute`. Тест Samsung route overload воспроизводится controlled fixed-count harness, не огромным system list на рабочем телефоне.
9. Включить dual stack отдельно от rule-update release flag. IPv6 real direct/tunnel payload и DNS64/CLAT/NAT64 proof обязательны; если upstream IPv6 transport недоступен, fallback selection явно ограничен без foreign leak. TUN address/prefix и relay pool имеют согласованный ownership.
10. Настроить MTU по measured transport overhead; TLS fragment/UI claims пересмотреть на проверяемые формулировки. Фрагментация не обещает обход любой сети и не лечит UDP blocked/operator full blackout.
11. Lifecycle: session/network generation на DNS/native/health/profile updates; Wi-Fi↔LTE, dual SIM, airplane, API/transport expiry, foreground/background, screenoff60min, processkill, work profile. Bounded retry не удерживает wake lock бесконечно; Android foreground-service/boot constraints проверяются на target SDK36 по фактическим manifest/runtime.
12. Настройки split tunneling показывают конфликт Android always-on lockdown с excluded apps: system может блокировать bypass. Не предлагать «банки direct гарантированы» в этом сочетании. App-level kill switch отличается от системного lockdown: ограничения process death и intentional pause/documented direct paths честно обозначены.
13. Выдать ручную diagnostic export с app/device/OS/build, operator/region введёнными пользователем, network type, transport, rules/DNS policy/generation, stages/timeouts. Нет автоматического IP/geo/qname/device identification upload; маскировать секреты/полные configs.
14. Пройти минимальные тесты, затем физическую РФ-матрицу. Исключить unsupported cells из обещаний, сохранить reproducible bug records и конкретный follow-up вместо отметки общего успеха.

## File manifest

| Поверхность | Файлы и назначение |
|---|---|
| Rule sources/build | `routing/sources.yaml`, `routing/build/`, `routing/tests/`, `routing/generated/`; planned новые reproducible tools/fixtures |
| Contract inputs | `contracts/routing-rules-v1.schema.json` из01, read-only после freeze |
| Android new policy | `app/src/main/java/org/hellokittyvpn/android/vpn/{DnsRoutingPolicy,DnsCachePolicy,RoutingRulesRepository,SignedRoutingPackageVerifier}.kt` — planned modules |
| Current data | `RussianRoutingData.kt`, `LteRoutingData.kt`, `app/src/main/assets/*` — baseline and compatibility loader |
| Shared engine/lifecycle | `XrayConfigBuilder.kt`, `XrayTunPlan.kt`, `VpnRoutes.kt`, `SocketProtection.kt`, `NetworkMonitor.kt`, `HelloKittyVpnService.kt`, `TunnelHealthPolicy.kt` |
| Settings/UI | `data/AppSettings.kt`, ViewModel/UI, EN/RU strings — explicit v1/v2 and lockdown/exclusion limits |
| Native if required | Actual source lock/bridge in `levik_whitelist_relay`, `libXray.aar` lock/build scripts; отдельная approved change surface, не обязательный blind upgrade |
| Tests/evidence | `src/test/.../routingv2/`, `src/androidTest/.../routingv2/`, `docs/hellokitty/verification/stage-05/`; captures/hashes/results без секретов |

Android paths в таблице относительно `levik_vpn_android/`. Формат/module names новых файлов — планируемые; сохранять существующие patterns, не создавать параллельный router/VPN subsystem без доказанной необходимости.

## Проверки, реалистичные дефекты и команды

**Все новые checks ниже: NOT RUN.** Идентифицировать app/test/core/node versions, модель/OS, transport, оператор и географию теста. Из [research-cases.md](../research-cases.md) использовать фактически имеющиеся поля, не дополнять пропуски догадкой. Отдельно включить описанный в общем плане случай [Samsung S23+/Android16 route overload](https://github.com/amnezia-vpn/amnezia-client/issues/2976) как external regression stimulus, а не уже воспроизведённый наш defect.

| Проверка | Реалистичная ошибка | Evidence/граница |
|---|---|---|
| Full/suffix/IDNA/domain override | Suffix совпадает с `evil-bank.ru`, forceVPN проигрывает прямому range | Unit expected class + engine config/runtime test |
| Shared IP/CDN/CNAME conflicts | Первый RU DNS answer делает foreign shared hostname DIRECT | Mixed-domain controlled endpoints обеих class; foreign остается tunnel |
| Signed package tamper/old version/new key/expiry/disk full | Manifest не подписан целиком либо high-water стирается при restart | Reject mutation; old good state сохраняется, emergency rollback newversion |
| HTTP/QUIC/ECH/app DoH | Sniff отсутствует, IP geo fallback делает foreign DIRECT | Unknown identity tunneled, explicit bank package exception отдельно |
| DNS off/auto/strict + resolver loss | Foreign DNS fallback идёт на операторский resolver | Capture operator interface: только разрешённые direct/bootstrap queries |
| Network change/DNS TTL/generation | Cached LTE CDN answer применяется на Wi-Fi или stale success меняет UI | Controlled dual-network test, old result rejected |
| Protect failure/bind failure/FD reuse | Regular ANY fallback выходит не через согласованный Network | Socket denied/closed; egress endpoint подтверждает нужную physical сеть |
| Real RU direct и foreign tunnel | DIRECT реализован на exit server | Own endpoints фиксируют source IP/ASN; Android physical capture совпадает |
| IPv6/AAAA-only/DNS64/NAT64 | `::/0` route есть, payload blackhole; hardcoded IPv4 bootstrap ломает IPv6-only | Полезный трафик обеих семей либо честный compatibility block; нет foreign IPv6 leak |
| MTU/DF/ICMP/PTB/upload/QUIC | MTU 1500 во вложенном relay вызывает blackhole больших пакетов | Перебор размеров, TCP upload/UDP loss, documented MTU выбранного транспорта |
| Kill switch/processkill/credential expiry | Иностранные соединения silently fall back direct | Непрерывные собственные HTTP/UDP probes + capture; ограничения system lockdown отдельно |
| Excluded bank+always-on lockdown | UI обещает bypass, system блокирует excluded UID | Actualbank/package network behavior и conflict warning |
| Work profile + Android 16/Samsung | VPN permission/status перепутаны между personal/work profile | Отдельный consent/state/actual payload каждого profile, отдельная OEM-ячейка |
| Doze/OEM/screenoff 60 min/VoIP | Цикл reconnect или wake-lock расходует батарею, задержанный DNS, voice loss | Физический device: duration/recovery/battery/resource report |
| 1k/10k/30k rule list | Каждый CIDR добавлен в Builder; число routes и parcel/RSS растут опасно | Фиксированное число OS routes; measured startup/RSS/FD и rollback |
| LAN/captive portal/hotspot | Bypass неявно открывает reserved addresses; hotspot обещан как VPN | Local policy tests; явные hotspot scope и portal classification |

Проект уже имеет Gradle задачи unit/lint и route/config tests. Начинать узко, затем полный вариант; команды из `levik_vpn_android/`, последовательно, без production credentials:

```bash
ANDROID_HOME=/opt/android-sdk ./gradlew :app:testPlayDebugUnitTest --tests 'org.hellokittyvpn.android.vpn.VpnRoutesTest' --tests 'org.hellokittyvpn.android.vpn.XrayConfigBuilderTest' --tests 'org.hellokittyvpn.android.vpn.XrayTunPlanTest' --tests 'org.hellokittyvpn.android.vpn.SocketProtectionTest' --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g
ANDROID_HOME=/opt/android-sdk ./gradlew :app:testPlayDebugUnitTest :app:testDirectDebugUnitTest :app:lintPlayDebug :app:lintDirectDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g
```

Новые routingv2 tests добавить в task selection после появления конкретных class names; нельзя считать их выполненными по существующему suite. Build/native packaging и instrumentation — по [командам этапа 04](04-android-integration.md), **APK и test APK одного flavor**, Gradle/ADB serialized. При native pin change выполнить штатную AAR/native hash/ELF/all ABI проверку заново, а не использовать прежние зеленые отчёты.

Read-only Android inventory для выбранного разрешённого serial:

```bash
adb -s DEVICE_SERIAL shell getprop ro.product.model
adb -s DEVICE_SERIAL shell getprop ro.build.version.release
adb -s DEVICE_SERIAL shell settings get global private_dns_mode
adb -s DEVICE_SERIAL shell dumpsys connectivity
adb -s DEVICE_SERIAL shell dumpsys deviceidle
adb -s DEVICE_SERIAL shell dumpsys meminfo org.hellokittyvpn.android.debug
```

`dumpsys` может содержать локальные IP/SSID/package данные: сохранять только нужные sanitized fields. Не выполнять force-idle/airplane/kill/PrivateDNS mutation на чужом или совместно используемом устройстве; fault scenarios проводить на отдельном test phone. Controlled lab harness и rule-builder команды сначала создать/документировать, затем выполнять; не выдумывать существующий `routing/build` CLI.

## Gates и критерии завершения

- **G05-A:** native DNS v2 spike завершён с реальным egress proof; frozen compatibility/contracts корректны; unit/security negative tests rules/signature/cache/overrides passed.
- **G05-B:** known RU direct выходит с physical телефона, foreign/unknown с exit; IPv4 и IPv6/NAT64 tested или явно ограниченный compatibility variant без заявленного dual stack. Production-ready target dual stack не закрывается при одном IPv4 proof.
- **G05-C:** packet captures подтверждают DNS/foreign fail-closed на NetworkLost/corekill/credentialexpiry; app exclusions и systemlockdown conflict тестированы; session/generation races не дают staleCONNECTED.
- **G05-D:** bounded route count при 30k CIDR, measured startup/RSS/FD; оба flavor проходят unit/lint/build и собственные native instrumentation, реальная OEM/операторская матрица содержит статусы/детали, rollback rules выполнен в staging.

Недопустимые blocking findings: foreign direct leak при обещанном kill switch; неверная owner/network binding; server-side DIRECT вместо phone-side; unsigned/rollback rules activation; broad shared-CDN bypass; выдача blackhole IPv6 за поддержку; route list перегружает Android; ложный CONNECTED. Unknown device/operator/region cell не повышается до VERIFIED по сходству с другой.

## Rollback и передача следующих этапов

Rule rollback выпускается подписанным **новым более высоким version** с last-good contents; high-water mark сохраняется. Feature flag возвращает DNS v1/IPv6 compatibility block для затронутого cohort, user overrides/local profiles не стираются. Foreign default остаётся tunneled/blocked; аварийное `direct all`, отключение TLS checks и глобальная замена native pins запрещены. Native rollback допускает только совместимый profile/wire contract; миграции caches recoverable. План rollback отдельно проверяет stale generations и interval без правил.

Этап 06 получает socket-purpose/protect/bind API, cellular/default direct policy, DNS bootstrap context, TUN/MTU/address compatibility и строгую границу одного VpnService. Он не должен заново заменять DNS pipeline или добавлять second VPN. Этап 07 получает field matrix с реальными model/OS/operator/region/time/transport/rule version, sanitized captures, воспроизводимыми failures и неизвестными ячейками.

Итоговый handoff содержит текущие/целевые версии policy и trust key purposes, source manifest/license, generated package hashes, changed-file manifest, measured route count/RSS/connect/recovery/battery, v1 migration, dualstack/NAT64 proof, rollback rehearsal и отчёты каждого gate. Статусы: `VERIFIED` — выполнено и прошло; `FAILED` — выполнено и не прошло; `NOT RUN` — не выполнялось; `UNABLE TO RUN` — препятствие среды/доступа. Никакой общей фразы «РФ проверена» вместо конкретных ячеек.
