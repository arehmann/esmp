---
phase: 16-openrewrite-recipe-based-migration-engine
plan: 01
subsystem: extraction
tags: [openrewrite, vaadin7, migration, neo4j, visitor, spring-data-neo4j]

# Dependency graph
requires:
  - phase: 15-docker-deployment-enterprise-scale
    provides: parallel extraction pipeline, batched UNWIND MERGE persistence, ExtractionAccumulator.merge()
  - phase: 02-ast-extraction
    provides: JavaIsoVisitor pattern, ExtractionAccumulator, visitBatch() visitor chain
provides:
  - MigrationPatternVisitor: 8th extraction visitor cataloging Vaadin 7 type usages with YES/PARTIAL/NO automation classification
  - MigrationActionData record in ExtractionAccumulator with ActionType and Automatable enums
  - MigrationActionNode Neo4j entity linked to JavaClass via HAS_MIGRATION_ACTION edges
  - ClassNode migration properties: migrationActionCount, automatableActionCount, automationScore, needsAiMigration
  - MigrationConfig: @ConfigurationProperties for custom enterprise type mapping overrides
  - Neo4j schema: migration_action_id uniqueness constraint + migration_action_class_fqn + java_class_automation_score indexes
affects:
  - phase 16-02: RecipeGeneratorService uses MigrationActionNode and ClassNode migration properties to generate OpenRewrite recipes
  - phase 16-03: MigrationController exposes migration analysis data via REST API

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "import-based migration detection: visitCompilationUnit() reads imports before class declarations to resolve class FQN first"
    - "TYPE_MAP/PARTIAL_MAP/COMPLEX_TYPES static classification maps in visitor for O(1) import categorization"
    - "automationScore formula: (yesCount + 0.5 * partialCount) / totalCount"
    - "actionId composition: classFqn + '#' + actionType.name() + '#' + source for stable deduplication"

key-files:
  created:
    - src/main/java/com/esmp/extraction/visitor/MigrationPatternVisitor.java
    - src/main/java/com/esmp/extraction/model/MigrationActionNode.java
    - src/main/java/com/esmp/extraction/persistence/MigrationActionNodeRepository.java
    - src/main/java/com/esmp/extraction/config/MigrationConfig.java
    - src/test/java/com/esmp/extraction/visitor/MigrationPatternVisitorTest.java
    - src/test/java/com/esmp/extraction/application/MigrationExtractionIntegrationTest.java
    - src/test/resources/fixtures/migration/SimpleVaadinView.java
    - src/test/resources/fixtures/migration/ComplexTableView.java
    - src/test/resources/fixtures/migration/PureServiceClass.java
  modified:
    - src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java
    - src/main/java/com/esmp/extraction/model/ClassNode.java
    - src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java
    - src/main/java/com/esmp/extraction/application/ExtractionService.java
    - src/main/java/com/esmp/extraction/application/LinkingService.java
    - src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java
    - src/main/java/com/esmp/indexing/application/IncrementalIndexingService.java
    - src/main/resources/application.yml

key-decisions:
  - "import-based detection via visitCompilationUnit() instead of visitImport(): imports appear before class declarations in the LST; CompilationUnit-level processing resolves class FQN from type declarations before iterating imports"
  - "MigrationActionData as record in ExtractionAccumulator: keeps all accumulator inner types co-located, avoids circular imports between visitor and model packages"
  - "actionId = classFqn + '#' + actionType.name() + '#' + source: composite business key enables stable deduplication without UUID generation; same class/type/source triple always produces the same ID"
  - "putAll in merge() for migrationActions: safe because each class FQN appears in exactly one partition in parallel extraction"
  - "Automatable.PARTIAL counts as 0.5 in automationScore: models that Panel-type actions are partially scriptable but need human review"

patterns-established:
  - "Visitor classification maps: static Map<String, String> TYPE_MAP + PARTIAL_MAP + COMPLEX_TYPES for import-based pattern matching — avoids string switch/case chains"
  - "Batched HAS_MIGRATION_ACTION linking: same UNWIND batch pattern as other edge types in LinkingService"
  - "Integration test static flag extractionDone: prevents duplicate extraction across 6 test methods sharing one container context"

requirements-completed:
  - MIG-01
  - MIG-02

# Metrics
duration: 24min
completed: 2026-03-28
---

# Phase 16 Plan 01: Migration Pattern Detection Summary

**MigrationPatternVisitor (8th extraction visitor) catalogs Vaadin 7 type usages with YES/PARTIAL/NO automation classification, persisting MigrationActionNode entities with HAS_MIGRATION_ACTION edges and ClassNode migration scores**

