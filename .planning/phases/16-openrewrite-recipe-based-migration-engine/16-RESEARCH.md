# Research: OpenRewrite Recipe-Based Migration Engine

> Research document for planning the implementation of automated Vaadin 7 → Vaadin 24 code transformation using OpenRewrite recipes, integrated with ESMP's knowledge graph.

---

## 1. Problem Statement

ESMP currently **analyzes** codebases and provides **context for AI-assisted migration**. The actual code transformation is done entirely by AI (via MCP `getMigrationContext`) or manually by developers. This works but has two gaps:

1. **Mechanical transforms are wasteful for AI.** Import swaps, type renames, and annotation additions are deterministic — AI shouldn't spend tokens on `com.vaadin.ui.TextField` → `com.vaadin.flow.component.textfield.TextField`. These are rules, not judgment calls.

2. **AI sometimes gets mechanical transforms wrong.** Wrong import paths, missed type renames, inconsistent package names. Deterministic recipes would be 100% correct every time.

**Proposed solution:** Add an OpenRewrite recipe engine that handles mechanical transforms automatically, leaving AI to focus on complex rewrites (data binding, layouts, business logic).

---

## 2. OpenRewrite Capabilities Relevant to ESMP

### 2.1 What OpenRewrite Can Do

OpenRewrite operates on Lossless Semantic Trees (LSTs) — the same AST representation ESMP already uses for extraction. Recipes can:

| Capability | OpenRewrite Recipe | Complexity |
|-----------|-------------------|------------|
| Rename a type (FQN swap) | `ChangeType` | Declarative YAML |
| Change an import | `ChangeType` (handles imports automatically) | Declarative YAML |
| Rename a method | `ChangeMethodName` | Declarative YAML |
| Change method target type | `ChangeMethodTargetToStatic` / `ChangeMethodTargetToVariable` | Declarative YAML |
| Add an annotation | Custom imperative recipe | Java code |
| Remove an annotation | `RemoveAnnotation` | Declarative YAML |
| Change a dependency (Gradle/Maven) | `ChangeDependency` | Declarative YAML |
| Replace expression A with B | Refaster template | Java code (medium) |
| Complex conditional transform | Custom `JavaIsoVisitor` | Java code (high) |
| Compose N recipes into one | Declarative recipe list | YAML |

### 2.2 Three Recipe Types

**Declarative (YAML):** Compose existing recipes. No custom code. Example:
```yaml
type: specs.openrewrite.org/v1beta/recipe
name: com.esmp.migration.VaadinTextFieldMigration
recipeList:
  - org.openrewrite.java.ChangeType:
      oldFullyQualifiedTypeName: com.vaadin.ui.TextField
      newFullyQualifiedTypeName: com.vaadin.flow.component.textfield.TextField
```

**Refaster Templates:** Expression-level replacement with compiler type checking. Example:
```java
@BeforeTemplate
String before(Button button, Button.ClickListener listener) {
    return button.addClickListener(listener);
}
@AfterTemplate
String after(Button button, ComponentEventListener<ClickEvent<Button>> listener) {
    return button.addClickListener(listener);
}
```

**Imperative (Java):** Full `JavaIsoVisitor` with arbitrary logic. Example: Adding `@Route` annotation based on Navigator registration patterns. This is what ESMP's existing visitors already are.

### 2.3 Recipe Execution Model

```
  Input: List<SourceFile> (LSTs) + Recipe
  Output: List<Result> (each with before/after SourceFile + diff)

  RecipeRun run = recipe.run(sourceFiles, executionContext);
  for (Result result : run.getChanges()) {
      String modifiedSource = result.getAfter().printAll();
      String diff = result.diff();  // git-style unified diff
  }
```

Key: recipes are **pure functions** — LST in, modified LST out. They don't touch the filesystem. The caller decides whether to write back.

---

## 3. Current ESMP Architecture (Relevant Components)

### 3.1 Extraction Pipeline Flow

