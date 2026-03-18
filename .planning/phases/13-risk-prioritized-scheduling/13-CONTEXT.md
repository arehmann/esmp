# Phase 13: Risk-Prioritized Scheduling - Context

**Gathered:** 2026-03-18
**Status:** Ready for planning

<domain>
## Phase Boundary

System recommends a data-driven module migration order from lowest-risk to highest-risk, incorporating composite risk scores, cross-module dependency topology, git change frequency, and cyclomatic complexity distribution. Developer sees the schedule as wave-based lanes and a sortable table in the Vaadin dashboard.

</domain>

<decisions>
## Implementation Decisions

### Scoring model
- Fresh SchedulingService (not extending PilotService) — PilotService optimizes for pilot selection (small safe modules), scheduling ranks ALL modules for migration order
- Claude's discretion on whether to build fresh or extend PilotService based on codebase fit
- Risk-dominant weighting: risk ~0.35, dependency count ~0.25, change frequency ~0.20, complexity ~0.20
- All weights externalized via `@ConfigurationProperties(prefix="esmp.scheduling.weight")` following RiskWeightConfig pattern, defaults match risk-dominant profile
- Full rationale per module: riskContribution, dependencyContribution, frequencyContribution, complexityContribution, finalScore — human-readable breakdown

### Git change frequency
- ProcessBuilder shelling out to `git log` on the sourceRoot path — no JGit dependency
- Configurable time window via `esmp.scheduling.git-window-days` property, default 180 days (6 months)
- File-to-module mapping: Claude's discretion (package path parsing or ClassNode.sourceFilePath join — use whichever is more reliable with available data)
- Graceful fallback when git unavailable: Claude's discretion on degradation strategy (zero-weight or error)

### Dependency ordering
- Topological sort + score: group modules into waves/tiers based on cross-module dependency DAG, then rank within each wave by composite score
- Dependencies must be migrated before dependents (wave ordering enforces this)
- Dependency depth: Claude's discretion (direct only vs transitive)
- Circular dependencies: detect strongly connected components (SCCs), merge into same wave, rank by score within the merged group

### Dashboard integration
- New ScheduleView in Vaadin, integrated into MainLayout sidebar alongside Dashboard and Lexicon
- Both views: wave lane visualization (horizontal lanes per wave with module cards) AND sortable ranked table — toggle between them
- Click module to see CytoscapeGraph dependency visualization (reusing Phase 12 component), highlighting which dependencies are in earlier waves (safe) vs later (risky)
- Score breakdown displayed on module drill-down
- On-demand git frequency: `GET /api/scheduling/recommend?sourceRoot=/path/to/repo` computes git frequency at request time, no persistence needed

### Claude's Discretion
- Whether to build fresh SchedulingService or extend PilotService (recommended: fresh)
- File-to-module mapping strategy for git log output
- Git unavailability fallback strategy
- Direct vs transitive dependency depth for topological sort
- Wave lane visual design and card layout details
- Loading/empty states for the schedule view

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` — SCHED-01 (module migration order by composite risk), SCHED-02 (dependency risk, change frequency, complexity)

### Roadmap
- `.planning/ROADMAP.md` — Phase 13 success criteria (4 items: REST endpoint, score factors, rationale, re-run after new ingestion)

### Existing scoring patterns
- `src/main/java/com/esmp/pilot/application/PilotService.java` — `recommendModules()` Cypher scoring pattern with Neo4jClient (Phase 9)
- `src/main/java/com/esmp/extraction/config/RiskWeightConfig.java` — `@ConfigurationProperties` weight pattern to follow

### Existing module aggregations
- `src/main/java/com/esmp/dashboard/application/DashboardService.java` — `getModuleSummaries()`, `getModuleDependencyEdges()`, `getRiskClusters()` (Phase 12)

### Risk scoring infrastructure
- `src/main/java/com/esmp/graph/application/RiskService.java` — 8-dimension enhanced risk score computation

### Cytoscape component
- `src/main/java/com/esmp/ui/CytoscapeGraph.java` — Reusable graph visualization component (Phase 12)

### Dashboard shell
- `src/main/java/com/esmp/ui/MainLayout.java` — AppLayout with SideNav (Phase 12)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **PilotService.recommendModules()**: Cypher scoring pattern with Neo4jClient `.bind()` — extend scoring formula approach
- **RiskWeightConfig**: `@ConfigurationProperties` pattern for externalized scoring weights
- **DashboardService.getModuleDependencyEdges()**: Cross-module DEPENDS_ON edge aggregation — input for topological sort
- **DashboardService.getModuleSummaries()**: Module-level risk/Vaadin7 aggregations — reuse for schedule scoring
- **CytoscapeGraph**: Java/JS Cytoscape wrapper with WeakMap bridge and node-click events
- **MainLayout**: AppLayout with SideNav — add ScheduleView route

### Established Patterns
- Module derivation: `split(c.packageName, '.')[2]` (NOT `c.module` which doesn't exist on ClassNode)
- Neo4jClient for all complex Cypher queries with `.bind()` parameterization
- API records as Java records with clear field documentation
- `@RestController` + `@RequestMapping("/api/{domain}")` pattern
- ValidationQueryRegistry for phase-specific validation queries

### Integration Points
- MainLayout sidebar: add "Schedule" nav item alongside Dashboard and Lexicon
- DashboardService data: reuse module aggregation Cypher queries
- CytoscapeGraph: reuse for module dependency drill-down
- Neo4j ClassNode properties: enhancedRiskScore, complexitySum, complexityMax, fanIn, fanOut, vaadin7Detected
- Cross-module DEPENDS_ON edges: already materialized by LinkingService

</code_context>

<specifics>
## Specific Ideas

- Wave lanes should visually communicate "migrate these first, then these" — left-to-right or top-to-bottom progression
- CytoscapeGraph on drill-down should color-code dependencies by wave (green = earlier wave/safe, red = later wave/risky)
- Rationale string should be immediately useful: "Low risk (0.12), 2 dependents, 45 commits/6mo, moderate complexity — safe early target"

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 13-risk-prioritized-scheduling*
*Context gathered: 2026-03-18*
