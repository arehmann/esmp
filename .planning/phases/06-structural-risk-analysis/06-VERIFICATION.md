---
phase: 06-structural-risk-analysis
verified: 2026-03-05T13:30:00Z
status: passed
score: 8/8 must-haves verified
re_verification: false
gaps: []
human_verification:
  - test: "Run extraction against a real Java source tree and call GET /api/risk/heatmap"
    expected: "Classes appear sorted by descending structuralRiskScore; all entries have non-zero scores"
    why_human: "Integration test uses Testcontainers but the Gradle daemon OOM issue prevents full-suite run in CI — manual smoke test confirms end-to-end pipeline"
  - test: "Call GET /api/risk/heatmap?stereotype=Service and GET /api/risk/class/{fqn}"
    expected: "Heatmap filtered to Service-labeled classes only; detail includes per-method cyclomaticComplexity breakdown"
    why_human: "Filter and detail behavior can only be confirmed against a graph populated with real extraction output"
---

# Phase 6: Structural Risk Analysis Verification Report

**Phase Goal:** Every class in the graph has structural risk metrics and a composite structural risk score
**Verified:** 2026-03-05T13:30:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Every class node has cyclomatic complexity computed from its methods | VERIFIED | `ComplexityVisitor` counts branches per method; `AccumulatorToModelMapper` aggregates `complexitySum` and `complexityMax` onto `ClassNode`; `MethodNode.cyclomaticComplexity` set per method |
| 2 | Every class node has fan-in and fan-out counts | VERIFIED | `RiskService.computeFanInOut()` executes Cypher pattern comprehension `size([(other)-[:DEPENDS_ON]->(c) | other])` setting `fanIn`/`fanOut` on all `JavaClass` nodes |
| 3 | Every class is flagged for DB write operations | VERIFIED | `ComplexityVisitor` detects `@Modifying`, `@Query` with INSERT/UPDATE/DELETE, and `persist`/`merge`/`remove`/`save`/`delete`/`flush` invocations; sets `hasDbWrites` and `dbWriteCount` on `ClassNode` |
| 4 | Every class has a composite structural risk score | VERIFIED | `RiskService.computeStructuralRiskScore()` executes Cypher SET using log-normalized weighted formula with configurable weights from `RiskWeightConfig` |
| 5 | User can call a REST endpoint and receive classes sorted by descending risk score | VERIFIED | `GET /api/risk/heatmap` in `RiskController` delegates to `RiskService.getHeatmap()` which returns `ORDER BY c.structuralRiskScore DESC` |
| 6 | Fan-in/out computation runs after DEPENDS_ON edges exist | VERIFIED | `ExtractionService` calls `riskService.computeAndPersistRiskScores()` after `linkingService.linkAllRelationships(accumulator)` — ordering enforced at line 196 |
| 7 | Risk weights are configurable | VERIFIED | `RiskWeightConfig` bound to `esmp.risk.weight.*`; defaults (0.4/0.2/0.2/0.2) in `application.yml`; injected into `RiskService.computeStructuralRiskScore()` |
| 8 | Per-class risk detail with method breakdown is accessible | VERIFIED | `GET /api/risk/class/{fqn}` returns `RiskDetailResponse` with `List<MethodComplexityEntry>` via `OPTIONAL MATCH (c)-[:DECLARES_METHOD]->(m:JavaMethod)` |

**Score:** 8/8 truths verified

---

### Required Artifacts

#### Plan 01 Artifacts (RISK-01, RISK-03)

| Artifact | Min Lines | Actual Lines | Status | Notes |
|----------|-----------|--------------|--------|-------|
| `src/main/java/com/esmp/extraction/visitor/ComplexityVisitor.java` | 120 | 297 | VERIFIED | Full `JavaIsoVisitor` with Deque stack CC counting and three-signal DB write detection |
| `src/test/java/com/esmp/extraction/visitor/ComplexityVisitorTest.java` | 80 | 536 | VERIFIED | 15 unit tests covering CC baselines, branch counting, and DB write detection |
| `src/main/java/com/esmp/extraction/model/ClassNode.java` | — | — | VERIFIED | 7 new risk properties: `complexitySum`, `complexityMax`, `fanIn`, `fanOut`, `hasDbWrites`, `dbWriteCount`, `structuralRiskScore` |
| `src/main/java/com/esmp/extraction/model/MethodNode.java` | — | — | VERIFIED | `cyclomaticComplexity` property added with getter/setter |
| `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` | — | — | VERIFIED | `methodComplexities` and `classWriteData` maps, `MethodComplexityData` and `ClassWriteData` records, `addMethodComplexity()` and `incrementClassDbWrites()` |
| `src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java` | — | — | VERIFIED | Maps `cyclomaticComplexity` to `MethodNode`, aggregates `complexitySum`/`complexityMax`, maps `hasDbWrites`/`dbWriteCount` to `ClassNode` |
| `src/main/java/com/esmp/extraction/application/ExtractionService.java` | — | — | VERIFIED | `ComplexityVisitor` instantiated and invoked in per-source-file loop; `RiskService` injected and called after linking |

