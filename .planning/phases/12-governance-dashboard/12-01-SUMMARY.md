---
phase: 12-governance-dashboard
plan: 01
subsystem: dashboard
tags: [neo4j, cypher, aggregation, dashboard, dto, integration-test]
dependency_graph:
  requires: [extraction, graph, pilot]
  provides: [dashboard-data-layer]
  affects: [dashboard-ui-plan-02]
tech_stack:
  added: []
  patterns: [neo4jClient.query().fetchAs().mappedBy(), static-setUpDone-guard, TDD-red-green]
key_files:
  created:
    - src/main/java/com/esmp/dashboard/api/ModuleSummary.java
    - src/main/java/com/esmp/dashboard/api/LexiconCoverage.java
    - src/main/java/com/esmp/dashboard/api/RiskCluster.java
    - src/main/java/com/esmp/dashboard/api/ModuleDependencyEdge.java
    - src/main/java/com/esmp/dashboard/api/ClassDetail.java
    - src/main/java/com/esmp/dashboard/api/BusinessTermSummary.java
    - src/main/java/com/esmp/dashboard/application/DashboardService.java
    - src/test/java/com/esmp/dashboard/DashboardServiceIntegrationTest.java
  modified: []
decisions:
  - "DashboardService uses Neo4jClient Cypher aggregation exclusively — no Java-side grouping loops"
  - "V7 detection uses labels() pattern (VaadinView/VaadinComponent/VaadinDataBinding) not c.vaadin7Detected property"
  - "getBusinessTermGraph ORDER BY aliases usageCount in RETURN clause — cannot reference pre-aggregation variable t after collect(DISTINCT)"
  - "LexiconCoverage.one().orElse() returns empty default when no BusinessTerm nodes exist"
metrics:
  duration: 7min
  completed: 2026-03-18
  tasks_completed: 2
  files_created: 8
---

# Phase 12 Plan 01: DashboardService Data Layer Summary

DashboardService with 6 Neo4j Cypher aggregation methods and 6 DTO records providing all data needed by the governance dashboard UI (Plan 02).

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Create DTO records and DashboardService | 3d50672 | 7 files created |
| 1 (fix) | Fix Cypher ORDER BY scope bug | f384abd | DashboardService.java |
| 2 RED | Integration tests (TDD RED) | 4079dc2 | DashboardServiceIntegrationTest.java |
| 2 GREEN | All 7 tests pass (TDD GREEN) | f384abd | (same commit as fix) |

## What Was Built

**6 DTO records** in `com.esmp.dashboard.api`:
- `ModuleSummary` — module name, classCount, vaadin7Count, vaadin7Pct, heatmapScore, avgEnhancedRisk, highRiskCount
- `LexiconCoverage` — total, curated, coveragePct
- `RiskCluster` — module, classCount, avgRisk, maxRisk, highRiskCount
- `ModuleDependencyEdge` — source, target, weight
- `ClassDetail` — fqn, simpleName, riskScore, labels, dependsOn
- `BusinessTermSummary` — termId, displayName, criticality, curated, classFqns

**DashboardService** in `com.esmp.dashboard.application` with 6 methods:
- `getModuleSummaries()` — DASH-01: V7 density + heatmap score per module, ordered by vaadin7Pct DESC
- `getLexiconCoverage()` — DASH-06: total/curated term counts + percentage
- `getRiskClusters()` — DASH-05: avg/max/highRiskCount per module, ordered by avgRisk DESC
- `getModuleDependencyEdges()` — DASH-03: cross-module DEPENDS_ON edge weights, limit 200
- `getClassesInModule(String)` — DASH-02: class-level drill-down with intra-module deps
- `getBusinessTermGraph()` — DASH-04: business terms with linked class FQNs via USES_TERM/DEFINES_RULE

**7 Integration Tests** in `DashboardServiceIntegrationTest`:
- testModuleSummaryReturnsVaadin7Percentages
- testHeatmapScore (validates formula: heatmapScore = vaadin7Pct * avgEnhancedRisk)
- testLexiconCoverageReturnsTermCounts
- testRiskClustersReturnModuleData
- testDependencyGraphModuleDependencyEdges
- testModuleSummaryClassDrillDown
- testBusinessConceptGraphReturnsTermsWithClasses

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed Cypher ORDER BY variable scope in getBusinessTermGraph**
- **Found during:** Task 2 (TDD RED test run)
- **Issue:** `ORDER BY t.usageCount DESC` after a `RETURN` clause with `collect(DISTINCT ...)` is invalid in Cypher — the variable `t` is no longer in scope after the aggregation
- **Fix:** Added `coalesce(t.usageCount, 0) AS usageCount` to the RETURN clause and changed ORDER BY to reference the alias `usageCount`
- **Files modified:** `src/main/java/com/esmp/dashboard/application/DashboardService.java`
- **Commit:** f384abd

## Self-Check: PASSED

Files exist:
- src/main/java/com/esmp/dashboard/api/ModuleSummary.java — FOUND
- src/main/java/com/esmp/dashboard/api/LexiconCoverage.java — FOUND
- src/main/java/com/esmp/dashboard/api/RiskCluster.java — FOUND
- src/main/java/com/esmp/dashboard/api/ModuleDependencyEdge.java — FOUND
- src/main/java/com/esmp/dashboard/api/ClassDetail.java — FOUND
- src/main/java/com/esmp/dashboard/api/BusinessTermSummary.java — FOUND
- src/main/java/com/esmp/dashboard/application/DashboardService.java — FOUND
- src/test/java/com/esmp/dashboard/DashboardServiceIntegrationTest.java — FOUND

Commits exist:
- 3d50672 — feat(12-01): create DashboardService with 6 Neo4j Cypher aggregation methods — FOUND
- 4079dc2 — test(12-01): add failing integration tests for DashboardService (TDD RED) — FOUND
- f384abd — feat(12-01): fix Cypher ORDER BY scope in getBusinessTermGraph, all 7 tests pass (TDD GREEN) — FOUND

All 7 tests pass: ./gradlew test --tests "com.esmp.dashboard.DashboardServiceIntegrationTest" — BUILD SUCCESSFUL
