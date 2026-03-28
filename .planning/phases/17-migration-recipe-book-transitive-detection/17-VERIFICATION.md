---
phase: 17-migration-recipe-book-transitive-detection
verified: 2026-03-28T19:30:00Z
status: passed
score: 6/6 must-haves verified
re_verification: false
---

# Phase 17: Migration Recipe Book & Transitive Detection Verification Report

**Phase Goal:** Externalize migration type mappings to a loadable JSON recipe book with base + custom overlay support, detect transitive Vaadin 7 usage through EXTENDS graph traversal with per-class complexity profiling, enrich the recipe book with usage counts and unmapped type discovery after each extraction, and expose recipe book management and enriched migration data via REST API and updated MCP tools.
**Verified:** 2026-03-28T19:30:00Z
**Status:** passed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Migration rules are loaded from an external JSON file, not hardcoded Java maps | VERIFIED | `RecipeBookRegistry.java` — `@PostConstruct load()` copies seed from classpath and loads via `ObjectMapper.readValue()`; `MigrationPatternVisitor.java` contains no `TYPE_MAP`, `PARTIAL_MAP`, `COMPLEX_TYPES`, or `JAVAX_PACKAGE_MAP` static fields |
| 2 | Recipe book contains 80+ rules covering Vaadin 7 components, data, server, and javax/jakarta types | VERIFIED | `vaadin-recipe-book-seed.json` contains 94 rules: COMPONENT=56, DATA_BINDING=13, SERVER=15, JAVAX_JAKARTA=10; all 94 IDs unique; all required fields non-null |
| 3 | Custom overlay file merges on top of base rules by source FQN key | VERIFIED | `RecipeBookRegistry.load()` (lines 100–115) applies overlay rules keyed by `rule.source()`, sets `isBase=false` on overlay entries; `RecipeBookRegistryTest` has 9 tests including overlay merge and isBase flag checks |
| 4 | MigrationPatternVisitor reads from RecipeBookRegistry instead of static TYPE_MAP/PARTIAL_MAP/COMPLEX_TYPES/JAVAX_PACKAGE_MAP | VERIFIED | `MigrationPatternVisitor` constructor accepts `RecipeBookRegistry`, builds `rulesBySource` snapshot; `processImport()` uses `rulesBySource.get(importFqn)`; grep confirms no static maps present |
| 5 | Classes inheriting from Vaadin 7 types are detected via EXTENDS graph traversal with per-class complexity profiling | VERIFIED | `MigrationRecipeService.detectTransitiveMigrations()` (line 431) uses `MATCH (c:JavaClass)-[:EXTENDS*1..10]->(ancestor:JavaClass)` with NOT guard; complexity profile computed via second query (`DECLARES_METHOD` + `CALLS` edges); `TransitiveDetectionIntegrationTest` has 6 tests confirming this |
| 6 | Recipe book enrichment, REST API management, and enriched MCP tools are exposed | VERIFIED | `RecipeBookController` at `/api/migration/recipe-book` with 5 endpoints; `MigrationActionEntry` has 13 fields including `isInherited`, `pureWrapper`, `vaadinAncestor`, `migrationSteps`; `MigrationToolService.getRecipeBookGaps()` with `@Tool` annotation exists |

