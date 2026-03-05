# Phase 8: Smart Chunking and Vector Indexing - Context

**Gathered:** 2026-03-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Enriched semantic code chunks are indexed in Qdrant and incremental re-indexing updates only changed files. Source files are chunked at method-level granularity, enriched with graph neighbors, domain terms, risk scores, and migration state, embedded using a local ONNX model (all-MiniLM-L6-v2), and stored in Qdrant. A separate REST API triggers indexing and re-indexing independently from the extraction pipeline.

</domain>

<decisions>
## Implementation Decisions

### Chunking strategy
- **Method-level granularity**: one chunk per method, plus one "class header" chunk per class (package, imports, fields, annotations, class javadoc)
- Class header is a **separate chunk** — method chunks reference it via classHeaderId, RAG can retrieve both when needed
- **Methods only** — inner classes are already separate ClassNodes in the graph (Phase 3), they get their own header + method chunks
- Chunking happens as a **post-extraction step** (separate ChunkingService), not during the visitor pass — enrichment data (neighbors, risk scores) only exists after the full extraction pipeline completes
- Follows the RiskService pattern: decoupled from extraction, can re-chunk without re-extracting

### Enrichment payload
- **1-hop graph neighbors**: direct callers, callees, dependencies, implementors (FQN lists). Deeper expansion deferred to Phase 11 RAG
- **Full risk breakdown**: structuralRiskScore, enhancedRiskScore, domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity — all already on ClassNode
- **Domain terms**: list of {termId, displayName} pairs from USES_TERM edges — lightweight, full details fetchable from Neo4j
- **Migration state**: Vaadin 7 API detection based on existing stereotype labels (VaadinView, VaadinComponent, VaadinDataBinding from Phase 2) — `vaadin7Detected: true/false` + list of detected patterns
- Both risk scores included for downstream flexibility (RAG can filter by either)

### Incremental re-indexing
- **Content hash detection**: SHA-256 hash of each source file, stored in Qdrant payload per chunk
- On re-index, query Qdrant for existing hashes, compare against current file hashes, only re-chunk and re-embed files whose hash changed
- **Changed file only** — no cascade to neighbor chunks. Neighbor enrichment for other files stays stale until they're re-indexed. Phase 10 (Continuous Indexing) can add cascade logic
- **Separate REST endpoints**: `POST /api/vector/index` (full) and `POST /api/vector/reindex` (incremental) in `com.esmp.vector` package, decoupled from extraction trigger

### Qdrant collection design
- **Single collection** (`code_chunks`) for all chunk types — payload fields for filtered queries
- **Deterministic UUID v5 point IDs** from namespace + classFqn + methodSignature — enables idempotent upsert, matches the MERGE-by-business-key pattern in Neo4j
- **Startup initialization**: create collection with vector config and payload indexes at app startup (like Neo4jSchemaInitializer pattern) — fail fast if misconfigured
- **Payload indexes**: classFqn (keyword), module (keyword), stereotype (keyword), chunkType (keyword), enhancedRiskScore (float range)

### Claude's Discretion
- ONNX runtime configuration and model loading strategy
- Exact chunk text formatting (how method source + context is concatenated for embedding)
- Qdrant vector dimension and distance metric (determined by all-MiniLM-L6-v2 model: 384 dimensions, cosine)
- ChunkingService and VectorIndexingService internal design
- Vector-specific validation queries for a VectorValidationQueryRegistry
- Test strategy (integration tests with Testcontainers Qdrant)
- Batch size for Qdrant upsert operations
- Error handling for embedding failures on individual chunks

</decisions>

<specifics>
## Specific Ideas

- The `com.esmp.vector` package was reserved at project init (Phase 1 CONTEXT.md: package-by-feature) — this is where chunking, embedding, and indexing code lives
- All-MiniLM-L6-v2 was decided at project init as the embedding model (STATE.md: "Local ONNX embeddings preferred over API embeddings at bulk indexing scale")
- The detail endpoint from Phase 6 (`GET /api/risk/class/{fqn}`) with method-level breakdown is directly useful for per-method enrichment — no new risk queries needed
- VEC-04 (incremental re-indexing) should be verifiable via the success criteria: "When a source file changes, only its affected chunks are re-embedded and updated in Qdrant (not a full collection rebuild)"

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- **QdrantClient bean** (`infrastructure/config/QdrantConfig.java`): Already configured for host:port 6334, ready to use
- **QdrantHealthIndicator** (`infrastructure/health/QdrantHealthIndicator.java`): Health check pattern exists
- **Neo4jSchemaInitializer**: Pattern for startup schema/index creation — replicate for Qdrant collection initialization
- **ClassNode** (`extraction/model/ClassNode.java`): Has all risk properties (structuralRiskScore, enhancedRiskScore, domainCriticality, etc.) and dynamic labels (stereotypes)
- **RiskService** (`graph/application/RiskService.java`): Pattern for post-extraction computation service
- **Neo4jClient**: For enrichment Cypher queries (1-hop neighbor traversal)
- **ValidationQueryRegistry**: Extensible pattern for VectorValidationQueryRegistry
- **LexiconController/RiskController**: Filterable endpoint pattern for vector API

### Established Patterns
- Neo4jClient `.query(cypher).bind(param)` for all complex Cypher queries
- Response record classes in `com.esmp.graph.api` or relevant `api` package
- Controller -> Service layering
- `@ConfigurationProperties` for configurable values (e.g., RiskWeightConfig)
- Testcontainers for integration tests
- Deterministic business-key IDs with MERGE/upsert

### Integration Points
- Reads from Neo4j: ClassNode properties, MethodNode properties, USES_TERM edges, all 9 relationship types for neighbor enrichment
- Reads source files: re-reads Java source for chunk text content (source path from ClassNode.sourcePath)
- Writes to Qdrant: upsert points with embeddings + enriched payloads
- New REST endpoints under `/api/vector/` for index and reindex triggers
- New VectorIndexingService in `com.esmp.vector` package
- New QdrantCollectionInitializer (or extend existing startup validator)
- Qdrant io.qdrant:client 1.13.0 already in version catalog

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 08-smart-chunking-vector-indexing*
*Context gathered: 2026-03-05*
