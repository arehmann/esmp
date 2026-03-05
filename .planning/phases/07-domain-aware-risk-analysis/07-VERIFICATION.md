---
phase: 07-domain-aware-risk-analysis
verified: 2026-03-05T00:00:00Z
status: passed
score: 9/9 must-haves verified
re_verification: false
---

# Phase 7: Domain-Aware Risk Analysis — Verification Report

**Phase Goal:** Add domain-aware risk scoring dimensions — domain criticality from USES_TERM edges, security sensitivity from keyword/annotation/package heuristics, financial involvement from keyword/annotation/package/USES_TERM heuristics, and business rule density from DEFINES_RULE edge counts. Extend API with domain fields and enhanced composite scoring.
**Verified:** 2026-03-05T00:00:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Every JavaClass node has a domainCriticality value derived from USES_TERM BusinessTerm criticality | VERIFIED | `computeDomainCriticality()` in RiskService.java L197-208; pattern comprehension on USES_TERM edges; CASE assigns 1.0/0.5/0.0 |
| 2 | Every JavaClass node has a securitySensitivity score from name/annotation/package heuristics | VERIFIED | `computeSecuritySensitivity()` L219-250; graduated scoring with 18 name keywords, 7 annotation keywords, 6 package keywords; `CASE WHEN rawScore > 1.0 THEN 1.0 ELSE rawScore END` clamping |
| 3 | Every JavaClass node has a financialInvolvement score from name/annotation/package/USES_TERM heuristics | VERIFIED | `computeFinancialInvolvement()` L261-301; 19 name keywords, 6 package keywords, 14 term keywords; EXISTS subquery for USES_TERM boost; neverMatch regex for annotation |
| 4 | Every JavaClass node has a businessRuleDensity value computed from DEFINES_RULE edge count | VERIFIED | `computeBusinessRuleDensity()` L310-317; `log(1.0 + ruleCount)` via pattern comprehension on DEFINES_RULE |
| 5 | GET /api/risk/heatmap response includes 5 domain score fields | VERIFIED | RiskHeatmapEntry.java L41-45 has domainCriticality/securitySensitivity/financialInvolvement/businessRuleDensity/enhancedRiskScore; mapNodeToHeatmapEntry reads all 5 via `.asDouble(0.0)` |
| 6 | GET /api/risk/heatmap?sortBy=enhanced sorts by enhancedRiskScore descending | VERIFIED | RiskController.java L62 `@RequestParam(defaultValue = "enhanced") String sortBy`; RiskService.java L388 ternary to `orderByProp`; ORDER BY concatenated at L414 |
| 7 | GET /api/risk/class/{fqn} includes domain score breakdown | VERIFIED | RiskDetailResponse.java L41-46 has 5 domain fields before methods; mapNodeToDetailResponse L488-506 reads all 5 |
| 8 | DomainRiskValidationQueryRegistry provides 3 domain risk validation queries | VERIFIED | DomainRiskValidationQueryRegistry.java: DOMAIN_SCORES_POPULATED (ERROR), HIGH_DOMAIN_RISK_NO_BUSINESS_TERMS (WARNING), SECURITY_FINANCIAL_FLAGGED (WARNING) |
| 9 | 15 integration tests pass covering all DRISK-01 through DRISK-05 requirements | VERIFIED | DomainRiskServiceIntegrationTest.java 624 lines, 15 @Test methods; covers all 5 DRISK requirements + 3 API assertions; 16 total annotations where line 42 is @Testcontainers |

