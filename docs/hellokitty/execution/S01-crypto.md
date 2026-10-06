# S01-CRYPTO / task S01-CRYPTO — Модель угроз, криптографический контракт, golden векторы и исполняемые probes

Дата UTC: 2026-10-06T21:35:00Z  
Главный агент / исполнитель: S01-CRYPTO (роль `crypto-security`)  
Source baseline HEAD + working snapshot hash: `0d05186a3436028f9392e22cbbc30781b0e59129`  
Scope / разрешённые paths:
- `contracts/signing-vectors.json`
- `contracts/envelope-vectors.json`
- `contracts/security-contract.md`
- `contracts/probes/crypto/**`
- `docs/hellokitty/execution/S01-crypto.md`

Prerequisites / gate evidence: G00 VERIFIED (source baseline 372 файла, аудит Keystore StrongBox fallback в `DeviceIdentity`, отсутствие проверки подписи сервера в `HybridProfileDecryptor`).

---

## Что изменено

1. **Модель угроз и спецификация безопасности (`contracts/security-contract.md`)**:
   - Описаны доверенные границы и 5 классов нарушителей (активный сетевой цензор/ISP MITM, модифицированный клиент с декомпилятором, скомпрометированное/отозванное устройство, ненадёжный оператор ноды, утечка логов/снимков БД).
   - Формализованы угрозы по STRIDE: MITM с перехватом TLS CA, Replay requests, Token/credential reuse, Cross-device profile injection, Rollback (понижение ревизий профиля и алгоритмов), Timing/side-channel attacks.
   - Зафиксирован канонический формат `RequestSigner v1` (ровно 8 строк, разделитель `\n` LF `0x0A`, без завершающего newline, uppercase HTTP method, запрет query/fragment в v1, окно времени $\pm 120$с, уникальность `(deviceId, nonce)` в БД 5 мин, wire hex fingerprint `SHA256(SPKI)`, хеш сырого тела до JSON-парсинга).
   - Специфицирован `HKVPN-PROFILE-V2` envelope: защищённые метаданные `protected` (base64url JSON), `wrappedKey` (RSA-OAEP-256 или RSA-OAEP-SHA1 для 32-байтного ключа AES-256), `iv` (12B), `ciphertext` (AES-256-GCM + 16B tag), подпись сервера `signature` (ECDSA P-256 strict DER).
   - Формулы аутентификации:
     * $\text{AAD} = \text{UTF8}(\text{"HKVPN-PROFILE-V2\textbackslash n"} + \text{protected})$
     * $\text{signed} = \text{UTF8}(\text{"HKVPN-PROFILE-V2\textbackslash n"} + \text{protected} + \text{"\textbackslash n"} + \text{wrappedKey} + \text{"\textbackslash n"} + \text{iv} + \text{"\textbackslash n"} + \text{ciphertext} + \text{"\textbackslash n"})$
   - Anti-rollback и строгий порядок верификации на клиенте (проверка подписи сервера $\to$ парсинг metadata и сверка `deviceId`, `credentialExpiresAt`, `profileRevision` $\to$ RSA unwrap AES-ключа $\to$ AES-GCM decrypt $\to$ зануление ключа в памяти).
   - Специфицирован протокол Lost-Response Recovery для Refresh:
     * Заголовок `Idempotency-Key` строго равен `clientOperationId` в теле.
     * Окно восстановления 120 секунд: точный повтор возвращает зашифрованный кэшированный ответ без повторной ротации и без отзыва семьи токенов.
     * Попытка использовать отработанный токен с новым `clientOperationId` классифицируется как Token Reuse с немедленным отзывом всей семьи токенов и активных сессий.
     * Тот же `clientOperationId` с изменённым телом возвращает `409 Conflict`.
     * Повтор после 120 секунд возвращает `401 REFRESH_RETRY_EXPIRED` (клиент инициирует Keystore `mode=reauth` с сохранением grant).
     * `DELETE /v1/devices/me` — тело пустое, заголовок `Idempotency-Key` запрещён (возвращает 400 при наличии), естественно идемпотентно (204 на первый вызов, 410 на повторные).
   - Разделение ключей: TLS Ingress, Server Profile Signing (`ES256`), Routing Manifest Signing (`ES256`), Android Keystore Identity (`PS256`/`RS256`), Node mTLS/HMAC, Ephemeral Storage Key.

