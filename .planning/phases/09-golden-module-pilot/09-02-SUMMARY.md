---
phase: 09-golden-module-pilot
plan: 02
subsystem: api
tags: [rest-api, integration-tests, testcontainers, neo4j, qdrant, spring-boot, pilot, vaadin7, golden-regression]

# Dependency graph
requires:
  - phase: 09-golden-module-pilot/09-01
    provides: PilotService, VectorSearchService, synthetic fixtures, PilotValidationReport, ModuleRecommendation, SearchRequest, SearchResponse records
  - phase: 08-smart-chunking-vector-indexing
    provides: VectorIndexingService, ChunkingService, QdrantCollectionInitializer
  - phase: 04-graph-validation-canonical-queries
    provides: ValidationService
provides:
  - PilotController with GET /api/pilot/recommend and GET /api/pilot/validate/{module}
  - VectorSearchController with POST /api/vector/search (400 on blank query)
  - PilotServiceIntegrationTest (8 tests): full pipeline golden regression suite for GMP-01 and GMP-03
  - VectorSearchIntegrationTest (5 tests): search relevance, module/stereotype filter, enrichment payload golden regression for GMP-02
affects: [10-rest-api-controllers, 11-rag-pipeline]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@GetMapping with {module:.+} regex in PilotController to prevent dot-truncation (Phase 3 convention)"
    - "POST /api/vector/search returns 400 ResponseEntity.badRequest() for blank query without throwing exception"
    - "Integration test one-time setup with static setUpDone flag + @BeforeEach guard (avoids @TestInstance(PER_CLASS) + @TempDir interaction issues)"
    - "Map.of() limit: use HashMap for more than 10 key-value pairs in Neo4jClient.bindAll() calls"

key-files:
  created:
    - src/main/java/com/esmp/pilot/api/PilotController.java
    - src/main/java/com/esmp/vector/api/VectorSearchController.java
    - src/test/java/com/esmp/pilot/application/PilotServiceIntegrationTest.java
    - src/test/java/com/esmp/vector/application/VectorSearchIntegrationTest.java
  modified: []

key-decisions:
  - "VectorSearchController validates blank query inline (returns 400) rather than delegating to VectorSearchService.search() to avoid exception propagation"
  - "Integration tests use static setUpDone flag + @BeforeEach guard instead of @BeforeAll + @TestInstance(PER_CLASS) to avoid Spring context startup ordering issues with @TempDir"
  - "PilotServiceIntegrationTest uses HashMap for createClassNode/Vaadin params (12 entries exceeds Map.of 10-entry limit)"
  - "Test assertions use 'anyMatch contains Invoice/Customer' not exact FQN match — resilient to package prefix in search results"

patterns-established:
  - "REST controllers in pilot/api and vector/api follow standard @RestController + @RequestMapping + constructor injection pattern"
  - "Integration test static flag pattern: private static boolean setUpDone + @BeforeEach guard for one-time Testcontainers setup"

requirements-completed: [GMP-01, GMP-02, GMP-03]

# Metrics
duration: 35min
completed: 2026-03-06
---

# Phase 9 Plan 02: REST Controllers and Integration Tests Summary

**PilotController (GET /api/pilot/recommend, GET /api/pilot/validate/{module}), VectorSearchController (POST /api/vector/search), PilotServiceIntegrationTest (8 tests), and VectorSearchIntegrationTest (5 tests) — all 13 integration tests pass on Neo4j + MySQL + Qdrant Testcontainers**

## Performance

- **Duration:** 35 min
- **Started:** 2026-03-06T09:00:00Z
- **Completed:** 2026-03-06T09:35:42Z
- **Tasks:** 1
- **Files modified:** 4

## Accomplishments

