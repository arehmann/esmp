---
phase: 19-alfa-wrapper-recipe-book-deep-transitive-detection
plan: 03
subsystem: migration
tags: [alfa-wrappers, api-responses, mcp-tools, validation-queries, integration-tests]

# Dependency graph
requires:
  - phase: 19-alfa-wrapper-recipe-book-deep-transitive-detection
    plan: 01
    provides: Alfa* overlay auto-loaded in RecipeBookRegistry; com.alfa.* detection in MigrationPatternVisitor
  - phase: 19-alfa-wrapper-recipe-book-deep-transitive-detection
    plan: 02
    provides: ownAlfaCalls on MigrationActionNode; Alfa-mediated transitive detection; vaadinAncestor resolution

provides:
  - MigrationActionEntry with ownAlfaCalls field (14th component)
  - MigrationPlan with hasAlfaIntermediaries + alfaIntermediaryCount (10 components)
  - ModuleMigrationSummary with alfaAffectedClassCount + layer2ClassCount + topAlfaGaps (16 components)
  - RecipeBookController.reload() returning {count, status} JSON body
  - MigrationToolService @Tool descriptions updated to mention Alfa* coverage
  - AlfaMigrationValidationQueryRegistry with 3 Cypher queries (total: 50 validation queries)
  - AlfaMigrationApiIntegrationTest (4 tests)
  - AlfaMcpToolIntegrationTest (3 tests)

affects:
  - REST API consumers: MigrationPlan and ModuleMigrationSummary JSON now include Alfa* fields
  - MCP tool Claude Code integration: descriptions now accurately describe Alfa* data
  - Validation framework: 3 new Alfa* queries registered as Spring @Component

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Cypher CASE WHEN ... THEN c ELSE null END in count(DISTINCT ...) for conditional aggregation"
    - "Pre-existing test failures are out of scope — logged to deferred-items not fixed"

key-files:
  created:
    - src/main/java/com/esmp/migration/validation/AlfaMigrationValidationQueryRegistry.java
    - src/test/java/com/esmp/migration/api/AlfaMigrationApiIntegrationTest.java
    - src/test/java/com/esmp/migration/application/AlfaMcpToolIntegrationTest.java
  modified:
    - src/main/java/com/esmp/migration/api/MigrationActionEntry.java
    - src/main/java/com/esmp/migration/api/MigrationPlan.java
    - src/main/java/com/esmp/migration/api/ModuleMigrationSummary.java
    - src/main/java/com/esmp/migration/api/RecipeBookController.java
    - src/main/java/com/esmp/migration/application/MigrationRecipeService.java
    - src/main/java/com/esmp/mcp/tool/MigrationToolService.java

key-decisions:
  - "ValidationSeverity has only ERROR and WARNING (no INFO) — used WARNING for ALFA_TRANSITIVE_DETECTION_ACTIVE and ALFA_NEEDS_MAPPING_DISCOVERABLE"
  - "@BeforeEach with static setUpDone flag pattern (not @BeforeAll + @TestInstance) avoids Testcontainers mapped-port-before-start error"
  - "Pre-existing test failures (RecipeBookRegistryTest 6/6, MigrationRecipeServiceIntegrationTest 4/4, MigrationControllerIntegrationTest 1/1) are not caused by this plan — logged as deferred items"

requirements-completed: [ALFA-03, ALFA-04, ALFA-05]

# Metrics
duration: 35min
completed: 2026-04-03
---

# Phase 19 Plan 03: Updated API Responses and MCP Surfaces for Alfa* Data Summary

**Added ownAlfaCalls to MigrationActionEntry, hasAlfaIntermediaries/alfaIntermediaryCount to MigrationPlan, alfaAffectedClassCount/layer2ClassCount/topAlfaGaps to ModuleMigrationSummary; reload returns count; 3 validation queries; 7 integration tests all passing**

## Performance

- **Duration:** 35 min
- **Started:** 2026-04-03T10:45:00Z
- **Completed:** 2026-04-03T11:20:00Z
- **Tasks:** 3
- **Files modified:** 7 modified + 3 created

## Accomplishments

- Added `Integer ownAlfaCalls` as the 14th component to `MigrationActionEntry` record; extended `loadActionsFromGraph()` Cypher to project `ma.ownAlfaCalls` and map it null-safely
- Added `hasAlfaIntermediaries` (boolean) and `alfaIntermediaryCount` (int) to `MigrationPlan` (10 components); `generatePlan()` computes both from the action list stream using `inheritedFrom().startsWith("com.alfa.")` predicate
- Added `alfaAffectedClassCount`, `layer2ClassCount`, and `topAlfaGaps` (`List<RecipeRule>`) to `ModuleMigrationSummary` (16 components); both `getModuleSummary()` and `getProjectSummary()` execute new Alfa* Cypher sub-query and filter registry for topAlfaGaps
- Changed `RecipeBookController.reload()` return type from `ResponseEntity<Void>` to `ResponseEntity<Map<String,Object>>` returning `{count: N, status: "reloaded"}`
- Updated `MigrationToolService` @Tool descriptions for `getMigrationPlan()` and `getRecipeBookGaps()` to mention Alfa* fields
- Created `AlfaMigrationValidationQueryRegistry` as Spring `@Component` with 3 queries: `ALFA_MIGRATION_ACTIONS_PRESENT` (WARNING), `ALFA_TRANSITIVE_DETECTION_ACTIVE` (WARNING), `ALFA_NEEDS_MAPPING_DISCOVERABLE` (WARNING) — total validation queries now 50
- All 7 integration tests pass: AlfaMigrationApiIntegrationTest 4/4, AlfaMcpToolIntegrationTest 3/3

## Task Commits

