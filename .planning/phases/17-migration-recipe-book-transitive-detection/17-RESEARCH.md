# Phase 17: Migration Recipe Book & Transitive Detection - Research

**Researched:** 2026-03-28
**Domain:** Java JSON configuration loading, Neo4j Cypher graph traversal, Spring Boot service extension
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Recipe book format & storage**
- External JSON file at runtime path `data/migration/vaadin-recipe-book.json` (configurable via `esmp.migration.recipe-book-path`)
- Classpath seed file `src/main/resources/migration/vaadin-recipe-book-seed.json` — copied to runtime path on first startup if no file exists
- Custom overlay file merged on top (configurable via `esmp.migration.custom-recipe-book-path`)
- Flat JSON structure with `rules[]` array — each rule has: id, category, source, target, actionType, automatable, context, migrationSteps[], status, usageCount, discoveredAt
- Categories: COMPONENT, DATA_BINDING, SERVER, JAVAX_JAKARTA, DISCOVERED (for auto-added unmapped types)
- Load once at startup (RecipeBookRegistry @PostConstruct) — no hot-reload, manual reload via API endpoint
- Custom overlay merge by `source` FQN key — custom wins entirely (full replacement), new sources are additive
- MigrationPatternVisitor refactored to read from RecipeBookRegistry instead of hardcoded static maps

**Transitive detection strategy**
- Post-linking Cypher traversal in ExtractionService pipeline — runs after linkAllRelationships() creates EXTENDS edges
- Cypher walks `(c:JavaClass)-[:EXTENDS*1..10]->(ancestor)` to find classes transitively inheriting from Vaadin 7 types
- Creates inherited MigrationAction nodes with `isInherited=true`, linked via HAS_MIGRATION_ACTION
- actionId for inherited actions: `classFqn + '#INHERITED#' + source` — deterministic MERGE, no duplicates

**Transitive complexity profiling (per-class)**
- Computed from existing graph data only — no new visitor needed
- Signals: overrideCount (methods matching ancestor method names), ownVaadinCalls (CALLS to com.vaadin.*), hasOwnBinding (VaadinDataBinding label), hasOwnComponents (VaadinComponent label)
- Weighted score: `transitiveComplexity = overrideCount*0.3 + ownVaadinCalls*0.3 + hasOwnBinding*0.2 + hasOwnComponents*0.2` (clamped to 1.0)
- Classification: score=0 → PURE_WRAPPER (automatable=PARTIAL), score≤threshold → AI_ASSISTED (automatable=PARTIAL), score>threshold → COMPLEX (automatable=NO)
- Weights and threshold configurable via `@ConfigurationProperties(prefix="esmp.migration.transitive")` — default ai-assisted-threshold=0.4
- MigrationAction stores: pureWrapper, transitiveComplexity, vaadinAncestor, overrideCount, ownVaadinCalls

**Extraction-driven enrichment**
- Unified `migrationPostProcessing()` method in MigrationRecipeService, called from ExtractionService after linkAllRelationships() and computeRiskScores()
- Three sub-steps: detectTransitiveMigrations() → recomputeMigrationScores() → enrichRecipeBook()
- enrichRecipeBook() aggregates MigrationAction nodes by source FQN, updates usageCount per rule, auto-adds NEEDS_MAPPING entries for unmapped com.vaadin.* types, writes updated recipe book to file
- NEEDS_MAPPING auto-additions: category=DISCOVERED, automatable=NO, actionType=COMPLEX_REWRITE, target=null, discoveredAt=date

**REST API — recipe book management**
- `GET /api/migration/recipe-book` — all rules with usageCount and status, filterable by category/status/automatable
- `GET /api/migration/recipe-book/gaps` — NEEDS_MAPPING rules only, sorted by usageCount descending
- `PUT /api/migration/recipe-book/rules/{id}` — add or update a custom rule (writes to file)
- `DELETE /api/migration/recipe-book/rules/{id}` — remove custom/discovered rule (base rules protected)
- `POST /api/migration/recipe-book/reload` — re-read file + re-merge custom overlay

**MCP tools — updates and additions**
- `getMigrationPlan(classFqn)` updated: each MigrationActionEntry gains isInherited, inheritedFrom, vaadinAncestor, pureWrapper, transitiveComplexity, overrideCount, ownVaadinCalls, migrationSteps[]
- `getModuleMigrationSummary(module)` updated: adds transitiveClassCount, coverageByType, coverageByUsage, topGaps
- New tool: `getRecipeBookGaps()` — returns NEEDS_MAPPING rules sorted by usageCount, so Claude discovers what to research next

**Coverage scoring**
- Two coverage metrics returned: coverageByType (unique mapped types / total unique types) and coverageByUsage (mapped type usages / total usages)
- Both included in getModuleMigrationSummary response for full context

### Claude's Discretion
- Recipe book seed JSON exact content (consolidating existing 32 mappings + expanding to 80+ for RB-02)
- MigrationAction node schema extensions for transitive fields
- Exact Cypher queries for transitive detection and complexity profiling
- RecipeBookRegistry internal implementation (loading, merging, write-back)
- Validation query design for recipe book specific queries
- Error handling for file I/O during enrichment
- RecipeBookController implementation details

