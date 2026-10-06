# S01-FIX-CRYPTO — Корректирующий отчет по криптографическим контрактам и векторам

**Дата UTC**: 2026-10-06  
**Роль / исполнитель**: S01-FIX-CRYPTO (`crypto-audit` & `test-vectors`)  
**Рабочая копия**: `/root/projects/hellokittyvpn`  
**Git HEAD**: `0d05186a3436028f9392e22cbbc30781b0e59129`  

---

## 1. Выявленные дефекты и причины корректировки

1. **Невалидные payload и bypass в тестовых векторах `envelope-vectors.json`**:
   - Векторы не содержали обязательного поля `profileRevision`.
   - В поле `accessId` использовалась строка `"access-1001"`, не удовлетворяющая формату UUID.
   - Значения `engine` имели некорректный регистр (`"XRAY"` вместо `"xray"`).
   - В ряде негативных векторов подпись сервера не проверялась или проверялась с обходом пайплайна (bypass), из-за чего поврежденный шифртекст падал на сигнатуре до проверки GCM.
2. **Отсутствие нормализации low-$S$ для ECDSA**:
   - Стандарт RFC 6979 и безопасность ECDSA требуют исключения пластичности подписи (malleability). Если компонент $s > \lfloor N/2 \rfloor$, подпись должна нормализоваться к $N - s$, а валидаторы обязаны отклонять high-$S$. Это требование не было отражено в верификаторах и негативных векторах.
3. **Логический сбой в модели потерянного ответа при refresh**:
   - Использование одного поля `bound_operation_id` на токене приводило к коллизии между операцией выпуска и операцией потребления токена: при повторном запросе exact retry токен считался скомпрометированным, что вызывало ложный отзыв семейства токенов.
   - Отсутствовала проверка принадлежности токена вызывающему устройству: чужой запрос рефреша мог отозвать семейство токенов жертвы.
   - Не моделировался статус `grant` (активен, отозван, истек).

---

## 2. Внесенные изменения

### 2.1. Полная регенерация `contracts/envelope-vectors.json`
- Позитивные векторы `pos_01_modern_oaep_sha256` и `pos_02_legacy_oaep_sha1` полностью перегенерированы с валидным payload: `profileRevision`, UUID для `accessId`, `profileId`, `id` серверов, lowercase `engine: "xray"`, соответствие схеме `TunnelProfileEnvelopeV2`.
- Пересчитаны реальные AES-256-GCM теги, RSA-OAEP ключи и строгие DER ECDSA P-256 серверные подписи с low-$S$ нормализацией.
- Сформирован полный набор из 11 негативных векторов, тестирующих каждый этап пайплайна верификации:
  * `neg_01_tampered_ciphertext`: корректно подписанный серверный конверт с поврежденным шифртекстом — отклоняется на этапе GCM authentication tag check (`GCM_AUTH_FAILED`).
  * `neg_02_tampered_iv`: поврежден IV — отклоняется проверкой серверной подписи (`ENVELOPE_SIGNATURE_INVALID`).
  * `neg_03_tampered_metadata_aad`: изменен protected header — отклоняется проверкой подписи (`ENVELOPE_SIGNATURE_INVALID`).
  * `neg_04_tampered_wrapped_key`: поврежден обернутый ключ — отклоняется при анврапе RSA-OAEP (`KEY_UNWRAP_FAILED`).
  * `neg_05_cross_device_mismatch`: несовпадение deviceId — отклоняется проверкой владения (`CROSS_DEVICE_VIOLATION`).
  * `neg_06_expired_credential`: истек срок действия учетных данных — отклоняется проверкой срока (`CREDENTIAL_EXPIRED`).
  * `neg_07_rollback_revision`: ревизия ниже текущей — отклоняется защитой от отката (`ROLLBACK_DETECTED`).
  * `neg_08_bad_server_signature`: искажена подпись — отклоняется валидатором подписи (`ENVELOPE_SIGNATURE_INVALID`).
  * `neg_09_unauthorized_signing_key`: подписан ключом, отсутствующим в доверенном наборе (`UNTRUSTED_KEY_SIGNATURE`).
  * `neg_10_missing_signature`: подпись отсутствует (`MISSING_SIGNATURE`).
  * `neg_11_high_s_signature`: подпись с $s > N/2$ — отклоняется проверкой нормализации low-$S$ (`ENVELOPE_SIGNATURE_INVALID`).

### 2.2. Фиксация в `contracts/security-contract.md`
- В спецификацию безопасности добавлен раздел о строгой low-$S$ нормализации ECDSA P-256: половина порядка группы $N/2 = \text{0x7fffffff800000007fffffffffffffffde737d56d38bcf4279dce5617e3192a8}$. Подписи с $s > N/2$ признаются недействительными.

### 2.3. Исправление моделей состояний Refresh (Python и Go)
- В `contracts/probes/crypto/test_refresh_state_probe.py` и `contracts/probes/crypto/go/refresh_state_test.go`:
  * Разделены поля привязки операции: `issuance_operation_id` (выпуск) и `consumed_by_operation_id` / `consumed_by_body_hash` (потребление).
  * Внедрено моделирование `GrantRecord` (`grant_id`, `status: ACTIVE/REVOKED/EXPIRED`, `expires_at`).
  * Добавлена проверка принадлежности токена вызывающему устройству: чужой вызов возвращает `401 TOKEN_DEVICE_MISMATCH` и **не отзывает** семейство токенов жертвы.
  * Устранены коллизии `family_id` (переход на криптографические UUIDv4 вместо префикса устройства и секундных таймстемпов).
  * Добавлена потокобезопасность (мьютексы) и многопоточные стресс-тесты с барьерами/горутинами, подтверждающие корректность работы single-flight exact retry под детектором гонок Go (`go test -race`).

---

## 3. Результаты верификации

1. `python3 contracts/probes/crypto/test_envelope_probe.py`: 100% PASS (2 positive, 11 negative).
2. `python3 contracts/probes/crypto/test_refresh_state_probe.py`: 100% PASS (11 этапов, включая барьерный рейс на 10 потоков).
3. `go test -v -race ./...` (в директории `contracts/probes/crypto/go`): 100% PASS, 0 гонок данных.
4. Kotlin `GoldenVectorsHarnessTest`: 100% PASS (весь набор подписей и дешифровки).

**Итог**: Криптографические контракты, тестовые векторы и модели состояний полностью верифицированы.
