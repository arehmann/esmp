---
phase: 11
slug: rag-pipeline
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-18
---

# Phase 11 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers 1.20.4 + AssertJ |
| **Config file** | none — auto-configured via `@SpringBootTest` |
| **Quick run command** | `./gradlew test --tests "com.esmp.rag.*" -x vaadin` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.rag.*" -x vaadin`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 11-01-01 | 01 | 1 | RAG-01 | integration | `./gradlew test --tests "com.esmp.rag.*RagServiceIntegrationTest*"` | ❌ W0 | ⬜ pending |
| 11-01-02 | 01 | 1 | RAG-02 | integration | `./gradlew test --tests "com.esmp.rag.*RagServiceIntegrationTest*"` | ❌ W0 | ⬜ pending |
| 11-01-03 | 01 | 1 | RAG-03 | integration | `./gradlew test --tests "com.esmp.rag.*RagServiceIntegrationTest*"` | ❌ W0 | ⬜ pending |
| 11-01-04 | 01 | 1 | RAG-04 | integration | `./gradlew test --tests "com.esmp.rag.*RagControllerIntegrationTest*"` | ❌ W0 | ⬜ pending |
| 11-01-05 | 01 | 1 | SLO-01 | integration | `./gradlew test --tests "*RagServiceIntegrationTest*slo01*"` | ❌ W0 | ⬜ pending |
| 11-01-06 | 01 | 1 | SLO-02 | integration | `./gradlew test --tests "*RagServiceIntegrationTest*slo02*"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/rag/application/RagServiceIntegrationTest.java` — stubs for RAG-01, RAG-02, RAG-03, SLO-01, SLO-02
- [ ] `src/test/java/com/esmp/rag/api/RagControllerIntegrationTest.java` — stubs for RAG-04
- [ ] Shared test fixtures: reuse existing `src/test/resources/fixtures/pilot/` Java files (20 fixture classes) + Testcontainers setup pattern from `VectorSearchIntegrationTest`
- [ ] SLO test fixtures: reuse incremental fixture stubs from `src/test/resources/fixtures/incremental/` (97 bulk stubs) for 50-node cone; add DEPENDS_ON edges via Neo4j client setup in test

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
