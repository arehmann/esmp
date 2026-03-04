---
phase: 1
slug: infrastructure
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-04
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (via `spring-boot-starter-test`) + Testcontainers |
| **Config file** | None — JUnit 5 auto-detected by Spring Boot Test |
| **Quick run command** | `./gradlew test --tests "*health*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30 seconds (Testcontainers startup dominates) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*health*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 1-01-01 | 01 | 1 | INFRA-01 | Integration | `./gradlew test --tests "*HealthIndicatorIntegrationTest*"` | ❌ W0 | ⬜ pending |
| 1-01-02 | 01 | 1 | INFRA-02 | Integration + Smoke | `./gradlew test --tests "*VirtualThreadsTest*"` | ❌ W0 | ⬜ pending |
| 1-01-03 | 01 | 1 | INFRA-03 | Integration + Build | `./gradlew build` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/infrastructure/health/HealthIndicatorIntegrationTest.java` — stubs for INFRA-01 (all three health indicators UP)
- [ ] `src/test/java/com/esmp/infrastructure/health/VirtualThreadsTest.java` — stubs for INFRA-02 (virtual threads active, health endpoint reachable)
- [ ] Testcontainers + `spring-boot-testcontainers` in `libs.versions.toml` and `build.gradle.kts` test dependencies

*If none: "Existing infrastructure covers all phase requirements."*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| `docker compose up` starts all services without errors | INFRA-01 | Docker Compose orchestration outside JVM test scope | Run `docker compose up -d` and verify all containers healthy via `docker compose ps` |
| Grafana dashboards accessible | INFRA-01 | UI verification | Navigate to `http://localhost:3000` and confirm Prometheus data source connected |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
