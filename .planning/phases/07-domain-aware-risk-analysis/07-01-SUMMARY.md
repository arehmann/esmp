---
phase: 07-domain-aware-risk-analysis
plan: 01
subsystem: risk-scoring
tags: [domain-risk, risk-scoring, neo4j, cypher, structural-analysis]
dependency_graph:
  requires: [06-02]
  provides: [enhanced-risk-score, domain-criticality, security-sensitivity, financial-involvement, business-rule-density]
  affects: [RiskService, ClassNode, RiskWeightConfig, Neo4jSchemaInitializer]
tech_stack:
  added: []
  patterns: [cypher-pattern-comprehension, graduated-heuristic-scoring, log-normalization, configurable-weights]
key_files:
  created: []
  modified:
    - src/main/java/com/esmp/extraction/model/ClassNode.java
    - src/main/java/com/esmp/extraction/config/RiskWeightConfig.java
    - src/main/java/com/esmp/graph/application/RiskService.java
    - src/main/resources/application.yml
    - src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java
decisions:
  - "[Phase 07-domain-aware-risk-analysis]: computeEnhancedRiskScore uses raw structural metrics re-weighted by domain weights — NOT structuralRiskScore — to avoid double-weighting the structural component"
  - "[Phase 07-domain-aware-risk-analysis]: Financial involvement skips annotation matching (neverMatch regex) since no financial-specific Java security annotations exist — name/package/USES_TERM keywords are sufficient"
  - "[Phase 07-domain-aware-risk-analysis]: computeSecuritySensitivity and computeFinancialInvolvement use Cypher integer flags (1/0) for branch results instead of CASE WHEN in arithmetic, then arithmetic outside CASE to compute the graduated score"
  - "[Phase 07-domain-aware-risk-analysis]: buildPattern() helper centralizes keyword list to Cypher regex conversion — reused by both security and financial computation methods"
metrics:
  duration: 3min
  completed_date: "2026-03-05"
  tasks_completed: 2
  files_modified: 5
---

# Phase 7 Plan 01: Domain Score Dimensions — Model Extension and Computation Summary

Domain-aware risk scoring added to RiskService: 4 Cypher computation methods for domain criticality (USES_TERM graph traversal), security sensitivity (keyword/annotation/package heuristics with graduated weighting), financial involvement (name/package/USES_TERM heuristics), and business rule density (log-normalized DEFINES_RULE count); plus 8-dimension enhanced composite score.

## What Was Built

### Task 1: ClassNode, RiskWeightConfig, application.yml, Neo4jSchemaInitializer

**ClassNode** (`extraction/model/ClassNode.java`): 5 new domain risk properties added in a Phase 7 section after the existing Phase 6 structural fields:
- `domainCriticality` (double): 0.0–1.0 from USES_TERM BusinessTerm criticality
- `securitySensitivity` (double): 0.0–1.0 from keyword/annotation/package heuristics
- `financialInvolvement` (double): 0.0–1.0 from keyword/package/USES_TERM heuristics
- `businessRuleDensity` (double): log-normalized DEFINES_RULE count
- `enhancedRiskScore` (double): 8-dimension composite score

Each field has standard getter/setter pairs following the existing ClassNode convention.

**RiskWeightConfig** (`extraction/config/RiskWeightConfig.java`): 8 new enhanced weight fields with correct defaults summing to 1.0:
- `domainComplexity = 0.24`, `domainFanIn = 0.12`, `domainFanOut = 0.12`, `domainDbWrites = 0.12`
- `domainCriticality = 0.10`, `securitySensitivity = 0.10`, `financialInvolvement = 0.10`, `businessRuleDensity = 0.10`

Spring relaxed binding maps `domain-criticality` YAML key to `domainCriticality` field automatically. Existing Phase 6 structural weights (complexity, fanIn, fanOut, dbWrites) are unchanged.

**application.yml** (`resources/application.yml`): 8 new domain weight defaults added under `esmp.risk.weight`.

**Neo4jSchemaInitializer** (`extraction/config/Neo4jSchemaInitializer.java`): `java_class_enhanced_risk_score` range index added for efficient ORDER BY on enhanced score queries.

### Task 2: Domain Score Computation Methods in RiskService

**Static constants added:**
- `SECURITY_NAME_KEYWORDS` (18 terms), `SECURITY_ANNOTATION_KEYWORDS` (7 terms), `SECURITY_PKG_KEYWORDS` (6 terms)
- `FINANCIAL_NAME_KEYWORDS` (19 terms), `FINANCIAL_PKG_KEYWORDS` (6 terms), `FINANCIAL_TERM_KEYWORDS` (14 terms)
- `NAME_HIT_WEIGHT=0.3`, `ANNOT_HIT_WEIGHT=0.5`, `BOTH_HIT_BONUS=0.2`, `PKG_HIT_BOOST=0.2`, `TERM_HIT_BOOST=0.2`

**computeDomainCriticality()** (DRISK-01): Single Cypher query using pattern comprehension to count High and Medium criticality USES_TERM edges. Assigns 1.0 for any High-criticality BusinessTerm, 0.5 for Medium-only, 0.0 for none.

**computeSecuritySensitivity()** (DRISK-02): Graduated Cypher heuristic. Uses `buildPattern()` to create regex patterns for name, annotation, and package matching. All keyword patterns and weights pass as parameters (no string interpolation in Cypher). `min(1.0, ...)` clamps result.

**computeFinancialInvolvement()** (DRISK-03): Same graduated pattern as security, plus an `EXISTS { ... }` subquery that checks USES_TERM edges to BusinessTerms matching financial keywords. Annotation matching skipped (neverMatch regex `(?!x)x`) since no financial-specific Java annotations exist.

**computeBusinessRuleDensity()** (DRISK-04): Pattern comprehension counts outgoing DEFINES_RULE edges, applies `log(1.0 + ruleCount)` for zero-safe log normalization.

**computeEnhancedRiskScore()** (DRISK-05): Single Cypher SET using raw structural properties (complexitySum, fanIn, fanOut, hasDbWrites) re-normalized with domain weights plus 4 domain dimension properties. All 8 weights bound as Cypher parameters from RiskWeightConfig.

**buildPattern()** helper: Converts `List<String>` keywords to Cypher regex `".*(" + joined + ").*"`.

**computeAndPersistRiskScores()** updated to call all 5 new methods sequentially after the existing structural computation, with descriptive log messages.

## Deviations from Plan

None — plan executed exactly as written.

## Self-Check

- [x] `src/main/java/com/esmp/extraction/model/ClassNode.java` — modified with 5 new domain risk fields and getters/setters
- [x] `src/main/java/com/esmp/extraction/config/RiskWeightConfig.java` — modified with 8 new enhanced weight fields and getters/setters
- [x] `src/main/resources/application.yml` — modified with 8 new domain weight defaults
- [x] `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` — modified with java_class_enhanced_risk_score index
- [x] `src/main/java/com/esmp/graph/application/RiskService.java` — modified with 5 new computation methods
- [x] Commit b85b1cf: feat(07-01): extend ClassNode, RiskWeightConfig, application.yml, Neo4jSchemaInitializer for domain risk
- [x] Commit d14b915: feat(07-01): implement domain score computation methods in RiskService
- [x] `./gradlew compileJava` passes: BUILD SUCCESSFUL

## Self-Check: PASSED
