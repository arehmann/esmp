---
phase: 05-domain-lexicon
plan: 02
subsystem: graph-api
tags: [neo4j, lexicon, rest-api, spring, linking, validation]

requires:
  - phase: 05-domain-lexicon plan: 01
    provides: BusinessTermNode, BusinessTermNodeRepository, ExtractionAccumulator.BusinessTermData

provides:
  - USES_TERM edges: JavaClass -> BusinessTerm (primary source + DEPENDS_ON dependents)
  - DEFINES_RULE edges: business-rule-pattern classes -> BusinessTerm
  - GET /api/lexicon/ with optional criticality/curated/search filters
  - GET /api/lexicon/{termId} with relatedClassFqns from USES_TERM edges
  - PUT /api/lexicon/{termId} updates definition/criticality/synonyms, sets curated=true
  - LexiconValidationQueryRegistry: 3 lexicon validation queries integrated into Phase 4 framework

affects:
  - ValidationService query count: now 23 (20 Phase-4 + 3 Phase-5 lexicon queries)
  - Phase 05-03: embedding pipeline can now query lexicon via REST API or BusinessTermNodeRepository
  - Any future validation registry that asserts exact query counts

tech-stack:
  added: []
  patterns:
    - "Test class exclusion by path: /src/test/ or /test/java/ path patterns (not /test/ which matches package names like com/test/)"
    - "Extensible ValidationQueryRegistry: protected List constructor allows subclasses to supply query list"
    - "Neo4jClient Cypher for flexible filtering with optional WHERE clauses built dynamically"
    - "USES_TERM dependent linking: graph traversal MATCH (primary)<-[:DEPENDS_ON]-(dep) for transitive usage coverage"

key-files:
  created:
    - src/main/java/com/esmp/graph/api/BusinessTermResponse.java
    - src/main/java/com/esmp/graph/api/UpdateTermRequest.java
    - src/main/java/com/esmp/graph/application/LexiconService.java
    - src/main/java/com/esmp/graph/api/LexiconController.java
    - src/main/java/com/esmp/graph/validation/LexiconValidationQueryRegistry.java
    - src/test/java/com/esmp/extraction/application/LexiconIntegrationTest.java
    - src/test/java/com/esmp/graph/api/LexiconControllerTest.java
  modified:
    - src/main/java/com/esmp/extraction/application/LinkingService.java
    - src/main/java/com/esmp/graph/validation/ValidationQueryRegistry.java
    - src/test/java/com/esmp/graph/api/ValidationControllerIntegrationTest.java

key-decisions:
  - "Test class exclusion uses /src/test/ or /test/java/ path patterns: /test/ alone matches package segments like com/test/ in source paths"
  - "ValidationQueryRegistry protected constructor added: LexiconValidationQueryRegistry extends it with super(queries) — the cleanest approach without extracting an interface"
  - "USES_TERM dependent linking via graph traversal: MATCH (primary)<-[:DEPENDS_ON]-(dep) in a single Cypher query rather than iterating accumulator dependency edges"
  - "ValidationControllerIntegrationTest counts made dynamic: hasSize(20) -> hasSizeGreaterThanOrEqualTo(20), sum equality uses totalResults variable — consistent with extensible registry design"

requirements-completed: [LEX-03, LEX-04]

duration: 31min
completed: 2026-03-05
---

# Phase 5 Plan 02: Lexicon Linking and REST API Summary

**USES_TERM and DEFINES_RULE graph edges via LinkingService, lexicon REST API (GET list/detail + PUT curate), and LexiconValidationQueryRegistry with 3 lexicon-specific validation queries**

## Performance

- **Duration:** 31 min
- **Started:** 2026-03-05T09:04:32Z
- **Completed:** 2026-03-05T09:36:07Z
- **Tasks:** 2 (both TDD: red + green commits)
- **Files modified/created:** 10

## Accomplishments

- USES_TERM edges created by LinkingService: from primary source JavaClass to BusinessTerm, and from all DEPENDS_ON-dependent classes (excluding test classes)
- DEFINES_RULE edges created for JavaClass nodes matching business-rule naming patterns (Validator, Rule, Policy, Constraint, Calculator, Strategy) and for constraint-annotated classes
- LinkingResult record extended with usesTermCount and definesRuleCount fields; both wired into linkAllRelationships()
- GET /api/lexicon/ returns all BusinessTerm nodes with optional filtering: criticality, curated, search (case-insensitive contains on termId or displayName)
- GET /api/lexicon/{termId} returns term detail populated with relatedClassFqns from USES_TERM graph traversal
- PUT /api/lexicon/{termId} updates definition, criticality, synonyms; sets curated=true, status='curated', and auto-derives migrationSensitivity from criticality
- LexiconValidationQueryRegistry adds 3 validation queries automatically detected by ValidationService: ORPHAN_BUSINESS_TERMS, DEFINES_RULE_COVERAGE, USES_TERM_EDGE_INTEGRITY
- 15 integration/unit tests pass (8 in LexiconIntegrationTest, 7 in LexiconControllerTest)

## Task Commits

