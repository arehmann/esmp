# Phase 12: Governance Dashboard - Context

**Gathered:** 2026-03-18
**Status:** Ready for planning

<domain>
## Phase Boundary

Developer can see the current state of the migration — what is done, what is risky, and what still uses Vaadin 7 APIs — in a single dashboard. Covers DASH-01 through DASH-06: module-level V7 API percentages, interactive dependency graph explorer, business concept graph, risk hotspot clusters, lexicon coverage, and migration progress heatmap.

</domain>

<decisions>
## Implementation Decisions

### Dashboard layout & navigation
- Single-page scrollable dashboard at root route `/` (default landing page)
- Full Vaadin AppLayout shell with drawer/sidebar navigation wrapping all routes (Dashboard, Lexicon, future views)
- LexiconView stays at `/lexicon` — now accessible from sidebar nav
- Top section: metric summary cards (V7 API %, Lexicon coverage %, Migration progress) with mini module-level breakdown inline
- Below cards: risk hotspot clusters visual map
- Lower sections: dependency graph explorer, business concept graph
- Card-based sections for each metric area

### Graph visualization approach
- Cytoscape.js integrated via Vaadin `@NpmPackage` / `@JsModule` pattern (MIT licensed, no commercial Vaadin license needed)
- One reusable `CytoscapeGraph` Java wrapper component used for both DASH-02 (dependency graph) and DASH-03 (business concept graph) — configured with different data and styling per use case
- Java wrapper communicates with Cytoscape.js via `getElement().callJsFunction()` for data push, `@DomEvent` for click callbacks
- Dependency graph (DASH-02): starts at module-level overview, click module to expand into class-level nodes
- Business concept graph (DASH-03): domain terms linked to implementing classes

### Data aggregation strategy
- New `DashboardService` backend service with dedicated Cypher queries for module-level aggregation
- Returns structured DTOs consumed directly by Vaadin views (no REST layer needed — server-side injection)
- Reusable by Phase 13 (Risk-Prioritized Scheduling)
- DASH-01 V7 counting: dual metric — class-level percentage (vaadin7Detected classes / total classes per module) as headline, plus total V7 pattern occurrence count as secondary
- DASH-05 lexicon coverage: curated terms / total terms percentage
- DASH-06 migration heatmap color: composite score — V7 class percentage weighted by average enhanced risk score per module (high-V7 + high-risk = most red)

### Interaction & drill-down model
- Clicking a graph node opens a side panel (right side of graph area) showing callers, callees, risk score, stereotype — graph stays visible
- DASH-04 risk hotspot clusters: visual cluster map (bubble chart or treemap) — size = class count, color = avg risk
- Clicking a summary metric card scrolls the page to the corresponding module-level breakdown section below
- Dashboard loads data once on page open — no auto-refresh or polling (single-user migration tool, data changes only when extraction/indexing runs)

### Claude's Discretion
- Exact Cytoscape.js layout algorithm choice (force-directed, COSE, etc.)
- CSS styling, spacing, color palette for the dashboard
- Exact cluster detection algorithm for DASH-04 (connected-component or risk-threshold-based grouping)
- AppLayout drawer styling and icon choices
- Error state handling and loading indicators

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` — DASH-01 through DASH-06 requirement definitions

### Existing UI patterns
- `src/main/java/com/esmp/ui/LexiconView.java` — Existing Vaadin 24 view pattern (@Route, Grid, ListDataProvider, filter pattern)
- `src/main/java/com/esmp/ui/TermDetailDialog.java` — Dialog pattern for detail views
- `src/main/java/com/esmp/ui/TermEditorDialog.java` — Editor dialog pattern

### Existing backend APIs (data sources for dashboard)
- `src/main/java/com/esmp/graph/api/RiskController.java` — `/api/risk/heatmap` (sortBy=enhanced|structural), `/api/risk/class/{fqn}` (method-level CC)
- `src/main/java/com/esmp/graph/api/GraphQueryController.java` — `/api/graph/class/{fqn}/dependency-cone`, `/search`
- `src/main/java/com/esmp/graph/api/LexiconController.java` — `/api/lexicon/` (term listing)
- `src/main/java/com/esmp/graph/api/ValidationController.java` — `/api/graph/validation` (38 validation queries)
- `src/main/java/com/esmp/graph/application/RiskService.java` — Risk score computation, heatmap data
- `src/main/java/com/esmp/graph/application/LexiconService.java` — Term CRUD and filtering

### Graph model reference
- `src/main/java/com/esmp/extraction/model/ClassNode.java` — vaadin7Detected, enhancedRiskScore, structuralRiskScore, module, stereotype fields
- `src/main/java/com/esmp/extraction/model/BusinessTermNode.java` — termId, displayName, criticality, curated, usageCount fields

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `LexiconView.java`: Vaadin 24 view with Grid, filters, ListDataProvider — pattern to follow for dashboard sections
- `TermDetailDialog.java`: Vaadin Dialog for click-to-detail — side panel will replace this pattern for graph views
- `RiskService.java`: Already computes enhanced risk scores per class with fan-in/out — DashboardService can aggregate these
- `LexiconService.java`: Term listing with filtering — lexicon coverage stat can query curated vs total count
- `GraphQueryService.java`: Dependency cone traversal — dependency graph explorer can reuse cone queries

### Established Patterns
- Vaadin 24 with Spring Boot: `@Route`, `@PageTitle`, constructor injection of `@Service` beans
- Neo4jClient for complex Cypher queries (used across RiskService, LexiconService, GraphQueryService)
- `ListDataProvider` for in-memory data with client-side filtering
- Vaadin `@NpmPackage` + `@JsModule` for JS library integration (documented in Vaadin docs, not yet used in codebase)

### Integration Points
- New `DashboardView` at `@Route("")` with AppLayout as parent layout
- New `MainLayout` (AppLayout) wrapping Dashboard and LexiconView routes
- New `DashboardService` injecting Neo4jClient for aggregate Cypher queries
- Cytoscape.js npm package + frontend JS module in `frontend/` directory
- `CytoscapeGraph` Java component wrapping Cytoscape.js for reuse

</code_context>

<specifics>
## Specific Ideas

- Dependency graph starts at module-level (coarse), expands to class-level on click — avoids overwhelming graph with 100s of nodes
- Risk hotspot clusters displayed as treemap/bubble chart — visual, not tabular
- Metric cards include mini module-level breakdown inline (not just big numbers)
- V7 metric shows both class percentage and raw pattern count for granularity
- Heatmap uses composite score (V7% * risk) so high-risk V7 modules stand out more than low-risk V7 modules

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 12-governance-dashboard*
*Context gathered: 2026-03-18*
