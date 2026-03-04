---
phase: 03-code-knowledge-graph
plan: 06
subsystem: extraction
tags: [neo4j, openrewrite, vaadin7, annotations, jpa, graph-traversal]

# Dependency graph
requires:
  - phase: 03-code-knowledge-graph
    provides: LinkingService, ExtractionAccumulator, VaadinPatternVisitor, ClassMetadataVisitor

provides:
  - HAS_ANNOTATION edges now created correctly (annotation FQNs normalized in ClassMetadataVisitor)
  - QUERIES edges now created via graph traversal (repository -> entity -> DBTable)
  - BINDS_TO edges now emitted via simple-name fallback when Vaadin JARs are absent

affects:
  - UAT verification
  - ExtractionService (calls LinkingService)
  - Any phase relying on HAS_ANNOTATION, QUERIES, or BINDS_TO edge presence

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Simple-name-to-FQN switch fallback for annotation resolution when classpath lacks annotation JARs"
    - "Graph-native traversal (DEPENDS_ON|IMPLEMENTS*1..3 -> MAPS_TO_TABLE) for repository-to-table resolution"
    - "Simple-name-based Vaadin type detection as fallback when Vaadin JARs absent from parser classpath"

key-files:
  created: []
  modified:
    - src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java
    - src/main/java/com/esmp/extraction/application/LinkingService.java
    - src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java
    - src/test/java/com/esmp/extraction/application/LinkingServiceIntegrationTest.java
    - src/test/java/com/esmp/extraction/visitor/VaadinPatternVisitorTest.java
    - src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java

key-decisions:
  - "ClassMetadataVisitor annotation FQN normalization: switch-based simple-name-to-FQN fallback mirrors JpaPatternVisitor pattern, ensures c.annotations stores FQNs matching JavaAnnotation nodes for HAS_ANNOTATION linking"
  - "QUERIES edge graph traversal: DEPENDS_ON|IMPLEMENTS*1..3 pattern avoids parsing generic type parameters from JpaRepository<Entity, ID> — graph-native and works for any entity-repository relationship depth"
  - "BINDS_TO simple-name fallback: BeanItemContainer excluded from fallback set (data source, not form binding); entity FQN falls back to 'Unknown' when generics unresolvable without Vaadin JARs"
  - "VaadinComponent simple-name fallback: 13 common Vaadin UI widget names added to VAADIN_UI_SIMPLE_NAMES set for detection without FQN resolution"

patterns-established:
  - "Pattern: When OpenRewrite type resolution may fail due to absent JARs, add a simple-name switch fallback covering the most common names in that package"
  - "Pattern: For cross-entity graph traversal (repo to entity), use variable-length Cypher path queries rather than accumulator map lookups — relies on already-persisted MAPS_TO_TABLE/IMPLEMENTS edges"

requirements-completed: [CKG-02]

# Metrics
duration: 20min
completed: 2026-03-05
---

# Phase 03 Plan 06: Gap Closure — HAS_ANNOTATION, QUERIES, BINDS_TO Summary

**Fixed three missing relationship edge types (HAS_ANNOTATION, QUERIES, BINDS_TO) via annotation FQN normalization in ClassMetadataVisitor, graph-native repository-to-table traversal in LinkingService, and simple-name fallback in VaadinPatternVisitor**

## Performance

- **Duration:** 20 min
- **Started:** 2026-03-04T22:45:09Z
- **Completed:** 2026-03-05T00:05:00Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- ClassMetadataVisitor.resolveAnnotationName() now maps 10 common simple annotation names (Entity, Table, Service, Repository, Controller, RestController, Component, Autowired, Inject, Query) to their FQNs via switch fallback, ensuring c.annotations list stores FQNs that match JavaAnnotation nodes for HAS_ANNOTATION edge creation
- LinkingService.linkQueryMethods() replaced brittle tableMappings map lookup with a Cypher graph traversal that traverses DEPENDS_ON|IMPLEMENTS edges (up to 3 hops) from the repository class to find an entity with a MAPS_TO_TABLE edge — eliminates the need to parse JpaRepository generic type parameters
- VaadinPatternVisitor now detects BeanFieldGroup and FieldGroup instantiation via simple class name when Vaadin JARs are absent from the parser classpath, emitting BINDS_TO edge data with "Unknown" entity FQN fallback; also detects 13 common Vaadin UI widget names for VaadinComponent marking

## Task Commits

Each task was committed atomically:

1. **Task 1: Fix HAS_ANNOTATION FQN mismatch and QUERIES repository-to-entity resolution** - `05c04b1` (fix)
2. **Task 2: Add simple-name fallback for BINDS_TO detection in VaadinPatternVisitor** - `4feda21` (fix)

## Files Created/Modified

- `src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java` - Added FQN normalization switch in resolveAnnotationName(); stores FQNs instead of simple names in c.annotations
- `src/main/java/com/esmp/extraction/application/LinkingService.java` - Replaced tableMappings lookup in linkQueryMethods() with Cypher graph traversal (DEPENDS_ON|IMPLEMENTS*1..3 -> MAPS_TO_TABLE)
- `src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java` - Added VAADIN_BINDING_SIMPLE_NAMES and VAADIN_UI_SIMPLE_NAMES sets; added else-branch with simple-name fallback in visitNewClass()
- `src/test/java/com/esmp/extraction/application/LinkingServiceIntegrationTest.java` - Added integration tests for HAS_ANNOTATION and QUERIES edge creation
- `src/test/java/com/esmp/extraction/visitor/VaadinPatternVisitorTest.java` - Added unit tests for BeanFieldGroup, FieldGroup, BeanItemContainer exclusion, Button component via simple-name fallback and FQN regression
- `src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java` - Fixed pre-existing compile error (parse() API change)

## Decisions Made

- Annotation FQN normalization via switch covers the 10 most common annotations; unknown simple names pass through unchanged (same behavior as before for unrecognized annotations)
- Graph traversal uses DEPENDS_ON|IMPLEMENTS with 1..3 hop limit — sufficient for direct and 1-hop repository relationships without unbounded traversal risk
- BINDS_TO simple-name fallback excludes BeanItemContainer (per existing convention from Plan 04) — it is a data source, not a form-to-entity binding mechanism
- Entity FQN in simple-name BINDS_TO fallback is "Unknown" — generic type params cannot be resolved without Vaadin JARs on classpath

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed pre-existing compile error in ClassMetadataVisitorTest**
- **Found during:** Task 1 (attempting to run new tests)
- **Issue:** ClassMetadataVisitorTest.parseInlineWithoutClasspath() called `parser.parse(List.of(source), ctx)` but the OpenRewrite JavaParser API signature changed — correct form is `parser.parse(ctx, source...)`
- **Fix:** Changed to `parser.parse(ctx, source)` using the varargs overload
- **Files modified:** src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java
- **Verification:** `./gradlew compileTestJava` succeeds
- **Committed in:** 05c04b1 (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Pre-existing test compile error — not caused by current task, fixed to unblock compilation. No scope creep.

## Issues Encountered

- Gradle intermittently throws `NoSuchFileException` for binary test results file during test runs — this is a known Gradle bug and does not indicate test failures; BUILD SUCCESSFUL was confirmed on re-runs

## Next Phase Readiness

- All three UAT gap edge types are now produced during extraction: HAS_ANNOTATION, QUERIES, and BINDS_TO (with simple-name fallback)
- Full test suite passes (all existing tests + new tests)
- Ready for UAT re-verification to confirm gap closure

---
*Phase: 03-code-knowledge-graph*
*Completed: 2026-03-05*
