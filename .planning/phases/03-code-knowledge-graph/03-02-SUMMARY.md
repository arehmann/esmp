---
phase: 03-code-knowledge-graph
plan: 02
subsystem: extraction-pipeline
tags: [visitors, dependency-injection, jpa, linking-service, neo4j, cypher, tdd]

# Dependency graph
requires:
  - phase: 03-code-knowledge-graph
    plan: 01
    provides: AnnotationNode, PackageNode, ModuleNode, DBTableNode @Node entities; DependsOnRelationship, ExtendsRelationship etc.; ExtractionAccumulator Phase 3 API

provides:
  - DependencyVisitor: DEPENDS_ON edge detection from @Autowired/@Inject fields and constructors
  - JpaPatternVisitor: MAPS_TO_TABLE detection from @Entity/@Table; QUERIES detection from @Query/findByX
  - ClassMetadataVisitor extended: @Service/@Repository/@Controller stereotype detection
  - AccumulatorToModelMapper extended: mapToAnnotationNodes(), mapToPackageNodes(), mapToModuleNodes(), mapToDBTableNodes(), stereotype labels in mapToClassNodes()
  - LinkingService: post-extraction EXTENDS/IMPLEMENTS/DEPENDS_ON/MAPS_TO_TABLE/QUERIES/HAS_ANNOTATION/CONTAINS_CLASS/CONTAINS_PACKAGE via idempotent Cypher MERGE
  - ExtractionService: 5-visitor pipeline with all new node type persistence and linking pass

affects:
  - 03-03-PLAN (graph query API that reads DEPENDS_ON, MAPS_TO_TABLE, QUERIES, EXTENDS, IMPLEMENTS edges)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "JavaIsoVisitor.visitVariableDeclarations() with getCursor().firstEnclosing(J.MethodDeclaration.class) guard for class-level fields only"
    - "Annotation FQN resolution with simple name fallback for unresolved JPA types (javax.persistence.Entity -> FQN mapping)"
    - "JPA snake_case default: CamelCase to snake_case via character-by-character uppercase detection"
    - "Neo4jClient Cypher MERGE with Map.bindAll() for idempotent relationship creation"
    - "Full @SpringBootTest with Testcontainers for LinkingService integration test (avoids @DataNeo4jTest slice neo4jTransactionManager bean issue)"
    - "TDD: RED (failing tests committed), GREEN (implementation committed) workflow"

key-files:
  created:
    - src/main/java/com/esmp/extraction/visitor/DependencyVisitor.java
    - src/main/java/com/esmp/extraction/visitor/JpaPatternVisitor.java
    - src/main/java/com/esmp/extraction/application/LinkingService.java
    - src/test/java/com/esmp/extraction/visitor/DependencyVisitorTest.java
    - src/test/java/com/esmp/extraction/visitor/JpaPatternVisitorTest.java
    - src/test/java/com/esmp/extraction/application/LinkingServiceIntegrationTest.java
  modified:
    - src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java
    - src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java
    - src/main/java/com/esmp/extraction/application/ExtractionService.java
    - src/main/java/com/esmp/extraction/api/ExtractionResponse.java
    - src/main/java/com/esmp/extraction/api/ExtractionController.java

key-decisions:
  - "Annotation FQN resolution fallback: JpaPatternVisitor maps simple annotation names (Entity, Table, Query) to known FQNs when OpenRewrite type resolution fails for javax.persistence types"
  - "LinkingService uses @SpringBootTest (not @DataNeo4jTest) in integration test to avoid neo4jTransactionManager qualifier bean not found in slice context"
  - "DependencyVisitor filters both JDK types AND Spring framework types (org.springframework.*) from DEPENDS_ON edges — only application-layer dependencies are captured"
  - "LinkingService.linkDependencies() deletes all existing DEPENDS_ON edges before recreating — full idempotency via delete+recreate rather than pure MERGE (avoids stale edges from renamed injected fields)"

