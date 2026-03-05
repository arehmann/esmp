---
phase: 05-domain-lexicon
plan: 01
subsystem: extraction
tags: [neo4j, openrewrite, lexicon, business-terms, spring-data-neo4j, camelcase, javadoc]

requires:
  - phase: 03-code-knowledge-graph
    provides: ExtractionAccumulator, visitor pattern, JpaPatternVisitor table mappings
  - phase: 04-graph-validation-canonical-queries
    provides: Neo4j schema initializer pattern, Neo4jClient Cypher MERGE patterns

provides:
  - BusinessTermNode @Node entity with full LEX-01 fields (termId, displayName, definition, criticality, migrationSensitivity, synonyms, curated, status, sourceType, primarySourceFqn, usageCount)
  - LexiconVisitor extracting terms from camelCase class names, enum names/constants, and Javadoc
  - BusinessTermNodeRepository (findBySourceType, findByCurated)
  - Curated-guard MERGE Cypher in ExtractionService (LEX-02/LEX-04 compliance)
  - Heuristic criticality seeding (financial/security terms -> High)
  - DB table name term extraction in AccumulatorToModelMapper
  - business_term_id_unique Neo4j constraint
  - businessTermCount in ExtractionResult/ExtractionResponse

affects:
  - phase 05-02 (lexicon REST API uses BusinessTermNode and BusinessTermNodeRepository)
  - phase 05-03 (embedding pipeline consumes BusinessTermNode.definition)
  - any future phase building on domain lexicon

tech-stack:
  added: []
  patterns:
    - "TDD red-green: failing tests committed first, implementation second, fixtures as separate commit"
    - "Curated-guard MERGE: ON CREATE/ON MATCH Cypher preserves human-curated term definitions on re-extraction"
    - "First-occurrence-wins deduplication in ExtractionAccumulator via computeIfAbsent"
    - "Mutable inner class (not record) for BusinessTermData to allow allSourceFqns set mutation"
    - "Heuristic seeding: hard-coded financial/security keyword sets for criticality classification"
    - "maxDepth(1) in test fixture walkers to avoid cross-fixture contamination"

key-files:
  created:
    - src/main/java/com/esmp/extraction/model/BusinessTermNode.java
    - src/main/java/com/esmp/extraction/persistence/BusinessTermNodeRepository.java
    - src/main/java/com/esmp/extraction/visitor/LexiconVisitor.java
    - src/test/java/com/esmp/extraction/visitor/LexiconVisitorTest.java
    - src/test/resources/fixtures/lexicon/SampleInvoiceService.java
    - src/test/resources/fixtures/lexicon/PaymentStatusEnum.java
  modified:
    - src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java
    - src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java
    - src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java
    - src/main/java/com/esmp/extraction/application/ExtractionService.java
    - src/main/java/com/esmp/extraction/api/ExtractionController.java
    - src/main/java/com/esmp/extraction/api/ExtractionResponse.java
    - src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java
    - src/test/java/com/esmp/extraction/parser/JavaSourceParserTest.java

key-decisions:
  - "BusinessTermData uses mutable class not record: allSourceFqns set needs mutation after computeIfAbsent creation"
  - "Curated-guard MERGE via Neo4jClient (not saveAll()) prevents overwriting human-curated definitions on re-extraction"
  - "LexiconVisitor runs after JpaPatternVisitor in visitor loop so DB table mappings exist when DB_TABLE terms are added in mapper"
  - "Fixture walker depth limited to maxDepth(1) in existing tests to avoid lexicon fixtures contaminating existing fixture counts"
  - "STOP_SUFFIXES includes 'enum' so enum type names extract domain terms without 'enum' suffix"
  - "FQN fallback to simpleName when cd.getType() is null (unresolved type) ensures terms extracted in no-classpath test scenarios"

patterns-established:
  - "Visitor + Accumulator + Mapper pipeline pattern extended for Phase 5 term extraction"
  - "Curated-guard MERGE: standard Cypher pattern for any entity that humans can curate"

requirements-completed: [LEX-01, LEX-02]

duration: 37min
completed: 2026-03-05
---