### Deferred Ideas (OUT OF SCOPE)
- Dashboard migration view with recipe book stats, gap visualization, coverage heatmap — could be Phase 18
- AI orchestration engine chaining recipe execution + RAG context + Claude migration — v2 requirement (ORCH-01)
- Recipe book versioning / changelog tracking — not needed for v1
- Automatic migration step generation from OpenRewrite recipe metadata — future enhancement
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| RB-01 | Migration rules stored in external JSON recipe book loaded at startup, supports base + custom overlay | JSON file loading pattern, @PostConstruct, file copy from classpath to runtime path |
| RB-02 | Comprehensive initial recipe book: 80+ rules covering all known Vaadin 7→24 and javax→jakarta | Existing 32 entries in MigrationPatternVisitor maps + expanded Vaadin API knowledge |
| RB-03 | Extraction-driven enrichment: usageCount per rule, NEEDS_MAPPING auto-discovery after extraction | Neo4j aggregation Cypher, Jackson write-back, three-step migrationPostProcessing() pipeline |
| RB-04 | Transitive detection via EXTENDS graph traversal: inherited types classified by complexity | EXTENDS*1..10 Cypher (already exists in GraphQueryService), MigrationActionNode schema extension |
| RB-05 | REST API for recipe book management + updated MCP tools surfacing transitive actions and coverage | Spring MVC REST patterns established in project, @Tool extension pattern in MigrationToolService |
| RB-06 | AI-optimized getMigrationPlan output with enrichment context (usageCount, pureWrapper, vaadinAncestor, migrationSteps) | MigrationActionEntry record extension, Neo4j query update in loadActionsFromGraph() |
</phase_requirements>

## Summary

Phase 17 builds directly on Phase 16's migration engine foundation. The work has three distinct concerns: (1) externalizing the hardcoded type maps from `MigrationPatternVisitor` into a loadable JSON recipe book with runtime management; (2) detecting classes that inherit Vaadin 7 types transitively via EXTENDS graph traversal and profiling their complexity; and (3) enriching the recipe book with per-rule usage statistics from each extraction run.

All infrastructure needed exists. The EXTENDS traversal pattern is already proven in `GraphQueryService.findInheritanceChain()` using `[:EXTENDS*1..10]`. The `MigrationActionNode` just needs new fields added. The `MigrationRecipeService` is the natural home for the new `migrationPostProcessing()` orchestrator. The `ExtractionService` pipeline already has the correct insertion point: after `riskService.computeAndPersistRiskScores()`. The `IncrementalIndexingService` has the same structure — step 6 runs risk, and step 7 is vector re-embed; `migrationPostProcessing()` inserts between them.

The seed recipe book consolidates existing mappings (18 TYPE_MAP + 1 PARTIAL_MAP + 11 COMPLEX_TYPES + 2 JAVAX entries = 32) and must be expanded to 80+ entries covering Vaadin 7 types not yet mapped. The Jackson `ObjectMapper` is already on the classpath via Spring Boot — no new dependencies needed.

**Primary recommendation:** Implement in three plans — (1) RecipeBookRegistry + seed JSON + MigrationPatternVisitor refactor, (2) transitive detection + MigrationActionNode schema + ExtractionService pipeline hook, (3) enrichment + REST API + MCP tool updates.

## Standard Stack

### Core (all already on classpath — no new dependencies needed)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Jackson `ObjectMapper` | 2.17.x (Spring Boot managed) | JSON read/write for recipe book | Already in Spring Boot starter; `@PostConstruct` file loading is trivial |
| Spring Data Neo4j `Neo4jClient` | Inherited from project | Cypher for transitive traversal, aggregation, MERGE | Established project pattern for all complex Cypher queries |
| Spring `@ConfigurationProperties` | Spring Boot 3.5.11 | `esmp.migration.transitive.*` weights/threshold | Consistent with RiskWeightConfig, SchedulingWeightConfig patterns |
| Spring `Resource` abstraction | Spring Boot 3.5.11 | Load seed file from classpath path | `ClassPathResource` for seed, `FileSystemResource` for runtime write |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `java.nio.file.Files` | JDK 21 | Copy seed to runtime path, write enriched book | Seed-copy on first startup, write-back after enrichment |
| `@PostConstruct` | Jakarta EE / Spring | RecipeBookRegistry initialization | Load once at startup, merge overlay |

**Installation:** No new dependencies. All required libraries are already present.

**Version verification:** Not applicable — no new dependencies.

## Architecture Patterns

### Recommended Project Structure

```
src/main/java/com/esmp/migration/
├── application/
│   ├── MigrationRecipeService.java     (extend with migrationPostProcessing())
│   └── RecipeBookRegistry.java         (NEW — @Component, @PostConstruct, load/merge/write)
├── api/
│   ├── MigrationActionEntry.java       (extend record — add transitive fields + migrationSteps)
│   ├── ModuleMigrationSummary.java     (extend record — add transitiveClassCount, coverage fields)
│   ├── RecipeBookController.java       (NEW — 5 recipe book management endpoints)
│   └── RecipeRule.java                 (NEW — immutable record matching JSON rule shape)
├── validation/
│   └── MigrationValidationQueryRegistry.java  (extend with 3 new recipe book queries)
└── ...

src/main/resources/migration/
└── vaadin-recipe-book-seed.json        (NEW — 80+ rules seeded from existing maps)

src/main/java/com/esmp/extraction/
├── config/MigrationConfig.java         (extend with recipe-book-path, custom-recipe-book-path, transitive.*)
├── model/MigrationActionNode.java      (add 6 transitive fields)
└── visitor/MigrationPatternVisitor.java (inject RecipeBookRegistry, read from it)

src/main/java/com/esmp/mcp/tool/
└── MigrationToolService.java           (update getMigrationPlan, getModuleMigrationSummary, add getRecipeBookGaps)
```