#### Plan 02 Artifacts (RISK-02, RISK-04, RISK-05)

| Artifact | Min Lines | Actual Lines | Status | Notes |
|----------|-----------|--------------|--------|-------|
| `src/main/java/com/esmp/graph/application/RiskService.java` | 100 | 268 | VERIFIED | `computeAndPersistRiskScores()`, `getHeatmap()`, `getClassDetail()` with full Neo4jClient Cypher implementation |
| `src/main/java/com/esmp/graph/api/RiskController.java` | 40 | 74 | VERIFIED | `GET /heatmap` and `GET /class/{fqn:.+}` endpoints wired to `RiskService` |
| `src/main/java/com/esmp/graph/api/RiskHeatmapEntry.java` | — | — | VERIFIED | Record with 11 fields matching heatmap response contract |
| `src/main/java/com/esmp/graph/api/RiskDetailResponse.java` | — | — | VERIFIED | Record extending heatmap fields with `List<MethodComplexityEntry>` |
| `src/main/java/com/esmp/graph/api/MethodComplexityEntry.java` | — | — | VERIFIED | Record: `methodId`, `simpleName`, `cyclomaticComplexity`, `parameterTypes` |
| `src/main/java/com/esmp/graph/validation/RiskValidationQueryRegistry.java` | 30 | 72 | VERIFIED | `@Component` extending `ValidationQueryRegistry` with 3 queries (RISK_SCORES_POPULATED/ERROR, FAN_IN_OUT_POPULATED/ERROR, HIGH_RISK_NO_DEPENDENCIES/WARNING) |
| `src/main/java/com/esmp/extraction/config/RiskWeightConfig.java` | 20 | 68 | VERIFIED | `@ConfigurationProperties(prefix="esmp.risk.weight")` with defaults 0.4/0.2/0.2/0.2 |
| `src/main/resources/application.yml` | — | — | VERIFIED | `esmp.risk.weight.complexity/fan-in/fan-out/db-writes` defaults present |
| `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` | — | — | VERIFIED | `java_class_risk_score` range index added |
| `src/test/java/com/esmp/graph/application/RiskServiceIntegrationTest.java` | 80 | 365 | VERIFIED | `@SpringBootTest` with Testcontainers (Neo4j + MySQL + Qdrant); 9 integration tests covering fan-in/out, composite score, heatmap sort/filter, detail, and 404 |

---

### Key Link Verification

#### Plan 01 Key Links

| From | To | Via | Status | Evidence |
|------|----|-----|--------|----------|
| `ComplexityVisitor` | `ExtractionAccumulator` | `acc.addMethodComplexity()` and `acc.incrementClassDbWrites()` | WIRED | Lines 83 and 90 in `ComplexityVisitor.java`; accumulator map mutations confirmed in `ExtractionAccumulator.java` lines 353 and 365 |
| `AccumulatorToModelMapper` | `ClassNode`/`MethodNode` | `setCyclomaticComplexity`, `setComplexitySum`, `setHasDbWrites` | WIRED | Mapper line 68 sets `cyclomaticComplexity`; lines 172 and 177 set class aggregates and DB writes |
| `ExtractionService` | `ComplexityVisitor` | `complexityVisitor.visit(sourceFile, accumulator)` | WIRED | Import at line 22; instantiation at line 144; invocation at line 154 in `ExtractionService.java` |

#### Plan 02 Key Links