- `PilotController` exposes `GET /api/pilot/recommend` (returns `List<ModuleRecommendation>`) and `GET /api/pilot/validate/{module:.+}` (returns `PilotValidationReport`). Uses `{module:.+}` regex per Phase 3 dot-truncation prevention convention.
- `VectorSearchController` exposes `POST /api/vector/search` with inline blank-query 400 guard before delegating to `VectorSearchService`.
- `PilotServiceIntegrationTest` (8 tests): covers GMP-01 (`recommendModules_returnsPilotModule`, `validateModule_returnsCompleteReport`, `validateModule_allPilotChecksPass`, `validateModule_markdownReportGenerated`) and GMP-03 (`validateModule_riskScoresPopulated`, `validateModule_businessTermsCovered`, `validateModule_migrationReadinessInReport`) plus pipeline integrity (`fullPipeline_syntheticModuleIndexed`).
- `VectorSearchIntegrationTest` (5 tests): covers GMP-02 (`search_byServiceQuery_returnsServiceChunks`, `search_byRepositoryQuery_returnsRepoChunks`, `search_withModuleFilter_scopesToPilot`, `search_withStereotypeFilter_filtersCorrectly`, `search_resultsHaveEnrichmentPayloads`).
- Integration tests create 20 synthetic Neo4j nodes (PilotService test) and 10 nodes (VectorSearch test) with all required properties, then call `vectorIndexingService.indexAll()` to chunk and index into Qdrant before assertions.
- Golden regression assertions verify: chunk count > 0 for pilot module, enrichment fields (`classFqn`, `chunkType`, `module`, `score`) present in search results, risk scores populated in validation report, business term coverage > 0.

## Task Commits

1. **Task 1: PilotController, VectorSearchController, and integration tests** - `ecd17ef` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/pilot/api/PilotController.java` — `@RestController @RequestMapping("/api/pilot")` with `recommend()` and `validate(module)` endpoints
- `src/main/java/com/esmp/vector/api/VectorSearchController.java` — `@RestController @RequestMapping("/api/vector")` with `search(request)` endpoint
- `src/test/java/com/esmp/pilot/application/PilotServiceIntegrationTest.java` — 8 integration tests with 20-class synthetic Neo4j setup + full Qdrant indexing
- `src/test/java/com/esmp/vector/application/VectorSearchIntegrationTest.java` — 5 integration tests with 10-class subset + search assertions

## Decisions Made

- `VectorSearchController` validates blank query inline (returns 400) rather than delegating exception handling — cleaner HTTP semantics
- Integration tests use `static setUpDone` flag + `@BeforeEach` guard instead of `@BeforeAll` + `@TestInstance(PER_CLASS)` — `@TestInstance(PER_CLASS)` + `@TempDir` + Testcontainers caused Spring context startup ordering issues ("Mapped port can only be obtained after the container is started")
- `PilotServiceIntegrationTest.createClassNode()` uses `HashMap` instead of `Map.of()` because 12 parameters exceed the `Map.of()` 10-entry compile-time limit

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed Map.of() compile error — exceeded 10-entry limit**
- **Found during:** Task 1 (compilation)
- **Issue:** `createClassNode`, `createVaadinViewNode`, `createVaadinDataBindingNode` in PilotServiceIntegrationTest used `Map.of()` with 12 entries — Java's `Map.of()` only supports up to 10 typed key-value pairs
- **Fix:** Replaced all three calls with `HashMap` construction + `put()` calls
- **Files modified:** `PilotServiceIntegrationTest.java`
- **Commit:** ecd17ef (fixed before commit)

**2. [Rule 3 - Blocking] Resolved Spring context startup ordering issue with @TestInstance(PER_CLASS)**
- **Found during:** Task 1 (first test run)
- **Issue:** Using `@TestInstance(PER_CLASS)` + `@BeforeAll` + `@TempDir` caused "Mapped port can only be obtained after the container is started" because Spring context initialization ran before containers were ready
- **Fix:** Replaced `@TestInstance(PER_CLASS)` + `@BeforeAll` with default lifecycle + `@BeforeEach` guarded by `static boolean setUpDone` flag
- **Files modified:** `PilotServiceIntegrationTest.java`, `VectorSearchIntegrationTest.java`
- **Commit:** ecd17ef (fixed before commit)

## Issues Encountered

None beyond the two auto-fixed deviations above.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- All three pilot REST endpoints (`GET /api/pilot/recommend`, `GET /api/pilot/validate/{module}`, `POST /api/vector/search`) are wired and tested
- Phase 9 complete — full pipeline validated end-to-end on synthetic pilot module
- Phase 10 REST API controllers can build on the established controller pattern

---
*Phase: 09-golden-module-pilot*
*Completed: 2026-03-06*

## Self-Check: PASSED

All artifacts verified:
- FOUND: PilotController.java
- FOUND: VectorSearchController.java
- FOUND: PilotServiceIntegrationTest.java (8 tests, 0 failures)
- FOUND: VectorSearchIntegrationTest.java (5 tests, 0 failures)
- FOUND: 09-02-SUMMARY.md
- FOUND: commit ecd17ef (task 1)
- FOUND: commit 4b5bd16 (metadata)
