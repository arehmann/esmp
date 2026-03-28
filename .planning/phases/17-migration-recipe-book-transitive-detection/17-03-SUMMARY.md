---
phase: 17-migration-recipe-book-transitive-detection
plan: 03
subsystem: migration-recipe-book
tags: [recipe-book, rest-api, mcp-tools, transitive-enrichment, coverage-metrics, validation]
dependency_graph:
  requires: [17-01-RecipeBookRegistry, 17-02-migrationPostProcessing]
  provides: [RecipeBookController, enriched-MigrationActionEntry, enriched-ModuleMigrationSummary, getRecipeBookGaps-MCP-tool]
  affects: [MigrationRecipeService, MigrationToolService, MigrationValidationQueryRegistry]
tech_stack:
  added: []
  patterns: [REST CRUD for JSON config, record extension with nullable transitive fields, MCP tool with RecipeBookRegistry injection]
key_files:
  created:
    - src/main/java/com/esmp/migration/api/RecipeBookController.java
    - src/test/java/com/esmp/migration/api/RecipeBookControllerIntegrationTest.java
  modified:
    - src/main/java/com/esmp/migration/api/MigrationActionEntry.java
    - src/main/java/com/esmp/migration/api/ModuleMigrationSummary.java
    - src/main/java/com/esmp/migration/application/MigrationRecipeService.java
    - src/main/java/com/esmp/mcp/tool/MigrationToolService.java
    - src/main/java/com/esmp/migration/validation/MigrationValidationQueryRegistry.java
    - src/test/java/com/esmp/migration/api/MigrationControllerIntegrationTest.java
decisions:
  - MigrationActionEntry.inheritedFrom and vaadinAncestor carry the same value — both mapped from ma.vaadinAncestor in Cypher; inheritedFrom is the semantic name for API consumers
  - coverageByType and coverageByUsage computed via 2 separate Cypher queries after the primary aggregation to avoid cartesian product complexity in a single query
  - topGaps computed from in-memory recipe book stream (not Neo4j) since NEEDS_MAPPING rules are a registry concern, not a graph concern
  - RecipeBookController.deleteRule returns 403 for isBase=true rules, 404 for missing, 204 for success — base rule protection is server-enforced
  - getRecipeBookGaps MCP tool has no parameters — always returns full NEEDS_MAPPING list sorted by usageCount; no pagination needed at current scale
metrics:
  duration: 8min
  completed_date: "2026-03-28T18:40:34Z"
  tasks_completed: 2
  files_changed: 8
---

# Phase 17 Plan 03: Recipe Book REST API, Enriched Records & MCP Tools Summary

Exposed the recipe book as a managed REST API (5 endpoints), extended MigrationActionEntry and ModuleMigrationSummary with transitive enrichment and coverage fields, added the getRecipeBookGaps MCP tool, extended validation queries from 44 to 47, and added 11 integration tests.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Extend API records, update MigrationRecipeService queries, create RecipeBookController, update MCP tools, extend validation | 5cb1c4b | RecipeBookController.java, MigrationActionEntry.java, ModuleMigrationSummary.java, MigrationRecipeService.java, MigrationToolService.java, MigrationValidationQueryRegistry.java |
| 2 | Integration tests for RecipeBookController and enriched MCP tools | 1d69d30 | RecipeBookControllerIntegrationTest.java, MigrationControllerIntegrationTest.java |

## What Was Built

### RecipeBookController (`com.esmp.migration.api`)

`@RestController @RequestMapping("/api/migration/recipe-book")` with 5 endpoints:

- `GET /api/migration/recipe-book?category=&status=&automatable=` — filterable rule list
- `GET /api/migration/recipe-book/gaps` — NEEDS_MAPPING rules sorted by usageCount desc
- `PUT /api/migration/recipe-book/rules/{id}` — create/replace custom rule (always isBase=false)
- `DELETE /api/migration/recipe-book/rules/{id}` — 403 for base rules, 404 if missing, 204 on success
- `POST /api/migration/recipe-book/reload` — re-reads recipe book from disk

### MigrationActionEntry — 13 fields (was 5)

New fields: `isInherited` (boolean), `inheritedFrom` (String, null for direct), `vaadinAncestor` (String, null for direct), `pureWrapper` (Boolean, null for direct), `transitiveComplexity` (Double, null for direct), `overrideCount` (Integer, null for direct), `ownVaadinCalls` (Integer, null for direct), `migrationSteps` (List<String>, never null).

`loadActionsFromGraph()` extended with new Cypher projections (COALESCE for isInherited) and post-query recipe book enrichment for migrationSteps.

### ModuleMigrationSummary — 13 fields (was 9)

New fields: `transitiveClassCount` (int), `coverageByType` (double, 0.0..1.0), `coverageByUsage` (double, 0.0..1.0), `topGaps` (List<String>, top-5 NEEDS_MAPPING sources).

`getModuleSummary()` now runs 3 Cypher queries: primary aggregation (with inheritedCount), coverage-by-type query, coverage-by-usage query. topGaps computed from registry stream.

### MigrationToolService — 10 tools (was 9)

New tool: `getRecipeBookGaps()` — returns NEEDS_MAPPING rules sorted by usageCount. `RecipeBookRegistry recipeBookRegistry` field added to constructor.

### MigrationValidationQueryRegistry — 6 queries (was 3, total 47)

New queries: `RECIPE_BOOK_LOADED` (WARNING), `TRANSITIVE_ACTIONS_DETECTED` (WARNING), `MIGRATION_COVERAGE_GAPS` (WARNING).

### Integration Tests

**RecipeBookControllerIntegrationTest** (8 tests, RB-05):
- RB-05-01: GET returns >= 80 rules
- RB-05-02: GET with category filter returns COMPONENT-only rules
- RB-05-03: GET gaps returns only NEEDS_MAPPING sorted desc
- RB-05-04: PUT creates custom rule with isBase=false
- RB-05-05: DELETE removes custom rule (204)
- RB-05-06: DELETE base rule returns 403
- RB-05-07: DELETE non-existent rule returns 404
- RB-05-08: POST reload returns 200, rules remain loaded

**MigrationControllerIntegrationTest** (3 new tests added to existing 7, RB-06):
- RB-06-01: getMigrationPlan returns isInherited=false and non-null migrationSteps for direct actions
- RB-06-02: getMigrationPlan returns isInherited=true, vaadinAncestor, pureWrapper for synthesized inherited actions
- RB-06-03: getModuleSummary returns transitiveClassCount, coverageByType (0..1), coverageByUsage (0..1), topGaps

## Deviations from Plan

None — plan executed exactly as written.

## Self-Check: PASSED

### Files verified to exist:
- `src/main/java/com/esmp/migration/api/RecipeBookController.java` — FOUND
- `src/main/java/com/esmp/migration/api/MigrationActionEntry.java` (13 fields) — FOUND
- `src/main/java/com/esmp/migration/api/ModuleMigrationSummary.java` (13 fields) — FOUND
- `src/test/java/com/esmp/migration/api/RecipeBookControllerIntegrationTest.java` — FOUND

### Commits verified:
- 5cb1c4b — feat(17-03): extend API records, RecipeBookController, MCP getRecipeBookGaps, 3 new validation queries
- 1d69d30 — feat(17-03): integration tests for RecipeBookController and enriched migration plan
