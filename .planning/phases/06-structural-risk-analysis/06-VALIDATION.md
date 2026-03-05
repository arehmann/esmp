---
phase: 6
slug: structural-risk-analysis
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-05
---

# Phase 6 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (Neo4j + MySQL + Qdrant) |
| **Config file** | `build.gradle.kts` — `tasks.withType<Test> { useJUnitPlatform() }` |
| **Quick run command** | `./gradlew test --tests "*ComplexityVisitorTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~180 seconds (full suite with Testcontainers) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*ComplexityVisitorTest"` (~10s)
- **After every plan wave:** Run `./gradlew test` (full suite ~3-5 min)
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 300 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 06-01-01 | 01 | 1 | RISK-01 | unit | `./gradlew test --tests "*ComplexityVisitorTest"` | ❌ W0 | ⬜ pending |
| 06-01-02 | 01 | 1 | RISK-03 | unit | `./gradlew test --tests "*ComplexityVisitorTest"` | ❌ W0 | ⬜ pending |
| 06-02-01 | 02 | 2 | RISK-02 | integration | `./gradlew test --tests "*RiskServiceIntegrationTest"` | ❌ W0 | ⬜ pending |
| 06-02-02 | 02 | 2 | RISK-04 | integration | `./gradlew test --tests "*RiskServiceIntegrationTest"` | ❌ W0 | ⬜ pending |
| 06-03-01 | 03 | 2 | RISK-05 | integration | `./gradlew test --tests "*RiskControllerIntegrationTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/extraction/visitor/ComplexityVisitorTest.java` — stubs for RISK-01, RISK-03
- [ ] `src/test/java/com/esmp/graph/application/RiskServiceIntegrationTest.java` — stubs for RISK-02, RISK-04
- [ ] `src/test/java/com/esmp/graph/api/RiskControllerIntegrationTest.java` — stubs for RISK-05

*All three follow established patterns: ComplexityVisitorTest follows JpaPatternVisitorTest (inline fixtures), integration tests follow LexiconIntegrationTest (@SpringBootTest + Testcontainers).*

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
