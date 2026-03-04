---
phase: 03-code-knowledge-graph
plan: 01
subsystem: database
tags: [neo4j, spring-data-neo4j, domain-model, graph-schema, relationship-properties]

# Dependency graph
requires:
  - phase: 02-ast-extraction
    provides: ClassNode, MethodNode, FieldNode @Node entities and ClassNodeRepository pattern

provides:
  - AnnotationNode, PackageNode, ModuleNode, DBTableNode @Node entities with business-key @Id + @Version
  - DependsOnRelationship, ExtendsRelationship, ImplementsRelationship, BindsToRelationship, QueriesRelationship, MapsToTableRelationship @RelationshipProperties classes
  - AnnotationNodeRepository, PackageNodeRepository, ModuleNodeRepository, DBTableNodeRepository repositories
  - Neo4jSchemaInitializer extended with 4 new uniqueness constraints (7 total)
  - ExtractionAccumulator extended with Phase 3 data holders, records, and mutation methods

affects:
  - 03-02-PLAN (visitor implementations that populate these data structures)
  - 03-03-PLAN (query API that reads from new node/relationship types)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@Node with business-key @Id (String) + @Version for idempotent MERGE semantics"
    - "@RelationshipProperties with @Id @GeneratedValue (Long) + @TargetNode for relationship metadata"
    - "Neo4jRepository<Entity, String> for business-key repositories"
    - "ExtractionAccumulator extension: additive-only changes to preserve backward compatibility"
    - "putIfAbsent deduplication for annotation data collection (prevents FQN overwrites)"

key-files:
  created:
    - src/main/java/com/esmp/extraction/model/AnnotationNode.java
    - src/main/java/com/esmp/extraction/model/PackageNode.java
    - src/main/java/com/esmp/extraction/model/ModuleNode.java
    - src/main/java/com/esmp/extraction/model/DBTableNode.java
    - src/main/java/com/esmp/extraction/model/DependsOnRelationship.java
    - src/main/java/com/esmp/extraction/model/ExtendsRelationship.java
    - src/main/java/com/esmp/extraction/model/ImplementsRelationship.java
    - src/main/java/com/esmp/extraction/model/BindsToRelationship.java
    - src/main/java/com/esmp/extraction/model/QueriesRelationship.java
    - src/main/java/com/esmp/extraction/model/MapsToTableRelationship.java
    - src/main/java/com/esmp/extraction/persistence/AnnotationNodeRepository.java
    - src/main/java/com/esmp/extraction/persistence/PackageNodeRepository.java
    - src/main/java/com/esmp/extraction/persistence/ModuleNodeRepository.java
    - src/main/java/com/esmp/extraction/persistence/DBTableNodeRepository.java
  modified:
    - src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java
    - src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java

key-decisions:
  - "QueriesRelationship targets DBTableNode (not MethodNode) — the edge goes from MethodNode to DBTableNode"
  - "addAnnotation uses putIfAbsent to deduplicate annotation data by FQN (prevents Pitfall 5 overwrites)"
  - "tableName stored lowercased in DBTableNode to enable case-insensitive deduplication across RDBMS dialects"

patterns-established:
  - "New @Node entities: @Node(label) class with String @Id (business key), @Version Long, no @GeneratedValue"
  - "New @RelationshipProperties: @RelationshipProperties class with @Id @GeneratedValue Long id, @TargetNode, metadata fields"
  - "New repositories: @Repository interface extends Neo4jRepository<Entity, String> with @Query(count) helper"
  - "Schema constraints: CREATE CONSTRAINT name IF NOT EXISTS FOR (n:Label) REQUIRE n.property IS UNIQUE"

requirements-completed: [CKG-01]

# Metrics
duration: 3min
completed: 2026-03-04
---

# Phase 3 Plan 01: Data Model Foundation Summary

**4 @Node entities, 6 @RelationshipProperties classes, 4 repositories, 7 total Neo4j schema constraints, and extended ExtractionAccumulator for the code knowledge graph domain model**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-04T18:37:37Z
- **Completed:** 2026-03-04T18:40:43Z
- **Tasks:** 2
- **Files modified:** 16

