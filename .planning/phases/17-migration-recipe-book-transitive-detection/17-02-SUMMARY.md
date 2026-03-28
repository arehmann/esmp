---
phase: 17-migration-recipe-book-transitive-detection
plan: 02
subsystem: migration-recipe-book
tags: [transitive-detection, vaadin7, extends-traversal, complexity-profiling, recipe-enrichment]
dependency_graph:
  requires: [17-01-RecipeBookRegistry, phase-16-openrewrite-migration-engine, phase-06-structural-risk-analysis]
  provides: [migrationPostProcessing, detectTransitiveMigrations, recomputeMigrationScores, enrichRecipeBook, TransitiveDetectionIntegrationTest]
  affects: [ExtractionService, IncrementalIndexingService, MigrationRecipeService, ClassNode]
tech_stack:
  added: []
  patterns: [EXTENDS*1..10 Cypher traversal, transitiveComplexity weighted scoring, MERGE idempotent inherited actions, enrichRecipeBook feedback loop]
key_files:
  created:
    - src/test/java/com/esmp/migration/application/TransitiveDetectionIntegrationTest.java
  modified:
    - src/main/java/com/esmp/migration/application/MigrationRecipeService.java
    - src/main/java/com/esmp/extraction/application/ExtractionService.java
    - src/main/java/com/esmp/indexing/application/IncrementalIndexingService.java
    - src/test/java/com/esmp/migration/application/MigrationRecipeServiceIntegrationTest.java
    - src/test/resources/fixtures/migration/SimpleVaadinView.java
decisions:
  - migrationPostProcessing() is NOT @Transactional — pure read+write orchestrator using Neo4jClient directly, similar to RagService and DashboardService
  - Complexity query uses two separate Cypher queries (ancestor method names first, then class override count) — single query chain via WITH was sufficient after fixing test setup queries
  - enrichRecipeBook() IOException logged as WARNING only — recipe book is a cache of graph data, stale counts until next extraction are acceptable
  - SimpleVaadinView fixture restored to Vaadin 7 imports — pre-existing drift caused by applyModule test writing to fixture files
  - Pre-existing cross-test interaction when running com.esmp.migration.* together deferred — plan acceptance criteria uses individual test class filters which pass
metrics:
  duration: 32min
  completed_date: "2026-03-28T18:29:00Z"
  tasks_completed: 2
  files_changed: 5
---

# Phase 17 Plan 02: Transitive Detection & Pipeline Integration Summary

Implemented transitive Vaadin 7 detection via EXTENDS*1..10 graph traversal with per-class complexity profiling, integrated `migrationPostProcessing()` into both the full extraction and incremental indexing pipelines, and added 9 integration tests covering transitive detection, complexity scoring, idempotency, recipe book enrichment, and persistence.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Implement migrationPostProcessing() with transitive detection, score recompute, enrichment | 278dd8a | MigrationRecipeService.java |
| 2 | Pipeline integration (ExtractionService + IncrementalIndexingService) and integration tests | d7a1312 | ExtractionService.java, IncrementalIndexingService.java, TransitiveDetectionIntegrationTest.java, MigrationRecipeServiceIntegrationTest.java |

## What Was Built

### migrationPostProcessing() — 3-step post-processing pipeline

**MigrationRecipeService** (extended):
- New fields: `RecipeBookRegistry recipeBookRegistry`, `MigrationConfig migrationConfig`
- `public void migrationPostProcessing()` — orchestrates 3 sub-steps with timing log

### detectTransitiveMigrations()

1. Get known Vaadin 7 source FQNs from recipe book (MAPPED/non-NEEDS_MAPPING, com.vaadin.* excluding Flow)
2. Cypher: `MATCH (c:JavaClass)-[:EXTENDS*1..10]->(ancestor:JavaClass) WHERE ancestor.fullyQualifiedName IN $vaadinSourceFqns AND NOT (c)-[:HAS_MIGRATION_ACTION]->(:MigrationAction {source: ancestor.fullyQualifiedName})`
3. Per-inheritor complexity profile:
   - Second Cypher collects ancestor method names → counts how many child methods match (overrideCount)
   - Counts CALLS edges to com.vaadin.* (excluding Flow) → ownVaadinCalls
   - Checks VaadinDataBinding / VaadinComponent labels on child
4. `transitiveComplexity = min(1.0, overrideCount*0.3 + ownVaadinCalls*0.3 + hasBinding*0.2 + hasComponent*0.2)`
5. Classification: `pureWrapper=true` → PARTIAL; `complexity<=0.4` → PARTIAL; `complexity>0.4` → NO
6. MERGE MigrationAction with `actionId = classFqn + "#INHERITED#" + ancestorFqn`; ON MATCH updates complexity fields

### recomputeMigrationScores()

Single Cypher: aggregates HAS_MIGRATION_ACTION per ClassNode and sets:
- `c.migrationActionCount`, `c.automatableActionCount`
- `c.automationScore = (yesCount + 0.5 * partialCount) / total`
- `c.needsAiMigration = (noCount > 0)`

