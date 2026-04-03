---
phase: 19-alfa-wrapper-recipe-book-deep-transitive-detection
plan: 02
subsystem: migration
tags: [transitive-detection, alfa-wrappers, neo4j-graph, complexity-scoring, integration-tests]

# Dependency graph
requires:
  - phase: 19-alfa-wrapper-recipe-book-deep-transitive-detection
    plan: 01
    provides: Alfa* overlay auto-loaded in RecipeBookRegistry; com.alfa.* detection in MigrationPatternVisitor

provides:
  - Extended detectTransitiveMigrations() with com.alfa.* in ancestor filter (ALFA-02)
  - resolveUltimateVaadinAncestor() helper that walks EXTENDS* to find com.vaadin.* leaf
  - ownAlfaCalls complexity dimension in transitiveComplexity scoring (ALFA-04)
  - alfaCallsWeight = 0.2 in TransitiveConfig
  - AlfaTransitiveDetectionIntegrationTest (5 tests covering ALFA-02a/b, ALFA-04a/b/c)

affects:
  - 19-03 (API responses now include Alfa-mediated actions with correct vaadinAncestor)
  - MigrationRecipeService.generatePlan() (Layer 2 Alfa classes now get migrationSteps from Alfa* rules)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "resolveUltimateVaadinAncestor() uses EXTENDS*1..10 STARTS WITH com.vaadin. + LIMIT 1 — walks chain to first Vaadin 7 node"
    - "c2 re-bind pattern in Cypher: second OPTIONAL MATCH uses c2 alias after WITH to avoid variable shadowing"
    - "inheritedFrom = ma.source (direct Alfa* ancestor); vaadinAncestor = ma.vaadinAncestor (ultimate com.vaadin.* leaf) — distinct for Alfa chains"
    - "Test overlay uses {"rules":[...]} wrapper — plain array fails Jackson deserialization of RecipeBook record"

key-files:
  created:
    - src/test/java/com/esmp/migration/application/AlfaTransitiveDetectionIntegrationTest.java
    - src/test/resources/fixtures/migration/alfa/AlfaButton.java
    - src/test/resources/fixtures/migration/alfa/AlfaTable.java
    - src/test/resources/fixtures/migration/alfa/AlfaButtonWrapper.java
    - src/test/resources/fixtures/migration/alfa/AlfaTableExtension.java
    - src/test/resources/fixtures/migration/alfa/BusinessServiceImpl.java
    - src/test/resources/fixtures/migration/alfa/ComplexBusinessView.java
    - src/test/resources/fixtures/migration/alfa/alfa-test-overlay.json
  modified:
    - src/main/java/com/esmp/extraction/model/MigrationActionNode.java
    - src/main/java/com/esmp/extraction/config/MigrationConfig.java
    - src/main/resources/application.yml
    - src/main/java/com/esmp/migration/application/MigrationRecipeService.java

key-decisions:
  - "vaadinAncestor on MigrationAction nodes is set to ultimateVaadinAncestor (com.vaadin.* leaf), not ancestorFqn (which may be com.alfa.*) — consumers can always find the real Vaadin 7 target"
  - "inheritedFrom in loadActionsFromGraph() is now ma.source (direct ancestor) not ma.vaadinAncestor — for Alfa chains they are different; for direct Vaadin chains they are the same"
  - "c2 variable alias used in second OPTIONAL MATCH for ownAlfaCalls to avoid Cypher variable shadowing after WITH clause"
  - "Test overlay format must be {"rules":[...]} (RecipeBook wrapper), not a plain array — RecipeBookRegistry uses objectMapper.readValue(.., RecipeBook.class)"

requirements-completed: [ALFA-02, ALFA-04]

# Metrics
duration: 25min
completed: 2026-04-03
---

# Phase 19 Plan 02: Deep Transitive Detection for Alfa* Chains Summary

**Extended detectTransitiveMigrations() to traverse Alfa* intermediaries via EXTENDS*1..10, resolving vaadinAncestor to the ultimate com.vaadin.* leaf; ownAlfaCalls added as a fourth complexity dimension; 5 integration tests validate ALFA-02 and ALFA-04**

## Performance

- **Duration:** 25 min
- **Started:** 2026-04-03T10:12:00Z
- **Completed:** 2026-04-03T10:37:35Z
- **Tasks:** 3
- **Files modified:** 4 source + 8 test (1 test class, 6 fixture .java, 1 overlay JSON)

## Accomplishments

- Added `ownAlfaCalls` Integer field to MigrationActionNode with getter/setter; added `alfaCallsWeight = 0.2` to TransitiveConfig with getter/setter; added `alfa-calls-weight: 0.2` to application.yml
- Extended `detectTransitiveMigrations()` ancestor filter to include `com.alfa.*` sources (single-line change enabling Alfa-mediated transitive detection without changing the EXTENDS*1..10 Cypher traversal)
- Added `resolveUltimateVaadinAncestor(String startFqn)` private helper that queries `EXTENDS*1..10` from the Alfa* class to find the first `com.vaadin.*` ancestor (excluding `com.vaadin.flow.`); returns `Optional<String>` — empty if none found within 10 hops
- Extended complexity Cypher query with third `OPTIONAL MATCH` on `c2` alias to count `ownAlfaCalls` via CALLS edges to `com.alfa.*` nodes; ownAlfaCalls now stored on MigrationAction nodes and included in rawScore
- Updated MERGE query to store `vaadinAncestor = $ultimateVaadinAncestor` (resolved com.vaadin.* leaf, not the com.alfa.* intermediary)
- Fixed `loadActionsFromGraph()`: `inheritedFrom` is now `source` (direct Alfa* ancestor), `vaadinAncestor` from `ma.vaadinAncestor` — distinct values for Alfa-mediated actions
- Context string now distinguishes Alfa-mediated from direct Vaadin inheritance with explicit `→ Vaadin 7 ancestor:` notation
- Created 6 fixture Java files covering Layer 0 (com.vaadin.*), Layer 1 (com.alfa.*), Layer 1.5 (AlfaButtonWrapper, AlfaTableExtension), and Layer 2 (BusinessServiceImpl, ComplexBusinessView)
- Created `alfa-test-overlay.json` with correct `{"rules":[...]}` wrapper format (plain array fails RecipeBook Jackson deserialization)
- All 5 AlfaTransitiveDetectionIntegrationTest tests pass: ALFA-02a, ALFA-02b, ALFA-04a, ALFA-04b, ALFA-04c

