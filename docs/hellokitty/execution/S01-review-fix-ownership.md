# S01-REVIEW-FIX-OWNERSHIP — Матрица ответственности и план исполнения повторного ревью G01

Дата: 2026-10-07 UTC  
Координатор: Главный координатор проекта  

---

## 1. Закрепление путей записи (Single Writer per File)

| Task ID | Исполнитель / Роль | Разрешенные пути записи (Write Paths) | Ответственность / Дефекты |
|---|---|---|---|
| **S01-R2-00** | Координатор | `docs/hellokitty/execution/S01-review-fix-inputs.md`<br>`docs/hellokitty/execution/S01-review-fix-ownership.md`<br>`docs/hellokitty/execution/status.md`<br>`contracts/security-contract.md` | Preflight, фиксация архитектурных решений, нормативный security contract (F01, F04, F05, F06, F08, F09) |
| **S01-R2-A** | `auth-state` | `contracts/probes/crypto/test_refresh_state_probe.py`<br>`contracts/probes/crypto/go/refresh_state_test.go`<br>`docs/hellokitty/execution/S01-review-fix-auth.md` | F01 (сохранение grant/expiry, отказ revoked/expired, атомарность family, 410 статус F09) |
| **S01-R2-B** | `persistence` | `backend/docs/data-model.md`<br>`contracts/probes/persistence/**`<br>`docs/hellokitty/execution/S01-review-fix-data.md` | F02 (retention и live tokens), F03 (CAS/ready observed evidence), F04 (recovery cache single write), F05 (idempotency request_body_sha256), F06 (AEAD шифрование VLESS UUID at rest) |
| **S01-R2-C** | `wire-conformance` | `contracts/mobile-v1.openapi.yaml`<br>`contracts/probes/conformance/**`<br>`contracts/challenge-vectors.json`<br>`contracts/probes/crypto/test_challenge_probe.py`<br>`contracts/probes/crypto/go/challenge_probe_test.go`<br>`docs/hellokitty/execution/S01-review-fix-wire.md` | F08 (signed serverTime wire-контракт и challenge-vectors.json), F09 (OpenAPI 410), F10 (conformance проверка реального envelope и метаданных) |
| **S01-R2-D** | `kotlin-crypto` | `levik_vpn_android/app/src/test/java/org/hellokittyvpn/android/core/network/GoldenVectorsHarnessTest.kt`<br>`levik_vpn_android/app/src/test/java/org/hellokittyvpn/android/core/network/GoldenVectorsVerifier.kt`<br>`docs/hellokitty/execution/S01-review-fix-kotlin.md` | F07 (полный verifier pipeline для 11 негативных и 2 позитивных векторов), F08 (проверка challenge векторов на Kotlin/JVM) |
| **S01-R2-E** | Координатор | `contracts/probes/crypto/run_all_probes.sh`<br>`docs/hellokitty/execution/contracts-freeze.sha256`<br>`contracts/execution/contracts-freeze.sha256`<br>`docs/hellokitty/execution/S01-review-fix-report.md`<br>`docs/hellokitty/STAGE_01_REVIEW_FIX_COMPLETION_REPORT.md`<br>`docs/hellokitty/agent-plan/STAGE_02_AGENT_PROMPT.md` | Сборка единого runner, пересчет freeze манифестов, итоговая интеграционная приёмка, закрытие G01 и передача этапу 02 |

---

## 2. Фазы исполнения (Waves)

- **Волна 0**: Координатор фиксирует preflight, status.md, базовые решения и обновляет `contracts/security-contract.md`.
- **Волна 1**:
  - `auth-state` (S01-R2-A): реализация и регрессионное тестирование исправлений F01 и F09 в Python и Go.
  - `persistence` (S01-R2-B): обновление `backend/docs/data-model.md` и создание PostgreSQL harness в `contracts/probes/persistence/` для проверки F02–F06.
  - `wire-conformance` (S01-R2-C): обновление `mobile-v1.openapi.yaml`, генерация `challenge-vectors.json`, реализация Python/Go challenge probes, расширение `conformance_test.py`.
- **Волна 2**:
  - `kotlin-crypto` (S01-R2-D): реализация полного verifier pipeline и верификация всех 11 негативных + 2 позитивных векторов, а также challenge векторов в Android Gradle test harness.
- **Волна 3**:
  - Координатор (S01-R2-E): запуск всех probe runner'ов, пересчет контрольных сумм `contracts-freeze.sha256`, составление итоговых отчетов, обновление `STAGE_02_AGENT_PROMPT.md` и финализация ворот G01.
