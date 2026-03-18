# Phase 12: Governance Dashboard - Research

**Researched:** 2026-03-18
**Domain:** Vaadin 24 Flow UI, Cytoscape.js JS integration, Neo4j Cypher aggregation
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Single-page scrollable dashboard at root route `/` (default landing page)
- Full Vaadin AppLayout shell with drawer/sidebar navigation wrapping all routes (Dashboard, Lexicon, future views)
- LexiconView stays at `/lexicon` — now accessible from sidebar nav
- Top section: metric summary cards (V7 API %, Lexicon coverage %, Migration progress) with mini module-level breakdown inline
- Below cards: risk hotspot clusters visual map
- Lower sections: dependency graph explorer, business concept graph
- Card-based sections for each metric area
- Cytoscape.js integrated via Vaadin `@NpmPackage` / `@JsModule` pattern (MIT licensed, no commercial Vaadin license needed)
- One reusable `CytoscapeGraph` Java wrapper component used for both DASH-02 (dependency graph) and DASH-03 (business concept graph) — configured with different data and styling per use case
- Java wrapper communicates with Cytoscape.js via `getElement().callJsFunction()` for data push, `@DomEvent` for click callbacks
- Dependency graph (DASH-02): starts at module-level overview, click module to expand into class-level nodes
- Business concept graph (DASH-03): domain terms linked to implementing classes
- New `DashboardService` backend service with dedicated Cypher queries for module-level aggregation
- Returns structured DTOs consumed directly by Vaadin views (no REST layer needed — server-side injection)
- Reusable by Phase 13 (Risk-Prioritized Scheduling)
- DASH-01 V7 counting: dual metric — class-level percentage (vaadin7Detected classes / total classes per module) as headline, plus total V7 pattern occurrence count as secondary
- DASH-05 lexicon coverage: curated terms / total terms percentage
- DASH-06 migration heatmap color: composite score — V7 class percentage weighted by average enhanced risk score per module (high-V7 + high-risk = most red)
- Clicking a graph node opens a side panel (right side of graph area) showing callers, callees, risk score, stereotype — graph stays visible
- DASH-04 risk hotspot clusters: visual cluster map (bubble chart or treemap) — size = class count, color = avg risk
- Clicking a summary metric card scrolls the page to the corresponding module-level breakdown section below
- Dashboard loads data once on page open — no auto-refresh or polling

### Claude's Discretion
- Exact Cytoscape.js layout algorithm choice (force-directed, COSE, etc.)
- CSS styling, spacing, color palette for the dashboard
- Exact cluster detection algorithm for DASH-04 (connected-component or risk-threshold-based grouping)
- AppLayout drawer styling and icon choices
- Error state handling and loading indicators

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| DASH-01 | Dashboard shows % Vaadin 7 APIs remaining per module | Cypher pattern using `ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])` — same pattern as PilotService.recommendModules(). Module derived from `c.module` property on JavaClass. |
| DASH-02 | Dashboard shows dependency graph explorer (interactive) | CytoscapeGraph wrapper component via `@NpmPackage("cytoscape")` + `@JsModule` + frontend JS wrapper. Module-to-class drill-down: load module-level graph first, then DEPENDS_ON cone on click. |
| DASH-03 | Dashboard shows business concept graph visualization | Same CytoscapeGraph reused with different data source: BusinessTerm nodes + USES_TERM/DEFINES_RULE edges queried from Neo4j. |
| DASH-04 | Dashboard shows risk hotspot clusters | DashboardService Cypher aggregates classes by module + risk threshold. Java-side clustering. Rendered as Cytoscape.js bubble/treemap-style visualization or via CytoscapeGraph with cluster styling. |
| DASH-05 | Dashboard shows lexicon coverage percentage | `MATCH (t:BusinessTerm) RETURN count(t) AS total, sum(CASE WHEN t.curated THEN 1 ELSE 0 END) AS curated` — single aggregation query. |
| DASH-06 | Dashboard shows migration progress heatmap | Per-module composite score: `vaadin7Pct * avgEnhancedRisk`. Color-coded via Vaadin HTML/CSS or Canvas element. Module rows queryable using same pattern as DASH-01. |
</phase_requirements>

---

## Summary

