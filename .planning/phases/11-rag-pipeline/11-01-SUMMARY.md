---
phase: 11-rag-pipeline
plan: "01"
subsystem: api
tags: [rag, vector-search, qdrant, spring-ai, configuration-properties]

# Dependency graph
requires:
  - phase: 08-smart-chunking-vector-indexing
    provides: VectorSearchService, ChunkSearchResult, code_chunks Qdrant collection with classFqn payload index
  - phase: 07-domain-aware-risk-analysis
    provides: enhancedRiskScore, structuralRiskScore fields on ClassNode used in ContextChunk
provides:
  - RAG API contract records: RagRequest, RagResponse, FocalClassDetail, ContextChunk, ScoreBreakdown, ConeSummary, DisambiguationResponse
  - RagWeightConfig @ConfigurationProperties(prefix=esmp.rag.weight) with defaults 0.40/0.35/0.25
  - VectorSearchService.searchByCone(float[], List<String>, int) — cone-constrained Qdrant search
  - application.yml esmp.rag.weight section
affects: [11-02-rag-service-orchestrator, 11-03-rag-controller]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@ConfigurationProperties class for RAG weights following RiskWeightConfig pattern"
    - "Pre-computed float[] vector passed into searchByCone — caller embeds for parallelism"
    - "Qdrant matchKeywords (plural) for list-based payload filter vs matchKeyword (singular) for single value"

key-files:
  created:
    - src/main/java/com/esmp/rag/api/RagRequest.java
    - src/main/java/com/esmp/rag/api/RagResponse.java
    - src/main/java/com/esmp/rag/api/FocalClassDetail.java
    - src/main/java/com/esmp/rag/api/ContextChunk.java
    - src/main/java/com/esmp/rag/api/ScoreBreakdown.java
    - src/main/java/com/esmp/rag/api/ConeSummary.java
    - src/main/java/com/esmp/rag/api/DisambiguationResponse.java
    - src/main/java/com/esmp/rag/config/RagWeightConfig.java
  modified:
    - src/main/java/com/esmp/vector/application/VectorSearchService.java
    - src/main/resources/application.yml

key-decisions:
  - "RagWeightConfig uses @Component + @ConfigurationProperties — same pattern as RiskWeightConfig (no @EnableConfigurationProperties needed)"
  - "searchByCone accepts pre-computed float[] so RagService can embed once and reuse vector for parallelism"
  - "matchKeywords (ConditionFactory plural variant) used for List<String> cone FQN filter — distinct from matchKeyword (singular)"

patterns-established:
  - "com.esmp.rag.api package holds pure record contracts; no Spring annotations on records"
  - "Disambiguation response uses nested record (DisambiguationCandidate inside DisambiguationResponse)"

requirements-completed: [RAG-02, RAG-03, RAG-04]

# Metrics
duration: 2min
completed: 2026-03-18
---

# Phase 11 Plan 01: RAG API Contracts and VectorSearchService Extension Summary

**7 RAG API records, RagWeightConfig @ConfigurationProperties, and VectorSearchService.searchByCone with matchKeywords cone filter — full contract layer for Phase 11 RAG orchestrator**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-18T11:29:44Z
- **Completed:** 2026-03-18T11:31:49Z
- **Tasks:** 2
- **Files modified:** 10

## Accomplishments

- Created 7 Java records in `com.esmp.rag.api` covering the full RAG response surface: request/response wrapper, focal class detail, ranked context chunk with score breakdown, cone summary, and disambiguation support
- Added `RagWeightConfig` following the established `RiskWeightConfig` pattern with defaults vectorSimilarity=0.40, graphProximity=0.35, riskScore=0.25 bound from `esmp.rag.weight` YAML section
- Extended `VectorSearchService` with `searchByCone` using Qdrant `matchKeywords` list filter on the `classFqn` payload index, accepting a pre-computed float[] vector for caller-controlled parallelism

## Task Commits

Each task was committed atomically:

1. **Task 1: Create RAG API records and RagWeightConfig** - `5658471` (feat)
2. **Task 2: Extend VectorSearchService with cone-constrained FQN-list search** - `dfe02e7` (feat)

**Plan metadata:** (see docs commit below)

## Files Created/Modified

- `src/main/java/com/esmp/rag/api/RagRequest.java` — Request body with query, fqn, limit, module, stereotype, includeFullSource
- `src/main/java/com/esmp/rag/api/RagResponse.java` — Main response wrapper with isDisambiguation() method
- `src/main/java/com/esmp/rag/api/FocalClassDetail.java` — Focal class details including codeText, domainTerms, risk scores
- `src/main/java/com/esmp/rag/api/ContextChunk.java` — Ranked context chunk with 14 fields including ScoreBreakdown and relationshipPath
- `src/main/java/com/esmp/rag/api/ScoreBreakdown.java` — Per-chunk score decomposition: vectorScore, graphProximityScore, riskScore, finalScore
- `src/main/java/com/esmp/rag/api/ConeSummary.java` — Aggregate cone stats: totalNodes, vaadin7Count, avgEnhancedRisk, topDomainTerms, uniqueBusinessTermCount
- `src/main/java/com/esmp/rag/api/DisambiguationResponse.java` — Disambiguation wrapper with nested DisambiguationCandidate record
- `src/main/java/com/esmp/rag/config/RagWeightConfig.java` — @ConfigurationProperties weights for RAG ranking formula
- `src/main/java/com/esmp/vector/application/VectorSearchService.java` — Added searchByCone method with matchKeywords filter
- `src/main/resources/application.yml` — Added esmp.rag.weight section with default values

## Decisions Made

- `RagWeightConfig` uses `@Component + @ConfigurationProperties` — same pattern as `RiskWeightConfig` (no `@EnableConfigurationProperties` needed since `@Component` registers the bean)
- `searchByCone` accepts pre-computed `float[]` so `RagService` (Plan 02) can embed the query once and reuse the vector for parallelism with Neo4j cone traversal
- `matchKeywords` (the plural `ConditionFactory` variant) is used for `List<String>` cone FQN filter — distinct from `matchKeyword` (singular) used for single-value filters in the existing `search()` method

## Deviations from Plan

None — plan executed exactly as written.

The only minor deviation was the Gradle task exclusion flag: the plan specified `-x vaadin` but that is ambiguous (multiple tasks match). Used `-x vaadinBuildFrontend -x vaadinPrepareFrontend` instead. Build passed cleanly.

## Issues Encountered

None of significance.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- All RAG API contracts are defined and compile-verified
- `RagWeightConfig` is bound and injectable
- `VectorSearchService.searchByCone` is ready for `RagService` orchestration in Plan 02
- Plan 02 can immediately consume `searchByCone` and all record types without any additional interface work

---
*Phase: 11-rag-pipeline*
*Completed: 2026-03-18*
