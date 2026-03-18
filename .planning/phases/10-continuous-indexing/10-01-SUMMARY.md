---
phase: 10-continuous-indexing
plan: 01
subsystem: indexing
tags: [incremental-indexing, hash-filter, vector-pipeline, extraction]
dependency_graph:
  requires:
    - Phase 8: VectorIndexingService, ChunkingService, CodeChunk model
    - Phase 6: RiskService
    - Phase 5: LinkingService, BusinessTermNode
    - Phase 3: ExtractionService internals (parser, visitors, mapper, repositories)
  provides:
    - IncrementalIndexingService (com.esmp.indexing.application)
    - FileHashUtil (com.esmp.indexing.util)
    - IncrementalIndexRequest/Response (com.esmp.indexing.api)
    - ChunkingService.chunkByFqns overload (com.esmp.vector.application)
  affects:
    - Phase 10 Plan 02 (REST API controller will expose IncrementalIndexingService)
    - Phase 10 Plan 03 (GitHub Actions workflow will call the REST API)
tech_stack:
  added: []
  patterns:
    - SHA-256 file hashing via java.security.MessageDigest + java.util.HexFormat
    - Separate @Transactional("neo4jTransactionManager") methods for delete and persist (avoids SDN session-cache conflicts)
    - Hash-filter pattern: compare SHA-256 on disk vs. stored contentHash in Neo4j before extraction
    - Selective vector chunking: chunkByFqns() limits Neo4j query to specific FQNs (SLO-03)
key_files:
  created:
    - src/main/java/com/esmp/indexing/util/FileHashUtil.java
    - src/main/java/com/esmp/indexing/api/IncrementalIndexRequest.java
    - src/main/java/com/esmp/indexing/api/IncrementalIndexResponse.java
    - src/main/java/com/esmp/indexing/application/IncrementalIndexingService.java
  modified:
    - src/main/java/com/esmp/vector/application/ChunkingService.java
decisions:
  - "Separate @Transactional methods for delete and extract-persist steps ensure delete commits before persist starts, avoiding SDN session-cache version conflicts (Research Pitfall 1)"
  - "runIncremental() is deliberately NOT @Transactional — Spring's default propagation ensures each callee's @Transactional creates and commits its own transaction"
  - "FileHashUtil uses java.util.HexFormat (JDK 17+ built-in) for clean hex formatting without manual %02x loops"
  - "chunkByFqns mirrors queryAllClasses() Cypher but adds WHERE c.fullyQualifiedName IN $fqns — enables SLO-03 selective re-embedding"
  - "resolveFqnsForPaths() and fetchStoredHashes() use Neo4j batch queries (IN $paths) rather than per-file queries to minimize round-trips"
  - "Qdrant deleteByClass failure is non-fatal — orphaned points are harmless and will be overwritten on next upsert"
metrics:
  duration: 5 minutes
  completed_date: "2026-03-18"
  tasks_completed: 2
  files_changed: 5
---

# Phase 10 Plan 01: Incremental Indexing Service Summary

SHA-256 hash-filtered incremental pipeline that deletes, re-extracts, re-links, re-risks, and selectively re-embeds only changed Java source files.

## What Was Built

### FileHashUtil
`com.esmp.indexing.util.FileHashUtil` — static utility with:
- `sha256(Path)`: computes lowercase hex SHA-256 using `MessageDigest` + `HexFormat.of().formatHex()`
- `relativize(Path, Path)`: converts CI-supplied absolute paths to Neo4j-stored relative paths (e.g., `com/example/Foo.java`)

### Request/Response Records
- `IncrementalIndexRequest`: record with `changedFiles`, `deletedFiles`, `sourceRoot`, `classpathFile` — null-safe compact constructor
- `IncrementalIndexResponse`: record with 9 count fields (`classesExtracted`, `classesDeleted`, `classesSkipped`, `nodesCreated`, `nodesUpdated`, `edgesLinked`, `chunksReEmbedded`, `chunksDeleted`, `durationMs`) plus `errors` list

### ChunkingService FQN Overload
New `chunkByFqns(List<String> fqns, String sourceRoot)` method and `queryClassesByFqns(List<String>)` private helper:
- Mirrors `chunkClasses()` and `queryAllClasses()` exactly, but adds `WHERE c.fullyQualifiedName IN $fqns` to the Cypher query
- SLO-03 optimization: only changed classes are chunked, not all classes

### IncrementalIndexingService
`com.esmp.indexing.application.IncrementalIndexingService` — 8-step orchestrator at 703 lines:

| Step | Method | Transaction |
|------|--------|-------------|
| 1 | Validate sourceRoot | none |
| 2 | `deleteClassesTransactional(fqns)` | `@Transactional("neo4jTransactionManager")` |
| 2b | `vectorIndexingService.deleteByClass(fqn)` | none (Qdrant) |
| 3 | Hash filter via `FileHashUtil.sha256()` + Neo4j batch fetch | none |
| 4 | `extractAndPersistTransactional(...)` | `@Transactional("neo4jTransactionManager")` |
| 5 | `linkingService.linkAllRelationships(accumulator)` | (LinkingService's own TX) |
| 6 | `riskService.computeAndPersistRiskScores()` | (RiskService's own TX) |
| 7 | `chunkByFqns` + `embedAndUpsert` per batch | none (Qdrant) |
| 8 | Build `IncrementalIndexResponse` | none |

**Key behaviors:**
- `runIncremental()` has no `@Transactional` — each step manages its own transaction
- Delete commits before extract starts (separate TX boundary)
- Hash filter skips unchanged files (`classesSkipped` counter)
- `contentHash` is injected into each ClassNode before `saveAll()` so the next incremental run can skip unchanged files
- Business terms use curated-guard MERGE (mirrors `ExtractionService.persistBusinessTermNodes()`)
- Linking and risk always run, even if no files changed (deletion may invalidate edges)
- Each step wraps errors in try-catch; errors accumulate in response, processing continues

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Missing import] HexFormat required explicit import**
- **Found during:** Task 1 compilation
- **Issue:** `HexFormat` is in `java.util` package but requires explicit import (not auto-imported)
- **Fix:** Added `import java.util.HexFormat;` to FileHashUtil.java
- **Files modified:** `FileHashUtil.java`
- **Commit:** b7891a1

**2. [Rule 1 - Bug] LinkingResult field names differ from plan spec**
- **Found during:** Task 2 compilation (9 errors)
- **Issue:** Plan spec referenced `lr.calls()`, `lr.extends_()`, etc. but actual `LinkingResult` fields are `extendsCount()`, `dependsOnCount()`, `mapsToTableCount()`, `queriesCount()`, `hasAnnotationCount()`, `containsHierarchyCount()`, `bindsToCount()`, `usesTermCount()`, `definesRuleCount()`
- **Fix:** Updated edge count aggregation in `runIncremental()` to use correct field names
- **Files modified:** `IncrementalIndexingService.java`
- **Commit:** de48c73

## Self-Check: PASSED

All 4 created files exist on disk. Both task commits (b7891a1, de48c73) present in git history. `./gradlew compileJava` passes with BUILD SUCCESSFUL.
