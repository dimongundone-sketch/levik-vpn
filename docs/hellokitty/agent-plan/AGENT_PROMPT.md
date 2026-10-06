# Готовое задание главному агенту

Текст ниже можно передать следующему агенту целиком. Это задание на исполнение плана **после отдельного запроса пользователя на реализацию**; текущая задача создала только документы.

```text
Работай в /root/projects/hellokittyvpn над Hello Kitty VPN.

Прочитай docs/hellokitty/agent-plan/README.md, этап00, существующий
implementation-plan.md, app-cleanup.md и research-cases.md. Проверь актуальные
user/repository instructions, git status и текущий код. Рабочее дерево уже
содержит очистку клиента; upstream HEAD не равен рабочим исходникам.
Не откатывай, не стирай и не заменяй эти изменения чистым clone.

Твоя роль — координатор. Выполняй отдельные этапы00–08 по зависимостям.
Назначай субагентам task IDs, write paths, входы, outputs и критерии приёмки
из соответствующего MD. При четырёх слотах держи максимум себя+3 активных
работника. Общие файлы/locks/migrations имеют одного writer; Gradle и ADB
с одним workspace/device запускай последовательно. Интегрируй результаты,
проверяй actual diff/commands и обновляй execution/status.md и отчёты этапов.

Сначала G00 baseline, затем G01 contract/security freeze. После них Go API,
Postgres и identity, worker/reconciler + новый Xray provisioner/WDTT agent,
Android integration, RU routing/DNS/IPv6, VK transport, field pilot и релиз.
VK lab может идти независимо после01; failure VK не мешает regular backend,
но не позволяет рекламировать подтверждённый whitelist bypass.

Сохраняй бесплатность, основной дизайн и отсутствие прежних аккаунтов,
подписок, поддержки и backend origins. Один VpnService/TUN. Российский direct
выполняй на устройстве через physical Network/protect; foreign traffic по
умолчанию через tunnel, в обещанном fail-closed режиме не допускай direct leak.
Не выдавай .ru/ASN эвристику за безошибочное распознавание всех сервисов.

Используй текущие dependency/source locks и стандартную криптографию.
Native wire identifiers заменяй только согласованной version migration.
У каждого устройства личные credentials; ownership/replay/revocation/expiry
проверяются end-to-end. Desired node state не равен observed core state.
Key generation/private tokens/cookies не попадают в отчёты, логи и Git.

Документы не дают разрешение публично деплоить, публиковать APK или менять
соседние сервисы. Проверяй действующую авторизацию каждого внешнего шага.
Подготовь reviewable configuration, staging evidence и rollback до запроса
необходимой новой авторизации. Самостоятельные локальные/code задачи продолжай,
если external domain/VPS/VK/SIM inputs отсутствуют; соответствующие real-world
checks оставляй NOT RUN/UNABLE TO RUN, не сокращая молча объём требования.

Записывай точные VERIFIED / FAILED / NOT RUN / UNABLE TO RUN, команду,
окружение и evidence. Эмулятор/unit/converter test не подтверждают actual VPN
payload, российский allowlist, камеру/OEM Doze или production reliability.
Ссылки на исторические успешные проверки не описывай как новый выполненный запуск.
Не завершай задачу просто наличием конфигов: нужна приёмка согласованного этапа,
а при полном запросе — работающий проверенный сервис и приложение с честными
ограничениями. Сообщай пользователю результат и материальные blockers кратко.
```

## Шаблон задания субагенту

```text
Роль: <role>. Этап: <NN>. Task IDs: <IDs>.
Прочитай <stage MD> и <конкретные contracts/source files>.
Зависимости закрыты: <gates + evidence>.
Можно изменять только: <пути с единственным writer>.
Общие файлы у координатора: <paths>; предложения отправляй, не редактируй их.
Нужный observable outcome: <поведение, а не список файлов>.
Обязательные invariants/negative tests: <из MD>.
Не делай <конкретные external mutations/неразрешённые интеграционные шаги>.
Результат: diff + execution/<task>-report.md по REPORT_TEMPLATE,
actual verification output и remaining gaps. Не называй план VERIFIED.
Перед расширением write scope сообщи координатору причину/consumer.
```
