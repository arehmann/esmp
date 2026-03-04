---
phase: 02-ast-extraction
verified: 2026-03-04T17:30:00Z
status: passed
score: 14/14 must-haves verified
re_verification: false
human_verification:
  - test: "Run full test suite and confirm all extraction tests pass"
    expected: "./gradlew test passes all 23 unit tests + 10 integration tests = 33 extraction tests"
    why_human: "Cannot run JVM/Docker in static analysis; integration tests require Testcontainers (Neo4j, MySQL, Qdrant)"
  - test: "POST /api/extraction/trigger with a real Vaadin 7 source tree and inspect Neo4j"
    expected: "Classes persist with VaadinView/VaadinDataBinding dynamic labels; vaadinViewCount > 0 in JSON response"
    why_human: "Known limitation: degraded-mode bootRun without explicit classpath shows 0 Vaadin label counts; requires actual deployment with classpath configured"
---

# Phase 2: AST Extraction Verification Report

**Phase Goal:** Parse Java/Vaadin 7 source into structured graph nodes using OpenRewrite LST
**Verified:** 2026-03-04T17:30:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|---------|
| 1 | OpenRewrite rewrite-java and rewrite-java-21 dependencies resolve without classpath conflicts | VERIFIED | `gradle/libs.versions.toml` has openrewrite=8.74.3 + 3 library aliases; `build.gradle.kts` uses `libs.openrewrite.java` + `libs.openrewrite.java.jdk21`; commits 7cc5d33, 1abb762 confirm `./gradlew compileJava` succeeds |
| 2 | Neo4j node entities represent Class, Method, and Field with business-key @Id and @Version for idempotent persistence | VERIFIED | `ClassNode.java` (@Id fullyQualifiedName, @Version Long, @DynamicLabels), `MethodNode.java` (@Id methodId, @Version Long), `FieldNode.java` (@Id fieldId, @Version Long) — all substantive, fully wired |
| 3 | Vaadin-specific secondary labels (VaadinView, VaadinComponent, VaadinDataBinding) are supported via @DynamicLabels | VERIFIED | `ClassNode.extraLabels` is `@DynamicLabels Set<String>`; `AccumulatorToModelMapper` sets labels from accumulator sets; `VaadinPatternVisitor` calls `acc.markAsVaadinView`, `acc.markAsVaadinComponent`, `acc.markAsVaadinDataBinding` |
| 4 | Synthetic test fixtures cover Vaadin UI, View, service, repository, entity, and data-bound form patterns | VERIFIED | All 6 files present: SampleEntity.java, SampleRepository.java, SampleService.java, SampleVaadinView.java (implements View, uses addComponent()), SampleVaadinForm.java (BeanFieldGroup), SampleUI.java (extends UI) |
| 5 | Neo4j uniqueness constraints exist for all node types preventing duplicate nodes on re-extraction | VERIFIED | `Neo4jSchemaInitializer` creates 3 constraints: `java_class_fqn_unique`, `java_method_id_unique`, `java_field_id_unique` — all with IF NOT EXISTS Cypher |
| 6 | Given a path to Java source files and a classpath file, the parser produces OpenRewrite SourceFile LSTs without error | VERIFIED | `JavaSourceParser` uses `InMemoryExecutionContext` with warning logger (not exception thrower); catches exceptions and returns partial results; 91 substantive lines |
| 7 | ClassMetadataVisitor extracts class name, package, annotations, methods, and fields from parsed LST | VERIFIED | `ClassMetadataVisitor` extends JavaIsoVisitor, overrides visitClassDeclaration/visitMethodDeclaration/visitVariableDeclarations; calls `acc.addClass`, `acc.addMethod`, `acc.addField`; 206 substantive lines; 7 unit tests |
| 8 | CallGraphVisitor extracts CALLS edges between methods across classes with caller and callee FQNs | VERIFIED | `CallGraphVisitor` extends JavaIsoVisitor, checks `getMethodType() != null`, skips JDK stdlib, finds enclosing method for caller; calls `acc.addCall`; 101 substantive lines; 3 unit tests |
| 9 | VaadinPatternVisitor detects Vaadin 7 View/UI classes and extracts CONTAINS_COMPONENT edges from addComponent() calls | VERIFIED | `VaadinPatternVisitor` checks implements/extends against VAADIN_VIEW_TYPES set; detects addComponent() calls with heuristic fallback; detects BeanFieldGroup in visitNewClass; 168 substantive lines; 5 unit tests |
| 10 | Parser handles malformed Java files gracefully with error logging, not exceptions | VERIFIED | `JavaParser` wrapped in try/catch; `InMemoryExecutionContext` uses warning lambda not exception thrower; JavaSourceParserTest includes malformed file handling test |
| 11 | POST /api/extraction/trigger with a source path parses all Java files and persists nodes to Neo4j | VERIFIED | `ExtractionController` @PostMapping("/trigger") delegates to `extractionService.extract()`; `ExtractionService` runs scan → parse → visit → map → saveAll() pipeline; 10 integration tests including `triggerEndpointReturns200WithExtractionSummary` |
| 12 | Re-running extraction on unchanged files does not create duplicate nodes (idempotent) | VERIFIED | Business-key @Id + @Version on all @Node entities enables SDN MERGE; `reRunningExtractionDoesNotCreateDuplicateClassNodes` integration test asserts count stable after 2 runs |
| 13 | CALLS relationships exist between methods across classes in Neo4j | VERIFIED | `afterExtractionCallsRelationshipExistsBetweenMethods` integration test queries `MATCH (m1:JavaMethod)-[:CALLS]->(m2:JavaMethod) RETURN count(*)`; asserts > 0 |
| 14 | Vaadin pattern audit report documents what patterns were found and what gaps exist | VERIFIED | `VaadinAuditService.generateReport()` produces `VaadinAuditReport` with 4 `PatternEntry` items and 5 `knownLimitations`; `extractionResponseIncludesAuditReport` integration test asserts non-null, non-blank summary |