# Metrics
duration: 19min
completed: 2026-03-04
---

# Phase 3 Plan 02: Extraction Pipeline Extension Summary

**5-visitor extraction pipeline with DependencyVisitor, JpaPatternVisitor, extended ClassMetadataVisitor, 4 new node type mappers, and LinkingService creating 8 relationship types via idempotent Cypher MERGE**

## Performance

- **Duration:** 19 min
- **Started:** 2026-03-04T18:43:14Z
- **Completed:** 2026-03-04T19:03:09Z
- **Tasks:** 2
- **Files modified:** 11 (6 created, 5 modified)

## Accomplishments

- Created `DependencyVisitor` detecting `@Autowired`/`@Inject` field injection and `@Autowired` constructor injection as DEPENDS_ON edges, filtering JDK and Spring framework types
- Created `JpaPatternVisitor` detecting `@Entity`/`@Table` for MAPS_TO_TABLE edges (with snake_case fallback when `@Table` is absent) and `@Query`/`findByX`/`deleteByX`/`countByX`/`existsByX` for QUERIES edges
- Extended `ClassMetadataVisitor` to detect `@Service`, `@Repository`, `@Controller`, `@RestController` stereotypes and populate `addAnnotation()` data
- Extended `AccumulatorToModelMapper` with 4 new node type mappers and stereotype label logic in `mapToClassNodes()`
- Created `LinkingService` with 6 linking methods (inheritance, dependencies, table mappings, query methods, annotations, package hierarchy) all using idempotent Neo4j Cypher MERGE
- Extended `ExtractionService` to run 5 visitors, persist all 5 node types, and call `LinkingService.linkAllRelationships()` after persistence
- Updated `ExtractionResult`, `ExtractionResponse`, and `ExtractionController` with new count fields (annotationCount, packageCount, moduleCount, tableCount)

## Task Commits

Each task was committed atomically:

1. **Task 1 (RED): add failing tests for DependencyVisitor and JpaPatternVisitor** - `2794f3b` (test)
2. **Task 1 (GREEN): implement DependencyVisitor, JpaPatternVisitor, extend ClassMetadataVisitor** - `1814bff` (feat)
3. **Task 2: extend mapper, create LinkingService, wire 5-visitor pipeline into ExtractionService** - `53be3c9` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/extraction/visitor/DependencyVisitor.java` - INJECTION_ANNOTATIONS set; visitVariableDeclarations() for field injection; visitMethodDeclaration() for constructor injection; JDK/Spring type filtering via EXCLUDED_PREFIXES
- `src/main/java/com/esmp/extraction/visitor/JpaPatternVisitor.java` - @Entity/@Table detection with toSnakeCase() fallback; @Query and derived query method detection (findBy*/deleteBy*/countBy*/existsBy*); annotation FQN simple name fallback
- `src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java` - Added SERVICE_STEREOTYPES and REPOSITORY_STEREOTYPES sets; extended visitClassDeclaration() to call markAsService()/markAsRepository() and addAnnotation() per annotation
- `src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java` - Added stereotype labels (Service, Repository, UIView) to mapToClassNodes(); added mapToAnnotationNodes(), mapToPackageNodes(), mapToModuleNodes(), mapToDBTableNodes()
- `src/main/java/com/esmp/extraction/application/LinkingService.java` - linkInheritanceRelationships() (EXTENDS+IMPLEMENTS), linkDependencies(), linkTableMappings(), linkQueryMethods(), linkAnnotations(), linkPackageHierarchy(); LinkingResult record
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` - Injected DependencyVisitor, JpaPatternVisitor, 4 new repositories, LinkingService; extended extract() to run 5 visitors, save all node types, call linkAllRelationships()
- `src/main/java/com/esmp/extraction/api/ExtractionResponse.java` - Added annotationCount, packageCount, moduleCount, tableCount fields with getters/setters
- `src/main/java/com/esmp/extraction/api/ExtractionController.java` - Passes new count fields to ExtractionResponse constructor
- `src/test/java/com/esmp/extraction/visitor/DependencyVisitorTest.java` - Tests for field injection, @Inject injection, constructor injection, JDK type filtering
- `src/test/java/com/esmp/extraction/visitor/JpaPatternVisitorTest.java` - Tests for @Entity+@Table, snake_case default, @Query, findByX/deleteByX/countByX/existsByX, entity-without-query validation
- `src/test/java/com/esmp/extraction/application/LinkingServiceIntegrationTest.java` - Full @SpringBootTest with Testcontainers: EXTENDS/IMPLEMENTS/DEPENDS_ON edge creation and idempotency tests

