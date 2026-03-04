# Phase 4: Graph Validation & Canonical Queries - Context

**Gathered:** 2026-03-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Structural graph correctness is verified before building semantic layers on top of it. 20 canonical validation queries are defined and passing against the populated graph. A dependency cone query is introduced as a new REST endpoint. Validation is read-only — it identifies problems but never modifies the graph.

</domain>

<decisions>
## Implementation Decisions

### Validation query design
- Split ~10 structural integrity checks + ~10 architectural pattern checks
- Structural integrity: no orphan nodes, no dangling edges, uniqueness constraint compliance, relationship endpoint validity, inheritance chain completeness
- Architectural patterns: every @Service has DEPENDS_ON, every Repository has QUERIES, every UIView has BINDS_TO, etc.
- Queries organized in an extensible registry pattern — Phase 4 ships 20, future phases (e.g., Phase 5 Domain Lexicon) can add USES_TERM validation queries without modifying core
- Structural invariants are hard ERROR pass/fail; suspicious-but-not-broken conditions (0 QUERIES edges, very low Service count) are soft WARNING
- Report includes both counts AND specific failing entity details (e.g., "Orphan nodes: com.example.Foo, com.example.Bar") for actionability

### Ground truth source
- Validate against the real legacy codebase (not just synthetic fixtures)
- Manual verification: pick 1-2 well-understood modules, run extraction, then verify the graph output matches the developer's mental model of those modules' structure
- Module selection deferred to execution time — Phase 4 builds the validation framework; developer chooses the sample module when running it
- No formal architecture docs to compare against — developer's domain knowledge is the ground truth

### Failure handling
- Invocation: REST endpoint `GET /api/graph/validation` — runs all queries, returns JSON report with pass/fail/warn per query
- Validation is strictly read-only — report only, no auto-remediation
- Severity levels: ERROR (structurally broken — orphan nodes, missing edges) vs WARNING (suspicious — low counts, unexpected zeros)
- Report per query: name, severity, status (PASS/FAIL/WARN), count, details (list of specific failing entities)

### Dependency cone
- Cone = all nodes reachable from a focal class via ALL structural relationship types: DEPENDS_ON, EXTENDS, IMPLEMENTS, CALLS, BINDS_TO, QUERIES, MAPS_TO_TABLE (full transitive)
- Max depth: 10 hops (matches existing EXTENDS*1..10 and DEPENDS_ON*1..10 patterns)
- Exposed as new REST endpoint: `GET /api/graph/class/{fqn}/dependency-cone` — reusable by Phase 11 (RAG Pipeline) later
- Cone accuracy validated by comparing graph-derived cones against developer's mental model for the sample module

### Claude's Discretion
- Query format: Java constants vs external YAML — Claude picks based on existing codebase patterns
- Exact Cypher query implementations for each of the 20 canonical queries
- Validation report JSON schema design
- Cone query Cypher pattern (multi-relationship variable-length path)
- Whether to add graph summary/stats endpoint alongside validation
- Test strategy for validation queries (integration tests with Testcontainers)

</decisions>

<specifics>
## Specific Ideas

- The dependency cone endpoint is a strategic investment — Phase 11 (RAG Pipeline) will use cones for retrieval context. Validating cone correctness now prevents compounding errors downstream.
- Extensible registry anticipates Phase 5 (Domain Lexicon) adding USES_TERM and DEFINES_RULE validation queries.
- The validation endpoint should feel like a "health check for the graph" — run it after extraction to confirm the graph is trustworthy.

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- `GraphQueryService`: 4 existing Cypher queries (structure, inheritance, service-dependents, search) — pattern for cone query implementation
- `GraphQueryController`: 4 REST endpoints under `/api/graph/` — validation and cone endpoints extend this
- `Neo4jClient`: Used for all complex Cypher traversals — validation queries follow the same pattern
- `Neo4jSchemaInitializer`: Uniqueness constraints on 7 node types — validation can verify constraint compliance
- `GraphQueryRepository`: SDN repository for simple lookups — validation can use for count queries

### Established Patterns
- Neo4jClient `.query(cypher).bind(param).to("name")` for parameterized Cypher
- Response record classes in `com.esmp.graph.api` package for REST responses
- `@GetMapping` with `{fqn:.+}` regex suffix for FQN path variables
- Testcontainers Neo4j for integration tests (`GraphQueryControllerIntegrationTest`)
- Controller -> Service -> Repository/Neo4jClient layering

### Integration Points
- New endpoints added to `GraphQueryController` or new `ValidationController` under `/api/graph/`
- Validation queries reference all 7 node types (JavaClass, JavaMethod, JavaField, JavaAnnotation, JavaPackage, JavaModule, DBTable) and 7 relationship types
- Cone endpoint extends `GraphQueryService` or new service class
- Report JSON returned via standard Spring Boot REST response

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 04-graph-validation-canonical-queries*
*Context gathered: 2026-03-05*