```
  JavaSourceParser.parse(paths, root, classpath)
       │
       ▼ List<SourceFile> (OpenRewrite LSTs — already parsed)
       │
       ▼ ExtractionService.visitSequentially() or visitInParallel()
       │
       ├── ClassMetadataVisitor.visit(sf, acc)    → classes, methods, fields, annotations
       ├── CallGraphVisitor.visit(sf, acc)         → CALLS edges
       ├── VaadinPatternVisitor.visit(sf, acc)     → Vaadin 7 patterns, BINDS_TO
       ├── DependencyVisitor.visit(sf, acc)        → DEPENDS_ON edges
       ├── JpaPatternVisitor.visit(sf, acc)        → QUERIES, MAPS_TO_TABLE
       ├── LexiconVisitor.visit(sf, acc)           → business terms
       └── ComplexityVisitor.visit(sf, acc)        → cyclomatic complexity, DB writes
       │
       ▼ ExtractionAccumulator (in-memory buffer)
       │
       ▼ AccumulatorToModelMapper → ClassNode, MethodNode, FieldNode, etc.
       │
       ▼ Neo4j persistence (saveAll + batched UNWIND MERGE)
       │
       ▼ LinkingService.linkAllRelationships() → Cypher MERGE for edges
       │
       ▼ RiskService.computeAndPersistRiskScores()
```

### 3.2 ExtractionAccumulator — Current Structure

6 logical sections with 34 mutation methods:

**Node Data:**
- `Map<String, ClassNodeData> classes` — keyed by FQN
- `Map<String, MethodNodeData> methods` — keyed by methodId
- `Map<String, FieldNodeData> fields` — keyed by fieldId

**Edge Lists:**
- `List<CallEdge> callEdges`
- `List<ComponentEdge> componentEdges`
- `List<DependencyEdge> dependencyEdges`
- `List<QueryMethodRecord> queryMethods`
- `List<BindsToRecord> bindsToEdges`

**Vaadin Labels:**
- `Set<String> vaadinViews`
- `Set<String> vaadinComponents`
- `Set<String> vaadinDataBindings`

**Stereotypes:**
- `Set<String> serviceClasses`, `repositoryClasses`, `uiViewClasses`

**Analysis Data:**
- `Map<String, AnnotationData> annotations`
- `Map<String, String> tableMappings`
- `Map<String, BusinessTermData> businessTerms`
- `Map<String, MethodComplexityData> methodComplexities`
- `Map<String, ClassWriteData> classWriteData`

**Missing (needed for migration recipes):**
- No per-class catalog of specific Vaadin 7 API usages (which types, which methods)
- No migration action tracking
- No automation score

### 3.3 VaadinPatternVisitor — Current Detection

Already detects:
- **VaadinView:** classes implementing `com.vaadin.navigator.View` or extending `com.vaadin.ui.UI`
- **VaadinComponent:** classes instantiating Vaadin UI types via `new` expressions
- **VaadinDataBinding:** classes using `BeanFieldGroup`, `FieldGroup`, `BeanItemContainer`
- **BINDS_TO edges:** form-to-entity bindings
- **CONTAINS_COMPONENT edges:** parent-child component hierarchy from `addComponent()`

Known Vaadin 7 types in the visitor:
```java
VAADIN_VIEW_TYPES = {"com.vaadin.navigator.View", "com.vaadin.ui.UI"}
VAADIN_DATA_BINDING_TYPES = {"com.vaadin.data.fieldgroup.BeanFieldGroup",
    "com.vaadin.data.fieldgroup.FieldGroup",
    "com.vaadin.data.util.BeanItemContainer"}
VAADIN_UI_SIMPLE_NAMES = {"Button", "TextField", "VerticalLayout", "HorizontalLayout",
    "Grid", "Table", "Label", "ComboBox", "DateField", "Panel",
    "FormLayout", "Window", "TabSheet"}
```

**Limitation:** Detects Vaadin 7 presence (boolean flags per class) but doesn't catalog **which specific types are used where** — only that the class "has Vaadin components." This needs to be more granular for recipe generation.

### 3.4 ClassNode Neo4j Model — Current Properties

