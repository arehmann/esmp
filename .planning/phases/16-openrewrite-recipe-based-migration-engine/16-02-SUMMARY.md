---
phase: 16-openrewrite-recipe-based-migration-engine
plan: 02
subsystem: migration
tags: [openrewrite, recipe, changetype, changepackage, vaadin7, migration, neo4j, integration-tests]

# Dependency graph
requires:
  - phase: 16-01
    provides: MigrationActionNode, HAS_MIGRATION_ACTION edges, ClassNode migration properties
  - phase: 02-ast-extraction
    provides: JavaSourceParser, ExtractionAccumulator
  - phase: 15-docker-deployment-enterprise-scale
    provides: SourceAccessService
provides:
  - MigrationRecipeService: loads migration actions from Neo4j, generates composite OpenRewrite recipes, executes in preview/apply mode
  - generatePlan: loads automatable vs. manual action split from Neo4j HAS_MIGRATION_ACTION edges
  - preview: executes composite recipe, returns unified diff and modified source without writing to disk
  - applyAndWrite: executes recipe and writes modified source back to the source file path
  - applyModule: batch-applies recipes to all automatable classes in a module
  - getModuleSummary: aggregates migration stats per module
  - MigrationValidationQueryRegistry: 3 validation queries (total: 44)
affects:
  - phase 16-03: MigrationController uses MigrationRecipeService for REST API endpoints

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "JVM classpath for ChangeType type resolution: JavaParser.fromJavaVersion().classpath(jvmJars) required for ChangeType to match unresolved Vaadin 7 types"
    - "CompositeRecipe wraps List<Recipe> for multi-action execution in a single RecipeRun"
    - "RecipeRun.getChangeset().getAllResults() returns List<Result>; Result.diff(Path.of('')) for unified diff; Result.getAfter().printAll() for modified source"
    - "InMemoryLargeSourceSet(List<SourceFile>) for in-memory recipe execution without file system output"
    - "slf4j-nop exclusion from rewrite-test dependency: rewrite-test ships NOP logger which conflicts with logback in Spring Boot test context"
    - "Fixture backup/restore pattern in applyModule test: reads all fixture files before batch apply, restores after test completes"

key-files:
  created:
    - src/main/java/com/esmp/migration/api/MigrationActionEntry.java
    - src/main/java/com/esmp/migration/api/MigrationPlan.java
    - src/main/java/com/esmp/migration/api/MigrationResult.java
    - src/main/java/com/esmp/migration/api/BatchMigrationResult.java
    - src/main/java/com/esmp/migration/api/ModuleMigrationSummary.java
    - src/main/java/com/esmp/migration/application/MigrationRecipeService.java
    - src/main/java/com/esmp/migration/validation/MigrationValidationQueryRegistry.java
    - src/test/java/com/esmp/migration/application/MigrationRecipeServiceIntegrationTest.java
    - src/test/resources/fixtures/migration/RecipeTargetView.java
  modified:
    - gradle/libs.versions.toml
    - build.gradle.kts

key-decisions:
  - "ChangeType requires JVM classpath for type resolution: org.openrewrite.java.ChangeType matches type usages by resolved JavaType, not textual FQN string — without a classpath, all Vaadin 7 types are JavaType.Unknown and ChangeType produces no changes. Solution: parse recipe-target files using the JVM classpath (System.getProperty('java.class.path')) which includes Vaadin 7 JARs in the Spring Boot application context"
  - "slf4j-nop exclusion from rewrite-test: org.openrewrite:rewrite-test:8.74.3 ships org.slf4j:slf4j-nop as a dependency, which conflicts with logback-classic in Spring Boot test context and causes 'LoggerFactory is not a Logback LoggerContext' IllegalStateException on context load. Fix: exclude(group='org.slf4j', module='slf4j-nop') in build.gradle.kts"
  - "Fixture backup/restore in applyModule test: applyModule writes to sourceFilePaths stored in Neo4j — which point to fixture files after path resolution. Test backs up all fixture file contents before calling applyModule and restores them in a finally block to prevent permanent fixture corruption"
  - "resolveSourcePath() uses SourceAccessService.getResolvedSourceRoot() for relative path resolution: extraction stores relative LST source paths (relative to the sourceRoot used during parsing); preview() resolves them against SourceAccessService to find files on disk"

patterns-established:
  - "Recipe service pattern: Neo4jClient loads actions, JavaParser parses source, CompositeRecipe wraps ChangeType/ChangePackage, InMemoryLargeSourceSet runs without disk writes"
  - "Integration test path fix: after extraction with fixtures dir, update sourceFilePath in Neo4j to absolute paths via Cypher SET to enable preview/apply testing"

requirements-completed:
  - MIG-03
  - MIG-04

# Metrics
duration: 35min
completed: 2026-03-28
---

# Phase 16 Plan 02: Recipe Engine Summary

**MigrationRecipeService loads migration actions from Neo4j HAS_MIGRATION_ACTION edges, builds composite OpenRewrite ChangeType/ChangePackage recipes, and executes them in preview mode (unified diff + modified source) or apply mode (writes to disk), with JVM classpath-based type resolution**

## Performance

- **Duration:** 35 min
- **Started:** 2026-03-28T13:49:15Z
- **Completed:** 2026-03-28T14:24:28Z
- **Tasks:** 2
- **Files modified:** 11 files (9 created, 2 modified)

## Accomplishments