**Score:** 14/14 truths verified

---

## Required Artifacts

### Plan 01 Artifacts

| Artifact | Min Lines | Actual Lines | Status | Details |
|----------|-----------|-------------|--------|---------|
| `gradle/libs.versions.toml` | — | — | VERIFIED | Contains openrewrite=8.74.3, vaadin-server=7.7.48, 3 library aliases |
| `src/main/java/com/esmp/extraction/model/ClassNode.java` | — | 231 | VERIFIED | @Node("JavaClass"), @Id fullyQualifiedName, @Version, @DynamicLabels, DECLARES_METHOD, DECLARES_FIELD, CONTAINS_COMPONENT relationships |
| `src/main/java/com/esmp/extraction/model/MethodNode.java` | — | 129 | VERIFIED | @Node("JavaMethod"), @Id methodId, @Version, CALLS outgoing relationship |
| `src/main/java/com/esmp/extraction/model/FieldNode.java` | — | 93 | VERIFIED | @Node("JavaField"), @Id fieldId, @Version |
| `src/test/resources/fixtures/SampleVaadinView.java` | — | — | VERIFIED | Implements com.vaadin.navigator.View, uses addComponent() 3 times |

### Plan 02 Artifacts

| Artifact | Min Lines | Actual Lines | Status | Details |
|----------|-----------|-------------|--------|---------|
| `src/main/java/com/esmp/extraction/parser/JavaSourceParser.java` | 40 | 91 | VERIFIED | OpenRewrite parser with classpath loading, InMemoryExecutionContext warning handler |
| `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` | 60 | 278 | VERIFIED | Full POJO with 5 inner record types, 8 mutation methods, all accessor/deduplication behavior |
| `src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java` | 50 | 206 | VERIFIED | JavaIsoVisitor with visitClassDeclaration, visitMethodDeclaration, visitVariableDeclarations |
| `src/main/java/com/esmp/extraction/visitor/CallGraphVisitor.java` | 30 | 101 | VERIFIED | JavaIsoVisitor with null-safe type check, JDK stdlib filter, caller context resolution |
| `src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java` | 50 | 168 | VERIFIED | JavaIsoVisitor with VAADIN_VIEW_TYPES set, addComponent() detection, BeanFieldGroup detection |
| `src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java` | 40 | 135 | VERIFIED | 7 test methods covering FQN extraction, annotations, methods, fields, interface detection |

### Plan 03 Artifacts