```java
// Identity
String fqn, simpleName, packageName, sourceFilePath, contentHash

// Structural
boolean isAbstract, isInterface, isEnum
String superClassName
List<String> implementedInterfaces

// Labels (dynamic via @DynamicLabels)
List<String> labels  // e.g., ["JavaClass", "Service", "VaadinView"]

// Risk (Phase 6-7)
double complexitySum, complexityMax, fanIn, fanOut
boolean hasDbWrites
int dbWriteCount
double structuralRiskScore, enhancedRiskScore
double domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity

// NOT currently present:
// - migrationActions
// - automationScore
// - vaadin7TypeUsages (specific types, not just boolean)
```

### 3.5 OpenRewrite Version and Dependencies

```toml
# gradle/libs.versions.toml
openrewrite = "8.74.3"
openrewrite-java = { module = "org.openrewrite:rewrite-java", version.ref = "openrewrite" }
openrewrite-java-jdk21 = { module = "org.openrewrite:rewrite-java-21", version.ref = "openrewrite" }
```

No recipe-specific dependencies yet (e.g., `rewrite-recipe-bom`, `rewrite-testing-frameworks`). These would need to be added.

### 3.6 MCP Tools — Current State

6 tools in `MigrationToolService`:
1. `getMigrationContext(classFqn)` — primary context assembly
2. `searchKnowledge(query, module, stereotype, topK)` — vector search
3. `getDependencyCone(classFqn, maxDepth)` — graph traversal
4. `getRiskAnalysis(classFqn, module, sortBy, limit)` — risk heatmap/detail
5. `browseDomainTerms(search, criticality)` — lexicon
6. `validateSystemHealth()` — 41 validation queries

**Missing:** No tools for migration planning, recipe generation, or recipe execution.

---

## 4. Vaadin 7 → Vaadin 24 Type Mapping Catalog

### 4.1 Mechanical Transforms (Recipe-Automatable)

These are deterministic 1:1 type swaps with no behavioral change:

**UI Components:**

| Vaadin 7 | Vaadin 24 | Recipe Type |
|----------|-----------|-------------|
| `com.vaadin.ui.TextField` | `com.vaadin.flow.component.textfield.TextField` | ChangeType |
| `com.vaadin.ui.TextArea` | `com.vaadin.flow.component.textfield.TextArea` | ChangeType |
| `com.vaadin.ui.PasswordField` | `com.vaadin.flow.component.textfield.PasswordField` | ChangeType |
| `com.vaadin.ui.Button` | `com.vaadin.flow.component.button.Button` | ChangeType |
| `com.vaadin.ui.Label` | `com.vaadin.flow.component.html.Span` | ChangeType |
| `com.vaadin.ui.CheckBox` | `com.vaadin.flow.component.checkbox.Checkbox` | ChangeType |
| `com.vaadin.ui.ComboBox` | `com.vaadin.flow.component.combobox.ComboBox` | ChangeType |
| `com.vaadin.ui.DateField` | `com.vaadin.flow.component.datepicker.DatePicker` | ChangeType |
| `com.vaadin.ui.Image` | `com.vaadin.flow.component.html.Image` | ChangeType |
| `com.vaadin.ui.Link` | `com.vaadin.flow.component.html.Anchor` | ChangeType |
| `com.vaadin.ui.MenuBar` | `com.vaadin.flow.component.menubar.MenuBar` | ChangeType |
| `com.vaadin.ui.ProgressBar` | `com.vaadin.flow.component.progressbar.ProgressBar` | ChangeType |
| `com.vaadin.ui.Upload` | `com.vaadin.flow.component.upload.Upload` | ChangeType |
| `com.vaadin.ui.Notification` | `com.vaadin.flow.component.notification.Notification` | ChangeType |

**Layouts:**

