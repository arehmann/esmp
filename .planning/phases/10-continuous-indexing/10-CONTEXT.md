# Phase 10: Continuous Indexing - Context

**Gathered:** 2026-03-06
**Status:** Ready for planning

<domain>
## Phase Boundary

CI-triggered incremental graph and vector updates on changed files only. When the legacy codebase undergoes active development, the knowledge graph and vector store stay current without requiring manual re-runs. This phase does NOT add new analysis capabilities — it makes existing extraction, linking, risk scoring, and vector indexing work incrementally.

</domain>

<decisions>
## Implementation Decisions

### Trigger mechanism
- New REST webhook endpoint: `POST /api/indexing/incremental`
- Synchronous — blocks until extraction + vector update completes, returns results inline
- CI pipeline calls it after build with a list of changed files
- Caller provides changed file paths (ESMP does not need git access)
- Single unified endpoint handles both incremental (changed files list) and full module re-index (sourceRoot only) use cases

### Changed file detection
- Trust the caller's changed-files list as primary trigger
- Also compute SHA-256 contentHash during extraction and store on ClassNode in Neo4j
- Use contentHash as secondary validation to skip truly-unchanged files (e.g., whitespace-only diffs from CI)
- Neo4j contentHash is the single source of truth for file versions — no separate MySQL audit table

### Deleted/renamed file handling
- Request body has two lists: `changedFiles` (to re-extract) and `deletedFiles` (to remove)
- CI pipeline provides both from `git diff --name-status`
- Hard delete from both Neo4j and Qdrant — stale data removed completely
- Cascade delete: removing a ClassNode also removes all edges (CALLS, DEPENDS_ON, etc.) and child MethodNodes/FieldNodes
- Renamed files treated as delete old + extract new (no rename tracking)

### Re-computation scope
- **Linking**: Global re-link via `LinkingService.linkAllRelationships()` — Cypher MERGE is idempotent, fast, and ensures transitive edges are correct
- **Risk scores**: Global recompute via `RiskService.computeAndPersistRiskScores()` — fan-in/out changes ripple beyond changed classes
- **Vector indexing**: Re-embed only changed files' chunks — neighbor enrichment payload drift corrected on their next change
- **Response**: Detailed report with counts (classesExtracted, classesDeleted, nodesCreated, nodesUpdated, edgesLinked, chunksReEmbedded, chunksDeleted, durationMs)

### Claude's Discretion
- Exact request/response record structure
- Error handling strategy (partial failures, transactional boundaries)
- Internal pipeline orchestration ordering
- Neo4j deletion Cypher implementation (DETACH DELETE pattern)
- Whether to extract only changed files or the full sourceRoot for linking context

</decisions>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ExtractionService.extract()`: Full pipeline orchestrator — scan, parse, visit, map, persist, link, risk, audit
- `VectorIndexingService.reindex()`: Already has hash-based incremental vector updates with Qdrant scroll comparison
- `VectorIndexingService.deleteByClass()`: Filter-based Qdrant point deletion by classFqn
- `LinkingService.linkAllRelationships()`: 9 relationship types via idempotent Cypher MERGE
- `RiskService.computeAndPersistRiskScores()`: Cypher-native global risk computation
- `ChunkingService.chunkClasses()`: Per-class chunking with 1-hop graph enrichment
- `ChunkIdGenerator`: Deterministic UUID v5 for idempotent Qdrant point IDs

### Established Patterns
- Neo4j MERGE semantics for idempotent node/edge creation
- `@Transactional("neo4jTransactionManager")` qualifier for Neo4j operations
- `Neo4jClient` for complex Cypher operations, SDN repositories for simple lookups
- Spring AI `EmbeddingModel.embed()` for batch embeddings
- Qdrant scroll-based pagination for bulk reads
- `ExtractionAccumulator` as pipeline-wide data carrier

### Integration Points
- `ClassNode.contentHash` field exists but is NOT populated — needs computation in extraction pipeline
- `ClassMetadataVisitor` line 96 sets contentHash to null with comment "higher level if needed"
- `POST /api/extraction/trigger` (ExtractionController) — existing full extraction endpoint
- `POST /api/vector/index` and `POST /api/vector/reindex` (VectorIndexController) — existing vector endpoints
- Pipeline ordering constraint: LinkingService MUST run before RiskService (DEPENDS_ON edges needed for fan-in/out)

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 10-continuous-indexing*
*Context gathered: 2026-03-06*
