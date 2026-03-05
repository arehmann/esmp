---
phase: 7
slug: domain-aware-risk-analysis
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-05
---

# Phase 7 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers + Spring Boot Test |
| **Config file** | None — configured via @SpringBootTest + @Testcontainers annotations |
| **Quick run command** | `./gradlew test --tests "com.esmp.graph.application.DomainRiskServiceIntegrationTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.graph.application.DomainRiskServiceIntegrationTest"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 07-01-01 | 01 | 1 | DRISK-01 | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.domainCriticality_*"` | ❌ W0 | ⬜ pending |
| 07-01-02 | 01 | 1 | DRISK-02 | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.securitySensitivity_*"` | ❌ W0 | ⬜ pending |
| 07-01-03 | 01 | 1 | DRISK-03 | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.financialInvolvement_*"` | ❌ W0 | ⬜ pending |
| 07-01-04 | 01 | 1 | DRISK-04 | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.businessRuleDensity_*"` | ❌ W0 | ⬜ pending |
| 07-01-05 | 01 | 1 | DRISK-05 | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.enhancedScore_*"` | ❌ W0 | ⬜ pending |
| 07-01-06 | 01 | 1 | DRISK-05 | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.heatmap_*"` | ❌ W0 | ⬜ pending |
| 07-01-07 | 01 | 1 | DRISK-05 | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.classDetail_*"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/graph/application/DomainRiskServiceIntegrationTest.java` — stubs for DRISK-01 through DRISK-05
- No additional framework install required — Testcontainers + JUnit 5 already present

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