### enrichRecipeBook()

1. Aggregate non-inherited MigrationAction nodes by source FQN → update usageCount in recipe book
2. Find actions with `context CONTAINS 'Unknown Vaadin 7 type'` → auto-add as DISCOVERED/NEEDS_MAPPING rules with `id=DISC-NNN`, `discoveredAt=LocalDate.now()`
3. Write back via `recipeBookRegistry.updateAndWrite()` — IOException logged as WARNING (non-fatal)

### Pipeline Integration

- **ExtractionService.extract()**: `migrationRecipeService.migrationPostProcessing()` inserted after `riskService.computeAndPersistRiskScores()`, before `vaadinAuditService.generateReport()`
- **IncrementalIndexingService.runIncremental()**: Step 6.5 inserted between risk computation (Step 6) and vector re-embed (Step 7), wrapped in try-catch (non-fatal failure)

### Integration Tests

**TransitiveDetectionIntegrationTest** (6 tests, RB-04):
- Synthetic graph: `com.vaadin.ui.CustomComponent` (ancestor with 2 methods) → `com.example.MyWidget` (child, overrides initContent) → `com.example.MySpecialWidget` (grandchild with VaadinComponent label + CALLS Button)
- RB-04-01: detects at least 2 inherited MigrationAction nodes
- RB-04-02: actionId format = `classFqn#INHERITED#ancestorFqn`
- RB-04-03: grandchild (VaadinComponent + CALLS Button) has complexity > 0 and pureWrapper=false
- RB-04-04: child with initContent() override has overrideCount >= 1 and complexity > 0
- RB-04-05: recomputeMigrationScores sets c.migrationActionCount >= 1 and valid automationScore
- RB-04-06: idempotent — running twice produces same count of inherited actions

**MigrationRecipeServiceIntegrationTest** (3 new tests added to existing 7, RB-03):
- RB-03-01: enrichRecipeBook updates usageCount for known Vaadin 7 type (TextField) to >= 3
- RB-03-02: enrichRecipeBook auto-discovers NEEDS_MAPPING entry for unknown type with "Unknown Vaadin 7 type" context
- RB-03-03: write-back persists — reload() after enrichment still shows updated usageCount >= 2

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Test setup Cypher used multi-WITH chain losing variable scope**
- **Found during:** Task 2 test execution (overrideCount=0 when expected >= 1)
- **Issue:** `MERGE m1 ... WITH m1 MERGE m2 ... WITH m2 MATCH (a) MERGE (a)-[:DECLARES_METHOD]->(m1)` — after `WITH m2`, variable `m1` is no longer in scope
- **Fix:** Split into separate `neo4jClient.query()` calls, each with its own MATCH + MERGE chain
- **Files modified:** `src/test/java/com/esmp/migration/application/TransitiveDetectionIntegrationTest.java`
- **Commit:** d7a1312

**2. [Rule 1 - Bug] SimpleVaadinView fixture had Vaadin 24 imports (pre-existing drift)**
- **Found during:** Task 2 MigrationRecipeServiceIntegrationTest run
- **Issue:** `applyModule_processesAutomatableClasses` writes to fixtures then restores, but the file persisted with Vaadin 24 imports from a previous run cycle
- **Fix:** Restored to Vaadin 7 imports from commit e6db5ee (original creation commit)
- **Files modified:** `src/test/resources/fixtures/migration/SimpleVaadinView.java`
- **Commit:** d7a1312

### Deferred Items

**Pre-existing cross-test interaction in `com.esmp.migration.*` wildcard run:**
- `MigrationRecipeServiceIntegrationTest.applyModule_processesAutomatableClasses` writes to fixture files. When `generatePlan_simpleView_returnsCorrectPlan` runs in a subsequent re-extraction (fresh JVM), the file may have Vaadin 24 imports if the restoration failed (or test order puts it before restoration completes).
- **Impact**: `./gradlew test --tests "com.esmp.migration.*"` fails with 1/50 test failure
- **No impact**: Running individual class filters as specified in plan acceptance criteria passes
- **Not caused by Phase 17-02 changes** — present before Task 2 wiring

## Self-Check: PASSED

### Files verified to exist:
- `src/main/java/com/esmp/migration/application/MigrationRecipeService.java` (contains `migrationPostProcessing()`) — FOUND
- `src/test/java/com/esmp/migration/application/TransitiveDetectionIntegrationTest.java` — FOUND
- ExtractionService.java contains `migrationRecipeService.migrationPostProcessing()` — FOUND (line 230)
- IncrementalIndexingService.java contains `migrationRecipeService.migrationPostProcessing()` — FOUND (line 351)

### Commits verified:
- 278dd8a — feat(17-02): implement migrationPostProcessing() with transitive detection, score recompute, enrichment
- d7a1312 — feat(17-02): wire migrationPostProcessing() into pipelines and add integration tests