- MigrationRecipeService with 5 public methods: generatePlan, preview, applyAndWrite, applyModule, getModuleSummary
- buildCompositeRecipe() maps CHANGE_TYPE → ChangeType(old, new, ignoreDefinition=true) and CHANGE_PACKAGE → ChangePackage(old, new, recursive=false) using CompositeRecipe
- preview() uses InMemoryLargeSourceSet + RecipeRun.getChangeset().getAllResults() + Result.diff(Path.of("")) + Result.getAfter().printAll()
- applyAndWrite() calls preview() then Files.writeString() to write transformed source
- applyModule() loads all classes with actions in a module, filters to automatable ones, calls applyAndWrite for each with error collection
- 5 API records: MigrationActionEntry, MigrationPlan, MigrationResult, BatchMigrationResult, ModuleMigrationSummary
- MigrationValidationQueryRegistry with 3 queries (MIGRATION_ACTIONS_POPULATED, MIGRATION_SCORES_COMPUTED, MIGRATION_ACTION_EDGES_INTACT) — total: 44
- 7 integration tests all passing

## Task Commits

1. **Task 1: MigrationRecipeService, API records, MigrationValidationQueryRegistry** — `4c98b35` (feat)
2. **Task 2: Integration tests and RecipeTargetView fixture** — `1aa78c8` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/migration/application/MigrationRecipeService.java` — 505 lines, recipe generation and execution engine
- `src/main/java/com/esmp/migration/api/MigrationActionEntry.java` — action entry record
- `src/main/java/com/esmp/migration/api/MigrationPlan.java` — migration plan response record
- `src/main/java/com/esmp/migration/api/MigrationResult.java` — recipe execution result with diff and modified source
- `src/main/java/com/esmp/migration/api/BatchMigrationResult.java` — batch module migration result
- `src/main/java/com/esmp/migration/api/ModuleMigrationSummary.java` — module aggregation record
- `src/main/java/com/esmp/migration/validation/MigrationValidationQueryRegistry.java` — 3 migration validation queries
- `src/test/java/com/esmp/migration/application/MigrationRecipeServiceIntegrationTest.java` — 7 integration tests
- `src/test/resources/fixtures/migration/RecipeTargetView.java` — Vaadin 7 TextField/Button/VerticalLayout fixture

## Decisions Made

- **ChangeType requires JVM classpath**: OpenRewrite ChangeType matches by resolved type, not textual FQN. Without classpath, Vaadin 7 types are JavaType.Unknown and no changes are produced. Fix: pass JVM classpath jars to JavaParser in parseWithJvmClasspath() helper.
- **slf4j-nop exclusion**: rewrite-test ships slf4j-nop which conflicts with logback in Spring Boot test context, causing IllegalStateException on context load. Fixed by excluding from openrewrite-testing dependency.
- **Fixture backup/restore**: applyModule writes to Neo4j-stored sourceFilePaths pointing to fixture files. Test backs up contents before running and restores in finally block.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ChangeType produces no changes without type resolution**
- **Found during:** Task 2 (MigrationRecipeServiceIntegrationTest preview tests)
- **Issue:** `ChangeType` recipe requires the Vaadin 7 types to be resolved in the LST (not `JavaType.Unknown`). Without passing the Vaadin 7 JAR to the parser, imports are stored as unresolved types and `ChangeType` produces no results.
- **Fix:** Added `parseWithJvmClasspath()` private helper in `MigrationRecipeService` that uses `System.getProperty("java.class.path")` to provide all JARs on the JVM classpath (including `vaadin-server-7.7.48.jar`) to `JavaParser.fromJavaVersion().classpath()`. The `javaSourceParser` field (via `JavaSourceParser`) is still used for other operations; recipe execution uses the new method.
- **Files modified:** `MigrationRecipeService.java`
- **Verification:** `preview_recipeTargetView_producesValidDiff` passes with Vaadin 24 imports in diff

**2. [Rule 1 - Bug] slf4j-nop classpath conflict from rewrite-test dependency**
- **Found during:** Task 2 (all integration test context loads failed)
- **Issue:** `org.openrewrite:rewrite-test:8.74.3` transitively depends on `org.slf4j:slf4j-nop:1.7.36`. When this is on the test classpath alongside logback, Spring Boot's LogbackLoggingSystem throws `IllegalStateException: LoggerFactory is not a Logback LoggerContext`.
- **Fix:** Added `exclude(group = "org.slf4j", module = "slf4j-nop")` to `testImplementation(libs.openrewrite.testing)` in `build.gradle.kts`.
- **Files modified:** `build.gradle.kts`
- **Verification:** All 7 integration tests pass with Spring context loading correctly

**3. [Rule 1 - Bug] Relative sourceFilePath resolution in preview()**
- **Found during:** Task 2 (preview tests returned noChanges because file not found on disk)
- **Issue:** Extraction stores relative LST source paths (e.g., `RecipeTargetView.java` relative to the fixtures dir used during parsing). `Path.of("RecipeTargetView.java")` doesn't exist from the current working directory.
- **Fix 1:** Added `resolveSourcePath()` helper that resolves against `SourceAccessService.getResolvedSourceRoot()`.
- **Fix 2:** Integration test `@BeforeEach` runs a Cypher SET to update relative paths to absolute paths in Neo4j after extraction.
- **Files modified:** `MigrationRecipeService.java`, `MigrationRecipeServiceIntegrationTest.java`

---

**Total deviations:** 3 auto-fixed (Rule 1 - Bug)
**Impact on plan:** All fixes were self-contained within the migration package. No scope creep. Core architecture matches plan specification.

## Self-Check: PASSED

All files verified present. All commits verified in git log.

---
*Phase: 16-openrewrite-recipe-based-migration-engine*
*Completed: 2026-03-28*