# Phase 5 Plan 01: Domain Lexicon Foundation Summary

**BusinessTermNode model with LexiconVisitor (camelCase/enum/Javadoc extraction), curated-guard Neo4j MERGE, and heuristic criticality seeding for financial/security terms**

## Performance

- **Duration:** 37 min
- **Started:** 2026-03-05T08:24:38Z
- **Completed:** 2026-03-05T09:01:38Z
- **Tasks:** 2 (Task 1 TDD: red + green commits, Task 2: pipeline wiring)
- **Files modified:** 14

## Accomplishments

- BusinessTermNode @Node entity with all 11 required LEX-01 fields persists to Neo4j with unique constraint on termId
- LexiconVisitor splits PascalCase/camelCase class names, filters 28 technical stop-suffixes, extracts enum type names and UPPER_SNAKE_CASE constants with 12 generic stop-words
- Curated-guard MERGE Cypher ensures human-curated definitions (curated=true) are never overwritten on re-extraction (LEX-02 compliance)
- Heuristic criticality seeding: 13 financial keywords and 12 auth/security keywords map to "High" criticality, all others "Low"
- DB table name terms extracted from acc.getTableMappings() in mapper post-visitor step, merged with visitor-extracted terms

## Task Commits

1. **TDD RED: Failing LexiconVisitor tests with fixtures** - `9aac7fb` (test)
2. **TDD GREEN: BusinessTermNode model, LexiconVisitor, repository and schema** - `b896433` (feat)
3. **Task 2: Wire LexiconVisitor into pipeline with curated-guard MERGE** - `d8ff90b` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/extraction/model/BusinessTermNode.java` - @Node("BusinessTerm") entity with all LEX-01 fields
- `src/main/java/com/esmp/extraction/persistence/BusinessTermNodeRepository.java` - Neo4jRepository with findBySourceType/findByCurated
- `src/main/java/com/esmp/extraction/visitor/LexiconVisitor.java` - JavaIsoVisitor extracting terms from class names, enums, Javadoc
- `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` - Added BusinessTermData class and addBusinessTerm()/getBusinessTerms()
- `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` - Added business_term_id_unique constraint
- `src/main/java/com/esmp/extraction/application/AccumulatorToModelMapper.java` - Added mapToBusinessTermNodes() with criticality seeding and DB table terms
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` - LexiconVisitor wired, persistBusinessTermNodes() with curated-guard MERGE
- `src/main/java/com/esmp/extraction/api/ExtractionController.java` - businessTermCount in response
- `src/main/java/com/esmp/extraction/api/ExtractionResponse.java` - businessTermCount field added
- `src/test/java/com/esmp/extraction/visitor/LexiconVisitorTest.java` - 17 tests covering all extraction scenarios
- `src/test/resources/fixtures/lexicon/SampleInvoiceService.java` - Fixture with Javadoc comment
- `src/test/resources/fixtures/lexicon/PaymentStatusEnum.java` - Fixture with PENDING_APPROVAL, ACTIVE, COMPLETED constants
- `src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java` - maxDepth(1) fix
- `src/test/java/com/esmp/extraction/parser/JavaSourceParserTest.java` - maxDepth(1) fix

## Decisions Made

- **BusinessTermData uses mutable class not record:** The `allSourceFqns` set requires mutation after the initial `computeIfAbsent` call — records are immutable so a regular static class was used instead.
- **Curated-guard MERGE via Neo4jClient:** Plain `saveAll()` would overwrite curated definitions on re-extraction. Neo4jClient with explicit ON CREATE/ON MATCH Cypher is the only way to implement the curated-protection invariant.
- **LexiconVisitor after JpaPatternVisitor in visitor loop:** DB table mappings need to exist in the accumulator when `mapToBusinessTermNodes()` processes them. Visitor order matters; lexicon visitor is last.
- **maxDepth(1) in existing fixture walkers:** New `fixtures/lexicon/` subdirectory caused `ClassMetadataVisitorTest.extractsAllSixFixtureClasses()` and `JavaSourceParserTest.parser_parsesAllSixFixtureFiles()` to assert size 8 instead of 6. Depth limiting is the correct fix — lexicon fixtures are domain-specific and should not be included in general AST tests.
- **STOP_SUFFIXES includes "enum":** `PaymentStatusEnum` should produce "payment" and "status" terms, not "enum". Added "enum" to stop-suffixes so the classification suffix is stripped.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Test used record-style accessors on mutable class fields**
- **Found during:** Task 1 TDD GREEN (compilation of LexiconVisitorTest)
- **Issue:** Test called `pendingData.primarySourceFqn()`, `orderTerm.javadocSeed()`, `paymentTerm.allSourceFqns()` as method calls, but BusinessTermData is a class with public fields (no accessor methods)
- **Fix:** Updated test to access fields directly (`pendingData.primarySourceFqn`, `orderTerm.javadocSeed`, etc.)
- **Files modified:** LexiconVisitorTest.java
- **Committed in:** b896433 (Task 1 feat commit)

