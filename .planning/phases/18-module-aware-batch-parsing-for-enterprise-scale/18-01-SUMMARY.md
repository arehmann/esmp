---
phase: 18-module-aware-batch-parsing-for-enterprise-scale
plan: 01
subsystem: extraction
tags: [gradle, maven, module-detection, topological-sort, kahns-bfs, xml-parsing]

# Dependency graph
requires:
  - phase: 17-migration-recipe-book-transitive-detection
    provides: migration pattern detection foundation this phase extends for multi-module projects
provides:
  - ModuleDetectionService: detects Gradle/Maven modules, validates source/classpath dirs, builds inter-module dep graph
  - ModuleGraph: Kahn's BFS topological sort with cycle detection fallback, wave-grouped output
  - ModuleDescriptor record: per-module metadata (name, sourceDir, compiledClassesDir, dependsOn, javaFiles)
  - ModuleDetectionResult record: waves + skippedModules + buildSystem + totals
  - BuildSystem enum: GRADLE, MAVEN, NONE
affects:
  - 18-02: BatchParsingOrchestrator will consume ModuleDetectionResult.waves() for per-wave parsing
  - 18-03: classpathStrategy will use ModuleDescriptor.compiledClassesDir for per-module classpath construction

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Kahn's BFS topological sort with cycle detection (same pattern as SchedulingService.topoSort)
    - Regex-based Gradle build file parsing (GRADLE_INCLUDE_PATTERN, GRADLE_PROJECT_DEP_PATTERN)
    - DOM parsing via DocumentBuilderFactory for Maven pom.xml (no new library needed)

key-files:
  created:
    - src/main/java/com/esmp/extraction/module/BuildSystem.java
    - src/main/java/com/esmp/extraction/module/ModuleDescriptor.java
    - src/main/java/com/esmp/extraction/module/ModuleDetectionResult.java
    - src/main/java/com/esmp/extraction/module/ModuleGraph.java
    - src/main/java/com/esmp/extraction/module/ModuleDetectionService.java
    - src/test/java/com/esmp/extraction/module/ModuleDetectionServiceTest.java
    - src/test/java/com/esmp/extraction/module/ModuleGraphTest.java
    - src/test/resources/fixtures/modules/gradle-multi/settings.gradle
    - src/test/resources/fixtures/modules/gradle-multi/module-a/build.gradle
    - src/test/resources/fixtures/modules/gradle-multi/module-b/build.gradle
    - src/test/resources/fixtures/modules/gradle-multi/module-c/build.gradle
    - src/test/resources/fixtures/modules/maven-multi/pom.xml
    - src/test/resources/fixtures/modules/maven-multi/module-a/pom.xml
    - src/test/resources/fixtures/modules/maven-multi/module-b/pom.xml
  modified: []

key-decisions:
  - "ModuleGraph stores skippedModules separately from waves — skipped modules have no valid source so they cannot be in any wave"
  - "settings.gradle include parsing uses GRADLE_INCLUDE_PATTERN capturing group after optional colon/dot prefix — handles both ':module-a' and 'module-a' syntax verified against AdSuite real-world sample"
  - "Maven inter-module dep matching accepts ${project.groupId} and ${project.parent.groupId} placeholders in addition to literal parent groupId — covers all common Maven POM patterns"
  - "DocumentBuilderFactory.setNamespaceAware(false) used for Maven pom.xml parsing — namespace-aware parsing requires qualified tag names, simpler without for this use case"
  - "External entity resolution disabled on DocumentBuilderFactory for security (XXE prevention)"

patterns-established:
  - "ModuleDetectionService.detect() is the single entry point — callers never call detectGradle/detectMaven directly"
  - "All module validation (src/main/java + compiled classes) happens inside detect() — waves only contain valid, parseable modules"
  - "ModuleGraph.computeWaves() follows identical BFS structure to SchedulingService.topoSort() for consistency"

requirements-completed:
  - MODEX-01
  - MODEX-02
  - MODEX-05

# Metrics
duration: 4min
completed: 2026-03-29
---

# Phase 18 Plan 01: Module Detection Subsystem Summary

**Gradle/Maven multi-module detection with Kahn's BFS wave ordering, validated source/classpath directories, and cycle fallback — foundation for per-module batch parsing.**

## Performance

- **Duration:** ~4 min
- **Started:** 2026-03-29T11:21:46Z
- **Completed:** 2026-03-29T11:25:46Z
- **Tasks:** 1 (TDD: RED -> GREEN)
- **Files modified:** 14

## Accomplishments
- ModuleDetectionService parses Gradle settings.gradle (colon-prefixed and plain module names), extracts project() dependencies from build.gradle, and parses Maven pom.xml via DOM
- ModuleGraph implements Kahn's BFS topological sort producing wave-grouped output; cycles assigned to a final "cycle wave" rather than crashing
- Modules with missing src/main/java or compiled classes dir are skipped with descriptive reasons (not crashed)
- No-build-file fallback returns BuildSystem.NONE for single-module codebases
- 15 unit tests across 2 test classes — all green (9 ModuleDetectionServiceTest, 6 ModuleGraphTest)

## Task Commits

Each task was committed atomically:

1. **Task 1: Module detection model and services** - `c1999fd` (feat)

**Plan metadata:** (docs commit follows)

_Note: TDD task — RED phase confirmed compilation failures; GREEN phase all 15 tests pass_

## Files Created/Modified

- `src/main/java/com/esmp/extraction/module/BuildSystem.java` — GRADLE, MAVEN, NONE enum
- `src/main/java/com/esmp/extraction/module/ModuleDescriptor.java` — per-module metadata record
- `src/main/java/com/esmp/extraction/module/ModuleDetectionResult.java` — detection result record with SkippedModule inner record
- `src/main/java/com/esmp/extraction/module/ModuleGraph.java` — Kahn's BFS topological sort with cycle detection
- `src/main/java/com/esmp/extraction/module/ModuleDetectionService.java` — Gradle/Maven build file parsing service
- `src/test/java/com/esmp/extraction/module/ModuleDetectionServiceTest.java` — 9 unit tests
- `src/test/java/com/esmp/extraction/module/ModuleGraphTest.java` — 6 unit tests
- `src/test/resources/fixtures/modules/gradle-multi/` — Gradle multi-module test fixtures
- `src/test/resources/fixtures/modules/maven-multi/` — Maven multi-module test fixtures

## Decisions Made

- ModuleGraph stores skippedModules separately from waves — skipped modules have no valid source so they cannot appear in any wave
- settings.gradle include parsing uses a single regex that captures the part after any leading colon/dot prefix, handling both `:module-a` and `module-a` syntax
- Maven inter-module dep matching accepts `${project.groupId}` and `${project.parent.groupId}` placeholders in addition to literal parent groupId
- DocumentBuilderFactory with namespace-awareness disabled for Maven pom.xml DOM parsing — simpler and sufficient for this use case
- External entity resolution disabled on DocumentBuilderFactory for XXE prevention

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `ModuleDetectionResult.waves()` is ready for 18-02 BatchParsingOrchestrator to consume
- `ModuleDescriptor.compiledClassesDir` is ready for 18-03 per-module classpath construction
- All module detection tests green; no known blockers for next plan

---
*Phase: 18-module-aware-batch-parsing-for-enterprise-scale*
*Completed: 2026-03-29*
