# S01-REVIEW-FIX-AUTH — Отчет исполнителя `auth-state` (F01, F09)

Дата: 2026-10-07 UTC  
Роль / компонент: `auth-state`  
Затрагиваемые файлы:
- `contracts/probes/crypto/test_refresh_state_probe.py`
- `contracts/probes/crypto/go/refresh_state_test.go`
- `docs/hellokitty/execution/S01-review-fix-auth.md`

---

## 1. Устраненные дефекты и обоснование изменений

### F01 (P1): Reauth сохраняет grant и не восстанавливает отозванный доступ
- **Исходный дефект**: `handle_reauth` в Python и `handleReauth` в Go вызывали публичный метод `enroll_device`, который генерировал совершенно новый `grant_id` с новым полным сроком действия (86400с / 30д). Это позволяло устройству с отозванным (`REVOKED`) или просроченным (`EXPIRED`) грантом обойти отзыв доступа и восстановить активную сессию. Кроме того, Go-модель использовала фиксированное время вместо инжектируемого, а в обеих моделях блокировка мьютекса отпускалась между отзывом старых семейств и выдачей новой семьи.
- **Исправление**:
  1. Реализована строгая проверка состояния устройства и гранта: если устройство не `ACTIVE`, либо грант отсутствует, отозван (`REVOKED`) или истек (`expires_at <= now`), операция завершается отказом (`401 GRANT_INACTIVE` / `401 GRANT_EXPIRED`). Никаких новых сущностей (grant, family, tokens) не создается.
  2. Для действующего гранта **сохраняется прежний `grant_id` и исходный `expires_at`**.
  3. В единой неделимой границе транзакции под мьютексом:
     - Все прежние семейства токенов (`token_families`) и активные access-токены (`access_tokens`) для данного устройства помечаются как `revoked`.
     - Создается ровно **одна новая семья токенов**, жестко привязанная к тому же гранту.
     - Время жизни семьи ограничено `min(now + 30d, grant.expires_at)`.
  4. Поддержано сохранение ответа в recovery cache (TTL 120с): exact retry в течение 120с возвращает закэшированный ответ (`_recoveredFromCache: true`) без создания еще одной семьи. Повтор с измененным телом возвращает `409 IDEMPOTENCY_CONFLICT`.

### F09 (P2): Унификация HTTP статуса REFRESH_RETRY_EXPIRED (410 Gone)
- **Исходный дефект**: При попытке повтора запроса с использованным refresh-токеном после истечения окна восстановления (> 120с) OpenAPI задавал статус `410 Gone`, а в коде reference models возвращался `401 Unauthorized`.
- **Исправление**:
  - В `test_refresh_state_probe.py` и `refresh_state_test.go` статус для истекшего окна восстановления унифицирован на `410 REFRESH_RETRY_EXPIRED`.

### Дополнительно (Замечание по конкурентности):
- В Go реализован тест `TestConcurrentFirstRefreshRace` с барьером (`sync.WaitGroup`), где 10 параллельных горутин обращаются с первичным (неиспользованным) токеном и одинаковым `clientOperationId`. Подтверждено, что создается строго **1 запись-преемник** в таблице токенов, а все 10 горутин получают статус `200` с идентичным токеном.

---

## 2. Верификация и регрессионные тесты

### Python Suite (`contracts/probes/crypto/test_refresh_state_probe.py`):
Команда: `python3 contracts/probes/crypto/test_refresh_state_probe.py`  
Результат: **100% PASS**  
Проверенные сценарии:
- `12.1`: Active device + Revoked grant -> отказ `401 GRANT_INACTIVE`.
- `12.2`: Active device + Expired grant -> отказ `401 GRANT_EXPIRED`.
- `12.3`: Missing grant -> отказ `401 GRANT_INACTIVE`.
- `12.4`: Valid short grant without extension -> `grant_id` и `expires_at` строго идентичны исходным, старая семья отозвана, старый токен дает `FAMILY_REVOKED`.
- `12.5`: Lost-response retry reauth в пределах 120с -> закэшированный ответ без повторной ротации.
- `12.6`: Reauth idempotency conflict (измененное тело) -> `409 IDEMPOTENCY_CONFLICT`.
- `12.7`: Same-second successive reauths -> создаются независимые семьи, предшественник отозван.
- `12.8`: Изоляция префиксов device ID -> отсутствие кросс-влияния на соседние устройства.
- `12.9`: Reauth после терминального `DELETE /v1/devices/me` -> отказ `401`.
- Step 8: Просроченный повтор (> 120с) возвращает `410 REFRESH_RETRY_EXPIRED`.

### Go Suite (`contracts/probes/crypto/go`):
Команда: `go test -race -count=1 -v ./...`  
Результат: **PASS (0 data races)**  
Включает:
- `TestLostResponseRefreshStateProbe`: проверка 18 этапов, включая все сценарии F01 и 410 статус.
- `TestConcurrentFirstRefreshRace`: барьерный тест первичной ротации на 10 горутинах (ровно 1 successor token).
- `TestConcurrentRefreshRaces`: 20 параллельных повторов из кэша.
- `go vet ./...`: 0 замечаний.
