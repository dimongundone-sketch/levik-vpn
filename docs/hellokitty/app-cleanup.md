# Очистка Android-приложения

Дата: 2026-10-06 UTC. Внесены реальные изменения исходников; серверная часть пока только спроектирована. База: `0d05186a3436028f9392e22cbbc30781b0e59129`.

## Что изменено

Название — **Hello Kitty VPN**, package/namespace `org.hellokittyvpn.android`, versionName `0.1.0`. Debug package — `org.hellokittyvpn.android.debug`. Новый package изолирует приложение от установленного Levik и его данных; это отдельная установка, автоматической миграции чужих аккаунтов нет. Исторический versionCode69 сохранён; релизные ключи не создавались и прежние ключи не используются.

Сохранены Compose-тема, палитра, анимация кнопки подключения, основные карточки Home/Servers/Stats, фильтры, избранное, ping, светлая/тёмная/AMOLED темы, настройки DNS/маршрутов/anti-DPI/split tunneling, Wi-Fi protection, pause/resume, boot, уведомления, tile/widget и Android TV navigation. Вместо страницы аккаунта — локальные настройки. Логотипы launcher/light/dark/mono/TV заменены собственными vector assets с котом и бантом; прежние PNG, включая monochrome mask, удалены.

Удалены пользовательские поверхности и код: Levik/Telegram login, account linking и activation links, тарифы/платежи/пробные периоды, подписки/управление устройствами прежнего кабинета, referrals, чужая поддержка, upload diagnostics, карта белых списков и фоновая телеметрия, старый MobileApiClient, subscription workers/notifications, внешний guard provider. Убраны cabinet base URL, прежние deep links и разрешение OTA скачивать APK из Nort321/levik-vpn. В обоих flavor платежи отключены.

Не добавлены фиктивные домены рабочего API или серверы в список. Независимый `AppRepository` импортирует локальные VPN links/полный Xray JSON, преобразует через существующий native runtime и хранит конфигурацию в Android Keystore/AES-GCM store. Сканирование QR использует ту же валидацию, что вставка текста. HTTP subscription URL и executable/file inputs не загружаются/не исполняются. Размер ограничен 1 MiB UTF-8, число серверов — существующим лимитом200. Неудачная валидация до записи сохраняет предыдущую конфигурацию. Импорт/удаление доступны при отключённом VPN.

На главном экране отображается локальный объём сессии; коммерческие quota/«безлимит» не используются. Диагностика вручную проверяет перечисленные внешние сервисы, показывает модель/ОС и предлагает системный share sheet. Ничего не отправляется прежней поддержке. Определение IP через чужой API удалено; IP отображается неизвестным до собственного endpoint.

Снят host-specific автоматический XHTTP mux для сервера прежнего продукта. Импортированные transport settings должны задаваться самим профилем; общий config builder и защита сокетов сохранены. Сохранены credential expiry и relay fail-closed guards. Polling старой платной подписки удалён; собственная серверная revocation будет реализована вместе с lease API. Relay-профили через local import запрещены до серверной выдачи.

## Что сохранено сознательно

Внутренние native/wire identifiers `levik-relay`, `liblevikrelay.so`, legacy `subscriptionId`/expiry fields и relay entitlement policy нужны для совместимости с закреплённым fork и сериализованными структурами. Они не создают аккаунт, оплату и запрос к чужому backend. Их согласованная замена на access/credential в protocol v2 входит в [план](implementation-plan.md). Простая замена имён только в Android сломала бы native IPC. Сохранены также невидимые пользователю resource identifiers widget, согласованные Xray tags/TUN name, WebView hook identifiers и версия AAD защищённого хранилища; это внутренние строки, не адреса прежнего сервиса. Их последующая замена должна учитывать пары ссылок и миграцию сохранённых данных. Названия каталогов `levik_vpn_android`/`levik_whitelist_relay` и атрибуция в LICENSE/source locks сохранены как происхождение кода.

Signed OTA verifier/download/install code сохранён для подключения собственного update origin позднее, но application container использует disabled update manager и не ставит background update work. `hello-kitty-vpn.invalid` в dormant update fixtures — зарезервированный нерабочий placeholder, не развёрнутый сервис. Release validation по-прежнему требует свои ключи и проверенные native artifacts; проверки не ослаблены ради получения APK.

Lockfiles и версии зависимостей не обновлялись. В strict verification metadata добавлен **один** отсутствовавший checksum parent POM `com.google.guava:guava-parent:33.3.1-android`. Кеш сравнен побайтово с canonical Maven Central; SHA256 `6e11986ea7250b51f847157e2dc937f32a306804dfce0007a5e81ddb9b95c579`. Это исправление metadata, а не отключение проверки или новая зависимость.

## Проверки

Ниже результаты завершённых проверок. XML unit/lint отчёты прочитаны, APK установлены на локальный Android 13 x86_64 эмулятор; проверен фактический вывод instrumentation, а не только exit code `adb`.

