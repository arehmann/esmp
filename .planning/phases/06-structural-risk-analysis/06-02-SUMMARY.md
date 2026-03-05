---
phase: 06-structural-risk-analysis
plan: "02"
subsystem: graph-api
tags: [risk-metrics, fan-in, fan-out, cypher, neo4j, spring-boot, rest-api, tdd, testcontainers]

dependency_graph:
  requires:
    - 06-01 (ComplexityVisitor: ClassNode.complexitySum/complexityMax/hasDbWrites + MethodNode.cyclomaticComplexity)
    - 03-03 (LinkingService.linkAllRelationships(): DEPENDS_ON edges used for fan-in/out)
  provides:
    - RiskService.computeAndPersistRiskScores(): Cypher-based fan-in/out + log-normalized composite score
    - GET /api/risk/heatmap: filterable (module, packageName, stereotype, limit) risk heatmap sorted desc by score
    - GET /api/risk/class/{fqn}: full risk detail with per-method CC breakdown
    - RiskValidationQueryRegistry: 3 validation queries (RISK_SCORES_POPULATED, FAN_IN_OUT_POPULATED, HIGH_RISK_NO_DEPENDENCIES)
    - RiskWeightConfig: configurable weights (esmp.risk.weight.* in application.yml)
    - java_class_risk_score Neo4j range index for ORDER BY heatmap performance
  affects:
    - 06-03 (any future AI-assisted risk report phase will read from /api/risk/heatmap)

tech_stack:
  added:
    - RiskService (com.esmp.graph.application) — fan-in/out Cypher + composite score computation
    - RiskController (com.esmp.graph.api) — REST endpoints
    - RiskWeightConfig (com.esmp.extraction.config) — @ConfigurationProperties(prefix="esmp.risk.weight")
    - RiskValidationQueryRegistry (com.esmp.graph.validation) — extends ValidationQueryRegistry
    - Response records: RiskHeatmapEntry, RiskDetailResponse, MethodComplexityEntry
  patterns:
    - Two-step Cypher update: Step 1 fan-in/out from DEPENDS_ON pattern comprehension, Step 2 composite score SET
    - Pattern comprehension for fan-in/out avoids GROUP BY Cypher complexity: size([(other)-[:DEPENDS_ON]->(c) | other]) AS fi
    - Neo4j log() function for log normalization in composite score; CASE WHEN for boolean dbWrites contribution
    - Dynamic Cypher construction with HashMap params (mirrors LexiconService.findByFilters pattern)
    - extractStereotypeLabels(Node): iterate node.labels() filtering against STEREOTYPE_LABELS Set<String>
    - Method detail mapped from collect(m) AS methods with null-safe stream filter

key_files:
  created:
    - src/main/java/com/esmp/graph/application/RiskService.java
    - src/main/java/com/esmp/graph/api/RiskController.java
    - src/main/java/com/esmp/graph/api/RiskHeatmapEntry.java
    - src/main/java/com/esmp/graph/api/RiskDetailResponse.java
    - src/main/java/com/esmp/graph/api/MethodComplexityEntry.java
    - src/main/java/com/esmp/graph/validation/RiskValidationQueryRegistry.java
    - src/main/java/com/esmp/extraction/config/RiskWeightConfig.java
    - src/test/java/com/esmp/graph/application/RiskServiceIntegrationTest.java
  modified:
    - src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java
    - src/main/java/com/esmp/extraction/application/ExtractionService.java
    - src/main/resources/application.yml

key-decisions:
  - "Fan-in/out computed via Cypher pattern comprehension (size([(other)-[:DEPENDS_ON]->(c) | other])) in a single MATCH+SET query — avoids OPTIONAL MATCH count(r) grouping complexity and is more readable"
  - "Composite risk score formula uses Neo4j's log() with +1 offset (log(1+x)) to handle zero-valued classes gracefully without -infinity"
  - "RiskService MUST be called after LinkingService.linkAllRelationships() — DEPENDS_ON edges are the input to fan-in/out computation; ordering enforced by ExtractionService wiring"
  - "RiskWeightConfig uses @ConfigurationProperties(prefix='esmp.risk.weight') + @Component following ExtractionConfig pattern — snake-case YAML property fan-in binds to fanIn field via Spring's relaxed binding"
  - "ValidationRegistry three queries: RISK_SCORES_POPULATED and FAN_IN_OUT_POPULATED are ERROR severity (required invariants), HIGH_RISK_NO_DEPENDENCIES is WARNING (actionable hint, not a violation)"

