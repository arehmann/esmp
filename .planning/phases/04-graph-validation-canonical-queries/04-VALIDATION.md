---
phase: 4
slug: graph-validation-canonical-queries
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-05
---

# Phase 4 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + AssertJ (Spring Boot Test BOM) |
| **Config file** | `build.gradle.kts` — `tasks.withType<Test> { useJUnitPlatform() }` |
| **Quick run command** | `./gradlew test --tests "com.esmp.graph.validation.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.graph.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 4-01-01 | 01 | 1 | GVAL-01, GVAL-03, GVAL-04 | Compile | `./gradlew compileJava` | ✅ | ⬜ pending |
| 4-01-02 | 01 | 1 | GVAL-01, GVAL-03, GVAL-04 | Integration | `./gradlew test --tests "com.esmp.graph.api.ValidationControllerIntegrationTest"` | ❌ W1 | ⬜ pending |
| 4-02-01 | 02 | 1 | GVAL-02 | Compile | `./gradlew compileJava` | ✅ | ⬜ pending |
| 4-02-02 | 02 | 1 | GVAL-02 | Integration | `./gradlew test --tests "com.esmp.graph.api.DependencyConeIntegrationTest"` | ❌ W1 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/graph/api/ValidationControllerIntegrationTest.java` — covers GVAL-01 (20 queries), GVAL-03 (orphans/duplicates), GVAL-04 (inheritance)
- [ ] `src/test/java/com/esmp/graph/api/DependencyConeIntegrationTest.java` — covers GVAL-02 (dependency cone)
- No new framework install needed — Testcontainers, JUnit 5, AssertJ already in `build.gradle.kts`

*Existing infrastructure covers framework requirements.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Graph answers match senior engineer expectations | GVAL-02 | Requires domain knowledge of a real module | Pick 1-2 well-understood modules, run extraction, compare graph output to developer's mental model |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
