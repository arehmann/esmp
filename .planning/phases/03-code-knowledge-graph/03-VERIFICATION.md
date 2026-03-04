---
phase: 03-code-knowledge-graph
verified: 2026-03-04T21:30:00Z
status: passed
score: 9/9 must-haves verified
re_verification:
  previous_status: gaps_found
  previous_score: 8/9
  gaps_closed:
    - "Graph stores CALLS, EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE relationships — BINDS_TO gap is now closed end-to-end"
  gaps_remaining: []
  regressions: []
---

# Phase 3: Code Knowledge Graph Verification Report

**Phase Goal:** Neo4j graph contains the full structural model of the codebase with all node types, relationship edges, and is queryable via API
**Verified:** 2026-03-04T21:30:00Z
**Status:** passed
**Re-verification:** Yes — after gap closure (plan 03-04 executed to close BINDS_TO gap)

## Re-verification Summary

Previous verification (2026-03-04T20:30:00Z) found one gap: the BINDS_TO relationship required by CKG-02 was specified in the model infrastructure (`BindsToRelationship.java`, `ExtractionAccumulator.addBindsToEdge()`) but never materialized — no visitor detected the pattern and no linking pass read the accumulator data.

Plan 03-04 was executed to close that gap. This re-verification confirms the gap is closed with no regressions. All 4 gap-closure commits are present in git: `246ef79`, `52c5c68`, `1f88c3a`, `6c573fd`.

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Neo4j schema has uniqueness constraints for AnnotationNode, PackageNode, ModuleNode, DBTableNode | VERIFIED | Neo4jSchemaInitializer.java: all four CREATE CONSTRAINT IF NOT EXISTS statements present (java_annotation_fqn_unique, java_package_name_unique, java_module_name_unique, db_table_name_unique) |
| 2 | ExtractionAccumulator collects annotation, package, module, DB table, dependency, query method, and BINDS_TO data | VERIFIED | ExtractionAccumulator.java: addBindsToEdge() at line 298, getBindsToEdges() at line 375, BindsToRecord at line 443; all 4 inner records present |
| 3 | All new @Node entities use string business-key @Id with @Version for idempotent MERGE | VERIFIED | AnnotationNode, PackageNode, ModuleNode, DBTableNode — all use `@Id private String` + `@Version private Long version` |
| 4 | All new @RelationshipProperties classes use @Id @GeneratedValue with @TargetNode | VERIFIED | DependsOnRelationship.java and BindsToRelationship.java (pattern confirmed for both) — all 6 relationship classes exist |
| 5 | DependencyVisitor detects @Autowired/@Inject field/constructor injection as DEPENDS_ON edges | VERIFIED | DependencyVisitor.java: INJECTION_ANNOTATIONS set, visitVariableDeclarations() with cursor guard, visitMethodDeclaration() for constructors, EXCLUDED_PREFIXES filter |
| 6 | JpaPatternVisitor detects @Entity/@Table for MAPS_TO_TABLE and @Query/findByX for QUERIES edges | VERIFIED | JpaPatternVisitor.java: ENTITY_ANNOTATIONS, TABLE_ANNOTATIONS, QUERY_ANNOTATIONS sets, toSnakeCase() fallback, DERIVED_QUERY_PREFIXES list |
| 7 | Graph stores CALLS, EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE relationships | VERIFIED | All 7 relationship types now wired end-to-end. BINDS_TO: VaadinPatternVisitor.visitNewClass() calls acc.addBindsToEdge() at line 153 for BeanFieldGroup/FieldGroup; LinkingService.linkBindsToEdges() materializes edges via Cypher MERGE (line 363); linkAllRelationships() calls linkBindsToEdges() at line 62 and includes bindsToCount in LinkingResult (line 394) |
| 8 | User can query the graph via 4 structured API endpoints (class structure, inheritance, service-dependents, search) | VERIFIED | GraphQueryController.java: 4 @GetMapping endpoints with :.+ path regex. GraphQueryService.java: Neo4jClient used for complex Cypher, repository for simple lookups. Integration test with 6 test cases |
| 9 | LinkingService creates post-extraction relationship edges via idempotent Cypher MERGE | VERIFIED | LinkingService.java: all 7 linking methods including new linkBindsToEdges() — all use MERGE. BINDS_TO MERGE at line 363: `MERGE (view)-[r:BINDS_TO {bindingMechanism: $mechanism}]->(entity)` |