### Pattern 1: RecipeBookRegistry — @PostConstruct load with classpath seed fallback

**What:** `@Component` singleton that loads the recipe book from the runtime path on startup. If the runtime file does not exist, copies the classpath seed. Then merges the custom overlay (if configured).

**When to use:** Single place to read rules. All other beans inject this registry.

```java
// Source: established project pattern (see SchedulingWeightConfig, RiskWeightConfig)
@Component
public class RecipeBookRegistry {

  private final MigrationConfig config;
  private final ObjectMapper objectMapper;
  private List<RecipeRule> rules = new ArrayList<>();

  @PostConstruct
  public void load() {
    Path runtimePath = Path.of(config.getRecipeBookPath());
    if (!Files.exists(runtimePath)) {
      // Seed from classpath — copy once
      Files.createDirectories(runtimePath.getParent());
      try (InputStream seed = getClass().getResourceAsStream("/migration/vaadin-recipe-book-seed.json")) {
        Files.copy(seed, runtimePath);
      }
    }
    // Load base rules
    RecipeBook book = objectMapper.readValue(runtimePath.toFile(), RecipeBook.class);
    Map<String, RecipeRule> merged = new LinkedHashMap<>();
    book.rules().forEach(r -> merged.put(r.source(), r));

    // Merge custom overlay (custom wins by source FQN key)
    String customPath = config.getCustomRecipeBookPath();
    if (customPath != null && !customPath.isBlank() && Files.exists(Path.of(customPath))) {
      RecipeBook custom = objectMapper.readValue(Path.of(customPath).toFile(), RecipeBook.class);
      custom.rules().forEach(r -> merged.put(r.source(), r));  // full replacement
    }
    this.rules = new ArrayList<>(merged.values());
  }

  public void reload() {
    load();  // re-reads file — called by POST /api/migration/recipe-book/reload
  }

  public List<RecipeRule> getRules() { return Collections.unmodifiableList(rules); }

  // Writes updated list (from enrichment write-back) to runtime path atomically
  public synchronized void updateAndWrite(List<RecipeRule> updated) {
    this.rules = new ArrayList<>(updated);
    objectMapper.writerWithDefaultPrettyPrinter()
        .writeValue(Path.of(config.getRecipeBookPath()).toFile(), new RecipeBook(updated));
  }
}
```

### Pattern 2: RecipeRule JSON shape

**What:** Immutable record that maps 1:1 to each rule object in the JSON.

```java
// RecipeRule record — must match seed JSON structure exactly
public record RecipeRule(
    String id,              // stable identifier e.g. "COMP-001"
    String category,        // COMPONENT, DATA_BINDING, SERVER, JAVAX_JAKARTA, DISCOVERED
    String source,          // Vaadin 7 FQN — key for overlay merge
    String target,          // Vaadin 24 FQN, or null for NEEDS_MAPPING
    String actionType,      // CHANGE_TYPE, CHANGE_PACKAGE, COMPLEX_REWRITE
    String automatable,     // YES, PARTIAL, NO
    String context,         // human-readable explanation (may be null)
    List<String> migrationSteps,  // AI-actionable step list (may be empty)
    String status,          // MAPPED, NEEDS_MAPPING
    int usageCount,         // updated by enrichment — how many classes use this type
    String discoveredAt     // ISO date, populated for DISCOVERED category entries
) {}
```

### Pattern 3: MigrationPatternVisitor refactored to use RecipeBookRegistry

**What:** Replace static maps with registry lookup. The visitor is constructed per-extraction run; it must receive the registry at construction time.

**Key insight:** `MigrationPatternVisitor` is instantiated in `ExtractionService.visitSequentially()` and per-partition in `visitInParallel()`. It is NOT a Spring bean — it is constructed directly. The registry must be passed as a constructor argument.

```java
// ExtractionService — inject RecipeBookRegistry
private final RecipeBookRegistry recipeBookRegistry;

// In visitSequentially():
MigrationPatternVisitor migrationPatternVisitor = new MigrationPatternVisitor(recipeBookRegistry);

// MigrationPatternVisitor constructor
public MigrationPatternVisitor(RecipeBookRegistry registry) {
    // Build lookup maps from registry rules at construction time (snapshot for thread safety)
    Map<String, RecipeRule> bySource = registry.getRules().stream()
        .collect(Collectors.toMap(RecipeRule::source, r -> r, (a, b) -> a));
    this.rulesBySource = Collections.unmodifiableMap(bySource);
}

private void processImport(String importFqn, String classFqn, ExtractionAccumulator acc) {
    RecipeRule rule = rulesBySource.get(importFqn);
    if (rule != null && !"NEEDS_MAPPING".equals(rule.status())) {
        acc.addMigrationAction(classFqn, new MigrationActionData(
            ActionType.valueOf(rule.actionType()),
            importFqn, rule.target(),
            Automatable.valueOf(rule.automatable()),
            rule.context()
        ));
        return;
    }
    // Unknown com.vaadin.* (not flow) → still add as COMPLEX_REWRITE with NO
    if (importFqn.startsWith("com.vaadin.") && !importFqn.startsWith("com.vaadin.flow.")) {
        acc.addMigrationAction(...);
    }
}
```