2. **Эталонные криптографические векторы (`contracts/signing-vectors.json`, `contracts/envelope-vectors.json`)**:
   - `contracts/signing-vectors.json`:
     * Тестовые ключи RSA-3072 (Modern, Legacy, Other) и RSA-4096 (Large) с PEM и DER SPKI.
     * 10 позитивных векторов: PS256 с токеном, PS256 без токена, PS256 пустое тело с токеном, PS256 пустое тело без токена, PS256 percent-encoded path, RS256 с токеном, RS256 без токена, RS256 пустое тело с токеном, RS256 пустое тело без токена, PS256 на ключе RSA-4096.
     * 11 негативных векторов: изменение метода (GET вместо POST), подмена path, внедрение query-параметра (`?node=de-01`), просроченный timestamp в прошлом (-300s), timestamp из будущего (+300s), дубликат nonce, несовпадение token hash, нарушение целостности тела (пробелы/пересериализация), неверный алгоритм, повреждённая подпись, подпись от чужого открытого ключа.
   - `contracts/envelope-vectors.json`:
     * Серверный ключ ECDSA P-256 (`keyId: "hkvpn-profile-signer-2026-v1"`) и поддельный ключ (`rogueServerKey`).
     * Ключи устройств RSA-3072 (`modern_device` и `legacy_device`).
     * 2 позитивных вектора: Modern (`RSA-OAEP-256+A256GCM`) и Legacy (`RSA-OAEP+A256GCM`) с полными промежуточными значениями (AES key, AAD, signed string) и эталонным JSON payload.
     * 10 негативных векторов: повреждённый ciphertext (ошибка GCM тега), изменённый IV, изменённые метаданные (AAD mismatch), повреждённый wrappedKey (ошибка OAEP), cross-device mismatch (чужой `deviceId`), просроченные credentials (`credentialExpiresAt`), откат ревизии (`profileRevision < lastObserved`), повреждённая серверная подпись, неавторизованный серверный ключ, отсутствующая подпись.

3. **Исполняемые кроссплатформенные probes (`contracts/probes/crypto/**`)**:
   - Python:
     * `test_signing_probe.py`: проверка канонизации RequestSigner и генерации/верификации подписей PS256/RS256, проверка всех позитивных и негативных векторов.
     * `test_envelope_probe.py`: проверка сборки, шифрования, дешифрования и верификации подписи Envelope v2 (AES-GCM + RSA-OAEP-256/SHA-1 + ECDSA P-256).
     * `test_refresh_state_probe.py`: state probe для lost-response refresh recovery (120s окно кэша, защита от гонок/burst, reuse detection, 409 conflict, expired retry, reauth recovery, DELETE idempotency).
   - Go (стандартная библиотека Go 1.22+ `crypto/*`):
     * `go/go.mod`: изолированный модуль тестовых проб `org.hellokittyvpn/contracts/probes/crypto`.
     * `go/signing_probe_test.go`: верификация `contracts/signing-vectors.json` через Go `crypto/rsa`, `crypto/sha256`, `crypto/x509`.
     * `go/envelope_probe_test.go`: верификация `contracts/envelope-vectors.json` через Go `crypto/ecdsa`, `crypto/rsa`, `crypto/aes`, `crypto/cipher`.
     * `go/refresh_state_test.go`: state machine probe для control plane в Go.
   - Мастер-скрипт запуска:
     * `contracts/probes/crypto/run_all_probes.sh`: выполняет последовательно все Python и Go проверки с контролем кодов возврата.

---

## Задачи и владельцы

