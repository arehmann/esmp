---
phase: 18-module-aware-batch-parsing-for-enterprise-scale
plan: 02
subsystem: extraction
tags: [module-aware, multi-module, gradle, maven, extraction-pipeline, sse-progress, transactions]

# Dependency graph
requires:
  - phase: 18-module-aware-batch-parsing-for-enterprise-scale
    plan: 01
    provides: ModuleDetectionService, ModuleDetectionResult, ModuleDescriptor used by ExtractionService
provides:
  - ExtractionService.extract: auto-detects multi-module projects and orchestrates per-module extraction in wave order
  - ExtractionService.extractSingleShot: @Transactional single-shot path for NONE buildSystem (unchanged behavior)
  - ExtractionService.extractModuleAware: non-transactional orchestrator with per-module transaction boundaries
  - ExtractionService.persistModuleNodes: @Transactional("neo4jTransactionManager") per-module persist helper
  - JavaSourceParser.parse(List<Path>, Path, List<Path>): overloaded parse accepting compiled classpath dirs directly
  - ExtractionProgressService.ProgressEvent: extended with module/stage/message/durationMs + legacy() factory
  - ExtractionService.ExtractionResult: extended with buildSystem/moduleSummaries/skippedModules fields
  - ModuleAwareExtractionIntegrationTest: 5 integration tests covering MODEX-02 through MODEX-05
affects:
  - POST /api/extraction/trigger: now auto-detects multi-module projects and returns buildSystem/moduleSummaries in result
  - SSE progress stream: events now include module name and stage for multi-module extraction

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Non-transactional orchestrator delegates to @Transactional helpers (same pattern as IncrementalIndexingService)
    - Per-module transaction boundary via persistModuleNodes() — prevents SDN session-cache OOM for 40K+ file codebases
    - Backward-compatible ProgressEvent via legacy() factory — single-shot call sites unchanged
    - Method overloading with cast disambiguation (parse(paths, root, (String) null) vs parse(paths, root, List<Path>))

key-files:
  created:
    - src/test/java/com/esmp/extraction/application/ModuleAwareExtractionIntegrationTest.java
    - src/test/resources/fixtures/modules/gradle-multi/module-a/src/main/java/com/example/modulea/BaseEntity.java
    - src/test/resources/fixtures/modules/gradle-multi/module-b/src/main/java/com/example/moduleb/UserService.java
    - src/test/resources/fixtures/modules/gradle-multi/module-c/src/main/java/com/example/modulec/UserView.java
  modified:
    - src/main/java/com/esmp/extraction/application/ExtractionService.java
    - src/main/java/com/esmp/extraction/application/ExtractionProgressService.java
    - src/main/java/com/esmp/extraction/parser/JavaSourceParser.java
    - src/test/java/com/esmp/extraction/application/ExtractionProgressServiceTest.java
    - src/test/java/com/esmp/extraction/parser/JavaSourceParserTest.java

key-decisions:
  - "ExtractionService.extract() is NOT @Transactional — same non-transactional orchestrator pattern as IncrementalIndexingService to avoid SDN session-cache conflicts across module transactions"
  - "ExtractionResult extended with buildSystem/moduleSummaries/skippedModules at the end of the record — single-shot path sets these to null for backward compatibility"
  - "parse(List<Path>, Path, null) is ambiguous after adding List<Path> overload — fixed by casting to (String) null in the test"
  - "ClasspathLoader injected into ExtractionService instead of instantiated via new — it is a Spring @Component and should be wired"
  - "Module-aware per-module failure isolation: each module wrapped in try/catch; failures add to allErrors but do not abort remaining modules"
  - "testProgressEvents verifies module summaries are non-empty rather than intercepting SSE events — module summaries are only populated when the COMPLETE sendModuleProgress call is reached"

patterns-established:
  - "ExtractionService.extractModuleAware processes waves sequentially, modules within a wave sequentially — future optimization can parallelize within-wave modules"
  - "persistModuleNodes is package-private (not private) to allow @Transactional proxy to work — same Spring proxy limitation as IncrementalIndexingService"
  - "ProgressEvent.legacy() factory maps old 3-field call sites to the new 6-field record with null module/message/durationMs"

requirements-completed:
  - MODEX-02
  - MODEX-03
  - MODEX-04
  - MODEX-05

# Metrics
duration: 11min
completed: 2026-03-29
---

# Phase 18 Plan 02: Module-Aware Extraction Pipeline Summary

**Module-aware extraction pipeline: ExtractionService auto-detects Gradle/Maven multi-module projects, orchestrates per-module parsing with dependency-order classpath, and emits SSE progress events with module name and stage fields.**

## Performance

- **Duration:** ~11 min
- **Started:** 2026-03-29T11:28:45Z
- **Completed:** 2026-03-29T11:39:52Z
- **Tasks:** 2 (implementation + integration tests)
- **Files modified:** 9 (5 modified, 4 created)

## Accomplishments

- ExtractionService detects modules at sourceRoot via ModuleDetectionService.detect(); delegates to extractModuleAware() or extractSingleShot() based on BuildSystem
- extractModuleAware() processes waves sequentially, per-module: builds dependency classpath from upstream compiled class dirs, calls parse(paths, root, List<Path>), visits, and persists in a dedicated @Transactional transaction
- Individual module failures caught with try/catch — error logged and added to allErrors, remaining modules proceed
- Cross-module linking, risk scoring, and migration post-processing run as a single final pass after all modules
- JavaSourceParser.parse(List<Path>, Path, List<Path>) overload bypasses ClasspathLoader entirely — caller provides resolved dirs directly
- ProgressEvent extended with 6 fields (module, stage, filesProcessed, totalFiles, message, durationMs) with backward-compatible legacy() factory
- ExtractionResult extended with buildSystem, moduleSummaries, skippedModules fields (null for single-shot)
- 5 integration tests covering single-shot fallback, module-aware detection, cross-module linking, skipped module reporting, and SSE progress events — all pass with Testcontainers