1. **Task 1: Add ownAlfaCalls to MigrationActionEntry** - `562f08e` (feat)
2. **Task 2: Add Alfa* fields to MigrationPlan, ModuleMigrationSummary, reload** - `bc7975b` (feat)
3. **Task 3: MCP descriptions, validation registry, 7 integration tests** - `1fcf3bb` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/migration/api/MigrationActionEntry.java` — added `Integer ownAlfaCalls` as 14th record component
- `src/main/java/com/esmp/migration/api/MigrationPlan.java` — added `hasAlfaIntermediaries` + `alfaIntermediaryCount` as 9th/10th components
- `src/main/java/com/esmp/migration/api/ModuleMigrationSummary.java` — added `alfaAffectedClassCount` + `layer2ClassCount` + `topAlfaGaps` as 14th/15th/16th components
- `src/main/java/com/esmp/migration/api/RecipeBookController.java` — reload() returns `Map<String,Object>` with count + status
- `src/main/java/com/esmp/migration/application/MigrationRecipeService.java` — 6 changes: ownAlfaCalls in Cypher + mapper + constructor; generatePlan() Alfa* computation; getModuleSummary() + getProjectSummary() Alfa* sub-queries + topAlfaGaps
- `src/main/java/com/esmp/mcp/tool/MigrationToolService.java` — @Tool descriptions updated for getMigrationPlan() and getRecipeBookGaps()
- `src/main/java/com/esmp/migration/validation/AlfaMigrationValidationQueryRegistry.java` — NEW: 3 Cypher validation queries
- `src/test/java/com/esmp/migration/api/AlfaMigrationApiIntegrationTest.java` — NEW: 4 tests (ALFA-03-api, ALFA-04-api, ALFA-05-api, ALFA-summary)
- `src/test/java/com/esmp/migration/application/AlfaMcpToolIntegrationTest.java` — NEW: 3 tests (MCP-ALFA-01, MCP-ALFA-02, MCP-ALFA-03)

## Decisions Made

- `ValidationSeverity` enum only has `ERROR` and `WARNING` (no `INFO` value) — used `WARNING` for the two structural/informational queries (ALFA_TRANSITIVE_DETECTION_ACTIVE and ALFA_NEEDS_MAPPING_DISCOVERABLE)
- Used `@BeforeEach` with a static `setUpDone` flag instead of `@BeforeAll` + `@TestInstance(PER_CLASS)` to avoid the Testcontainers "Mapped port can only be obtained after the container is started" error during context initialization
- Cypher conditional aggregation uses `count(DISTINCT CASE WHEN condition THEN c ELSE null END)` which is Neo4j-safe for nullable conditional counting

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ValidationSeverity.INFO does not exist — replaced with WARNING**
- **Found during:** Task 3 compilation of AlfaMigrationValidationQueryRegistry.java
- **Issue:** The `ValidationSeverity` enum (defined in Phase 4) only has `ERROR` and `WARNING` values. The plan's code template used `ValidationSeverity.INFO` which caused a compilation failure.
- **Fix:** Replaced all `ValidationSeverity.INFO` occurrences with `ValidationSeverity.WARNING` in AlfaMigrationValidationQueryRegistry. Updated Javadoc comments to say "WARNING" instead of "(INFO)".
- **Files modified:** `src/main/java/com/esmp/migration/validation/AlfaMigrationValidationQueryRegistry.java`
- **Verification:** compileJava clean
- **Committed in:** 1fcf3bb (Task 3 commit)

**2. [Rule 1 - Bug] @BeforeAll + @TestInstance(PER_CLASS) caused Testcontainers startup failure**
- **Found during:** Task 3 test execution
- **Issue:** The plan instructed `@BeforeAll` with `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` for `AlfaMigrationApiIntegrationTest` and `AlfaMcpToolIntegrationTest`. However, this caused `IllegalStateException: Mapped port can only be obtained after the container is started` during Spring context initialization, because `@DynamicPropertySource` runs before containers are started in this combination.
- **Fix:** Changed `@BeforeAll` to `@BeforeEach` with a static `setUpDone = false` flag (matching the pattern used in `AlfaTransitiveDetectionIntegrationTest`). Removed `@TestInstance(PER_CLASS)` annotation.
- **Files modified:** Both test files
- **Verification:** All 7 tests pass (4/4 API + 3/3 MCP)
- **Committed in:** 1fcf3bb (Task 3 commit)

---

**Total deviations:** 2 auto-fixed (2x Rule 1 - Bug)
**Impact on plan:** Both fixes necessary for compilation and test execution. No scope creep.

## Issues Encountered

### Pre-existing Test Failures (Out of Scope)

The following test failures were present **before this plan** and are not caused by Plan 19-03 changes:

- `RecipeBookRegistryTest` — 6 failures (pre-existing; failing before this plan's changes)
- `MigrationRecipeServiceIntegrationTest` — 4 failures (pre-existing; related to file I/O and source resolution)
- `MigrationControllerIntegrationTest.getSummary_missingModule_returns400` — 1 failure (pre-existing; caused by uncommitted MigrationController change from another parallel agent that changed `@RequestParam String module` to `@RequestParam(required=false)` and added `getProjectSummary()` fallback)

These are logged in deferred-items and are not regressions introduced by this plan.

## Known Stubs

None — all fields are fully wired:
- `ownAlfaCalls` in MigrationActionEntry comes from `ma.ownAlfaCalls` in Neo4j (set by detectTransitiveMigrations() in Plan 19-02)
- `hasAlfaIntermediaries` / `alfaIntermediaryCount` computed in-memory from the action list
- `alfaAffectedClassCount` / `layer2ClassCount` from Cypher sub-queries
- `topAlfaGaps` filtered from the live recipe book registry

## Self-Check: PASSED