| Task ID | Owner | Write paths | Dependencies | Status | Evidence |
|---|---|---|---|---|---|
| S01-CRYPTO-SPEC | `crypto-security` | `contracts/security-contract.md` | G00 | VERIFIED | Документ с моделью угроз, спецификацией RequestSigner v1, Profile Envelope v2, Refresh Recovery и Key Separation |
| S01-CRYPTO-VEC-SIGN | `crypto-security` | `contracts/signing-vectors.json` | S01-CRYPTO-SPEC | VERIFIED | 10 позитивных и 11 негативных векторов (PS256, RS256, RSA-3072/4096) |
| S01-CRYPTO-VEC-ENV | `crypto-security` | `contracts/envelope-vectors.json` | S01-CRYPTO-SPEC | VERIFIED | 2 позитивных (Modern/Legacy) и 10 негативных векторов Envelope v2 |
| S01-CRYPTO-PROBE-PY | `crypto-security` | `contracts/probes/crypto/*.py` | Векторы | VERIFIED | Все 3 Python-скрипта успешно выполняются со 100% прохождением тестов |
| S01-CRYPTO-PROBE-GO | `crypto-security` | `contracts/probes/crypto/go/*` | Векторы | VERIFIED | Все Go тесты проходят через `go test -v ./...` за 0.057s |
| S01-CRYPTO-REPORT | `crypto-security` | `docs/hellokitty/execution/S01-crypto.md` | Все probes | VERIFIED | Данный итоговый отчёт |

---

## Проверки

| Check | Command / environment | Status | Actual result / evidence | Limits |
|---|---|---|---|---|
| Python RequestSigner v1 Probe | `python3 contracts/probes/crypto/test_signing_probe.py` | VERIFIED | 10 positive PASS, 11 negative PASS (100% pass) | Python 3.12.3 + cryptography 50.0.1 |
| Python Profile Envelope v2 Probe | `python3 contracts/probes/crypto/test_envelope_probe.py` | VERIFIED | 2 positive PASS, 10 negative PASS (100% pass) | Python 3.12.3 + cryptography 50.0.1 |
| Python Refresh State Probe | `python3 contracts/probes/crypto/test_refresh_state_probe.py` | VERIFIED | 8 сценариев (ротация, 120s повтор, burst, 409 conflict, token reuse, 120s expired, reauth, DELETE 400/204/410) PASS (100% pass) | В памяти эмуляция state machine |
| Go Crypto Probes Suite | `cd contracts/probes/crypto/go && go test -v -count=1 ./...` | VERIFIED | `TestProfileEnvelopeGoldenVectors` PASS (0.03s)<br>`TestLostResponseRefreshStateProbe` PASS (0.00s)<br>`TestRequestSignerGoldenVectors` PASS (0.02s)<br>Общий итог: `ok org.hellokittyvpn/contracts/probes/crypto 0.057s` | Go 1.22.2 amd64 (stdlib only) |
| Master Probes Harness | `./contracts/probes/crypto/run_all_probes.sh` | VERIFIED | Все 4 этапа завершились с кодом 0; 100% PASS RATE | Bash harness |

---

## Gate

G01: **READY FOR FREEZE** (криптографическая часть субагента S01-CRYPTO полностью готова).  
Закрытые критерии крипто-безопасности:
- [x] Строгая модель угроз и правила канонизации RequestSigner v1 утверждены.
- [x] Побайтово фиксированные формулы AAD и signed string для Profile Envelope v2 проверены.
- [x] Полные тестовые векторы подписи и конверта сгенерированы и согласованы.
- [x] Независимые исполняемые probes на Python и Go подтвердили 100% совместимость.
- [x] Протокол восстановления потерянного ответа (Lost-Response Refresh Recovery) проверен на гонки, повторы, конфликты и попытки переиспользования токенов.

---

## Совместимость и риск

- **Android Keystore Compatibility**:
  * Modern (API 35+): RSA-PSS (PS256: salt 32, MGF1-SHA256) и RSA-OAEP-SHA256 (`RSA/ECB/OAEPWithSHA-256AndMGF1Padding`).
  * Legacy (API 34): RSASSA-PKCS1-v1_5 (RS256) и RSA-OAEP-SHA1 (`RSA/ECB/OAEPWithSHA-1AndMGF1Padding`).
  * Политика устройства фиксируется на сервере в момент enroll и не может быть понижена через заголовок запроса.
