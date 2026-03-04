---
phase: 03-code-knowledge-graph
plan: 03
subsystem: api
tags: [neo4j, spring-data-neo4j, rest-api, cypher, testcontainers, tdd]

# Dependency graph
requires:
  - phase: 03-code-knowledge-graph
    plan: 01
    provides: ClassNode, MethodNode, FieldNode @Node entities, DEPENDS_ON/EXTENDS/HAS_ANNOTATION relationship types

provides:
  - GraphQueryController with 4 REST endpoints for code knowledge graph queries
  - GraphQueryService with Neo4jClient Cypher queries for complex graph traversals
  - GraphQueryRepository for simple SDN derived lookups on ClassNode
  - Response DTOs: ClassStructureResponse, InheritanceChainResponse, DependencyResponse, SearchResponse
  - Integration test proving all 4 endpoints against real Neo4j via Testcontainers

affects:
  - 03-04-PLAN and beyond (consumers of the graph query API)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Neo4jClient.query().bind(param).to(name) for all parameterized Cypher — never string concat"
    - "Neo4jClient for variable-length path queries (*1..10) where SDN cannot map path objects"
    - "GraphQueryRepository extends Neo4jRepository<ClassNode, String> for simple SDN derived queries"
    - "Java records as response DTOs for flat JSON serialization"
    - "@GetMapping with :.+ regex suffix preserves dots in FQN path variables"
    - "Testcontainers Neo4j + MySQL + Qdrant for integration tests with @DynamicPropertySource"

key-files:
  created:
    - src/main/java/com/esmp/graph/api/ClassStructureResponse.java
    - src/main/java/com/esmp/graph/api/InheritanceChainResponse.java
    - src/main/java/com/esmp/graph/api/DependencyResponse.java
    - src/main/java/com/esmp/graph/api/SearchResponse.java
    - src/main/java/com/esmp/graph/api/GraphQueryController.java
    - src/main/java/com/esmp/graph/application/GraphQueryService.java
    - src/main/java/com/esmp/graph/persistence/GraphQueryRepository.java
    - src/test/java/com/esmp/graph/api/GraphQueryControllerIntegrationTest.java
  modified: []

key-decisions:
  - "FQN path variables use :.+ regex suffix (@GetMapping(\"/class/{fqn:.+}\")) to prevent Spring MVC dot-truncation"
  - "Neo4jClient used for all complex queries; GraphQueryRepository only for simple derived queries"
  - "findInheritanceChain uses a combined OPTIONAL MATCH query returning both root class and ancestors in one round-trip"
  - "findServiceDependents uses min(length(path)) AS hops to get shortest path count for each service"

patterns-established:
  - "com.esmp.graph package separates query-side concerns from com.esmp.extraction write-side"
  - "GraphQueryService orchestrates Neo4jClient (complex) and GraphQueryRepository (simple) in same service"
  - "Response records use nested records for sub-entities (MethodSummary, FieldSummary, etc.)"
  - "Integration test pre-populates graph via Neo4jClient Cypher in @BeforeEach"

requirements-completed: [CKG-03]

# Metrics
duration: 16min
completed: 2026-03-04
---

# Phase 3 Plan 03: Graph Query API Summary

**4 REST endpoints for querying the code knowledge graph using Neo4jClient for variable-length Cypher traversals and Spring Data Neo4j for simple derived lookups, with Testcontainers integration tests**

## Performance

- **Duration:** 16 min
- **Started:** 2026-03-04T18:42:57Z
- **Completed:** 2026-03-04T18:58:57Z
- **Tasks:** 2
- **Files modified:** 8

## Accomplishments

- Created `com.esmp.graph` package with clean separation from extraction write-side: `api`, `application`, `persistence` sub-packages
- Implemented 4 Java record DTOs (ClassStructureResponse, InheritanceChainResponse, DependencyResponse, SearchResponse) with nested records for sub-entities
- Implemented `GraphQueryRepository` extending `Neo4jRepository<ClassNode, String>` for simple SDN derived lookups (findByFullyQualifiedName, findBySimpleNameContainingIgnoreCase)
- Implemented `GraphQueryService` with 4 methods: `findClassStructure` (Neo4jClient multi-OPTIONAL MATCH), `findInheritanceChain` (variable-length EXTENDS *1..10), `findServiceDependents` (transitive DEPENDS_ON *1..10 with label filtering), `searchByName` (repository delegation)
- Implemented `GraphQueryController` with 4 endpoints using `:.+` path variable regex to handle FQN dots
- All 6 integration tests pass against real Neo4j, MySQL, Qdrant via Testcontainers