**Score:** 9/9 truths verified

---

## Required Artifacts

### Plan 01 Artifacts (CKG-01) — Regression Check

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/extraction/model/AnnotationNode.java` | @Node(JavaAnnotation) with FQN business key | VERIFIED | Previously verified; no modification in plan 03-04 |
| `src/main/java/com/esmp/extraction/model/PackageNode.java` | @Node(JavaPackage) with packageName business key | VERIFIED | Previously verified; no modification in plan 03-04 |
| `src/main/java/com/esmp/extraction/model/ModuleNode.java` | @Node(JavaModule) with moduleName business key | VERIFIED | Previously verified; no modification in plan 03-04 |
| `src/main/java/com/esmp/extraction/model/DBTableNode.java` | @Node(DBTable) with tableName business key | VERIFIED | Previously verified; no modification in plan 03-04 |
| `src/main/java/com/esmp/extraction/model/DependsOnRelationship.java` | @RelationshipProperties for DEPENDS_ON | VERIFIED | Previously verified; no modification in plan 03-04 |
| `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` | Extended accumulator with data holders for all new types | VERIFIED | addBindsToEdge() line 298, getBindsToEdges() line 375, BindsToRecord line 443 — all present; no regressions |
| `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` | Creates constraints for new node types | VERIFIED | Previously verified; no modification in plan 03-04 |

All 4 new repositories exist: AnnotationNodeRepository, PackageNodeRepository, ModuleNodeRepository, DBTableNodeRepository.
All 6 relationship classes exist: DependsOnRelationship, ExtendsRelationship, ImplementsRelationship, BindsToRelationship, QueriesRelationship, MapsToTableRelationship.

### Plan 02 Artifacts (CKG-02) — Full Verification (Previously-Failing Items)

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java` | BINDS_TO edge detection via acc.addBindsToEdge() | VERIFIED | visitNewClass() extended: checks BeanFieldGroup/FieldGroup (not BeanItemContainer), extracts entity FQN from generic type parameter via JavaType.Parameterized, calls acc.addBindsToEdge() at line 153. BeanItemContainer excluded per plan spec. |
| `src/main/java/com/esmp/extraction/visitor/DependencyVisitor.java` | DEPENDS_ON edge detection | VERIFIED | Previously verified; no modification in plan 03-04 |
| `src/main/java/com/esmp/extraction/visitor/JpaPatternVisitor.java` | @Entity/@Table and @Query/findByX detection | VERIFIED | Previously verified; no modification in plan 03-04 |
| `src/main/java/com/esmp/extraction/application/LinkingService.java` | Post-extraction relationship materialization including BINDS_TO | VERIFIED | linkBindsToEdges() method at line 353 with Cypher MERGE at line 363. linkAllRelationships() calls linkBindsToEdges() at line 62 and passes bindsToCount to LinkingResult at line 70-72. LinkingResult record now has 7 fields including bindsToCount at line 394. Only one instantiation site exists in codebase — updated to 7-arg form. |
| `src/main/java/com/esmp/extraction/application/ExtractionService.java` | Full 5-visitor pipeline | VERIFIED | All 5 visitors instantiated and called; linkingService.linkAllRelationships() called at line 165 — no regressions |
| `src/test/java/com/esmp/extraction/visitor/VaadinPatternVisitorTest.java` | Unit tests including BINDS_TO detection test | VERIFIED | New test `detectsBindsToEdge_fromBeanFieldGroupInstantiation` at lines 76-84: asserts acc.getBindsToEdges() is not empty and contains edge with viewFqn=SampleVaadinForm, entityFqn=SampleEntity, mechanism=BeanFieldGroup. 6 total tests. |
| `src/test/java/com/esmp/extraction/visitor/JpaPatternVisitorTest.java` | Unit tests for JpaPatternVisitor | VERIFIED | Previously verified; file exists |
| `src/test/java/com/esmp/extraction/application/LinkingServiceIntegrationTest.java` | Integration test including BINDS_TO linking | VERIFIED | New test `linkBindsToEdges_createsBindsToRelationship` at lines 234-276: creates two ClassNodes via raw Cypher, calls linkBindsToEdges(), asserts count=1, verifies edge in Neo4j, verifies idempotency (re-run count still 1). Full @SpringBootTest with Testcontainers (Neo4j 2026.01.4, MySQL 8.4, Qdrant). |