| Vaadin 7 | Vaadin 24 | Recipe Type |
|----------|-----------|-------------|
| `com.vaadin.ui.VerticalLayout` | `com.vaadin.flow.component.orderedlayout.VerticalLayout` | ChangeType |
| `com.vaadin.ui.HorizontalLayout` | `com.vaadin.flow.component.orderedlayout.HorizontalLayout` | ChangeType |
| `com.vaadin.ui.FormLayout` | `com.vaadin.flow.component.formlayout.FormLayout` | ChangeType |
| `com.vaadin.ui.CssLayout` | `com.vaadin.flow.component.html.Div` | ChangeType |
| `com.vaadin.ui.Panel` | `com.vaadin.flow.component.html.Div` | ChangeType (+ styling) |

**Annotations and Routing:**

| Vaadin 7 Pattern | Vaadin 24 Equivalent | Recipe Type |
|------------------|---------------------|-------------|
| `implements com.vaadin.navigator.View` | `@Route("path")` annotation | Imperative |
| `extends com.vaadin.ui.UI` | `@Route("") + AppLayout` | Imperative |
| `@Theme("mytheme")` | `@Theme("mytheme")` (same but different package) | ChangeType |
| `@Push` | `@Push` (same but different package) | ChangeType |

**Servlet/Jakarta:**

| Vaadin 7 Era | Modern | Recipe Type |
|-------------|--------|-------------|
| `javax.servlet.*` | `jakarta.servlet.*` | ChangePackage |
| `javax.validation.*` | `jakarta.validation.*` | ChangePackage |

### 4.2 Complex Transforms (Needs AI)

These require understanding context and intent — not mechanically translatable:

| Vaadin 7 Pattern | Vaadin 24 Equivalent | Why AI Needed |
|------------------|---------------------|---------------|
| `Table` with PropertyValueGenerators | `Grid<T>` with column renderers | Column definitions are completely different APIs. Each column's value provider, renderer, and editor must be individually rewritten based on what the column displays. |
| `BeanItemContainer<T>` | `DataProvider.ofCollection()` or `CallbackDataProvider` | Data loading, filtering, and sorting patterns are architecturally different. Lazy loading containers need CallbackDataProvider with fetch/count callbacks. |
| `BeanFieldGroup` / `FieldGroup` | `Binder<T>` | Validation binding, converters, and error handling are structural changes. Field-to-property mapping syntax completely changes. |
| `Navigator` routing logic | `@Route` + `BeforeEnterEvent` | Navigation parameters, view lifecycle callbacks, guards, and URL patterns all need contextual rewriting. |
| `CustomComponent` with `setCompositionRoot()` | `Composite<T>` with `getContent()` | The composition model changes. Layout building in `initComponent()` must move to constructor or `getContent()`. |
| `Window` (modal dialog) | `Dialog` | Event handling, sizing, positioning, close listeners all change. |
| `TabSheet` | `Tabs` + content routing | Tab selection handling and lazy content loading patterns differ fundamentally. |
| `Tree` / `TreeTable` | `TreeGrid<T>` | Hierarchical data providers are completely different. |
| `addComponent()` patterns | `add()` patterns | Mostly mechanical BUT insertion order and sizing behavior differences can be subtle. |
| `setSizeFull()` / `setWidth("100%")` | Similar but different defaults | Vaadin 24 has different default sizing. Needs context to know what to keep vs remove. |

### 4.3 Automation Score Estimation

Based on typical enterprise Vaadin 7 codebases:

| Class Category | Estimated Automation % | Count in Typical App |
|---------------|----------------------|---------------------|
| Pure service/repository (no Vaadin UI) | 95% (javax→jakarta only) | ~40% of classes |
| Simple forms (TextField, Button, basic layout) | 60-70% | ~20% of classes |
| Complex views (Table, data binding, navigation) | 20-30% | ~25% of classes |
| Custom components, extensions | 10-15% | ~15% of classes |
| **Weighted average** | **~55-65%** | — |

---

## 5. Proposed Architecture

### 5.1 Two-Phase Design

