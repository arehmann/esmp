---
phase: 05-domain-lexicon
plan: 03
subsystem: ui
tags: [vaadin, vaadin-24, grid, lexicon, ui, curation, spring-boot]

requires:
  - phase: 05-domain-lexicon plan: 02
    provides: LexiconService (findAll, findByTermId, updateTerm), BusinessTermNode, BusinessTermResponse

provides:
  - Vaadin 24 plugin and BOM integrated into Gradle build (vaadin-spring-boot-starter)
  - LexiconView @Route("lexicon"): sortable, filterable Grid<BusinessTermNode> with 7 columns
  - TermDetailDialog: read-only dialog listing related class FQNs fetched from LexiconService.findByTermId()
  - TermEditorDialog: inline term editor for definition, criticality, synonyms with Consumer<UpdateResult> callback
  - LexiconService.findAll() convenience method returning List<BusinessTermNode>
  - In-memory filter via SerializablePredicate + ListDataProvider (single setFilter per change — no accumulation)

affects:
  - Phase 12 Governance Dashboard (Vaadin 24 pattern established here)
  - Any future Vaadin view in the project follows the Route + VerticalLayout pattern

tech-stack:
  added:
    - com.vaadin:vaadin-spring-boot-starter (24.9.12 via BOM)
    - com.vaadin Gradle plugin (24.9.12)
  patterns:
    - "Vaadin 24 view: @Route + @PageTitle + extends VerticalLayout + constructor DI of Spring services"
    - "In-memory grid filtering: ListDataProvider.setFilter(SerializablePredicate) — single composite predicate replaced on each change"
    - "Usage-count drill-down: LUMO_TERTIARY_INLINE button calls service.findByTermId() and opens TermDetailDialog"
    - "Term edit dialog: Consumer<UpdateResult> callback pattern separates UI from service call"

key-files:
  created:
    - src/main/java/com/esmp/ui/LexiconView.java
    - src/main/java/com/esmp/ui/TermEditorDialog.java
    - src/main/java/com/esmp/ui/TermDetailDialog.java
  modified:
    - build.gradle.kts
    - gradle/libs.versions.toml
    - src/main/java/com/esmp/graph/application/LexiconService.java

key-decisions:
  - "SerializablePredicate from com.vaadin.flow.function (not com.vaadin.flow.server) — correct package in Vaadin 24 flow-server JAR"
  - "LexiconService.findAll() added delegating to businessTermNodeRepository.findAll() — LexiconView needs BusinessTermNode entities for in-memory ListDataProvider, not BusinessTermResponse records"
  - "appendHeaderRow() stores HeaderRow reference directly — avoids fragile getHeaderRows().get(size-1) calls for multi-column filter setup"

patterns-established:
  - "Vaadin 24 views live in com.esmp.ui (under main com.esmp package scan) — no @EnableVaadin needed"
  - "Filter row via appendHeaderRow() with named column references (not index-based)"

requirements-completed: [LEX-04]

duration: 6min
completed: 2026-03-05
human-verify: approved
---

# Phase 5 Plan 03: Vaadin 24 Lexicon UI Summary

**Vaadin 24 added to Gradle build with sortable/filterable LexiconView grid, usage-count detail-on-click via TermDetailDialog, and inline term curation via TermEditorDialog — human verified and approved**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-05T09:38:36Z
- **Completed:** 2026-03-05T09:43:51Z
- **Tasks:** 2/2 (Task 1 auto; Task 2 checkpoint:human-verify — APPROVED)
- **Files modified:** 6

## Accomplishments

- Vaadin 24.9.12 integrated via Gradle plugin + BOM + vaadin-spring-boot-starter; project compiles cleanly with no unchecked warnings
- LexiconView at /lexicon renders all BusinessTerm nodes in a sortable 7-column grid with in-memory filter on term name, criticality, and status
- Usage count column renders LUMO_TERTIARY_INLINE button; click fetches relatedClassFqns from LexiconService.findByTermId() and opens TermDetailDialog
- TermEditorDialog allows editing definition (TextArea), criticality (ComboBox), and synonyms (comma-separated TextField); save calls LexiconService.updateTerm() and refreshes the grid item
- Filter row uses single SerializablePredicate composite rebuilt on each change (no filter accumulation)

## Task Commits

1. **Task 1: Add Vaadin 24 to Gradle build and create LexiconView with TermEditorDialog and TermDetailDialog** - `40d7100` (feat)

