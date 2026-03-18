---
phase: 13-risk-prioritized-scheduling
plan: 02
subsystem: ui
tags: [vaadin, cytoscape, scheduling, wave-visualization]

# Dependency graph
requires:
  - phase: 13-risk-prioritized-scheduling
    plan: 01
    provides: SchedulingService.recommend(), ScheduleResponse, WaveGroup, ModuleSchedule
  - phase: 12-governance-dashboard
    provides: DashboardService.getModuleDependencyEdges(), CytoscapeGraph component

provides:
  - ScheduleView at /schedule with wave lane visualization and table toggle
  - Module drill-down with score breakdown and wave-colored CytoscapeGraph
  - MainLayout sidebar updated with Schedule nav item

affects:
  - MainLayout (added third nav item)
  - All Vaadin views (accessible via updated sidebar)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Wave card color by finalScore threshold (<0.3 green, <0.6 amber, else red)
    - CytoscapeGraph node color by wave relative to selected (earlier=green, same=blue, later=red)
    - loadSafe helper for resilient data loading without crashing the UI
    - Grid<ModuleSchedule> with .setComparator() for formatted-string columns still sortable by value

key-files:
  created:
    - src/main/java/com/esmp/ui/ScheduleView.java
  modified:
    - src/main/java/com/esmp/ui/MainLayout.java

key-decisions:
  - "ScheduleView injects both SchedulingService and DashboardService — scheduling for wave data, dashboard for dependency edges used in drill-down CytoscapeGraph"
  - "Wave card background is a pastel color (green/amber/red) matching finalScore thresholds for instant risk legibility"
  - "drillDownGraph is a shared CytoscapeGraph instance on the detailPanel — reused across module selections to avoid creating multiple Cytoscape instances"

requirements-completed: [SCHED-01, SCHED-02]

# Metrics
duration: 10min
completed: 2026-03-19
---

# Phase 13 Plan 02: ScheduleView UI Summary

**Vaadin ScheduleView at /schedule with wave lane cards, sortable table toggle, module drill-down showing score breakdown and wave-colored CytoscapeGraph, and MainLayout sidebar updated with Schedule nav item**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-03-18T23:07:23Z
- **Completed:** 2026-03-19
- **Tasks completed before checkpoint:** 1 of 2
- **Files modified:** 2

## Accomplishments

- ScheduleView: `@Route(value = "schedule", layout = MainLayout.class)` with `@PageTitle("Migration Schedule")`
- Wave View: FlexLayout of module cards per WaveGroup, card background tinted by finalScore risk level (green < 0.3, amber < 0.6, red >= 0.6)
- Table View: sortable `Grid<ModuleSchedule>` with 9 columns — Wave, Module, Score, Risk, Dependents, Commits, Avg CC, Classes, Rationale
- Module drill-down: score contribution breakdown (risk/dependency/frequency/complexity/final) + italic rationale + CytoscapeGraph with wave-colored nodes (green=earlier wave, blue=current, red=later wave) + legend
- MainLayout: added `new SideNavItem("Schedule", ScheduleView.class, VaadinIcon.CALENDAR.create())` as third sidebar item
- `./gradlew compileJava -x vaadinPrepareFrontend` passes cleanly
- All scheduling tests pass (9 tests: 3 unit + 6 integration)

## Task Commits

1. **Task 1: ScheduleView and MainLayout update** - `cc62b9e` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/ui/ScheduleView.java` — wave lane view + table view + drill-down with CytoscapeGraph
- `src/main/java/com/esmp/ui/MainLayout.java` — added Schedule SideNavItem

## Decisions Made

- ScheduleView injects both SchedulingService (wave/scheduling data) and DashboardService (dependency edges for drill-down graph)
- Wave card colors are pastel backgrounds, not text colors, for readability at a glance
- Shared `drillDownGraph` CytoscapeGraph instance reused across module selections to avoid spawning multiple Cytoscape.js instances

## Deviations from Plan

None — plan executed exactly as written. All acceptance criteria met on first attempt.

## Checkpoint: Human Verification — APPROVED

Task 2 was a `checkpoint:human-verify` gate. The UI was built and compiles cleanly. Human verification confirmed the full scheduling system works end-to-end:
- /schedule page loads with Generate Schedule button
- Wave lanes appear with module cards colored by risk
- Table view toggle shows sortable grid with all required columns
- Module drill-down shows score breakdown and CytoscapeGraph with wave-colored nodes
- Sidebar shows Dashboard, Lexicon, Schedule
- REST API returns valid ScheduleResponse JSON

## Task Commits

1. **Task 1: ScheduleView and MainLayout update** - `cc62b9e` (feat)
2. **Task 2: End-to-end verification** - human-approved (no code commit)

---
*Phase: 13-risk-prioritized-scheduling*
*Completed: 2026-03-19*

## Self-Check

### Files created/modified:
- FOUND: src/main/java/com/esmp/ui/ScheduleView.java
- FOUND: src/main/java/com/esmp/ui/MainLayout.java (modified)

### Commits exist:
- FOUND: cc62b9e

## Self-Check: PASSED
