# S01-FIX-MOBILE — Корректирующий отчет по мобильным контрактам и валидации

**Дата UTC**: 2026-10-06  
**Роль / исполнитель**: S01-FIX-MOBILE (`contracts` & `mobile-runtime`)  
**Рабочая копия**: `/root/projects/hellokittyvpn`  
**Git HEAD**: `0d05186a3436028f9392e22cbbc30781b0e59129`  

---

## 1. Выявленные дефекты и причины корректировки

В ходе аудита этапа 01 были выявлены следующие расхождения между контрактами и мобильным клиентом:
1. **Несовместимость поля `purpose`**: В `contracts/profile-v2.schema.json` заголовок `TunnelProfileProtectedHeader` требовал константу `"profile-v2"`, тогда как в коде клиента, тестовых векторах и спецификации безопасности был зафиксирован `"profile"`.
2. **Отсутствие формальной схемы серверов в `profile-v2.schema.json`**: Массив `servers` в payload схемы не содержал строгих определений для `TunnelServerV2`, протоколов Xray/VLESS и настроек Reality.
3. **Отсутствие сквозного тестового гарниса на стороне Kotlin/JVM**: Тесты `RequestSigner` и дешифратора в Android-клиенте проверяли синтетические строки без сквозной валидации против нормативных JSON-векторов репозитория.

---

## 2. Внесенные изменения

### 2.1. Исправление `contracts/profile-v2.schema.json`
- Константа `TunnelProfileProtectedHeader.purpose.const` приведена к каноническому значению `"profile"`.
- Добавлены строгие определения в `$defs`:
  - `UUID`: шаблон `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`
  - `TunnelServerV2`: поддержка движков `engine: ["xray", "relay"]`, категорий `["regular", "mobile", "mobile-allowlist"]`, объектов `outbound` с `vnext`, `realitySettings`.
  - `TunnelBootstrapConfig` и `TunnelRoutingConfig`: формализованы параметры MTU, DNS, тайм-аутов и версий правил.
- Проверено соответствие мета-схеме Draft 2020-12 (`jsonschema.Draft202012Validator`) с локальным резолвингом `$ref`.

### 2.2. Синхронизация `contracts/mobile-v1.openapi.yaml`
- В примере ответа эндпоинта `GET /v1/profiles/{id}` декодирован и заново сериализован base64url заголовок `protected`, приведенный к `purpose: "profile"`.

### 2.3. Реализация Kotlin/JVM тестового гарниса
- Создан тестовый файл [`GoldenVectorsHarnessTest.kt`](file:///root/projects/hellokittyvpn/levik_vpn_android/app/src/test/java/org/hellokittyvpn/android/core/network/GoldenVectorsHarnessTest.kt):
  * **`testSigningVectorsCanonicalAndVerification`**: считывает `contracts/signing-vectors.json`, строит каноническую строку через `RequestSigner.canonicalPayload()`, сопоставляет с `intermediate.canonicalPayload` и валидирует реальные подписи PS256 (RSASSA-PSS с MGF1-SHA-256) и RS256 (SHA256withRSA) через публичные ключи.
  * **`testEnvelopeVectorsDecryptionAndPayload`**: считывает `contracts/envelope-vectors.json`, выполняет анврап сессионного ключа через RSA-OAEP (SHA-256 и SHA-1), дешифрует AES-256-GCM с AAD (`HKVPN-PROFILE-V2\n{protected}`) и валидирует полученный JSON против структуры `TunnelProfilePayloadV2`.
  * **`testEnvelopeNegativeVectorsRejection`**: проверяет отказ при поврежденном шифртексте (`AEADBadTagException`) и поврежденном завернутом ключе (`BadPaddingException`).

---

## 3. Верификация и статус

- `conformance_test.py`: 100% PASS (5/5 проверок)
- Gradle `:app:testDirectDebugUnitTest`: 100% PASS (включая `GoldenVectorsHarnessTest`, `RequestSignerTest`, `DeviceIdentityCapabilityTest`)
- Gradle `:app:testPlayDebugUnitTest`: 100% PASS

**Итог**: Дефекты мобильных контрактов устранены.