Phase 12 is a pure UI + data aggregation phase building on all preceding phases. The backend already holds all required data: `JavaClass` nodes have `enhancedRiskScore`, `structuralRiskScore`, `module`, and Vaadin stereotype labels (`VaadinView`, `VaadinDataBinding`, `VaadinComponent` via `@DynamicLabels`); `BusinessTermNode` nodes have `curated`; the graph has `USES_TERM` and `DEFINES_RULE` edges. No new extraction or indexing is needed.

The two novel technical elements are: (1) the `MainLayout` with `AppLayout` + `SideNav` that wraps all routes, and (2) the `CytoscapeGraph` Java component that bridges Vaadin Flow's server-push model to Cytoscape.js running in the browser. The Vaadin `@NpmPackage` / `@JsModule` / `@Tag` pattern is the standard approach for embedding non-web-component JS libraries; a small `frontend/cytoscape-graph.js` wrapper file handles `init`, `setData`, and emits a custom DOM event on node click that Java receives via `@DomEvent`.

All module-level aggregations needed for DASH-01 through DASH-06 can be expressed as straightforward Neo4j Cypher queries. The DASH-01 V7 detection pattern (`ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])`) is already proven in `PilotService.recommendModules()`. The DashboardService becomes a thin service aggregating existing graph data into DTOs.

**Primary recommendation:** Follow the proven Vaadin `@NpmPackage` + `@JsModule` + frontend JS wrapper pattern for CytoscapeGraph. Derive all dashboard metrics from existing Neo4j graph properties using parameterized Cypher aggregations. Use `AppLayout` + `SideNav` for MainLayout — it is Vaadin's canonical main view pattern.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Vaadin Flow | 24.9.12 (via BOM) | Server-side UI framework, routing, component tree | Already in project — canonical Java web UI for this stack |
| Cytoscape.js | 3.33.1 | Interactive graph visualization (DASH-02, DASH-03, DASH-04) | MIT licensed, no commercial Vaadin license required; mature API; works with `@NpmPackage` pattern |
| Spring Data Neo4j / Neo4jClient | via Spring Boot 3.5.11 | Parameterized Cypher queries for dashboard aggregations | Already in project; `Neo4jClient` is the established pattern for complex Cypher |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Vaadin AppLayout | 24.9.12 (bundled) | Main layout shell with drawer | Wrapping all routes with consistent navigation |
| Vaadin SideNav / SideNavItem | 24.9.12 (bundled) | Sidebar navigation items | Navigation drawer items for Dashboard, Lexicon |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Cytoscape.js | vis.js, D3.js | Cytoscape.js has cleaner npm ESM export, simpler API surface, smaller bundle; chosen by user |
| Vaadin charts (Pro) | Chart.js via @NpmPackage | Pro license required for Vaadin Charts; Chart.js via same JS integration pattern works for treemap/heatmap if needed |

**Installation:**
```bash
# Cytoscape.js is added via @NpmPackage — Vaadin downloads it automatically at build time.
# No manual npm install needed. Declare in the Java component class:
# @NpmPackage(value = "cytoscape", version = "3.33.1")
```

**Version verification (verified 2026-03-18):**
- `cytoscape`: 3.33.1 (latest stable as of March 2026)
- Vaadin: 24.9.12 (already in `libs.versions.toml`)

---

## Architecture Patterns

### Recommended Project Structure
```
src/
├── main/
│   ├── java/com/esmp/
│   │   ├── ui/
│   │   │   ├── MainLayout.java            # AppLayout shell, SideNav, @Route(layout=...)
│   │   │   ├── DashboardView.java         # @Route(""), scrollable dashboard
│   │   │   ├── CytoscapeGraph.java        # @NpmPackage + @JsModule wrapper component
│   │   │   └── [existing LexiconView, dialogs]
│   │   └── dashboard/
│   │       ├── application/
│   │       │   └── DashboardService.java  # Neo4jClient Cypher aggregations
│   │       └── api/
│   │           ├── ModuleSummary.java      # record: module, classCount, vaadin7Count, vaadin7Pct, ...
│   │           ├── LexiconCoverage.java    # record: total, curated, coveragePct
│   │           ├── RiskCluster.java        # record: module, classCount, avgRisk, maxRisk
│   │           └── BusinessTermLink.java   # record: termId, displayName, classFqns
│   └── frontend/
│       └── cytoscape-graph.js             # JS wrapper: exports init(), setData(), handles events
```