## Accomplishments

- Established 4 new @Node entity classes (AnnotationNode, PackageNode, ModuleNode, DBTableNode) following the established business-key pattern with @Id String + @Version Long
- Created 6 @RelationshipProperties classes (DependsOn, Extends, Implements, BindsTo, Queries, MapsToTable) following the CallsRelationship pattern with @Id @GeneratedValue Long + @TargetNode
- Extended Neo4jSchemaInitializer to create 7 total uniqueness constraints (3 existing + 4 new: JavaAnnotation, JavaPackage, JavaModule, DBTable)
- Extended ExtractionAccumulator with 8 new mutation methods, 9 new read accessors, and 4 new inner record types — all additive changes preserving existing API

## Task Commits

Each task was committed atomically:

1. **Task 1: Create new @Node entities, @RelationshipProperties classes, and repositories** - `b1081db` (feat)
2. **Task 2: Extend ExtractionAccumulator with new data holders, records, and mutation methods** - `7278908` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/extraction/model/AnnotationNode.java` - @Node("JavaAnnotation") with fullyQualifiedName business key and retention field
- `src/main/java/com/esmp/extraction/model/PackageNode.java` - @Node("JavaPackage") with packageName business key and CONTAINS_CLASS relationship
- `src/main/java/com/esmp/extraction/model/ModuleNode.java` - @Node("JavaModule") with moduleName business key and CONTAINS_PACKAGE relationship
- `src/main/java/com/esmp/extraction/model/DBTableNode.java` - @Node("DBTable") with lowercased tableName business key
- `src/main/java/com/esmp/extraction/model/DependsOnRelationship.java` - @RelationshipProperties for DEPENDS_ON with injectionType and fieldName
- `src/main/java/com/esmp/extraction/model/ExtendsRelationship.java` - @RelationshipProperties for EXTENDS with resolutionConfidence
- `src/main/java/com/esmp/extraction/model/ImplementsRelationship.java` - @RelationshipProperties for IMPLEMENTS with resolutionConfidence
- `src/main/java/com/esmp/extraction/model/BindsToRelationship.java` - @RelationshipProperties for BINDS_TO with bindingMechanism
- `src/main/java/com/esmp/extraction/model/QueriesRelationship.java` - @RelationshipProperties for QUERIES targeting DBTableNode with queryType
- `src/main/java/com/esmp/extraction/model/MapsToTableRelationship.java` - @RelationshipProperties for MAPS_TO_TABLE targeting DBTableNode
- `src/main/java/com/esmp/extraction/persistence/AnnotationNodeRepository.java` - Neo4jRepository<AnnotationNode, String>
- `src/main/java/com/esmp/extraction/persistence/PackageNodeRepository.java` - Neo4jRepository<PackageNode, String>
- `src/main/java/com/esmp/extraction/persistence/ModuleNodeRepository.java` - Neo4jRepository<ModuleNode, String>
- `src/main/java/com/esmp/extraction/persistence/DBTableNodeRepository.java` - Neo4jRepository<DBTableNode, String>
- `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` - Extended with 4 new IF NOT EXISTS constraints
- `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` - Extended with Phase 3 data holders, records, and mutation methods

## Decisions Made

- QueriesRelationship targets DBTableNode (not MethodNode) — edge represents MethodNode-queries-DBTableNode
- Used putIfAbsent for addAnnotation deduplication (per Pitfall 5 in plan context) to prevent FQN key overwrites
- DBTableNode tableName stored lowercased per plan specification for case-insensitive RDBMS portability

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- All domain model types are established; Plan 02 visitors can now populate data into ExtractionAccumulator and persist via repositories
- All relationship property classes are available for Plan 02 to wire up EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE edges
- Neo4j constraints protect against duplicate nodes on re-extraction

## Self-Check

- [ ] All 14 new Java source files exist and compile: PASSED (./gradlew compileJava BUILD SUCCESSFUL)
- [ ] Task 1 commit b1081db exists: PASSED
- [ ] Task 2 commit 7278908 exists: PASSED

## Self-Check: PASSED

---
*Phase: 03-code-knowledge-graph*
*Completed: 2026-03-04*