### Pattern 4: Transitive detection Cypher

**What:** Post-linking Cypher that finds classes inheriting from known Vaadin 7 types and creates inherited MigrationAction nodes.

**Key insight from existing code:** `GraphQueryService` already has `OPTIONAL MATCH path = (c)-[:EXTENDS*1..10]->(ancestor:JavaClass)` at line 164. The transitive detection uses the same pattern but inverts it — for each known Vaadin 7 source FQN, find all descendants. Alternatively (and more efficiently), find all classes with EXTENDS ancestors whose FQN matches the known set.

```cypher
// Phase 1: Find transitively inheriting classes
MATCH (c:JavaClass)-[:EXTENDS*1..10]->(ancestor:JavaClass)
WHERE ancestor.fullyQualifiedName IN $vaadinSourceFqns
AND NOT (c)-[:HAS_MIGRATION_ACTION]->(:MigrationAction {source: ancestor.fullyQualifiedName})
RETURN c.fullyQualifiedName AS classFqn,
       ancestor.fullyQualifiedName AS ancestorFqn,
       c.simpleName AS simpleName,
       labels(c) AS classLabels

// Phase 2: Complexity profiling for each transitive class
MATCH (c:JavaClass {fullyQualifiedName: $classFqn})
MATCH (ancestor:JavaClass {fullyQualifiedName: $ancestorFqn})
OPTIONAL MATCH (ancestor)-[:DECLARES_METHOD]->(am:JavaMethod)
OPTIONAL MATCH (c)-[:DECLARES_METHOD]->(cm:JavaMethod)
  WHERE cm.simpleName IN collect(DISTINCT am.simpleName)
WITH c, ancestor, count(cm) AS overrideCount
OPTIONAL MATCH (c)-[:CALLS]->(callee:JavaClass)
  WHERE callee.fullyQualifiedName STARTS WITH 'com.vaadin.'
  AND NOT callee.fullyQualifiedName STARTS WITH 'com.vaadin.flow.'
WITH c, ancestor, overrideCount, count(DISTINCT callee) AS ownVaadinCalls
RETURN overrideCount, ownVaadinCalls,
       'VaadinDataBinding' IN labels(c) AS hasOwnBinding,
       'VaadinComponent' IN labels(c) AS hasOwnComponents
```

### Pattern 5: MigrationActionNode schema extension

**What:** Add 6 new fields to `MigrationActionNode` for transitive information. All fields are optional (null for non-transitive actions).

```java
// New fields on MigrationActionNode
private boolean isInherited;           // true for transitively-detected actions
private Boolean pureWrapper;           // true = score==0, null for direct actions
private Double transitiveComplexity;   // 0.0..1.0, null for direct actions
private String vaadinAncestor;         // ancestor FQN, null for direct actions
private Integer overrideCount;         // override count, null for direct actions
private Integer ownVaadinCalls;        // direct vaadin calls, null for direct actions
```

**MERGE pattern for inherited actions (actionId format):**
```
actionId = classFqn + "#INHERITED#" + ancestorFqn
```
This is deterministic: same class inheriting same ancestor always produces same ID.

### Pattern 6: migrationPostProcessing() pipeline insertion

**What:** Called from ExtractionService after `computeAndPersistRiskScores()`, before the Vaadin audit report.

```java
// ExtractionService.extract() — after line 218 (computeAndPersistRiskScores())
migrationRecipeService.migrationPostProcessing();
```

**IncrementalIndexingService:** Insert between step 6 (risk) and step 7 (vector re-embed) at line ~344.

### Pattern 7: Enrichment write-back

**What:** After extraction, aggregate MigrationAction nodes by source FQN, count usages, update `usageCount` on each rule, auto-add NEEDS_MAPPING entries for unknown types.

```cypher
// Aggregate usage counts by source type
MATCH (ma:MigrationAction)
WHERE NOT ma.isInherited
RETURN ma.source AS source, count(ma) AS usageCount

// Find unmapped types (auto-discovered NEEDS_MAPPING candidates)
MATCH (ma:MigrationAction {automatable: 'NO'})
WHERE ma.source STARTS WITH 'com.vaadin.'
  AND NOT ma.source STARTS WITH 'com.vaadin.flow.'
  AND ma.actionType = 'COMPLEX_REWRITE'
  AND NOT exists { MATCH (:MigrationAction {source: ma.source, actionType: 'CHANGE_TYPE'}) }
RETURN ma.source AS source, count(ma) AS usageCount
ORDER BY usageCount DESC
```

### Pattern 8: REST API — RecipeBookController

**What:** 5-endpoint controller in `com.esmp.migration.api`. Follows same pattern as existing `MigrationController`.

