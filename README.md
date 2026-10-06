# Hello Kitty VPN

Бесплатный Android VPN-клиент на Kotlin/Compose, основанный на открытом исходном коде Nort321/levik-vpn. Приложение использует собственный пакет `org.hellokittyvpn.android`, без прежних аккаунтов, оплаты, поддержки и API.

Текущий этап: технический план и очистка клиента. Бэкэнд и новые VPN-узлы не развёрнуты; для подключения нужна собственная локальная конфигурация VPN. Direct relay находится на стадии подготовки и полевых испытаний.

- [План реализации](docs/hellokitty/implementation-plan.md)
- [Этапы и задания команде агентов](docs/hellokitty/agent-plan/README.md)
- [Исследование пользовательских проблем](docs/hellokitty/research-cases.md)
- [Аудит исходной базы и сервера](docs/hellokitty/baseline-audit.md)
- [Изменения и проверка клиента](docs/hellokitty/app-cleanup.md)

## Сборка

Требуются Java 17 и Android SDK 36. Зависимости закреплены lockfiles и strict verification metadata.

```bash
bash scripts/ci/fetch-libxray.sh
cd levik_vpn_android
ANDROID_HOME=/opt/android-sdk ./gradlew :app:testPlayDebugUnitTest :app:testDirectDebugUnitTest :app:lintPlayDebug :app:assemblePlayDebug
```

Для упаковки Direct дополнительно нужны pinned Go/NDK из `levik_whitelist_relay/source/tools.lock`. Play не содержит VK native relay. Ни один debug APK не является проверенным production-релизом. Ключи подписи, API и update origin прежнего продукта не используются.

На текущем сервере эти toolchains установлены отдельно. Из `levik_vpn_android/` Direct пересобирается так:

```bash
GO_BIN=/root/.cache/hellokittyvpn-toolchains/go-1.26.5/bin/go \
ANDROID_NDK_HOME=/root/.cache/hellokittyvpn-toolchains/android-sdk/ndk/29.0.14206865 \
GOMAXPROCS=2 ANDROID_HOME=/opt/android-sdk \
./gradlew :app:assembleDirectDebug :app:assembleDirectDebugAndroidTest \
  --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g
```

Instrumentation устанавливается вместе с app APK **того же flavor**. Local import проверен с native converter и Keystore на Android 13; реальное соединение с VPN-нодой и VK не подменяется этой проверкой.

Документы `docs/architecture.md`, `docs/security-model.md`, `docs/whitelist-map.md` и `docs/whitelist-relay.md` описывают upstream-базу; актуальные решения проекта находятся в `docs/hellokitty/`. Исторические release scripts/workflows не следует запускать для публикации Hello Kitty.

## Лицензии

Исходная лицензия [AGPL-3.0](LICENSE) и third-party notices сохраняются. Названия upstream в лицензиях, native source locks и совместимых wire-контрактах указывают происхождение кода; они не подключают приложение к чужой инфраструктуре. Условия лицензий учитываются и для бесплатного распространения.