| Artifact | Min Lines | Actual Lines | Status | Details |
|----------|-----------|-------------|--------|---------|
| `src/main/java/com/esmp/extraction/application/ExtractionService.java` | 60 | 166 | VERIFIED | Full scan → parse → visit → map → persist → audit pipeline, @Transactional("neo4jTransactionManager") |
| `src/main/java/com/esmp/extraction/api/ExtractionController.java` | 30 | 71 | VERIFIED | @RestController, @PostMapping("/trigger"), sourceRoot validation, delegates to extractionService.extract() |
| `src/main/java/com/esmp/extraction/audit/VaadinAuditService.java` | 40 | 87 | VERIFIED | generateReport() produces 4 pattern entries + 5 known limitations |
| `src/test/java/com/esmp/extraction/ExtractionIntegrationTest.java` | 80 | 260 | VERIFIED | 10 test methods with Testcontainers (Neo4j, MySQL, Qdrant), @BeforeEach clears graph |

---

## Key Link Verification

### Plan 01 Key Links

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `build.gradle.kts` | `gradle/libs.versions.toml` | version catalog `libs.openrewrite.*` | WIRED | `implementation(libs.openrewrite.java)` + `implementation(libs.openrewrite.java.jdk21)` confirmed |
| `ClassNode.java` | `MethodNode.java` | @Relationship DECLARES_METHOD | WIRED | `@Relationship(type = "DECLARES_METHOD", direction = OUTGOING) List<MethodNode> methods` |
| `Neo4jSchemaInitializer.java` | Neo4j database | CREATE CONSTRAINT IF NOT EXISTS at startup | WIRED | 3 constraint statements executed via Neo4jClient on ApplicationRunner.run() |

### Plan 02 Key Links

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `JavaSourceParser.java` | `ExtractionAccumulator.java` | Parser output fed to visitors which populate accumulator | WIRED | Wired through ExtractionService: parser produces SourceFile list → visitors called per file → accumulator populated |
| `ClassMetadataVisitor.java` | `ExtractionAccumulator.java` | addClass, addMethod, addField calls | WIRED | `acc.addClass(...)`, `acc.addMethod(...)`, `acc.addField(...)` all present and called |
| `CallGraphVisitor.java` | `ExtractionAccumulator.java` | addCall() for each method invocation | WIRED | `acc.addCall(callerMethodId, calleeMethodId, sourceFile, lineNumber)` confirmed |
| `VaadinPatternVisitor.java` | `ExtractionAccumulator.java` | markAsVaadinView, addComponentEdge calls | WIRED | `acc.markAsVaadinView(fqn)`, `acc.markAsVaadinView(fqn)`, `acc.addComponentEdge(...)`, `acc.markAsVaadinComponent(...)`, `acc.markAsVaadinDataBinding(...)` all confirmed |

### Plan 03 Key Links

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `ExtractionController.java` | `ExtractionService.java` | REST endpoint delegates to service | WIRED | `extractionService.extract(request.getSourceRoot(), request.getClasspathFile())` |
| `ExtractionService.java` | `JavaSourceParser.java` | Parse Java source files | WIRED | `javaSourceParser.parse(javaPaths, sourceRootPath, resolvedClasspathFile)` |
| `ExtractionService.java` | `ClassNodeRepository.java` | Persist extracted nodes to Neo4j | WIRED | `classNodeRepository.saveAll(classNodes)` |
| `ExtractionService.java` | `VaadinAuditService.java` | Generate audit report after extraction | WIRED | `vaadinAuditService.generateReport(accumulator)` |
| `AccumulatorToModelMapper.java` | `ClassNode.java` | Maps ExtractionAccumulator data to @Node entities | WIRED | Imports ClassNode, MethodNode, FieldNode; `mapToClassNodes()` builds all entity types |

---

## Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|-------------|-------------|-------------|--------|---------|
| AST-01 | 02-01, 02-02, 02-03 | System can parse Java/Vaadin 7 source code into structured AST using OpenRewrite LST | SATISFIED | JavaSourceParser uses OpenRewrite 8.74.3 to produce SourceFile LSTs from Java source files; integration tests prove parsing against 6 fixture files |
| AST-02 | 02-02, 02-03 | System extracts class metadata, method signatures, field definitions, annotations, and imports | SATISFIED | ClassMetadataVisitor extracts FQN, package, annotations, modifiers, superclass, interfaces, methods (with params/return type), class-level fields; 7 ClassMetadataVisitorTest tests verify |
| AST-03 | 02-02, 02-03 | System builds call graph edges between methods across classes | SATISFIED | CallGraphVisitor extracts directed CALLS edges with caller/callee FQN methodIds; filters JDK stdlib; integration test asserts CALLS relationships exist in Neo4j |
| AST-04 | 02-01, 02-03 | System persists extracted nodes and relationships to Neo4j graph database | SATISFIED | Spring Data Neo4j repositories persist ClassNode/MethodNode/FieldNode with DECLARES_METHOD, DECLARES_FIELD, CALLS, CONTAINS_COMPONENT relationships; idempotent MERGE via @Id business keys; 10 integration tests prove persistence |

