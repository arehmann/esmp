# Phase 14: MCP Server for AI-Powered Migration Context - Context

**Gathered:** 2026-03-19
**Status:** Ready for planning

<domain>
## Phase Boundary

Build an MCP server embedded in the existing ESMP Spring Boot application that exposes the platform's knowledge services (Code Knowledge Graph, RAG pipeline, Risk Engine, Domain Lexicon, Vector Search, Validation) as MCP tools. Claude Code connects via SSE transport and uses these tools to retrieve structured, domain-aware migration context during Vaadin 7 → Vaadin 24 migration work.

This phase delivers the MCP protocol layer, the MigrationContextAssembler (aggregation + token budgeting), caching, observability, and graceful degradation. It does NOT add new analysis capabilities — it exposes what Phases 1-13 already built.

</domain>

<decisions>
## Implementation Decisions

### MCP Transport & Architecture
- SSE (Server-Sent Events) transport embedded in the existing Spring Boot application
- NOT a separate process — MCP endpoint coexists with Vaadin UI and REST APIs
- Single process, single set of DB connections (Neo4j, Qdrant, MySQL)
- Claude Code connects via `http://localhost:8080/mcp/sse` (or configured port)
- Use Spring AI MCP Server Spring Boot Starter (`spring-ai-mcp-server-spring-boot-starter`)

### Tool Surface (6 Tools — Hybrid Approach)
- **3 aggregate tools** (primary interface):
  - `get_migration_context` — The power tool. Input: target class FQN. Internally calls GraphQueryService (dependency cone), RagService (vector search + re-ranking), LexiconService (domain terms), RiskService (scores), ValidationService (health). Returns unified migration context.
  - `search_knowledge` — Semantic vector search. Input: query string + optional filters (module, stereotype, topK). Returns ranked code chunks.
  - `get_dependency_cone` — Graph exploration. Input: class FQN + depth. Returns nodes + edges.
- **3 drill-down tools** (targeted follow-ups):
  - `get_risk_analysis` — Risk heatmap (filterable) OR class-level detail with per-method complexity. For when Claude needs risk-specific info without full context assembly.
  - `browse_domain_terms` — Lexicon search/browse. Input: search query or filter by criticality. Returns terms with definitions, related classes, usage counts.
  - `validate_system_health` — Run 41 validation queries. Returns pass/fail report. Claude calls this before migration to confirm data integrity.

