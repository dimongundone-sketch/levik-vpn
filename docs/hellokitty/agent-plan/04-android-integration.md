# Этап 04. Интеграция Android-клиента с собственным API

**Статус реализации этапа: NOT RUN.** Этот файл — задание следующему главному агенту и его субагентам. Проверки прежней очистки описаны в [app-cleanup.md](../app-cleanup.md); они не подтверждают интеграцию с новым API. Бэкэнд выдачи сейчас отсутствует. Не отмечать пункты ниже выполненными по факту наличия интерфейса или mock-сервера.

## Цель и результат

Hello Kitty VPN получает бесплатный доступ и персональный профиль устройства от собственного control plane, подключается к настоящему regular Xray-узлу и подтверждает полезный трафик. Пользователь сохраняет локальный импорт, прежние Home/Servers/Stats, темы, анимации, QR, tile/widget и настройки. В приложении не возвращаются чужие аккаунты, оплаты, подписки, поддержка, адреса Levik или необходимость GMS для Direct.

Результат этапа — проверенная цепочка `Keystore identity → enrollment → session → provisioning operation → signed/encrypted profile → native conversion → VPN → authenticated payload`. Испытание VK относится к этапу 06; изменение российской маршрутизации, DNS и полноценного IPv6 — к [этапу 05](05-ru-routing-and-networking.md). В этапе 04 достаточно regular IPv4 payload gate, но запрещено ослаблять существующий IPv6 fail-closed ради прохождения теста.

## Предусловия и входные артефакты

1. Этап 00 передал текущий diff, baseline APK/test APK, зависимости, отчёты и владельцев файлов. Прежние незакоммиченные изменения уже содержат очистку; не возвращать удалённые классы из upstream.
2. [Этап 01](01-contracts-and-security.md) заморозил OpenAPI, request signing vectors, profile/envelope schemas, key-purpose registry, error codes, capabilities и клиентскую миграцию. Пути endpoint и форматы ниже — требования исходного общего плана; при расхождении использовать утверждённый контракт 01 и оформить решение о расхождении, а не самостоятельно изобретать protocol v2.
3. Этап 02 предоставил staging API с enrollment/refresh/ownership/idempotency и отрицательными тестами. Этап 03 предоставил **настоящий** Xray provisioner: ready означает проверенное применение credential, а не запись в JSON/БД.
4. Есть собственный staging hostname/TLS, публичные ключи проверки с обозначенными назначениями, собственный regular exit и разрешённое тестовое устройство. Секреты не передаются через Markdown, CLI arguments, логи или screenshots. Домен `.invalid` в dormant OTA не является адресом работающего API.
5. Имеются API/node correlation IDs и способ проверить отзыв выданного credential без доступа Android к management API. Запуск реальной ноды и ingress должен быть уже разрешён в предыдущем этапе; этот файл не разрешает менять посторонние сервисы сервера.

До выполнения этих условий A и B могут готовить код по frozen fixtures, C — тесты. Закрыть real-node gate с mock-ответом нельзя.

## Что существует сейчас и какие gaps предстоит закрыть

| Поверхность | Текущий факт из исходников | Целевое изменение |
|---|---|---|
| `AppRepository` | Только local import, один prepared-profile и selected-server в `SecureFileStore`; отдельных API sources нет | Добавить серверный источник с origin/owner/revision, сохранить local source и безопасный выбор |
| `DeviceIdentity` | RSA 3072 в Android Keystore; modern API 35+ — PS256/OAEP-256, legacy — RS256/OAEP-SHA1; существующий alias сохраняется | Объявлять реально проверенные capabilities; не пересоздавать identity на каждом refresh и не подменять её ECDSA |
| `RequestSigner` | Canonical v1, 16-byte nonce, encoded path без query/fragment, body до 1 MiB | HTTP client отправляет именно подписанные bytes и согласованные `X-HKVPN-*` headers |
| `HybridProfileDecryptor` | RSA-OAEP + AES-256-GCM, IV 12 bytes; decryptor сам не проверяет detached server signature и семантическую owner-binding AAD | До decrypt/activation проверять signed envelope, purpose/keyId, owner, expiry, revision и затем связность decrypted payload |
| Protected storage | `AtomicFile` атомарен для одного значения; две последовательные записи не образуют транзакцию | Commit нового профиля/selection/revision единым record либо recoverable journal; сбой не теряет предыдущий валидный профиль |
| VPN lifecycle | Один `HelloKittyVpnService`, engine registry, native callbacks, credential deadlines, kill switch, health polling | Подключить выдачу/renew/revoke и переход VERIFYING; не публиковать CONNECTED только после native start |
| Updates | Disabled manager; release origin и ключи ещё не подключены | Не включать OTA в этом этапе; сохранить проверки и отсутствие старых origins |