**Score:** 9/9 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/extraction/model/ClassNode.java` | 5 new domain risk properties with getters/setters | VERIFIED | Lines 114-149 Phase 7 section; domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity, enhancedRiskScore; 10 getter/setter pairs added (L357-395) |
| `src/main/java/com/esmp/extraction/config/RiskWeightConfig.java` | 8 new enhanced weight fields with correct defaults summing to 1.0 | VERIFIED | Lines 50-75; domainComplexity=0.24, domainFanIn=0.12, domainFanOut=0.12, domainDbWrites=0.12, domainCriticality=0.10, securitySensitivity=0.10, financialInvolvement=0.10, businessRuleDensity=0.10; sum=1.0; 16 getter/setter pairs L109-171 |
| `src/main/java/com/esmp/graph/application/RiskService.java` | 5 computation methods + buildPattern helper + keyword constants | VERIFIED | computeDomainCriticality, computeSecuritySensitivity, computeFinancialInvolvement, computeBusinessRuleDensity, computeEnhancedRiskScore; buildPattern() L363-365; 6 static keyword lists + 5 scoring constants L69-106 |
| `src/main/resources/application.yml` | 8 domain weight defaults under esmp.risk.weight | VERIFIED | Lines 53-60; all 8 kebab-case keys present matching Spring relaxed binding to RiskWeightConfig fields |
| `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` | java_class_enhanced_risk_score range index | VERIFIED | Lines 80-82 create index IF NOT EXISTS for n.enhancedRiskScore |
| `src/main/java/com/esmp/graph/api/RiskHeatmapEntry.java` | Extended heatmap record with 5 domain fields | VERIFIED | Lines 41-45; all 5 domain fields appended after stereotypeLabels with full Javadoc |
| `src/main/java/com/esmp/graph/api/RiskDetailResponse.java` | Extended detail record with 5 domain fields | VERIFIED | Lines 41-46; 5 domain fields between stereotypeLabels and methods |
| `src/main/java/com/esmp/graph/api/RiskController.java` | sortBy parameter on heatmap endpoint | VERIFIED | Line 62 `@RequestParam(defaultValue = "enhanced") String sortBy`; passed to riskService.getHeatmap at L63 |
| `src/main/java/com/esmp/graph/validation/DomainRiskValidationQueryRegistry.java` | 3 domain risk validation queries | VERIFIED | 73 lines; @Component; extends ValidationQueryRegistry; DOMAIN_SCORES_POPULATED at L33, HIGH_DOMAIN_RISK_NO_BUSINESS_TERMS at L47, SECURITY_FINANCIAL_FLAGGED at L62 |
| `src/test/java/com/esmp/graph/application/DomainRiskServiceIntegrationTest.java` | 15 integration tests (min 150 lines) | VERIFIED | 624 lines; 15 @Test methods; 4 helper methods for test data (createBusinessTerm, createUsesTermEdge, createDefinesRuleEdge, createClassNodeWithAnnotations) |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `RiskService.java` | Neo4j JavaClass nodes | Cypher SET for computeDomainCriticality | WIRED | L197-208: `neo4jClient.query(cypher).run()` |
| `RiskService.java` | Neo4j JavaClass nodes | Cypher SET for computeSecuritySensitivity | WIRED | L219-250: query + `bindAll()` with 7 parameters + `.run()` |
| `RiskService.java` | Neo4j JavaClass nodes | Cypher SET for computeFinancialInvolvement | WIRED | L261-301: query + `bindAll()` with 10 parameters + `.run()` |
| `RiskService.java` | Neo4j JavaClass nodes | Cypher SET for computeBusinessRuleDensity | WIRED | L310-317: `neo4jClient.query(cypher).run()` |
| `RiskService.java` | Neo4j JavaClass nodes | Cypher SET for computeEnhancedRiskScore | WIRED | L327-352: query + `bindAll()` with 8 RiskWeightConfig values + `.run()` |
| `RiskService.java` | `RiskWeightConfig.java` | Spring DI + 8 getDomainX() calls in computeEnhancedRiskScore | WIRED | L343-350: getDomainComplexity(), getDomainFanIn(), getDomainFanOut(), getDomainDbWrites(), getDomainCriticality(), getSecuritySensitivity(), getFinancialInvolvement(), getBusinessRuleDensity() |
| `RiskController.java` | `RiskService.java` | getHeatmap with sortBy parameter | WIRED | L63: `riskService.getHeatmap(module, packageName, stereotype, limit, sortBy)` |
| `RiskService.java` | `RiskHeatmapEntry.java` | mapNodeToHeatmapEntry reads 5 domain properties | WIRED | L468-485: node.get("domainCriticality"), node.get("securitySensitivity"), etc. all present with `.asDouble(0.0)` defaults |
| `DomainRiskServiceIntegrationTest.java` | RiskService + RiskController | riskService.computeAndPersistRiskScores() + MockMvc | WIRED | L235, L257, L280, L310, L332, L358, L384, L408, L453, L474, L505, L531, L571, L591, L611 |
| `computeAndPersistRiskScores()` | 5 domain methods | Sequential calls | WIRED | L134-148: computeDomainCriticality, computeSecuritySensitivity, computeFinancialInvolvement, computeBusinessRuleDensity, computeEnhancedRiskScore called in correct dependency order |

---

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
|-------------|---------------|-------------|--------|----------|
| DRISK-01 | 07-01, 07-02 | System computes domain criticality per class from USES_TERM BusinessTerm criticality | SATISFIED | computeDomainCriticality() uses pattern comprehension on USES_TERM edges; High=1.0, Medium=0.5, none=0.0; 3 integration tests (domainCriticality_highForClassWithHighBusinessTerm, domainCriticality_zeroForClassWithNoTerms, domainCriticality_zeroForClassWithOnlyLowTerms) |
| DRISK-02 | 07-01, 07-02 | System scores security sensitivity for classes handling authentication, authorization, or encryption | SATISFIED | computeSecuritySensitivity() with 18 name + 7 annotation + 6 package keywords; graduated scoring NAME_HIT_WEIGHT=0.3, ANNOT_HIT_WEIGHT=0.5, BOTH_HIT_BONUS=0.2, PKG_HIT_BOOST=0.2; 3 integration tests |
| DRISK-03 | 07-01, 07-02 | System scores financial involvement for classes in payment, billing, or ledger operations | SATISFIED | computeFinancialInvolvement() with 19 name + 6 package + 14 term keywords; USES_TERM boost via EXISTS subquery; 2 integration tests including boost comparison |
| DRISK-04 | 07-01, 07-02 | System computes business rule density per class from DEFINES_RULE edge count | SATISFIED | computeBusinessRuleDensity() uses log(1.0 + ruleCount); 2 integration tests (log(4) for 3 rules, 0.0 for no rules) |
| DRISK-05 | 07-01, 07-02 | System produces enhanced composite risk score combining structural risk, domain criticality, security sensitivity, financial involvement, and business rule density | SATISFIED | computeEnhancedRiskScore() uses all 8 raw dimensions with domain weights from RiskWeightConfig; 2 integration tests (nonNull for all classes, higher for domain-critical class); neo4j index on enhancedRiskScore |

All 5 DRISK requirements are SATISFIED. No orphaned requirements detected — all 5 were claimed by plans 07-01 and 07-02.

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `RiskService.java` | 446 | `return null` | INFO | Lambda returns null for null Neo4j list values; immediately filtered by `.filter(e -> e != null ...)` at L456. Not a stub — intentional null-safety guard in the method collection mapper. No impact on goal. |

No blockers. No stubs. No placeholder implementations found.

---

### Human Verification Required

None. All observable truths can be verified programmatically via code inspection and test coverage. Integration tests use Testcontainers to exercise the actual Neo4j Cypher queries, providing high confidence in end-to-end correctness.

---

### Summary

Phase 7 goal is fully achieved. All 4 domain risk dimensions are implemented as real Cypher computation methods in RiskService with correct graph traversal patterns:

- **DRISK-01 (Domain Criticality):** Pattern comprehension on USES_TERM edges correctly assigns 1.0/0.5/0.0 based on BusinessTerm.criticality values.
- **DRISK-02 (Security Sensitivity):** Graduated heuristic with 31 keywords across name/annotation/package dimensions; correctly fixed the Cypher `min()` aggregation bug (documented in 07-02 SUMMARY) using CASE WHEN clamping.
- **DRISK-03 (Financial Involvement):** Same graduated heuristic plus USES_TERM boost via EXISTS subquery; neverMatch regex correctly skips annotation matching.
- **DRISK-04 (Business Rule Density):** log-normalization of DEFINES_RULE edge count via pattern comprehension.
- **DRISK-05 (Enhanced Composite Score):** 8-dimension formula using raw structural properties re-weighted by domain weights; avoids double-weighting by not using structuralRiskScore as input.

API layer is fully wired: RiskHeatmapEntry and RiskDetailResponse both carry all 5 domain fields; RiskController accepts sortBy parameter defaulting to "enhanced"; RiskService mappers read all domain properties from Neo4j nodes with `.asDouble(0.0)` safety defaults.

DomainRiskValidationQueryRegistry is a properly discovered @Component (extends ValidationQueryRegistry) adding 3 validation queries, bringing the total validation query count to 29.

15 integration tests with Testcontainers (Neo4j 2026.01.4, MySQL 8.4, Qdrant) provide end-to-end verification of all DRISK requirements and API behavior. Test file is 624 lines, well above the 150-line minimum.

Phase 6 structural risk computation (computeFanInOut, computeStructuralRiskScore, 4 original weights) is unchanged — backward compatibility preserved.

---

_Verified: 2026-03-05T00:00:00Z_
_Verifier: Claude (gsd-verifier)_