```
  PHASE A: Analysis (hooks into extraction)       PHASE B: Execution (separate service)
  ============================================    ==========================================
  MigrationPatternVisitor (8th visitor)           MigrationRecipeService
  - Runs during extraction                        - Triggered by user/AI via API/MCP
  - READ-ONLY — catalogs patterns                 - Generates recipes from graph data
  - Stores migration actions in accumulator       - Applies recipes to source files
  - Persisted to Neo4j on ClassNode               - Returns diffs and modified files
```

### 5.2 Phase A: MigrationPatternVisitor

**Purpose:** Catalog exactly which Vaadin 7 patterns each class uses and whether they can be automated.

**Integration point:** Added as 8th visitor in `ExtractionService.visitBatch()` and `visitSequentially()`.

**New data structures:**

```java
// In ExtractionAccumulator — new section:
private final Map<String, List<MigrationAction>> migrationActions = new HashMap<>();

public void addMigrationAction(String classFqn, MigrationAction action) {
    migrationActions.computeIfAbsent(classFqn, k -> new ArrayList<>()).add(action);
}

// New record type:
public record MigrationAction(
    ActionType type,      // CHANGE_TYPE, CHANGE_IMPORT, ADD_ANNOTATION, REMOVE_IMPLEMENTS,
                          // CHANGE_METHOD, COMPLEX_REWRITE
    String source,        // Current pattern (e.g., "com.vaadin.ui.Table")
    String target,        // Target pattern (e.g., "com.vaadin.flow.component.grid.Grid")
    Automatable auto,     // YES, PARTIAL, NO
    String context        // Additional info (e.g., "3 columns with PropertyValueGenerator")
) {}

public enum ActionType {
    CHANGE_TYPE, CHANGE_IMPORT, ADD_ANNOTATION, REMOVE_ANNOTATION,
    REMOVE_IMPLEMENTS, CHANGE_METHOD, CHANGE_PACKAGE, COMPLEX_REWRITE
}

public enum Automatable { YES, PARTIAL, NO }
```

**Visitor implementation outline:**

```java
public class MigrationPatternVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

    // Master type mapping: Vaadin 7 FQN → Vaadin 24 FQN
    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
        entry("com.vaadin.ui.TextField", "com.vaadin.flow.component.textfield.TextField"),
        entry("com.vaadin.ui.Button", "com.vaadin.flow.component.button.Button"),
        // ... 30+ entries from section 4.1
    );

    // Types that need AI — can be detected but not auto-transformed
    private static final Set<String> COMPLEX_TYPES = Set.of(
        "com.vaadin.ui.Table",
        "com.vaadin.data.util.BeanItemContainer",
        "com.vaadin.data.fieldgroup.BeanFieldGroup",
        "com.vaadin.ui.Window",
        "com.vaadin.ui.TabSheet",
        "com.vaadin.ui.Tree",
        "com.vaadin.ui.TreeTable"
    );

    private String currentClassFqn;

    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExtractionAccumulator acc) {
        if (cd.getType() != null) {
            currentClassFqn = cd.getType().getFullyQualifiedName();

            // Check extends: com.vaadin.ui.CustomComponent → Composite
            // Check implements: com.vaadin.navigator.View → @Route
            // ...
        }
        return super.visitClassDeclaration(cd, acc);
    }

    @Override
    public J.Import visitImport(J.Import _import, ExtractionAccumulator acc) {
        String fqn = _import.getTypeName();
        if (TYPE_MAP.containsKey(fqn)) {
            acc.addMigrationAction(currentClassFqn, new MigrationAction(
                ActionType.CHANGE_TYPE, fqn, TYPE_MAP.get(fqn), Automatable.YES, null));
        } else if (COMPLEX_TYPES.contains(fqn)) {
            acc.addMigrationAction(currentClassFqn, new MigrationAction(
                ActionType.COMPLEX_REWRITE, fqn, describeTarget(fqn), Automatable.NO,
                describeComplexity(fqn)));
        }
        return super.visitImport(_import, acc);
    }

    @Override
    public J.NewClass visitNewClass(J.NewClass nc, ExtractionAccumulator acc) {
        // Detect new Table(), new BeanItemContainer<>(), etc.
        // Catalog with column count, binding type, etc. for context
        return super.visitNewClass(nc, acc);
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExtractionAccumulator acc) {
        // Detect navigator.navigateTo(), addComponent(), setSizeFull(), etc.
        return super.visitMethodInvocation(mi, acc);
    }
}
```

