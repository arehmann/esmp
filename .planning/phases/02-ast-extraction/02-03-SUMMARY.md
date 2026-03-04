---
phase: 02-ast-extraction
plan: 03
subsystem: extraction
tags: [spring-data-neo4j, rest-api, tdd, neo4j-persistence, vaadin-audit, idempotency, transaction-manager]

# Dependency graph
requires:
  - phase: 02-ast-extraction
    plan: 01
    provides: Neo4j @Node entities (ClassNode, MethodNode, FieldNode, CallsRelationship, ContainsComponentRelationship), ExtractionConfig, Neo4jSchemaInitializer
  - phase: 02-ast-extraction
    plan: 02
    provides: JavaSourceParser, ExtractionAccumulator, ClassMetadataVisitor, CallGraphVisitor, VaadinPatternVisitor
provides:
  - ClassNodeRepository, MethodNodeRepository, FieldNodeRepository (Spring Data Neo4j repositories)
  - AccumulatorToModelMapper: maps ExtractionAccumulator to Neo4j entity graph with full relationship wiring
  - ExtractionService: scan -> parse -> visit -> map -> saveAll() orchestration with Neo4j transaction
  - ExtractionController: POST /api/extraction/trigger REST endpoint
  - ExtractionRequest/ExtractionResponse: REST DTOs
  - VaadinAuditReport/VaadinAuditService: Vaadin pattern audit with known limitations list
  - Neo4jTransactionConfig: explicit JPA + Neo4j TM beans resolving auto-config conflict
  - ExtractionIntegrationTest: 10 Testcontainers integration tests proving full pipeline
affects:
  - Phase 3 onwards (Neo4j graph is now populated and queryable)
  - STATE.md blocker resolved: Vaadin 7 recipe coverage audit delivered via VaadinAuditReport

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Dual transaction manager pattern: explicit JPA transactionManager (@Primary) + Neo4jTransactionManager (neo4jTransactionManager) to coexist when both JPA and Neo4j are on classpath"
    - "@Transactional('neo4jTransactionManager') qualifier on ExtractionService.extract() for correct Neo4j session binding"
    - "AccumulatorToModelMapper builds full entity graph in-memory before saveAll() — single transaction for all nodes and relationships"
    - "ClassNodeRepository.saveAll() performs idempotent MERGE via business-key @Id + @Version on all @Node entities"
    - "VaadinAuditService documents known static analysis limitations (conditional trees, reflection, runtime push config)"

key-files:
  created:
    - src/main/java/com/esmp/extraction/persistence/ClassNodeRepository.java
    - src/main/java/com/esmp/extraction/persistence/MethodNodeRepository.java
    - src/main/java/com/esmp/extraction/persistence/FieldNodeRepository.java
    - src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java
    - src/main/java/com/esmp/extraction/application/ExtractionService.java
    - src/main/java/com/esmp/extraction/api/ExtractionController.java
    - src/main/java/com/esmp/extraction/api/ExtractionRequest.java
    - src/main/java/com/esmp/extraction/api/ExtractionResponse.java
    - src/main/java/com/esmp/extraction/audit/VaadinAuditReport.java
    - src/main/java/com/esmp/extraction/audit/VaadinAuditService.java
    - src/main/java/com/esmp/extraction/config/Neo4jTransactionConfig.java
    - src/test/java/com/esmp/extraction/ExtractionIntegrationTest.java
  modified: []

key-decisions:
  - "Dual transaction manager: JPA ConditionalOnMissingBean(PlatformTransactionManager) suppresses Neo4jTransactionManager auto-config when JPA is present — must create both manually with distinct bean names"
  - "@Transactional('neo4jTransactionManager') required on ExtractionService.extract() — default @Transactional binds to JPA TM (primary), leaving Neo4jTemplate.transactionTemplate null"
  - "ExtractionService uses @Transactional('neo4jTransactionManager') not @Transactional to ensure correct Neo4j session context for saveAll()"

patterns-established:
  - "Neo4j + JPA coexistence: explicit Neo4jTransactionConfig with transactionManager (JPA, @Primary) + neo4jTransactionManager (Neo4j) + neo4jTemplate (with TM injected)"
  - "AccumulatorToModelMapper assembles entity graph in-memory before calling repository.saveAll() — prevents N+1 save calls"

requirements-completed: [AST-01, AST-02, AST-03, AST-04]

# Metrics
duration: 30min
completed: 2026-03-04
---