## Task Commits

1. **Task 1: ownAlfaCalls + alfaCallsWeight** - `84da634` (feat)
2. **Task 2: Extend detectTransitiveMigrations()** - `3fc4691` (feat)
3. **Task 3: Fixture hierarchy + integration tests** - `88ad8fa` (test)

## Files Created/Modified

- `src/main/java/com/esmp/extraction/model/MigrationActionNode.java` — added `ownAlfaCalls` Integer field with getter/setter
- `src/main/java/com/esmp/extraction/config/MigrationConfig.java` — added `alfaCallsWeight = 0.2` to TransitiveConfig
- `src/main/resources/application.yml` — added `alfa-calls-weight: 0.2` under `esmp.migration.transitive`
- `src/main/java/com/esmp/migration/application/MigrationRecipeService.java` — 7 changes: ancestor filter, ultimateVaadinAncestor local var, extended complexity query + lambda, ownAlfaCalls in rawScore, updated context string, updated MERGE with new bindings, resolveUltimateVaadinAncestor() helper, fixed inheritedFrom in loadActionsFromGraph()
- `src/test/java/com/esmp/migration/application/AlfaTransitiveDetectionIntegrationTest.java` — 5 integration tests (ALFA-02a/b, ALFA-04a/b/c)
- `src/test/resources/fixtures/migration/alfa/alfa-test-overlay.json` — test overlay with AlfaButton (CHANGE_TYPE/YES) and AlfaTable (COMPLEX_REWRITE/NO)
- 6 fixture Java files covering the Alfa* inheritance hierarchy

## Decisions Made

- `vaadinAncestor` on MigrationAction nodes is set to `ultimateVaadinAncestor` (the deepest `com.vaadin.*` ancestor found via EXTENDS traversal), not `ancestorFqn` (which is the com.alfa.* class that triggered the detection). This ensures API consumers always get the real Vaadin 7 target type for recipe lookup.
- `inheritedFrom` in `loadActionsFromGraph()` is now `source` (the direct Alfa* ancestor), and `vaadinAncestor` comes from `ma.vaadinAncestor`. For direct Vaadin actions these are identical; for Alfa-mediated actions they differ as intended.
- Cypher re-binds `c2` in the third OPTIONAL MATCH to avoid variable shadowing after the second WITH clause — safe across all Neo4j versions.
- The test overlay JSON must use `{"rules":[...]}` format (matching the `RecipeBook` record structure) rather than a plain array, because `RecipeBookRegistry` uses `objectMapper.readValue(.., RecipeBook.class)`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed test overlay JSON format from plain array to {"rules":[...]} wrapper**
- **Found during:** Task 3 (application context failed to start in integration test)
- **Issue:** `alfa-test-overlay.json` was created as a plain JSON array `[...]` but `RecipeBookRegistry.load()` deserializes the overlay file via `objectMapper.readValue(overlayFile.toFile(), RecipeBook.class)`. The `RecipeBook` record expects `{"rules":[...]}`. The plain array caused a Jackson `MismatchedInputException`, which propagated as `BeanCreationException` for `recipeBookRegistry` at Spring context startup.
- **Fix:** Wrapped the overlay JSON in `{"rules":[...]}` format and added all required `RecipeRule` fields (id, category, context, discoveredAt, etc.).
- **Files modified:** `src/test/resources/fixtures/migration/alfa/alfa-test-overlay.json`
- **Verification:** AlfaTransitiveDetectionIntegrationTest context started cleanly; 5/5 tests pass
- **Committed in:** 88ad8fa (Task 3 commit — overlay was corrected before commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Fix required for test to start. No scope creep. The fix clarifies the RecipeBook wrapper format as a pattern decision for future overlay test files.

## Issues Encountered

None beyond the deviation documented above.

## Known Stubs

None — all detection logic is fully wired. The `resolveUltimateVaadinAncestor()` helper returns `Optional.empty()` if no Vaadin ancestor is found within 10 hops, causing `ancestorFqn` to be used as fallback — intentional graceful degradation.

## Next Phase Readiness

- Plan 19-03 can expose `vaadinAncestor`, `ownAlfaCalls`, and `inheritedFrom` fields in API responses for Alfa-mediated actions
- `generatePlan()` now returns migrationSteps from Alfa* recipe rules for Layer 2 classes
- `migrationPostProcessing()` is idempotent — running twice produces no duplicate MigrationAction nodes (MERGE semantics + stable actionId)
- ALFA-02 satisfied: EXTENDS*1..10 traversal through com.alfa.* intermediaries; vaadinAncestor resolves to com.vaadin.* leaf
- ALFA-04 satisfied: pureWrapper=true/false + ownAlfaCalls + transitiveComplexity correctly computed for Layer 2 classes

---
*Phase: 19-alfa-wrapper-recipe-book-deep-transitive-detection*
*Completed: 2026-04-03*