Время сервера из challenge ещё нельзя считать доверенным только потому, что JSON пришёл по TLS. Его доказательство, назначение ключа и bounded clock adjustment должны соответствовать контракту 01. Системные TLS certificate checks не отключаются при неверных часах.

## Интерфейсы, контракты и инварианты

### Identity и HTTP

- `deviceId = lowercase SHA256(DER SPKI)`; приватный RSA-ключ не экспортируется. API принимает объявленный алгоритм в рамках сохранённой key policy. PS256: SHA-256/MGF1-SHA256/salt length 32; RS256: SHA-256/PKCS#1 v1.5. Legacy fallback допускается только как известная capability устройства, не как повтор запроса с ослабленным алгоритмом после server rejection.
- Подписываются `v1\nMETHOD\nencodedPath\nepochSeconds\nnonce\ndeviceId\nSHA256(token)\nSHA256(body)`. Path/query/ingress normalization фиксирует 01. JSON сериализуется **один раз** до подписи и отправляется без повторной сериализации. Header `algorithm` берётся из `identity.requestSigningAlgorithm()`.
- Enrollment: challenge/complete, одноразовый challenge, invitation policy пилота и без user email/Telegram. Отдельные challenge/signing nonce не смешиваются. Public SPKI и capabilities входят в подписываемое тело complete.
- Access TTL, refresh rotation/reuse, request-nonce window и endpoint rate limits берутся из 01/02. Начальные значения общего плана: access 15 min, challenge 120 s, timestamp ±120 s, nonce TTL 5 min. Не дублировать их как независимо расходящиеся константы.
- На каждой сетевой попытке новый request nonce/signature; тот же `clientOperationId` и `Idempotency-Key` для повторяемой операции. Конфликт key/body — 409 и terminal error. Query не добавляется к v1 для удобства пагинации без новой signing версии.
- Собственный HTTPS origin задаётся контролируемой конфигурацией сборки; URL от QR/профиля не меняет API origin. Redirect на другой host, downgrade HTTP и автоматически пересланный bearer запрещены. Timeouts/content type/UTF-8 проверяются до decode. По проектному01 request≤1 MiB (обычно64 KiB), encrypted profile response≤2 MiB, decoded payload≤1 MiB, metadata≤16 KiB; request и response не смешивать из-за base64 overhead. Current decryptor bound4 MiB не становится новым HTTP cap автоматически. Final caps подтверждает freeze01.
- `application/problem+json` превращается в устойчивые локализованные состояния по code/status/retryable/Retry-After, не по тексту исключения. 401 допускает один сериализованный refresh; 403/revoked/expired не запускают бесконечный re-enrollment. 429/5xx — bounded backoff+jitter, cancellation прекращает retry.

### Signed profile envelope

- Ключи `profile`, `challenge/time`, `routing`, `OTA` имеют отдельные purpose/keyId. Проверку ECDSA P-256/SHA256 можно вынести из проверенного Direct verifier в общий модуль, сохранив strict DER/key checks и тесты; **приватные ключи и purpose не объединять**, Play не должен зависеть от Direct source set.
- До activation проверяются схема, `deviceId`, `profileId`, credential/access binding, issue/expiry, monotonic revision, algorithms, IV, ciphertext и signatureKeyId. Signature охватывает metadata, encryption algorithm, encryptedKey, IV, ciphertext и AAD по точным bytes/vectors этапа 01. AES-GCM authentication не заменяет server signature.
- До и после decrypt metadata сверяется с payload; changed owner/expiry/accessId отклоняется. Только затем parser/preparer/native validation, commit cache и публикация в Flow. Native error/неизвестный engine не заменяет рабочий профиль.
- Anti-rollback использует защищённый high-water mark отдельно для соответствующих owner/purpose, а не только `issuedAt` локальных часов. Нельзя откатить revision сменой `keyId`, bootstrap endpoint или process restart. Новая key transition проверяется доверенным старым ключом по 01; неизвестный key не скачивается по URL из недоверенного envelope.
- `subscriptionId`/`subscriptionExpiresAt` и `levik-relay` остаются только в изолированном legacy mapper, пока schema/native migration не согласована. Пользователь не видит «подписку». `liblevikrelay.so`, IPC, AAD и wire names нельзя переименовать общим replace; миграция требует Android/node/native совместимости и fixtures.

