# Phase 11: RAG Pipeline - Context

**Gathered:** 2026-03-18
**Status:** Ready for planning

<domain>
## Phase Boundary

Multi-layer GraphRAG retrieval pipeline: given a focal class (or natural language query), combine Neo4j graph expansion (dependency cone) with Qdrant embedding similarity search, merge and re-rank results by weighted score, and return a structured migration context package via REST API. This phase builds the retrieval layer — AI orchestration that consumes it is a separate (v2) phase.

</domain>

<decisions>
## Implementation Decisions

### Query Input & Resolution
- Accept both **class FQN/simple name** and **natural language** queries
- Resolution strategy: **Neo4j lookup first** — try resolving input as FQN or simple class name in Neo4j. If found → graph+vector pipeline. If no match → treat as natural language, vector-only search with graph expansion on top 3 hits
- Ambiguous simple names (multiple matches): return a **disambiguation response** listing matching FQNs with module/stereotype info. User re-queries with the exact FQN
- Natural language fallback: expand graph cone around **top 3 vector hits**, merge all cone results + vector results into final response
- Accept optional filters: **module, stereotype, limit** — consistent with existing VectorSearchController API pattern

### Cone-Constrained Search
- **Filter Qdrant by cone FQNs** — after getting cone nodes from Neo4j, pass their FQNs as a Qdrant payload filter (`classFqn IN [...]`). Server-side filtering leverages existing payload index on classFqn
- Cone depth: **fixed at 10 hops** (existing validated depth from Phase 4)
- No FQN cap: **query all cone FQNs** regardless of cone size. A 50-node cone produces ~150-300 chunks — well within Qdrant's filter capacity
- Embedding source: **embed focal class's own chunk text** for similarity search within the cone ("show me things like this class")

### Merge & Ranking
- **Weighted linear combination**: `finalScore = 0.4 * vectorSimilarity + 0.35 * graphProximity + 0.25 * enhancedRiskScore`
- Graph proximity derived from hop distance (1/hopCount normalization)
- Weights configurable via `application.yml` (`esmp.rag.weight.*`)
- **No Vaadin-specific ranking boost** — framework-agnostic scoring. Vaadin metadata (vaadin7Detected, vaadinPatterns) included in response for downstream filtering but does not affect ranking. This keeps the pipeline reusable across migration targets (any Vaadin version, other frameworks)
- Default result limit: **top 20**, configurable via query param (max 100)

### Response Structure
- **Structured migration package** optimized for AI orchestrator consumption (high priority consumer):
  - `focalClass`: full detail — FQN, stereotype, risk scores, domain terms, code text
  - `contextChunks`: ranked list — each with code text, relationship path to focal class (type + hop count), score breakdown (vector/graph/risk components), risk scores, domain terms, vaadin metadata
  - `coneSummary`: aggregate stats — total nodes, vaadin7 count, avg enhanced risk, top domain terms (deduplicated), total unique business terms
- Code text: **semantic chunk text by default** (from Qdrant). Optional `includeFullSource=true` param fetches full `.java` source files from disk for all results. AI orchestrator uses full source mode when generating migration PRs
- Each context result includes **relationship path** to focal class: e.g., "DEPENDS_ON (1 hop)", "EXTENDS > IMPLEMENTS (2 hops)" — critical for AI orchestrator to understand WHY a class is in context

### Claude's Discretion
- Internal service class structure and method decomposition
- Error handling for missing source files (includeFullSource mode)
- Exact graph proximity normalization formula
- Response serialization format details
- SLO optimization strategies (caching, parallel execution)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Graph Expansion
- `src/main/java/com/esmp/graph/application/GraphQueryService.java` — `findDependencyCone()` method with 7-relationship-type Cypher traversal up to 10 hops
- `src/main/java/com/esmp/graph/api/DependencyConeResponse.java` — ConeNode record (fqn, labels) — existing response shape for cone results
- `src/main/java/com/esmp/graph/api/GraphQueryController.java` — existing `/api/graph/class/{fqn}/dependency-cone` endpoint (comment says "designed for reuse by Phase 11")

### Vector Search
- `src/main/java/com/esmp/vector/application/VectorSearchService.java` — `search(SearchRequest)` embeds query, searches Qdrant with optional filters, returns ChunkSearchResult list
- `src/main/java/com/esmp/vector/api/SearchRequest.java` — existing request record (query, limit, module, stereotype, chunkType)
- `src/main/java/com/esmp/vector/api/ChunkSearchResult.java` — 22-field result record with risk scores, graph neighbors, domain terms, vaadin metadata

### Risk & Domain Context
- `src/main/java/com/esmp/graph/application/RiskService.java` — enhanced risk score computation (8-dimension composite)
- `src/main/java/com/esmp/vector/model/CodeChunk.java` — 22-field chunk model with all enrichment data
- `src/main/java/com/esmp/vector/model/DomainTermRef.java` — (termId, displayName) used in chunk domain term lists

### Existing Patterns
- `src/main/java/com/esmp/vector/api/VectorSearchController.java` — REST controller pattern with inline validation
- `src/main/java/com/esmp/pilot/api/PilotController.java` — module-scoped validation and recommendation endpoint patterns
- `src/main/java/com/esmp/extraction/config/RiskWeightConfig.java` — `@ConfigurationProperties` pattern for configurable weights

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **GraphQueryService.findDependencyCone()**: Already traverses all 7 relationship types up to 10 hops. Returns `DependencyConeResponse` with FQNs and labels. Can be called directly from RAG service
- **VectorSearchService.search()**: Already embeds text via Spring AI EmbeddingModel, searches Qdrant with payload filters, maps results to ChunkSearchResult. Needs extension to accept FQN list filter (currently supports module/stereotype/chunkType only)
- **ChunkSearchResult**: 22-field record already carries risk scores, graph neighbors, domain terms, vaadin metadata — most fields needed by the RAG response
- **RiskWeightConfig pattern**: `@ConfigurationProperties` with defaults — reuse same pattern for RAG weight configuration
- **EmbeddingModel bean**: Spring AI auto-configured all-MiniLM-L6-v2 (384-dim ONNX) — already warmed up at startup via EmbeddingWarmup

### Established Patterns
- **Neo4jClient for complex Cypher**: All variable-length traversals and aggregations use Neo4jClient, not SDN repositories
- **@ConfigurationProperties for tunable params**: RiskWeightConfig, VectorConfig both use this pattern
- **Response records**: All API responses are Java records in `api/` sub-packages
- **Validation registries**: Each phase adds its own `ValidationQueryRegistry` @Component — RAG should follow this pattern
- **Controller inline validation**: VectorSearchController validates blank query inline (returns 400)

### Integration Points
- **New package**: `com.esmp.rag` with `api/`, `application/`, `config/` sub-packages
- **VectorSearchService**: Needs a new method or overload accepting `List<String> fqnFilter` for cone-constrained search
- **GraphQueryService**: Existing `findDependencyCone()` reused as-is; may need a method that also returns hop distances per node (currently only returns FQN + labels)
- **Source file reading**: For `includeFullSource` mode, need to read .java files from disk — ChunkingService already has this pattern (`readSourceFile`)

</code_context>

<specifics>
## Specific Ideas

- RAG pipeline must be **framework-agnostic** in ranking — no Vaadin-specific boosts. The tool should support migration from any Vaadin version (or other frameworks). Vaadin metadata is in the response for downstream filtering only
- AI orchestrator is the **highest priority consumer** — response structure optimized for LLM context assembly
- Dashboard and developer REST consumers are secondary — they use the same API but may ignore code text fields

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 11-rag-pipeline*
*Context gathered: 2026-03-18*
