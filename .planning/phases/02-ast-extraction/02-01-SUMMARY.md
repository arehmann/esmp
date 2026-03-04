---
phase: 02-ast-extraction
plan: 01
subsystem: extraction
tags: [openrewrite, neo4j, spring-data-neo4j, vaadin7, ast, domain-model]

# Dependency graph
requires:
  - phase: 01-infrastructure
    provides: Spring Boot project with Neo4j, Gradle Kotlin DSL version catalog
provides:
  - OpenRewrite rewrite-java 8.74.3 + rewrite-java-21 on implementation classpath
  - Neo4j @Node entities for ClassNode, MethodNode, FieldNode with business-key @Id and @Version
  - @RelationshipProperties for CALLS (CallsRelationship) and CONTAINS_COMPONENT (ContainsComponentRelationship) edges
  - Neo4jSchemaInitializer creating 3 uniqueness constraints at startup (JavaClass, JavaMethod, JavaField)
  - ExtractionConfig @ConfigurationProperties binding esmp.extraction.source-root and classpath-file
  - 6 synthetic Vaadin 7 test fixture Java files in src/test/resources/fixtures/
affects:
  - 02-ast-extraction (plans 02, 03 depend on these entities and fixtures)
  - all future extraction tests

# Tech tracking
tech-stack:
  added:
    - org.openrewrite:rewrite-java:8.74.3 (implementation)
    - org.openrewrite:rewrite-java-21:8.74.3 (implementation, alias openrewrite-java-jdk21)
    - com.vaadin:vaadin-server:7.7.48 (testImplementation)
  patterns:
    - Business-key @Id + @Version on all @Node entities for idempotent SDN MERGE semantics
    - @DynamicLabels Set<String> extraLabels on ClassNode for runtime Vaadin secondary labels
    - ApplicationRunner component for startup Cypher constraint creation via Neo4jClient
    - @ConfigurationProperties for extraction subsystem config isolation

key-files:
  created:
    - src/main/java/com/esmp/extraction/model/ClassNode.java
    - src/main/java/com/esmp/extraction/model/MethodNode.java
    - src/main/java/com/esmp/extraction/model/FieldNode.java
    - src/main/java/com/esmp/extraction/model/CallsRelationship.java
    - src/main/java/com/esmp/extraction/model/ContainsComponentRelationship.java
    - src/main/java/com/esmp/extraction/config/ExtractionConfig.java
    - src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java
    - src/test/resources/fixtures/SampleEntity.java
    - src/test/resources/fixtures/SampleRepository.java
    - src/test/resources/fixtures/SampleService.java
    - src/test/resources/fixtures/SampleVaadinView.java
    - src/test/resources/fixtures/SampleVaadinForm.java
    - src/test/resources/fixtures/SampleUI.java
  modified:
    - gradle/libs.versions.toml
    - build.gradle.kts
    - src/main/resources/application.yml

key-decisions:
  - "Version catalog alias openrewrite-java-jdk21 used (not openrewrite-java-21) because Gradle cannot generate numeric accessors from hyphenated aliases ending in digits"
  - "@Version uses org.springframework.data.annotation.Version (spring-data-commons) not org.springframework.data.neo4j.core.schema — SDN does not have its own @Version annotation"
  - "vaadin-server:7.7.48 added as testImplementation only — provides Vaadin 7 class symbols for classpath type resolution in tests, must not be on runtime classpath"

patterns-established:
  - "Extraction model: package com.esmp.extraction.model for @Node and @RelationshipProperties entities"
  - "Extraction config: package com.esmp.extraction.config for @Configuration and @Component startup classes"
  - "Test fixtures: src/test/resources/fixtures/ for raw .java source files parsed by OpenRewrite in tests"
  - "Idempotency: business-key @Id + @Version on every @Node entity, plus uniqueness constraint in Neo4jSchemaInitializer"

requirements-completed: [AST-01, AST-04]

# Metrics
duration: 4min
completed: 2026-03-04
---

# Phase 2 Plan 01: Dependencies, Domain Model, and Test Fixtures Summary

**OpenRewrite 8.74.3 embedded as library, 5 Neo4j @Node/@RelationshipProperties entities with idempotent MERGE support, and 6 synthetic Vaadin 7 test fixtures covering UI, View, service, repository, entity, and data-bound form patterns**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-04T15:49:20Z
- **Completed:** 2026-03-04T15:54:07Z
- **Tasks:** 2
- **Files modified:** 13 created, 3 modified

## Accomplishments

- OpenRewrite rewrite-java + rewrite-java-21 on implementation classpath with no Spring Boot BOM conflicts
- Five Neo4j domain model entities (ClassNode, MethodNode, FieldNode, CallsRelationship, ContainsComponentRelationship) with business-key @Id + @Version for idempotent persistence and @DynamicLabels for Vaadin secondary labels
- Neo4jSchemaInitializer creates three uniqueness constraints (JavaClass.fullyQualifiedName, JavaMethod.methodId, JavaField.fieldId) at startup using IF NOT EXISTS Cypher
- Six synthetic Vaadin 7 fixture files representing the full module pattern: entity, repository, service, View, data-bound form, and UI entry point

## Task Commits

Each task was committed atomically:

1. **Task 1: Add OpenRewrite and Vaadin 7 dependencies, create Neo4j domain model entities** - `7cc5d33` (feat)
2. **Task 2: Create synthetic Vaadin 7 test fixture Java files** - `39796ce` (feat)