### Pattern 1: AppLayout MainLayout
**What:** A Java class extending `AppLayout` that acts as the parent layout for all routes. Uses `SideNav` in the drawer for navigation. Views reference it via `@Route(value="", layout=MainLayout.class)`.

**When to use:** Always — this is the Vaadin canonical pattern for multi-view apps.

```java
// Source: https://vaadin.com/docs/latest/components/app-layout
@Route("")
public class MainLayout extends AppLayout {
    public MainLayout() {
        DrawerToggle toggle = new DrawerToggle();
        H1 appName = new H1("ESMP");
        appName.getStyle().set("font-size", "1rem").set("margin", "0");

        SideNav nav = new SideNav();
        nav.addItem(
            new SideNavItem("Dashboard", DashboardView.class, VaadinIcon.DASHBOARD.create()),
            new SideNavItem("Lexicon", LexiconView.class, VaadinIcon.BOOK.create())
        );

        Scroller scroller = new Scroller(nav);
        addToDrawer(scroller);
        addToNavbar(toggle, appName);
    }
}
```

**Note on routing:** In Vaadin 24, `@Route(value="", layout=MainLayout.class)` on `DashboardView` makes it the root path AND wraps it in `MainLayout`. LexiconView changes from `@Route("lexicon")` to `@Route(value="lexicon", layout=MainLayout.class)`.

### Pattern 2: Cytoscape.js Wrapper Component
**What:** A Java component that extends `Component` with `@Tag("div")` (not a web component), annotated with `@NpmPackage` and `@JsModule`. Uses `getElement().callJsFunction()` to push data, `@DomEvent` to receive click events.

**When to use:** Any interactive Cytoscape.js graph in the UI.

```java
// Source: https://vaadin.com/docs/latest/flow/create-ui/web-components/java-api-for-a-web-component
@Tag("div")
@NpmPackage(value = "cytoscape", version = "3.33.1")
@JsModule("./cytoscape-graph.js")
public class CytoscapeGraph extends Component implements HasSize {

    public CytoscapeGraph() {
        setHeight("500px");
        setWidth("100%");
        // Initialize Cytoscape on the div element after attach
        addAttachListener(e ->
            getElement().executeJs("window.__initCytoscape($0)", getElement())
        );
    }

    /** Pushes new graph data (nodes + edges as JSON string) to Cytoscape. */
    public void setGraphData(String elementsJson) {
        getElement().callJsFunction("setData", elementsJson);
    }

    /** Registers a listener for node-click events from Cytoscape. */
    public Registration addNodeClickListener(ComponentEventListener<NodeClickEvent> listener) {
        return addListener(NodeClickEvent.class, listener);
    }

    @DomEvent("node-click")
    public static class NodeClickEvent extends ComponentEvent<CytoscapeGraph> {
        private final String nodeId;
        private final String nodeType;

        public NodeClickEvent(CytoscapeGraph source, boolean fromClient,
                @EventData("event.detail.id") String nodeId,
                @EventData("event.detail.type") String nodeType) {
            super(source, fromClient);
            this.nodeId = nodeId;
            this.nodeType = nodeType;
        }

        public String getNodeId() { return nodeId; }
        public String getNodeType() { return nodeType; }
    }
}
```

**Frontend wrapper file (`src/main/frontend/cytoscape-graph.js`):**
```javascript
// Source: Vaadin @JsModule pattern for non-web-component libraries
import cytoscape from 'cytoscape';

window.__initCytoscape = function(element) {
    const cy = cytoscape({
        container: element,
        elements: [],
        style: [
            { selector: 'node', style: { 'label': 'data(label)', 'background-color': '#0d6efd' } },
            { selector: 'edge', style: { 'width': 1, 'line-color': '#ccc', 'target-arrow-shape': 'triangle' } }
        ],
        layout: { name: 'cose' }
    });
    // Store cy instance on element for later data updates
    element._cy = cy;

    cy.on('tap', 'node', function(evt) {
        const node = evt.target;
        element.dispatchEvent(new CustomEvent('node-click', {
            detail: { id: node.id(), type: node.data('type') },
            bubbles: true
        }));
    });
};

// Called via getElement().callJsFunction("setData", jsonStr)
Element.prototype.setData = function(elementsJson) {
    if (this._cy) {
        const elements = JSON.parse(elementsJson);
        this._cy.elements().remove();
        this._cy.add(elements);
        this._cy.layout({ name: 'cose' }).run();
    }
};
```

