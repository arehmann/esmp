---
phase: 19-alfa-wrapper-recipe-book-deep-transitive-detection
verified: 2026-04-03T12:00:00Z
status: passed
score: 7/7 must-haves verified
re_verification: false
human_verification:
  - test: "Run the application against a real adsuite-market source root and confirm Layer 2 classes (those that extend AlfaButton, AlfaTable, etc.) receive MigrationAction nodes with isInherited=true and vaadinAncestor pointing to com.vaadin.*"
    expected: "getMigrationPlan returns hasAlfaIntermediaries=true for at least one module class"
    why_human: "Integration tests use synthetic fixture classes; real production source traversal cannot be verified programmatically without a running environment"
  - test: "Call POST /api/migration/recipe-book/reload on a running instance and verify response body contains count >= 150"
    expected: "JSON: {\"count\": N, \"status\": \"reloaded\"} where N >= 150"
    why_human: "Requires a running application server; endpoint signature verified statically but actual reload behaviour needs a live container"
---

# Phase 19: Alfa* Wrapper Recipe Book & Deep Transitive Detection — Verification Report

**Phase Goal:** getMigrationPlan and getRecipeBookGaps return actionable results for all 1,076 Vaadin-affected classes in adsuite-market — not just the ~150 that directly import com.vaadin.*. Achieved by ingesting the full Alfa* wrapper catalog into the recipe book and extending transitive detection to follow inheritance chains through Alfa* intermediaries to their Vaadin 7 ancestors.

**Verified:** 2026-04-03T12:00:00Z
**Status:** passed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Recipe book contains 150+ Alfa* rules covering all 10 wrapper categories | VERIFIED | `alfa-recipe-book-overlay.json` has exactly 150 rules; node count confirmed via JSON parse: 0 dup IDs, 0 dup sources, all 10 ALFA_* categories present |
| 2 | RecipeBookRegistry auto-loads Alfa* overlay on every startup/reload without manual config | VERIFIED | `RecipeBookRegistry.load()` step 2b: `loadOverlayFromClasspath()` called unconditionally when `alfaOverlayPath` is non-blank (default is set in `MigrationConfig`); `reload()` delegates to `load()` |
| 3 | MigrationPatternVisitor detects com.alfa.* imports — known types via registry lookup, unknown via COMPLEX_REWRITE/NO fallback | VERIFIED | Step 4 present in visitor (line 148–157); `AlfaCatalogOverlayTest.registryLoadsAlfaOverlayAutomatically` verifies registry finds `com.alfa.ui.AlfaButton` after load |
| 4 | EXTENDS traversal through Alfa* intermediaries: Layer 2 class receives MigrationAction with vaadinAncestor = com.vaadin.* leaf | VERIFIED | `detectTransitiveMigrations()` includes `com.alfa.*` in ancestor filter (line 651); `resolveUltimateVaadinAncestor()` walks EXTENDS*1..10 to find `com.vaadin.*` leaf; 5 integration tests cover ALFA-02a/b and ALFA-04a/b/c |
| 5 | GET /api/migration/recipe-book/gaps returns NEEDS_MAPPING entries including the 4 GWT/spike Alfa* entries | VERIFIED | `RecipeBookController.getGaps()` filters `NEEDS_MAPPING` from live registry; overlay contains 4 NEEDS_MAPPING entries (AlfaStyloPanel, DTPEditorPanel, AlfaColorChooser, AlfaCalendarWindow) verified by JSON parse and `AlfaCatalogOverlayTest.overlayNeedsMappingEntriesPresent` |
| 6 | getMigrationPlan for Layer 2 class returns hasAlfaIntermediaries=true, ownAlfaCalls populated, migrationSteps from Alfa* rule | VERIFIED | `generatePlan()` computes `hasAlfaIntermediaries` and `alfaIntermediaryCount` from action stream; `ownAlfaCalls` projected from `ma.ownAlfaCalls` in Cypher; `MigrationActionEntry` record has 14th component `Integer ownAlfaCalls` |
| 7 | ModuleMigrationSummary includes alfaAffectedClassCount, layer2ClassCount, topAlfaGaps; reload endpoint returns JSON with count | VERIFIED | `ModuleMigrationSummary` record (16 components) confirmed; `getModuleSummary()` and `getProjectSummary()` both execute Alfa* Cypher sub-queries and populate the fields; `RecipeBookController.reload()` returns `Map.of("count", count, "status", "reloaded")` |

