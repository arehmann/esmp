---
phase: 10-continuous-indexing
plan: 02
subsystem: indexing
tags: [rest-api, integration-tests, slo-validation, incremental-indexing, testcontainers]
dependency_graph:
  requires:
    - Phase 10 Plan 01: IncrementalIndexingService, FileHashUtil, IncrementalIndexRequest/Response
    - Phase 9: Testcontainers patterns (PilotServiceIntegrationTest)
    - Phase 8: VectorIndexingService, QdrantClient
    - Phase 3: ExtractionService, Neo4jClient patterns
  provides:
    - IndexingController (com.esmp.indexing.api) — POST /api/indexing/incremental
    - IncrementalIndexingServiceIntegrationTest (com.esmp.indexing.application)
    - 5 core fixture files (fixtures/incremental/)
    - 97 bulk stub fixtures (fixtures/incremental/bulk/)
  affects:
    - Phase 10 Plan 03: GitHub Actions workflow will call POST /api/indexing/incremental
tech_stack:
  added: []
  patterns:
    - Per-test @BeforeEach reset pattern (Neo4j DETACH DELETE + Qdrant clear) for test isolation
    - Raw Cypher MERGE via neo4jClient for shared node types (bypasses SDN @Version conflict)
    - Pre-delete-then-reinsert pattern for ClassNodes before re-extraction in incremental runs
    - Files.walk() scan in controller for full re-index path (sourceRoot only, no changedFiles)
key_files:
  created:
    - src/main/java/com/esmp/indexing/api/IndexingController.java
    - src/test/java/com/esmp/indexing/application/IncrementalIndexingServiceIntegrationTest.java
    - src/test/resources/fixtures/incremental/BaseService.java
    - src/test/resources/fixtures/incremental/BaseRepository.java
    - src/test/resources/fixtures/incremental/BaseEntity.java
    - src/test/resources/fixtures/incremental/ModifiedService.java
    - src/test/resources/fixtures/incremental/NewController.java
    - src/test/resources/fixtures/incremental/bulk/ (97 files: BulkEntity01-30, BulkService01-30, BulkRepo01-20, BulkUtil01-17)
  modified:
    - src/main/java/com/esmp/indexing/application/IncrementalIndexingService.java
decisions:
  - "Per-test @BeforeEach baseline reset chosen over shared static setUpDone flag because tests mutate conflicting state (deletion, hash modification, bulk load) — isolation requires clean Neo4j and Qdrant per test"
  - "Raw Cypher MERGE replaces SDN saveAll() for AnnotationNode/PackageNode/ModuleNode/DBTableNode in incremental re-extractions — avoids OptimisticLockingFailureException on @Version fields when nodes pre-exist from a prior extraction run"
  - "Pre-delete stale ClassNode step (Step 3b) added before extractAndPersistTransactional — ensures ClassNode is absent from Neo4j before SDN saveAll() so @Version constraint never fails for the class being re-extracted"
  - "IndexingController full re-index path: Files.walk(sourceRootPath).filter(*.java) mirrors ExtractionService scan pattern; controller builds changedFiles list and delegates to runIncremental — no new service method needed"
metrics:
  duration: 24 minutes
  completed_date: "2026-03-18"
  tasks_completed: 2
  files_changed: 106
---

# Phase 10 Plan 02: REST Controller, Integration Tests, and SLO Validation Summary

IndexingController REST endpoint with unified incremental/full-reindex path, 8 integration tests proving CI-01 through CI-03 and SLO-03/SLO-04, plus bug fixes for SDN @Version conflicts in incremental re-extraction.

## What Was Built

### IndexingController
`com.esmp.indexing.api.IndexingController` — `@RestController` at `/api/indexing`:

**`POST /api/indexing/incremental`**
- Accepts `IncrementalIndexRequest` with `changedFiles`, `deletedFiles`, `sourceRoot`, `classpathFile`
- **Full re-index path**: when `changedFiles` and `deletedFiles` are both empty, scans `sourceRoot` for all `.java` files via `Files.walk()` and passes them as `changedFiles` to `runIncremental()`
- Returns 400 if `sourceRoot` is blank/null and no files provided
- Synchronous execution; returns `IncrementalIndexResponse` with 200 on success or partial success
- Delegates all logic to `IncrementalIndexingService.runIncremental()`

### Fixture Files
**Core fixtures** (`src/test/resources/fixtures/incremental/`):
- `BaseService.java` — `@Service` calling `BaseRepository` with 3 methods (package `com.esmp.incremental`)
- `BaseRepository.java` — `@Repository` extending JpaRepository with custom `@Query`
- `BaseEntity.java` — `@Entity` with `@Table(name="base_entity")`, id and name fields
- `ModifiedService.java` — Same FQN as `BaseService` but with 2 additional methods (used to simulate file change)
- `NewController.java` — `@RestController` with 4 CRUD endpoints (used for "new file" test scenario)

**Bulk fixtures** (`src/test/resources/fixtures/incremental/bulk/`):
- 30 `BulkEntityNN.java` — minimal `@Entity` stubs with `@Table`
- 30 `BulkServiceNN.java` — minimal `@Service` stubs with 2 methods
- 20 `BulkRepoNN.java` — `@Repository` interfaces extending JpaRepository
- 17 `BulkUtilNN.java` — plain utility classes with 2 static methods
- **Total: 97 bulk files** (+ 3 core = 100 files for SLO-04)