**Important:** The `@JsModule("./cytoscape-graph.js")` path prefix `./` means Vaadin resolves it relative to `src/main/frontend/`. This is the correct placement for in-project frontend files.

### Pattern 3: DashboardService Cypher Aggregations
**What:** `@Service` using `Neo4jClient` for all dashboard aggregation queries.

**DASH-01 / DASH-06 — V7 API and migration heatmap (per module):**
```cypher
// Source: Pattern established in PilotService.recommendModules() (Phase 9)
MATCH (c:JavaClass)
WHERE c.module IS NOT NULL AND c.module <> ''
WITH c.module AS module,
     count(c) AS classCount,
     sum(CASE WHEN ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])
              THEN 1 ELSE 0 END) AS vaadin7Count,
     avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgEnhancedRisk
RETURN module, classCount, vaadin7Count,
       toFloat(vaadin7Count) / classCount AS vaadin7Pct,
       (toFloat(vaadin7Count) / classCount) * avg(coalesce(c.enhancedRiskScore, 0.0)) AS heatmapScore,
       avgEnhancedRisk
ORDER BY vaadin7Pct DESC
```

**DASH-05 — Lexicon coverage:**
```cypher
MATCH (t:BusinessTerm)
RETURN count(t) AS total,
       sum(CASE WHEN t.curated = true THEN 1 ELSE 0 END) AS curated
```

**DASH-04 — Risk hotspot clusters (module-level):**
```cypher
MATCH (c:JavaClass)
WHERE c.module IS NOT NULL
WITH c.module AS module,
     count(c) AS classCount,
     avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgRisk,
     max(coalesce(c.enhancedRiskScore, 0.0)) AS maxRisk,
     sum(CASE WHEN coalesce(c.enhancedRiskScore, 0.0) > 0.7 THEN 1 ELSE 0 END) AS highRiskCount
RETURN module, classCount, avgRisk, maxRisk, highRiskCount
ORDER BY avgRisk DESC
```

**DASH-02 — Dependency graph (module-level nodes + edges for overview):**
```cypher
// Module-level overview: aggregate DEPENDS_ON edges between modules
MATCH (c1:JavaClass)-[:DEPENDS_ON]->(c2:JavaClass)
WHERE c1.module IS NOT NULL AND c2.module IS NOT NULL AND c1.module <> c2.module
WITH c1.module AS sourceModule, c2.module AS targetModule, count(*) AS edgeWeight
RETURN sourceModule, targetModule, edgeWeight
ORDER BY edgeWeight DESC LIMIT 200
```

**DASH-03 — Business concept graph (terms + implementing classes):**
```cypher
MATCH (t:BusinessTerm)
OPTIONAL MATCH (c:JavaClass)-[:USES_TERM|DEFINES_RULE]->(t)
RETURN t.termId AS termId, t.displayName AS displayName, t.criticality AS criticality,
       collect(DISTINCT c.fullyQualifiedName) AS classFqns
LIMIT 100
```

### Pattern 4: Vaadin metric summary cards
**What:** Vaadin `Div` components styled with Lumo theme classes, containing `H3` + `Span` for metric display.

```java
// Card pattern — no third-party library needed, pure Vaadin Lumo
private Component buildMetricCard(String title, String value, String subtitle) {
    Div card = new Div();
    card.addClassNames("card", "p-m");
    card.getStyle()
        .set("border", "1px solid var(--lumo-contrast-10pct)")
        .set("border-radius", "var(--lumo-border-radius-m)")
        .set("cursor", "pointer");

    H3 heading = new H3(title);
    heading.addClassName("text-secondary");
    Span valueSpan = new Span(value);
    valueSpan.getStyle().set("font-size", "2rem").set("font-weight", "bold");
    Span sub = new Span(subtitle);
    sub.addClassName("text-secondary");

    card.add(heading, valueSpan, sub);
    return card;
}
```

