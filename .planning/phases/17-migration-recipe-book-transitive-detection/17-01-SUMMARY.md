---
phase: 17-migration-recipe-book-transitive-detection
plan: 01
subsystem: migration-recipe-book
tags: [recipe-book, json-config, migration-rules, vaadin7, transitive-detection]
dependency_graph:
  requires: [phase-16-openrewrite-recipe-based-migration-engine]
  provides: [RecipeBookRegistry, vaadin-recipe-book-seed.json, MigrationPatternVisitor refactor, MigrationActionNode transitive fields]
  affects: [ExtractionService, MigrationPatternVisitor, MigrationActionNode]
tech_stack:
  added: [RecipeBookRegistry component, JSON recipe book (94 rules), ObjectMapper-based load/merge/write]
  patterns: [runtime-configurable recipe book, overlay merge by source FQN key, snapshot-based thread safety]
key_files:
  created:
    - src/main/java/com/esmp/migration/api/RecipeRule.java
    - src/main/java/com/esmp/migration/api/RecipeBook.java
    - src/main/java/com/esmp/migration/application/RecipeBookRegistry.java
    - src/main/resources/migration/vaadin-recipe-book-seed.json
    - src/test/java/com/esmp/migration/application/RecipeBookRegistryTest.java
    - src/test/java/com/esmp/migration/application/RecipeBookSeedTest.java
    - src/test/resources/migration/test-recipe-book.json
    - src/test/resources/migration/test-custom-overlay.json
  modified:
    - src/main/java/com/esmp/extraction/config/MigrationConfig.java
    - src/main/java/com/esmp/extraction/model/MigrationActionNode.java
    - src/main/java/com/esmp/extraction/visitor/MigrationPatternVisitor.java
    - src/main/java/com/esmp/extraction/application/ExtractionService.java
    - src/main/resources/application.yml
    - src/test/java/com/esmp/extraction/visitor/MigrationPatternVisitorTest.java
    - .gitignore
decisions:
  - RecipeBookRegistry uses snapshot-at-construction pattern for MigrationPatternVisitor thread safety — registry may reload while visitors run in parallel
  - Seed JSON isBase field omitted from JSON (set by registry at load time) to avoid redundant data in the file
  - JAVAX_JAKARTA rules use prefix matching (importFqn.startsWith(r.source())) for package-level coverage without enumerating all sub-packages
  - SRV-009 ExternalResource target set to java.lang.String (was null) to satisfy MAPPED/CHANGE_TYPE invariant
  - data/ runtime directory added to .gitignore — recipe book runtime path is a deployment concern, not source control
metrics:
  duration: 21min
  completed_date: "2026-03-28T17:53:38Z"
  tasks_completed: 2
  files_changed: 15
---

# Phase 17 Plan 01: Recipe Book Registry & Transitive Fields Summary

Externalized all hardcoded Vaadin 7 type maps from MigrationPatternVisitor into a loadable JSON recipe book with 94 rules, created the RecipeBookRegistry Spring component, refactored MigrationPatternVisitor to use registry lookup, injected the registry into ExtractionService, and extended MigrationActionNode with 6 transitive detection fields.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | RecipeBookRegistry, RecipeRule, RecipeBook, MigrationConfig extension, seed JSON (94 rules), MigrationActionNode transitive fields | 95bb381 | RecipeBookRegistry.java, vaadin-recipe-book-seed.json, MigrationActionNode.java, MigrationConfig.java |
| 2 | Refactor MigrationPatternVisitor, ExtractionService wiring, unit tests | 324271b | MigrationPatternVisitor.java, ExtractionService.java, RecipeBookRegistryTest.java, RecipeBookSeedTest.java |

## What Was Built

### RecipeBookRegistry (`com.esmp.migration.application`)
- `@Component` with `@PostConstruct public void load()` — copies seed from classpath if runtime file missing, loads base rules (isBase=true), applies overlay by source FQN key (isBase=false)
- `public List<RecipeRule> getRules()` — unmodifiable snapshot
- `public Optional<RecipeRule> findBySource(String)` — O(1) lookup
- `public synchronized void reload()` — re-reads from disk
- `public synchronized void updateAndWrite(List<RecipeRule>)` — persists to disk atomically

### RecipeRule Record (12 fields)
`id, category, source, target, actionType, automatable, context, migrationSteps, status, usageCount, discoveredAt, isBase`

### Seed JSON — 94 rules across 6 categories
- **COMP-001..051**: 51 component rules (TextField→Button→VerticalLayout→HorizontalLayout→FormLayout→CssLayout→Panel→NativeSelect→ListSelect→OptionGroup→Slider→RichTextArea→TwinColSelect→InlineDateField→PopupDateField→ColorPicker→Audio→Video→Embedded→Flash→BrowserFrame→NativeButton→AbsoluteLayout→GridLayout→SplitPanel→HorizontalSplitPanel→VerticalSplitPanel→Accordion→PopupView→DragAndDropWrapper→Calendar→Table→Grid→Window→TabSheet→Tree→TreeTable→CustomComponent→UI)
- **DATA-001..011**: 11 data binding rules (BeanItemContainer→BeanFieldGroup→FieldGroup→Property→Item→Container→Validator→Converter→IndexedContainer→HierarchicalContainer→SimpleStringFilter)
- **NAV-001..005**: 5 navigator rules (View→Navigator→ViewChangeListener→ViewDisplay→ViewProvider)
- **SRV-001..015**: 15 server rules (VaadinServlet→VaadinService→VaadinSession→VaadinRequest→VaadinResponse→Page→ClientConnector→Resource→ExternalResource→FileResource→ThemeResource→StreamResource→ErrorHandler→SessionDestroyListener→RequestHandler)
- **EVT-001..002**: 2 event rules (ItemClickEvent→LayoutEvents)
- **JXJK-001..010**: 10 javax→jakarta package rules (servlet→validation→persistence→annotation→inject→transaction→ws.rs→mail→xml.bind→ejb)

