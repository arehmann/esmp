---
phase: 10
slug: continuous-indexing
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-06
---

# Phase 10 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers + AssertJ (Spring Boot Test) |
| **Config file** | none — inherited from Spring Boot test autoconfiguration |
| **Quick run command** | `./gradlew test --tests "com.esmp.indexing.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.indexing.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 10-01-01 | 01 | 1 | CI-01 | integration | `./gradlew test --tests "*.IncrementalIndexingServiceIntegrationTest#incrementalRun_extractsOnlyChangedFiles"` | ❌ W0 | ⬜ pending |
| 10-01-02 | 01 | 1 | CI-02 | integration | `./gradlew test --tests "*.IncrementalIndexingServiceIntegrationTest#deletedFile_removesClassNodeFromNeo4j"` | ❌ W0 | ⬜ pending |
| 10-01-03 | 01 | 1 | CI-02 | integration | `./gradlew test --tests "*.IncrementalIndexingServiceIntegrationTest#changedFile_updatesContentHashOnClassNode"` | ❌ W0 | ⬜ pending |
| 10-01-04 | 01 | 1 | CI-02 | integration | `./gradlew test --tests "*.IncrementalIndexingServiceIntegrationTest#unchangedFile_isSkipped"` | ❌ W0 | ⬜ pending |
| 10-01-05 | 01 | 1 | CI-03 | integration | `./gradlew test --tests "*.IncrementalIndexingServiceIntegrationTest#deletedFile_removesQdrantChunks"` | ❌ W0 | ⬜ pending |
| 10-01-06 | 01 | 1 | CI-03 | integration | `./gradlew test --tests "*.IncrementalIndexingServiceIntegrationTest#changedFile_updatesQdrantChunks"` | ❌ W0 | ⬜ pending |
| 10-02-01 | 02 | 2 | SLO-03 | integration | `./gradlew test --tests "*.IncrementalIndexingServiceIntegrationTest#incrementalRun_5files_completesUnder30Seconds"` | ❌ W0 | ⬜ pending |
| 10-02-02 | 02 | 2 | SLO-04 | integration (@Tag("slow")) | `./gradlew test --tests "*.IncrementalIndexingServiceIntegrationTest#fullReindex_100classes_completesUnder5Minutes"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/indexing/application/IncrementalIndexingServiceIntegrationTest.java` — stubs for CI-01, CI-02, CI-03, SLO-03, SLO-04
- [ ] `src/test/resources/fixtures/incremental/` — 3 baseline Java files + 2 modified versions for change detection

*Existing infrastructure covers test framework — JUnit 5 + Testcontainers already configured.*

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
