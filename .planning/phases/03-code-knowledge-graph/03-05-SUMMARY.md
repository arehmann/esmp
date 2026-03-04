---
phase: 03-code-knowledge-graph
plan: 05
subsystem: graph
tags: [neo4j, neo4jclient, cypher, spring-data-neo4j, openrewrite, stereotype, dynamic-labels]

# Dependency graph
requires:
  - phase: 03-code-knowledge-graph
    provides: ClassMetadataVisitor, GraphQueryService, GraphQueryRepository — stereotype detection and search infrastructure
provides:
  - Simple-name fallback in SERVICE_STEREOTYPES and REPOSITORY_STEREOTYPES for unresolved annotation types
  - searchByName using Neo4jClient Cypher with labels() for correct dynamic label hydration
affects: [04-graph-validation, 05-rag-embedding]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Stereotype set dual-entry: include both FQN and simple name so unresolved annotations still match"
    - "Neo4jClient Cypher over SDN derived query when @DynamicLabels hydration is required"

key-files:
  created: []
  modified:
    - src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java
    - src/main/java/com/esmp/graph/application/GraphQueryService.java
    - src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java
    - src/test/java/com/esmp/graph/api/GraphQueryControllerIntegrationTest.java

key-decisions:
  - "SERVICE_STEREOTYPES and REPOSITORY_STEREOTYPES include both FQNs and simple names so stereotype labels apply when OpenRewrite cannot resolve annotation types from the parse classpath"
  - "searchByName() replaced with Neo4jClient Cypher using labels(c) — SDN derived query (findBySimpleNameContainingIgnoreCase) does not hydrate @DynamicLabels, leaving the labels list always empty"

patterns-established:
  - "Stereotype detection: dual-entry sets handle FQN-resolved and simple-name-only annotations uniformly"
  - "Dynamic label reading: use Neo4jClient Cypher labels() function rather than SDN entity mapping when @DynamicLabels must appear in the response"

requirements-completed: [CKG-03]

# Metrics
duration: 13min
completed: 2026-03-04
---

# Phase 03 Plan 05: Stereotype Label Detection and Search Label Hydration Summary

**Fixed two UAT-identified bugs: stereotype labels now applied via simple-name fallback in ClassMetadataVisitor, and searchByName reads Neo4j dynamic labels directly via Cypher labels() instead of SDN entity mapping**

## Performance

- **Duration:** 13 min
- **Started:** 2026-03-04T22:45:02Z
- **Completed:** 2026-03-04T22:58:00Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- Added simple-name entries ("Service", "Repository", "Controller", "RestController", "Component") to stereotype sets so @Service/@Repository annotations are detected even when OpenRewrite type resolution fails
- Replaced searchByName SDN derived query with Neo4jClient Cypher that reads `labels(c)` directly, ensuring dynamic labels (Service, Repository) appear in search responses
- Added 4 new unit tests for simple-name fallback in ClassMetadataVisitorTest (including inline parse without classpath)
- Added 2 new integration tests asserting dynamic labels appear in GraphQueryControllerIntegrationTest

## Task Commits

Each task was committed atomically:

1. **Task 1: Add simple name fallback to stereotype detection** - `80e2ec1` (fix)
2. **Task 2: Replace searchByName SDN derived query with Neo4jClient Cypher** - `6dcaf68` (fix)

**Plan metadata:** (committed with final docs commit)

_Note: Task 1 was TDD — tests written first (RED), then implementation (GREEN in same commit)._

## Files Created/Modified
- `src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java` - Added "Service", "Controller", "RestController", "Component" to SERVICE_STEREOTYPES; "Repository" to REPOSITORY_STEREOTYPES
- `src/main/java/com/esmp/graph/application/GraphQueryService.java` - Replaced searchByName SDN call with Neo4jClient Cypher query reading labels(c)
- `src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java` - Added simpleNameFallback_serviceAnnotation_marksAsService, simpleNameFallback_repositoryAnnotation_marksAsRepository, simpleNameFallback_controllerAnnotation_marksAsService, fqnResolved_serviceAnnotation_stillMarksAsService tests
- `src/test/java/com/esmp/graph/api/GraphQueryControllerIntegrationTest.java` - Added testSearch_returnsDynamicLabels and testSearch_repositoryEntry_hasDynamicLabel tests

## Decisions Made
- Stereotype sets use dual-entry approach (both FQN and simple name) — minimal invasive change, no logic modification needed in the annotation loop
- searchByName uses the same Neo4jClient pattern already established in findClassStructure() for consistency

## Deviations from Plan

None - plan executed exactly as written. The linter additionally updated `resolveAnnotationName` to use a switch-based FQN lookup, which complements the stereotype set fix without conflicting with it.

## Issues Encountered
- Gradle binary test results file error (`NoSuchFileException`) occurred twice during long-running Testcontainers tests — resolved by re-running with `--rerun` flag. This is a known Gradle infrastructure issue with slow Testcontainers startup, not a test logic issue. Both `--rerun` runs returned BUILD SUCCESSFUL with 0 failures.

## Next Phase Readiness
- Stereotype labels (Service, Repository) now correctly applied to ClassNodes extracted from Java sources without full Spring classpath on the parser
- searchByName endpoint now returns correct dynamic labels, enabling UAT test 4 (service dependents + search label verification) to pass
- Both bugs identified in UAT gap analysis are resolved

---
*Phase: 03-code-knowledge-graph*
*Completed: 2026-03-04*