### Integration Tests
`com.esmp.indexing.application.IncrementalIndexingServiceIntegrationTest` — 8 tests:

| Test | Requirement | Result |
|------|-------------|--------|
| `incrementalRun_extractsOnlyChangedFiles` | CI-01 | PASS |
| `unchangedFile_isSkipped` | CI-02 (hash skip) | PASS (classesSkipped=3) |
| `changedFile_updatesContentHashOnClassNode` | CI-02 (hash update) | PASS |
| `deletedFile_removesClassNodeFromNeo4j` | CI-02 (cascade delete) | PASS |
| `deletedFile_removesQdrantChunks` | CI-03 | PASS |
| `changedFile_updatesQdrantChunks` | CI-03 | PASS (chunksReEmbedded=7) |
| `incrementalRun_5files_completesUnder30Seconds` | SLO-03 | PASS (~2.4s) |
| `fullReindex_100classes_completesUnder5Minutes` | SLO-04 | PASS (~12.6s) |

**Test pattern**: Per-test `@BeforeEach` reset (Neo4j DETACH DELETE + Qdrant clear + 3-file baseline extraction), `@TempDir` for file staging, `Neo4jClient` for graph verification, `QdrantClient.scrollAsync()` with `matchKeyword("classFqn", ...)` filter for vector verification.

### Bug Fixes Applied to IncrementalIndexingService

**Step 3b (new)**: Pre-delete stale ClassNodes for changed files before `extractAndPersistTransactional`.
- Resolves FQNs for changed file paths via `resolveFqnsForPaths()`, then calls `deleteClassesTransactional()`
- Prevents `OptimisticLockingFailureException` when SDN's `@Version` check finds the ClassNode already exists with `version > 0`

**Cypher MERGE for shared nodes**: Replaced `annotationNodeRepository.saveAll()`, `packageNodeRepository.saveAll()`, `moduleNodeRepository.saveAll()`, `dbTableNodeRepository.saveAll()` with raw `neo4jClient` Cypher MERGE queries:
- `MERGE (a:JavaAnnotation {fullyQualifiedName: $fqn}) ON CREATE SET ... ON MATCH SET ...`
- `MERGE (p:JavaPackage {packageName: $name}) ON CREATE SET ... ON MATCH SET ...`
- `MERGE (m:JavaModule {moduleName: $name}) ON CREATE SET ... ON MATCH SET ...`
- `MERGE (t:DBTable {tableName: $name}) ON CREATE SET ... ON MATCH SET ...`
- These shared entity types may already exist from prior extractions with `@Version > 0`. SDN's `saveAll()` treats new transient instances as version=0, causing commit-phase `OptimisticLockingFailureException`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] SDN @Version OptimisticLockingFailureException on shared nodes**
- **Found during:** Task 2 test execution (CI-03, CI-02 hash test)
- **Issue:** `extractAndPersistTransactional` used SDN `saveAll()` for AnnotationNode, PackageNode, ModuleNode, DBTableNode. When these nodes pre-exist from a prior extraction run (version=1 in Neo4j), trying to MERGE a new transient instance (version=null) triggers commit-phase `OptimisticLockingFailureException`
- **Fix:** Replaced all 4 `saveAll()` calls with raw Cypher MERGE via `neo4jClient`. Also added Step 3b pre-delete for ClassNodes before re-extraction
- **Files modified:** `IncrementalIndexingService.java`
- **Commit:** ed38ea1

**2. [Rule 1 - Bug] SLO-04 test extracted 97 instead of 100 classes**
- **Found during:** Task 2 test execution (SLO-04)
- **Issue:** `@BeforeEach` baseline run stored hashes for 3 core fixtures in Neo4j. When SLO-04 copied same files to `bulkDir` with `bulkDir` as sourceRoot, relative paths matched stored hashes → 3 files skipped, only 97 extracted
- **Fix:** Added explicit `neo4jClient.query("MATCH (n) DETACH DELETE n")` + `clearQdrantCollection()` at start of SLO-04 test to remove stale hashes
- **Files modified:** `IncrementalIndexingServiceIntegrationTest.java`
- **Commit:** ed38ea1

**3. [Rule 1 - Bug] SLO-04 used static setUpDone but tests require isolation**
- **Found during:** Initial test design
- **Issue:** Plan specified static `setUpDone` pattern, but tests mutate conflicting state (deletion removes nodes, hash change requires different file content) — shared state would cause test ordering dependencies
- **Fix:** Changed to per-test `@BeforeEach` reset (full Neo4j clear + Qdrant clear + baseline extraction) for complete isolation
- **Files modified:** `IncrementalIndexingServiceIntegrationTest.java`
- **Commit:** ed38ea1

## Self-Check: PASSED

All created files exist on disk. Both task commits (b6ba5ae, ed38ea1) present in git history. `./gradlew test --tests "com.esmp.indexing.*"` shows 8/8 tests pass with BUILD SUCCESSFUL.
