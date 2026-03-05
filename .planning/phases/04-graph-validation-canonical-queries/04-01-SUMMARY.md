---
phase: 04-graph-validation-canonical-queries
plan: 01
subsystem: api
tags: [neo4j, cypher, validation, spring-boot, testcontainers]

# Dependency graph
requires:
  - phase: 03-code-knowledge-graph
    provides: Neo4jClient pattern, graph schema (7 node types, 9 relationship types), GraphQueryService established pattern
provides:
  - GET /api/graph/validation endpoint returning ValidationReport with 20 query results
  - ValidationQueryRegistry @Component with 20 canonical Cypher queries (10 structural + 10 architectural)
  - ValidationService execution engine via Neo4jClient.query().fetch().all()
  - ValidationReport and ValidationQueryResult response records
  - Extensible registry pattern (future phases add their own ValidationQueryRegistry beans)
affects:
  - 04-02 (dependency cone plan builds on same Neo4jClient pattern)
  - future phases adding validation queries

# Tech tracking
tech-stack:
  added: []
  patterns:
    - ValidationQueryRegistry @Component pattern for extensible query registration
    - List<ValidationQueryRegistry> injection in ValidationService for multi-registry aggregation
    - CALLS_EDGE_COVERAGE inverted status logic (count > 0 = PASS)
    - Map<String, Object> extraction from neo4jClient.query().fetch().all() for mixed count+list rows

key-files:
  created:
    - src/main/java/com/esmp/graph/validation/ValidationSeverity.java
    - src/main/java/com/esmp/graph/validation/ValidationStatus.java
    - src/main/java/com/esmp/graph/validation/ValidationQuery.java
    - src/main/java/com/esmp/graph/validation/ValidationQueryRegistry.java
    - src/main/java/com/esmp/graph/validation/ValidationService.java
    - src/main/java/com/esmp/graph/api/ValidationQueryResult.java
    - src/main/java/com/esmp/graph/api/ValidationReport.java
    - src/main/java/com/esmp/graph/api/ValidationController.java
    - src/test/java/com/esmp/graph/api/ValidationControllerIntegrationTest.java
  modified: []

key-decisions:
  - "ValidationService accepts List<ValidationQueryRegistry> (not single instance) for extensibility — future phases add their own registry beans without modifying core service"
  - "CALLS_EDGE_COVERAGE uses inverted status logic: count > 0 = PASS (coverage query, not violation query)"
  - "Well-formed test graph requires all JavaClass nodes to have HAS_ANNOTATION edges for ANNOTATION_COVERAGE query to pass"
  - "Validation queries use Map<String,Object> extraction from fetch().all() — simpler than fetchAs() for mixed count+list return shapes"

patterns-established:
  - "ValidationQueryRegistry: @Component with List.of() in constructor, getQueries() returns unmodifiable list"
  - "ValidationService: iterates List<ValidationQueryRegistry>, executes each via Neo4jClient, determines status by severity + count"
  - "Integration test well-formed graph: all classes must have HAS_ANNOTATION edges to satisfy ANNOTATION_COVERAGE"

requirements-completed: [GVAL-01, GVAL-03, GVAL-04]

# Metrics
duration: 6min
completed: 2026-03-05
---

# Phase 04 Plan 01: Validation Framework Summary

**20 canonical Cypher validation queries (10 structural + 10 architectural) with extensible registry, Neo4jClient execution engine, and GET /api/graph/validation REST endpoint**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-05T00:00:33Z
- **Completed:** 2026-03-05T00:06:30Z
- **Tasks:** 2 (Task 1: framework + source files; Task 2: integration tests TDD)
- **Files modified:** 9

## Accomplishments