### Repository, offline и lifecycle

- Local и managed profiles различаются типом источника. Local import не получает серверную signature/grant задним числом; его нынешние bounds, invalid-import preservation, запрет HTTP subscription/executable и relay local import сохраняются. Remote receipt не стирает local input без явного пользовательского выбора.
- Token refresh — single-flight на приложение. При обрыве ответа после server rotation используют заранее утверждённое recovery/idempotency правило 01/02; повтор old refresh наугад может отозвать family. Pending rotation commit хранится crash-safe без plaintext token в preferences.
- Persist только encrypted records в `noBackupFilesDir`/Keystore. Не писать UUID/access/refresh/full config в history, clipboard, telemetry, `SavedStateHandle`, error dialogs или logcat. Буферы bytes очищать, где это допускает существующий API; не обещать полного обнуления immutable JVM strings.
- Offline valid cached credential работает без API до своей expiry, с проверенной ранее signature/revision. Отключение API не создаёт новое право доступа. Revoked локально credential не «восстанавливается» предыдущим cache/rollback. UI отличает «обновление не удалось, текущий ключ ещё действителен» от «ключ истёк».
- Renewal на плановой половине TTL и foreground/network recovery — bounded, serialized, без бесконечного polling. Новый профиль активируется после real-node ready; старый credential действует только в согласованном overlap. Force-close/Doze не должны передвигать expiry.
- Удаление managed device и удаление local profile — разные действия. Server revoke доставляется idempotent outbox; UI не утверждает немедленное завершение offline node sessions. Local source не отправляется API при delete/diagnostics.
- Один VpnService и один owner core/TUN. Mutable session связывается с generation; refresh/poll/старый callback не меняют новую выбранную сессию. UI видит PREPARING/CONNECTING/VERIFYING/CONNECTED и понятные terminal states; дополнительные стадии не ломают текущую композицию экранов.
- Начальная payload-проверка направляется через **VPN Network** на собственный HTTPS endpoint с nonce/expected response. Captive portal HTML, TCP handshake и direct response не являются успехом. Второй независимый контроль снижает ложные outage; UDP/DNS readiness отдельна от HTTPS readiness.

## Разделение работы между агентами

Главный агент интегрирует контракты и остаётся единственным writer общих файлов. Одновременно работают максимум **главный + 3 субагента**. Владение означает право записи; остальные могут читать и оставлять findings.

| Роль | File ownership | Входы | Артефакт и ответственность | Зависимость |
|---|---|---|---|---|
| Главный `android-integrator` | `AppContainer.kt`, `data/AppRepository.kt`, `ui/AppViewModel.kt`, `ui/HelloKittyVpnApp.kt`, `vpn/HelloKittyVpnService.kt`, manifests, Gradle, resources, shared `ApiModels.kt` | Handoff 00–03, отчёты A/B/C | Composition, source migration, UI/lifecycle; разрешает изменения общих контрактов | Все PR/patches сверяет последовательно |
| A `android-api-session` | Новые `core/network/HelloKittyApiClient.kt`, `MobileV1Models.kt`, `ApiOriginPolicy.kt`, `core/auth/DeviceEnrollment.kt`, `SessionCoordinator.kt`; собственные тесты этих модулей | Frozen OpenAPI/signing vectors; staging API02 | TLS/request client, enrollment/refresh/error/idempotency с cancellation | Начинает после 01, real calls после 02 |
| B `android-profile-security` | Новые `core/security/ProfileEnvelopeVerifier.kt`, `TrustedSigningKeys.kt`, `data/ManagedProfileStore.kt`, `vpn/ManagedProfileMapper.kt`; `HybridProfileDecryptor.kt` только по согласованному контракту; свои crypto/storage tests | Envelope vectors, current identity/decryptor/storage, node profile03 | Verify/decrypt/bind/prepare/commit; migration и anti-rollback | После 01; API DTO получает от A |
| C `android-integration-qa` | Новые tests/fixtures в отдельном каталоге, `docs/hellokitty/verification/stage-04/` | Согласованные public interfaces A/B, исследованные случаи, emulator/physical inventory | Contract negatives, crash/concurrency сценарии, real-node report, UI regression | Fixtures может готовить параллельно; Gradle/ADB только по очереди |

