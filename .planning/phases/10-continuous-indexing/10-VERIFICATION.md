---
phase: 10-continuous-indexing
verified: 2026-03-18T00:00:00Z
status: passed
score: 13/13 must-haves verified
re_verification: false
gaps: []
human_verification:
  - test: "SLO-03 wall-clock timing under load"
    expected: "5-file incremental run completes in under 30 seconds in a real CI environment (not just Testcontainers)"
    why_human: "Integration test asserts durationMs < 30_000 within Testcontainers, but actual CI performance depends on hardware, ONNX model warm-up time, and network latency to Qdrant. Cannot measure real-world CI performance programmatically."
  - test: "SLO-04 wall-clock timing at scale"
    expected: "100-class full re-index completes in under 5 minutes in production"
    why_human: "Integration test asserts durationMs < 300_000 within Testcontainers (observed ~12.6s per SUMMARY). Real production with large classpath resolution, cold ONNX model, and remote Qdrant may vary. Marked @Tag(\"slow\") — must be confirmed in a representative environment."
---

# Phase 10: Continuous Indexing Verification Report

**Phase Goal:** As the legacy codebase undergoes active development, the knowledge graph and vector store stay current without requiring manual re-runs.
**Verified:** 2026-03-18
**Status:** passed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Caller receives classesSkipped > 0 when submitting unchanged files a second time | VERIFIED | `unchangedFile_isSkipped` test asserts `response.classesSkipped() == 3`; `fetchStoredHashes()` + SHA-256 comparison in `runIncremental()` lines 251-268 |
| 2 | Neo4j contains no JavaClass node for a FQN that appeared in deletedFiles | VERIFIED | `deleteClassesTransactional()` executes DETACH DELETE Cypher; `deletedFile_removesClassNodeFromNeo4j` test asserts afterCount == 0 |
| 3 | Qdrant contains no points for a classFqn that appeared in deletedFiles | VERIFIED | `vectorIndexingService.deleteByClass(fqn)` called per deleted FQN (line 207); `deletedFile_removesQdrantChunks` test asserts countQdrantChunks == 0 |
| 4 | Neo4j ClassNode.contentHash matches SHA-256 of the source file after extraction | VERIFIED | `classNode.setContentHash(fileHashMap.get(relativePath))` injected before saveAll (lines 479-487); `changedFile_updatesContentHashOnClassNode` verifies hash equality |
| 5 | ChunkingService.chunkByFqns returns chunks only for the specified FQN list, not all classes | VERIFIED | `queryClassesByFqns()` adds `WHERE c.fullyQualifiedName IN $fqns` to Cypher (ChunkingService.java line 314); confirmed at line 189 |
| 6 | POST /api/indexing/incremental with changedFiles triggers extraction of only those files | VERIFIED | IndexingController delegates to `incrementalIndexingService.runIncremental(request)` (line 101); hash-filter skips unchanged; `incrementalRun_extractsOnlyChangedFiles` test asserts >= 3 nodes |
| 7 | Deleted class FQN is removed from Neo4j graph including child nodes and all edges | VERIFIED | DETACH DELETE with OPTIONAL MATCH on JavaMethod + JavaField child nodes (lines 410-415) |
| 8 | Deleted class chunks are removed from Qdrant | VERIFIED | `vectorIndexingService.deleteByClass(fqn)` for each deleted FQN (line 207) |
| 9 | Unchanged files (hash match) are skipped on second run | VERIFIED | Batch hash fetch + SHA-256 comparison; skipped++ counter; confirmed by test |
| 10 | Changed files get updated contentHash in Neo4j | VERIFIED | fileHashMap injected into ClassNode before saveAll; test confirms hash before != hash after |
| 11 | Changed file chunks are re-embedded in Qdrant with updated payloads | VERIFIED | Step 7: deleteByClass + chunkByFqns + embedAndUpsert (lines 346-378); `changedFile_updatesQdrantChunks` verifies contentHash payload in returned points |
| 12 | Incremental run of 5 changed files completes in under 30 seconds | VERIFIED (in Testcontainers) | `incrementalRun_5files_completesUnder30Seconds` asserts durationMs < 30_000; SUMMARY reports ~2.4s; human verification recommended for production |
| 13 | Full re-index of 100 classes completes in under 5 minutes | VERIFIED (in Testcontainers) | `fullReindex_100classes_completesUnder5Minutes` asserts durationMs < 300_000 and classesExtracted >= 100; SUMMARY reports ~12.6s; human verification recommended |

