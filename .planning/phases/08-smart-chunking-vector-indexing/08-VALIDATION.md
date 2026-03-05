---
phase: 8
slug: smart-chunking-vector-indexing
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-05
---

# Phase 8 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (junit-jupiter) via spring-boot-starter-test |
| **Config file** | `build.gradle.kts` — `tasks.withType<Test> { useJUnitPlatform() }` |
| **Quick run command** | `./gradlew test --tests "com.esmp.vector.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~60 seconds (includes Testcontainers startup for Neo4j + Qdrant) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.vector.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 8-01-01 | 01 | 1 | VEC-01 | unit | `./gradlew test --tests "com.esmp.vector.application.ChunkingServiceTest"` | ❌ W0 | ⬜ pending |
| 8-01-02 | 01 | 1 | VEC-01 | unit | `./gradlew test --tests "com.esmp.vector.application.ChunkingServiceTest"` | ❌ W0 | ⬜ pending |
| 8-02-01 | 02 | 1 | VEC-02 | integration | `./gradlew test --tests "com.esmp.vector.application.VectorIndexingServiceIntegrationTest"` | ❌ W0 | ⬜ pending |
| 8-02-02 | 02 | 1 | VEC-03 | integration | `./gradlew test --tests "com.esmp.vector.application.VectorIndexingServiceIntegrationTest"` | ❌ W0 | ⬜ pending |
| 8-03-01 | 03 | 1 | VEC-03 | integration | `./gradlew test --tests "com.esmp.vector.config.QdrantCollectionInitializerTest"` | ❌ W0 | ⬜ pending |
| 8-04-01 | 04 | 2 | VEC-04 | integration | `./gradlew test --tests "com.esmp.vector.application.VectorIndexingServiceIntegrationTest"` | ❌ W0 | ⬜ pending |
| 8-04-02 | 04 | 2 | VEC-04 | integration | `./gradlew test --tests "com.esmp.vector.api.VectorIndexControllerIntegrationTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/vector/application/ChunkingServiceTest.java` — stubs for VEC-01 (method-level chunking + class header)
- [ ] `src/test/java/com/esmp/vector/application/VectorIndexingServiceIntegrationTest.java` — stubs for VEC-02, VEC-03, VEC-04 (enrichment, embedding, incremental reindex)
- [ ] `src/test/java/com/esmp/vector/config/QdrantCollectionInitializerTest.java` — stubs for VEC-03 (startup collection creation)
- [ ] `src/test/java/com/esmp/vector/api/VectorIndexControllerIntegrationTest.java` — stubs for VEC-04 (REST endpoint)

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