`DeviceIdentity.kt`, `RequestSigner.kt`, `SecureFileStore.kt`, текущие parser/preparer остаются за главным агентом при изменениях: B сообщает необходимый patch. Нельзя одновременно писать в repository/service/Gradle под разными подзадачами. QA не меняет assertions, чтобы разрешить неверный flavor или ослабленную проверку.

## Задачи и волны

| ID | Работа | Владелец | Зависимости | Готовность |
|---|---|---|---|---|
| S04-01 | Сверить signatures/envelope/HTTP caps, origin и capabilities с 01; утвердить interfaces A/B | Главный + A/B read-only | 00, 01 | Mismatch list пуст либо оформлен versioned contract change |
| S04-02 | HTTP transport с immutable bytes/signing/timeout/origin policy | A | S04-01 | Golden vectors и request captures совпадают с Go |
| S04-03 | Enrollment + single-flight refresh + crash-safe token rotation | A | S04-02, API02 | Replay/parallel/reuse tests, Direct без GMS |
| S04-04 | Signed envelope verifier, purpose/keyId, owner/revision/expiry checks | B | S04-01 | Cross-language/tamper/algorithm tests |
| S04-05 | Managed profile store/mapper + local source migration | B; composition — главный | S04-04 | Interrupted commit восстанавливается; local import сохранён |
| S04-06 | Operation polling/renew/revoke → repository → ViewModel | Главный | S04-03, S04-05, node03 | Pending ≠ usable; cancel/switch безопасны |
| S04-07 | VPN payload VERIFYING, generation/expiry binding, truthful error UI | Главный | S04-06 | Native handshake без payload не даёт CONNECTED |
| S04-08 | Новые unit/security/instrumentation tests + визуальная регрессия | C | A/B interfaces, затем S04-06/07 | Отчёты с конкретными assertions/реальными выводами |
| S04-09 | Issued profile → физический Android/эмулятор → real node; renew/revoke/outage | Главный + C | 02/03 ready, S04-08 | Gate regular payload и ownership/revoke evidence |
| S04-10 | Итоговый diff, migration/rollback и handoff05/06 | Главный | S04-09 | Нет old origins/общих credentials, полный manifest и statuses |

- **Волна 0:** главный утверждает S04-01. A получает signing/HTTP fixtures, B — envelope fixtures, C — testcase manifest; никто не меняет контракт независимо.
- **Волна 1:** A делает S04-02/03, B — S04-04/05, C готовит negative fixtures и аппаратную матрицу. Главный подготавливает минимальные integration seams без перезаписи их файлов.
- **Волна 2:** главный последовательно интегрирует S04-06/07, A/B читают diff и закрывают findings в своих поверхностях. C выполняет проверки после конкретного integration snapshot; изменения кода во время сборки исключены.
- **Волна 3:** один владелец Gradle/ADB выполняет S04-08/09, главный анализирует результаты и оформляет S04-10. Не запускать два Gradle daemon для этого checkout и не менять app/test APK между flavor без переустановки пары.

## Implementation checklist

1. Зафиксировать текущие package/minSdk/flavors/dependency locks, публичный API origin и feature flag managed enrollment. Не обновлять dependency ради нового HTTP client: сначала оценить Android/JDK HTTP и уже имеющийся набор; новое средство вводить только с обоснованием и lock/verification diff.
2. DTO отделить от legacy prepared-profile. Decode strict unknown engine/enum/version, nullable/required semantics по schema; reject oversized arrays/strings, duplicate/conflicting identity. Список серверов — реальные capabilities/capacity, без фиктивного fallback.
3. Добавить bounded API client и единый error mapper. Написать signing capture tests до repository integration. Проверить подпись тела после всех сериализаторов/interceptors, включая пустые GET/DELETE.
4. Реализовать enrollment/rotation через отдельный coordinator; mutex не держится во время длительного сетевого ожидания repository. Сохранить transaction/cancellation границы и reuse semantics API.
5. Реализовать verifier прежде mapper/decrypt activation. Использовать public fixtures, настоящие Keystore capability probes и отдельный тест modern/legacy OAEP. Invalid server response не очищает local либо ранее valid managed cache.
6. Сделать source-aware crash-safe state record. Проверить process death между записью нового profile/revision/selection, между server refresh commit/client commit, после готовности node и до доставки envelope.
7. Wire repository Flow и UI без аккаунтного кабинета: invitation/enrollment только минимально необходимое для бесплатного пилота. Сохранить кнопку local import, QR и подтверждение конфигурации при отключённом VPN. Не запускать VPN из foreground callback без существующего consent.
8. Wire operations/renew с единственным session owner. Смена сервера отменяет лишний poll, но не теряет operation ID; explicit idempotent cleanup ненужного lease согласован с API. Не создавать новую credential на каждый timeout.
9. Подключить VERIFYING и проверку полезного HTTPS тела. Endpoint может иметь separate internal client request token; он не становится API secret в публичном URL. Сравнивать nonce, status/body/max size; unexpected portal/response — ошибка стадии verification.
10. Проверить log/export redaction через fixtures с известными fake секретами, secret persistence, происхождение каждого outbound request и отсутствие старого origin. Локальная диагностика не отправляет полный профиль.
11. Выполнить unit → native instrumentation → physical/real-node проверки. По каждой несостоявшейся проверке указать `NOT RUN` либо `UNABLE TO RUN` с причиной; не подменять результаты старой очистки.