- Created validation sub-package `com.esmp.graph.validation` with enums, record, registry, and service
- `ValidationQueryRegistry` holds all 20 canonical Cypher queries covering orphans, dangles, duplicates, edge integrity, and architectural patterns (Service/Repository/View/Entity invariants)
- `GET /api/graph/validation` endpoint returns `ValidationReport` with per-query name/description/severity/status/count/details and aggregate errorCount/warnCount/passCount
- 5 integration tests pass: well-formed graph produces 20 PASS, broken graphs correctly produce FAIL/WARN for targeted queries

## Task Commits

Each task was committed atomically:

1. **Task 1: Create validation framework types, registry with 20 Cypher queries, and execution service** - `14290c9` (feat)
2. **Task 2: Integration tests RED phase** - `db21dab` (test)
3. **Task 2: Integration tests GREEN phase** - `816704b` (feat)

_Note: TDD tasks have multiple commits (test RED → feat GREEN)_

## Files Created/Modified

- `src/main/java/com/esmp/graph/validation/ValidationSeverity.java` - Enum: ERROR, WARNING
- `src/main/java/com/esmp/graph/validation/ValidationStatus.java` - Enum: PASS, FAIL, WARN
- `src/main/java/com/esmp/graph/validation/ValidationQuery.java` - Record: name, description, cypher, severity
- `src/main/java/com/esmp/graph/validation/ValidationQueryRegistry.java` - @Component with all 20 canonical queries
- `src/main/java/com/esmp/graph/validation/ValidationService.java` - @Service executing queries via Neo4jClient, aggregating from List<ValidationQueryRegistry>
- `src/main/java/com/esmp/graph/api/ValidationQueryResult.java` - Record: name, description, severity, status, count, details
- `src/main/java/com/esmp/graph/api/ValidationReport.java` - Record: generatedAt, results, errorCount, warnCount, passCount
- `src/main/java/com/esmp/graph/api/ValidationController.java` - @RestController GET /api/graph/validation
- `src/test/java/com/esmp/graph/api/ValidationControllerIntegrationTest.java` - 5 integration tests with Testcontainers

## Decisions Made

- `ValidationService` accepts `List<ValidationQueryRegistry>` rather than a single registry instance. Future phases (e.g., Phase 5 domain lexicon) add their own `@Component` registry beans and they are automatically aggregated.
- `CALLS_EDGE_COVERAGE` uses inverted pass/fail logic: count > 0 = PASS (sanity coverage check, not a violation query). Special-cased in `ValidationService.determineStatus()`.
- Well-formed integration test graph requires `HAS_ANNOTATION` edges on every class to satisfy the `ANNOTATION_COVERAGE` query (count == 0 expected for well-formed graphs).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Well-formed test graph missing HAS_ANNOTATION edges for BaseService and SampleRepository**
- **Found during:** Task 2 (integration tests GREEN phase)
- **Issue:** `ANNOTATION_COVERAGE` query flagged BaseService and SampleRepository as unannotated (count=2, WARN status), causing `allQueriesPassOnWellFormedGraph` test to fail
- **Fix:** Added `HAS_ANNOTATION` edges from BaseService to `@Component` annotation node and from SampleRepository to `@Repository` annotation node in `@BeforeEach` setup
- **Files modified:** `src/test/java/com/esmp/graph/api/ValidationControllerIntegrationTest.java`
- **Verification:** All 5 integration tests pass after fix
- **Committed in:** `816704b` (Task 2 GREEN commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - incomplete test graph data)
**Impact on plan:** Fix necessary for test correctness — ANNOTATION_COVERAGE query is intentionally an informational WARNING, but well-formed graphs should have all classes annotated. No scope creep.

## Issues Encountered

None beyond the auto-fixed test graph issue above.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 04-02 (dependency cone) is already complete (committed before this plan's finalization)
- Phase 04 validation framework is complete: 20 queries registered, endpoint live, tests passing
- The extensible registry pattern is ready for Phase 5 to add domain-lexicon validation queries

---
*Phase: 04-graph-validation-canonical-queries*
*Completed: 2026-03-05*