- **Backend Go Compatibility**:
  * Использует исключительно стандартную библиотеку Go `crypto/*` (`crypto/rsa`, `crypto/ecdsa`, `crypto/aes`, `crypto/cipher`, `crypto/sha256`, `crypto/sha1`). Никаких нестандартных форков OpenSSL или самодельных примитивов.
- **Rollback Risk**:
  * Клиент на Android отвергает любой профиль с `profileRevision < lastObservedRevision`.
  * Клиент отвергает профиль с истёкшим `credentialExpiresAt`.

---

## Передача следующему этапу

### Созданные артефакты и их контрольные суммы SHA-256:

| Файл | SHA-256 контрольная сумма | Описание |
|---|---|---|
| `contracts/security-contract.md` | `7b97a981eea29c10e67b3bab6a959a1cdda8697e2c37324a6d34724db724edbf` | Полный контракт безопасности, Threat Model, wire-форматы, алгоритмы |
| `contracts/signing-vectors.json` | `c9055079124a2ec97e978202af6047cfbe7584fe85702d1cf7bd17187f71b06e` | Golden vectors подписи RequestSigner v1 (10 pos, 11 neg) |
| `contracts/envelope-vectors.json` | `dead712628ae40ab9631c531e10f85a973cd223d1f659861706f42af83bbf34a` | Golden vectors Profile Envelope v2 (2 pos, 10 neg) |
| `contracts/probes/crypto/run_all_probes.sh` | `323a77192346e3ae4cc4a1264f45879a34223597df5b28ae9ad62d7ebcb1a1b4` | Мастер-скрипт верификации крипто-проб |
| `contracts/probes/crypto/test_signing_probe.py` | `87414f040e15e1f4b3b9b57e8969bc1386d02b8e184a62d3da8fd345ba86da97` | Python-проба подписи RequestSigner v1 |
| `contracts/probes/crypto/test_envelope_probe.py` | `e8cbfc3081fc87dd6d395ea6e5d701bde3df4e6ab13f5f00727d2b1154966f90` | Python-проба шифрования Profile Envelope v2 |
| `contracts/probes/crypto/test_refresh_state_probe.py` | `7667e00f6349b98c6b435f0257d695b8897b03edff03caedc29c7c47f4f19672` | Python state probe протокола Refresh Recovery |
| `contracts/probes/crypto/go/signing_probe_test.go` | `5cfad25c2ef052ee10559315bf60226dc9c2bcadafd9e9682dc978efe735de4a` | Go-тест верификации signing golden vectors |
| `contracts/probes/crypto/go/envelope_probe_test.go` | `a530d9550d03063d425c06be0d9da5b485c80629463ad5dffb61f884bd311212` | Go-тест верификации envelope golden vectors |
| `contracts/probes/crypto/go/refresh_state_test.go` | `40bca59303eb31b1eadd37ef4bdf339727723e3d7cc07cf7cd6a38bdad6137c8` | Go-тест state machine Lost-Response Refresh Recovery |

### Инструкции по валидации:
```bash
# Запуск полного набора проверок (Python + Go):
./contracts/probes/crypto/run_all_probes.sh

# Запуск Go-тестов напрямую:
cd contracts/probes/crypto/go && go test -v -count=1 ./...

# Запуск отдельных Python-проб:
python3 contracts/probes/crypto/test_signing_probe.py
python3 contracts/probes/crypto/test_envelope_probe.py
python3 contracts/probes/crypto/test_refresh_state_probe.py
```

### Входные данные для последующих этапов:
1. **Этап 02 (Backend Control Plane - Go)**: Реализация `backend/internal/auth` и middleware должна использовать канонизацию из `contracts/security-contract.md` и валидироваться тестами `contracts/probes/crypto/go/signing_probe_test.go`. Реализация выдачи профилей `backend/internal/profiles` должна формировать Envelope v2 строго по формулам AAD и signed string. Реализация сессий `backend/internal/auth` должна строго следовать 120s recovery window и правилам отзыва семей токенов.
2. **Этап 04 (Android Integration - Kotlin)**: `RequestSigner.kt` и `HybridProfileDecryptor.kt` на Android должны быть дополнены валидацией подписи сервера ECDSA P-256 (`ES256`) и anti-rollback проверкой `profileRevision`.