## File manifest

Все новые пути ниже планируемые; корень Android — `levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/`.

| Путь | Действие |
|---|---|
| `core/network/HelloKittyApiClient.kt`, `MobileV1Models.kt`, `ApiOriginPolicy.kt` | Новый собственный API; старый MobileApiClient не восстанавливать |
| `core/auth/DeviceEnrollment.kt`, `SessionCoordinator.kt` | New enrollment/rotation/coordinator |
| `core/security/ProfileEnvelopeVerifier.kt`, `TrustedSigningKeys.kt` | Новый shared verifier с purpose/key policy |
| `core/security/HybridProfileDecryptor.kt`, `DeviceIdentity.kt`, `SecureFileStore.kt` | Только требуемые approved изменения; preserve aliases/AAD compatibility |
| `data/ManagedProfileStore.kt`, `vpn/ManagedProfileMapper.kt` | Новый managed record и ограниченный legacy boundary |
| `data/AppRepository.kt`, `AppContainer.kt` | Composition, sources, cache/migration |
| `ui/AppViewModel.kt`, `ui/HelloKittyVpnApp.kt`, EN/RU strings | Minimal enrollment/state UI, дизайн сохранён |
| `vpn/HelloKittyVpnService.kt`, `VpnState.kt`, `TunnelHealthPolicy.kt` | Issued profile lifetime, VERIFYING, generation guards |
| `src/test/...`, `src/androidTest/...` | Golden/negative/state/concurrency/Keystore/native/UI tests |
| `contracts/*` | Read-only frozen inputs; изменения только через owner01 |
| `docs/hellokitty/verification/stage-04/` | Sanitized reports, matrices, migration/rollback/handoff |

Новые сборочные параметры и trust assets заносить в manifest handoff. Gradle/manifest/dependency changes пишет только главный и повторяет соответствующие distribution/lock/security gates.

## Проверки и ожидаемый дефект

**Все следующие новые проверки имеют статус NOT RUN.** Числа unit/instrumentation прежней очистки не являются целью количества новых тестов.

| Сценарий | Реалистичный дефект, который тест должен поймать | Доказательство |
|---|---|---|
| Kotlin/Go signing golden + path/body tamper | Interceptor пересериализует JSON или ingress нормализует path | Сервер отклоняет изменённый запрос; exact fixture проходит |
| 10 parallel reads при expired access | Каждый запрос независимо rotates refresh | Одна rotation, ответы валидны, family не отозвана случайно |
| Process death после server rotate | Cache хранит только уже использованный refresh | Recovery строго по contract; без silent new identity |
| Envelope: wrong device/purpose/keyId/expiry/ciphertext | Decrypt достаточен, owner/signature не проверяются | Каждая mutation rejected до commit/native start |
| Старый profile после restart/смены keyId | Revision high-water теряется или привязан только к keyId | Rollback rejected, valid cache остаётся |
| Disk full/interrupted state commit | Profile и selection сохраняются частично | Предыдущий profile/selection восстановлены, нет ложного CONNECTED |
| API недоступен: valid/expired cached credentials | Любая ошибка удаляет profile либо продлевает себе expiry | Valid offline соединяется; expired fail-closed |
| Operation pending или stale callback при server switch | UI берёт непроверенный lease/чужую generation | Новая сессия неизменна, pending не usable |
| Native handshake, заблокирован payload/captive portal | CONNECTED устанавливается до verification | VERIFYING → конкретная ошибка, без false success |
| Real revoke/renew | Клиент только удалил UI state, node credential всё ещё пригодна | Read-back node + new connection/established-session результат по этапу 03 |
| Direct/Play и legacy/modern Keystore | Тест APK одного flavor установлен поверх другого; modern capability ошибочно универсальна | Собственная APK/test APK пара и actual algorithm evidence |
| Local import/QR/TV/widget/settings | Managed enrollment вытеснил локальную работу либо сменил дизайн | Existing regression suite + screenshots + invalid-import preservation |
| Fake-secret log/export scan | DTO toString/logging раскрывает bearer/UUID/key | Known sentinel отсутствует в stdout/log/export/screenshot |

