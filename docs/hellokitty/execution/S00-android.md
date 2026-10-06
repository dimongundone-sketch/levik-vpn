# S00-ANDROID / Аудит Android-клиента — краткий результат

Дата UTC: 2026-10-06
Главный агент / исполнитель: android-audit (координатор: Главный агент)
Source baseline HEAD + working snapshot hash: HEAD `0d05186a3436028f9392e22cbbc30781b0e59129`, working tree dirty (214 tracked modified/deleted + 15 untracked paths)
Scope / разрешённые paths: `docs/hellokitty/execution/S00-android.md`
Prerequisites / gate evidence: S00-BASE snapshot taken; координатор зафиксировал baseline репозитория и запустил задачу S00-ANDROID.

## Что изменено

Произведён полный read-only аудит кодовой базы Android-клиента (`levik_vpn_android/`), конфигураций сборки Gradle, цепочек зависимостей, криптографических компонентов и нативных привязок. Исходный код приложения, нативные библиотеки, тесты и файлы Gradle НЕ изменялись.

Файлы и существенные решения:
- Создан документ аудита: [`docs/hellokitty/execution/S00-android.md`](file:///root/projects/hellokittyvpn/docs/hellokitty/execution/S00-android.md).
- Исходники `levik_vpn_android/` сохранены в неприкосновенности (read-only invariant соблюдён на 100%).
- Проведена полная инвентаризация 10 ключевых компонентов-потребителей (`AppContainer`, `AppRepository`, `AppViewModel`, `HelloKittyVpnService`, `RequestSigner`, `DeviceIdentity`, `HybridProfileDecryptor`, `SecureFileStore`, `XrayConfigBuilder`, `XrayRuntime`).
- Зафиксированы параметры SDK, toolchains, lock-файлы зависимостей и strict verification metadata.
- Проведено сопоставление исторических результатов верификации очистки от 2026-10-06 и текущих фактических артефактов на диске.

---

### 1. Карта компонентов и потребителей контрактов (Current Consumers)

1. **[`AppContainer`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/AppContainer.kt)**:
   - **Роль**: Главный контейнер внедрения зависимостей (DI root) приложения.
   - **Жизненный цикл**: Создаётся при старте [`HelloKittyVpnApplication`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/HelloKittyVpnApplication.kt).
   - **Управляемые сущности**:
     - `nativeCleanupScope`: SupervisorJob + Dispatchers.IO.
     - `json`: `Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }`.
     - `updateManager`: [`DisabledAppUpdateManager`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/update/AppUpdateManager.kt) — фоновые обновления намеренно отключены до развертывания собственного релизного сервера.
     - `deviceIdentity`: [`DeviceIdentity`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/DeviceIdentity.kt).
     - `secureStore`: [`SecureFileStore`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/SecureFileStore.kt).
     - `settings`: [`AppSettings`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/data/AppSettings.kt).
     - `russianRoutingData` и `lteRoutingData`: парсеры списков обхода РФ и LTE.
     - `xrayRuntime`: [`XrayRuntime`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/XrayRuntime.kt).
     - `tunnelEngineRegistry`: фабрика движков туннеля (Play vs Direct).
     - `repository`: [`AppRepository`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/data/AppRepository.kt).
     - `vpnController`: [`VpnController`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/VpnController.kt).
     - `wifiMonitor`: [`WifiAutoConnectMonitor`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/WifiAutoConnectMonitor.kt).

2. **[`AppRepository`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/data/AppRepository.kt)**:
   - **Роль**: Центральное хранилище состояния профилей и серверов.
   - **Потребители**: [`AppViewModel`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/ui/AppViewModel.kt).
   - **Поведение**:
     - Принимает текст конфигурации через `importProfile(text)`.
     - Использует [`LocalProfileInput`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/data/LocalProfileInput.kt) и [`TunnelProfilePreparer`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/TunnelProfilePreparer.kt).
     - Сохраняет результат в зашифрованный `secureStore.put(SecureFileStore.TUNNEL_PROFILE, bytes)` и `SELECTED_SERVER`.
     - Обеспечивает безопасную очистку оперативной памяти (`bytes.fill(0)` в блоках `finally`).
     - Предоставляет `cachedTunnel()` и `selectServer(serverId)`.

3. **[`AppViewModel`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/ui/AppViewModel.kt)**:
   - **Роль**: ViewModel для Compose UI экрана (`HelloKittyVpnApp`).
   - **Потребители**: UI-компоненты экранов Home, Servers, Stats, Profile/Settings.
   - **Поведение**:
     - Реактивно слушает `repository.tunnelProfile` и `vpnController.state`.
     - Контролирует операцию `importProfile(text)` (разрешена только когда VPN не подключён / в пингуемом статусе).
     - Инициирует подключение через `vpnController.connect()` с проверкой срока годности профиля (`cachedProfileIsUsable`) и согласия пользователя на VPN disclosure.
     - Управляет настройками обхода (`setBypassRussianTraffic`), anti-DPI (`setAntiDpiPreset`), DNS, kill switch.
     - Запускает ручную диагностику сети (`NetworkDiagnostics.runDiagnostics`).

4. **[`HelloKittyVpnService`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/HelloKittyVpnService.kt)**:
   - **Роль**: Системная служба `VpnService` Android.
   - **Потребители**: Системный сетевой стек Android, команды от `VpnController`.
   - **Поведение**:
     - Создаёт и настраивает системный виртуальный сетевой интерфейс (`TUN`).
     - Применяет сетевые маршруты через [`VpnRoutes`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/VpnRoutes.kt).
     - Применяет split tunneling (пакеты приложений через `builder.addDisallowedApplication` / `addAllowedApplication`).
     - Интегрирован с движками туннелирования через `TunnelEngineRegistry` (поддерживает Xray и Relay).
     - Реализует политику самовосстановления (`TunnelHealthPolicy`) и переключения серверов (`MobileServerSwitchPolicy`).
     - Реализует app-level Kill Switch (блокирующий blackhole маршрут при обрыве).

5. **[`RequestSigner`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/network/RequestSigner.kt)**:
   - **Роль**: Канонический генератор криптографических подписей HTTP-запросов.
   - **Формат подписи v1**: `v1\n$method\n$path\n$timestamp\n$nonce\n$deviceId\n$tokenHash\n$bodyHash`.
   - **Текущий статус**: **DORMANT (в спящем режиме)**. После удаления старого `MobileApiClient` класс не вызывается в рабочем коде приложения, но полностью покрыт тестами [`RequestSignerTest`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/test/java/org/hellokittyvpn/android/core/network/RequestSignerTest.kt).
   - **Назначение**: Будущий потребитель для взаимодействия с новым Hello Kitty Backend API (этап 01–03).

6. **[`DeviceIdentity`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/DeviceIdentity.kt)**:
   - **Роль**: Менеджер аппаратной идентичности устройства на базе `AndroidKeyStore`.
   - **Ключи**: RSA-3072, алиасы `kitty_device_identity_v2` (legacy) и `kitty_device_identity_v3` (modern).
   - **Поддерживаемые алгоритмы**:
     - Подпись: `PS256` (API 35+) и `RS256` (legacy).
     - Расшифровка: `RSA-OAEP-256` и `RSA-OAEP`.
   - **Поведение и ограничения**: Пытается использовать StrongBox (на Android 9+), но содержит явный fallback без StrongBox (TEE/software) при `ProviderException` / `InvalidAlgorithmParameterException`. Допускает fallback с `MODERN` на `LEGACY` при неподдерживаемых параметрах Keystore. Не гарантирует исключительно аппаратную изоляцию уровня StrongBox на всех устройствах. Генерирует стабильный `deviceId` как SHA-256 hex от SPKI публичного ключа.

7. **[`HybridProfileDecryptor`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/HybridProfileDecryptor.kt)**:
   - **Роль**: Асимметричный дешифратор конвертов профилей (`TunnelProfileEnvelope`).
   - **Алгоритм**: Расшифровывает симметричный 256-битный ключ через `DeviceIdentity.decryptProfileKey` (RSA-OAEP), затем расшифровывает тело профиля через `AES/GCM/NoPadding` с проверкой IV (12 байт) и AAD (до 2048 байт).
   - **Ограничения безопасности**: Выполняет исключительно расшифровку шифротекста, но сам **НЕ проверяет серверную подпись**, не выполняет семантическую привязку профиля к `deviceId` / `accessId`, не проверяет сроки годности (`credentialExpiresAt`) и anti-rollback ревизии. Называть эти гарантии выполненными без реализации проверки серверной подписи и метаданных профиля нельзя.
   - **Текущий статус**: **DORMANT (в спящем режиме)**. Требует расширения до безопасного конверта профиля v2 на этапе 01.

8. **[`SecureFileStore`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/SecureFileStore.kt)**:
   - **Роль**: Локальное шифрованное файловое хранилище.
   - **Размещение**: `context.noBackupFilesDir / "kitty_secure_v1"`.
   - **Шифрование**: Ключ AES-256 в `AndroidKeyStore` (`kitty_local_storage_v1`), `AES/GCM/NoPadding`, AAD с префиксом `levik-secure-file:v1:$name`.
   - **Атомарность**: Использование `AtomicFile`. Стирание буферов в `finally`.
   - **Хранимые сущности**: `tunnel_profile`, `selected_server`, `last_regular_server`, `vpn_disclosure_consent`, `session_token`, `pending_revocation_token`.

9. **[`XrayConfigBuilder`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/XrayConfigBuilder.kt)**:
   - **Роль**: Генератор JSON-конфигурации для ядра Xray.
   - **Поведение**:
     - Настраивает TUN inbound (`levik-tun-in`, MTU 1500, дескриптор `xray.tun.fd`).
     - Создаёт маршруты: `directCidrs`, `directDomains` (РФ домены, LTE CIDR), `proxyDomains`.
     - Добавляет `fragment` dialer для Anti-DPI (outbound `levik-fragment`).
     - **Блокирует IPv6**: правило маршрутизации перенаправляет весь трафик `::/0` в outbound `levik-block` (blackhole).
     - Настраивает синтетический профиль для Relay SOCKS proxy (`RELAY_PROXY_TAG` = "levik-relay-proxy").

10. **[`XrayRuntime`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/XrayRuntime.kt)**:
    - **Роль**: JNI-мост к официальной библиотеке `libXray` (AAR v26.7.28).
    - **Поведение**:
      - Загружает библиотеку `gojni`.
      - Регистрирует `DialerController` и `ListenerController` для защиты сокетов (`protectFd`) от закольцовывания в VPN.
      - Вызывает `convertShareLinksToXrayJson` для нативной конвертации ссылок в JSON outbounds.
      - Управляет жизненным циклом ядра через `runXrayFromJson` и `stopXray` с поддержкой аренды (`CoreOwnershipRegistry`).

---

### 2. Исходники, SDK, runtime hashes и фиксации

#### 2.1 Параметры Android проекта
- **Namespace**: `org.hellokittyvpn.android`
- **Application ID**: `org.hellokittyvpn.android` (debug: `org.hellokittyvpn.android.debug`)
- **minSdk**: `26` (Android 8.0 Oreo)
- **compileSdk**: `36`
- **targetSdk**: `36` (Android 16)
- **versionCode**: `69`
- **versionName**: `0.1.0` (debug: `0.1.0-debug`)
- **Java / Kotlin Target**: Java 17 (`JvmTarget.JVM_17`, `JavaVersion.VERSION_17`)
- **Android Gradle Plugin (AGP)**: `8.13.2`
- **Gradle Wrapper**: `8.13` (SHA256: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`)
- **Kotlin**: `2.3.20`
- **Compose Compiler / Plugin**: `2.3.20`
- **Compose BOM**: `2026.06.00`
- **CycloneDX Plugin**: `3.4.1`

#### 2.2 Фиксация зависимостей и strict metadata
- **Dependency Locking**: режим `LockMode.STRICT` активирован для всех конфигураций во всех проектах.
- **Root lockfile**: `levik_vpn_android/gradle.lockfile`
  - SHA256: `a3873f6e3cd44adcc848f047eaf8dc83cec22acff9d8aae86875e28bb7537d86`
- **App lockfile**: `levik_vpn_android/app/gradle.lockfile`
  - SHA256: `1fa0112eb7ba6be1ea983576d27ad1743afad8b3e421428d865e0271a3d64431` (335 зафиксированных модулей)
- **Verification Metadata**: `levik_vpn_android/gradle/verification-metadata.xml`
  - SHA256: `7caf3dd341dcada68a3b56d99a3b27c387ae4b1a9f0c05617ff1a42d5dbb2265`
  - Строгая проверка чексумм артефактов и POM-файлов (`verify-metadata: true`, `verify-signatures: false`).
  - Содержит зафиксированный родительский POM Guava `com.google.guava:guava-parent:33.3.1-android` (SHA256 `6e11986ea7250b51f847157e2dc937f32a306804dfce0007a5e81ddb9b95c579`).

#### 2.3 Нативные бинарные зависимости и ассеты
- **`libXray.aar`** (`levik_vpn_android/app/libs/libXray.aar`):
  - Версия: `v26.7.28`
  - SHA256: `4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d` (побайтово проверено)
- **Relay Native Libraries (`liblevikrelay.so`)** (`levik_whitelist_relay/build/android/jniLibs/`):
  - `arm64-v8a`: SHA256 `ab6c5c9b6929ad52a6d429966eb3569008eed183e6b84ab2bdbf0c81201d3f09`
  - `armeabi-v7a`: SHA256 `bbdb6667aeb4fed9b95130c05b2d3d585143cb0c732cf069748ca932228d08f2`
  - `x86_64`: SHA256 `c824ddafec87d7c25a2beee2d900f1d582109b44f44bf130b247a8dc8f88f1f2`
- **Toolchains locks** (`levik_whitelist_relay/source/tools.lock`):
  - `GO_VERSION=1.26.5` (архив SHA256: `5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053`)
  - `ANDROID_NDK_VERSION=29.0.14206865`
  - `ANDROID_PAGE_SIZE=16384` (16 KiB alignment проверен)
- **LTE routing assets** (`levik_vpn_android/app/src/main/assets/`):
  - `lte_whitelist_domains.txt`: SHA256 `dfa4ffeec6c97a6feb7c594934d0c8b4e170fadff1d781f0de012ee383832eb9` (910 строк)
  - `lte_whitelist_ipv4.cidr`: SHA256 `149d27a8e3502b95b9a378817854c87af6417c2a9bbf0b17eb8f7affef1f3868` (30 228 строк)

#### 2.4 Существующие собранные APK артефакты
- **Direct debug APK**: `levik_vpn_android/app/build/outputs/apk/direct/debug/app-direct-debug.apk`
  - Размер: 140 203 640 байт
  - SHA256: `276cfd4f420956a01a94c3c5d9d21aab4bcbc3d9e22911c41834636f968f1899`
- **Play debug APK**: `levik_vpn_android/app/build/outputs/apk/play/debug/app-play-debug.apk`
  - Размер: 219 707 314 байт
  - SHA256: `8c2282528c50b774f31f6b6c2b606292888dda36553fe4e66032804622b24b28`

---

### 3. Как Android-клиент работает в текущем состоянии

1. **Локальный импорт (v2ray / Xray JSON / share links)**:
   - Входные данные: пользователь вставляет текст через буфер обмена или сканирует QR-код камерой (`CameraX` + `ZXing`).
   - Валидация входных данных ([`LocalProfileInput`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/data/LocalProfileInput.kt)):
     - Размер строго от 1 байта до 1 048 576 байт (1 MiB UTF-8).
     - Формат: JSON либо строки со схемами `vless://`, `vmess://`, `trojan://`, `ss://`, `hysteria2://`, `hy2://`.
     - Загрузка профилей по внешним URL (HTTP/HTTPS subscriptions) полностью заблокирована во избежание сетевых инъекций.
     - Профили с `engine != TunnelEngineKind.XRAY` блокируются (`require(candidate.engine == TunnelEngineKind.XRAY)`): импорт relay-профилей вручную запрещён.
   - Нативная конвертация: `XrayRuntime.convertProfile` вызывает Go-метод `convertShareLinksToXrayJson` в `libXray`. Нативное ядро парсит протоколы, формирует outbounds, нормализует Reality SNI и параметры.
   - Лимиты: максимум 200 серверов в профиле. Невалидный импорт выбрасывает исключение и сохраняет предыдущий рабочий профиль без изменений.

2. **Шифрование через Android Keystore**:
   - Локальные настройки и активный профиль шифруются в [`SecureFileStore`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/SecureFileStore.kt) с использованием ключа AES-256 в `AndroidKeyStore`.
   - Режим `AES/GCM/NoPadding`, 96-битный IV (генерируется аппаратно при каждой записи), 128-битный auth tag, AAD с префиксом `levik-secure-file:v1:$name`.
   - Ключ устройства в [`DeviceIdentity`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/DeviceIdentity.kt) создаётся как RSA-3072 с запросом StrongBox (на Android 9+), но с обязательным fallback на обычный Keystore (TEE/software) в случае сбоя StrongBox.
   - Все конфиденциальные буферы оперативной памяти (`ByteArray`) гарантированно затираются нулями (`fill(0)`) в секциях `finally`.

3. **Блокировка IPv6 (Leak Prevention / Fail-Closed)**:
   - В маршрутизации Android ([`VpnRoutes`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/VpnRoutes.kt)): для предотвращения утечки IPv6 трафика мимо VPN на физический интерфейс сотового оператора TUN-интерфейс захватывает IPv6 (через `::/0` или нативные исключения Android 13+).
   - В маршрутизации Xray ([`XrayConfigBuilder`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/vpn/XrayConfigBuilder.kt)):
     - Добавлено жесткое правило: трафик с входящего интерфейса `levik-tun-in` с адресом `::/0` безусловно направляется в outbound `levik-block` (blackhole).
     - Политикой DNS `queryStrategy` установлено значение `UseIPv4`.
     - Настоящий IPv6 dual-stack egress в текущей версии клиента отсутствует: весь IPv6 трафик глушится внутри TUN для обеспечения приватности и исключения DNS/traffic leaks.

4. **Текущее состояние Relay-адаптера**:
   - **Play flavor**: Полная изоляция. Relay вырезан из сборки (`HKVPN_RELAY_ENABLED = false`), нативная библиотека `liblevikrelay.so` отсутствует. Задачи верификации Gradle `verifyPlayRelayExclusion` и `verifyPlayPackagedRelayExclusion` контролируют отсутствие любых остатков relay в APK Play.
   - **Direct flavor**: Код адаптера [`RelayTunnelEngineAdapter`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/direct/java/org/hellokittyvpn/android/vpn/RelayTunnelEngineAdapter.kt) полностью скомпилирован и зарегистрирован в `TunnelEngineRegistry`.
     - Архитектура: запуск нативного ELF-процесса `liblevikrelay.so`, обмен через абстрактные сокеты `@kitty_wlr_control_*` и `@kitty_wlr_protect_*`.
     - Защита сокетов: передача файловых дескрипторов через `SCM_RIGHTS` и вызовы `VpnService.protect` + `Network.bindSocket`.
     - Аутентификация: модуль `AndroidRelayVkTurnProvider` запрашивает TURN-креды сессии VK.
     - Сеть: нативный процесс поднимает локальный SOCKS5-прокси, а Xray инкапсулирует исходящий трафик в него (`levik-relay-proxy`).
   - **Статус доступности**: **DORMANT (в спящем режиме)**. Хотя весь нативный и котлиновский стек Direct готов, локальный импорт блокирует создание профилей типа `LEVIK_RELAY`. Адаптер активируется только тогда, когда backend Hello Kitty начнет отдавать сертифицированные relay-конфиги через защищённый протокол выдачи.

---

## Задачи и владельцы

| Task ID | Owner | Write paths | Dependencies | Status | Evidence |
|---|---|---|---|---|---|
| S00-ANDROID | `android-audit` | `docs/hellokitty/execution/S00-android.md` | S00-BASE | VERIFIED | Полный read-only аудит исходников, SDK, runtime hashes, consumers и relay завершён; ограничения безопасности зафиксированы |

---

## Проверки

Ниже приведена матрица проверок: разделение текущих read-only проверок субагента от исторических датированных запусков очистки 2026-10-06.

| Check | Command / environment | Status | Actual result / evidence | Limits |
|---|---|---|---|---|
| Read-only code audit | Чтение `levik_vpn_android/app/src/` | VERIFIED | Изучены все 10 consumers, архитектура, шифрование, сетевые маршруты, JNI | Исходники не изменялись |
| Dependency locks audit | `sha256sum levik_vpn_android/*.lockfile` | VERIFIED | Root lockfile `a3873f6...`, app lockfile `1fa0112...` (335 modules) | Strict locking активен |
| Strict verification metadata | `sha256sum levik_vpn_android/gradle/verification-metadata.xml` | VERIFIED | SHA256 `7caf3dd341dcada68a3b56d99a3b27c387ae4b1a9f0c05617ff1a42d5dbb2265` | Чексумма parent POM Guava на месте |
| libXray AAR pin check | `sha256sum levik_vpn_android/app/libs/libXray.aar` | VERIFIED | SHA256 `4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d` совпадает с Gradle build script | Версия v26.7.28 |
| Relay native JNI libs check | `find levik_whitelist_relay/build/android/jniLibs -type f -exec sha256sum {} +` | VERIFIED | arm64-v8a (`ab6c5c9...`), armeabi-v7a (`bbdb666...`), x86_64 (`c824dda...`) присутствуют и соответствуют сборке | 16 KiB alignment |
| Debug APK outputs check | `sha256sum app-direct-debug.apk app-play-debug.apk` | VERIFIED | Direct (`276cfd4...`, 140M), Play (`8c22825...`, 219M) побайтово совпадают с `app-cleanup.md` | Существующие debug артефакты |
| Unit tests Play (Historical) | Historical XML reports (2026-10-06 19:16 UTC) | VERIFIED (historical) | 141 тест, 0 failures, 0 errors, 0 skipped (`testPlayDebugUnitTest`) | Не перезапускались в S00 (read-only) |
| Unit tests Direct (Historical) | Historical XML reports (2026-10-06 19:16 UTC) | VERIFIED (historical) | 169 тестов, 0 failures, 0 errors, 0 skipped (`testDirectDebugUnitTest`) | Не перезапускались в S00 (read-only) |
| Lint Play Debug (Historical) | Historical report (2026-10-06 19:31 UTC) | VERIFIED (historical) | 0 errors, 26 warnings, 3 hints (`lint-results-playDebug.txt`) | Не перезапускался в S00 |
| Lint Direct Debug (Historical) | Historical report (2026-10-06 19:43 UTC) | VERIFIED (historical) | 0 errors, 31 warnings, 3 hints (`lint-results-directDebug.txt`) | Не перезапускался в S00 |
| Instrumentation tests (Historical) | Android 13 x86_64 Redroid (2026-10-06 UTC) | VERIFIED (historical) | Play: `OK (14 tests)`, Direct: `OK (14 tests)` | Не перезапускались в S00 |
| Toolchains availability | System Java 17, cached Go 1.26.5 & NDK 29 | VERIFIED | Java 17.0.20.1, Go 1.26.5 (`5c2c3b1...`), NDK 29.0.14206865 в `/root/.cache/hellokittyvpn-toolchains/` | Системные toolchains не затронуты |
| Re-run Gradle build / tests | `./gradlew test...` / `assemble...` | NOT RUN | Намеренно не запускались: инвариант этапа 00 (строго read-only аудит, избежание OOM и инвалидации кэшей) | Отсутствие изменений в коде |
| Production Signed Release | `assembleDirectRelease`, `assemblePlayRelease` | NOT RUN | Релизные ключи и прод-сертификаты отсутствуют (заблокировано до этапа 08) | Блокер: Keystore & Signing |
| Real VK Relay / SIM field tests | Реальный трафик через операторов РФ | NOT RUN | Отсутствуют тестовые учетные записи VK и SIM-карты операторов РФ | Блокер: SIM & VK accounts |

---

## Gate

**G00: IN PROGRESS (в части S00-ANDROID аудит завершён, ожидает общей интеграции G00).**
- Read-only аудит кодовой базы клиента выполнен на 100%.
- Архитектурная карта 10 потребителей задокументирована.
- Зафиксированы реальные ограничения: `DeviceIdentity` допускает fallback без StrongBox; `HybridProfileDecryptor` расшифровывает payload, но не валидирует цифровую подпись сервера и семантические метаданные.
- Все хеши зависимостей, нативных библиотек и lock-файлов зафиксированы.
- Исторические тесты очистки (2026-10-06) верифицированы по отчётам и отделены от статуса текущего запуска.

---

## Совместимость и риск

1. **Разделение Flavor (Direct vs Play)**:
   - Play flavor полностью исключает Relay (`HKVPN_RELAY_ENABLED = false`), защищен от наличия нативного ELF-файла проверками Gradle.
   - Direct flavor содержит полный стек Relay, но блокирует ручной импорт relay-конфигов до появления серверной выдачи.
2. **Размер APK и память хоста**:
   - Размер Direct APK (140 MiB) и Play APK (219 MiB) обусловлен включением тяжелого нативного ядра `libXray` для 3–4 архитектур (arm64-v8a, armeabi-v7a, x86_64).
   - При сборке Gradle потребляет более 1.5–2 GiB RAM на воркер. Учитывая заполнение swap на хосте (91%), любые будущие сборки Gradle обязаны выполняться с `--max-workers=1` и `-Dorg.gradle.jvmargs="-Xmx2048m"`.
3. **Сохранённые исторические маркеры (Internal Identifiers)**:
   - В коде сохранены внутренние строки: префикс AAD `levik-secure-file:v1:`, теги Xray `levik-tun-in`, `levik-direct`, `levik-block`, имена файлов `liblevikrelay.so`. Это сделано сознательно для сохранения целостности нативного IPC и совместимости форматов сохраненных данных. Их замена на этапе 01–03 потребует согласованной миграции данных.
4. **IPv6**:
   - В текущей реализации IPv6 трафик глушится для предотвращения утечек. Добавление dual-stack IPv6 egress потребует согласованного изменения контрактов сервера и `XrayConfigBuilder`.

---

## Передача следующему этапу (Handoff к этапу 01)

### Артефакты и пути
- Файл отчёта: `docs/hellokitty/execution/S00-android.md`
- Исходники клиента: `levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/`
- Нативные библиотеки: `levik_vpn_android/app/libs/libXray.aar`, `levik_whitelist_relay/build/android/jniLibs/`
- Debug APK: `levik_vpn_android/app/build/outputs/apk/{direct,play}/debug/`

### Входные данные для этапа 01 (Contracts and Security)
- Готовые спящие потребители серверного API:
  - [`RequestSigner`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/network/RequestSigner.kt) (канонический запрос v1 с метками времени, nonce и SHA-256 хешами).
  - [`DeviceIdentity`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/DeviceIdentity.kt) (RSA-3072 ключ для подписи и OAEP расшифровки, PS256/RS256, с поддержкой fallback без StrongBox).
  - [`HybridProfileDecryptor`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/main/java/org/hellokittyvpn/android/core/security/HybridProfileDecryptor.kt) (дешифрование AES-256-GCM + RSA-OAEP; требует проектирования контрактной проверки серверной подписи и семантической привязки в профиле v2).
- Требуется согласовать схему публичного мобильного API `/v1` (`/v1/devices/challenges`, `/v1/devices/complete`, `/v1/tokens/refresh`, `/v1/profiles` и др.), не копируя приватный WDTT API нод (`/internal/v1/leases/*`).