## Files Created/Modified

- `gradle/libs.versions.toml` - Added vaadin=24.9.12 version, vaadin-spring-boot-starter library, vaadin-bom library, com.vaadin plugin
- `build.gradle.kts` - Added com.vaadin plugin alias, vaadin addons maven repo, dependencyManagement BOM import, vaadin-spring-boot-starter dependency
- `src/main/java/com/esmp/graph/application/LexiconService.java` - Added findAll() delegating to BusinessTermNodeRepository for in-memory grid binding
- `src/main/java/com/esmp/ui/LexiconView.java` - @Route("lexicon") view: Grid<BusinessTermNode> with sortable columns, filter header row, usage-count drill-down, edit actions
- `src/main/java/com/esmp/ui/TermDetailDialog.java` - Read-only Dialog showing Grid<String> of related class FQNs
- `src/main/java/com/esmp/ui/TermEditorDialog.java` - Editor Dialog with TextArea/ComboBox/TextField fields and Consumer<UpdateResult> callback

## Decisions Made

- **SerializablePredicate in com.vaadin.flow.function:** The type is at `com.vaadin.flow.function.SerializablePredicate` (not `com.vaadin.flow.server`). Discovered by inspecting the flow-server JAR. `ListDataProvider.setFilter()` does not accept `java.util.function.Predicate` — requires the Vaadin-serializable subtype.
- **LexiconService.findAll() returns List<BusinessTermNode>:** LexiconView uses `ListDataProvider<BusinessTermNode>` for in-memory sorting/filtering. The existing `findByFilters()` returns `List<BusinessTermResponse>` which lacks the mutable setters needed to refresh individual items after editing. Adding a simple `findAll()` that delegates to the repository is the cleanest solution.
- **appendHeaderRow() reference stored:** Calling `grid.appendHeaderRow()` once and storing the `HeaderRow` reference avoids the error-prone `getHeaderRows().get(size-1)` pattern when setting multiple filter components on the same appended row.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] SerializablePredicate wrong package**
- **Found during:** Task 1 (first compileJava attempt)
- **Issue:** Used `com.vaadin.flow.server.SerializablePredicate` (from plan intent). Correct package is `com.vaadin.flow.function.SerializablePredicate`.
- **Fix:** Updated import to `com.vaadin.flow.function.SerializablePredicate` after inspecting JAR contents.
- **Files modified:** LexiconView.java
- **Committed in:** 40d7100 (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Necessary fix for compilation. No scope creep.

## Issues Encountered

None beyond the one auto-fixed import error.

## User Setup Required

**To test the UI:**
1. Run `docker compose up -d` to start Neo4j, MySQL, Qdrant
2. Run `./gradlew bootRun` to start the application
3. If no terms are in the graph, trigger extraction first:
   `curl -X POST "http://localhost:8080/api/extraction/trigger?sourceRoot=/path/to/java/sources"`
4. Open `http://localhost:8080/lexicon`

No new environment variables or infrastructure dependencies.

## Next Phase Readiness

Phase 05-domain-lexicon is fully complete across all three plans:

- **05-01:** BusinessTermNode model, term extraction visitor (class names, enums, Javadoc, DB table), curated-guard MERGE — LEX-01, LEX-02, LEX-03 met
- **05-02:** USES_TERM and DEFINES_RULE graph edges, LexiconService (CRUD + filter), LexiconController REST API at `/api/lexicon/`, LexiconValidationQueryRegistry — LEX-03 expanded
- **05-03:** Vaadin 24 UI at `/lexicon` — sortable/filterable grid, usage-count detail-on-click, inline term editor, human-verified and approved — LEX-04 met

Vaadin 24 pattern is established for Phase 12 Governance Dashboard. All lexicon infrastructure is ready for Phase 6 (RAG embedding ingestion of domain terms).

## Self-Check: PASSED

- `src/main/java/com/esmp/ui/LexiconView.java` - FOUND
- `src/main/java/com/esmp/ui/TermEditorDialog.java` - FOUND
- `src/main/java/com/esmp/ui/TermDetailDialog.java` - FOUND
- `build.gradle.kts` updated with Vaadin plugin and dependency - FOUND
- `gradle/libs.versions.toml` updated with vaadin=24.9.12 - FOUND
- Commit 40d7100 - VERIFIED

---
*Phase: 05-domain-lexicon*
*Completed: 2026-03-05*
