# Phase 9: Golden Module Pilot - Context

**Gathered:** 2026-03-06
**Status:** Ready for planning

<domain>
## Phase Boundary

End-to-end validation of the semantic pipeline on one bounded context before scaling to the full codebase. Select a pilot module, run the full pipeline (extraction, graph population, chunking, enrichment, vector indexing), validate results against expected outputs, build a search endpoint for interactive retrieval testing, and produce a structured validation report. Fix critical pipeline issues discovered; document minor issues for later.

</domain>

<decisions>
## Implementation Decisions

### Module selection
- **Automated recommendation with manual override**: Build a Cypher query that scores modules by Vaadin 7 stereotype count, risk score diversity, and class count, then recommends the best candidate with rationale
- User can override via API parameter to specify a different module
- **Synthetic test fixtures as pilot target**: Create a realistic multi-class test module with Vaadin 7 patterns (views, services, repos, entities, domain terms) — controlled, verifiable, exercises all pipeline stages
- **Medium size (15-40 classes)**: Enough variety for meaningful graph relationships, risk distribution, and domain terms while still manually verifiable

### Validation approach
- **Both automated assertions + manual checklist**: Integration tests assert objective criteria (edge counts, chunk completeness, enrichment fields); manual review for subjective quality (relevance, usefulness)
- **Both markdown report + REST API**: `GET /api/pilot/validate/{module}` returns JSON validation metrics; also generates a structured markdown report for human review
- **Full node + edge coverage**: Verify all node types (ClassNode, MethodNode, FieldNode, AnnotationNode, PackageNode, BusinessTermNode) and all 9 relationship types are present where expected
- **Pass/fail per check, no composite score**: Each validation check reports pass/fail/warning — composite confidence score deferred to later phases when there's more data to calibrate against

### RAG retrieval testing
- **Vector similarity + manual graph check**: Automated Qdrant embedding search validates returned chunks; manually verify that graph neighbors appear in enrichment payloads
- **New search endpoint**: `POST /api/vector/search` — accept text query, embed it, search Qdrant, return ranked chunks with full payloads. Phase 11 RAG will build on this.
- **Both precision + enrichment quality**: For known query classes, verify that top-K results are relevant AND that chunk payloads contain correct callers/callees, domain terms, risk scores, Vaadin patterns
- **3-5 test queries**: One per major class type (service, repo, view, entity, utility) — focused set sufficient to spot systemic issues

### Issue handling
- **Fix critical, defer minor**: Issues that break pipeline correctness (wrong edges, missing enrichment, corrupt chunks) are fixed immediately; cosmetic or optimization issues documented for later
- **Lightweight golden regression tests**: A few key assertions (chunk count for pilot module, key enrichment fields present, search result relevance for known queries) as permanent integration tests
- **Pragmatic exit criteria**: All critical issues fixed + minor issues documented — no need for 100% perfection to proceed to Phase 10
- **Lightweight migration readiness assessment**: Basic stats per module (Vaadin 7 class count, avg risk score, domain term coverage %) without full migration recommendations — that's Phase 13

### Claude's Discretion
- Exact synthetic fixture design (class names, relationships, Vaadin 7 patterns)
- Module scoring Cypher query design and weight tuning
- Validation report structure and section organization
- Search endpoint response format and pagination
- Integration test strategy (Testcontainers vs in-memory)
- Which enrichment fields to include in golden regression assertions

</decisions>

<specifics>
## Specific Ideas

- The existing `POST /api/vector/index` and `POST /api/vector/reindex` endpoints from Phase 8 are the pipeline triggers — pilot orchestrates calling extraction, then indexing, then validation
- All 32 existing validation queries (Phases 4-8) should run as part of pilot validation — they verify graph integrity before checking vector/retrieval quality
- The pilot validation endpoint should reuse the ValidationService pattern (registry of checks, execute all, collect results)
- Module recommendation query can leverage existing risk heatmap data (`GET /api/risk/heatmap`) and stereotype labels already on ClassNodes

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- **ValidationService** (`graph/application/ValidationService.java`): Accepts `List<ValidationQueryRegistry>` — pilot can add a `PilotValidationQueryRegistry` with pilot-specific checks
- **VectorIndexingService** (`vector/application/VectorIndexingService.java`): `indexAll(sourceRoot)` and `reindex(sourceRoot)` — pilot triggers these
- **ChunkingService** (`vector/application/ChunkingService.java`): `chunkClasses(sourceRoot)` — pilot validates chunk output quality
- **RiskController** (`graph/api/RiskController.java`): `GET /api/risk/heatmap` with sortBy parameter — pilot reuses for module scoring
- **GraphQueryController** (`graph/api/GraphQueryController.java`): Structure/dependency endpoints — pilot validates graph completeness
- **EmbeddingModel** bean: Spring AI auto-configured, used for search endpoint embedding
- **QdrantClient** bean: Already configured for Qdrant operations

### Established Patterns
- Controller -> Service layering for new endpoints
- `@ConfigurationProperties` for configurable values
- ValidationQueryRegistry extensibility pattern
- Response record classes in `api` packages
- Testcontainers for integration tests
- Neo4jClient for complex Cypher queries

### Integration Points
- Reads from Neo4j: All node types and relationships for coverage validation
- Reads from Qdrant: Chunk retrieval and search for quality validation
- New REST endpoints: `GET /api/pilot/validate/{module}`, `POST /api/vector/search`, `GET /api/pilot/recommend`
- New PilotValidationService in `com.esmp.pilot` package
- Extends existing validation framework with pilot-specific checks

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 09-golden-module-pilot*
*Context gathered: 2026-03-06*
