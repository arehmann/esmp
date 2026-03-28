---
phase: 17
slug: migration-recipe-book-transitive-detection
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-28
---

# Phase 17 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers (Neo4j + MySQL + Qdrant) |
| **Config file** | No separate config — Spring Boot auto-configuration with `@SpringBootTest` |
| **Quick run command** | `./gradlew test --tests "com.esmp.migration.*" -x vaadinPrepareFrontend` |
| **Full suite command** | `./gradlew test -x vaadinPrepareFrontend` |
| **Estimated runtime** | ~45 seconds (migration tests) / ~120 seconds (full suite) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.migration.*" -x vaadinPrepareFrontend`
- **After every plan wave:** Run `./gradlew test -x vaadinPrepareFrontend`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| RB-01 | 01 | 1 | RecipeBookRegistry load/merge/reload | Unit | `./gradlew test --tests "com.esmp.migration.application.RecipeBookRegistryTest" -x vaadinPrepareFrontend` | ❌ W0 | ⬜ pending |
| RB-02 | 01 | 1 | Seed JSON 80+ rules, valid fields, unique IDs | Unit | `./gradlew test --tests "com.esmp.migration.application.RecipeBookSeedTest" -x vaadinPrepareFrontend` | ❌ W0 | ⬜ pending |
| RB-03 | 02 | 2 | enrichRecipeBook() usageCount + DISCOVERED entries | Integration | `./gradlew test --tests "com.esmp.migration.application.MigrationRecipeServiceIntegrationTest" -x vaadinPrepareFrontend` | ✅ (extend) | ⬜ pending |
| RB-04 | 02 | 2 | Transitive detection + complexity classification | Integration | `./gradlew test --tests "com.esmp.migration.application.TransitiveDetectionIntegrationTest" -x vaadinPrepareFrontend` | ❌ W0 | ⬜ pending |
| RB-05 | 03 | 3 | RecipeBookController CRUD + base rule protection | Integration | `./gradlew test --tests "com.esmp.migration.api.RecipeBookControllerIntegrationTest" -x vaadinPrepareFrontend` | ❌ W0 | ⬜ pending |
| RB-06 | 03 | 3 | MCP tools return transitive fields + migrationSteps | Integration | `./gradlew test --tests "com.esmp.migration.api.MigrationControllerIntegrationTest" -x vaadinPrepareFrontend` | ✅ (extend) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/migration/application/RecipeBookRegistryTest.java` — unit test for @PostConstruct load, overlay merge, reload — covers RB-01
- [ ] `src/test/java/com/esmp/migration/application/RecipeBookSeedTest.java` — validates seed JSON integrity — covers RB-02
- [ ] `src/test/java/com/esmp/migration/application/TransitiveDetectionIntegrationTest.java` — integration test with fixture graph data — covers RB-04
- [ ] `src/test/java/com/esmp/migration/api/RecipeBookControllerIntegrationTest.java` — REST endpoint integration tests — covers RB-05
- [ ] `src/test/resources/migration/` — test seed JSON fixture for unit tests (minimal, not the full 80+ production seed)

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
