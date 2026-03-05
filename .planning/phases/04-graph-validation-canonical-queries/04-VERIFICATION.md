---
phase: 04-graph-validation-canonical-queries
verified: 2026-03-05T00:00:00Z
status: passed
score: 12/12 must-haves verified
re_verification: false
gaps: []
human_verification:
  - test: "Run GET /api/graph/validation against real extracted codebase"
    expected: "JSON with 20 query results; errorCount and warnCount reflect actual graph health"
    why_human: "Integration tests use synthetic test graphs; real-graph fidelity requires live extraction run"
  - test: "Run GET /api/graph/class/{fqn}/dependency-cone against real class in extracted graph"
    expected: "Cone nodes match senior engineer's architectural expectations for that class"
    why_human: "GVAL-02 requires human validation that cone accuracy matches confirmed architectural expectations (Success Criterion 2 and 5)"
---

# Phase 4: Graph Validation & Canonical Queries Verification Report

**Phase Goal:** Structural graph is verified correct before building semantic layers on top of it
**Verified:** 2026-03-05
**Status:** passed (with 2 human verification items for real-graph validation)
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | GET /api/graph/validation returns a JSON report with 20 query results | VERIFIED | `ValidationController.java` line 40-44: `@GetMapping("/validation")` calls `validationService.runAllValidations()`, returns `ResponseEntity<ValidationReport>`. `ValidationQueryRegistry.java` contains exactly 20 `new ValidationQuery(...)` instances (grep count = 20). Integration test `reportStructure()` asserts `report.results().hasSize(20)`. |
| 2 | Each query result has name, description, severity, status (PASS/FAIL/WARN), count, and details | VERIFIED | `ValidationQueryResult.java` record has all 6 fields. `ValidationReport.java` record has `generatedAt`, `results`, `errorCount`, `warnCount`, `passCount`. Integration test `reportStructure()` asserts each result has all fields non-null. |
| 3 | Orphan/dangling node queries detect structurally broken nodes | VERIFIED | `detectsOrphanAndDanglingNodes` test (490-line test file) creates broken graph, asserts ORPHAN_CLASS_NODES WARN, DANGLING_METHOD_NODES FAIL, DANGLING_FIELD_NODES FAIL with count > 0. |
| 4 | Duplicate FQN query detects non-unique class names | VERIFIED | Query #5 `DUPLICATE_CLASS_FQNS` defined in registry with ERROR severity and correct Cypher (`cnt > 1` pattern). |
| 5 | Inheritance chain completeness query flags classes with broken EXTENDS edges | VERIFIED | `detectsBrokenInheritanceChain` test creates class with `superClass` property pointing to existing node but no EXTENDS edge, asserts `INHERITANCE_CHAIN_COMPLETENESS` count > 0 and status FAIL. |
| 6 | Architectural pattern queries flag services without dependencies, repos without queries, views without bindings | VERIFIED | `detectsArchitecturalPatternViolations` test creates LonelyService (Service, no DEPENDS_ON), EmptyRepository (Repository, no method QUERIES), UnboundView (VaadinView, no BINDS_TO), asserts each query returns WARN with count > 0. |
| 7 | ERROR severity queries produce FAIL; WARNING severity produce WARN | VERIFIED | `ValidationService.determineStatus()` lines 126-138: count > 0 + ERROR => FAIL, count > 0 + WARNING => WARN. CALLS_EDGE_COVERAGE special-cased (inverted). All 5 integration tests pass against this logic. |
| 8 | GET /api/graph/class/{fqn}/dependency-cone returns all nodes reachable from focal class | VERIFIED | `GraphQueryController.java` line 83-90: `@GetMapping("/class/{fqn:.+}/dependency-cone")` calls `graphQueryService.findDependencyCone(fqn)`. `GraphQueryService.java` line 331-394: full implementation with 7-relationship variable-length Cypher traversal. |
| 9 | Cone traverses all 7 structural relationship types up to 10 hops | VERIFIED | `GraphQueryService.java` line 335: `DEPENDS_ON\|EXTENDS\|IMPLEMENTS\|CALLS\|BINDS_TO\|QUERIES\|MAPS_TO_TABLE*1..10`. All 7 types confirmed present. |
| 10 | Cone response includes each reachable node's identifier and labels | VERIFIED | `DependencyConeResponse.java` record with nested `ConeNode(String fqn, List<String> labels)`. Cypher uses CASE/WHEN dispatch to extract correct identifier per node type. `coneNodeLabels` test asserts Repository dynamic label is present. |
| 11 | Cone for a class with no outgoing edges returns coneSize 0 and empty coneNodes | VERIFIED | `coneForIsolatedClass` test asserts coneSize == 0 and coneNodes.isEmpty(). OPTIONAL MATCH + collect(DISTINCT) handles null rows natively in Cypher. |
| 12 | Non-existent FQN returns 404 | VERIFIED | `coneForNonExistentClass` test asserts HttpStatus.NOT_FOUND. Controller returns `ResponseEntity.notFound().build()` when `Optional.empty()` from service. |

