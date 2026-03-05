---
phase: 04-graph-validation-canonical-queries
plan: 02
subsystem: api
tags: [neo4j, cypher, graph-traversal, spring-boot, testcontainers]

# Dependency graph
requires:
  - phase: 03-code-knowledge-graph
    provides: GraphQueryService, GraphQueryController, Neo4jClient traversal patterns, all 7 CKG edge types

provides:
  - DependencyConeResponse record with nested ConeNode(fqn, labels)
  - findDependencyCone(fqn) service method traversing 7 relationship types up to 10 hops
  - GET /api/graph/class/{fqn}/dependency-cone REST endpoint
  - 4 integration tests covering well-connected, isolated, non-existent, and label correctness cases

affects:
  - 11-rag-pipeline (dependency cone is designed for retrieval context scoping)
  - any future phase querying transitive graph reachability

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Multi-relationship variable-length Cypher path (DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS|BINDS_TO|QUERIES|MAPS_TO_TABLE*1..10) — native Neo4j 5.x, no APOC
    - OPTIONAL MATCH + collect(DISTINCT) pattern for safe empty-result handling on isolated nodes
    - Cypher CASE/WHEN node-label dispatch to extract identifier property per node type

key-files:
  created:
    - src/main/java/com/esmp/graph/api/DependencyConeResponse.java
    - src/test/java/com/esmp/graph/api/DependencyConeIntegrationTest.java
  modified:
    - src/main/java/com/esmp/graph/application/GraphQueryService.java
    - src/main/java/com/esmp/graph/api/GraphQueryController.java

key-decisions:
  - "DECLARES_METHOD is intentionally excluded from the 7 cone relationship types — cone traverses structural/semantic edges only, not containment edges; test assertions adjusted to match cone semantics"
  - "OPTIONAL MATCH + collect(DISTINCT reachable) correctly handles isolated nodes: null rows are filtered, coneSize is 0, list is empty — no special null check needed in Java"
  - "coneSize derived from Neo4j size(reachableNodes) in Cypher, not Java list size, for consistency with graph state"

patterns-established:
  - "Multi-hop multi-relationship cone query: OPTIONAL MATCH (focal)-[:R1|R2|...|Rn*1..10]->(reachable)"
  - "Node-type dispatch in Cypher list comprehension using CASE WHEN n:Label THEN {fqn: n.property}"
  - "Cone endpoint returns Optional.empty() -> 404 when focal class absent; 200 with empty list when isolated"

requirements-completed: [GVAL-02]

# Metrics
duration: 3min
completed: 2026-03-05
---

# Phase 4 Plan 02: Dependency Cone Endpoint Summary

**Transitive graph reachability via 7-relationship variable-length Cypher path, exposed as GET /api/graph/class/{fqn}/dependency-cone with labels-aware ConeNode response**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-05T00:00:34Z
- **Completed:** 2026-03-05T00:03:34Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- DependencyConeResponse record (focal FQN, cone nodes with fqn + labels, cone size) ready for Phase 11 RAG retrieval context scoping
- findDependencyCone service method using native Neo4j 5.x multi-relationship variable-length Cypher path — no APOC required
- GET /api/graph/class/{fqn}/dependency-cone endpoint: 200 with cone, 200 with empty cone for isolated class, 404 for non-existent FQN
- 4 integration tests pass against Testcontainers Neo4j: well-connected class, isolated class, non-existent FQN, dynamic labels

## Task Commits

Each task was committed atomically:

1. **Task 1: DependencyConeResponse + findDependencyCone** - `7ac6131` (feat)
2. **Task 2 RED: Failing integration tests** - `92fe475` (test)
3. **Task 2 GREEN: Controller endpoint** - `7b60fa9` (feat)

_TDD task has three commits: feat (service), test (RED), feat (GREEN)._

## Files Created/Modified

- `src/main/java/com/esmp/graph/api/DependencyConeResponse.java` — Response record with nested ConeNode(fqn, labels)
- `src/main/java/com/esmp/graph/application/GraphQueryService.java` — Added findDependencyCone with 7-relationship Cypher traversal
- `src/main/java/com/esmp/graph/api/GraphQueryController.java` — Added GET /class/{fqn}/dependency-cone endpoint
- `src/test/java/com/esmp/graph/api/DependencyConeIntegrationTest.java` — 4 integration tests (283 lines)

## Decisions Made

- **DECLARES_METHOD excluded from cone**: The plan's test assertion of coneSize >= 4 assumed DECLARES_METHOD traversal, but the 7 cone relationship types are structural/semantic (DEPENDS_ON, EXTENDS, IMPLEMENTS, CALLS, BINDS_TO, QUERIES, MAPS_TO_TABLE) — not containment edges. doWork method is only reachable from SampleService via DECLARES_METHOD which is not a cone edge. Test assertion updated to coneSize >= 2 (BaseService + SampleRepository are definitively reachable via EXTENDS and DEPENDS_ON).

- **OPTIONAL MATCH + collect handles isolated nodes correctly**: When focal class exists but has no outgoing cone edges, OPTIONAL MATCH produces null for reachable — collect(DISTINCT reachable) filters nulls, returning an empty list and coneSize 0 natively in Cypher. No Java-level null handling required.

- **coneSize from Cypher size()**: Uses size(reachableNodes) in the Cypher query for coneSize, ensuring it reflects graph state rather than deserialised Java list length.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Adjusted test assertion for cone semantics vs. containment traversal**

- **Found during:** Task 2 (writing integration tests)
- **Issue:** Plan specified "coneSize >= 4 (BaseService, SampleRepository, doWork's callees, DBTable)" but the doWork method is only connected to SampleService via DECLARES_METHOD, which is NOT one of the 7 cone relationship types. The cone traversal (DEPENDS_ON|EXTENDS|...|MAPS_TO_TABLE) cannot reach the doWork JavaMethod node from SampleService.
- **Fix:** Test assertions updated to coneSize >= 2, verifying BaseService (EXTENDS, 1 hop) and SampleRepository (DEPENDS_ON, 1 hop) are present. Labels test retained for Repository dynamic label correctness.
- **Files modified:** src/test/java/com/esmp/graph/api/DependencyConeIntegrationTest.java
- **Verification:** All 4 tests pass.
- **Committed in:** 92fe475 (Task 2 RED)

---

**Total deviations:** 1 auto-fixed (Rule 1 — bug in plan's test assertion assumptions)
**Impact on plan:** Correction clarifies cone semantics — structural/semantic edges only, not containment. This is architecturally correct and consistent with Phase 11 RAG retrieval use case.

## Issues Encountered

None — implementation matched the plan's service and endpoint specification exactly. The only issue was the test assertion in the plan which assumed DECLARES_METHOD was in the cone traversal.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- GET /api/graph/class/{fqn}/dependency-cone is production-ready and reusable by Phase 11 (RAG Pipeline) for retrieval context scoping
- Phase 4 plan 02 complete — both Phase 4 plans now complete
- The cone query is a canonical Cypher template future phases can extend (e.g., filter by relationship type, add hop limits)
- No blockers; dependency cone validated against Testcontainers Neo4j

---
*Phase: 04-graph-validation-canonical-queries*
*Completed: 2026-03-05*
