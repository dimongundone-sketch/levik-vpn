# S01-FIX-DATA — Корректирующий отчет по модели данных, WDTT и Xray API

**Дата UTC**: 2026-10-06  
**Роль / исполнитель**: S01-FIX-DATA (`database-design` & `node-architecture`)  
**Рабочая копия**: `/root/projects/hellokittyvpn`  
**Git HEAD**: `0d05186a3436028f9392e22cbbc30781b0e59129`  

---

## 1. Выявленные дефекты и причины корректировки

1. **Синтаксическая ошибка в SQL-функции `uuid_generate_v7()`**:
   - В `backend/docs/data-model.md` переменная `v_time` была объявлена как `double precision`, что приводило к ошибке приведения типов при вызове `clock_timestamp()` в PostgreSQL. Отсутствовала декларация расширения `pgcrypto` для вызова `gen_random_bytes()`.
2. **Некорректный предикат уникальности IP-адресов в `leases`**:
   - В частичном уникальном индексе таблицы `leases` использовалось условие `WHERE lease_status != 'tombstone'`. Из-за этого истекшие или отозванные адреса в статусе `tombstone` не удерживали слот в БД и могли быть повторно выданы другому устройству во время 48-часового окна удержания, что вызывало конфликт маршрутов и утечку пакетов.
3. **Ошибочное утверждение об абсолютной невозможности Read-Back из Xray-core**:
   - В предыдущем отчете утверждалось, что Xray-core не поддерживает никакого чтения пользователей. Анализ исходного кода зафиксированного коммита `5ca6f4b7d4dc20a881d4330e498892697627ec0c` и тега `v26.3.27` показал наличие в `command.proto` RPC `GetInboundUsers` и `GetInboundUsersCount`, а также поддержку интерфейса `proxy.UserManager` в инбаундах VLESS.

---

## 2. Внесенные изменения

### 2.1. Исправление `backend/docs/data-model.md`
- **Функция `uuid_generate_v7()`**:
  * Добавлен `CREATE EXTENSION IF NOT EXISTS pgcrypto;`.
  * Тип `v_time` изменен на `timestamptz`, корректно извлекающий миллисекунды эпохи Unix через `FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT`.
- **Таблица `refresh_tokens`**:
  * Разделены поля выпуска (`issuance_operation_id`, `issuance_client_op_id`, `issuance_body_hash`) и потребления (`consumed_by_operation_id`, `consumed_by_client_op_id`, `consumed_by_body_hash`).
  * Чек-констрейнт `operations.operation_type` дополнен типами `enroll_complete`, `refresh_token`, `reauth_complete`.
  * В транзакциях enrollment и refresh проверки активности устройства, владения и срока гранта вынесены **до** возврата кэшированного ответа.
- **Таблица `leases` и резервирование пула WDTT**:
  * Предикат частичного уникального индекса заменен на:
    ```sql
    CREATE UNIQUE INDEX uq_leases_node_ip_reserved 
        ON leases (node_id, ip_address) 
        WHERE lease_status != 'released';
    ```
  * Теперь все промежуточные статусы (`requested`, `active`, `expired`, `revoked`, `tombstone`) надежно удерживают слот IP до наступления `purge_after` (не менее 48 часов после истечения).
  * Зафиксирован инвариант емкости пула: `active + retained_tombstones <= 249`. Освобождение адреса для повторной выдачи возможно только после перевода в `lease_status = 'released'`.

### 2.2. Актуализация Xray Proto, Evidence Model и спайк-тестов
- Обновлены [`contracts/probes/node/README.md`](file:///root/projects/hellokittyvpn/contracts/probes/node/README.md) и [`contracts/probes/node/xray_proto_spike.py`](file:///root/projects/hellokittyvpn/contracts/probes/node/xray_proto_spike.py):
  * Задокументировано реальное состояние API зафиксированного Xray-core (`5ca6f4b`): наличие `GetInboundUsers(GetInboundUserRequest)` и `GetInboundUsersCount(GetInboundUserRequest)` в `xray.app.proxyman.command.HandlerService`.
  * Подтверждена реализация `proxy.UserManager` в `proxy/vless/inbound/inbound.go` (`AddUser`, `RemoveUser`, `GetUser`, `GetUsers`, `GetUsersCount`).
  * Сформулирована многоуровневая модель **Authoritative Observed Evidence**:
    1. Первичное подтверждение применения операции (код `OK` от `AlterInbound`);
    2. Выборочная проверка (`GetInboundUsers` по email) и агрегатная сверка (`GetInboundUsersCount`);
    3. Crash-safe локальный журнал node-agent (`/var/lib/hkvpn/xray-state.json`), поколение узла (`generation`) и цикл реконсилиации после падения ядра;
    4. Автономный локальный expiry sweeper на ноде.
  * В скрипт `xray_proto_spike.py` добавлены методы и тесты чтения пользователей и сверки счетчиков.

---

## 3. Результаты верификации

1. `python3 contracts/probes/node/xray_proto_spike.py -v`: 100% PASS (8 тестов: проверка RPC, AlterInbound, сессии, реконсилиация поколений, replay cache, идемпотентность, емкость 249 IP WDTT и 48-часовой retention grace).
2. SQL-модель в `backend/docs/data-model.md` полностью синхронизирована с контрактами и моделями состояний.

**Итог**: Спецификация данных, модель доказательства нод и инварианты резервирования IP приведены к строгому нормативному виду.