**Plan metadata:** (to be updated after final commit)

## Files Created/Modified

- `gradle/libs.versions.toml` - Added openrewrite 8.74.3 and vaadin-server 7.7.48 version entries + 3 library aliases
- `build.gradle.kts` - Added openrewrite-java, openrewrite-java-jdk21 (implementation) and vaadin-server (testImplementation) deps
- `src/main/java/com/esmp/extraction/model/ClassNode.java` - @Node("JavaClass") with business-key FQN @Id, @Version, @DynamicLabels, DECLARES_METHOD and DECLARES_FIELD relationships
- `src/main/java/com/esmp/extraction/model/MethodNode.java` - @Node("JavaMethod") with methodId @Id, @Version, CALLS outgoing relationship
- `src/main/java/com/esmp/extraction/model/FieldNode.java` - @Node("JavaField") with fieldId @Id, @Version
- `src/main/java/com/esmp/extraction/model/CallsRelationship.java` - @RelationshipProperties for CALLS edges with call site metadata
- `src/main/java/com/esmp/extraction/model/ContainsComponentRelationship.java` - @RelationshipProperties for Vaadin CONTAINS_COMPONENT edges
- `src/main/java/com/esmp/extraction/config/ExtractionConfig.java` - @ConfigurationProperties("esmp.extraction") binding source-root and classpath-file
- `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` - ApplicationRunner creating 3 uniqueness constraints via Neo4jClient
- `src/main/resources/application.yml` - Added esmp.extraction.source-root and classpath-file defaults
- `src/test/resources/fixtures/SampleEntity.java` - JPA entity fixture for annotation extraction tests
- `src/test/resources/fixtures/SampleRepository.java` - JpaRepository interface fixture for interface detection tests
- `src/test/resources/fixtures/SampleService.java` - @Service with repository calls for call graph extraction tests
- `src/test/resources/fixtures/SampleVaadinView.java` - @SpringView implements View with addComponent() calls for VaadinView label and CONTAINS_COMPONENT tests
- `src/test/resources/fixtures/SampleVaadinForm.java` - BeanFieldGroup data binding fixture for VaadinDataBinding label tests
- `src/test/resources/fixtures/SampleUI.java` - extends UI fixture for VaadinUI entry point detection tests

## Decisions Made

- **Version catalog alias naming:** Gradle cannot generate type-safe accessors for aliases ending in numeric segments (the `java-21` suffix created an `Unresolved reference 'java21'` error). Renamed alias to `openrewrite-java-jdk21`, which generates the accessor `libs.openrewrite.java.jdk21`.
- **@Version annotation source:** The `@Version` annotation for SDN optimistic locking is `org.springframework.data.annotation.Version` (from spring-data-commons), not `org.springframework.data.neo4j.core.schema.Version` (which does not exist).
- **vaadin-server scope:** Added as `testImplementation` only. Vaadin 7 uses `javax.servlet` which conflicts with Spring Boot's Jakarta EE runtime. The JAR is only needed for type symbol resolution during OpenRewrite parsing in tests.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed Gradle version catalog alias to avoid numeric suffix accessor failure**
- **Found during:** Task 1 (dependency setup)
- **Issue:** Alias `openrewrite-java-21` generates inaccessible Gradle type-safe accessor — Gradle cannot produce valid Kotlin property name from alias ending in digits
- **Fix:** Renamed alias to `openrewrite-java-jdk21` in libs.versions.toml, updated build.gradle.kts reference to `libs.openrewrite.java.jdk21`
- **Files modified:** gradle/libs.versions.toml, build.gradle.kts
- **Verification:** `./gradlew compileJava` succeeds after rename
- **Committed in:** `7cc5d33` (Task 1 commit)

**2. [Rule 1 - Bug] Fixed @Version import to use spring-data-commons package**
- **Found during:** Task 1 (entity compilation)
- **Issue:** Initial code imported `org.springframework.data.neo4j.core.schema.Version` which does not exist; SDN delegates optimistic locking to `org.springframework.data.annotation.Version`
- **Fix:** Changed import to `org.springframework.data.annotation.Version` in ClassNode, MethodNode, FieldNode
- **Files modified:** ClassNode.java, MethodNode.java, FieldNode.java
- **Verification:** `./gradlew compileJava` succeeds with 0 errors
- **Committed in:** `7cc5d33` (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (2 Rule 1 bugs)
**Impact on plan:** Both fixes required for compilation. No scope creep. All plan deliverables met.

## Issues Encountered

- Gradle type-safe accessor limitations with numeric alias suffixes — resolved by using descriptive suffix `jdk21`
- SDN @Version annotation location not in `neo4j.core.schema` package as initially assumed — resolved by locating correct spring-data-commons package

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Plan 02 (AST extraction visitors and call graph) can now use the ClassNode, MethodNode, FieldNode entities directly
- Plan 02 tests can use the 6 fixture files from src/test/resources/fixtures/
- ExtractionConfig is ready for binding esmp.extraction.source-root to point at a target project
- Neo4jSchemaInitializer will run on first startup and ensure uniqueness constraints are in place
- Remaining concern from STATE.md: OpenRewrite Vaadin 7 recipe coverage audit still required (Plans 02-03 scope)

## Self-Check: PASSED

All 13 created files verified present on disk. Both task commits (7cc5d33, 39796ce) confirmed in git log.

---
*Phase: 02-ast-extraction*
*Completed: 2026-03-04*
