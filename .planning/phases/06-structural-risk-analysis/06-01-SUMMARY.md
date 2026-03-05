---
phase: 06-structural-risk-analysis
plan: "01"
subsystem: extraction-pipeline
tags: [complexity, risk-metrics, cyclomatic-complexity, db-writes, openrewrite, tdd]
dependency_graph:
  requires:
    - 05-03 (LexiconVisitor pipeline position - ComplexityVisitor runs after)
  provides:
    - ComplexityVisitor: cyclomatic complexity and DB write detection per method/class
    - ClassNode.complexitySum, ClassNode.complexityMax: class-level CC aggregates
    - ClassNode.hasDbWrites, ClassNode.dbWriteCount: DB write classification
    - MethodNode.cyclomaticComplexity: per-method CC metric
  affects:
    - 06-02 (RiskService reads CC and DB write data from ClassNode/MethodNode)
tech_stack:
  added:
    - ComplexityVisitor (new JavaIsoVisitor in extraction pipeline)
  patterns:
    - Deque<int[]> counter stack for per-method CC tracking (no state leaking across files)
    - Simple-name-to-FQN fallback for @Modifying annotation (mirrors JpaPatternVisitor)
    - Map.merge() for incremental DB write count accumulation
    - TDD: RED commit (test only) -> GREEN commit (implementation)
key_files:
  created:
    - src/main/java/com/esmp/extraction/visitor/ComplexityVisitor.java
    - src/test/java/com/esmp/extraction/visitor/ComplexityVisitorTest.java
  modified:
    - src/main/java/com/esmp/extraction/model/ClassNode.java
    - src/main/java/com/esmp/extraction/model/MethodNode.java
    - src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java
    - src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java
    - src/main/java/com/esmp/extraction/application/ExtractionService.java
decisions:
  - "ComplexityVisitor uses Deque<int[]> counter stack to safely handle nested methods and lambdas without leaking CC across source files"
  - "DB write deduplication uses a Set<String> of flagged methodIds to prevent double-counting the same method via both @Modifying and @Query write SQL"
  - "J.Case.getCaseLabels() returns List<J> in OpenRewrite 8.x (not List<? extends Expression>) - isDefaultCase() iterates using J type"
  - "structuralRiskScore initialized to 0.0 in mapper; computation deferred to Plan 02 after fan-in/out are computed via Cypher"
  - "Integration tests (LinkingServiceIntegrationTest, LexiconIntegrationTest) require Docker/Neo4j - pre-existing failures not caused by this plan"
metrics:
  duration: 35min
  completed: "2026-03-05"
  tasks_completed: 2
  files_changed: 7
---

# Phase 6 Plan 01: ComplexityVisitor and DB Write Detection Summary

AST-time cyclomatic complexity counting and DB write detection via ComplexityVisitor, with model extensions and pipeline wiring.

## What Was Built

### ComplexityVisitor (new)

`JavaIsoVisitor<ExtractionAccumulator>` that computes cyclomatic complexity per method and detects DB write operations per class during the OpenRewrite AST parse pass.

**CC counting strategy:** A `Deque<int[]>` counter stack ensures each method declaration gets its own counter. Entering `visitMethodDeclaration` pushes a new `int[]{0}`, all branch visitors (`visitIf`, `visitTernary`, `visitForLoop`, etc.) call `incrementTopCounter()`, and exiting pops the counter and commits `cc = counter + 1` (baseline) to the accumulator.

**Branch points counted:** `if`, ternary, `for`, `for-each`, `while`, `do-while`, non-default `switch case`, `catch`.

**DB write detection:** Three signal types: (1) `@Modifying` annotation on method, (2) `@Query` annotation with `INSERT|UPDATE|DELETE` in string value (case-insensitive regex), (3) invocation of `persist`, `merge`, `remove`, `delete`, `save`, `saveAll`, `deleteAll`, or `flush`. A `Set<String>` deduplicates writes so the same method is never double-counted.

### Model Extensions

**ClassNode.java** — 7 new properties:
- `complexitySum` (int)
- `complexityMax` (int)
- `fanIn` (int) — populated in Plan 02
- `fanOut` (int) — populated in Plan 02
- `hasDbWrites` (boolean)
- `dbWriteCount` (int)
- `structuralRiskScore` (double) — computed in Plan 02

**MethodNode.java** — 1 new property:
- `cyclomaticComplexity` (int)

### ExtractionAccumulator Extensions

- `methodComplexities: Map<String, MethodComplexityData>` with `addMethodComplexity()` and `getMethodComplexities()`
- `classWriteData: Map<String, ClassWriteData>` with `incrementClassDbWrites()` (using `Map.merge()`) and `getClassWriteData()`
- New records: `MethodComplexityData(methodId, declaringClassFqn, cyclomaticComplexity)` and `ClassWriteData(classFqn, writeCount)`

### AccumulatorToModelMapper Updates

- After building each MethodNode: looks up `acc.getMethodComplexities().get(methodId)` and sets `cyclomaticComplexity`
- After building each ClassNode: computes `complexitySum` and `complexityMax` from the class's MethodNode list; maps `hasDbWrites` and `dbWriteCount` from `ClassWriteData`; sets `structuralRiskScore = 0.0`

### ExtractionService Pipeline

`ComplexityVisitor` added after `LexiconVisitor` in the per-source-file visitor loop.

## Test Results

| Test Class | Tests | Passed | Notes |
|---|---|---|---|
| ComplexityVisitorTest | 15 | 15 | New: all CC + DB write scenarios |
| ClassMetadataVisitorTest | 11 | 11 | No regression |
| CallGraphVisitorTest | 3 | 3 | No regression |
| DependencyVisitorTest | 4 | 4 | No regression |
| JpaPatternVisitorTest | 8 | 8 | No regression |
| LexiconVisitorTest | 17 | 17 | No regression |
| VaadinPatternVisitorTest | 11 | 11 | No regression |
| **Total visitor unit tests** | **69** | **69** | |

Integration tests (LinkingServiceIntegrationTest, LexiconIntegrationTest) require Docker/Neo4j — pre-existing failures, unrelated to this plan.

## Commits

| Task | Commit | Description |
|---|---|---|
| Task 1 (TDD) | `e1361b1` | ComplexityVisitor, model risk properties, accumulator extensions (15 tests) |
| Task 2 (wiring) | `a5bb1e7` | Mapper complexity mapping + ExtractionService pipeline wiring |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] J.Case.getCaseLabels() type mismatch in OpenRewrite 8.x**
- **Found during:** Task 1 (first GREEN compile attempt)
- **Issue:** `J.Case.getCaseLabels()` returns `List<J>` (not `List<? extends Expression>`) in OpenRewrite 8.74.3 — the plan specified `List<? extends Expression>` which caused a compile error
- **Fix:** Changed `isDefaultCase()` to iterate over `List<J>` directly
- **Files modified:** `ComplexityVisitor.java`
- **Commit:** `e1361b1` (fixed inline before commit)

## Self-Check: PASSED

All artifacts verified:
- FOUND: ComplexityVisitor.java
- FOUND: ComplexityVisitorTest.java
- FOUND: 06-01-SUMMARY.md
- FOUND: commit e1361b1 (Task 1)
- FOUND: commit a5bb1e7 (Task 2)
- FOUND: 7 new ClassNode properties (complexitySum, complexityMax, fanIn, fanOut, hasDbWrites, dbWriteCount, structuralRiskScore)
- FOUND: cyclomaticComplexity in MethodNode
- FOUND: complexityVisitor.visit wired in ExtractionService