### MigrationConfig Extensions
- `recipeBookPath`: runtime file path (default: `data/migration/vaadin-recipe-book.json`, env override: `ESMP_MIGRATION_RECIPE_BOOK_PATH`)
- `customRecipeBookPath`: optional overlay path (env override: `ESMP_MIGRATION_CUSTOM_RECIPE_BOOK_PATH`)
- `TransitiveConfig`: 5 weight fields for Plan 02 (overrideWeight=0.3, ownCallsWeight=0.3, bindingWeight=0.2, componentWeight=0.2, aiAssistedThreshold=0.4)

### MigrationActionNode Transitive Fields (Plan 02 prep)
`isInherited` (boolean), `pureWrapper` (Boolean), `transitiveComplexity` (Double), `vaadinAncestor` (String), `overrideCount` (Integer), `ownVaadinCalls` (Integer)

### Refactored MigrationPatternVisitor
- Removed: `TYPE_MAP`, `PARTIAL_MAP`, `COMPLEX_TYPES`, `COMPLEX_TARGETS`, `JAVAX_PACKAGE_MAP`, `buildComplexContext()`
- Added: constructor taking `RecipeBookRegistry` snapshot at construction time for thread safety
- `processImport()` uses O(1) `rulesBySource.get()` for direct lookups and linear prefix scan over `javaxJakartaRules` list

### Unit Tests (21 total)
- `RecipeBookRegistryTest` (9 tests): load, findBySource, isBase flags, overlay merge/replace, overlay isBase=false, missing overlay, reload, updateAndWrite, classpath seed copy
- `RecipeBookSeedTest` (12 tests): count>=80, non-null id/category/source/actionType/automatable/status, unique IDs, valid enum values (categories/actionTypes/automatable/status), MAPPED type/package rules have target, spot-checks for TextField/Table/javax.servlet/javax.persistence

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Restored SimpleVaadinView fixture overwritten by Phase 16 applyAndWrite tests**
- **Found during:** Task 2 test run
- **Issue:** `src/test/resources/fixtures/migration/SimpleVaadinView.java` had Vaadin 24 imports on disk (was overwritten by Phase 16 `MigrationRecipeServiceIntegrationTest.applyAndWrite()`) causing `generatePlan_simpleView_returnsCorrectPlan` to find 0 migration actions
- **Fix:** Restored file to original Vaadin 7 imports via `git restore`
- **Files modified:** `src/test/resources/fixtures/migration/SimpleVaadinView.java`
- **Commit:** a5c56ac

**2. [Rule 1 - Bug] SRV-009 ExternalResource had null target with CHANGE_TYPE/MAPPED**
- **Found during:** Task 2 RecipeBookSeedTest failure
- **Issue:** `com.vaadin.server.ExternalResource` had `actionType=CHANGE_TYPE, status=MAPPED` but `target=null`, violating the seed integrity invariant
- **Fix:** Set target to `java.lang.String` (ExternalResource wraps a URL string)
- **Files modified:** `src/main/resources/migration/vaadin-recipe-book-seed.json`
- **Commit:** 324271b (bundled in Task 2 commit)

**3. [Rule 2 - Missing] Added data/ to .gitignore**
- **Found during:** Post-task git status check
- **Issue:** `data/` directory created by RecipeBookRegistry at startup was untracked
- **Fix:** Added `data/` to .gitignore as runtime artifact
- **Files modified:** `.gitignore`
- **Commit:** ef72890

## Self-Check: PASSED

### Files verified to exist:
- `src/main/java/com/esmp/migration/api/RecipeRule.java` — FOUND
- `src/main/java/com/esmp/migration/api/RecipeBook.java` — FOUND
- `src/main/java/com/esmp/migration/application/RecipeBookRegistry.java` — FOUND
- `src/main/resources/migration/vaadin-recipe-book-seed.json` — FOUND (94 rules)
- `src/test/java/com/esmp/migration/application/RecipeBookRegistryTest.java` — FOUND
- `src/test/java/com/esmp/migration/application/RecipeBookSeedTest.java` — FOUND

### Commits verified:
- 95bb381 — feat(17-01): RecipeBookRegistry, seed JSON (94 rules), transitive fields
- 324271b — feat(17-01): refactor MigrationPatternVisitor to use RecipeBookRegistry
- a5c56ac — fix(17-01): restore SimpleVaadinView fixture
- ef72890 — chore(17-01): add data/ to .gitignore