```java
@RestController
@RequestMapping("/api/migration/recipe-book")
public class RecipeBookController {

  @GetMapping               // GET /api/migration/recipe-book?category=&status=&automatable=
  public List<RecipeRule> getAllRules(...) { ... }

  @GetMapping("/gaps")      // GET /api/migration/recipe-book/gaps
  public List<RecipeRule> getGaps() { ... }

  @PutMapping("/rules/{id}")  // PUT /api/migration/recipe-book/rules/{id}
  public RecipeRule upsertRule(@PathVariable String id, @RequestBody RecipeRule rule) { ... }

  @DeleteMapping("/rules/{id}")  // DELETE /api/migration/recipe-book/rules/{id}
  public void deleteRule(@PathVariable String id) { ... }

  @PostMapping("/reload")   // POST /api/migration/recipe-book/reload
  public void reload() { ... }
}
```

### Pattern 9: MCP tool additions

**What:** Extend `MigrationToolService` following the existing `@Tool` + `@Timed` + counter pattern.

```java
// New tool — follows exact same @Tool + @Timed + meterRegistry.counter pattern
@Tool(description = "Returns unmapped Vaadin 7 types (NEEDS_MAPPING) sorted by usageCount "
    + "descending. Use to discover what types Claude needs to research and add to the recipe book.")
@Timed("esmp.mcp.getRecipeBookGaps")
public List<RecipeRule> getRecipeBookGaps() { ... }
```

### Anti-Patterns to Avoid

- **Hot-reloading via file watcher:** Locked decision is no hot-reload; reload is explicit via API endpoint.
- **RecipeBookRegistry as visitor field:** The visitor is not a Spring bean. Pass registry as constructor arg and take a snapshot at construction time for thread safety during parallel extraction.
- **Transitive detection before EXTENDS edges:** MUST run after `linkAllRelationships()`. LinkingService.linkInheritanceEdges() creates EXTENDS edges — transitive detection depends on them.
- **Separate @Transactional on migrationPostProcessing():** ExtractionService.extract() is already @Transactional("neo4jTransactionManager"). The post-processing call must be within the same transaction boundary OR must handle its own session (use raw Neo4jClient like RiskService does, which is not @Transactional itself).
- **COMPLEX_TYPES set still hardcoded in MigrationActionNode MERGE:** The visitor reads from registry, but the actionId still uses `ActionType.COMPLEX_REWRITE`. No change needed — this is already a string field.
- **Writing recipe book inside @Transactional:** File I/O in `enrichRecipeBook()` must be isolated from Neo4j transaction. Catch IOException, log warning, do not abort extraction.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON serialization of recipe book | Custom serializer | Jackson `ObjectMapper` (already on classpath) | Type-safe, handles nulls, pretty-print, no extra deps |
| Classpath resource copying | Stream copy loop | `Files.copy(InputStream, Path)` or `StreamUtils.copy` | One-liner, atomic |
| Overlay merge logic | Complex tree merge | Simple `LinkedHashMap.put()` by source FQN key | Locked decision: custom wins entirely (full replacement) |
| Vaadin 7 type detection in visitor | Regex scanning | Registry lookup by import FQN key | O(1) map lookup, already proven pattern |
| Transitive EXTENDS traversal | BFS in Java | Neo4j Cypher `[:EXTENDS*1..10]` | Graph-native, already in GraphQueryService line 164 |
| Complexity score clamping | Custom clamp | `Math.min(1.0, score)` inline | Trivial — no helper needed |

**Key insight:** No new library dependencies are required for this entire phase. All infrastructure is in place.

## Common Pitfalls

### Pitfall 1: MigrationPatternVisitor not a Spring bean — constructor injection needed

**What goes wrong:** Attempting `@Autowired RecipeBookRegistry` in `MigrationPatternVisitor` — the class is instantiated with `new MigrationPatternVisitor()` in ExtractionService, not by Spring.

**Why it happens:** All other visitors follow the same pattern (none are Spring beans). The ExtractionService creates them directly.

**How to avoid:** Add `RecipeBookRegistry registry` constructor parameter to `MigrationPatternVisitor`. Inject `RecipeBookRegistry` into `ExtractionService` (which IS a Spring bean) and pass it when creating the visitor.

**Warning signs:** `NullPointerException` on `this.rulesBySource` during extraction.

### Pitfall 2: Recipe book write-back inside Neo4j transaction corrupts extraction

**What goes wrong:** `enrichRecipeBook()` writes JSON to disk inside the `@Transactional("neo4jTransactionManager")` extraction transaction. File I/O is not rolled back on Neo4j transaction failure — leaves partial state.

**Why it happens:** `migrationPostProcessing()` is called from `extract()` which is annotated `@Transactional`.

**How to avoid:** `enrichRecipeBook()` wraps the file write in try/catch. IOException is logged as WARNING (non-fatal) and does not propagate. The recipe book is a cache of graph data — worst case is stale counts until next extraction.

**Warning signs:** File write succeeds but Neo4j MERGE fails — orphan file state.

### Pitfall 3: Transitive detection creates duplicate actions for classes with direct imports

**What goes wrong:** A class that directly imports `com.vaadin.ui.TextField` AND inherits a class that also uses it gets two `HAS_MIGRATION_ACTION` edges for the same source type.

