# Шаблон отчёта этапа и субагента

Это форма; примеры не являются результатами проверок. При исполнении скопировать в `docs/hellokitty/execution/SNN-report.md` или `<task>-report.md`. Secrets и полный config/cookies/IP пользователей не прикладывать.

```markdown
# SNN / task ID — краткий результат

Дата UTC:
Главный агент / исполнитель:
Source baseline HEAD + working snapshot hash:
Scope / разрешённые paths:
Prerequisites / gate evidence:

## Что изменено

Observable behavior до/после, потребители контракта, версии/schema.
Файлы и существенные решения. Planned paths отдельно от реально созданных.

## Задачи и владельцы

| Task ID | Owner | Write paths | Dependencies | Status | Evidence |
|---|---|---|---|---|---|
| SNN-... | ... | ... | ... | NOT RUN | Причина/следующий шаг |

## Проверки

| Check | Command / environment | Status | Actual result / evidence | Limits |
|---|---|---|---|---|
| ... | ... | NOT RUN | Не запускалась; причина | ... |

VERIFIED — выполнено и прошло.
FAILED — выполнено и не прошло; причина/исправление/повтор отдельно.
NOT RUN — намеренно не запускалось; причина и зависимость.
UNABLE TO RUN — попытка/проверка заблокирована конкретным ограничением среды.

## Gate

GNN: status.
Какие критерии закрыты, какие остаются. Кто проверил integration/security.
Historic evidence помечено датой и не считается текущим запуском.

## Совместимость и риск

Known current/target differences, wire/version boundaries, rollback.
Миграции/expiry/revoke/backup semantics и необратимые шаги.

## Передача следующему этапу

Contracts/artifacts с hashes и paths, настройки без secrets, test fixtures,
command instructions, список blockers/владелец/нужный input.
Готовое следующее task задание и шаги, которые можно делать независимо.
```

Главный принимает отчёт после просмотра соответствующего diff и доказательств. Для security/infrastructure gates требуется интеграционная проверка; статус субагента не переносится автоматически на весь этап. Блокирующие failures не скрываются списком выполненных tasks.