**2. [Rule 1 - Bug] Deduplication test expected "payment" from both fixtures but SampleInvoiceService doesn't contain "payment" in name**
- **Found during:** Task 1 TDD GREEN (1 test failure after initial green)
- **Issue:** Test `deduplication_sameTermFromTwoSources_onlyOneEntry` expected `allSourceFqns.size() >= 2` for "payment", but SampleInvoiceService splits to "sample/invoice" (no "payment"). Only PaymentStatusEnum produces "payment".
- **Fix:** Rewrote test to verify single-source "invoice" term from SampleInvoiceService, and added `deduplication_sameTermFromInlineMultiSource_allSourceFqnsTracked` test using inline-parsed OrderService/OrderRepository classes to test multi-source tracking.
- **Files modified:** LexiconVisitorTest.java
- **Committed in:** b896433 (Task 1 feat commit)

**3. [Rule 1 - Bug] J.Statement import — Statement is in org.openrewrite.java.tree.Statement not J.Statement**
- **Found during:** Task 1 TDD GREEN (compilation of LexiconVisitor)
- **Issue:** LexiconVisitor used `J.Statement` in for loop over enum body statements but `Statement` is a top-level interface in `org.openrewrite.java.tree`, not nested in `J`
- **Fix:** Added `import org.openrewrite.java.tree.Statement` and changed loop to `for (Statement stmt : ...)`
- **Files modified:** LexiconVisitor.java
- **Committed in:** b896433 (Task 1 feat commit)

**4. [Rule 2 - Missing Critical] Existing fixture walkers needed maxDepth(1) to prevent count regression**
- **Found during:** Task 2 full test suite run
- **Issue:** ClassMetadataVisitorTest (expected 6 classes) and JavaSourceParserTest (expected 6 files) found 8 due to new lexicon fixtures. Pre-existing tests relying on exact counts.
- **Fix:** Added `maxDepth(1)` argument to `Files.walk()` in both test files' fixture scanning helpers.
- **Files modified:** ClassMetadataVisitorTest.java, JavaSourceParserTest.java
- **Committed in:** d8ff90b (Task 2 feat commit)

---

**Total deviations:** 4 auto-fixed (3 bug fixes, 1 missing critical)
**Impact on plan:** All auto-fixes necessary for correctness. No scope creep. Core logic unchanged from plan.

## Issues Encountered

- OpenRewrite `J.ClassDeclaration.getType()` returns null for fixtures with unresolvable superclass references. Added a fallback to use `cd.getSimpleName()` as FQN and `cd.getKind()` for enum detection. This ensures term extraction works even when the parser cannot fully resolve types (common in no-classpath test scenarios).

## User Setup Required

None - no external service configuration required. BusinessTermNode nodes will be created in Neo4j on next extraction run.

## Next Phase Readiness

- BusinessTermNode model fully persisted to Neo4j with curated-guard protection
- BusinessTermNodeRepository ready for Phase 5 Plan 02 lexicon REST API
- Criticality seeding heuristic established; can be refined in Phase 5 Plan 03 (embedding)
- LEX-01 and LEX-02 requirements met

## Self-Check: PASSED

All created files present. All task commits verified in git log.

---
*Phase: 05-domain-lexicon*
*Completed: 2026-03-05*