**Why it happens:** Transitive detection runs independently of the direct import detection from Phase 16.

**How to avoid:** The Cypher query for transitive detection uses `NOT (c)-[:HAS_MIGRATION_ACTION]->(:MigrationAction {source: ancestor.fullyQualifiedName})` to skip classes that already have a direct action for that type.

**Warning signs:** `MigrationActionNode` validation query (`MIGRATION_ACTION_EDGES_INTACT`) reports mismatches.

### Pitfall 4: EXTENDS traversal returns null rows for unresolved parent types

**What goes wrong:** If a class extends `AbstractVaadinWidget` but that parent FQN is not in Neo4j (e.g., third-party library class, not extracted), the traversal stops there and may return null rows.

**Why it happens:** `OPTIONAL MATCH` returns null for the ancestor when the parent node doesn't exist as a `JavaClass` node.

**How to avoid:** Use `WHERE ancestor IS NOT NULL AND ancestor.fullyQualifiedName IN $vaadinSourceFqns` filter. Classes with unresolved parents simply don't match — this is correct behavior.

**Warning signs:** NPE when accessing `ancestorFqn` from query results.

### Pitfall 5: actionId collision for inherited actions

**What goes wrong:** If two different ancestors are both Vaadin 7 types and a class inherits both (via diamond inheritance or multi-hop chain), both produce the same actionId prefix `classFqn + '#INHERITED#'`.

**Why it happens:** The `#INHERITED#` prefix was designed for single-ancestor cases.

**How to avoid:** The full actionId is `classFqn + '#INHERITED#' + ancestorFqn`. Since `ancestorFqn` is the full ancestor FQN, it is unique per ancestor — no collision.

**Warning signs:** Only one inherited action created when two were expected.

### Pitfall 6: Parallel extraction — RecipeBookRegistry snapshot must be thread-safe

**What goes wrong:** `MigrationPatternVisitor` instances in parallel partitions share no state, but if they all call `registry.getRules()` concurrently while a reload is happening, they may see inconsistent rule lists.

**Why it happens:** `RecipeBookRegistry.load()` via `reload()` API modifies the internal list.

**How to avoid:** Take a snapshot of the rule list in the `MigrationPatternVisitor` constructor (`new ArrayList<>(registry.getRules())`). The registry's internal field update is synchronized via `synchronized` on `updateAndWrite()`. The snapshot ensures each visitor has a consistent immutable view.

**Warning signs:** `ConcurrentModificationException` during parallel extraction.

### Pitfall 7: ExtractionService @Transactional scope vs. migrationPostProcessing()

**What goes wrong:** `migrationPostProcessing()` runs Cypher queries for transitive detection inside the existing `@Transactional("neo4jTransactionManager")` extraction transaction. Depending on how `Neo4jClient` participates in the transaction, data persisted earlier in the same transaction may or may not be visible.

**Why it happens:** Neo4j's read-your-writes semantics within a single transaction mean the just-persisted `MigrationAction` nodes are visible via `Neo4jClient` queries in the same transaction.

**How to avoid:** This is the correct pattern — `RiskService.computeAndPersistRiskScores()` already runs inside the extraction transaction and reads nodes persisted earlier in the same call. `migrationPostProcessing()` follows the exact same precedent.

## Code Examples

### EXTENDS transitive detection — reference from GraphQueryService (verified, line 164)

```java
// Source: GraphQueryService.findInheritanceChain() line 164
// Pattern used for transitive traversal — proven in production
String cypher = """
    MATCH (c:JavaClass {fullyQualifiedName: $fqn})
    OPTIONAL MATCH path = (c)-[:EXTENDS*1..10]->(ancestor:JavaClass)
    WITH c, ancestor, path
    RETURN c.implementedInterfaces AS rootIfaces,
           ancestor.fullyQualifiedName AS ancestorFqn,
           ancestor.simpleName AS ancestorName,
           ancestor.implementedInterfaces AS ancestorIfaces,
           CASE WHEN path IS NOT NULL THEN length(path) ELSE null END AS depth
    """;
```

### Complexity profiling score formula (from CONTEXT.md)

```java
// Source: 17-CONTEXT.md — locked decision
double rawScore = (overrideCount * 0.3)
    + (ownVaadinCalls * 0.3)
    + (hasOwnBinding ? 0.2 : 0.0)
    + (hasOwnComponents ? 0.2 : 0.0);
double transitiveComplexity = Math.min(1.0, rawScore);

// Classification
boolean pureWrapper = transitiveComplexity == 0.0;
String automatable;
if (pureWrapper) {
    automatable = "PARTIAL";   // PURE_WRAPPER — mechanical wrapping
} else if (transitiveComplexity <= config.getAiAssistedThreshold()) {
    automatable = "PARTIAL";   // AI_ASSISTED
} else {
    automatable = "NO";        // COMPLEX
}
```

### MigrationConfig extension

```java
// MigrationConfig.java — extend existing @ConfigurationProperties(prefix="esmp.migration")
private String recipeBookPath = "data/migration/vaadin-recipe-book.json";
private String customRecipeBookPath = "";

// Nested config class for transitive weights
private TransitiveConfig transitive = new TransitiveConfig();

public static class TransitiveConfig {
    private double overrideWeight = 0.3;
    private double ownCallsWeight = 0.3;
    private double bindingWeight = 0.2;
    private double componentWeight = 0.2;
    private double aiAssistedThreshold = 0.4;
    // getters/setters
}
```