### Anti-Patterns to Avoid
- **Using `executeJs()` with string interpolation for user data:** Use `callJsFunction()` with typed parameters instead — `executeJs()` with concatenated strings risks injection if any data comes from user input.
- **Loading all JavaClass nodes into Java for aggregation:** Always aggregate in Cypher (Neo4j-side); do not `MATCH (c:JavaClass) RETURN c` and aggregate in Java — 1000+ class graphs make this prohibitive.
- **Calling `getElement().callJsFunction()` before component is attached:** The element DOM is only ready after the attach lifecycle. Wrap initialization in `addAttachListener()`.
- **`c.vaadin7Detected` as a stored property:** This property does NOT exist on ClassNode in Neo4j. The correct detection is via `labels(c)` — `ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])`. The `c.vaadin7Detected` path in RagService queries a property that may be null; dashboard queries must use the labels pattern.
- **Putting MainLayout at `@Route("")` without child routes:** `MainLayout` should NOT have `@Route` itself. Child views use `@Route(value="...", layout=MainLayout.class)`. `DashboardView` has `@Route(value="", layout=MainLayout.class)`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Graph layout algorithm | Custom force-directed in Java or Canvas | Cytoscape.js built-in `cose` layout | COSE handles thousands of nodes; handles compound/cluster graphs natively |
| Navigation shell with drawer | Custom CSS/JS sidebar | Vaadin `AppLayout` + `SideNav` | AppLayout is responsive, handles mobile drawer toggle, route-aware highlighting built-in |
| Module-level V7 aggregation | Java loop over all classes | Cypher `sum(CASE WHEN ANY(l IN labels(c)...))` | Single round-trip; avoids hydrating 1000+ nodes into JVM heap |
| Risk cluster detection | Graph community detection algorithms | Cypher threshold grouping (> 0.7 score = high-risk cluster) | For the dashboard visual, simple threshold grouping is sufficient and interpretable; real community detection adds complexity without UI benefit |

**Key insight:** Vaadin's server-push model means all data transformation can stay in Java/Cypher; Cytoscape.js only needs to receive final `{nodes, edges}` JSON. Never implement visualization logic in Java — push data, let the library render.

---

## Common Pitfalls

### Pitfall 1: @Route and @Layout ordering
**What goes wrong:** DashboardView at `@Route("")` without specifying `layout=MainLayout.class` causes it to render without the AppLayout wrapper, breaking navigation.
**Why it happens:** Vaadin requires explicit parent layout declaration in `@Route`.
**How to avoid:** All views that should appear inside the shell use `@Route(value="X", layout=MainLayout.class)`. DashboardView: `@Route(value="", layout=MainLayout.class)`. LexiconView must be updated from `@Route("lexicon")` to `@Route(value="lexicon", layout=MainLayout.class)`.
**Warning signs:** If the SideNav drawer disappears when navigating to a route, that route is missing `layout=MainLayout.class`.

### Pitfall 2: Cytoscape.js init before element is attached
**What goes wrong:** `getElement().callJsFunction(...)` called in the Java constructor throws or silently does nothing because the element is not yet in the browser DOM.
**Why it happens:** Vaadin sends JS to the browser only after the component attaches to the UI. The constructor runs server-side before that.
**How to avoid:** Use `addAttachListener(e -> getElement().executeJs("..."))` for initialization. Push graph data after construction via a separate method.
**Warning signs:** Cytoscape container renders empty; no JS errors in browser console.

### Pitfall 3: `c.vaadin7Detected` property vs. `labels(c)` check
**What goes wrong:** Dashboard Cypher queries `c.vaadin7Detected` which returns `null` for all nodes because that property is never stored on ClassNode — it is derived from the dynamic labels at query time.
**Why it happens:** ChunkingService detects Vaadin 7 via labels at chunking time and stores it in the vector payload. The ClassNode in Neo4j does not have a `vaadin7Detected` property.
**How to avoid:** Use `ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])` in all dashboard Cypher queries. This pattern is proven in `PilotService.recommendModules()`.
**Warning signs:** DASH-01 shows 0% V7 classes across all modules.

### Pitfall 4: Large graph data serialization bottleneck
**What goes wrong:** Sending 500+ node Cytoscape.js graph as a JSON string in a single `callJsFunction` call blocks the UI thread and causes visible lag.
**Why it happens:** Vaadin serializes the string through the WebSocket; very large payloads cause noticeable delay.
**How to avoid:** The DASH-02 dependency graph starts at module-level (small number of nodes). Class-level expansion is triggered on-demand per module click — loads only classes in the clicked module. Limit module-level graph to top 200 edges (`LIMIT 200` in Cypher).
**Warning signs:** Browser freezes for 1+ seconds after graph load; `callJsFunction` takes > 500ms.