| From | To | Via | Status | Evidence |
|------|----|-----|--------|----------|
| `ExtractionService` | `RiskService.computeAndPersistRiskScores()` | Called after `linkingService.linkAllRelationships()` | WIRED | `ExtractionService.java` line 196: `riskService.computeAndPersistRiskScores()` — after linking call |
| `RiskService` | `Neo4jClient` | Cypher queries for fan-in/out and composite score SET | WIRED | `RiskService.java` lines 94, 111, 171, 193: four `neo4jClient.query(...)` calls |
| `RiskController` | `RiskService` | `riskService.getHeatmap()` and `riskService.getClassDetail()` | WIRED | `RiskController.java` lines 58 and 70 |
| `RiskWeightConfig` | `RiskService` | Injected weights in `computeStructuralRiskScore()` | WIRED | `RiskService.java` lines 113-116: `riskWeightConfig.getComplexity()`, `getFanIn()`, `getFanOut()`, `getDbWrites()` |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| RISK-01 | 06-01 | System computes cyclomatic complexity per class/method | SATISFIED | `ComplexityVisitor` + accumulator + mapper pipeline; `MethodNode.cyclomaticComplexity` and `ClassNode.complexitySum/Max` populated during extraction |
| RISK-02 | 06-02 | System computes fan-in and fan-out metrics per class | SATISFIED | `RiskService.computeFanInOut()` sets `ClassNode.fanIn` and `ClassNode.fanOut` via Cypher pattern comprehension on DEPENDS_ON edges |
| RISK-03 | 06-01 | System detects DB write operations per class | SATISFIED | `ComplexityVisitor` detects three write signals (@Modifying, @Query write SQL, JPA method invocations); `ClassNode.hasDbWrites` and `dbWriteCount` populated |
| RISK-04 | 06-02 | System produces composite structural risk score per class | SATISFIED | `RiskService.computeStructuralRiskScore()` computes `w*log(1+complexitySum) + w*log(1+fanIn) + w*log(1+fanOut) + w*(hasDbWrites?1:0)` on all JavaClass nodes |
| RISK-05 | 06-02 | User can view dependency heatmap sorted by structural risk score | SATISFIED | `GET /api/risk/heatmap` via `RiskController` + `RiskService.getHeatmap()` with `ORDER BY c.structuralRiskScore DESC`; `GET /api/risk/class/{fqn}` for per-class detail |

All 5 requirements (RISK-01 through RISK-05) declared in plan frontmatter are satisfied. No orphaned requirements — REQUIREMENTS.md maps exactly these 5 IDs to Phase 6 and no others.

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `RiskService.java` | 200 | `return null` | Info | Inside mapper lambda — null-guard for absent Neo4j node values; filtered by `.filter(e -> e != null ...)` on line 210. Not a stub. |
| `ComplexityVisitor.java` | 241, 258 | `return null` | Info | `extractAnnotationStringValue()` — returns null when annotation has no matching string argument. Null-checked by caller at line 97. Not a stub. |

No blocking anti-patterns found.

---

### Commit Verification

All 5 commits documented in SUMMARY files exist in git history and are verified:

| Commit | Plan | Description |
|--------|------|-------------|
| `e1361b1` | 06-01 Task 1 | ComplexityVisitor, model risk properties, accumulator extensions (15 tests) |
| `a5bb1e7` | 06-01 Task 2 | Mapper complexity mapping + ExtractionService pipeline wiring |
| `3837253` | 06-02 TDD RED | Failing integration tests for RiskService and RiskController |
| `540c34e` | 06-02 Task 1 GREEN | RiskService, RiskController, response records, validation registry, schema index |
| `2b3f307` | 06-02 Task 2 | Wire RiskService into ExtractionService pipeline |

---

### Human Verification Required

#### 1. End-to-End Extraction with Risk Pipeline

**Test:** Run `POST /api/extraction/trigger` against a real Java source tree, then call `GET /api/risk/heatmap`
**Expected:** All extracted classes appear in the heatmap sorted by descending `structuralRiskScore`; complex, highly-coupled classes rank higher than simple leaf classes
**Why human:** Testcontainers integration test passes individually but Gradle daemon OOM prevents full-suite execution; smoke test confirms pipeline integration

#### 2. Heatmap Filter Behavior

**Test:** Call `GET /api/risk/heatmap?stereotype=Service` and `GET /api/risk/heatmap?packageName=com.example`
**Expected:** Only Service-labeled classes appear in the first response; only classes in the given package prefix appear in the second
**Why human:** Filter correctness requires a populated graph with known stereotype labels and package names

#### 3. Class Detail Endpoint

**Test:** Call `GET /api/risk/class/{fqn}` with a known FQN; call with a nonexistent FQN
**Expected:** Known class returns 200 with `methods` array containing per-method `cyclomaticComplexity`; nonexistent returns 404
**Why human:** Requires an extracted graph to confirm the DECLARES_METHOD traversal works end-to-end

---

### Gaps Summary

No gaps found. All observable truths are verified, all artifacts exist and are substantive (well above minimum line counts), all key links are wired, and all 5 requirements are satisfied.

The only note for awareness: the Gradle daemon OOM issue when running the full Testcontainers test suite in parallel is a pre-existing infrastructure problem documented in Phases 3 and 5. It does not affect individual test correctness.

---

_Verified: 2026-03-05T13:30:00Z_
_Verifier: Claude (gsd-verifier)_