**Persistence:** Migration actions stored as JSON property on ClassNode or as separate `MigrationAction` nodes linked via `HAS_MIGRATION_ACTION` edges.

**Computed properties on ClassNode (new):**
- `int migrationActionCount` — total actions detected
- `int automatableActionCount` — actions where auto=YES
- `double automationScore` — automatableActionCount / migrationActionCount
- `boolean needsAiMigration` — has any auto=NO actions

### 5.3 Phase B: MigrationRecipeService

**Purpose:** Generate and execute OpenRewrite recipes based on graph data.

```java
@Service
public class MigrationRecipeService {

    private final Neo4jClient neo4jClient;
    private final JavaSourceParser parser;

    /**
     * Generate a migration plan for a class showing what's automatable vs manual.
     */
    public MigrationPlan generatePlan(String classFqn) {
        List<MigrationAction> actions = loadActionsFromGraph(classFqn);

        List<MigrationAction> automated = actions.stream()
            .filter(a -> a.auto() == Automatable.YES).toList();
        List<MigrationAction> manual = actions.stream()
            .filter(a -> a.auto() != Automatable.YES).toList();

        return new MigrationPlan(classFqn, automated, manual,
            automated.size(), manual.size(),
            (double) automated.size() / actions.size());
    }

    /**
     * Apply automated recipes to a source file.
     * Returns the diff and modified source without writing to disk.
     */
    public MigrationResult applyRecipes(String classFqn, Path sourceFile, Path projectRoot,
                                         String classpathFile) {
        MigrationPlan plan = generatePlan(classFqn);

        // Parse the source file into LST
        List<SourceFile> lsts = parser.parse(List.of(sourceFile), projectRoot, classpathFile);

        // Build composite recipe from automatable actions
        Recipe composite = buildCompositeRecipe(plan.automated());

        // Execute — pure transformation, no side effects
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(t -> log.warn(...));
        RecipeRun run = composite.run(new LargeSourceSet(lsts), ctx);

        // Collect results
        List<Result> changes = run.getChanges();
        if (changes.isEmpty()) {
            return MigrationResult.noChanges(classFqn);
        }

        Result result = changes.get(0);
        return new MigrationResult(
            classFqn,
            result.diff(),                          // unified diff
            result.getAfter().printAll(),            // full modified source
            plan.automated().size(),                 // automated count
            plan.manual(),                           // remaining manual items
            plan.automationScore()
        );
    }

    /**
     * Apply recipes and write back to the source file.
     */
    public MigrationResult applyAndWrite(String classFqn, Path sourceFile, ...) {
        MigrationResult result = applyRecipes(classFqn, sourceFile, ...);
        if (result.hasChanges()) {
            Files.writeString(sourceFile, result.modifiedSource());
        }
        return result;
    }

    private Recipe buildCompositeRecipe(List<MigrationAction> actions) {
        List<Recipe> recipes = new ArrayList<>();
        for (MigrationAction action : actions) {
            switch (action.type()) {
                case CHANGE_TYPE -> recipes.add(
                    new ChangeType(action.source(), action.target(), false));
                case CHANGE_PACKAGE -> recipes.add(
                    new ChangePackage(action.source(), action.target(), false));
                case ADD_ANNOTATION -> recipes.add(
                    new AddAnnotation(action.target(), action.context()));
                case REMOVE_IMPLEMENTS -> recipes.add(
                    new RemoveImplements(action.source()));
                // ... other action types
            }
        }
        // Compose into a single recipe
        return new CompositeRecipe(recipes);
    }
}
```

### 5.4 New API Endpoints