### Pitfall 5: Frontend JS file resolution
**What goes wrong:** `@JsModule("cytoscape-graph.js")` (without `./`) attempts to resolve from `node_modules` and fails because there is no `cytoscape-graph` npm package.
**Why it happens:** Without `./` prefix, Vaadin treats the path as an npm module reference.
**How to avoid:** Always prefix local frontend files with `./` — `@JsModule("./cytoscape-graph.js")` resolves from `src/main/frontend/`.
**Warning signs:** Build error "Module not found: cytoscape-graph".

### Pitfall 6: Vaadin `SerializablePredicate` in component columns
**What goes wrong:** Using `java.util.function.Predicate` instead of `com.vaadin.flow.function.SerializablePredicate` in `ListDataProvider.setFilter()` causes a compilation error.
**Why it happens:** Vaadin requires `SerializablePredicate` for Push serialization.
**How to avoid:** Follow the existing `LexiconView.java` pattern — import `com.vaadin.flow.function.SerializablePredicate`.
**Warning signs:** `incompatible types: Predicate cannot be converted to SerializablePredicate`.

---

## Code Examples

### Module summary Cypher (DASH-01 + DASH-06)
```cypher
// Source: Extended from PilotService.recommendModules() pattern (Phase 9)
MATCH (c:JavaClass)
WHERE c.module IS NOT NULL AND c.module <> ''
WITH c.module AS module,
     count(c) AS classCount,
     sum(CASE WHEN ANY(l IN labels(c)
                  WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])
              THEN 1 ELSE 0 END) AS vaadin7Count,
     avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgEnhancedRisk,
     sum(CASE WHEN coalesce(c.enhancedRiskScore, 0.0) > 0.7 THEN 1 ELSE 0 END) AS highRiskCount
RETURN module, classCount, vaadin7Count,
       toFloat(vaadin7Count) / classCount AS vaadin7Pct,
       (toFloat(vaadin7Count) / classCount) * avgEnhancedRisk AS heatmapScore,
       avgEnhancedRisk, highRiskCount
ORDER BY vaadin7Pct DESC
```

### CytoscapeGraph Java component (complete skeleton)
```java
// Source: Vaadin @NpmPackage/@JsModule docs + project pattern
@Tag("div")
@NpmPackage(value = "cytoscape", version = "3.33.1")
@JsModule("./cytoscape-graph.js")
public class CytoscapeGraph extends Component implements HasSize {

    public CytoscapeGraph() {
        setHeight("500px");
        setWidth("100%");
        addAttachListener(e ->
            getElement().executeJs("window.__initCytoscape($0)", getElement()));
    }

    public void setGraphData(String elementsJson) {
        getElement().callJsFunction("setData", elementsJson);
    }

    public Registration addNodeClickListener(
            ComponentEventListener<NodeClickEvent> listener) {
        return addListener(NodeClickEvent.class, listener);
    }

    @DomEvent("node-click")
    public static class NodeClickEvent extends ComponentEvent<CytoscapeGraph> {
        private final String nodeId;
        private final String nodeType;

        public NodeClickEvent(CytoscapeGraph source, boolean fromClient,
                @EventData("event.detail.id") String nodeId,
                @EventData("event.detail.type") String nodeType) {
            super(source, fromClient);
            this.nodeId = nodeId;
            this.nodeType = nodeType;
        }

        public String getNodeId() { return nodeId; }
        public String getNodeType() { return nodeType; }
    }
}
```

### Module-level dependency graph Cypher (DASH-02 overview)
```cypher
// Aggregate DEPENDS_ON across module boundaries for module-level graph
MATCH (c1:JavaClass)-[:DEPENDS_ON]->(c2:JavaClass)
WHERE c1.module IS NOT NULL AND c2.module IS NOT NULL
  AND c1.module <> c2.module
WITH c1.module AS source, c2.module AS target, count(*) AS weight
RETURN source, target, weight
ORDER BY weight DESC LIMIT 200
```