## Decisions Made

- Annotation FQN fallback: when OpenRewrite can't resolve `javax.persistence.Entity` (classpath not including JPA jar explicitly), JpaPatternVisitor maps the simple name `Entity` to `javax.persistence.Entity` for matching
- `@SpringBootTest` chosen over `@DataNeo4jTest` for `LinkingServiceIntegrationTest` to avoid the `neo4jTransactionManager` qualifier bean missing issue in the slice context (JPA transaction manager needed for dual TM config)
- DependencyVisitor excludes `org.springframework.*` types in addition to JDK types — this keeps the graph focused on application-layer dependencies
- `linkDependencies()` uses delete-then-create pattern instead of pure MERGE for idempotency, ensuring stale edges from renamed injected fields are removed on re-extraction

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] JPA annotation FQN resolution fallback for unresolved types**
- **Found during:** Task 1 GREEN phase (JpaPatternVisitorTest failing)
- **Issue:** javax.persistence.Entity / javax.persistence.Table FQNs not resolved by OpenRewrite when JPA not explicitly on compile classpath during test parsing; annotations came back as simple names only
- **Fix:** Added simple name → FQN fallback in `resolveAnnotationFqn()` mapping "Entity" → "javax.persistence.Entity" etc.
- **Files modified:** `JpaPatternVisitor.java`
- **Commit:** `1814bff` (part of GREEN phase commit)

**2. [Rule 3 - Blocking] @DataNeo4jTest slice incompatibility with neo4jTransactionManager qualifier**
- **Found during:** Task 2, LinkingServiceIntegrationTest execution
- **Issue:** `@DataNeo4jTest` slice doesn't load `Neo4jTransactionConfig` which creates the `neo4jTransactionManager` bean; `@Transactional("neo4jTransactionManager")` on `LinkingService` fails with `NoSuchBeanDefinitionException`
- **Fix:** Changed test from `@DataNeo4jTest` + `@Import` to full `@SpringBootTest(webEnvironment = NONE)` with all 3 Testcontainers (matches pattern of existing `ExtractionIntegrationTest`)
- **Files modified:** `LinkingServiceIntegrationTest.java`
- **Commit:** `53be3c9`

## Issues Encountered

None beyond the auto-fixed deviations above.

## Next Phase Readiness

- All 5 visitors run in the extraction pipeline; the graph is now populated with DEPENDS_ON, MAPS_TO_TABLE, HAS_ANNOTATION, CONTAINS_CLASS/PACKAGE, EXTENDS, IMPLEMENTS edges
- Graph query API (Plan 03) can now traverse the full relationship graph
- Re-extraction is idempotent: MERGE semantics on nodes + delete-recreate on DEPENDS_ON edges

## Self-Check

- [ ] DependencyVisitor.java exists: PASSED
- [ ] JpaPatternVisitor.java exists: PASSED
- [ ] LinkingService.java exists: PASSED
- [ ] Test commit 2794f3b exists: PASSED
- [ ] Feat commit 1814bff exists: PASSED
- [ ] Feat commit 53be3c9 exists: PASSED

## Self-Check: PASSED

---
*Phase: 03-code-knowledge-graph*
*Completed: 2026-03-04*