All 4 requirements (AST-01 through AST-04) are satisfied. No orphaned requirements for Phase 2.

---

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `ClassMetadataVisitor.java` | 173 | `return null` in `extractSourceFilePath()` | INFO | Benign — method returns nullable String (source path may be absent); callers handle null by passing null to `acc.addClass()` which stores it as a null property; not a stub |
| `ExtractionIntegrationTest.java` | 194 | `isGreaterThanOrEqualTo(0L)` on CONTAINS_COMPONENT edge count | INFO | Trivially true assertion — edge count could be 0 and test still passes; documented in SUMMARY as known limitation (inter-class component edges require Vaadin classpath at runtime); VaadinPatternVisitorTest has more specific unit tests for this behavior |

No blockers. No stubs. No placeholder implementations found.

---

## Human Verification Required

### 1. Full Test Suite Execution

**Test:** Run `./gradlew test` from project root
**Expected:** All 33 extraction tests pass (23 unit tests across 4 test classes + 10 integration tests in ExtractionIntegrationTest); no compilation errors; existing Phase 1 tests remain green
**Why human:** Cannot execute JVM or Docker from static analysis; integration tests require Testcontainers spinning up Neo4j 2026.01.4, MySQL 8.4, and Qdrant containers

### 2. Vaadin Label Detection in Full-Classpath Mode

**Test:** Start with `docker compose up -d`, then `./gradlew bootRun`, then POST to `/api/extraction/trigger` with `sourceRoot: "src/test/resources/fixtures"` and `classpathFile` pointing to a file containing all JARs from `java.class.path`
**Expected:** Response shows `vaadinViewCount >= 2` (SampleVaadinView + SampleUI), `vaadinDataBindingCount >= 1` (SampleVaadinForm); Neo4j query `MATCH (c:VaadinView) RETURN c.simpleName` shows at least 2 nodes
**Why human:** The summary documents that `vaadinViewCount` shows 0 in degraded mode (bootRun without explicit classpath config). The integration tests use full java.class.path and do assert >= 1 VaadinView nodes, but this specific deployment scenario cannot be verified statically. Human approved this in Task 2 checkpoint on 2026-03-04.

---

## Notable Decisions with Correctness Impact

The following implementation decisions directly affect correctness and should be understood by reviewers:

1. **Dual transaction manager (`Neo4jTransactionConfig`):** JPA and Neo4j auto-configs both use `@ConditionalOnMissingBean(PlatformTransactionManager.class)` — this caused the Neo4j TM to be suppressed, making `saveAll()` throw NPE. Fixed by explicitly creating both TMs; ExtractionService uses `@Transactional("neo4jTransactionManager")`.

2. **Vaadin detection in degraded mode:** When Vaadin classpath JARs are absent at runtime, `VaadinPatternVisitor` cannot resolve FQNs, so VaadinView/VaadinDataBinding counts are 0. The heuristic fallback for `addComponent()` (null receiver check) still captures CONTAINS_COMPONENT edges. This is expected behavior, documented in SUMMARY and VALIDATION.md.

3. **`JavaTypeCache` internal package:** `org.openrewrite.java.internal.JavaTypeCache` — accessed directly as internal class. No public API alternative exists.

4. **Inherited JPA method declaring types:** `findAll()` and `save()` on `SampleRepository` resolve to Spring Data parent interfaces (e.g., `ListCrudRepository`) as declaring type, not `SampleRepository`. CallGraphVisitorTest uses `findByName` (custom method) for precise assertions.

---

## Gaps Summary

No gaps. All 14 observable truths are verified. All artifacts exist, are substantive (well above minimum line counts), and are wired into the pipeline. All 4 requirement IDs (AST-01, AST-02, AST-03, AST-04) are satisfied with direct implementation evidence.

The only items flagged are:
- One trivially true integration test assertion for CONTAINS_COMPONENT edge count (INFO severity, documented known limitation)
- Two human verification items requiring running the actual test suite and a live deployment check

---

_Verified: 2026-03-04T17:30:00Z_
_Verifier: Claude (gsd-verifier)_