## Task Commits

Each task was committed atomically:

1. **Task 1: Create response DTOs, GraphQueryRepository, and GraphQueryService** - `5dc231c` (feat)
2. **Task 2 (RED): Add failing integration test** - `d7bc1df` (test)
3. **Task 2 (GREEN): Implement GraphQueryController** - `4dff0d6` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/graph/api/ClassStructureResponse.java` - Record DTO with nested MethodSummary, FieldSummary, DependencySummary
- `src/main/java/com/esmp/graph/api/InheritanceChainResponse.java` - Record DTO with AncestorEntry chain (depth-ordered)
- `src/main/java/com/esmp/graph/api/DependencyResponse.java` - Record DTO with ServiceEntry list (hops-ordered)
- `src/main/java/com/esmp/graph/api/SearchResponse.java` - Record DTO with SearchEntry results list
- `src/main/java/com/esmp/graph/api/GraphQueryController.java` - @RestController with 4 @GetMapping endpoints, :.+ regex on FQN paths
- `src/main/java/com/esmp/graph/application/GraphQueryService.java` - @Service using Neo4jClient for complex Cypher, repository for simple lookups
- `src/main/java/com/esmp/graph/persistence/GraphQueryRepository.java` - @Repository extending Neo4jRepository<ClassNode, String>
- `src/test/java/com/esmp/graph/api/GraphQueryControllerIntegrationTest.java` - 6 integration tests with Testcontainers setup and test graph pre-population

## Decisions Made

- FQN path variables use `:.+` regex suffix to prevent Spring MVC dot-truncation (e.g., `com.example.Foo` must not become `com`)
- `Neo4jClient` used for all queries in `GraphQueryService` except `searchByName` which delegates to SDN repository
- Combined OPTIONAL MATCH query for `findInheritanceChain` returns root class info and ancestors in a single round-trip
- `min(length(path)) AS hops` in `findServiceDependents` returns shortest hop count per service when multiple paths exist

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Generic type erasure in findInheritanceChain**
- **Found during:** Task 1 verification (compileJava)
- **Issue:** `fetchAs(List.class).mappedBy(...).one()` returned `Optional<List>` which could not be assigned to `Optional<List<String>>` — Java generic type erasure prevents raw `List.class` from matching a parameterized return type
- **Fix:** Rewrote the method to use a single combined Cypher query with `.fetch().all()` returning `Collection<Map<String, Object>>` and manually extracting root interfaces and ancestor chain, eliminating the problematic `fetchAs(List.class)` call entirely
- **Files modified:** `GraphQueryService.java`
- **Commit:** `5dc231c` (part of Task 1 commit after fix)

## Issues Encountered

- Gradle `java.nio.file.NoSuchFileException` on binary in-progress test results file on Windows: This is a known Gradle Windows file locking race condition that does not affect test execution results. Confirmed via XML test result files that all 6 integration tests passed (0 failures, 0 errors).
- Pre-existing `LinkingServiceIntegrationTest` failures (`No bean named 'neo4jTransactionManager'`): Out of scope for plan 03-03. Logged to deferred items.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- All 4 query endpoints are operational; downstream phases can invoke the REST API to validate graph content
- `com.esmp.graph` package is fully independent of `com.esmp.extraction` and can be extended with new endpoints
- Response DTOs are flat Java records suitable for JSON serialization without additional configuration

## Self-Check

- [ ] All 8 source files exist: PASSED
- [ ] Commits 5dc231c, d7bc1df, 4dff0d6 exist: PASSED
- [ ] All 6 integration tests pass (confirmed via XML): PASSED

## Self-Check: PASSED

---
*Phase: 03-code-knowledge-graph*
*Completed: 2026-03-04*