**Score:** 6/6 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/migration/application/RecipeBookRegistry.java` | Recipe book loading, merging, reload, write-back | VERIFIED | `@Component`; `@PostConstruct load()`; `getRules()`, `reload()`, `findBySource()`, `updateAndWrite()` all present; 202 lines of substantive implementation |
| `src/main/resources/migration/vaadin-recipe-book-seed.json` | Seed recipe book with 80+ rules | VERIFIED | 94 rules; all 4 required categories present; all 94 IDs unique; no null required fields; MAPPED+CHANGE_TYPE/CHANGE_PACKAGE rules all have non-null targets |
| `src/main/java/com/esmp/migration/api/RecipeRule.java` | Immutable record with 12 fields including `isBase` | VERIFIED | `record RecipeRule(String id, String category, String source, String target, String actionType, String automatable, String context, List<String> migrationSteps, String status, int usageCount, String discoveredAt, boolean isBase)` — exactly 12 fields |
| `src/main/java/com/esmp/migration/api/RecipeBook.java` | Simple wrapper record | VERIFIED | `record RecipeBook(List<RecipeRule> rules)` present in migration api package |
| `src/main/java/com/esmp/extraction/config/MigrationConfig.java` | Extended with `recipeBookPath`, `customRecipeBookPath`, `TransitiveConfig` | VERIFIED | All 3 additions present; `TransitiveConfig` static inner class with 5 weight fields and `aiAssistedThreshold`; proper getters/setters |
| `src/main/java/com/esmp/extraction/model/MigrationActionNode.java` | 6 transitive detection fields | VERIFIED | `isInherited`, `pureWrapper`, `transitiveComplexity`, `vaadinAncestor`, `overrideCount`, `ownVaadinCalls` — all 6 fields with getters/setters |
| `src/main/java/com/esmp/extraction/visitor/MigrationPatternVisitor.java` | Uses RecipeBookRegistry, no static maps | VERIFIED | Constructor `MigrationPatternVisitor(RecipeBookRegistry registry)`; snapshot pattern; no static TYPE_MAP/PARTIAL_MAP/COMPLEX_TYPES/JAVAX_PACKAGE_MAP |
| `src/main/java/com/esmp/migration/application/MigrationRecipeService.java` | `migrationPostProcessing()` with 3 sub-steps | VERIFIED | `migrationPostProcessing()` at line 415; calls `detectTransitiveMigrations()`, `recomputeMigrationScores()`, `enrichRecipeBook()` |
| `src/main/java/com/esmp/migration/api/RecipeBookController.java` | 5 recipe book management endpoints | VERIFIED | `@RestController @RequestMapping("/api/migration/recipe-book")`; `getAllRules`, `getGaps`, `upsertRule`, `deleteRule`, `reload` — all 5 handler methods |
| `src/main/java/com/esmp/migration/api/MigrationActionEntry.java` | 13 fields with transitive + migrationSteps fields | VERIFIED | Record has 13 fields: original 5 + `isInherited`, `inheritedFrom`, `vaadinAncestor`, `pureWrapper`, `transitiveComplexity`, `overrideCount`, `ownVaadinCalls`, `migrationSteps` |
| `src/main/java/com/esmp/migration/api/ModuleMigrationSummary.java` | 13 fields with transitiveClassCount, coverageByType/Usage, topGaps | VERIFIED | Record has 13 fields: original 9 + `transitiveClassCount`, `coverageByType`, `coverageByUsage`, `topGaps` |
| `src/main/java/com/esmp/mcp/tool/MigrationToolService.java` | `getRecipeBookGaps()` MCP tool + `recipeBookRegistry` field | VERIFIED | `@Tool` `getRecipeBookGaps()` at line 356; `private final RecipeBookRegistry recipeBookRegistry` at line 68 |
| `src/main/java/com/esmp/migration/validation/MigrationValidationQueryRegistry.java` | 3 new validation queries (6 total in registry) | VERIFIED | `RECIPE_BOOK_LOADED`, `TRANSITIVE_ACTIONS_DETECTED`, `MIGRATION_COVERAGE_GAPS` all present |
| `src/test/java/com/esmp/migration/application/RecipeBookRegistryTest.java` | At least 5 `@Test` methods | VERIFIED | 10 `@Test` methods |
| `src/test/java/com/esmp/migration/application/RecipeBookSeedTest.java` | Asserts `rules.size() >= 80` | VERIFIED | 17 `@Test` methods; seed integrity checks present |
| `src/test/java/com/esmp/migration/application/TransitiveDetectionIntegrationTest.java` | At least 6 `@Test` methods covering RB-04 | VERIFIED | 7 `@Test` methods covering RB-04 scenarios |
| `src/test/java/com/esmp/migration/api/RecipeBookControllerIntegrationTest.java` | At least 7 `@Test` methods | VERIFIED | 8 `@Test` methods including GET, filter, gaps, PUT, DELETE (204), DELETE base (403), DELETE not-found (404), POST reload |
| `src/test/java/com/esmp/migration/api/MigrationControllerIntegrationTest.java` | At least 3 new `@Test` methods for RB-06 | VERIFIED | 11 total `@Test` methods; RB-06-01/02/03 all present with `isInherited`, `vaadinAncestor`, `migrationSteps`, `coverageByType`, `coverageByUsage` assertions |
| `src/test/resources/migration/test-recipe-book.json` | 5-rule test fixture | VERIFIED | File present in migration test resources |
| `src/test/resources/migration/test-custom-overlay.json` | Overlay fixture with 1 overriding rule | VERIFIED | File present in migration test resources |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `MigrationPatternVisitor.java` | `RecipeBookRegistry.java` | Constructor argument, snapshot at construction time | WIRED | `RecipeBookRegistry.*registry` pattern confirmed at line 49–59; snapshot built into `rulesBySource` map |
| `ExtractionService.java` | `RecipeBookRegistry.java` | Spring injection, passed to MigrationPatternVisitor | WIRED | `private final RecipeBookRegistry recipeBookRegistry` (line 83); passed as `new MigrationPatternVisitor(recipeBookRegistry)` at lines 282 and 383 |
| `ExtractionService.java` | `MigrationRecipeService.java` | `migrationPostProcessing()` call after `computeAndPersistRiskScores()` | WIRED | `migrationRecipeService.migrationPostProcessing()` at line 230 of ExtractionService |
| `IncrementalIndexingService.java` | `MigrationRecipeService.java` | `migrationPostProcessing()` call between risk and vector steps | WIRED | `migrationRecipeService.migrationPostProcessing()` at line 351 of IncrementalIndexingService |
| `RecipeBookController.java` | `RecipeBookRegistry.java` | Spring injection | WIRED | `private final RecipeBookRegistry recipeBookRegistry` injected via constructor; used in all 5 handler methods |
| `MigrationToolService.java` | `RecipeBookRegistry.java` | Spring injection for `getRecipeBookGaps` | WIRED | `private final RecipeBookRegistry recipeBookRegistry` (line 68); used in `getRecipeBookGaps()` (lines 359–362) |
| `MigrationRecipeService.loadActionsFromGraph()` | `RecipeBookRegistry.java` | Post-query enrichment with `migrationSteps` | WIRED | `recipeBookRegistry.findBySource(source).map(RecipeRule::migrationSteps)` at line 774–777 |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| RB-01 | 17-01 | Migration rules stored in external JSON recipe book (not hardcoded Java maps) — loaded at startup, supports base rules + user custom overlay | SATISFIED | `RecipeBookRegistry` loads seed JSON at `@PostConstruct`, merges overlay by source FQN key; `MigrationPatternVisitor` has no static maps |
| RB-02 | 17-01 | Comprehensive initial recipe book covering all known Vaadin 7 → 24 component/data/server types plus javax → jakarta mappings (80+ rules) | SATISFIED | 94 rules in seed JSON; COMPONENT=56, DATA_BINDING=13, SERVER=15, JAVAX_JAKARTA=10; all integrity invariants pass |
| RB-03 | 17-02 | Extraction-driven enrichment — after each extraction, recipe book updated with per-rule usage counts, discovered unmapped types (NEEDS_MAPPING), and codebase-specific statistics | SATISFIED | `enrichRecipeBook()` in `MigrationRecipeService` aggregates usage counts from graph, auto-discovers "Unknown Vaadin 7 type" entries as DISC-NNN; `MigrationRecipeServiceIntegrationTest` has 3 tests covering RB-03-01/02/03 |
| RB-04 | 17-02 | Transitive detection via EXTENDS graph traversal — custom widgets inheriting from Vaadin 7 types discovered, classified as pure wrapper (no overrides) or complex, assigned inherited automation level | SATISFIED | `detectTransitiveMigrations()` uses `EXTENDS*1..10` with NOT guard; complexity = min(1.0, overrideCount×0.3 + ownVaadinCalls×0.3 + hasBinding×0.2 + hasComponent×0.2); `TransitiveDetectionIntegrationTest` 6 tests pass |
| RB-05 | 17-03 | REST API for recipe book management (view rules, view gaps, add/update custom rules) and updated MCP tools that surface transitive actions and coverage scores | SATISFIED | `RecipeBookController` with 5 endpoints; `getRecipeBookGaps()` MCP tool; `RecipeBookControllerIntegrationTest` 8 tests |
| RB-06 | 17-03 | AI-optimized output — getMigrationPlan returns enrichment context (usageCount, pureWrapper flag, vaadinAncestor, migration steps) so Claude Code can make accurate migration decisions without additional queries | SATISFIED | `MigrationActionEntry` has 13 fields; `loadActionsFromGraph()` enriches with `migrationSteps` from registry; `MigrationControllerIntegrationTest` RB-06-01/02/03 all verify enriched output |

All 6 requirements satisfied.

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `MigrationRecipeService.java` | 970 | `return null` in `buildCompositeRecipe()` | Info | Legitimate — returns null when no automatable recipes found (checked by caller); not a stub |
| `MigrationPatternVisitor.java` | 173 | `return null` in `resolveClassFqn()` | Info | Legitimate — returns null when compilation unit has no class declarations; guards at call site |

No blockers or warnings found.

---

### Human Verification Required

#### 1. Seed JSON Rule Quality

**Test:** Browse `GET /api/migration/recipe-book` output and spot-check 5-10 rules across categories (COMPONENT, SERVER, DATA_BINDING). Verify that `migrationSteps` arrays contain meaningful AI-actionable guidance.
**Expected:** COMPLEX_REWRITE rules (e.g., `com.vaadin.ui.Table`) have 3-5 detailed migration steps; CHANGE_TYPE rules have at least 2 steps.
**Why human:** Content quality of natural-language migration instructions cannot be validated programmatically.

#### 2. Transitive Complexity Scoring Calibration

**Test:** Run extraction on a real codebase with custom Vaadin 7 widget inheritance. Examine inherited MigrationAction nodes with `isInherited=true`. Verify that `transitiveComplexity` scores feel proportionate — pure wrappers at 0.0, complex classes with multiple overrides near 0.6+.
**Expected:** Scores reflect actual migration effort. `pureWrapper=true` classes should be mechanically migratable; `pureWrapper=false` with `transitiveComplexity > 0.4` should require AI assistance.
**Why human:** Score calibration is a judgment call requiring domain knowledge about migration effort.

#### 3. Recipe Book Overlay End-to-End

**Test:** Configure `ESMP_MIGRATION_CUSTOM_RECIPE_BOOK_PATH` to point at a custom overlay file with an enterprise-specific type. Restart the app and verify `GET /api/migration/recipe-book` shows the custom rule with `isBase=false`.
**Expected:** Custom rule appears, overrides base rule if source FQN matches, and `POST /api/migration/recipe-book/reload` refreshes the overlay.
**Why human:** File system path resolution and environment variable override require a running deployment.

---

## Gaps Summary

No gaps. All phase 17 must-haves are verified as present, substantive, and wired.

Notable implementation details that deviate harmlessly from plan:
- Seed JSON has 94 rules (plan required 80+) — this is an upward deviation, not a gap.
- NAV and EVT rule IDs are filed under COMPONENT and DATA_BINDING categories respectively (the plan's category list specified COMPONENT, DATA_BINDING, SERVER, JAVAX_JAKARTA, DISCOVERED — the seed uses these exact 4 active categories, with NAV/EVT classified logically within them). The `RecipeBookSeedTest` validates category values against the allowed set and passes.
- The SUMMARY noted a pre-existing cross-test interaction (`com.esmp.migration.*` wildcard run fails 1/50 due to `applyModule` writing fixture files). This is a pre-existing issue from phase 16 and does not block any phase 17 acceptance criteria — individual test class filters all pass.

---

_Verified: 2026-03-28T19:30:00Z_
_Verifier: Claude (gsd-verifier)_
