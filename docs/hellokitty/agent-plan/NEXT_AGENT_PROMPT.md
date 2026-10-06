# Hello Kitty VPN: следующий промпт агенту

Подготовлен 2026-10-06 после проверки отчётов S00. Задание: исправить приёмку G00, выполнить этап 01 и подготовить передачу этапу 02. Этот файл содержит инструкции для следующего исполнителя, а не свидетельство выполненной реализации.

Передай агенту текст ниже целиком.

```text
Работай в /root/projects/hellokittyvpn над Hello Kitty VPN.

Твоя задача: исправить недостатки приёмки этапа 00, затем полностью
выполнить этап 01 «Контракты и безопасность». Подготовь передачу этапу 02.
Реализацию остальных этапов пока не начинай.

Ты — координатор. Используй субагентов: максимум главный агент
и три активных исполнителя. Каждый файл должен иметь одного владельца.

1. Прочитай исходные документы и проверь рабочую копию

Прочитай действующие инструкции пользователя и применимые AGENTS.md,
затем:
- docs/hellokitty/README.md
- docs/hellokitty/agent-plan/README.md
- docs/hellokitty/agent-plan/00-baseline-and-handoff.md
- docs/hellokitty/agent-plan/01-contracts-and-security.md
- docs/hellokitty/agent-plan/02-backend-control-plane.md
- docs/hellokitty/agent-plan/REPORT_TEMPLATE.md
- docs/hellokitty/implementation-plan.md
- docs/hellokitty/app-cleanup.md
- docs/hellokitty/execution/status.md
- все четыре отчёта S00.

Проверь git status и текущие исходники. Upstream HEAD
0d05186a3436028f9392e22cbbc30781b0e59129 не содержит выполненную
очистку Android. Сохрани все существующие изменения. Не делай
reset, clean, checkout, замену рабочей копии клоном или автоматический commit.

Используй status.md внутри репозитория. Отдельная копия в каталоге
«Hello Kitty VPN: Execution Status ...» содержит неработающие ссылки
на отчёты и не должна становиться вторым журналом исполнения.

2. Исправь приёмку G00

До устранения обязательных пробелов отметь G00 как IN PROGRESS
и поясни причину. Сохраняй датированные исторические результаты;
исправления отчётов должны ясно отделяться от прежних наблюдений.

Выполни следующее:

а) Закрепи настоящую рабочую копию исходников:
- список существующих изменений и удалённых путей;
- SHA256 актуальных исходников, включая новые untracked source files;
- явно определённый набор включённых путей;
- исключение секретов, build outputs и caches.
Создай docs/hellokitty/execution/baseline-source.sha256
и описание состава снимка в S00-report.md.
HEAD, количество dirty paths и хеши APK не заменяют source baseline.

б) Исправь выводы о сервере:
- ip addr и ip route не подтверждают геолокацию или ASN;
- отсутствие listener TCP 443 не подтверждает внешнюю доступность;
- ss не проверяет firewall.
При предыдущей независимой проверке обнаружены 13 явных DROP
в IPv4 filter rules и один в IPv6. Повтори узкую read-only проверку,
укажи время и фактический результат. Не трактуй количество DROP
как доказательство блокировки именно 443.

в) Убери необоснованное требование российского расположения
собственного exit для VK-транспорта. Доступный первый узел VK/TURN
и собственный exit — разные компоненты. География сервера сама
по себе не доказывает работоспособность или невозможность транспорта.

г) Исправь обязательность порта PostgreSQL 25432:
закрытая контейнерная сеть без публикации порта БД на хост
не конфликтует с соседним localhost:5432.

д) Исправь завышенные гарантии Android-аудита:
DeviceIdentity допускает fallback без StrongBox.
HybridProfileDecryptor выполняет расшифровку, но сам не проверяет
серверную подпись и семантическую привязку профиля.
Не называй эти гарантии выполненными без соответствующих проверок.

е) Сверь очередь этапа 01 с исходным планом. Не смешивай:
- публичный mobile API /v1;
- request signing v1;
- profile envelope v2;
- новый внутренний Xray node API v2;
- существующий WDTT node API v1;
- версию формата OpenAPI.

Замена публичного API на /api/v2/devices/* не обоснована текущими
отчётами. Верни согласованную основу /v1. Любое необходимое
отклонение оформляй решением с причиной, последствиями для
потребителей и синхронизацией документов.

Закрой G00 только после проверки исправленных отчётов,
source baseline и распределения владельцев файлов.

3. Выполни этап 01 через три непересекающиеся задачи

Субагент A — S01-MOBILE, роль contracts.
Разрешённые файлы:
- contracts/mobile-v1.openapi.yaml
- contracts/profile-v2.schema.json
- contracts/routing-rules-v1.schema.json
- docs/hellokitty/execution/S01-mobile.md

Результат: полные request/response schemas, endpoints, headers,
errors, ограничения размеров, enum/state vocabulary и примеры.
Используй /v1/devices/challenges, /v1/devices/complete,
/v1/tokens/refresh и остальные endpoints этапа 01.
Не проектируй mobile API как копию private WDTT API.

Субагент B — S01-CRYPTO, роль crypto-security.
Разрешённые файлы:
- contracts/signing-vectors.json
- contracts/envelope-vectors.json
- contracts/security-contract.md
- contracts/probes/crypto/**
- docs/hellokitty/execution/S01-crypto.md

Результат: threat model, точные криптографические контракты,
положительные и отрицательные vectors, независимые Kotlin/Go
compatibility probes и проверяемая модель refresh recovery.

Android подписывает запросы PS256 или RS256:
PS256 — SHA256, MGF1-SHA256, salt length 32;
RS256 — PKCS#1 v1.5 с SHA256.
HMAC существующего node-agent — отдельный протокол управления нодой.

Сохрани канонизацию RequestSigner: raw body, encoded path,
hash токена, timestamp и nonce. Query запрещён в signing v1.
Для refresh access Authorization отсутствует; секрет refresh
находится в подписанном body.

Проверь параметры RSA-OAEP по фактическому DeviceIdentity
и его modern/legacy capabilities. Для envelope зафиксируй
серверную подпись, protected metadata, AAD, owner binding,
expiry, revision, purpose/keyId и anti-rollback согласно плану.
AES-GCM не заменяет серверную подпись.

Субагент C — S01-DATA, роль storage-design.
Разрешённые файлы:
- backend/docs/data-model.md
- contracts/node-xray-v2.openapi.yaml
- contracts/probes/node/**
- docs/hellokitty/execution/S01-data.md

Результат: сущности, constraints, transactional boundaries,
token families, idempotency, durable replay, outbox, leases,
revocation и desired/observed state.

Обязательно выполни isolated spike по закреплённым Xray
source/proto: какие операции реально доступны и какие
доказательства подтверждают применение credential.
Не выдумывай ListUsers RPC и не считай desired state
доказательством observed state.

Учти текущий WDTT: максимум 249 адресов, lease до 24 часов,
удержание записей по умолчанию 48 часов и отсутствие
подтверждённой ротации с двумя одновременно рабочими паролями.

SQL migrations и production adapter относятся к этапам 02/03.
На этапе 01 нужны модель, интерфейсы и исполняемые probes,
а не публичный работающий backend.

Координатор единолично меняет execution/status.md, S01-report.md,
общие индексы, freeze record и согласованные изменения плана.
Не выдавай субагенту весь contracts/, если другие пишут внутри него.
Изменение shared files согласовывай до записи.

4. Проверь контракты по поведению

Субагенты используют REPORT_TEMPLATE.md и приводят реальные
команды, результаты и ограничения. Обязательные проверки:
- структурная валидация OpenAPI, schemas и примеров;
- Kotlin/Go signing bytes и PS256/RS256 compatibility;
- изменение body, path, алгоритма и повтор запроса;
- подмена envelope, перенос между устройствами, expiry и rollback;
- потерянный refresh response, повтор и конкурентная rotation;
- запрет восстановления отозванного доступа через reauth;
- согласованная модель DB/outbox и Xray observed evidence;
- разделение ключей по назначению.

Используй ограничения и acceptance criteria файла этапа 01.
Не заменяй независимую проверку тестом, который лишь повторяет
реализацию. Исторические результаты Android-тестов не называй
новыми запусками.

Gradle и ADB в общей рабочей копии запускай последовательно.
Используй существующие locks и изолированные toolchains.
Не обновляй зависимости без необходимости.

5. Закрой этап и подготовь передачу

G01 можно отметить VERIFIED только после фактически выполненных
проверок и согласования всех потребителей. Создай S01-report.md,
freeze record с SHA256 согласованных контрактов и очередь этапа 02
с task IDs, владельцами файлов, зависимостями и критериями приёмки.

Используй статусы VERIFIED / FAILED / NOT RUN / UNABLE TO RUN.
Если обязательная проверка недоступна, укажи точную причину
и оставь G01 незакрытым; выполни все независимые доступные задачи.

На этом этапе разрешены локальные документы, contracts,
isolated probes и необходимые проверки. Не меняй production,
соседние сервисы, firewall или публичный ingress; не публикуй APK.
Домен, VK-аккаунты и российские SIM не требуются для большинства
задач этапа 01 и не должны останавливать их выполнение.

В финале сообщи:
- какие замечания G00 устранены;
- какие артефакты и проверки G01 завершены;
- точные статусы G00/G01;
- оставшиеся ограничения;
- путь к передаче этапу 02.
```
