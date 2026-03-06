---
phase: 9
slug: golden-module-pilot
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-06
---

# Phase 9 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers + AssertJ (Spring Boot 3.5.x) |
| **Config file** | No separate config — `@Testcontainers` + `@DynamicPropertySource` pattern |
| **Quick run command** | `./gradlew test --tests "com.esmp.pilot.*" --tests "com.esmp.vector.api.VectorSearch*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.pilot.*" --tests "com.esmp.vector.api.VectorSearch*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 09-01-01 | 01 | 1 | GMP-01 | integration | `./gradlew test --tests "com.esmp.pilot.application.PilotServiceIntegrationTest"` | ❌ W0 | ⬜ pending |
| 09-01-02 | 01 | 1 | GMP-01 | integration | `./gradlew test --tests "com.esmp.pilot.api.PilotControllerIntegrationTest"` | ❌ W0 | ⬜ pending |
| 09-02-01 | 02 | 1 | GMP-02 | integration | `./gradlew test --tests "com.esmp.vector.api.VectorSearchIntegrationTest"` | ❌ W0 | ⬜ pending |
| 09-02-02 | 02 | 1 | GMP-02 | integration | same as above | ❌ W0 | ⬜ pending |
| 09-03-01 | 03 | 2 | GMP-03 | integration | `./gradlew test --tests "com.esmp.pilot.application.PilotServiceIntegrationTest"` | ❌ W0 | ⬜ pending |
| 09-03-02 | 03 | 2 | GMP-03 | integration | same as above | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/pilot/application/PilotServiceIntegrationTest.java` — stubs for GMP-01, GMP-03
- [ ] `src/test/java/com/esmp/pilot/api/PilotControllerIntegrationTest.java` — stubs for GMP-01 (recommend endpoint)
- [ ] `src/test/java/com/esmp/vector/api/VectorSearchIntegrationTest.java` — stubs for GMP-02
- [ ] `src/test/resources/fixtures/pilot/` — synthetic fixture `.java` source files

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| RAG result relevance quality | GMP-02 | Subjective assessment of search result usefulness | Run 3-5 test queries via `POST /api/vector/search`, review top-5 results for each, assess relevance of returned chunks |
| Migration readiness assessment accuracy | GMP-03 | Requires domain expert judgment | Review pilot validation report, compare risk scores and Vaadin 7 pattern counts against manual module inspection |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