### Class-level drill-down Cypher (DASH-02 module expansion)
```cypher
// Called when user clicks a module node — loads classes within that module
// and their DEPENDS_ON edges within the same module
MATCH (c:JavaClass)
WHERE c.module = $module
OPTIONAL MATCH (c)-[:DEPENDS_ON]->(dep:JavaClass)
WHERE dep.module = $module
RETURN c.fullyQualifiedName AS fqn,
       c.simpleName AS simpleName,
       coalesce(c.enhancedRiskScore, 0.0) AS riskScore,
       [l IN labels(c) WHERE l <> 'JavaClass'] AS labels,
       collect(DISTINCT dep.fullyQualifiedName) AS dependsOn
```

### Business concept graph Cypher (DASH-03)
```cypher
// BusinessTerm nodes + their implementing/using classes
MATCH (t:BusinessTerm)
OPTIONAL MATCH (c:JavaClass)-[:USES_TERM|DEFINES_RULE]->(t)
WHERE c IS NOT NULL
RETURN t.termId AS termId,
       t.displayName AS displayName,
       t.criticality AS criticality,
       t.curated AS curated,
       collect(DISTINCT {fqn: c.fullyQualifiedName, simpleName: c.simpleName}) AS classes
ORDER BY t.usageCount DESC LIMIT 100
```