requirements-completed:
  - RISK-02
  - RISK-04
  - RISK-05

duration: 50min
completed: "2026-03-05"
---

# Phase 6 Plan 02: Fan-in/Out, Composite Risk Score, and Risk API Summary

**Cypher-computed fan-in/out metrics and log-normalized composite structural risk score with filterable REST heatmap and per-class detail endpoints**

## Performance

- **Duration:** 50 min
- **Started:** 2026-03-05T11:15:00Z
- **Completed:** 2026-03-05T12:50:00Z
- **Tasks:** 2 (+ TDD RED commit)
- **Files modified:** 10

## Accomplishments

- Fan-in/out computed from existing DEPENDS_ON graph edges via Cypher pattern comprehension — no re-parsing required
- Composite structural risk score formula: `w_complexity*log(1+complexitySum) + w_fanIn*log(1+fanIn) + w_fanOut*log(1+fanOut) + w_dbWrites*(hasDbWrites?1:0)` with all weights configurable
- GET /api/risk/heatmap with optional filtering by module, packageName, stereotype, and limit
- GET /api/risk/class/{fqn} returning full risk profile with per-method cyclomatic complexity breakdown
- ExtractionService pipeline now automatically computes risk scores after each extraction run

## Task Commits

| Task | Commit | Description |
|------|--------|-------------|
| Task 1 (TDD RED) | `3837253` | Failing integration tests for RiskService and RiskController |
| Task 1 (TDD GREEN) | `540c34e` | RiskService, RiskController, response records, validation registry, schema index, application.yml |
| Task 2 | `2b3f307` | Wire RiskService into ExtractionService pipeline |

## Files Created/Modified

- `src/main/java/com/esmp/graph/application/RiskService.java` — fan-in/out Cypher, composite score computation, heatmap and detail queries with node mapping
- `src/main/java/com/esmp/graph/api/RiskController.java` — REST endpoints following LexiconController pattern
- `src/main/java/com/esmp/graph/api/RiskHeatmapEntry.java` — response record for heatmap list entries
- `src/main/java/com/esmp/graph/api/RiskDetailResponse.java` — response record for class detail with methods
- `src/main/java/com/esmp/graph/api/MethodComplexityEntry.java` — response record for per-method CC
- `src/main/java/com/esmp/graph/validation/RiskValidationQueryRegistry.java` — 3 validation queries extending ValidationQueryRegistry
- `src/main/java/com/esmp/extraction/config/RiskWeightConfig.java` — configurable weight properties
- `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` — added java_class_risk_score range index
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` — added RiskService injection + post-linking call
- `src/main/resources/application.yml` — added esmp.risk.weight.* defaults
- `src/test/java/com/esmp/graph/application/RiskServiceIntegrationTest.java` — 9 integration tests

## Decisions Made

- Fan-in/out via Cypher pattern comprehension (`size([(other)-[:DEPENDS_ON]->(c) | other])`): avoids OPTIONAL MATCH grouping complexity and is more idiomatic Cypher
- Log normalization with +1 offset (`log(1+x)`): handles zero-valued classes gracefully without -infinity
- `RiskService.computeAndPersistRiskScores()` MUST be called after `linkAllRelationships()` — DEPENDS_ON edges are the input; ordering enforced by ExtractionService wiring
- `RiskWeightConfig` uses `@ConfigurationProperties(prefix = "esmp.risk.weight")` + `@Component` following ExtractionConfig pattern; Spring's relaxed binding maps `fan-in` YAML key to `fanIn` field
- Validation queries: `RISK_SCORES_POPULATED` and `FAN_IN_OUT_POPULATED` are ERROR (required invariants post-extraction), `HIGH_RISK_NO_DEPENDENCIES` is WARNING (actionable hint for reviewers)

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

- Gradle daemon OOM when running full test suite (all Testcontainers tests in parallel). Pre-existing issue observed in Phase 3 and documented in prior summaries. RiskServiceIntegrationTest passes individually; unit tests pass individually. The full-suite run crashed the daemon but this is not a regression caused by this plan.

## Next Phase Readiness

- Phase 6 structural risk feature is complete: complexity metrics (Phase 6 Plan 01) + fan-in/out + composite score + REST API (Phase 6 Plan 02)
- `GET /api/risk/heatmap` and `GET /api/risk/class/{fqn}` are available for any AI-assisted risk reporting or Vaadin UI in future phases
- Total validation queries: 26 (20 structural + 3 lexicon + 3 risk)

---
*Phase: 06-structural-risk-analysis*
*Completed: 2026-03-05*