```java
@RestController
@RequestMapping("/api/migration")
public class MigrationController {

    // Get migration plan for a class
    @GetMapping("/plan/{fqn}")
    public MigrationPlan getPlan(@PathVariable String fqn);

    // Get migration summary for a module
    @GetMapping("/summary")
    public ModuleMigrationSummary getSummary(@RequestParam String module);

    // Preview recipe application (returns diff, doesn't write)
    @PostMapping("/preview/{fqn}")
    public MigrationResult preview(@PathVariable String fqn, @RequestBody MigrationRequest req);

    // Apply recipes to source file
    @PostMapping("/apply/{fqn}")
    public MigrationResult apply(@PathVariable String fqn, @RequestBody MigrationRequest req);

    // Batch apply recipes to all automatable classes in a module
    @PostMapping("/apply-module")
    public BatchMigrationResult applyModule(@RequestBody ModuleMigrationRequest req);
}
```

### 5.5 New MCP Tools

```java
// Tool 7: Get migration plan
@Tool(description = "Get migration plan for a class showing which transforms are "
    + "automatable by recipe and which need AI. Returns automation score and action list.")
public MigrationPlan getMigrationPlan(String classFqn);

// Tool 8: Apply automated recipes (preview mode)
@Tool(description = "Apply OpenRewrite recipes to automate mechanical transforms for a class. "
    + "Returns diff preview. Does not modify files unless confirmed.")
public MigrationResult applyMigrationRecipes(String classFqn, String sourceRoot);

// Tool 9: Get module migration summary
@Tool(description = "Get migration automation summary for a module — how many classes are "
    + "fully automatable, partially automatable, and need AI.")
public ModuleMigrationSummary getModuleMigrationSummary(String module);
```

### 5.6 Dashboard View

New **Migration View** (`/migration`) showing:

- Per-module automation scores (bar chart: green=automatable, amber=partial, red=needs AI)
- Per-class migration action breakdown (table: class, automation%, action count, status)
- "Apply All Recipes" button per module
- Diff viewer for previewing recipe changes

---

## 6. Dependencies to Add

```toml
# gradle/libs.versions.toml additions
[libraries]
openrewrite-recipe-bom     = { module = "org.openrewrite.recipe:rewrite-recipe-bom", version = "3.6.1" }
openrewrite-testing        = { module = "org.openrewrite:rewrite-test", version.ref = "openrewrite" }
```

```kotlin
// build.gradle.kts additions
implementation(libs.openrewrite.recipe.bom)
testImplementation(libs.openrewrite.testing)
```

The `rewrite-recipe-bom` provides access to built-in recipes like `ChangeType`, `ChangePackage`, `ChangeMethodName`, etc. The `rewrite-test` provides `RewriteTest` interface for testing custom recipes.

---

## 7. Data Flow — Complete Picture

```
  EXTRACTION (enhanced)
  =====================
  Parse .java → LST
       │
       ├── 7 existing visitors → analysis data
       └── MigrationPatternVisitor (8th) → migration actions
       │
       ▼
  ExtractionAccumulator
       │ (now includes migrationActions map)
       ▼
  AccumulatorToModelMapper
       │ (maps actions → ClassNode properties)
       ▼
  Neo4j ClassNode
       │ (new: migrationActionCount, automatableActionCount, automationScore, needsAiMigration)
       │ (new: MigrationAction nodes or JSON property)
       ▼
  ┌─────────────────────────────────────────────────────┐
  │                  CONSUMPTION LAYER                    │
  │                                                       │
  │  MCP Tools:                                          │
  │  ├── getMigrationPlan(fqn)     → plan + actions      │
  │  ├── applyMigrationRecipes(fqn)→ diff + modified src │
  │  └── getModuleMigrationSummary → module automation % │
  │                                                       │
  │  REST API:                                           │
  │  ├── GET /api/migration/plan/{fqn}                   │
  │  ├── POST /api/migration/preview/{fqn}               │
  │  ├── POST /api/migration/apply/{fqn}                 │
  │  └── POST /api/migration/apply-module                │
  │                                                       │
  │  Dashboard:                                          │
  │  └── /migration view with automation scores + diffs   │
  │                                                       │
  │  Combined Workflow:                                  │
  │  1. getMigrationPlan → "60% automatable"             │
  │  2. applyMigrationRecipes → mechanical transforms    │
  │  3. getMigrationContext → AI context for remaining    │
  │  4. AI completes complex transforms                  │
  │  5. Re-index + validate                              │
  └─────────────────────────────────────────────────────┘
```

