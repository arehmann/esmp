---
phase: 02-ast-extraction
plan: 02
subsystem: extraction
tags: [openrewrite, ast, java-parser, visitor, call-graph, vaadin7, tdd]

# Dependency graph
requires:
  - phase: 02-ast-extraction
    plan: 01
    provides: OpenRewrite 8.74.3 on classpath, Neo4j domain model entities, 6 synthetic Vaadin 7 fixture files in src/test/resources/fixtures/
provides:
  - ClasspathLoader: reads classpath text file, filters non-existent JARs, handles missing file gracefully
  - JavaSourceParser: OpenRewrite type-attributed Java source parser with InMemoryExecutionContext error handling
  - ExtractionAccumulator: POJO collecting ClassNodeData, MethodNodeData, FieldNodeData, CallEdge, ComponentEdge in Maps/Lists with deduplication
  - ClassMetadataVisitor: JavaIsoVisitor extracting class FQN/package/annotations/modifiers/super/interfaces, method signatures, class-level fields
  - CallGraphVisitor: JavaIsoVisitor extracting directed CALLS edges between methods; filters JDK stdlib noise
  - VaadinPatternVisitor: JavaIsoVisitor marking VaadinView/VaadinComponent/VaadinDataBinding and extracting CONTAINS_COMPONENT edges
  - 23 unit tests covering all six extraction behaviors against synthetic fixtures
affects:
  - 02-ast-extraction plan 03 (Neo4j persistence — consumes ExtractionAccumulator output)
  - 02-ast-extraction plan 04 (REST trigger — orchestrates parse → visit → persist pipeline)
  - all future extraction tests

# Tech tracking
tech-stack:
  added: []
  patterns:
    - JavaIsoVisitor<ExtractionAccumulator> pattern — accumulator passed as type parameter P, no ExecutionContext needed as third arg to visit()
    - Full test classpath injection — tests write java.class.path JARs to temp file so Spring Data, JPA, and Vaadin types resolve in fixture parsing
    - Annotation name fallback — resolveAnnotationName() returns FQN when resolvable, simple name when type is <unknown> (incomplete classpath scenario)
    - Field extraction cursor check — getCursor().firstEnclosing(J.MethodDeclaration.class) == null to detect class-level fields vs local variables

key-files:
  created:
    - src/main/java/com/esmp/extraction/parser/ClasspathLoader.java
    - src/main/java/com/esmp/extraction/parser/JavaSourceParser.java
    - src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java
    - src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java
    - src/main/java/com/esmp/extraction/visitor/CallGraphVisitor.java
    - src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java
    - src/test/java/com/esmp/extraction/parser/JavaSourceParserTest.java
    - src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java
    - src/test/java/com/esmp/extraction/visitor/CallGraphVisitorTest.java
    - src/test/java/com/esmp/extraction/visitor/VaadinPatternVisitorTest.java
  modified: []

key-decisions:
  - "JavaTypeCache is in org.openrewrite.java.internal (internal package) — must be imported explicitly; no public factory method available"
  - "visitor.visit() signature is visit(Tree, P) with no ExecutionContext argument — P is the accumulator type passed directly"
  - "Inherited JPA methods (findAll, save) have Spring Data parent interface (e.g., ListCrudRepository) as declaring type, not the subinterface — call graph tests use custom repository methods (findByName) or asserting without SampleRepository specificity"
  - "Test classpath must include ALL project JARs (java.class.path) not just Vaadin JAR for accurate Spring/JPA type resolution in visitor tests"
  - "Annotation FQN fallback: when OpenRewrite type resolution returns <unknown> (incomplete classpath), fall back to annotation simple name"

patterns-established:
  - "Extraction pipeline: ClasspathLoader -> JavaSourceParser -> [ClassMetadataVisitor, CallGraphVisitor, VaadinPatternVisitor] -> ExtractionAccumulator"
  - "Visitor pattern: extends JavaIsoVisitor<ExtractionAccumulator>, accumulator is the P type parameter, call super.visit*() in every override"
  - "Test classpath: write java.class.path entries to temp file for full type attribution in unit tests"

requirements-completed: [AST-01, AST-02, AST-03]

# Metrics
duration: 15min
completed: 2026-03-04
---

# Phase 2 Plan 02: AST Extraction Pipeline Summary

**OpenRewrite-based Java source parser with type-attributed classpath, ExtractionAccumulator POJO, and three JavaIsoVisitors extracting class metadata, call graphs, and Vaadin 7 patterns — all verified by 23 unit tests against synthetic fixtures**

## Performance

- **Duration:** 15 min
- **Started:** 2026-03-04T15:57:00Z
- **Completed:** 2026-03-04T16:12:28Z
- **Tasks:** 2 (each TDD: RED commit + GREEN commit)
- **Files modified:** 10 created