# Phase 2 Plan 03: REST Endpoint, Neo4j Persistence, and Vaadin Audit Summary

**Extraction pipeline REST endpoint (POST /api/extraction/trigger) wired end-to-end: OpenRewrite visitors extract AST data, AccumulatorToModelMapper assembles the Neo4j entity graph, Spring Data Neo4j persists it idempotently, and VaadinAuditService generates the Vaadin pattern audit report addressing the STATE.md confidence blocker**

## Performance

- **Duration:** 30 min
- **Started:** 2026-03-04T16:15:50Z
- **Completed:** 2026-03-04T16:46:00Z
- **Tasks:** 1 auto-implemented (Task 2 is human-verify checkpoint)
- **Files created:** 12

## Accomplishments

- Three Spring Data Neo4j repositories (ClassNodeRepository, MethodNodeRepository, FieldNodeRepository) with idempotent MERGE behavior via `@Id` business keys
- `AccumulatorToModelMapper` builds the complete entity graph in-memory (ClassNode -> MethodNode list, FieldNode list, CallsRelationship list, ContainsComponentRelationship list) before persisting in one `saveAll()` call
- `ExtractionService` orchestrates the full scan -> parse -> visit -> map -> persist -> audit pipeline, using `@Transactional("neo4jTransactionManager")` for correct Neo4j session binding
- `ExtractionController` exposes `POST /api/extraction/trigger` with sourceRoot validation and synchronous execution
- `VaadinAuditService` generates a `VaadinAuditReport` documenting all detected Vaadin patterns (counts + example FQNs) and 5 known static analysis limitations — this directly addresses the STATE.md blocker about OpenRewrite Vaadin 7 recipe coverage confidence
- `Neo4jTransactionConfig` resolves the JPA/Neo4j auto-configuration conflict by creating both transaction managers explicitly
- 10 `ExtractionIntegrationTest` tests with Testcontainers Neo4j — all pass, proving end-to-end pipeline, class counts, CALLS relationships, VaadinView labels, idempotency, and audit report

## Task Commits

1. **Task 1 RED: Failing integration tests for extraction pipeline** - `3ede74e` (test)
2. **Task 1 GREEN: Implement extraction pipeline REST endpoint and Neo4j persistence** - `1a87773` (feat)

## Files Created

