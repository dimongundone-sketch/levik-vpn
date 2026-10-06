# Xray Core Proto & Command API Isolated Spike & WDTT Pool Model

**Component**: Node Provisioning & Data Plane Storage Spike  
**Directory**: `contracts/probes/node/`  
**Owner**: S01-DATA (`storage-design`)  
**Target Core**: Xray-core / xtls (pinned client core `5ca6f4b7d4dc20a881d4330e498892697627ec0c` & node core Xray 26.3.27)

---

## 1. Исследование Protobuf и Command API Xray-Core

### Структура `app/proxyman/command/command.proto`
В архитектуре Xray-core (зафиксированный коммит `5ca6f4b7d4dc20a881d4330e498892697627ec0c` и релизный тег `v26.3.27`) управление входящими подключениями и пользователями осуществляется через gRPC сервис `xray.app.proxyman.command.HandlerService`:

```protobuf
service HandlerService {
  rpc AddInbound(AddInboundRequest) returns (AddInboundResponse);
  rpc RemoveInbound(RemoveInboundRequest) returns (RemoveInboundResponse);
  rpc AlterInbound(AlterInboundRequest) returns (AlterInboundResponse);
  rpc ListInbounds(ListInboundsRequest) returns (ListInboundsResponse);
  rpc AddOutbound(AddOutboundRequest) returns (AddOutboundResponse);
  rpc RemoveOutbound(RemoveOutboundRequest) returns (RemoveOutboundResponse);
  rpc AlterOutbound(AlterOutboundRequest) returns (AlterOutboundResponse);
  rpc ListOutbounds(ListOutboundsRequest) returns (ListOutboundsResponse);
  rpc GetInboundUsers(GetInboundUserRequest) returns (GetInboundUserResponse);
  rpc GetInboundUsersCount(GetInboundUserRequest) returns (GetInboundUsersCountResponse);
}
```

Модификация пользователей инбаунда выполняется через `AlterInbound`:
```protobuf
message AlterInboundRequest {
  string tag = 1;
  xray.common.serial.TypedMessage operation = 2;
}

message AlterInboundResponse {}

// Операции, упаковываемые в TypedMessage:
message AddUserOperation {
  xray.common.protocol.User user = 1;
}

message RemoveUserOperation {
  string email = 1;
}
```

Запросы инспекции пользователей:
```protobuf
message GetInboundUserRequest {
  string tag = 1;
  string email = 2;
}

message GetInboundUserResponse {
  xray.common.protocol.User user = 1;
}

message GetInboundUsersCountResponse {
  int64 count = 1;
}
```

---

## 2. Архитектурный факт: Read-Back в Xray-Core и его границы

### Анализ исходного кода Xray (`proxy/vless/inbound`):
1. **Поддержка интерфейса `proxy.UserManager`**: В реализации инбаунда VLESS (`proxy/vless/inbound/inbound.go`) реализован интерфейс `proxy.UserManager`:
   - `AddUser(ctx, user)`
   - `RemoveUser(ctx, email)`
   - `GetUser(email)`
   - `GetUsers()`
   - `GetUsersCount()`
2. **Селективное чтение вместо полного дампа**:
   - `GetInboundUsers(tag, email)` возвращает пользователя по известному `email` (в нашей схеме: `<credentialId>@hkvpn.internal`).
   - `GetInboundUsersCount(tag)` возвращает суммарное количество активных пользователей в инбаунде.
   - Метод `ListUsers` для полного нефильтрованного дампа всех пользователей отсутствует в `command.proto`.
3. **Зависимость от реализации протокола**:
   - Интерфейс `proxy.UserManager` реализован в VLESS и Trojan, но отсутствует или ограничен в ряде других протоколов Xray (например, в некоторых конфигурациях Shadowsocks/Dokodemo).
4. **Потеря динамических пользователей при перезапуске Xray**:
   - Все пользователи, добавленные через `AlterInbound`, хранятся **исключительно в оперативной памяти процесса Xray-core**. При падении или перезапуске процесса Xray таблица пользователей в ядре очищается.
5. **Ограничение закрытия сессий (`RemoveUser`)**:
   - Вызов `RemoveUserOperation` удаляет пользователя из таблицы аутентификации Xray. Это предотвращает создание **новых** TCP/UDP сессий, но **НЕ прерывает уже установленные активные TCP-прокси соединения**. Существующие сессии продолжают работать до их естественного закрытия клиентом или сервером.

---

## 3. Двухуровневая модель Authoritative Observed Evidence для Xray

В силу специфики Xray-core авторитетное подтверждение наблюдаемого состояния (**Authoritative Observed Evidence**) строится многоуровнево:

1. **Первичное подтверждение применения операции**:
   - Успешный сетевой ответ `AlterInbound` gRPC с кодом `status.OK` от локального gRPC-сокета ядра Xray.
2. **Выборочная и агрегатная верификация через gRPC**:
   - `GetInboundUsers(tag, email)` для точечной проверки наличия пользователя по email/uuid;
   - `GetInboundUsersCount(tag)` для сверки агрегатного числа активных учетных записей в инбаунде с локальным журналом.
3. **Локальный персистентный crash-safe журнал node-agent (`/var/lib/hkvpn/xray-state.json`)**:
   - Агент ноды ведет собственный crash-safe журнал с атомарной записью через `tempfile + fsync + rename`.
   - В журнале фиксируются: `credentialId`, `uuid`, `inboundTag`, `observedRevision`, `status`, `expiresAt`, `appliedAt`.
4. **Поколение узла (`node generation`) и цикл реконсилиации**:
   - Агент отслеживает монотонный счетчик поколения (`generation`).
   - При старте сервиса ноды поколение инкрементируется, а агент запускает цикл **реконсилиации при перезапуске**: читает журнал, отбрасывает просроченные записи и повторно вызывает `AlterInbound(AddUser)` для всех активных записей.
5. **Локальный Expiry Sweeper**:
   - Фоновый процесс на ноде регулярно проверяет локальный журнал и вызывает `AlterInbound(RemoveUser)` по наступлении `expiresAt`, гарантируя отзыв доступа даже при недоступности центрального Control Plane.

---

## 4. Модель пула адресов WDTT и предотвращение коллизий

- **Подсеть**: `10.66.66.0/24` (интерфейс `wdtt0`).
- **Адрес сервера**: `10.66.66.1`.
- **Клиентский диапазон**: `10.66.66.2` – `10.66.66.250` (ровно **249 IP-адресов**).
- **Срок аренды (Lease TTL)**: 24 часа.
- **Окно удержания (Retention Grace)**: 48 часов (`PurgeAfter = expiresAt + 48h`).
- **Tombstone Reservation**:
  - Истекший или отозванный IP переходит в статус `tombstone` и **не может быть выделен другому устройству** на протяжении 48 часов.
  - Это предотвращает коллизии пакетов в ядре туннеля (WireGuard/DTLS) и смешение трафика разных устройств.
  - При высокой ротации устройств эффективная емкость пула исчерпывается суммой активных и удерживаемых записей (`active + tombstone <= 249`).

---

## 5. Исполняемый тестовый probe (`xray_proto_spike.py`)

Скрипт [xray_proto_spike.py](file:///root/projects/hellokittyvpn/contracts/probes/node/xray_proto_spike.py) содержит полную программную модель взаимодействия с Xray HandlerService, проверку инвариантов generation/reconciliation и симуляцию пула WDTT.

### Запуск probe:
```bash
python3 contracts/probes/node/xray_proto_spike.py -v
```