### application.yml additions

```yaml
esmp:
  migration:
    custom-mappings: {}
    recipe-book-path: ${ESMP_MIGRATION_RECIPE_BOOK_PATH:data/migration/vaadin-recipe-book.json}
    custom-recipe-book-path: ${ESMP_MIGRATION_CUSTOM_RECIPE_BOOK_PATH:}
    transitive:
      override-weight: 0.3
      own-calls-weight: 0.3
      binding-weight: 0.2
      component-weight: 0.2
      ai-assisted-threshold: 0.4
```

### Seed JSON structure (first 5 entries pattern)

```json
{
  "rules": [
    {
      "id": "COMP-001",
      "category": "COMPONENT",
      "source": "com.vaadin.ui.TextField",
      "target": "com.vaadin.flow.component.textfield.TextField",
      "actionType": "CHANGE_TYPE",
      "automatable": "YES",
      "context": null,
      "migrationSteps": [
        "Replace import with com.vaadin.flow.component.textfield.TextField",
        "Remove setValue(String)/getValue() calls — API is compatible",
        "Review addValueChangeListener() — signature changed to ValueChangeEvent<String>"
      ],
      "status": "MAPPED",
      "usageCount": 0,
      "discoveredAt": null
    },
    {
      "id": "COMP-007",
      "category": "COMPONENT",
      "source": "com.vaadin.ui.Table",
      "target": "com.vaadin.flow.component.grid.Grid",
      "actionType": "COMPLEX_REWRITE",
      "automatable": "NO",
      "context": "Table has no direct equivalent in Vaadin 24. Migration requires replacing with Grid, adding column definitions, and migrating container data sources to DataProvider.",
      "migrationSteps": [
        "Replace Table with Grid<T> where T is the bean type",
        "Add addColumn() calls for each table column",
        "Replace Container/BeanItemContainer with ListDataProvider or CallbackDataProvider",
        "Replace ItemClickListener with addItemClickListener()",
        "Replace setContainerDataSource() with setItems() or setDataProvider()"
      ],
      "status": "MAPPED",
      "usageCount": 0,
      "discoveredAt": null
    }
  ]
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Hardcoded static maps in MigrationPatternVisitor | External JSON recipe book via RecipeBookRegistry | Phase 17 | Rules can grow without code changes; users add custom rules at runtime |
| No transitive detection | EXTENDS*1..10 Cypher traversal post-linking | Phase 17 | Custom widgets inheriting Vaadin 7 types are now automatically flagged |
| No feedback loop for unmapped types | NEEDS_MAPPING auto-discovery after each extraction | Phase 17 | Recipe book grows organically as new codebases expose unknown types |
| MCP tool returns action type/source/target only | MCP tool returns migrationSteps[], pureWrapper, vaadinAncestor, usageCount | Phase 17 | Claude Code can execute a migration without additional queries |

**Deprecated/outdated:**
- Static `TYPE_MAP`, `PARTIAL_MAP`, `COMPLEX_TYPES`, `JAVAX_PACKAGE_MAP` constants in `MigrationPatternVisitor`: replaced by registry lookup. The maps themselves become the seed JSON content.

## Open Questions

1. **Recipe book seed: exact Vaadin 7 type inventory for 80+ rules**
   - What we know: 32 entries exist in the current static maps. The remaining 48+ must cover Vaadin 7 types not yet mapped (e.g., `com.vaadin.ui.Grid` [Vaadin 7's Grid], `com.vaadin.ui.NativeSelect`, `com.vaadin.ui.ListSelect`, `com.vaadin.ui.OptionGroup`, `com.vaadin.ui.Slider`, `com.vaadin.ui.RichTextArea`, `com.vaadin.ui.TwinColSelect`, `com.vaadin.ui.InlineDateField`, `com.vaadin.navigator.Navigator`, `com.vaadin.server.VaadinServlet`, `com.vaadin.data.Property`, etc.)
   - What's unclear: The exact canonical list of all public Vaadin 7.7.x types that enterprise apps commonly use
   - Recommendation: Seed JSON is marked Claude's Discretion. The implementer should enumerate all `com.vaadin.ui.*`, `com.vaadin.navigator.*`, `com.vaadin.server.*`, `com.vaadin.data.*` types from Vaadin 7.7.x release notes and the vaadin-server:7.7.48 JAR already on the test classpath. Target 80+ but don't block on hitting exactly 80.

2. **RecipeBookController: base rule protection for DELETE endpoint**
   - What we know: DELETE must protect base rules. Custom/discovered rules (from overlay or DISCOVERED category) can be deleted.
   - What's unclear: Where the "base" boundary is maintained — rules loaded from seed vs. overlay vs. discovered
   - Recommendation: Add a `boolean isBase` field to `RecipeRule` (set true when loaded from seed, false for overlay or DISCOVERED). DELETE returns 403 if `isBase=true`.

3. **ModuleMigrationSummary record extension — breaking API change**
   - What we know: `ModuleMigrationSummary` is a Java record — adding fields requires updating all call sites (constructor invocations). The record is used in `MigrationRecipeService.getModuleSummary()` and the MCP tool.
   - What's unclear: Whether any tests directly construct `ModuleMigrationSummary` with the current 9-arg constructor
   - Recommendation: Check `MigrationRecipeServiceIntegrationTest` and `MigrationControllerIntegrationTest` for construction before adding fields. Likely only 2 files need updating.

## Validation Architecture

> nyquist_validation is enabled (config.json: `"nyquist_validation": true`)

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers (Neo4j + MySQL + Qdrant) |
| Config file | No separate config — Spring Boot auto-configuration with `@SpringBootTest` |
| Quick run command | `./gradlew test --tests "com.esmp.migration.*" -x vaadinPrepareFrontend` |
| Full suite command | `./gradlew test -x vaadinPrepareFrontend` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| RB-01 | RecipeBookRegistry loads seed, merges overlay, survives reload | Unit | `./gradlew test --tests "com.esmp.migration.application.RecipeBookRegistryTest" -x vaadinPrepareFrontend` | Wave 0 |
| RB-02 | Seed JSON has 80+ rules, all required fields present, IDs unique | Unit | `./gradlew test --tests "com.esmp.migration.application.RecipeBookSeedTest" -x vaadinPrepareFrontend` | Wave 0 |
| RB-03 | enrichRecipeBook() updates usageCount, auto-adds DISCOVERED entries | Integration | `./gradlew test --tests "com.esmp.migration.application.MigrationRecipeServiceIntegrationTest" -x vaadinPrepareFrontend` | EXISTS (extend) |
| RB-04 | Transitive detection finds inherited Vaadin 7 types, assigns correct complexity class | Integration | `./gradlew test --tests "com.esmp.migration.application.TransitiveDetectionIntegrationTest" -x vaadinPrepareFrontend` | Wave 0 |
| RB-05 | RecipeBookController CRUD endpoints return correct status codes, protect base rules | Integration | `./gradlew test --tests "com.esmp.migration.api.RecipeBookControllerIntegrationTest" -x vaadinPrepareFrontend` | Wave 0 |
| RB-06 | getMigrationPlan returns migrationSteps[], pureWrapper, vaadinAncestor for transitive actions | Integration | `./gradlew test --tests "com.esmp.migration.api.MigrationControllerIntegrationTest" -x vaadinPrepareFrontend` | EXISTS (extend) |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "com.esmp.migration.*" -x vaadinPrepareFrontend`
- **Per wave merge:** `./gradlew test -x vaadinPrepareFrontend`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/com/esmp/migration/application/RecipeBookRegistryTest.java` — unit test for @PostConstruct load, overlay merge, reload — covers RB-01
- [ ] `src/test/java/com/esmp/migration/application/RecipeBookSeedTest.java` — validates seed JSON integrity — covers RB-02
- [ ] `src/test/java/com/esmp/migration/application/TransitiveDetectionIntegrationTest.java` — integration test with fixture graph data — covers RB-04
- [ ] `src/test/java/com/esmp/migration/api/RecipeBookControllerIntegrationTest.java` — REST endpoint integration tests — covers RB-05
- [ ] `src/test/resources/migration/` — test seed JSON fixture for unit tests (minimal, not the full 80+ production seed)

## Sources

### Primary (HIGH confidence)

- Direct code inspection: `MigrationPatternVisitor.java` — 32 existing type entries (18 TYPE_MAP + 1 PARTIAL_MAP + 11 COMPLEX_TYPES + 2 JAVAX)
- Direct code inspection: `GraphQueryService.java` line 164 — `[:EXTENDS*1..10]` Cypher pattern verified in production code
- Direct code inspection: `ExtractionService.java` lines 218-242 — exact pipeline insertion point identified
- Direct code inspection: `IncrementalIndexingService.java` lines 320-344 — matching insertion point in incremental path
- Direct code inspection: `MigrationActionNode.java` — current 7-field schema, version field present
- Direct code inspection: `MigrationConfig.java` — `@ConfigurationProperties(prefix="esmp.migration")`, existing `customMappings` field
- Direct code inspection: `application.yml` — `esmp.migration.custom-mappings: {}` pattern, all existing property namespaces
- Direct code inspection: `MigrationToolService.java` — exact `@Tool` + `@Timed` + counter pattern to follow
- Direct code inspection: `MigrationValidationQueryRegistry.java` — 3 existing queries (total 44 before phase 17)

### Secondary (MEDIUM confidence)

- 17-CONTEXT.md locked decisions — all implementation decisions verified against codebase
- REQUIREMENTS.md RB-01 through RB-06 — traceability confirmed

### Tertiary (LOW confidence)

- Vaadin 7.7.x public API inventory beyond the 32 already mapped — needs enumeration from JAR manifest or Vaadin docs for seed expansion to 80+

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies, all libraries verified on classpath
- Architecture: HIGH — all patterns sourced directly from existing codebase code
- Pitfalls: HIGH — derived from project decision history in STATE.md and code inspection
- Seed content (RB-02): MEDIUM — 32 entries fully verified; 48+ expansion requires Vaadin 7 API enumeration

**Research date:** 2026-03-28
**Valid until:** 2026-04-28 (stable — Spring Boot + Neo4j + Jackson APIs are stable)
