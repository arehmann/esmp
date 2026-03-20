---
phase: 15
slug: docker-deployment-enterprise-scale
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-20
---

# Phase 15 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test 3.5.11 + Testcontainers |
| **Config file** | No standalone config — `@SpringBootTest` in each test class |
| **Quick run command** | `./gradlew test --tests "*.SourceAccessServiceTest" --no-daemon` |
| **Full suite command** | `./gradlew test --no-daemon` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*.SourceAccessServiceTest" --no-daemon`
- **After every plan wave:** Run `./gradlew test --no-daemon`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 15-01-01 | 01 | 1 | DOCK-01 | smoke | `docker build -t esmp-test .` | ❌ W0 | ⬜ pending |
| 15-01-02 | 01 | 1 | DOCK-02 | integration | `docker compose -f docker-compose.full.yml up -d && curl /actuator/health` | ❌ W0 | ⬜ pending |
| 15-01-03 | 01 | 1 | DOCK-03 | unit | `./gradlew test --tests "*.SourceAccessServiceTest#testVolumeMountStrategy"` | ❌ W0 | ⬜ pending |
| 15-01-04 | 01 | 1 | DOCK-04 | integration | `./gradlew test --tests "*.SourceAccessServiceTest#testGithubUrlStrategy"` | ❌ W0 | ⬜ pending |
| 15-01-05 | 01 | 1 | DOCK-05 | integration | Docker Compose smoke test | ❌ W0 | ⬜ pending |
| 15-02-01 | 02 | 2 | SCALE-01 | integration | `./gradlew test --tests "*.ParallelExtractionTest"` | ❌ W0 | ⬜ pending |
| 15-02-02 | 02 | 2 | SCALE-02 | integration | `./gradlew test --tests "*.BatchedPersistenceTest"` | ❌ W0 | ⬜ pending |
| 15-02-03 | 02 | 2 | SCALE-03 | unit | `./gradlew test --tests "*.ExtractionProgressServiceTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/source/application/SourceAccessServiceTest.java` — stubs for DOCK-03, DOCK-04
- [ ] `src/test/java/com/esmp/extraction/application/ParallelExtractionTest.java` — stubs for SCALE-01
- [ ] `src/test/java/com/esmp/extraction/application/BatchedPersistenceTest.java` — stubs for SCALE-02
- [ ] `src/test/java/com/esmp/extraction/application/ExtractionProgressServiceTest.java` — stubs for SCALE-03

*Docker smoke tests (DOCK-01, DOCK-02, DOCK-05) are shell-level, not JUnit.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Docker image builds | DOCK-01 | Requires Docker daemon | `docker build -t esmp-test .` and verify exit 0 |
| Full stack starts in Docker Compose | DOCK-02 | Requires Docker daemon + all services | `docker compose -f docker-compose.full.yml up -d` then `curl http://localhost:8080/actuator/health` |
| Service-to-service networking | DOCK-05 | Requires running Docker network | Verify Neo4j/MySQL/Qdrant connections via health endpoint |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