### MigrationContextAssembler (Core Component)
- Dedicated `@Service` that orchestrates all service calls for `get_migration_context`
- Parallel async calls to Neo4j cone, vector search, lexicon, risk (like RagService's CompletableFuture pattern)
- Priority-ordered context assembly:
  1. Business rules (highest — always included)
  2. Domain terms (always included)
  3. Risk analysis (always included)
  4. Dependencies / dependency cone summary
  5. Code chunks from vector search (lowest — truncated first)
- Smart token budget truncation:
  - Configurable max tokens (e.g., `esmp.mcp.context.max-tokens=8000`)
  - Always preserve rules + terms + risk
  - Truncate code chunks to fit budget
  - Add `truncated: true` flag and `truncatedItems` count so Claude knows more is available
  - Claude can call `search_knowledge` to fetch the truncated chunks if needed

### Caching (Caffeine)
- Caffeine local cache for hot queries:
  - Dependency cones (TTL ~5min)
  - Domain terms per class (TTL ~10min)
  - Frequent semantic query results
- Cache eviction on incremental re-index (`POST /api/indexing/incremental` triggers evict)
- Configurable via `esmp.mcp.cache.*` properties

### Observability
- **Micrometer metrics**: latency per tool, cache hit rate, tool invocation counts, response size
  - `esmp.mcp.request.duration` (timer, tagged by tool name)
  - `esmp.mcp.cache.hit.ratio` (gauge)
  - `esmp.mcp.tool.invocations` (counter, tagged by tool name)
- **Structured request logging**: every MCP request logs request ID, tool name, parameters, latency, payload size, truncation flag, warnings
- Spring Actuator already provides Micrometer infrastructure — leverage existing setup

### Graceful Degradation
- If a downstream service fails (Neo4j down, Qdrant timeout), return partial context with warnings
- `warnings` array in response listing what's missing (e.g., "Vector service unavailable: code chunks omitted")
- `contextCompleteness` score (0.0-1.0):
  - All services respond: 1.0
  - Vectors down: ~0.7 (graph + risk + terms still available)
  - Graph down: ~0.3 (only vectors available)
  - Everything down: 0.0 with error
- Claude uses completeness score to gauge confidence in the returned context

### Performance SLOs (from user spec)
- `get_migration_context` < 1.5s
- `search_knowledge` < 500ms
- `get_dependency_cone` < 300ms
- Achieved via parallel async calls + Caffeine caching + connection pooling

### Security (Scoped to Single-User)
- No OAuth2 or RBAC — PROJECT.md defines single-user platform
- MCP endpoint accessible on localhost only (default Spring Boot binding)
- No secrets in responses
- Audit trail via structured request logging

### Claude's Discretion
- Exact Caffeine cache TTL values and eviction policies
- Internal method decomposition of MigrationContextAssembler
- Token estimation algorithm (character-based heuristic vs tiktoken-equivalent)
- Exact contextCompleteness scoring weights per service
- MCP tool description strings (what Claude sees in tool list)
- Error response format details

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### User Specification
- User provided a comprehensive MCP server specification during discussion (not a file — captured in decisions above). Covers: objective, architecture, API design, service layer, context assembly, performance, caching, security, observability, error handling, testing, deployment.

### Existing Services to Expose
- `src/main/java/com/esmp/rag/application/RagService.java` — RAG pipeline orchestrator (parallel CompletableFuture pattern to replicate)
- `src/main/java/com/esmp/graph/application/GraphQueryService.java` — Dependency cone, class structure, inheritance chain queries
- `src/main/java/com/esmp/graph/application/RiskService.java` — Structural + domain-aware risk scoring
- `src/main/java/com/esmp/graph/application/LexiconService.java` — Domain term CRUD
- `src/main/java/com/esmp/vector/application/VectorSearchService.java` — Semantic vector search
- `src/main/java/com/esmp/graph/validation/ValidationService.java` — 41 validation query execution

### Existing Patterns to Follow
- `src/main/java/com/esmp/rag/config/RagWeightConfig.java` — ConfigurationProperties pattern for weights
- `src/main/java/com/esmp/vector/config/VectorConfig.java` — ConfigurationProperties pattern for service config
- `src/main/java/com/esmp/scheduling/application/SchedulingService.java` — Read-only orchestrator pattern (NOT @Transactional)

### MCP SDK
- Spring AI MCP Server documentation — SSE transport configuration, tool registration, Spring Boot starter setup

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **RagService** (`rag/application/RagService.java`): Already does cone traversal + vector search + re-ranking with CompletableFuture parallelism. MigrationContextAssembler extends this pattern with additional services (lexicon, risk, validation).
- **RagWeightConfig** (`rag/config/RagWeightConfig.java`): ConfigurationProperties pattern for tunable weights — replicate for MCP config.
- **VectorSearchService.searchByCone()**: Accepts pre-computed float[] for embedding reuse — MCP assembler can leverage this.
- **ValidationService**: Accepts `List<ValidationQueryRegistry>` — all 7 registries (41 queries) auto-discovered via Spring DI.
- **All 14 services** are Spring `@Service` beans — directly injectable into MCP tool handlers.

### Established Patterns
- Read-only orchestrators (RagService, DashboardService, SchedulingService) are NOT @Transactional — MCP assembler should follow this pattern.
- Neo4jClient raw Cypher for complex traversals — MCP tools should delegate to existing services, not write new Cypher.
- CompletableFuture.supplyAsync for parallel service calls — established in RagService, reuse in assembler.
- ConfigurationProperties with `esmp.*` prefix — use `esmp.mcp.*` for all MCP config.

### Integration Points
- MCP SSE endpoint registers alongside existing Vaadin routes and REST controllers in the same Spring Boot app.
- Cache eviction hooks into IncrementalIndexingService — when graph/vectors update, MCP cache must invalidate.
- Micrometer metrics integrate with existing Spring Actuator setup (already configured for Prometheus in Phase 1).
- All 11 existing REST controllers remain functional — MCP is an additional access layer, not a replacement.

</code_context>

<specifics>
## Specific Ideas

- User provided a full specification document defining the MCP server as "the ONLY gateway between AI and the ESMP knowledge system"
- `get_migration_context` output schema specified: summary, dependencies, dependency_graph, vaadin_usages, business_terms (with criticality), business_rules (with source), risk (level + score + reason), relevant_code_chunks, relevant_doc_chunks
- Context assembly must be deterministic and reproducible — same input always produces same output (given same graph state)
- Every request must be logged for auditability — if AI produces bad output, trace back to what context it received

</specifics>

<deferred>
## Deferred Ideas

- **Redis distributed cache** — User spec mentioned Redis as optional. Overkill for single-user local deployment. Consider if ESMP moves to multi-user.
- **OAuth2 / RBAC security** — User spec mentioned role-based access (Developer, Architect, MigrationBot). Out of scope per PROJECT.md single-user constraint.
- **Kubernetes deployment** — User spec mentioned K8s. Current deployment is Docker Compose. Defer to when/if production deployment is needed.
- **Git/CI metadata integration** — User spec mentioned as "optional initial phase." Could be a Phase 15 extension.
- **Temporal graph queries** — Listed in user spec's future extensions.
- **Behavioral diff integration** — Listed as v2 requirement (DIFF-01/02/03).
- **Drift detection APIs** — Listed in user spec's future extensions.

</deferred>

---

*Phase: 14-mcp-server-for-ai-powered-migration-context*
*Context gathered: 2026-03-19*