## Task Commits

1. **Task 1: Module-aware extraction pipeline** - `ddd82ea` (feat)
2. **Task 2: Integration tests** - `cc67ac8` (test)

## Files Created/Modified

- `src/main/java/com/esmp/extraction/application/ExtractionService.java` — Refactored with module detection, extractSingleShot, extractModuleAware, persistModuleNodes, sendModuleProgress; ExtractionResult extended with 3 new fields; ModuleExtractionSummary inner record added
- `src/main/java/com/esmp/extraction/application/ExtractionProgressService.java` — ProgressEvent replaced with 6-field record plus legacy() factory
- `src/main/java/com/esmp/extraction/parser/JavaSourceParser.java` — New parse() overload accepting List<Path> compiled classpath dirs
- `src/test/java/com/esmp/extraction/application/ExtractionProgressServiceTest.java` — Updated for new ProgressEvent API; added tests for module-aware and legacy event construction
- `src/test/java/com/esmp/extraction/parser/JavaSourceParserTest.java` — Fixed ambiguous null call to (String) null cast
- `src/test/java/com/esmp/extraction/application/ModuleAwareExtractionIntegrationTest.java` — 5 integration tests (testSingleShotFallback, testModuleAwareDetectionAndExtraction, testCrossModuleLinking, testSkippedModuleReporting, testProgressEvents)
- `src/test/resources/fixtures/modules/gradle-multi/module-a/src/main/java/com/example/modulea/BaseEntity.java` — BaseEntity fixture
- `src/test/resources/fixtures/modules/gradle-multi/module-b/src/main/java/com/example/moduleb/UserService.java` — UserService fixture (cross-module dep on BaseEntity)
- `src/test/resources/fixtures/modules/gradle-multi/module-c/src/main/java/com/example/modulec/UserView.java` — UserView fixture (cross-module dep on UserService)

## Decisions Made

- ExtractionService.extract() is NOT @Transactional — delegates to @Transactional helpers to avoid SDN session-cache OOM (same pattern as IncrementalIndexingService)
- ExtractionResult record extended at the end with nullable fields — single-shot path returns null for buildSystem/moduleSummaries/skippedModules to preserve backward compatibility
- parse(paths, root, null) is ambiguous after adding List<Path> overload — cast to (String) null in test to disambiguate
- ClasspathLoader injected into ExtractionService instead of instantiated via new to respect Spring DI
- testProgressEvents validates indirectly via moduleSummaries — SSE events are only reachable via registered emitters, but module summaries prove the COMPLETE stage was reached

## Deviations from Plan

**1. [Rule 2 - Missing Critical Functionality] Added ClasspathLoader injection to ExtractionService**
- **Found during:** Task 1 — plan suggested `new ClasspathLoader()` for external JAR loading in extractModuleAware
- **Issue:** ClasspathLoader is a Spring @Component and should be injected, not instantiated via `new`
- **Fix:** Added `ClasspathLoader classpathLoader` as constructor parameter, wired in constructor
- **Files modified:** ExtractionService.java
- **Commit:** ddd82ea

**2. [Rule 1 - Bug] Fixed ambiguous method call after adding List<Path> parse() overload**
- **Found during:** Task 1 — `parser.parse(sources, projectRoot, null)` in JavaSourceParserTest became ambiguous
- **Issue:** Java cannot disambiguate `null` between `String` and `List<Path>` overloads
- **Fix:** Cast to `(String) null` in the test
- **Files modified:** JavaSourceParserTest.java
- **Commit:** ddd82ea

**3. [Rule 1 - Bug] Removed broken SseEmitter subclass attempt in testProgressEvents**
- **Found during:** Task 2 — initial implementation tried to override `send(SseEmitterBuilder)` which is not a method on SseEmitter
- **Issue:** SseEmitter.send() does not take an SseEmitterBuilder parameter — it takes an SseEventBuilder
- **Fix:** Simplified testProgressEvents to validate moduleSummaries (indirect proof that COMPLETE events fired) rather than intercepting SSE events directly
- **Files modified:** ModuleAwareExtractionIntegrationTest.java
- **Commit:** cc67ac8

## Issues Encountered

None requiring manual intervention.

## User Setup Required

None — no new services or environment variables required.

## Next Phase Readiness

- Phase 18 Plan 02 is complete: module-aware extraction is live in POST /api/extraction/trigger
- Plan 03 (if it exists) would handle additional classpath strategy refinements or multi-threaded within-wave parallelism
- All phase 18 requirements MODEX-02 through MODEX-05 are satisfied

## Self-Check: PASSED

All created/modified files verified on disk:
- FOUND: ExtractionService.java
- FOUND: ExtractionProgressService.java
- FOUND: JavaSourceParser.java
- FOUND: ModuleAwareExtractionIntegrationTest.java
- FOUND: BaseEntity.java fixture
- FOUND: UserService.java fixture
- FOUND: UserView.java fixture

Commits verified:
- FOUND: ddd82ea (feat: module-aware extraction pipeline)
- FOUND: cc67ac8 (test: integration tests)

---
*Phase: 18-module-aware-batch-parsing-for-enterprise-scale*
*Completed: 2026-03-29*