## Accomplishments

- OpenRewrite parser pipeline with type-attributed classpath loading via `ClasspathLoader` and graceful error handling in `JavaSourceParser`
- `ExtractionAccumulator` POJO with record inner types (`ClassNodeData`, `MethodNodeData`, `FieldNodeData`, `CallEdge`, `ComponentEdge`) and deduplication via Map keying
- Three `JavaIsoVisitor` implementations covering AST-02 (class/method/field metadata), AST-03 (call graph edges), and Vaadin 7 pattern detection (VaadinView, VaadinComponent, VaadinDataBinding, CONTAINS_COMPONENT edges)
- 23 unit tests across 4 test classes — all passing against synthetic Vaadin 7 fixture files

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Failing tests for ClasspathLoader, JavaSourceParser, ExtractionAccumulator** - `0f41612` (test)
2. **Task 1 GREEN: Implement ClasspathLoader, JavaSourceParser, ExtractionAccumulator** - `1abb762` (feat)
3. **Task 2 RED: Failing visitor tests for ClassMetadataVisitor, CallGraphVisitor, VaadinPatternVisitor** - `dbbe093` (test)
4. **Task 2 GREEN: Implement visitors with full test suite** - `0ca2145` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/extraction/parser/ClasspathLoader.java` - Reads classpath text file, filters non-existent JARs, normalizes Windows/Linux path separators
- `src/main/java/com/esmp/extraction/parser/JavaSourceParser.java` - OpenRewrite type-attributed parser with InMemoryExecutionContext warning logger
- `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` - POJO with Map<String, ClassNodeData> + Map<String, MethodNodeData> + Map<String, FieldNodeData> + List<CallEdge> + List<ComponentEdge> + VaadinView/Component/DataBinding sets
- `src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java` - Extracts class FQN, package, annotations, modifiers, super, interfaces, methods, class-level fields; uses cursor for field vs local var detection
- `src/main/java/com/esmp/extraction/visitor/CallGraphVisitor.java` - Extracts directed CALLS edges; filters java.lang/util/io/nio JDK noise; handles null getMethodType() gracefully
- `src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java` - Detects VaadinView (implements View / extends UI), VaadinComponent (new com.vaadin.ui.* expressions), VaadinDataBinding (BeanFieldGroup), CONTAINS_COMPONENT edges from addComponent() calls
- `src/test/java/com/esmp/extraction/parser/JavaSourceParserTest.java` - 8 tests covering classpath loading, fixture parsing, malformed file handling, accumulator operations
- `src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java` - 7 tests: SampleService FQN, @Service annotation, 3 methods, @Autowired field, SampleRepository interface, SampleEntity @Entity, 6 classes total
- `src/test/java/com/esmp/extraction/visitor/CallGraphVisitorTest.java` - 3 tests: findByName call edge, findAll call edge, non-empty edges
- `src/test/java/com/esmp/extraction/visitor/VaadinPatternVisitorTest.java` - 5 tests: VaadinView marks, VaadinDataBinding, CONTAINS_COMPONENT edges, VaadinComponent from new expressions

## Decisions Made

- **`JavaTypeCache` is in internal package:** `org.openrewrite.java.internal.JavaTypeCache` — no public API alternative; accessed directly as internal class for the `typeCache()` builder method on `JavaParser.Builder`.
- **`visitor.visit()` takes `(Tree, P)` not `(Tree, P, ExecutionContext)`:** The accumulator is the P type parameter; no ExecutionContext is passed to `visit()` (it's for the `TreeVisitor` base, not visitor dispatch).
- **Inherited JPA method declaring types:** `SampleRepository.findAll()` is inherited from `org.springframework.data.repository.ListCrudRepository` — the declaring type in the call graph edge is the Spring Data parent, not `SampleRepository`. Tests use `findByName` (custom method) for precise SampleRepository-specific assertions.
- **Full test classpath:** Tests write all JARs from `java.class.path` to a temp classpath file. Passing only the Vaadin JAR caused Spring/JPA type resolution to fail, producing `<unknown>` FQNs and preventing annotation/call-graph tests from passing.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed JavaTypeCache import to use internal package**
- **Found during:** Task 1 (JavaSourceParser compilation)
- **Issue:** `import org.openrewrite.java.JavaTypeCache` resolves as not found — class is in `org.openrewrite.java.internal` package
- **Fix:** Changed import to `org.openrewrite.java.internal.JavaTypeCache`
- **Files modified:** JavaSourceParser.java
- **Verification:** `./gradlew compileJava` succeeds
- **Committed in:** `1abb762` (Task 1 feat commit)

**2. [Rule 1 - Bug] Fixed visitor.visit() API call in tests**
- **Found during:** Task 2 (test compilation)
- **Issue:** Tests called `visitor.visit(source, acc, ctx)` with `InMemoryExecutionContext` as third arg; actual API is `visit(Tree, P)` — no ExecutionContext argument
- **Fix:** Removed `InMemoryExecutionContext` from visitor call sites; removed import
- **Files modified:** ClassMetadataVisitorTest.java, CallGraphVisitorTest.java, VaadinPatternVisitorTest.java
- **Verification:** `./gradlew compileTestJava` succeeds
- **Committed in:** `0ca2145` (Task 2 feat commit)

**3. [Rule 1 - Bug] Fixed annotation name fallback for unresolved types**
- **Found during:** Task 2 (ClassMetadataVisitorTest failures)
- **Issue:** `resolveAnnotationName()` returned `<unknown>` FQN when annotation type unresolved; test assertion `a.contains("Service")` failed against `<unknown>`
- **Fix:** Added check in `resolveAnnotationName()` — if FQN starts with `<`, fall back to `annotation.getSimpleName()`
- **Files modified:** ClassMetadataVisitor.java
- **Verification:** 7 ClassMetadataVisitorTest tests pass
- **Committed in:** `0ca2145` (Task 2 feat commit)

**4. [Rule 1 - Bug] Fixed test classpath to include all JARs for full type resolution**
- **Found during:** Task 2 (CallGraphVisitorTest failures)
- **Issue:** Tests only passed Vaadin JAR to parser; Spring Data JARs absent so `repository.findAll()` had `getMethodType() == null`; `SampleService#findAll` had no outgoing call edges
- **Fix:** Changed `buildVaadinClasspathFile()` to write all JARs from `System.getProperty("java.class.path")` to temp file
- **Files modified:** ClassMetadataVisitorTest.java, CallGraphVisitorTest.java, VaadinPatternVisitorTest.java
- **Verification:** All 3 CallGraphVisitorTest tests pass; class/field annotations resolve to non-<unknown> values
- **Committed in:** `0ca2145` (Task 2 feat commit)