### Plan 03 Artifacts (CKG-03) — Regression Check

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/graph/api/GraphQueryController.java` | REST endpoints for graph queries | VERIFIED | 4 @GetMapping endpoints confirmed; no modification in plan 03-04 |
| `src/main/java/com/esmp/graph/application/GraphQueryService.java` | Orchestrates Cypher queries via Neo4jClient | VERIFIED | Previously verified; no modification in plan 03-04 |
| `src/main/java/com/esmp/graph/persistence/GraphQueryRepository.java` | Repository with derived query methods | VERIFIED | Previously verified; no modification in plan 03-04 |
| `src/test/java/com/esmp/graph/api/GraphQueryControllerIntegrationTest.java` | Integration tests proving all 4 endpoints | VERIFIED | Previously verified; no modification in plan 03-04 |

---

## Key Link Verification

### Previously-Failed Key Links (BINDS_TO Gap)

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `VaadinPatternVisitor.java` | `ExtractionAccumulator` | `acc.addBindsToEdge(viewFqn, entityFqn, mechanism)` in `visitNewClass` | WIRED | Line 153: `acc.addBindsToEdge(enclosingFqn, entityFqn, bindingMechanism)` — called when bindingMechanism is non-null (BeanFieldGroup or FieldGroup only) |
| `LinkingService.java` | Neo4j BINDS_TO edges | Cypher `MERGE (view)-[r:BINDS_TO {bindingMechanism: $mechanism}]->(entity)` in `linkBindsToEdges()` | WIRED | Line 363: MERGE statement present with bindingMechanism as relationship key |
| `LinkingService.linkAllRelationships()` | `linkBindsToEdges()` | Method call within linkAllRelationships orchestration | WIRED | Line 62: `int bindsToCount = linkBindsToEdges(acc);` — called after linkPackageHierarchy(); result included in LinkingResult at lines 70-72 |

### Previously-Passing Key Links (Regression Check)

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `ExtractionService.java` | `LinkingService` | `linkingService.linkAllRelationships(accumulator)` | WIRED | Line 165 confirmed present; no regressions |
| `GraphQueryController` | `GraphQueryService` | Constructor injection, service method calls | WIRED | Previously verified; no modification in plan 03-04 |

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| CKG-01 | 03-01-PLAN | Graph stores Class, Method, Field, Annotation, Package, Module, UI View, Service, Repository, and DB Table nodes | SATISFIED | 4 new @Node entities (AnnotationNode, PackageNode, ModuleNode, DBTableNode). Service/Repository/UIView stored as dynamic labels on ClassNode via extraLabels. All node types persist via repositories. No change in plan 03-04. |
| CKG-02 | 03-02-PLAN | Graph stores CALLS, EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE relationships | SATISFIED | All 7 relationship types now wired end-to-end. BINDS_TO gap closed by plan 03-04: VaadinPatternVisitor detects BeanFieldGroup/FieldGroup instantiation, extracts entity FQN from generic type parameter, calls acc.addBindsToEdge(); LinkingService.linkBindsToEdges() materializes edges via idempotent MERGE; linkAllRelationships() includes the pass. Previous BLOCKED status is now SATISFIED. |
| CKG-03 | 03-03-PLAN | User can query the graph via structured API endpoints | SATISFIED | 4 REST endpoints operational: GET /api/graph/class/{fqn}, GET /api/graph/class/{fqn}/inheritance, GET /api/graph/repository/{fqn}/service-dependents, GET /api/graph/search?name=X. Integration test with 6 test cases. No change in plan 03-04. |

No orphaned requirements — all Phase 3 requirement IDs (CKG-01, CKG-02, CKG-03) are claimed by plans and now satisfied.

Note: REQUIREMENTS.md traceability table still shows CKG-01, CKG-02, CKG-03 with status "Pending" — this is a documentation lag in the traceability table, not a code gap. The `[x]` checkboxes in the v1 Requirements section correctly show them as complete.

---

## Anti-Patterns Found

None. Previous blockers (VaadinPatternVisitor had no addBindsToEdge call; LinkingService had no BINDS_TO pass) are fully resolved. No TODO/FIXME/placeholder patterns found in the 4 modified files.

---

## Human Verification Required

### 1. LinkingServiceIntegrationTest Full Suite Pass

**Test:** Run `./gradlew test --tests "com.esmp.extraction.application.LinkingServiceIntegrationTest"` and confirm all 6 tests pass (including the new `linkBindsToEdges_createsBindsToRelationship` test).
**Expected:** 6 tests pass. The SUMMARY claims PASS.
**Why human:** Cannot execute tests from static analysis. Previous verification noted pre-existing "No bean named 'neo4jTransactionManager'" failures — the 03-04 SUMMARY claims they are now resolved but test execution cannot be confirmed without running the container stack.

### 2. VaadinPatternVisitorTest BINDS_TO Test Pass

**Test:** Run `./gradlew test --tests "com.esmp.extraction.visitor.VaadinPatternVisitorTest"` and confirm all 6 tests pass including `detectsBindsToEdge_fromBeanFieldGroupInstantiation`.
**Expected:** 6 tests pass. The SUMMARY claims PASS.
**Why human:** Cannot execute tests from static analysis. The test relies on parsing `SampleVaadinForm.java` with BeanFieldGroup type resolution — type attribution depends on classpath being available at test time.

---

## Gaps Summary

No gaps remain. The single gap from the initial verification (BINDS_TO relationship not materialized) is fully closed:

**Root cause was two missing pipeline links — both now present:**

1. `VaadinPatternVisitor.visitNewClass()` now calls `acc.addBindsToEdge(enclosingFqn, entityFqn, bindingMechanism)` when it detects `new BeanFieldGroup<>(...)` or `new FieldGroup(...)` instantiation. Entity FQN is extracted from the generic type parameter via `JavaType.Parameterized.getTypeParameters().get(0)`. BeanItemContainer is correctly excluded (data source, not a form binding). Fallback to "Unknown" when no generic type is present.

2. `LinkingService.linkBindsToEdges()` is a new `@Transactional("neo4jTransactionManager")` method that iterates `acc.getBindsToEdges()` and runs `MERGE (view)-[r:BINDS_TO {bindingMechanism: $mechanism}]->(entity)` for each record. The MERGE includes bindingMechanism as a relationship key property, providing idempotency. `linkAllRelationships()` calls this method and includes `bindsToCount` as the 7th field in the `LinkingResult` record.

All 4 commits confirmed in git: `246ef79` (failing test for visitor), `52c5c68` (VaadinPatternVisitor fix), `1f88c3a` (failing integration test for LinkingService), `6c573fd` (LinkingService fix). One instantiation site of `new LinkingResult(...)` exists in the codebase and is confirmed to use the 7-argument form. No regressions detected in previously-verified artifacts.

---

_Verified: 2026-03-04T21:30:00Z_
_Verifier: Claude (gsd-verifier)_
_Re-verification: Yes — closes gap from 2026-03-04T20:30:00Z initial verification_
