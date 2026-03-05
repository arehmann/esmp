---
phase: 07-domain-aware-risk-analysis
plan: 02
subsystem: risk-api
tags: [domain-risk, risk-api, integration-tests, neo4j, cypher, validation]
dependency_graph:
  requires: [07-01]
  provides: [domain-risk-api, domain-risk-validation, domain-risk-integration-tests]
  affects: [RiskController, RiskService, RiskHeatmapEntry, RiskDetailResponse, DomainRiskValidationQueryRegistry]
tech_stack:
  added: []
  patterns: [tdd-integration-tests, testcontainers, mockmvc-assertions, cypher-case-clamping]
key_files:
  created:
    - src/main/java/com/esmp/graph/validation/DomainRiskValidationQueryRegistry.java
    - src/test/java/com/esmp/graph/application/DomainRiskServiceIntegrationTest.java
  modified:
    - src/main/java/com/esmp/graph/api/RiskHeatmapEntry.java
    - src/main/java/com/esmp/graph/api/RiskDetailResponse.java
    - src/main/java/com/esmp/graph/api/RiskController.java
    - src/main/java/com/esmp/graph/application/RiskService.java
decisions:
  - "[Phase 07-domain-aware-risk-analysis]: Cypher min() is an aggregation function — scalar clamping to 1.0 uses CASE WHEN rawScore > 1.0 THEN 1.0 ELSE rawScore END with an intermediate WITH clause computing rawScore"
  - "[Phase 07-domain-aware-risk-analysis]: sortBy in getHeatmap validates to one of two hardcoded Java strings (structural/enhanced), then uses string concatenation for ORDER BY property name — safe because orderByProp is never user-supplied"
metrics:
  duration: 28min
  completed_date: "2026-03-05"
  tasks_completed: 2
  files_modified: 6
---

# Phase 7 Plan 02: Domain Risk API Extension and Integration Tests Summary

Extended the risk REST API with 5 domain score fields and a `sortBy` parameter, added `DomainRiskValidationQueryRegistry` with 3 queries, and created 15 integration tests covering all DRISK-01 through DRISK-05 requirements. Fixed a Cypher `min()` aggregation bug in `computeSecuritySensitivity` and `computeFinancialInvolvement` that prevented domain score computation from running.

## What Was Built

### Task 1: API Records, Controller, Service Mappers, Validation Registry

**RiskHeatmapEntry** (`graph/api/RiskHeatmapEntry.java`): 5 new domain fields appended after `stereotypeLabels`:
- `domainCriticality` (double): 0.0–1.0 from USES_TERM BusinessTerm criticality
- `securitySensitivity` (double): 0.0–1.0 from keyword/annotation/package heuristics
- `financialInvolvement` (double): 0.0–1.0 from keyword/package/USES_TERM heuristics
- `businessRuleDensity` (double): log-normalized DEFINES_RULE count
- `enhancedRiskScore` (double): 8-dimension composite score

**RiskDetailResponse** (`graph/api/RiskDetailResponse.java`): Same 5 domain fields inserted between `stereotypeLabels` and `methods` (methods must remain last per plan specification).

**RiskController** (`graph/api/RiskController.java`): Added `sortBy` parameter with default `"enhanced"` to the `GET /api/risk/heatmap` endpoint. `"structural"` sorts by `structuralRiskScore DESC`, anything else (including default `"enhanced"`) sorts by `enhancedRiskScore DESC`.

**RiskService** (`graph/application/RiskService.java`):
- `getHeatmap()` signature extended with `sortBy` 5th parameter
- `orderByProp` computed from `sortBy` value using Java ternary before Cypher string construction (safe: value is one of two hardcoded Java strings)
- `mapNodeToHeatmapEntry()` reads 5 new domain properties from Neo4j node with `.asDouble(0.0)` defaults
- `mapNodeToDetailResponse()` reads same 5 new domain properties before `methods`
- Fixed Cypher aggregation bug (see Deviations)

**DomainRiskValidationQueryRegistry** (`graph/validation/DomainRiskValidationQueryRegistry.java`): New `@Component` extending `ValidationQueryRegistry` with 3 queries:
1. **DOMAIN_SCORES_POPULATED** (ERROR): JavaClass nodes where `enhancedRiskScore IS NULL`
2. **HIGH_DOMAIN_RISK_NO_BUSINESS_TERMS** (WARNING): Classes with `domainCriticality > 0` but no USES_TERM edges
3. **SECURITY_FINANCIAL_FLAGGED** (WARNING): Total classes with non-zero security or financial scores (sanity check)