**5. [Rule 1 - Bug] Fixed call graph test assertion for inherited JPA method declaring types**
- **Found during:** Task 2 (CallGraphVisitorTest — detectsCallFromSampleService_findAll failing)
- **Issue:** Test expected `calleeMethodId().contains("SampleRepository")` for `findAll` and `save`, but these methods are inherited from Spring Data parent interfaces (e.g., `ListCrudRepository`), so the callee declaring type is the parent, not `SampleRepository`
- **Fix:** Renamed test to `detectsCallFromSampleService_findByName_to_repository_findByName` using the custom query method; added a looser assertion test for `findAll` without SampleRepository specificity
- **Files modified:** CallGraphVisitorTest.java
- **Verification:** All 3 CallGraphVisitorTest tests pass
- **Committed in:** `0ca2145` (Task 2 feat commit)

---

**Total deviations:** 5 auto-fixed (2 Rule 1 bugs on visitor API, 3 Rule 1 bugs on test correctness)
**Impact on plan:** All auto-fixes were necessary for correctness. No scope creep. All plan deliverables met.

## Issues Encountered

- `JavaTypeCache` in internal package required `org.openrewrite.java.internal` import — not a public API but required by `JavaParser.Builder.typeCache()` method signature
- OpenRewrite `JavaIsoVisitor` visitor dispatch does not take `ExecutionContext` as argument to `visit()` — the accumulator IS the P type parameter; ExecutionContext is only needed when constructing `InMemoryExecutionContext` for the parser itself
- Inherited JPA method resolution — Spring Data's `findAll()` and `save()` have the repository base interface as declaring type, not the user-defined subinterface; only custom query methods declared directly on `SampleRepository` resolve to `SampleRepository` as declaring type

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Plan 03 (Neo4j persistence) can now consume `ExtractionAccumulator` output directly
- `ExtractionAccumulator` has all the data structures Plan 03 needs for `ClassNode`, `MethodNode`, `FieldNode`, `CallsRelationship`, and `ContainsComponentRelationship` persistence
- Vaadin secondary labels (VaadinView, VaadinComponent, VaadinDataBinding) are in `acc.getVaadinViews()`, `acc.getVaadinComponents()`, `acc.getVaadinDataBindings()` — Plan 03 should apply these as `@DynamicLabels` when persisting `ClassNode`
- Plan 04 (REST trigger) can instantiate `JavaSourceParser` and the three visitors and wire them together into an `ExtractionService`
- Remaining concern from STATE.md: OpenRewrite Vaadin 7 recipe coverage audit still required — Plans 02-03 focus on extraction, recipe audit is later

## Self-Check: PASSED