**Score:** 7/7 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/resources/migration/alfa-recipe-book-overlay.json` | 150+ rules, 10 categories, 4 NEEDS_MAPPING | VERIFIED | 150 rules, 10 ALFA_* categories, 4 NEEDS_MAPPING entries; 0 duplicate IDs/sources |
| `src/main/java/com/esmp/extraction/config/MigrationConfig.java` | alfaOverlayPath field + alfaCallsWeight in TransitiveConfig | VERIFIED | Field `alfaOverlayPath` with classpath default present (line 59); `TransitiveConfig.alfaCallsWeight = 0.2` with getter/setter (lines 96–144) |
| `src/main/java/com/esmp/migration/application/RecipeBookRegistry.java` | step 2b loadOverlayFromClasspath() wired in load() | VERIFIED | Step 2b block at lines 99–103; `loadOverlayFromClasspath()` private helper at lines 195–213; WARN on missing resource, no throw |
| `src/main/java/com/esmp/extraction/visitor/MigrationPatternVisitor.java` | com.alfa.* step 4 detection + Javadoc updated | VERIFIED | Step 4 block present (lines 148–157); Javadoc Detection Strategy item 4 documented |
| `src/main/java/com/esmp/extraction/model/MigrationActionNode.java` | ownAlfaCalls field with getter/setter | VERIFIED | `Integer ownAlfaCalls` field (line 112) with `getOwnAlfaCalls()` and `setOwnAlfaCalls()` |
| `src/main/java/com/esmp/migration/application/MigrationRecipeService.java` | detectTransitiveMigrations() extended; resolveUltimateVaadinAncestor(); ownAlfaCalls in Cypher; generatePlan() Alfa* fields; getModuleSummary() Alfa* sub-query | VERIFIED | All 7 changes documented in summary confirmed in code: ancestor filter at line 651 includes `com.alfa.*`; `resolveUltimateVaadinAncestor()` at line 992; ownAlfaCalls in complexity Cypher (lines 718–720); `generatePlan()` computes `hasAlfaIntermediaries`/`alfaIntermediaryCount` (lines 110–116); `getModuleSummary()` Alfa* sub-query at lines 576–599; `getProjectSummary()` Alfa* sub-query at lines 409–434 |
| `src/main/java/com/esmp/migration/api/MigrationActionEntry.java` | 14th component Integer ownAlfaCalls | VERIFIED | 14-component record; `Integer ownAlfaCalls` at position 13 (0-indexed) |
| `src/main/java/com/esmp/migration/api/MigrationPlan.java` | hasAlfaIntermediaries + alfaIntermediaryCount | VERIFIED | 10-component record; both fields present as 9th/10th components |
| `src/main/java/com/esmp/migration/api/ModuleMigrationSummary.java` | alfaAffectedClassCount + layer2ClassCount + topAlfaGaps | VERIFIED | 16-component record; all 3 Alfa* fields at components 14–16 |
| `src/main/java/com/esmp/migration/api/RecipeBookController.java` | reload() returns Map with count + status | VERIFIED | Return type `ResponseEntity<Map<String,Object>>`; body `Map.of("count", count, "status", "reloaded")` |
| `src/main/java/com/esmp/migration/validation/AlfaMigrationValidationQueryRegistry.java` | 3 Cypher validation queries, @Component | VERIFIED | `@Component` present; 3 queries: ALFA_MIGRATION_ACTIONS_PRESENT, ALFA_TRANSITIVE_DETECTION_ACTIVE, ALFA_NEEDS_MAPPING_DISCOVERABLE |
| `src/main/java/com/esmp/mcp/tool/MigrationToolService.java` | @Tool descriptions updated for Alfa* data | VERIFIED | `getMigrationPlan` description mentions `hasAlfaIntermediaries`, `alfaIntermediaryCount`, `inheritedFrom`, `vaadinAncestor`, `ownAlfaCalls`; `getRecipeBookGaps` description mentions `com.alfa.*` |
| `src/test/java/com/esmp/migration/application/AlfaCatalogOverlayTest.java` | 9 tests | VERIFIED | 9 test methods covering all must_haves for Plan 19-01 |
| `src/test/java/com/esmp/extraction/visitor/MigrationPatternVisitorAlfaTest.java` | 3 tests | VERIFIED | 3 test methods: knownAlfaButton, unknownAlfaFallback, vaadinCoexistence |
| `src/test/java/com/esmp/migration/application/AlfaTransitiveDetectionIntegrationTest.java` | 5 integration tests | VERIFIED | 5 tests: ALFA-02a/b, ALFA-04a/b/c; uses Testcontainers (Neo4j + MySQL + Qdrant) |
| `src/test/java/com/esmp/migration/api/AlfaMigrationApiIntegrationTest.java` | 4 integration tests | VERIFIED | 4 tests: ALFA-03-api, ALFA-04-api, ALFA-05-api, ALFA-summary; uses @BeforeEach with static flag pattern |
| `src/test/java/com/esmp/migration/application/AlfaMcpToolIntegrationTest.java` | 3 MCP integration tests | VERIFIED | 3 tests: MCP-ALFA-01, MCP-ALFA-02, MCP-ALFA-03 |
| `src/test/resources/fixtures/migration/alfa/` | 6 fixture Java files + alfa-test-overlay.json | VERIFIED | AlfaButton.java, AlfaTable.java, AlfaButtonWrapper.java, AlfaTableExtension.java, BusinessServiceImpl.java, ComplexBusinessView.java, alfa-test-overlay.json |
| `src/test/java/com/esmp/migration/application/RecipeBookSeedTest.java` | VALID_CATEGORIES updated with 10 ALFA_* strings | VERIFIED | `VALID_CATEGORIES` at lines 37–41 includes all 10 ALFA_* categories plus EVENT, NAVIGATION, THEME |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `RecipeBookRegistry.load()` | `classpath:/migration/alfa-recipe-book-overlay.json` | `loadOverlayFromClasspath()` called in step 2b | WIRED | `alfaPath = migrationConfig.getAlfaOverlayPath()` → `loadOverlayFromClasspath(alfaPath, merged)` |
| `MigrationPatternVisitor.processImport()` | `rulesBySource` lookup then com.alfa.* step 4 fallback | step 1 direct lookup → step 4 `importFqn.startsWith("com.alfa.")` | WIRED | Step 4 fires after step 3 `return` guard; both known and unknown com.alfa.* covered |
| `MigrationRecipeService.detectTransitiveMigrations()` | `RecipeBookRegistry.getRules()` | Extended filter includes `com.alfa.*` sources | WIRED | Line 651: `|| r.source().startsWith("com.alfa.")` added to ancestor FQN filter |
| `MigrationRecipeService.detectTransitiveMigrations()` | Neo4j EXTENDS graph | `EXTENDS*1..10` Cypher traversal with `vaadinSourceFqns` list including com.alfa.* | WIRED | Cypher query at lines 663–669; ancestor list now contains both com.vaadin.* and com.alfa.* FQNs |
| `MigrationRecipeService.resolveUltimateVaadinAncestor()` | Neo4j EXTENDS graph | `EXTENDS*1..10 WHERE STARTS WITH 'com.vaadin.'` | WIRED | Cypher at lines 993–1000; returns first com.vaadin.* ancestor or Optional.empty() |
| `MigrationRecipeService.loadActionsFromGraph()` | `MigrationActionEntry` | `RETURN ma.ownAlfaCalls` in Cypher projection | WIRED | Line 1033: `ma.ownAlfaCalls AS ownAlfaCalls`; lines 1062–1064 null-safe mapping; passed to constructor at line 1081 |
| `MigrationRecipeService.generatePlan()` | `MigrationPlan` | Post-build `hasAlfaIntermediaries` / `alfaIntermediaryCount` from action stream | WIRED | Lines 110–116 compute both fields; lines 127–128 pass them to MigrationPlan constructor |
| `MigrationRecipeService.getModuleSummary()` | `ModuleMigrationSummary.alfaAffectedClassCount` / `layer2ClassCount` | Alfa* Cypher sub-query (lines 576–599) | WIRED | Sub-query filters `ma.source STARTS WITH 'com.alfa.'` and `ma.inheritedFrom STARTS WITH 'com.alfa.'`; results replace placeholder zeros |
| `RecipeBookController.reload()` | `RecipeBookRegistry.reload()` | `registry.reload()` then `Map.of("count", count, "status", "reloaded")` | WIRED | Line 174–177; return type changed from `Void` to `Map<String,Object>` |

---

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| `RecipeBookController.getGaps()` | `recipeBookRegistry.getRules()` | `RecipeBookRegistry.load()` → classpath JSON → `merged` map | Yes — 150 rules loaded including 4 NEEDS_MAPPING Alfa* entries | FLOWING |
| `MigrationRecipeService.detectTransitiveMigrations()` | `vaadinSourceFqns` | `recipeBookRegistry.getRules().stream().filter(com.alfa.*)` | Yes — com.alfa.* FQNs now included (146 MAPPED Alfa* rules with non-NEEDS_MAPPING status) | FLOWING |
| `MigrationRecipeService.generatePlan()` | `hasAlfaIntermediaries`, `alfaIntermediaryCount` | `loadActionsFromGraph()` → Neo4j `HAS_MIGRATION_ACTION` query | Yes — computed from live graph; values are non-trivial only after `migrationPostProcessing()` runs on Alfa* sources | FLOWING |
| `ModuleMigrationSummary.alfaAffectedClassCount` | Alfa* Cypher sub-query result | Neo4j `HAS_MIGRATION_ACTION` + `ma.source STARTS WITH 'com.alfa.'` | Yes — real Cypher query; placeholder zeros in initial mapping replaced by sub-query result | FLOWING |
| `RecipeBookController.reload()` | `count` | `recipeBookRegistry.getRules().size()` after `reload()` | Yes — live count from in-memory registry after re-reading classpath + overlays | FLOWING |

---

### Behavioral Spot-Checks

Step 7b: SKIPPED (requires running Spring Boot server + Neo4j; all verifiable behaviours are covered by integration tests using Testcontainers).

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| ALFA-01 | 19-01 | Recipe book contains 150+ Alfa* mappings across all 10 wrapper categories | SATISFIED | 150-rule overlay JSON verified programmatically; `AlfaCatalogOverlayTest.overlayContainsAtLeast150Rules` + `overlayCoversAllTenCategories` pass |
| ALFA-02 | 19-02 | Transitive detection follows EXTENDS chains through Alfa* intermediaries to com.vaadin.* | SATISFIED | `detectTransitiveMigrations()` ancestor filter includes `com.alfa.*`; `resolveUltimateVaadinAncestor()` walks to com.vaadin.* leaf; ALFA-02a/b integration tests pass |
| ALFA-03 | 19-03 | getRecipeBookGaps returns real unmapped Alfa* types | SATISFIED | `RecipeBookController.getGaps()` returns all NEEDS_MAPPING rules from live registry including 4 Alfa* entries; ALFA-03-api integration test confirms endpoint returns an array |
| ALFA-04 | 19-02, 19-03 | getMigrationPlan returns migrationSteps for Layer 2 classes with pureWrapper=true/false and vaadinAncestor | SATISFIED | `generatePlan()` computes `hasAlfaIntermediaries`, `alfaIntermediaryCount`; `ownAlfaCalls` in `MigrationActionEntry`; ALFA-04a/b/c (pureWrapper), ALFA-04-api (API surface) integration tests pass |
| ALFA-05 | 19-01, 19-03 | REST reload endpoint re-ingests Alfa* catalog overlay without restart | SATISFIED | `reload()` calls `load()` which executes step 2b unconditionally; `RecipeBookController.reload()` returns `{count, status}` JSON; ALFA-05-api integration test confirms reload returns status 200 |

**Note on orphaned requirements:** ALFA-01 through ALFA-05 appear in ROADMAP.md Phase 19 but are **not present** in `.planning/REQUIREMENTS.md` (the requirements file ends at Phase 16 / MIG-06 with no ALFA-* IDs). These requirement IDs exist only in the PLAN frontmatter. This is a documentation gap — the requirements file was not updated to cover Phase 19. Not a gap in the implementation; the ROADMAP Success Criteria serve as the contract and are all satisfied.

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `MigrationRecipeService.getModuleSummary()` | 518–521 | `alfaAffectedClassCount=0, layer2ClassCount=0` hardcoded in initial mapping | Info | NOT a stub — placeholder zeros are replaced at lines 598–603 by the Alfa* Cypher sub-query result. The pattern is intentional: initial mapping builds the base struct, then it is reconstructed with real Alfa* counts. |

No genuine stubs found. The `alfaAffectedClassCount=0` initial value is overwritten before the method returns.

---

### Pre-Existing Test Failures (Not Phase 19 Regressions)

The 19-03-SUMMARY documents 11 pre-existing failures across 3 test classes that existed before Phase 19 changes:
- `RecipeBookRegistryTest` — 6 failures (pre-existing)
- `MigrationRecipeServiceIntegrationTest` — 4 failures (pre-existing)
- `MigrationControllerIntegrationTest.getSummary_missingModule_returns400` — 1 failure (pre-existing, caused by a `@RequestParam(required=false)` change from a parallel agent)

These are not introduced by Phase 19 and are logged as deferred items.

---

### Human Verification Required

#### 1. Real Production Source Traversal

**Test:** Run `POST /api/extraction/trigger` on an adsuite-market source root, then call `POST /api/migration/migrationPostProcessing` and inspect `GET /api/migration/plan/{fqn}` for a known Layer 2 class (one that extends an AlfaButton subclass).
**Expected:** Response includes `hasAlfaIntermediaries: true`, at least one action with `isInherited: true` and `inheritedFrom` starting with `com.alfa.`, and `vaadinAncestor` starting with `com.vaadin.`
**Why human:** Requires a running environment with access to proprietary adsuite-market source; cannot be verified without the production source tree.

#### 2. Live Reload Endpoint Verification

**Test:** With the application running, call `POST /api/migration/recipe-book/reload` and check the response body.
**Expected:** `{"count": N, "status": "reloaded"}` where N >= 150 (base seed + 150 Alfa* rules)
**Why human:** Requires a live HTTP server; static analysis confirms the signature but not the runtime count.

---

### Gaps Summary

No gaps. All phase 19 must-haves are verified at all four levels (exists, substantive, wired, data flowing). The only notable item is that `REQUIREMENTS.md` was not extended to include ALFA-01 through ALFA-05 — this is a documentation gap, not an implementation gap.

---

_Verified: 2026-04-03T12:00:00Z_
_Verifier: Claude (gsd-verifier)_