## Performance

- **Duration:** 24 min
- **Started:** 2026-03-28T13:19:31Z
- **Completed:** 2026-03-28T13:44:28Z
- **Tasks:** 2 (TDD Task 1 + standard Task 2)
- **Files modified:** 16 files (9 created, 7 modified)

## Accomplishments

- MigrationPatternVisitor with 18-entry TYPE_MAP, PARTIAL_MAP (Panel), COMPLEX_TYPES set (11 types), and JAVAX_PACKAGE_MAP — import-based detection via visitCompilationUnit()
- MigrationActionNode Neo4j entity + MigrationActionNodeRepository persisted via batched UNWIND MERGE before LinkingService runs
- ClassNode extended with 4 migration properties (migrationActionCount, automatableActionCount, automationScore, needsAiMigration) computed in AccumulatorToModelMapper
- LinkingService extended with linkMigrationActions() creating HAS_MIGRATION_ACTION edges; LinkingResult record updated
- Neo4j schema: uniqueness constraint on actionId + 2 indexes; 8 unit tests + 6 integration tests all passing

## Task Commits

1. **Task 1: MigrationPatternVisitor, accumulator extensions, and test fixtures** - `e6db5ee` (feat)
2. **Task 2: MigrationActionNode model, ClassNode properties, mapper, linking, persistence, schema** - `e66f729` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/extraction/visitor/MigrationPatternVisitor.java` - 8th extraction visitor with import-based Vaadin 7 pattern detection
- `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` - MigrationActionData record (ActionType + Automatable enums), addMigrationAction(), getMigrationActions(), merge() extension
- `src/main/java/com/esmp/extraction/model/MigrationActionNode.java` - @Node("MigrationAction") entity with actionId composite key
- `src/main/java/com/esmp/extraction/model/ClassNode.java` - 4 migration analysis properties
- `src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java` - migration property mapping + mapToMigrationActionNodes()
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` - 8th visitor + persistMigrationActionNodesBatched()
- `src/main/java/com/esmp/extraction/application/LinkingService.java` - linkMigrationActions() + updated LinkingResult
- `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` - 3 new schema statements
- `src/main/java/com/esmp/extraction/config/MigrationConfig.java` - @ConfigurationProperties for custom mappings
- `src/main/resources/application.yml` - esmp.migration.custom-mappings section

## Decisions Made

- **visitCompilationUnit() not visitImport()**: OpenRewrite LST visits imports before class declarations, so import-based detection needs class FQN resolved first from type declarations. CompilationUnit-level processing handles this ordering correctly.
- **MigrationActionData record in ExtractionAccumulator**: Keeps all accumulator inner types co-located, consistent with existing MethodComplexityData, ClassWriteData pattern.
- **actionId = classFqn + '#' + actionType.name() + '#' + source**: Deterministic business key for deduplication without UUID generation — consistent with existing BusinessTermNode.termId pattern.
- **Automatable.PARTIAL = 0.5 weight**: Models that Panel-type renames are partially automatable (recipe handles the type change, human handles styling).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Vaadin SpringBootAutoConfiguration requires WebApplicationContext**
- **Found during:** Task 2 (MigrationExtractionIntegrationTest)
- **Issue:** Test used WebEnvironment.NONE, Vaadin's SpringBootAutoConfiguration requires a WebApplicationContext bean — context load failed
- **Fix:** Changed to WebEnvironment.MOCK (same pattern as Phase 8 integration tests per STATE.md decision)
- **Files modified:** MigrationExtractionIntegrationTest.java
- **Verification:** 6/6 integration tests pass
- **Committed in:** e66f729 (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Standard Vaadin+SpringBootTest pattern fix. No scope creep.

## Issues Encountered

- Gradle cache lock errors from prior test runs (Java transforms locked by previous daemon) — resolved by stopping the daemon with `./gradlew --stop` before re-running tests. No code changes needed.

## Next Phase Readiness

- MigrationPatternVisitor provides the detection foundation: every class with Vaadin 7 types now has MigrationAction nodes and ClassNode migration scores in Neo4j
- Phase 16-02 (RecipeGeneratorService) can query `MATCH (c:JavaClass)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction) WHERE ma.automatable = 'YES'` to identify candidates for OpenRewrite recipe generation
- ClassNode.automationScore enables prioritization: high-score classes are fully scriptable; low-score (needsAiMigration=true) classes need AI context assembly via MCP

## Self-Check: PASSED

All files verified present. All commits verified in git log.

---
*Phase: 16-openrewrite-recipe-based-migration-engine*
*Completed: 2026-03-28*