**Score:** 13/13 truths verified

---

## Required Artifacts

### Plan 01 Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/indexing/application/IncrementalIndexingService.java` | Incremental pipeline orchestrator, min 150 lines | VERIFIED | 779 lines; full 8-step pipeline with separate @Transactional methods for delete and persist |
| `src/main/java/com/esmp/indexing/util/FileHashUtil.java` | SHA-256 file hashing utility, exports sha256 | VERIFIED | 56 lines; `sha256(Path)` + `relativize(Path, Path)` static methods present |
| `src/main/java/com/esmp/indexing/api/IncrementalIndexRequest.java` | Record with changedFiles, deletedFiles, sourceRoot, classpathFile | VERIFIED | Null-safe compact constructor; all 4 fields present |
| `src/main/java/com/esmp/indexing/api/IncrementalIndexResponse.java` | Record with counts and durationMs | VERIFIED | 9 count fields + errors list; compact constructor for null-safety |
| `src/main/java/com/esmp/vector/application/ChunkingService.java` | New chunkByFqns(List<String>, String) overload | VERIFIED | Method at line 183; `queryClassesByFqns()` private helper with FQN filter at line 314 |

### Plan 02 Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/indexing/api/IndexingController.java` | POST /api/indexing/incremental REST endpoint, exports incrementalIndex | VERIFIED | 104 lines; `@PostMapping("/incremental")` at line 56; full re-index path via Files.walk(); 400 for blank sourceRoot |
| `src/test/java/com/esmp/indexing/application/IncrementalIndexingServiceIntegrationTest.java` | Integration tests for CI-01 through SLO-04, min 250 lines | VERIFIED | 530 lines; 8 test methods covering all 5 requirement IDs; Testcontainers (Neo4j + MySQL + Qdrant) |
| `src/test/resources/fixtures/incremental/` | Core fixture files for incremental tests | VERIFIED | 5 files: BaseService.java, BaseRepository.java, BaseEntity.java, ModifiedService.java, NewController.java |
| `src/test/resources/fixtures/incremental/bulk/` | ~97 minimal Java stub fixtures for SLO-04 | VERIFIED | 97 files: BulkEntity01-30 (30), BulkService01-30 (30), BulkRepo01-20 (20), BulkUtil01-17 (17) |

---

## Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| IncrementalIndexingService | ExtractionService internals | `javaSourceParser.parse()` + `classNodeRepository.saveAll()` | WIRED | Lines 442, 501 in extractAndPersistTransactional |
| IncrementalIndexingService | LinkingService + RiskService | `linkingService.linkAllRelationships()` + `riskService.computeAndPersistRiskScores()` | WIRED | Lines 319, 332 in runIncremental Step 5 + 6 |
| IncrementalIndexingService | VectorIndexingService + ChunkingService | `vectorIndexingService.deleteByClass()` + `chunkingService.chunkByFqns()` | WIRED | Lines 207, 349, 358 in runIncremental Step 2b + 7 |
| IndexingController | IncrementalIndexingService | `incrementalIndexingService.runIncremental(request)` | WIRED | IndexingController.java line 101 |
| IncrementalIndexingServiceIntegrationTest | Full pipeline (Neo4j + Qdrant + ONNX) | `@SpringBootTest(webEnvironment = WebEnvironment.MOCK)` + Testcontainers | WIRED | Lines 57-79; all 3 containers declared and registered via @DynamicPropertySource |

---

## Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| CI-01 | 10-01, 10-02 | CI hook re-extracts changed files on each build | SATISFIED | Hash-filter skips unchanged; only trulyChangedPaths passed to parser; `incrementalRun_extractsOnlyChangedFiles` test passes |
| CI-02 | 10-01, 10-02 | Graph nodes and edges update incrementally (not full rebuild) | SATISFIED | Separate delete + extract-persist TXs; SHA-256 skip; cascade DETACH DELETE; `unchangedFile_isSkipped`, `changedFile_updatesContentHashOnClassNode`, `deletedFile_removesClassNodeFromNeo4j` tests pass |
| CI-03 | 10-01, 10-02 | Vector embeddings update incrementally for changed chunks | SATISFIED | Step 7 deletes old Qdrant points then re-embeds via chunkByFqns; `deletedFile_removesQdrantChunks`, `changedFile_updatesQdrantChunks` tests pass |
| SLO-03 | 10-01, 10-02 | Incremental re-index of 5 changed files completes in under 30 seconds | SATISFIED (test) | `incrementalRun_5files_completesUnder30Seconds` asserts durationMs < 30_000; SLO-03 optimization via chunkByFqns (selective chunking only changed classes) |
| SLO-04 | 10-01, 10-02 | Full re-index of 100-class module completes in under 5 minutes | SATISFIED (test) | `fullReindex_100classes_completesUnder5Minutes` @Tag("slow") asserts durationMs < 300_000 with >= 100 classes extracted |

No orphaned requirements found — all 5 Phase 10 requirement IDs (CI-01, CI-02, CI-03, SLO-03, SLO-04) are claimed by both plans and covered by implementation.

---

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| — | — | None found | — | — |

No TODOs, FIXMEs, placeholder returns, or stub implementations found in any Phase 10 source files.

---

## Human Verification Required

### 1. SLO-03 Real CI Performance

**Test:** Run POST /api/indexing/incremental with 5 changed files in your actual CI environment (GitHub Actions or equivalent) against a running application with warm JVM and pre-loaded ONNX model.
**Expected:** Response durationMs < 30,000 (30 seconds).
**Why human:** Integration test passes (~2.4s in Testcontainers per SUMMARY), but Testcontainers runs on developer hardware with embedded services. Real CI introduces cold starts, ONNX model load time (~1-3s for all-MiniLM-L6-v2), and network latency to remote Qdrant that cannot be measured programmatically here.

### 2. SLO-04 Real Production Performance

**Test:** Run POST /api/indexing/incremental with sourceRoot only (full re-index path) against a real production-sized source tree of ~100 classes.
**Expected:** Response durationMs < 300,000 (5 minutes) and errors list is empty.
**Why human:** The @Tag("slow") test passes (~12.6s in Testcontainers per SUMMARY) using minimal stub fixtures. Production classes with complex visitor paths, larger classpaths, and remote Qdrant may increase duration. The SLO must be verified against representative load.

---

## Summary

Phase 10 goal is achieved. All 13 must-have truths are verified against actual code. The incremental indexing pipeline is fully implemented and wired:

- **FileHashUtil** computes SHA-256 hashes and normalizes paths for stored contentHash comparison.
- **IncrementalIndexingService** orchestrates an 8-step pipeline with correctly separated @Transactional boundaries (delete commits before extract-persist starts, preventing SDN session-cache version conflicts).
- **ChunkingService.chunkByFqns** provides the SLO-03 optimization — selective chunking of only changed FQNs via a Cypher WHERE filter.
- **IndexingController** exposes POST /api/indexing/incremental with unified incremental/full-reindex behavior, returning 400 for missing sourceRoot.
- **8 integration tests** (530 lines) cover all 5 requirement IDs end-to-end with Testcontainers; all reported PASS in SUMMARY with concrete observed timings (~2.4s SLO-03, ~12.6s SLO-04).

Two items are flagged for human verification: SLO-03 and SLO-04 timing in a real CI/production environment (not a blocking gap — automated tests pass; this is a production confidence check).

---

_Verified: 2026-03-18_
_Verifier: Claude (gsd-verifier)_