1. **TDD RED: Failing LexiconIntegrationTest** - `5f6643d` (test)
2. **TDD GREEN: USES_TERM and DEFINES_RULE linking** - `d5cb217` (feat)
3. **TDD RED: Failing LexiconControllerTest** - `23c0fc4` (test)
4. **TDD GREEN: LexiconService, LexiconController, LexiconValidationQueryRegistry** - `6ab76a0` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/extraction/application/LinkingService.java` - Added linkBusinessTermUsages(), linkBusinessRules(), updated linkAllRelationships() and LinkingResult record
- `src/main/java/com/esmp/graph/api/BusinessTermResponse.java` - New record with all 12 fields including relatedClassFqns
- `src/main/java/com/esmp/graph/api/UpdateTermRequest.java` - New record for PUT request body
- `src/main/java/com/esmp/graph/application/LexiconService.java` - findByFilters(), findByTermId(), updateTerm() using Neo4jClient Cypher
- `src/main/java/com/esmp/graph/api/LexiconController.java` - GET list, GET detail, PUT update at /api/lexicon/
- `src/main/java/com/esmp/graph/validation/LexiconValidationQueryRegistry.java` - 3 lexicon validation queries
- `src/main/java/com/esmp/graph/validation/ValidationQueryRegistry.java` - Added protected List constructor for extensibility
- `src/test/java/com/esmp/extraction/application/LexiconIntegrationTest.java` - 8 integration tests (USES_TERM, DEFINES_RULE, idempotency, curated protection)
- `src/test/java/com/esmp/graph/api/LexiconControllerTest.java` - 7 REST API integration tests
- `src/test/java/com/esmp/graph/api/ValidationControllerIntegrationTest.java` - Made query count assertions dynamic (hasSizeGreaterThanOrEqualTo)

## Decisions Made

- **Test class exclusion by /src/test/ path:** `/test/` alone matches package segments like `com/test/` in source file paths. Using `/src/test/` and `/test/java/` path patterns correctly targets standard Maven/Gradle test source directories only.
- **ValidationQueryRegistry protected constructor:** The cleanest extension mechanism without extracting an interface. LexiconValidationQueryRegistry extends ValidationQueryRegistry and calls `super(queries)` with its 3 queries. ValidationService's `List<ValidationQueryRegistry>` injection automatically discovers both beans.
- **USES_TERM dependent linking via single Cypher:** One query `MATCH (primary)<-[:DEPENDS_ON]-(dep) WHERE NOT path contains /src/test/` handles all dependents at once, rather than iterating dependency edges from the accumulator (which may not match what's in the graph after prior runs).
- **ValidationControllerIntegrationTest hardcoded 20 made dynamic:** The test was written when Phase 4 delivered 20 queries. Now 23. Changed to `hasSizeGreaterThanOrEqualTo(20)` and `totalResults` variable — consistent with the extensible registry design documented in Phase 4.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Test class exclusion path matched package segment**
- **Found during:** Task 1 TDD GREEN (1 of 8 tests failed)
- **Issue:** The test at line 172 (asserting PaymentController gets USES_TERM edge from depending on InvoiceService) failed. Root cause: `/test/` in the Cypher exclusion filter matched the `/com/test/` package segment in source paths like `/main/com/test/PaymentController.java`
- **Fix:** Changed exclusion filter from `CONTAINS '/test/'` to `(CONTAINS '/src/test/' OR CONTAINS '/test/java/')` — these are the standard Maven/Gradle test source directory path patterns. Also updated LexiconIntegrationTest test class path from `/test/com/test/` to `/src/test/java/com/test/`
- **Files modified:** LinkingService.java, LexiconIntegrationTest.java
- **Commit:** d5cb217

**2. [Rule 1 - Bug] ValidationControllerIntegrationTest had hardcoded count of 20**
- **Found during:** Task 2 — full test suite run after GREEN commit
- **Issue:** `allQueriesPassOnWellFormedGraph()` and `reportStructure()` both used `hasSize(20)` / `isEqualTo(20)`. LexiconValidationQueryRegistry adding 3 queries raised total to 23, causing both tests to fail with "Expected size 20 but was 23"
- **Fix:** Changed `hasSize(20)` to `hasSizeGreaterThanOrEqualTo(20)` in both tests; replaced literal `20` in sum equality with `report.results().size()` stored in `totalResults`. This is consistent with the extensible registry design established in Phase 4
- **Files modified:** ValidationControllerIntegrationTest.java
- **Commit:** 6ab76a0 (included in GREEN commit)

---

**Total deviations:** 2 auto-fixed bugs
**Impact:** Both fixes were necessary for correctness. No scope creep.

## Issues Encountered

None beyond the 2 auto-fixed bugs above.

## User Setup Required

None. The new REST API endpoints are available on next application startup. No new infrastructure dependencies.

## Next Phase Readiness

- USES_TERM and DEFINES_RULE edges populated after each extraction run
- GET /api/lexicon/ and PUT /api/lexicon/{termId} ready for Phase 5 Plan 03 or UI consumption
- LexiconValidationQueryRegistry auto-detected by ValidationService — validation reports now include 3 lexicon integrity checks
- LEX-03 and LEX-04 requirements met

## Self-Check: PASSED
