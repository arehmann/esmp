---
phase: 12-governance-dashboard
plan: 02
subsystem: ui
tags: [vaadin, cytoscape, applayout, sidenav, frontend-component, graph-visualization]
dependency_graph:
  requires: [dashboard-data-layer]
  provides: [main-layout-shell, cytoscape-graph-component, lexicon-routing]
  affects: [dashboard-ui-plan-03]
tech_stack:
  added: [cytoscape@3.33.1 (npm via @NpmPackage)]
  patterns: [AppLayout-shell, WeakMap-instance-management, DomEvent-@EventData, addAttachListener-init, executeJs-element-ref]
key_files:
  created:
    - src/main/java/com/esmp/ui/MainLayout.java
    - src/main/java/com/esmp/ui/CytoscapeGraph.java
    - src/main/frontend/cytoscape-graph.js
    - src/main/java/com/esmp/ui/DashboardView.java
  modified:
    - src/main/java/com/esmp/ui/LexiconView.java
decisions:
  - "DashboardView stub created in Plan 02 so MainLayout.class compiles — full implementation in Plan 03"
  - "CytoscapeGraph uses executeJs with window.__setCytoscapeData($0,$1) passing element ref to WeakMap lookup instead of callJsFunction"
  - "addAttachListener ensures Cytoscape.js is only initialized after DOM element exists in browser"
metrics:
  duration: 34min
  completed: 2026-03-18
  tasks_completed: 2
  files_created: 4
  files_modified: 1
---

# Phase 12 Plan 02: MainLayout Shell and CytoscapeGraph Component Summary

AppLayout navigation shell with SideNav, reusable CytoscapeGraph wrapper for Cytoscape.js 3.33.1, and updated LexiconView routing through MainLayout.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Create MainLayout AppLayout shell and update LexiconView routing | 389fed4 | MainLayout.java, LexiconView.java (modified), DashboardView.java (stub) |
| 2 | Create CytoscapeGraph Java component and frontend JS wrapper | 96a0054 | CytoscapeGraph.java, cytoscape-graph.js |

## What Was Built

**MainLayout** in `com.esmp.ui`:
- Extends `AppLayout` with `DrawerToggle` + `H1("ESMP")` in navbar
- `SideNav` with two items: Dashboard (`VaadinIcon.DASHBOARD`) and Lexicon (`VaadinIcon.BOOK`)
- Wrapped in `Scroller` for overflow handling
- No `@Route` annotation — used as `layout=MainLayout.class` by child views

**LexiconView routing updated:**
- `@Route("lexicon")` → `@Route(value = "lexicon", layout = MainLayout.class)`
- All existing functionality preserved — no other changes

**DashboardView stub** in `com.esmp.ui`:
- Empty `Div` subclass with `@Route(value = "", layout = MainLayout.class)`
- Required so `MainLayout` can reference `DashboardView.class` at compile time
- Plan 03 will replace this with the full dashboard implementation

**CytoscapeGraph** in `com.esmp.ui`:
- `@Tag("div")` + `@NpmPackage(value="cytoscape", version="3.33.1")` + `@JsModule("./cytoscape-graph.js")`
- `setGraphData(String elementsJson)` — pushes Cytoscape.js elements JSON to browser via `executeJs`
- `addNodeClickListener(ComponentEventListener<NodeClickEvent>)` — returns `Registration`
- `NodeClickEvent` inner static class with `@DomEvent("node-click")` capturing `nodeId`, `nodeType`, `nodeLabel` via `@EventData`
- `addAttachListener` ensures Cytoscape.js initializes only after DOM element is rendered

**cytoscape-graph.js** in `src/main/frontend/`:
- `WeakMap<Element, CytoscapeInstance>` for safe per-element instance management
- `window.__initCytoscape(element)` — idempotent init guard, sets up node styles with `data(color)`, `data(size)`, and edge styles; registers `tap node` → `dispatchEvent(node-click CustomEvent)`
- `window.__setCytoscapeData(element, elementsJson)` — parses JSON, replaces graph elements, re-runs cose layout, fits viewport

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created DashboardView stub for compile-time reference**
- **Found during:** Task 1
- **Issue:** `MainLayout` references `DashboardView.class` which does not exist until Plan 03. This causes a compile error.
- **Fix:** Created a minimal `DashboardView extends Div` stub with `@Route(value = "", layout = MainLayout.class)` so `MainLayout` compiles. Plan 03 will replace the stub with the full implementation.
- **Files modified:** `src/main/java/com/esmp/ui/DashboardView.java` (created)
- **Commit:** 389fed4

### Out-of-scope (deferred)

- `vaadinPrepareFrontend` Gradle task fails with reflections NPE (`Cannot invoke "Class.isInterface()"`). This failure is pre-existing (reproduces on the HEAD prior to Plan 02 changes) and is unrelated to any Plan 02 file. Documented in deferred-items.

## Self-Check: PASSED

Files exist:
- src/main/java/com/esmp/ui/MainLayout.java — FOUND
- src/main/java/com/esmp/ui/CytoscapeGraph.java — FOUND
- src/main/frontend/cytoscape-graph.js — FOUND
- src/main/java/com/esmp/ui/DashboardView.java — FOUND
- src/main/java/com/esmp/ui/LexiconView.java (modified) — FOUND

Commits exist:
- 389fed4 — feat(12-02): create MainLayout AppLayout shell and update LexiconView routing — FOUND
- 96a0054 — feat(12-02): create CytoscapeGraph component and cytoscape-graph.js frontend bridge — FOUND

Acceptance criteria:
- MainLayout extends AppLayout — PASS
- SideNav with Dashboard and Lexicon items — PASS
- No @Route on MainLayout — PASS
- LexiconView uses layout = MainLayout.class — PASS
- CytoscapeGraph @Tag("div") + @NpmPackage(cytoscape 3.33.1) + @JsModule — PASS
- setGraphData method — PASS
- addNodeClickListener + @DomEvent("node-click") — PASS
- addAttachListener + window.__initCytoscape — PASS
- cytoscape-graph.js with WeakMap, __initCytoscape, __setCytoscapeData, node-click, dispatchEvent — PASS
- ./gradlew compileJava -x spotlessCheck exits 0 — PASS