### DashboardService DTO records
```java
// Lean records consumed directly by DashboardView — no REST layer
public record ModuleSummary(
    String module,
    int classCount,
    int vaadin7Count,
    double vaadin7Pct,
    double heatmapScore,
    double avgEnhancedRisk,
    int highRiskCount
) {}

public record LexiconCoverage(int total, int curated, double coveragePct) {}

public record ModuleDependencyEdge(String source, String target, int weight) {}

public record BusinessTermSummary(
    String termId, String displayName, String criticality,
    boolean curated, List<String> classFqns
) {}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Vaadin 8 custom JavaScript (GWT-based) | `@NpmPackage` + `@JsModule` + Element API in Vaadin 14+ | Vaadin 14 (2019) | Clean npm integration; no GWT; works with Vite bundler in Vaadin 24 |
| `Tabs` vertical menu in AppLayout | `SideNav` + `SideNavItem` components | Vaadin 23.2 | Built-in active-state tracking, icons, hierarchical nav without manual RouterLink tabs |
| `@Layout` annotation | `@Route(value="...", layout=MainLayout.class)` | Both valid in Vaadin 24 | `layout=` parameter on `@Route` is the canonical approach; `@Layout` is an alternative added in 24.x |
| `executeJs()` for both init and function calls | `executeJs()` for init, `callJsFunction()` for repeated calls | Vaadin 14+ | `callJsFunction()` is type-safe and defers correctly; `executeJs()` is for arbitrary JS expressions |

**Deprecated/outdated:**
- `@JavaScript` annotation for loading scripts: replaced by `@JsModule` (ES modules) — do not use in new Vaadin 24 code
- `UI.getCurrent().getPage().executeJs()`: use `getElement().executeJs()` scoped to the component element

---

## Open Questions

1. **`vaadin7Detected` property in Neo4j**
   - What we know: `c.vaadin7Detected` is queried in `RagService.findFocalClassMetadata()` as a stored property, but `ClassNode.java` has no such field — it is not stored by the extraction pipeline. The Cypher query returns `null` for this property.
   - What's unclear: Whether RagService silently handles null (likely, given `instanceof Boolean b && b` null check) or whether this is a latent bug.
   - Recommendation: Dashboard queries MUST use `ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])` — the proven pattern from PilotService. Do not introduce a stored `vaadin7Detected` property in this phase.

2. **Cytoscape.js COSE layout performance for large graphs**
   - What we know: COSE is the built-in force-directed layout in Cytoscape.js. For 100+ node graphs it may be slow on first render.
   - What's unclear: How many modules the real codebase has; whether module-level graph will have 5 or 50 nodes.
   - Recommendation: Start with `cose` layout. If render time is > 2 seconds for the module overview graph, fall back to `breadthfirst` which is O(n) but less visually informative.

3. **Frontend JS wrapper ESM compatibility with Vaadin's Vite bundler**
   - What we know: Vaadin 24 uses Vite for frontend bundling. Cytoscape.js 3.33.x provides ESM exports (`cytoscape/dist/cytoscape.esm.js`). The `@JsModule` import should resolve correctly via npm.
   - What's unclear: Whether `Element.prototype.setData` assignment pattern (used in the wrapper approach) is the best API surface or if attaching methods to the element prototype creates issues with Vite's module isolation.
   - Recommendation: Use a closure-based approach instead — store the Cytoscape instance in a WeakMap keyed by the DOM element rather than extending `Element.prototype`. This is safer and compatible with strict module isolation.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers 1.20.4 |
| Config file | none (configured via `tasks.withType<Test> { useJUnitPlatform() }` in `build.gradle.kts`) |
| Quick run command | `./gradlew test --tests "com.esmp.dashboard.*" -x spotlessCheck` |
| Full suite command | `./gradlew test -x spotlessCheck` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| DASH-01 | Module V7 % aggregation returns correct counts | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testModuleSummary*" -x spotlessCheck` | Wave 0 |
| DASH-02 | Dependency graph data returns nodes and edges | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testDependencyGraph*" -x spotlessCheck` | Wave 0 |
| DASH-03 | Business concept graph returns terms with class links | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testBusinessConceptGraph*" -x spotlessCheck` | Wave 0 |
| DASH-04 | Risk cluster aggregation returns per-module risk | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testRiskClusters*" -x spotlessCheck` | Wave 0 |
| DASH-05 | Lexicon coverage % computed correctly | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testLexiconCoverage*" -x spotlessCheck` | Wave 0 |
| DASH-06 | Migration heatmap score computed correctly | integration | `./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest#testHeatmapScore*" -x spotlessCheck` | Wave 0 |
| DASH-02 (UI) | CytoscapeGraph receives node click event | unit | `./gradlew test --tests "com.esmp.ui.CytoscapeGraphTest*" -x spotlessCheck` | Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.dashboard.*" -x spotlessCheck`
- **Per wave merge:** `./gradlew test -x spotlessCheck`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/dashboard/DashboardServiceIntegrationTest.java` — covers DASH-01 through DASH-06; uses Testcontainers (Neo4j + MySQL) + pilot fixtures
- [ ] `src/test/java/com/esmp/ui/CytoscapeGraphTest.java` — unit tests for Java component event registration; WebEnvironment.MOCK required (following Phase 8 pattern)

---

## Sources

### Primary (HIGH confidence)
- Vaadin official docs — https://vaadin.com/docs/latest/flow/create-ui/web-components/java-api-for-a-web-component — `callJsFunction`, `@DomEvent`, `@EventData` patterns
- Vaadin official docs — https://vaadin.com/docs/latest/components/app-layout — AppLayout + SideNav patterns
- Vaadin official docs — https://vaadin.com/docs/latest/components/side-nav — SideNavItem constructor signatures and icon patterns
- Cytoscape.js official site — https://js.cytoscape.org/ — current version 3.33.1, layout names, event API
- Project source: `PilotService.recommendModules()` — proven Cypher pattern for `ANY(l IN labels(c) WHERE l IN ['VaadinView'...])` module aggregation
- Project source: `RiskService.getHeatmap()` — Neo4jClient query pattern with `bindAll(Map)` and `mappedBy`
- Project source: `LexiconView.java` — established Vaadin 24 view pattern with `@Route`, Grid, ListDataProvider

### Secondary (MEDIUM confidence)
- Vaadin blog — https://vaadin.com/blog/building-java-api-for-javascript-libraries — lightweight approach to wrapping non-web-component JS libraries; `@Tag("div")` + Element API pattern
- Vaadin blog — https://blog.vaadin.com/how-to-integrate-components-into-a-vaadin-flow-application — `./` prefix for local frontend files resolving to `src/main/frontend/`

### Tertiary (LOW confidence)
- None — all findings verified against official Vaadin docs or existing project code.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Vaadin 24.9.12 and Cytoscape.js 3.33.1 verified; `@NpmPackage` pattern confirmed via official docs
- Architecture: HIGH — MainLayout/AppLayout pattern from official docs; Cypher aggregations derived from existing working queries in PilotService and RiskService
- Pitfalls: HIGH — `vaadin7Detected` null issue verified by reading ClassNode.java; other pitfalls from official docs and project codebase
- Code examples: HIGH — all Cypher examples follow proven patterns from Phases 9 and 6; Java patterns from official Vaadin docs

**Research date:** 2026-03-18
**Valid until:** 2026-04-18 (Vaadin 24.9.x is stable branch; Cytoscape.js has frequent patch releases but API is stable)
