---
phase: 12
plan: "03"
status: complete
started: 2026-03-18
completed: 2026-03-18
---

## What was built

Full governance DashboardView assembling all 6 DASH requirements into a single scrollable page:

1. **Metric Summary Cards** (DASH-01, DASH-05, DASH-06) — V7 API %, Lexicon Coverage %, Migration Progress with click-to-scroll and collapsible module breakdown grid
2. **Migration Heatmap** (DASH-06) — Color-coded module grid (green/yellow/orange/red) sorted by composite heatmap score
3. **Risk Hotspot Clusters** (DASH-04) — CytoscapeGraph bubble map with module nodes sized by class count, colored by avg risk, click shows detail panel
4. **Dependency Graph Explorer** (DASH-02) — Module-level CytoscapeGraph with drill-down to class-level on click, "Back to modules" button, side panel with class details
5. **Business Concept Graph** (DASH-03) — Terms linked to implementing classes with criticality coloring, click shows term or class details in side panel
6. **Human-verify checkpoint** — Dashboard verified by user with extracted pilot data

## Key files

### Created
- `src/main/java/com/esmp/ui/DashboardView.java` — 709-line full dashboard view with 5 sections

### Modified
- `src/main/java/com/esmp/dashboard/application/DashboardService.java` — Fixed Cypher queries to derive module from `split(packageName, '.')[2]` instead of non-existent `c.module` property
- `src/main/java/com/esmp/ui/CytoscapeGraph.java` — Fixed attach race condition: `setGraphData()` called before component attached now queues data and applies on `addAttachListener`

## Deviations

1. **Module derivation fix**: All DashboardService Cypher queries used `c.module` which doesn't exist on JavaClass nodes — fixed to use `split(c.packageName, '.')[2]` for module derivation from package name
2. **CytoscapeGraph attach race**: `setGraphData()` called in constructor before `__initCytoscape` ran on attach — added `pendingData` queue pattern so data is applied after Cytoscape.js initialization
3. **Vaadin vaadinPrepareFrontend reflections bug**: Pre-existing `org.reflections:0.10.2` NPE when scanning with app classes on classpath — workaround: clean generated files before build

## Self-Check: PASSED

- [x] DashboardView at route `/` with layout=MainLayout.class
- [x] Metric cards show V7%, Lexicon Coverage%, Migration Progress
- [x] Heatmap grid with color-coded scores
- [x] Risk cluster CytoscapeGraph bubbles
- [x] Dependency graph with drill-down
- [x] Business concept graph with term-class links
- [x] All data from DashboardService, loaded once in constructor
- [x] Human verification passed