- `src/main/java/com/esmp/extraction/persistence/ClassNodeRepository.java` - Spring Data Neo4j repository with `countAll()` Cypher query
- `src/main/java/com/esmp/extraction/persistence/MethodNodeRepository.java` - Spring Data Neo4j repository
- `src/main/java/com/esmp/extraction/persistence/FieldNodeRepository.java` - Spring Data Neo4j repository
- `src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java` - Maps ExtractionAccumulator to @Node entities with relationship wiring; applies VaadinView/VaadinComponent/VaadinDataBinding @DynamicLabels
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` - Orchestrates full pipeline, @Transactional("neo4jTransactionManager"), per-file error handling, returns ExtractionResult record
- `src/main/java/com/esmp/extraction/api/ExtractionController.java` - @RestController POST /api/extraction/trigger with sourceRoot directory validation
- `src/main/java/com/esmp/extraction/api/ExtractionRequest.java` - Request DTO with sourceRoot, classpathFile, moduleFilter (all optional)
- `src/main/java/com/esmp/extraction/api/ExtractionResponse.java` - Response DTO with all counts, errors list, auditReport, durationMs
- `src/main/java/com/esmp/extraction/audit/VaadinAuditReport.java` - Report object with PatternEntry list, knownLimitations, summary string
- `src/main/java/com/esmp/extraction/audit/VaadinAuditService.java` - Generates audit from accumulator; 5 known limitations documented
- `src/main/java/com/esmp/extraction/config/Neo4jTransactionConfig.java` - Explicit JPA TM (@Primary) + Neo4j TM + wired Neo4jTemplate to fix auto-config conflict
- `src/test/java/com/esmp/extraction/ExtractionIntegrationTest.java` - 10 integration tests with Testcontainers (Neo4j, MySQL, Qdrant)

## Decisions Made

- **Dual transaction manager explicit configuration:** JPA's `@ConditionalOnMissingBean(PlatformTransactionManager.class)` and Neo4j's same condition cause one to suppress the other during auto-configuration. The fix is to create both explicitly in a `@Configuration` class — JPA's TM named `transactionManager` with `@Primary`, Neo4j's TM named `neo4jTransactionManager`. The auto-configs then skip TM creation (their `@ConditionalOnMissingBean` conditions are satisfied).
- **`@Transactional("neo4jTransactionManager")` qualifier on ExtractionService:** Default `@Transactional` binds to the primary TM (JPA), which leaves Neo4jTemplate's internal `transactionTemplate` null when trying to write. Qualifying with `"neo4jTransactionManager"` ensures the Neo4j session is properly opened before repository writes.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed Neo4jTemplate null transactionTemplate with dual TM configuration**
- **Found during:** Task 1 GREEN (ExtractionIntegrationTest first run)
- **Issue:** `classNodeRepository.saveAll()` threw `NullPointerException: Cannot invoke transactionTemplate.execute() because transactionTemplate is null`. Root cause: JPA's `ConditionalOnMissingBean(PlatformTransactionManager.class)` suppresses Neo4j's `transactionManager` bean, leaving `Neo4jTemplate` without a `transactionTemplate` field. Adding `@Transactional` without a qualifier also failed because it bound to the JPA TM, not Neo4j.
- **Fix:** Created `Neo4jTransactionConfig` with explicit `JpaTransactionManager` (name: `transactionManager`, `@Primary`) and `Neo4jTransactionManager` (name: `neo4jTransactionManager`) beans plus a custom `Neo4jTemplate` with the Neo4j TM injected. Changed `ExtractionService.extract()` to `@Transactional("neo4jTransactionManager")`.
- **Files modified:** `Neo4jTransactionConfig.java` (new), `ExtractionService.java`
- **Verification:** `./gradlew test --tests "com.esmp.extraction.ExtractionIntegrationTest"` — all 10 tests pass
- **Committed in:** `1a87773`

---

**Total deviations:** 1 auto-fixed (Rule 1 Bug — Neo4j/JPA transaction manager auto-config conflict)
**Impact on plan:** Required an additional config class not in the original plan. No scope creep; the fix is a standard Spring Boot JPA+Neo4j coexistence pattern.

## Issues Encountered

- JPA and Neo4j auto-configuration transaction manager conflict: with both `spring-boot-starter-data-jpa` and `spring-boot-starter-data-neo4j` on classpath, the `@ConditionalOnMissingBean(PlatformTransactionManager.class)` conditions on both auto-configs cause only ONE transaction manager to be created. The other side (Neo4j) is left without a proper TM, making `Neo4jTemplate.saveAll()` fail with NPE on its internal `transactionTemplate` field (which is only set when a TM is passed to the constructor).

## Human Verification

**Task 2 checkpoint approved by user on 2026-03-04.**

Verification confirmed:
- POST /api/extraction/trigger endpoint returns extraction summary
- Neo4j graph populated with ClassNode, MethodNode, FieldNode nodes and relationships
- VaadinView/VaadinDataBinding dynamic labels applied to Vaadin fixture classes
- CONTAINS_COMPONENT edges reflect component hierarchy via heuristic fallback
- Idempotent re-extraction confirmed (node count stable on re-run)
- VaadinAuditReport documents detected patterns and known limitations

**Known Limitation — Vaadin detection counts in degraded mode:**
`vaadinViewCount`, `vaadinComponentCount`, and `vaadinDataBindingCount` in the response show 0 when fixtures are parsed without Vaadin classpath JARs at runtime (e.g., `bootRun` without explicit classpath config). This is expected degraded-mode behavior. Unit tests in `VaadinPatternVisitorTest` prove correct detection when the full classpath (including Vaadin 7 JARs) is provided. The CONTAINS_COMPONENT edges are populated via heuristic fallback (`addComponent` call detection) and work regardless of classpath. This is NOT a bug — document as known deployment consideration for production use with real source trees.

## Next Phase Readiness

- Neo4j graph is now populated with ClassNode, MethodNode, FieldNode, CALLS, DECLARES_METHOD, DECLARES_FIELD, CONTAINS_COMPONENT relationships
- Vaadin secondary labels (VaadinView, VaadinComponent, VaadinDataBinding) are applied via @DynamicLabels
- Idempotent re-extraction confirmed by integration test
- VaadinAuditReport addresses the STATE.md blocker about OpenRewrite Vaadin 7 recipe coverage confidence
- Phase 3 (graph analysis / migration planning) can query the Neo4j graph directly

## Self-Check: PASSED
