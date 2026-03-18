---
phase: 13
slug: risk-prioritized-scheduling
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-18
---

# Phase 13 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers |
| **Config file** | None — annotations only (@SpringBootTest, @Testcontainers) |
| **Quick run command** | `./gradlew test --tests "com.esmp.scheduling.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.scheduling.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 13-01-01 | 01 | 1 | SCHED-01 | integration | `./gradlew test --tests "*.SchedulingServiceIntegrationTest.testRecommendReturnsOrderedModules"` | ❌ W0 | ⬜ pending |
| 13-01-02 | 01 | 1 | SCHED-01 | integration | `./gradlew test --tests "*.SchedulingServiceIntegrationTest.testRationaleContainsAllFields"` | ❌ W0 | ⬜ pending |
| 13-01-03 | 01 | 1 | SCHED-02 | integration | `./gradlew test --tests "*.SchedulingServiceIntegrationTest.testTopologicalWaveOrdering"` | ❌ W0 | ⬜ pending |
| 13-01-04 | 01 | 1 | SCHED-02 | integration | `./gradlew test --tests "*.SchedulingServiceIntegrationTest.testCircularDependencyFallback"` | ❌ W0 | ⬜ pending |
| 13-01-05 | 01 | 1 | SCHED-02 | unit | `./gradlew test --tests "*.GitFrequencyServiceTest.testGitUnavailableReturnsEmpty"` | ❌ W0 | ⬜ pending |
| 13-01-06 | 01 | 1 | SCHED-02 | unit | `./gradlew test --tests "*.GitFrequencyServiceTest.testParsesGitLogOutput"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/scheduling/SchedulingServiceIntegrationTest.java` — covers SCHED-01 ordering + SCHED-02 topological waves + circular dep fallback
- [ ] `src/test/java/com/esmp/scheduling/GitFrequencyServiceTest.java` — unit tests for git log parsing and unavailability fallback

*Testcontainer setup: copy from DashboardServiceIntegrationTest — Neo4j + MySQL + Qdrant containers.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| ScheduleView wave lane visualization renders correctly | SCHED-01 | Vaadin visual UI rendering | Open /schedule, click "Generate Schedule", verify wave lanes display modules |
| CytoscapeGraph drill-down shows wave-colored nodes | SCHED-02 | Visual graph rendering | Click a module in schedule view, verify dependency graph with green/red/blue coloring |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
