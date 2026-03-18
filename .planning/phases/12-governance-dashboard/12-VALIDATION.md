---
phase: 12
slug: governance-dashboard
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-18
---

# Phase 12 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers 1.20.4 |
| **Config file** | none (configured via `tasks.withType<Test> { useJUnitPlatform() }` in `build.gradle.kts`) |
| **Quick run command** | `./gradlew test --tests "com.esmp.dashboard.*" -x spotlessCheck` |
| **Full suite command** | `./gradlew test -x spotlessCheck` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.dashboard.*" -x spotlessCheck`
- **After every plan wave:** Run `./gradlew test -x spotlessCheck`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 12-01-01 | 01 | 1 | DASH-01 | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testModuleSummary*" -x spotlessCheck` | ❌ W0 | ⬜ pending |
| 12-01-02 | 01 | 1 | DASH-02 | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testDependencyGraph*" -x spotlessCheck` | ❌ W0 | ⬜ pending |
| 12-01-03 | 01 | 1 | DASH-03 | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testBusinessConceptGraph*" -x spotlessCheck` | ❌ W0 | ⬜ pending |
| 12-01-04 | 01 | 1 | DASH-04 | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testRiskClusters*" -x spotlessCheck` | ❌ W0 | ⬜ pending |
| 12-01-05 | 01 | 1 | DASH-05 | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testLexiconCoverage*" -x spotlessCheck` | ❌ W0 | ⬜ pending |
| 12-01-06 | 01 | 1 | DASH-06 | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testHeatmapScore*" -x spotlessCheck` | ❌ W0 | ⬜ pending |
| 12-02-01 | 02 | 2 | DASH-02 | unit | `./gradlew test --tests "com.esmp.ui.CytoscapeGraphTest*" -x spotlessCheck` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/dashboard/DashboardServiceIntegrationTest.java` — stubs for DASH-01 through DASH-06; uses Testcontainers (Neo4j + MySQL) + pilot fixtures
- [ ] `src/test/java/com/esmp/ui/CytoscapeGraphTest.java` — unit tests for Java component event registration

*Existing test infrastructure (Testcontainers, JUnit 5, Spring Boot Test) covers framework needs.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Graph node click opens side panel | DASH-02 | Requires browser DOM interaction | Open dashboard, click a graph node, verify side panel displays callers/callees/risk |
| Heatmap color encoding visible | DASH-06 | Visual rendering verification | Open dashboard, verify module colors range from green (low risk) to red (high risk) |
| AppLayout sidebar navigation works | N/A | Layout routing verification | Open dashboard, click Lexicon in sidebar, verify navigation to /lexicon |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
