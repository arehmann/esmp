---
phase: 11-rag-pipeline
plan: "02"
subsystem: api
tags: [rag, neo4j, qdrant, spring-ai, completable-future, testcontainers]

# Dependency graph
requires:
  - phase: 11-rag-pipeline/11-01
    provides: RagRequest, RagResponse, FocalClassDetail, ContextChunk, ScoreBreakdown, ConeSummary, DisambiguationResponse, RagWeightConfig, VectorSearchService.searchByCone
  - phase: 08-smart-chunking-vector-indexing
    provides: code_chunks Qdrant collection, VectorSearchService, ChunkSearchResult
  - phase: 07-domain-aware-risk-analysis
    provides: enhancedRiskScore, structuralRiskScore on ClassNode
  - phase: 04-graph-validation-canonical-queries
    provides: ValidationQueryRegistry base class, ValidationQuery, ValidationSeverity
provides:
  - RagService orchestrator: resolve -> cone -> embed -> search -> merge -> rank pipeline
  - RagController: POST /api/rag/context endpoint with 400/404 validation
  - RagValidationQueryRegistry: 3 new validation queries (total 38 across codebase)
  - 10 RagServiceIntegrationTest tests covering RAG-01, RAG-02, RAG-03, RAG-04, SLO-01, SLO-02
  - 4 RagControllerIntegrationTest tests covering REST 200/400/404
affects: [any future AI/LLM orchestration layers that build on RAG context packages]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "CompletableFuture.supplyAsync for parallel Neo4j cone traversal + EmbeddingModel text embedding"
    - "Hop-distance cone via MATCH path + min(length(path)) AS hopDist in Cypher WITH clause"
    - "DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS|BINDS_TO|QUERIES|MAPS_TO_TABLE*1..10 variable-length multi-rel traversal"
    - "Weighted score formula: vectorSimilarity*vectorScore + graphProximity*(1/hopDist) + riskScore*enhancedRisk"

key-files:
  created:
    - src/main/java/com/esmp/rag/application/RagService.java
    - src/main/java/com/esmp/rag/api/RagController.java
    - src/main/java/com/esmp/rag/validation/RagValidationQueryRegistry.java
    - src/test/java/com/esmp/rag/application/RagServiceIntegrationTest.java
    - src/test/java/com/esmp/rag/api/RagControllerIntegrationTest.java
  modified: []

key-decisions:
  - "RagService is NOT @Transactional — pure read orchestrator using Neo4jClient directly"
  - "CompletableFuture.supplyAsync parallelizes cone traversal and EmbeddingModel.embed() independently"
  - "Focal class is always added to coneWithHops at hop 0 so it ranks with graphProximity=1.0"
  - "NL fallback merges cones from top-3 Qdrant hits (min hop distance for overlapping FQNs)"
  - "ConeSummary domainTerms parsed from JSON string payload using Jackson ObjectMapper"
  - "RagValidationQueryRegistry uses violation-count inversion: count=0 means PASS, count=1 means FAIL"

patterns-established:
  - "RagService: pure orchestrator, no write paths, no @Transactional"
  - "Controller validation inline (null, blank, limit bounds) returning Map.of error bodies"

requirements-completed: [RAG-01, RAG-02, RAG-03, RAG-04, SLO-01, SLO-02]

# Metrics
duration: 35min
completed: 2026-03-18
---

# Phase 11 Plan 02: RagService Orchestrator, RagController, and Integration Tests Summary

**RagService hop-distance cone traversal + parallel embedding + cone-constrained Qdrant search + weighted re-ranking, served via POST /api/rag/context with 14 passing integration tests**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-03-18T12:00:00Z
- **Completed:** 2026-03-18T12:43:00Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments

- Built `RagService` as a pure read orchestrator executing the full RAG pipeline in 8 steps: FQN/simple-name/natural-language resolution, hop-distance cone expansion via Cypher (7 relationship types, *1..10), parallel embedding + cone traversal via `CompletableFuture`, cone-constrained Qdrant search via `searchByCone`, weighted re-ranking by vectorSimilarity/graphProximity/riskScore, and `RagResponse` assembly with `FocalClassDetail`, `ConeSummary`, and `DisambiguationResponse` support
- Added `RagController` with `POST /api/rag/context` serving 400 (blank input, invalid limit), 404 (class not found), and 200 (full RAG response or disambiguation)
- Added `RagValidationQueryRegistry` with 3 new queries bringing the total to 38 across the codebase
- All 14 integration tests pass: 10 in `RagServiceIntegrationTest` (RAG-01 through RAG-04, SLO-01, SLO-02, pipeline integrity) and 4 in `RagControllerIntegrationTest` (REST HTTP codes)

## Task Commits

1. **Task 1: RagService orchestrator** - `fef3134` (feat)
2. **Task 2: RagController, RagValidationQueryRegistry, and integration tests** - `2829ff2` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/rag/application/RagService.java` — Full RAG orchestrator with 8-step pipeline, 3 query resolution paths, parallel async execution
- `src/main/java/com/esmp/rag/api/RagController.java` — POST /api/rag/context with validation and 400/404/200 responses
- `src/main/java/com/esmp/rag/validation/RagValidationQueryRegistry.java` — 3 RAG pre-condition validation queries
- `src/test/java/com/esmp/rag/application/RagServiceIntegrationTest.java` — 10 integration tests, full Testcontainers setup
- `src/test/java/com/esmp/rag/api/RagControllerIntegrationTest.java` — 4 REST integration tests with RANDOM_PORT

## Decisions Made

- `RagService` has no `@Transactional` annotation — it only reads and does not need a transaction boundary; Neo4jClient auto-commits each individual query
- `CompletableFuture.supplyAsync` is used without a custom executor (uses ForkJoinPool.commonPool), which is appropriate for the pilot fixture test scale; production at scale could benefit from a dedicated thread pool but is out of scope for Phase 11
- The focal class is inserted into `coneWithHops` at hop distance 0 so it participates in vector search with `graphProximity = 1.0`, giving focal-class chunks the maximum graph proximity boost
- `RagValidationQueryRegistry` uses inverted logic (count = 1 means failure) to represent pre-condition checks rather than violation queries — this follows a pattern established by `PilotValidationQueryRegistry` for sentinel checks
- Natural language fallback merges cone maps from top-3 Qdrant hits with `Math.min` for overlapping FQNs, prioritizing proximity over fan-out

## Deviations from Plan

None — plan executed exactly as written.

The `codeText` field in `ContextChunk` is populated with the `classFqn` string when no Qdrant chunk text is available in the payload (Qdrant payload text field named `text` was not stored in Phase 8 — only metadata fields were indexed). This is not a deviation from the plan (which specified "use a summary string") and matches the fallback behavior described in Step 7.

## Issues Encountered

None of significance. The Cypher `min(length(path)) AS hopDist` aggregation pattern (documented in Phase 7 memory) worked correctly first time.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Phase 11 is now feature-complete: RAG API contracts (Plan 01) + RAG orchestrator + controller (Plan 02) are all deployed
- The `POST /api/rag/context` endpoint is ready for integration with any LLM/AI orchestration layer in future phases
- Total validation queries: 38 (35 from Phases 4-10 + 3 RAG)
- All 6 requirements validated: RAG-01, RAG-02, RAG-03, RAG-04, SLO-01, SLO-02

---
*Phase: 11-rag-pipeline*
*Completed: 2026-03-18*

## Self-Check: PASSED

- `src/main/java/com/esmp/rag/application/RagService.java` exists
- `src/main/java/com/esmp/rag/api/RagController.java` exists
- `src/main/java/com/esmp/rag/validation/RagValidationQueryRegistry.java` exists
- `src/test/java/com/esmp/rag/application/RagServiceIntegrationTest.java` exists
- `src/test/java/com/esmp/rag/api/RagControllerIntegrationTest.java` exists
- Task commits fef3134 and 2829ff2 confirmed in git log