---

## 8. Implementation Estimates

### Plan Breakdown

| Plan | Scope | Estimated Tasks |
|------|-------|----------------|
| **Plan 1:** MigrationPatternVisitor + accumulator + Neo4j persistence | 8th visitor, new data structures, ClassNode properties, merge() update | 2 tasks |
| **Plan 2:** MigrationRecipeService + recipe generation + execution | Recipe builder, composite recipe, diff generation, file write | 2 tasks |
| **Plan 3:** REST API + MCP tools + integration tests | 3 endpoints, 3 MCP tools, Testcontainers tests | 2 tasks |
| **Plan 4:** Dashboard migration view | Vaadin 24 view with automation scores, diff viewer | 1-2 tasks |

### Key Risks

1. **OpenRewrite recipe API stability.** We use 8.74.3 for parsing. The recipe execution API (`RecipeRun`, `Result`, `LargeSourceSet`) may have version-specific behavior. Need to verify API compatibility.

2. **Type resolution for recipe execution.** Recipes need the same classpath as parsing to resolve types correctly. The existing `ClasspathLoader` already handles this for extraction — reuse it for recipe execution.

3. **Parallel extraction compatibility.** The MigrationPatternVisitor must be stateless per batch (like existing visitors). The `currentClassFqn` tracking needs to be handled via the visitor's cursor, not a class-level field, to be thread-safe.

4. **Recipe composability.** Applying 15 `ChangeType` recipes to a single file should produce one clean result, not 15 separate passes. OpenRewrite's `CompositeRecipe` handles this but should be verified with real Vaadin code.

5. **Vaadin 7 type catalog completeness.** The type mapping in section 4.1 covers common types but enterprise codebases may use less common Vaadin 7 APIs (e.g., `AbstractSelect`, `OptionGroup`, `TwinColSelect`, `Calendar`). The mapping needs to be extensible.

---

## 9. Open Questions for Planning

1. **Should migration actions be stored as ClassNode properties (JSON) or as separate Neo4j nodes with relationships?** Properties are simpler; separate nodes allow Cypher querying per action type.

2. **Should recipe execution write to the original source files or to a staging directory?** Writing in-place is simpler but risky. A staging directory with diff review is safer.

3. **Should we support batch recipe execution across an entire module in one API call?** Useful for the "Apply All Recipes to Wave 1" workflow but increases blast radius.

4. **Should the MigrationPatternVisitor extend VaadinPatternVisitor or be separate?** Extending would avoid duplicate traversal of Vaadin patterns. Separate keeps concerns clean.

5. **Should we ship a default `rewrite.yml` with ESMP that users can customize?** This would let users add their own project-specific type mappings beyond the default Vaadin 7→24 catalog.

6. **Priority: Should this be a single milestone phase or split across multiple phases?** Plan 1 (analysis) delivers value independently — you can see automation scores even before recipe execution is built.

---

## 10. References

- OpenRewrite documentation: https://docs.openrewrite.org/
- OpenRewrite recipe authoring: https://docs.openrewrite.org/authoring-recipes/writing-a-java-refactoring-recipe
- OpenRewrite visitor pattern: https://docs.openrewrite.org/concepts-and-explanations/visitors
- OpenRewrite recipe types: https://docs.openrewrite.org/authoring-recipes/types-of-recipes
- Vaadin 7 → 24 migration guide: https://vaadin.com/docs/latest/upgrading
- ESMP extraction pipeline: `src/main/java/com/esmp/extraction/application/ExtractionService.java`
- ESMP VaadinPatternVisitor: `src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java`
- ESMP ExtractionAccumulator: `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java`
- ESMP MCP tools: `src/main/java/com/esmp/mcp/tool/MigrationToolService.java`
