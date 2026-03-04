---
phase: 03-code-knowledge-graph
plan: 04
subsystem: extraction-pipeline
tags: [binds-to, vaadin, linking-service, graph-relationships, ckg-02]
dependency_graph:
  requires: [03-01, 03-02, 03-03]
  provides: [BINDS_TO-edges-end-to-end]
  affects: [ExtractionService, LinkingService, VaadinPatternVisitor]
tech_stack:
  added: []
  patterns: [OpenRewrite-Parameterized-type-extraction, Cypher-MERGE-idempotency]
key_files:
  created: []
  modified:
    - src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java
    - src/main/java/com/esmp/extraction/application/LinkingService.java
    - src/test/java/com/esmp/extraction/visitor/VaadinPatternVisitorTest.java
    - src/test/java/com/esmp/extraction/application/LinkingServiceIntegrationTest.java
decisions:
  - "BeanItemContainer excluded from BINDS_TO detection: it is a data source (container), not a form-to-entity binding mechanism like BeanFieldGroup/FieldGroup"
  - "Entity FQN falls back to 'Unknown' when BeanFieldGroup/FieldGroup has no generic type parameter (plain FieldGroup without generics)"
  - "BINDS_TO MERGE includes bindingMechanism as part of relationship key to allow distinct edges per mechanism if needed"
metrics:
  duration: 4min
  completed_date: "2026-03-04"
  tasks_completed: 2
  files_modified: 4
requirements:
  - CKG-02
---

# Phase 3 Plan 04: BINDS_TO Gap Closure Summary

**One-liner:** Wired BINDS_TO relationship end-to-end: VaadinPatternVisitor extracts entity type from BeanFieldGroup generic parameter and LinkingService materializes edges via idempotent Cypher MERGE.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | Failing test for BINDS_TO detection | 246ef79 | VaadinPatternVisitorTest.java |
| 1 (GREEN) | VaadinPatternVisitor emits BINDS_TO edges | 52c5c68 | VaadinPatternVisitor.java |
| 2 (RED) | Failing integration test for linkBindsToEdges | 1f88c3a | LinkingServiceIntegrationTest.java |
| 2 (GREEN) | LinkingService.linkBindsToEdges() + LinkingResult update | 6c573fd | LinkingService.java |

## What Was Built

### Task 1: VaadinPatternVisitor BINDS_TO Detection

Extended `visitNewClass()` in `VaadinPatternVisitor` to emit BINDS_TO edge data when BeanFieldGroup or FieldGroup is instantiated:

1. Checks if the new class type is BeanFieldGroup or FieldGroup (excludes BeanItemContainer per plan spec)
2. Determines binding mechanism name ("BeanFieldGroup" or "FieldGroup")
3. Extracts entity FQN from generic type parameter via `JavaType.Parameterized.getTypeParameters().get(0)` — falls back to "Unknown" when no generics present
4. Calls `acc.addBindsToEdge(enclosingClassFqn, entityFqn, bindingMechanism)`

For `SampleVaadinForm` with `new BeanFieldGroup<>(SampleEntity.class)`, the visitor correctly emits:
- viewFqn: `com.example.sample.SampleVaadinForm`
- entityFqn: `com.example.sample.SampleEntity`
- mechanism: `BeanFieldGroup`

### Task 2: LinkingService BINDS_TO Materialization

Added `linkBindsToEdges()` method to `LinkingService` following the same pattern as `linkDependencies()`:

- Iterates `acc.getBindsToEdges()` and runs Cypher MERGE for each record
- MERGE includes `bindingMechanism` as a relationship property key — idempotent by design
- Returns 0 early if accumulator has no BINDS_TO edges
- `linkAllRelationships()` now calls `linkBindsToEdges()` and logs the count
- `LinkingResult` record gains `bindsToCount` field (7th field)

## Verification

- `./gradlew test --tests "com.esmp.extraction.visitor.VaadinPatternVisitorTest"` — PASS (6 tests including new BINDS_TO test)
- `./gradlew test --tests "com.esmp.extraction.application.LinkingServiceIntegrationTest"` — PASS (6 tests including new BINDS_TO test)
- `./gradlew compileJava` — PASS (no regressions from LinkingResult signature change)

## CKG-02 Relationship Coverage (All 7 Now Wired)

| Relationship | Visitor | Linking Method | Status |
|---|---|---|---|
| CALLS | CallGraphVisitor | (stored on MethodNode) | Done |
| EXTENDS | ClassMetadataVisitor | linkInheritanceRelationships() | Done |
| IMPLEMENTS | ClassMetadataVisitor | linkInheritanceRelationships() | Done |
| DEPENDS_ON | DependencyVisitor | linkDependencies() | Done |
| MAPS_TO_TABLE | JpaPatternVisitor | linkTableMappings() | Done |
| QUERIES | JpaPatternVisitor | linkQueryMethods() | Done |
| BINDS_TO | VaadinPatternVisitor | linkBindsToEdges() | **Done (this plan)** |

## Deviations from Plan

None — plan executed exactly as written.

## Self-Check: PASSED

- All 4 modified files exist on disk
- All 4 task commits found in git log (246ef79, 52c5c68, 1f88c3a, 6c573fd)
- addBindsToEdge call confirmed in VaadinPatternVisitor.java
- linkBindsToEdges method confirmed in LinkingService.java
- bindsToCount field confirmed in LinkingResult record
