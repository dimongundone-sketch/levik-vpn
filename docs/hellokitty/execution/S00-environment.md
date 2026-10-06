# S00-ENV / Аудит среды и инфраструктуры — краткий результат

Дата UTC: 2026-10-06
Главный агент / исполнитель: environment-audit (координатор: Главный агент)
Source baseline HEAD + working snapshot hash: HEAD `0d05186a3436028f9392e22cbbc30781b0e59129`, working tree dirty (214 tracked modified/deleted + 15 untracked)
Scope / разрешённые paths: `docs/hellokitty/execution/S00-environment.md`
Prerequisites / gate evidence: S00-BASE snapshot taken; координатор зафиксировал baseline и запустил задачу S00-ENV.

## Что изменено

Произведена полная read-only инвентаризация хоста, системных ресурсов, сетевых listeners, Docker-контейнеров, toolchains и ADB-устройств без каких-либо мутаций ОС, правил фаервола, служб и исходного кода.

Файлы и существенные решения:
- Создан отчёт инвентаризации среды: [`docs/hellokitty/execution/S00-environment.md`](file:///root/projects/hellokittyvpn/docs/hellokitty/execution/S00-environment.md).
- Мутации хоста (iptables flush, docker restart/prune, service restart, git reset) НЕ производились; все проверки выполнены строго в read-only режиме.
- Никакие приватные ключи, пароли, токены окружения и внешние IP-адреса пользователей в отчёт не включены.

## Задачи и владельцы

| Task ID | Owner | Write paths | Dependencies | Status | Evidence |
|---|---|---|---|---|---|
| S00-ENV | `environment-audit` | `docs/hellokitty/execution/S00-environment.md` | S00-BASE | VERIFIED | Инвентаризация хоста, ресурсов, портов, Docker, toolchains, ADB и внешних зависимостей завершена |

## Проверки

| Check | Command / environment | Status | Actual result / evidence | Limits |
|---|---|---|---|---|
| OS & Kernel | `uname -a && cat /etc/os-release` | VERIFIED | Ubuntu 24.04.4 LTS (Noble Numbat), Linux 6.8.0-142-generic x86_64, 6 vCPUs (Intel Xeon Platinum 8173M @ 2.0GHz) | Read-only |
| RAM & Swap | `free -h` | VERIFIED | RAM total 15Gi, used 9.5Gi, free 2.1Gi, buff/cache 4.6Gi, available 6.1Gi. Swap total 2.5Gi, used 2.3Gi (91%), free 234Mi | Swap близок к исчерпанию; сборки требуют ограничения параллелизма |
| Disk space | `df -h /` | VERIFIED | `/dev/vda2`: total 99G, used 80G, available 15G (85% used) | Свободно ~15 GiB; избегать накопления тяжёлых build caches |
| Listening ports (TCP/UDP) | `ss -lntup`, `ss -lnup` | VERIFIED | 0.0.0.0: TCP 22, 80, 2053, 2096, 3000, 5984, 6380, 6768, 8000, 8080, 8443, 8787, 10808, 10809. UDP: 5353, 10808, 39670, 41641, 56000. Localhost (127.0.0.1): TCP 4410-4440, 5037, 5432, 5555, 6379, 8008, 8081, 8090, 8097, 8123, 15432, 55432 | TCP 443 не занят локальным listener; TCP 5432 занят на localhost:5432 сторонним сервисом (popcorn); UDP 56000 занят wdtt-server |
| Docker containers metadata | `docker ps -a --format ...` | VERIFIED | 13 Up: redroid (Android 13), ws-scrcpy, local_telegram_api, 3x-ui, popcorn-films (backend, torrserver, redis, postgres), obsidian_couchdb, avitoscam (fetcher, proxy, bot, monitor, api, retention, db), pcf-postgres. 4 Exited | Хост разделяется с несколькими сторонними сервисами. Никаких env/secrets не извлекалось |
| Network interfaces & subnets | `ip -br addr`, `ip route show` | VERIFIED | `ens3` (внешний интерфейс IPv4), `tailscale0` (100.x.x.x), `docker0` и мосты `br-*` (172.17.0.0/16 — 172.24.0.0/16) | Локальные команды `ip addr` и `ip route` показывают сетевой интерфейс и шлюз, но не подтверждают геолокацию или ASN. Подсеть relay 10.66.66.0/24 не конфликтует с существующими Docker bridge |
| System Java & JVM | `java -version && javac -version` | VERIFIED | OpenJDK 17.0.20.1 (build 17.0.20.1+1-1-24.04-Ubuntu), путь `/usr/lib/jvm/java-17-openjdk-amd64`. Подходит для Gradle 8.13 | `JAVA_HOME` в env пуст, но gradlew находит JVM штатно |
| Android SDK & system NDK | `ls /opt/android-sdk/` | VERIFIED | Android SDK установлен в `/opt/android-sdk`. Platforms: android-34, android-35, android-36. Build-tools: 34.0.0, 35.0.0, 36.0.0. NDK системный: 25.2.9519653, 28.2.13676358 | Системный NDK не содержит версии 29 |
| Pinned toolchains cache | `ls /root/.cache/hellokittyvpn-toolchains/` | VERIFIED | Go 1.26.5 (`/root/.cache/hellokittyvpn-toolchains/go-1.26.5/bin/go`, 269M) и NDK 29.0.14206865 (`/root/.cache/hellokittyvpn-toolchains/android-sdk/ndk/29.0.14206865`, 2.4G) присутствуют | Системный Go 1.22.2 не затронут, NDK 29 изолирован |
| ADB devices & emulators | `/opt/android-sdk/platform-tools/adb devices -l` | VERIFIED | Подключен контейнер `redroid` (127.0.0.1:5555 / emulator-5554): Android 13 (API 33), ABI `x86_64`. Доступен `ws-scrcpy` на 127.0.0.1:8008 | Единственный виртуальный таргет; требует строгой сериализации UI/инструментальных тестов |
| Port 443 & Firewall Audit | `ss -lntup \| grep ':443 '` & `iptables -S \| grep DROP` | VERIFIED | 2026-10-06 21:16 UTC: На TCP 443 локальный listener отсутствует. При проверке firewall выявлено 13 правил DROP в IPv4 (изоляция bridge Docker и доступ к 6768 через tailscale0) и 1 правило DROP в IPv6. Эти DROP не относятся к 443. Отсутствие listener не доказывает внешнюю доступность порта со стороны Internet/провайдера | `ss` проверяет только локальные сокеты, но не firewall и не провайдерский фильтр |
| External resources audit | Чтение конфигураций и окружения | VERIFIED | Доменное имя проекта, валидные публичные TLS-сертификаты, зарубежные выделенные VPN-ноды, тестовые аккаунты VK, российские SIM-карты, релизный keystore — отсутствуют | Зафиксированы как внешние блокеры следующих этапов |

## Gate

G00: IN PROGRESS (до завершения исправления замечаний и утверждения baseline-source.sha256).
- Инвентаризация среды хоста выполнена без мутаций.
- Риски конфликтов портов и ресурсов выявлены и задокументированы с учётом сетевой изоляции.
- Изолированные toolchains подтверждены.

## Совместимость и риск

1. **Разделяемый хост (Shared Host)**: Хост содержит рабочие production-контейнеры сторонних проектов (Avito, Popcorn Films, CouchDB, 3x-ui). Любые мутации инфраструктуры (docker prune, iptables flush, reboot) строго запрещены.
2. **Ограничение по памяти и диску**: Свободно 15 GiB диска и 6.1 GiB RAM, swap заполнен на 91%. Параллельные сборки Gradle/Go могут вызвать OOM. Сборки должны выполняться последовательно с лимитом памяти воркеров (`org.gradle.jvmargs=-Xmx2048m`).
3. **Конфликты портов для backend (G02/G03)**:
   - Стандартный порт PostgreSQL `5432` на `127.0.0.1` занят `postgres-popcorn`. Однако закрытая контейнерная сеть без публикации порта БД на хост полностью устраняет конфликт с соседним localhost:5432. Выделение порта хоста (например, `25432`) требуется только в случае необходимости прямого доступа к БД с хоста.
   - Порт Redis `6379` на `127.0.0.1` и `6380` на `0.0.0.0` заняты.
   - Порт UDP `56000` занят сторонним `wdtt-server`. Новый relay не должен пытаться биндиться на UDP 56000.
   - Порт TCP 443 свободен от локальных listener, но отсутствие listener не гарантирует внешнюю сетевую доступность через сетевые экраны хостинг-провайдера.
4. **Топология VK-транспорта**: Доступный первый узел (VK/TURN) и собственный exit-узел — это раздельные архитектурные компоненты. Географическое размещение сервера само по себе не является доказательством работоспособности или невозможности транспорта. Российское расположение собственного exit-узла не требуется.
5. **Эмулятор**: Доступен только один эмулятор Redroid 13 x86_64. Физические устройства отсутствуют. Одновременный запуск двух тестов или сборок с UI невозможен.

## Передача следующему этапу

### Доказательства и пути к артефактам
- Cached Go 1.26.5: `/root/.cache/hellokittyvpn-toolchains/go-1.26.5/bin/go` (версия `go1.26.5 linux/amd64`)
- Cached NDK 29: `/root/.cache/hellokittyvpn-toolchains/android-sdk/ndk/29.0.14206865`
- System Java 17: `/usr/lib/jvm/java-17-openjdk-amd64`
- Android SDK: `/opt/android-sdk` (platforms 34, 35, 36; build-tools 34.0.0, 35.0.0, 36.0.0; adb 1.0.41)
- Redroid target: `emulator-5554` / `127.0.0.1:5555` (API 33, x86_64)
- Local Telegram API: `http://127.0.0.1:8081`

### Список отсутствующих внешних ресурсов (External Blockers)
1. **DNS-домен проекта** — отсутствует (требуется к G03). Владелец: пользователь / инфраструктура.
2. **Публичный TLS-сертификат** — отсутствует (требуется к G03). Владелец: пользователь.
3. **Зарубежные выделенные exit IP** — хост DE разделяемый; для чистого VPN-трафика нужны отдельные ноды (требуются к G04). Владелец: пользователь.
4. **Тестовые аккаунты VK** — отсутствуют (требуются к G06). Владелец: пользователь.
5. **Физические устройства и SIM-карты операторов РФ** — отсутствуют в облачной среде (требуются к G05/G07). Владелец: пользователь / QA.
6. **Релизный Android keystore** — отсутствует (требуется к G08). Владелец: релиз-инженер.

### Готовность к следующим шагам
- Инфраструктура полностью описана и готова для проектирования контрактов (этап 01) и бэкенда (этап 02).
- Разработка локальных контрактов и тестов в памяти/SQLite/mock не заблокирована отсутствием внешних ресурсов.