Последовательные команды выполняются из `levik_vpn_android/`; значения toolchains сверяются с действующими lock, а не обновляются до latest:

```bash
bash ../scripts/ci/fetch-libxray.sh
ANDROID_HOME=/opt/android-sdk ./gradlew :app:testPlayDebugUnitTest :app:testDirectDebugUnitTest --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g
ANDROID_HOME=/opt/android-sdk ./gradlew :app:lintPlayDebug :app:lintDirectDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g
ANDROID_HOME=/opt/android-sdk ./gradlew :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g
```

Для Direct native packaging у предыдущего этапа есть отдельные pinned Go/NDK, а не новая глобальная установка:

```bash
GO_BIN=/root/.cache/hellokittyvpn-toolchains/go-1.26.5/bin/go ANDROID_NDK_HOME=/root/.cache/hellokittyvpn-toolchains/android-sdk/ndk/29.0.14206865 GOMAXPROCS=2 ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleDirectDebug :app:assembleDirectDebugAndroidTest :app:verifyDirectReleaseRuntimeClasspath verifyAllDependencyLocks --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g
```

После inventory `adb devices -l` выбрать одно разрешённое устройство и установить **обе** APK одного flavor; пример ниже — Direct. Команды не публикуют APK и не подключают реальные credentials автоматически:

```bash
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/direct/debug/app-direct-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/direct/debug/app-direct-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w org.hellokittyvpn.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`DEVICE_SERIAL` заменить конкретным serial из inventory. Actual instrumentation output должен содержать итог `OK (...)`; exit code adb сам по себе не доказывает прохождение. Для Play установить соответствующую Play-пару. Нельзя запускать сборку нового snapshot одновременно с тестами старого APK. XML JUnit/lint и хеши установленных artifacts занести в отчёт.

## Gates, rollback и handoff

**G04-A:** frozen Kotlin/Go vectors совпадают, origin policy/authorization/tamper/rotation tests пройдены, local source и cache migration проверены. **G04-B:** оба flavor проходят unit/lint/build и собственную instrumentation APK пару; warnings перечислены. **G04-C:** собственный API выдаёт двум устройствам различные owner-bound credentials, настоящий node03 их применяет, хотя бы один физический Android выполняет authenticated HTTPS payload через regular exit; UDP/DNS evidence и revoke/renew зафиксированы отдельно. Эмуляторный payload полезен, но не заменяет physical gate.

Переход к полноценному этапу 05 допустим после G04-C: не отлаживать новый DNS/IPv6 поверх ещё нерабочего regular provisioning. Исследовательский routing spike/fixtures можно готовить раньше read-only. VK не является условием закрытия regular gate.

Rollback: выключить managed enrollment/новую активацию feature flag, сохранить local source и последнюю **неотозванную и неистёкшую** совместимую managed запись; отозванные credentials/refresh не восстанавливать. Не удалять Keystore identity для устранения ошибок. При schema migration хранить безопасный backward-readable record либо выполнять проверенный forward repair; downgrade без обхода anti-rollback. При подозрении компрометации revoke важнее сохранения cached подключения.

Handoff включает schema/key-purpose versions, API/node build IDs, redacted operation correlation IDs, package/flavor/ABI/APK hashes, installed test APK hashes, test outputs, real device/OS/operator/network details, список изменённых файлов, compatibility migration и rollback rehearsal. Для каждого gate — `VERIFIED`, `FAILED`, `NOT RUN` или `UNABLE TO RUN`. Этап 05 получает интерфейсы session generation/credential deadline/profile source; этап 06 — frozen relay capability/legacy mapper boundary, без предположения, что VK уже работает.