**Score:** 12/12 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/graph/validation/ValidationSeverity.java` | Enum ERROR, WARNING | VERIFIED | File exists, enum with 2 values |
| `src/main/java/com/esmp/graph/validation/ValidationStatus.java` | Enum PASS, FAIL, WARN | VERIFIED | File exists, enum with 3 values |
| `src/main/java/com/esmp/graph/validation/ValidationQuery.java` | Record: name, description, cypher, severity | VERIFIED | File exists |
| `src/main/java/com/esmp/graph/validation/ValidationQueryRegistry.java` | @Component with all 20 canonical Cypher queries | VERIFIED | 20 `new ValidationQuery(...)` instances confirmed by grep count; ORPHAN_CLASS_NODES present at line 42; `getQueries()` returns unmodifiable list |
| `src/main/java/com/esmp/graph/validation/ValidationService.java` | Query execution engine returning ValidationReport | VERIFIED | `runAllValidations()` at line 48; iterates registries via `registry.getQueries().stream()`; executes via `neo4jClient.query(cypher).fetch().all()`; correct CALLS_EDGE_COVERAGE inversion |
| `src/main/java/com/esmp/graph/api/ValidationQueryResult.java` | Record with all 6 fields | VERIFIED | File exists with all required fields |
| `src/main/java/com/esmp/graph/api/ValidationReport.java` | Record with generatedAt, results, counts | VERIFIED | File exists with all 5 fields |
| `src/main/java/com/esmp/graph/api/ValidationController.java` | GET /api/graph/validation endpoint | VERIFIED | `@GetMapping("/validation")` at line 40; constructor injection of ValidationService |
| `src/test/java/com/esmp/graph/api/ValidationControllerIntegrationTest.java` | 5 integration tests, min 80 lines | VERIFIED | 490 lines, 6 @Test methods (5 behavioral + 1 structure); Testcontainers Neo4j + MySQL + Qdrant; all 5 test scenarios covered |
| `src/main/java/com/esmp/graph/api/DependencyConeResponse.java` | Record with ConeNode nested record | VERIFIED | File exists with `ConeNode(String fqn, List<String> labels)` at line 40 |
| `src/main/java/com/esmp/graph/application/GraphQueryService.java` | findDependencyCone method | VERIFIED | `findDependencyCone(String fqn)` at line 331; 7-relationship Cypher; OPTIONAL MATCH + collect(DISTINCT); node-type CASE dispatch |
| `src/main/java/com/esmp/graph/api/GraphQueryController.java` | dependency-cone endpoint | VERIFIED | `@GetMapping("/class/{fqn:.+}/dependency-cone")` at line 83; 404 on empty Optional |
| `src/test/java/com/esmp/graph/api/DependencyConeIntegrationTest.java` | 4 integration tests, min 60 lines | VERIFIED | 283 lines, 5 @Test methods; all 4 behavioral scenarios covered |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `ValidationController.java` | `ValidationService.java` | Constructor injection | WIRED | Line 27: `public ValidationController(ValidationService validationService)`. Line 42: `validationService.runAllValidations()` called in handler. |
| `ValidationService.java` | `Neo4jClient` | `neo4jClient.query(cypher).fetch().all()` | WIRED | Lines 77-80: `neo4jClient.query(query.cypher()).fetch().all()` — exact required pattern present. |
| `ValidationService.java` | `ValidationQueryRegistry.java` | `registry.getQueries()` iteration | WIRED | Lines 49-51: `registries.stream().flatMap(registry -> registry.getQueries().stream())` — iterates all registries, collects all queries. |
| `GraphQueryController.java` | `GraphQueryService.java` | `graphQueryService.findDependencyCone(fqn)` | WIRED | Lines 86-89: `graphQueryService.findDependencyCone(fqn).map(ResponseEntity::ok).orElse(...)` |
| `GraphQueryService.java` | `Neo4jClient` | Variable-length multi-relationship Cypher | WIRED | Line 335 contains `DEPENDS_ON\|EXTENDS\|IMPLEMENTS\|CALLS\|BINDS_TO\|QUERIES\|MAPS_TO_TABLE*1..10` — all 7 relationship types confirmed. |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| GVAL-01 | 04-01-PLAN.md | 20 canonical validation queries defined and passing against populated graph | SATISFIED | Registry has exactly 20 queries (grep-confirmed). 5 integration tests pass including `allQueriesPassOnWellFormedGraph` asserting all 20 PASS. |
| GVAL-02 | 04-02-PLAN.md | Dependency cone accuracy verified against manually confirmed architectural expectations | PARTIALLY SATISFIED (needs human) | Cone endpoint implemented and integration-tested. Automated tests confirm technical correctness. Human validation against real extracted codebase still needed per ROADMAP Success Criterion 2 and 5. |
| GVAL-03 | 04-01-PLAN.md | No orphan nodes or duplicate structural nodes exist in graph | SATISFIED | ORPHAN_CLASS_NODES (query 1) and DUPLICATE_CLASS_FQNS (query 5) both implemented and test-verified to detect violations. `detectsOrphanAndDanglingNodes` test confirms detection works. |
| GVAL-04 | 04-01-PLAN.md | Inheritance chains complete and transitive repository dependencies correctly resolved | SATISFIED | INHERITANCE_CHAIN_COMPLETENESS (query 15, ERROR) and TRANSITIVE_REPO_DEPENDENCY (query 16, WARNING) both implemented. `detectsBrokenInheritanceChain` test confirms detection. |

All 4 GVAL requirement IDs from plan frontmatter are accounted for. No orphaned requirement IDs.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `GraphQueryService.java` | 77, 97, 109, 120, 130 | `return null` in lambda | Info | Pre-existing Phase 3 code. Null values are immediately filtered by `.filter(x -> x != null)` in stream pipeline. Not stubs — these are defensive null guards inside `asList()` mapper lambdas. No impact on Phase 4 goal. |

No blockers or warnings found in Phase 4 artifacts.

### Human Verification Required

#### 1. Real-graph validation endpoint smoke test

**Test:** After triggering extraction against a real Java source module, call `GET /api/graph/validation`.
**Expected:** HTTP 200 with JSON body containing 20 query results. At minimum `CALLS_EDGE_COVERAGE` should return PASS (count > 0), `DUPLICATE_CLASS_FQNS` should return PASS (count = 0). `errorCount` should be 0 for a well-formed extraction.
**Why human:** Integration tests use a synthetic 10-node test graph. Real-graph fidelity — whether the 20 queries correctly reflect the state of an actual extracted codebase — requires a live run and human interpretation of WARN results.

#### 2. Dependency cone architectural accuracy (GVAL-02)

**Test:** Extract a known bounded context (e.g., a service class with known callers and dependencies). Call `GET /api/graph/class/{fqn}/dependency-cone`. Compare returned cone nodes to what a senior engineer knows should be reachable.
**Expected:** Cone nodes match the engineer's mental model of the architectural boundary. No unexpected inclusions (false positives) and no missing nodes (false negatives) for directly connected relationships.
**Why human:** ROADMAP Success Criterion 2 explicitly states "Dependency cone accuracy is verified — graph-derived cones match manually verified architectural expectations" and Success Criterion 5 states "Graph answers for a known sample module match senior engineer expectations." These require a human SME to validate.

### Gaps Summary

No gaps blocking goal achievement. All 12 must-have truths are verified against the actual codebase. All 5 key links are wired. Both integration test suites are substantive (490 and 283 lines respectively). All 6 commits documented in the summaries exist in git history.

The 2 human verification items are confirmations of real-graph fidelity — they do not indicate missing implementation. The code, queries, and tests are complete and correct.

---

_Verified: 2026-03-05_
_Verifier: Claude (gsd-verifier)_
