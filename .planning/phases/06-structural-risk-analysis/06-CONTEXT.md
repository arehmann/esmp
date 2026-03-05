# Phase 6: Structural Risk Analysis - Context

**Gathered:** 2026-03-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Every class in the graph gets structural risk metrics (cyclomatic complexity, fan-in/out, DB write detection) and a composite structural risk score. A REST API exposes a filterable heatmap endpoint sorted by risk, plus a per-class detail endpoint. No UI view — Phase 12 (Governance Dashboard) handles visualization.

</domain>

<decisions>
## Implementation Decisions

### Complexity computation
- Compute cyclomatic complexity **per method** by counting branch points (if/else/for/while/switch/catch) in the AST
- Add a new `ComplexityVisitor` (OpenRewrite `JavaIsoVisitor`) that runs during the extraction pipeline alongside existing visitors
- Store per-method CC on `MethodNode` as a `cyclomaticComplexity` integer property
- Aggregate to class level: store **both** sum and max of method CCs on `ClassNode` (`complexitySum`, `complexityMax`)
- Computation happens during extraction (single source-parse pass), not as a separate post-extraction step

### Risk score storage
- Store risk metrics as **properties directly on ClassNode** — no separate RiskProfile node
- New ClassNode properties: `complexitySum`, `complexityMax`, `fanIn`, `fanOut`, `hasDbWrites`, `dbWriteCount`, `structuralRiskScore`
- New MethodNode property: `cyclomaticComplexity`
- Risk scores are **fully recomputed on every re-extraction** — no curated guard (unlike lexicon terms)
- Composite score formula: **weighted sum** — `score = w1*complexity + w2*fanIn + w3*fanOut + w4*dbWriteFlag`
- Weights configurable via **application.properties** (e.g., `esmp.risk.weight.complexity=0.3`)

### Heatmap endpoint design
- **REST endpoint only** — `GET /api/risk/heatmap` returns JSON sorted by descending structural risk score
- Phase 12 (Governance Dashboard) builds the visual heatmap; Phase 6 provides the data API
- **Filterable** via query params: `?module=X&package=Y&stereotype=Service&limit=50` — follows LexiconController's filterable pattern
- Response includes **full metric breakdown** per class: fqn, complexitySum, complexityMax, fanIn, fanOut, hasDbWrites, dbWriteCount, structuralRiskScore, stereotype labels
- **Per-class detail endpoint**: `GET /api/risk/class/{fqn}` — full risk profile + method-level complexity breakdown + contributing factors. Reusable by Phase 7 and Phase 12

### DB write detection
- **Extend existing JPA visitor** (or add detection logic in the new ComplexityVisitor) — detect @Modifying, persist()/merge()/delete() calls, JPQL/SQL with INSERT/UPDATE/DELETE
- Store **both** binary flag (`hasDbWrites: true/false`) and quantified count (`dbWriteCount: N`) per class
- Binary flag feeds into composite risk score; count available in detail endpoint
- **Flag any write type** — no distinction between INSERT/UPDATE/DELETE for Phase 6. Sufficient for migration risk
- Fan-in/fan-out computed from **DEPENDS_ON edges** (class-level): fanIn = count of classes that DEPENDS_ON this class, fanOut = count of classes this class DEPENDS_ON

### Claude's Discretion
- Exact ComplexityVisitor implementation (which AST nodes count as branch points)
- Default weight values for composite risk formula
- RiskController and RiskService internal design
- Risk-specific validation queries for RiskValidationQueryRegistry
- Normalization strategy for individual metrics before weighting (min-max, log scale, etc.)
- Test strategy (integration tests with Testcontainers)
- Whether fan-in/out computation happens during extraction or as a post-extraction Cypher aggregation

</decisions>

<specifics>
## Specific Ideas

- The composite risk score is a strategic foundation — Phase 7 (Domain-Aware Risk) adds domain criticality, security sensitivity, and financial involvement weights on top of this structural baseline.
- Fan-in/out from DEPENDS_ON edges is already materialized in the graph (Phase 3 LinkingService), so computation is a Cypher aggregation query — no re-parsing needed.
- The detail endpoint (`GET /api/risk/class/{fqn}`) with method-level breakdown will be valuable for Phase 12's dashboard drill-down and Phase 8's risk-weighted chunking.
- Following Phase 4's extensible ValidationQueryRegistry pattern, a `RiskValidationQueryRegistry` can verify that all classes have risk scores computed.

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- **ExtractionAccumulator** (`visitor/ExtractionAccumulator.java`): Extend with complexity and DB write data collection methods
- **JavaIsoVisitor pattern** (5 existing visitors): Template for new ComplexityVisitor
- **ClassNode** (`extraction/model/ClassNode.java`): Add risk metric properties (complexitySum, complexityMax, fanIn, fanOut, hasDbWrites, dbWriteCount, structuralRiskScore)
- **MethodNode** (`extraction/model/MethodNode.java`): Add cyclomaticComplexity property
- **ValidationQueryRegistry** (`graph/validation/ValidationQueryRegistry.java`): Extensible @Component pattern for RiskValidationQueryRegistry
- **LexiconController** (`graph/api/LexiconController.java`): Filterable endpoint pattern to reuse for heatmap API
- **Neo4jClient**: For fan-in/out aggregation Cypher queries and risk score computation
- **JpaPatternVisitor** (`extraction/visitor/JpaPatternVisitor.java`): Existing JPA detection logic — extend or reference for DB write detection

### Established Patterns
- Neo4jClient `.query(cypher).bind(param)` for all complex Cypher queries
- Response record classes in `com.esmp.graph.api` package
- Controller -> Service -> Repository/Neo4jClient layering
- `@DynamicLabels` for stereotype labels on nodes
- Application.properties for configurable values
- Testcontainers Neo4j for integration tests

### Integration Points
- Add `ComplexityVisitor` to `ExtractionService.extract()` visitor sequence
- Add risk properties to ClassNode and MethodNode (schema evolution)
- Add risk score computation step to ExtractionService (post-visitor, pre-persist, or post-persist Cypher)
- New `RiskController` under `/api/risk/` for heatmap and detail endpoints
- New `RiskService` for risk computation and querying
- New `RiskValidationQueryRegistry` extending validation framework
- Add uniqueness indexes on new properties if needed via Neo4jSchemaInitializer
- Fan-in/out computed via Cypher COUNT on DEPENDS_ON edges (already in graph from Phase 3)

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 06-structural-risk-analysis*
*Context gathered: 2026-03-05*