Total validation queries: **29** (20 Phase 4 + 3 lexicon + 3 structural risk + 3 domain risk).

### Task 2: DomainRiskServiceIntegrationTest (TDD)

**15 integration tests** covering all DRISK requirements:

| Test | Requirement | Assertion |
|------|-------------|-----------|
| `domainCriticality_highForClassWithHighBusinessTerm` | DRISK-01 | domainCriticality=1.0 |
| `domainCriticality_zeroForClassWithNoTerms` | DRISK-01 | domainCriticality=0.0 |
| `domainCriticality_zeroForClassWithOnlyLowTerms` | DRISK-01 | domainCriticality=0.0 |
| `securitySensitivity_nonZeroForAuthNamedClass` | DRISK-02 | securitySensitivity > 0 |
| `securitySensitivity_nonZeroForSecuredAnnotation` | DRISK-02 | securitySensitivity > 0 |
| `securitySensitivity_zeroForPlainClass` | DRISK-02 | securitySensitivity=0.0 |
| `financialInvolvement_nonZeroForPaymentNamedClass` | DRISK-03 | financialInvolvement > 0 |
| `financialInvolvement_boostedByFinancialTerm` | DRISK-03 | USES_TERM class > non-USES_TERM class |
| `businessRuleDensity_logNormalizedFromDefinesRule` | DRISK-04 | density=log(4) for 3 DEFINES_RULE |
| `businessRuleDensity_zeroForNoRules` | DRISK-04 | density=0.0 |
| `enhancedScore_nonNullForAllClasses` | DRISK-05 | NULL count=0 after computation |
| `enhancedScore_higherForDomainCriticalClass` | DRISK-05 | domain-critical > plain (same structural) |
| `heatmap_sortByEnhanced` | API | first entry = critical class |
| `heatmap_includesDomainFields` | API | 5 domain fields in JSON response |
| `classDetail_includesDomainBreakdown` | API | 5 domain fields in detail response |

Test infrastructure: same Testcontainers pattern as `RiskServiceIntegrationTest` (Neo4j 2026.01.4, MySQL 8.4, Qdrant). New helpers: `createBusinessTerm`, `createUsesTermEdge`, `createDefinesRuleEdge`, `createClassNodeWithAnnotations`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed Cypher min() aggregation function misuse in computeSecuritySensitivity and computeFinancialInvolvement**
- **Found during:** Task 2 (GREEN phase — all 15 tests failed with `InvalidDataAccessResourceUsageException`)
- **Issue:** Both methods used `min(1.0, rawScore)` in a Cypher SET expression. In Cypher, `min()` is an aggregation function (like SQL `MIN()`), not a scalar mathematical min. Its use outside an aggregation context produces a `Neo.ClientError.Statement.SyntaxError`.
- **Fix:** Introduced an intermediate `WITH c, nameHit, annotHit, pkgHit, ... AS rawScore` clause, then used `CASE WHEN rawScore > 1.0 THEN 1.0 ELSE rawScore END` for scalar clamping.
- **Files modified:** `src/main/java/com/esmp/graph/application/RiskService.java`
- **Commit:** b88d587

**Note on pre-existing failures:** The full test suite reports 18 failures, but all 18 are in `LexiconIntegrationTest` (9) and `LinkingServiceIntegrationTest` (9) which were already failing before Plan 02 changes (verified by git stash test). Out-of-scope per deviation rules — logged here for traceability.

## Self-Check

- [x] `src/main/java/com/esmp/graph/api/RiskHeatmapEntry.java` — 5 new domain fields
- [x] `src/main/java/com/esmp/graph/api/RiskDetailResponse.java` — 5 new domain fields before methods
- [x] `src/main/java/com/esmp/graph/api/RiskController.java` — sortBy parameter
- [x] `src/main/java/com/esmp/graph/application/RiskService.java` — sortBy in getHeatmap, domain fields in mappers, Cypher fix
- [x] `src/main/java/com/esmp/graph/validation/DomainRiskValidationQueryRegistry.java` — 3 domain risk queries
- [x] `src/test/java/com/esmp/graph/application/DomainRiskServiceIntegrationTest.java` — 15 integration tests
- [x] Commit bea6e3f: feat(07-02): extend API records, controller, service mappers, and add domain risk validation
- [x] Commit c62064b: test(07-02): add failing integration tests for domain-aware risk scoring
- [x] Commit b88d587: feat(07-02): implement domain risk integration tests and fix Cypher min() aggregation bug
- [x] `./gradlew test --tests "com.esmp.graph.application.*"` — BUILD SUCCESSFUL (24 tests, all pass)

## Self-Check: PASSED