| Проверка | Статус |
|---|---|
| `bash scripts/ci/fetch-libxray.sh` | VERIFIED — AAR соответствует закреплённой SHA256 |
| `bash scripts/ci/check-repository-policy.sh` | VERIFIED |
| `bash scripts/ci/check-action-pins.sh` | VERIFIED |
| Kotlin Play/Direct | VERIFIED — финальные app/test APK собраны |
| Unit tests Play | VERIFIED — 141 тест, 0 failures/errors/skipped |
| Unit tests Direct | VERIFIED — 169 тестов, 0 failures/errors/skipped |
| `lintPlayDebug` | VERIFIED — 0 errors, 26 warnings, 3 hints |
| `lintDirectDebug` | VERIFIED — 0 errors, 31 warnings, 3 hints |
| `assemblePlayDebug` / `assemblePlayDebugAndroidTest` | VERIFIED |
| `assembleDirectDebug` / `assembleDirectDebugAndroidTest` | VERIFIED |
| Play instrumentation, Android 13 x86_64 | VERIFIED — `OK (14 tests)` |
| Direct instrumentation, Android 13 x86_64 | VERIFIED — `OK (14 tests)`, отдельный Direct test APK |
| Home/Settings/import UI | VERIFIED — просмотр screenshots и UI hierarchy; собственный бренд, import и QR controls |
| Relay native build/ELF validation | VERIFIED — arm64-v8a, armeabi-v7a, x86_64, штатная проверка ELF/16 KiB alignment |
| Direct APK native packaging | VERIFIED — три relay ELF в APK побайтово совпадают с результатом native build; x86_64 executable извлечён на устройстве |
| `verifyDirectReleaseRuntimeClasspath`, `verifyAllDependencyLocks` | VERIFIED — зависимости/locks; это не сборка signed release |
| Static origins/package/manifest, `git diff --check`, local doc links | VERIFIED |
| Signed release / public deploy | NOT RUN — текущий этап не включает публикацию и production |
| VK/российские SIM/физические модели | NOT RUN — нет предоставленных собственных VK-сессий и физической полевой матрицы |

Lint warnings включают unused resources, typography, Compose boxing, compatibility attributes и Direct battery/Wi-Fi declarations. Они перечислены в `levik_vpn_android/app/build/reports/lint-results-{play,direct}Debug.html`; результат не описывается как «без предупреждений».

Промежуточные FAILED: strict verification обнаружила отсутствовавший POM checksum; компиляция — остаточные ссылки на удалённый код; новый тест — неправильное распознавание неполного relay envelope. Исправления прошли повторную проверку. Первый совмещённый Direct Gradle проход завершился неожиданным исчезновением daemon; отдельные lint и build/locks с `--max-workers=1` и `-Xmx1g` прошли. Причина остановки daemon не установлена. Один промежуточный запуск Direct instrumentation ошибочно использовал Play test APK и дал 2 flavor mismatch failures; после установки собственного Direct test APK все 14 тестов прошли. Проверки не отключались для устранения этих failures.

Pinned Go 1.26.5 и NDK 29.0.14206865 установлены в отдельный `/root/.cache/hellokittyvpn-toolchains/`, системные toolchains не заменены. Native сборка использовала `GO_BIN`, `ANDROID_NDK_HOME`, `GOMAXPROCS=2` и штатный `scripts/build-android-client.sh`. SHA256 официального Go archive проверена по lock. Зависший download одного модуля повторён из того же официального Go proxy через отдельный file cache; стандартная проверка `go.sum` сохранена. Изменений dependency versions/lockfiles нет.

Тесты старого account/billing/trial/support/map lifecycle удалены вместе с соответствующей функциональностью. Сохраняются тесты VPN runtime, routes, socket protection, engine ownership, native adapter contracts, crypto, storage и updates security. Добавлены проверки local input bounds/типов/clock/relay prohibition, credential lifetime и инструментальный тест настоящего конвертера: корректный import → invalid import → прежний профиль сохранён → delete. Тесты не подтверждают мобильную проходимость транспорта.

## Артефакты

- [Direct debug APK](../../levik_vpn_android/app/build/outputs/apk/direct/debug/app-direct-debug.apk): 140 203 640 bytes, SHA256 `276cfd4f420956a01a94c3c5d9d21aab4bcbc3d9e22911c41834636f968f1899`.
- [Play debug APK](../../levik_vpn_android/app/build/outputs/apk/play/debug/app-play-debug.apk): 219 707 314 bytes, SHA256 `8c2282528c50b774f31f6b6c2b606292888dda36553fe4e66032804622b24b28`.
- Instrumentation APKs: `app/build/outputs/apk/androidTest/{direct,play}/debug/`; отдельная сборка для каждого flavor обязательна.
- Unit results: `app/build/test-results/test{Direct,Play}DebugUnitTest/`; UI screenshots: `app/build/reports/ui/`.

Это локальные debug artifacts, игнорируемые Git; они не являются опубликованным или подписанным production-релизом. APK содержит несколько ABI, размер релизной доставки требуется измерить отдельно.

## Ограничения текущего результата

Без собственного конфига клиент не подключается: API выдачи ещё отсутствует. Соединение с настоящим внешним VPN-узлом в этом этапе не проверялось. RU split routing — существующая реализация со старым snapshot и блокированным IPv6; улучшение/dual stack относится к следующему этапу. Direct native packaging проверен, но настоящий VK allocation/session не запускался. Эмулятор не подтверждает Samsung/Xiaomi/OEM power management, физическую камеру, российские allowlists или скорость реального VPN-узла. Исторические upstream release workflows/scripts не адаптированы для публикации нового продукта и не запускались.

Конфигурации могут содержать секреты выбранного сервера; импортировать следует доверенный материал. Гарантии «VPN-IP нельзя заблокировать», «все российские сервисы определяются безошибочно», «VK работает при любом отключении» не заявляются.
