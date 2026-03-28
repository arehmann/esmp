# Phase 17: Migration Recipe Book & Transitive Detection - Context

**Gathered:** 2026-03-28
**Status:** Ready for planning

<domain>
## Phase Boundary

Externalize migration type mappings from hardcoded Java maps to a loadable JSON recipe book with base + custom overlay support, detect transitive Vaadin 7 usage through EXTENDS graph traversal with per-class complexity profiling, enrich the recipe book with usage counts and unmapped type discovery after each extraction, and expose recipe book management and enriched migration data via REST API and updated MCP tools.

This phase does NOT add new Vaadin 7→24 mappings beyond consolidating existing ones — it builds the infrastructure for the recipe book to grow over time through extraction-driven discovery and API-driven curation.

</domain>

<decisions>
## Implementation Decisions

### Recipe book format & storage
- External JSON file at runtime path `data/migration/vaadin-recipe-book.json` (configurable via `esmp.migration.recipe-book-path`)
- Classpath seed file `src/main/resources/migration/vaadin-recipe-book-seed.json` — copied to runtime path on first startup if no file exists
- Custom overlay file merged on top (configurable via `esmp.migration.custom-recipe-book-path`)
- Flat JSON structure with `rules[]` array — each rule has: id, category, source, target, actionType, automatable, context, migrationSteps[], status, usageCount, discoveredAt
- Categories: COMPONENT, DATA_BINDING, SERVER, JAVAX_JAKARTA, DISCOVERED (for auto-added unmapped types)
- Load once at startup (RecipeBookRegistry @PostConstruct) — no hot-reload, manual reload via API endpoint
- Custom overlay merge by `source` FQN key — custom wins entirely (full replacement), new sources are additive
- MigrationPatternVisitor refactored to read from RecipeBookRegistry instead of hardcoded static maps

### Transitive detection strategy
- Post-linking Cypher traversal in ExtractionService pipeline — runs after linkAllRelationships() creates EXTENDS edges
- Cypher walks `(c:JavaClass)-[:EXTENDS*1..10]->(ancestor)` to find classes transitively inheriting from Vaadin 7 types
- Creates inherited MigrationAction nodes with `isInherited=true`, linked via HAS_MIGRATION_ACTION
- actionId for inherited actions: `classFqn + '#INHERITED#' + source` — deterministic MERGE, no duplicates

### Transitive complexity profiling (per-class)
- Computed from existing graph data only — no new visitor needed
- Signals: overrideCount (methods matching ancestor method names), ownVaadinCalls (CALLS to com.vaadin.*), hasOwnBinding (VaadinDataBinding label), hasOwnComponents (VaadinComponent label)
- Weighted score: `transitiveComplexity = overrideCount*0.3 + ownVaadinCalls*0.3 + hasOwnBinding*0.2 + hasOwnComponents*0.2` (clamped to 1.0)
- Classification: score=0 → PURE_WRAPPER (automatable=PARTIAL), score≤threshold → AI_ASSISTED (automatable=PARTIAL), score>threshold → COMPLEX (automatable=NO)
- Weights and threshold configurable via `@ConfigurationProperties(prefix="esmp.migration.transitive")` — default ai-assisted-threshold=0.4
- MigrationAction stores: pureWrapper, transitiveComplexity, vaadinAncestor, overrideCount, ownVaadinCalls

### Extraction-driven enrichment
- Unified `migrationPostProcessing()` method in MigrationRecipeService, called from ExtractionService after linkAllRelationships() and computeRiskScores()
- Three sub-steps: detectTransitiveMigrations() → recomputeMigrationScores() → enrichRecipeBook()
- enrichRecipeBook() aggregates MigrationAction nodes by source FQN, updates usageCount per rule, auto-adds NEEDS_MAPPING entries for unmapped com.vaadin.* types, writes updated recipe book to file
- NEEDS_MAPPING auto-additions: category=DISCOVERED, automatable=NO, actionType=COMPLEX_REWRITE, target=null, discoveredAt=date

### REST API — recipe book management
- `GET /api/migration/recipe-book` — all rules with usageCount and status, filterable by category/status/automatable
- `GET /api/migration/recipe-book/gaps` — NEEDS_MAPPING rules only, sorted by usageCount descending
- `PUT /api/migration/recipe-book/rules/{id}` — add or update a custom rule (writes to file)
- `DELETE /api/migration/recipe-book/rules/{id}` — remove custom/discovered rule (base rules protected)
- `POST /api/migration/recipe-book/reload` — re-read file + re-merge custom overlay

### MCP tools — updates and additions
- `getMigrationPlan(classFqn)` updated: each MigrationActionEntry gains isInherited, inheritedFrom, vaadinAncestor, pureWrapper, transitiveComplexity, overrideCount, ownVaadinCalls, migrationSteps[]
- `getModuleMigrationSummary(module)` updated: adds transitiveClassCount, coverageByType (mapped types / total types), coverageByUsage (mapped usages / total usages), topGaps (highest-usage unmapped types)
- New tool: `getRecipeBookGaps()` — returns NEEDS_MAPPING rules sorted by usageCount, so Claude discovers what to research next

### Coverage scoring
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

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Migration engine (Phase 16 foundation)
- `src/main/java/com/esmp/extraction/visitor/MigrationPatternVisitor.java` — Current hardcoded TYPE_MAP (18), PARTIAL_MAP (1), COMPLEX_TYPES (11), JAVAX_PACKAGE_MAP (2) — must be refactored to read from RecipeBookRegistry
- `src/main/java/com/esmp/migration/application/MigrationRecipeService.java` — Recipe generation/execution service, add migrationPostProcessing() with 3 sub-steps
- `src/main/java/com/esmp/extraction/model/MigrationActionNode.java` — Neo4j node schema, needs transitive fields (isInherited, pureWrapper, transitiveComplexity, vaadinAncestor, overrideCount, ownVaadinCalls)
- `src/main/java/com/esmp/extraction/config/MigrationConfig.java` — Existing config, extend with recipe-book-path, custom-recipe-book-path, transitive weights/threshold

### Extraction pipeline integration
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` — Pipeline orchestrator, call migrationPostProcessing() after linkAllRelationships() + computeRiskScores()
- `src/main/java/com/esmp/extraction/application/LinkingService.java` — Creates EXTENDS edges needed for transitive traversal
- `src/main/java/com/esmp/extraction/model/ClassNode.java` — Migration properties (migrationActionCount, automatableActionCount, automationScore, needsAiMigration)

### MCP tools
- `src/main/java/com/esmp/mcp/tool/MigrationToolService.java` — Update getMigrationPlan + getModuleMigrationSummary, add getRecipeBookGaps

### API records
- `src/main/java/com/esmp/migration/api/MigrationActionEntry.java` — Extend with transitive enrichment fields
- `src/main/java/com/esmp/migration/api/MigrationPlan.java` — May need new fields for transitive summary
- `src/main/java/com/esmp/migration/api/ModuleMigrationSummary.java` — Extend with transitiveClassCount, coverageByType, coverageByUsage, topGaps

### Vaadin detection (reference)
- `src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java` — VaadinComponent, VaadinDataBinding, VaadinView labels used as transitive complexity signals
- `src/main/java/com/esmp/graph/application/GraphQueryService.java` — EXTENDS chain traversal patterns (reference for Cypher)

### Phase 16 context
- `.planning/phases/16-openrewrite-recipe-based-migration-engine/16-CONTEXT.md` — Phase 16 decisions (MCP tools return data only, actionId composite key, automation classification)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `MigrationPatternVisitor`: TYPE_MAP, PARTIAL_MAP, COMPLEX_TYPES, JAVAX_PACKAGE_MAP — source of truth for seed JSON content
- `MigrationRecipeService`: Already owns recipe generation/execution — natural home for migrationPostProcessing()
- `MigrationConfig`: Already has `@ConfigurationProperties(prefix="esmp.migration")` — extend with new fields
- `LinkingService.linkInheritanceEdges()`: Creates EXTENDS edges via Cypher MERGE — transitive detection depends on this
- `GraphQueryService`: Has `EXTENDS*1..10` Cypher patterns — reference for transitive traversal
- `Neo4jSchemaInitializer`: Constraint/index creation — add indexes for new MigrationAction fields

### Established Patterns
- `@ConfigurationProperties` for config (RiskWeightConfig, SchedulingWeightConfig) — use for transitive weights
- Package-by-feature: recipe book code in `com.esmp.migration` package
- Neo4jClient Cypher for complex graph queries, batched UNWIND MERGE for bulk persistence
- MigrationAction composite actionId for deterministic MERGE (extend with INHERITED prefix)
- `@Tool` annotation + Spring AI for MCP tool methods

### Integration Points
- ExtractionService: Add migrationPostProcessing() call after computeRiskScores()
- IncrementalIndexingService: migrationPostProcessing() also runs on incremental reindex
- MigrationPatternVisitor: Refactor to inject RecipeBookRegistry, read rules from registry instead of static maps
- MigrationToolService: Update 2 existing tools, add 1 new tool
- application.yml: New esmp.migration.recipe-book-path, custom-recipe-book-path, transitive.* properties

</code_context>

<specifics>
## Specific Ideas

- Recipe book seed should consolidate existing 32 mappings and expand to 80+ covering all known Vaadin 7→24 component/data/server types plus javax→jakarta (RB-02)
- migrationSteps[] array on each rule is the key AI enabler — it tells Claude exactly what to do per migration action, not just what to rename
- The feedback loop: extract → discover gaps → Claude/user resolves gaps via API → re-extract → full coverage
- NEEDS_MAPPING auto-discovery means the recipe book grows organically with each new codebase analyzed — no manual audit needed
- Pipeline order matters: transitive detection MUST run after EXTENDS edges exist (post-linking) and BEFORE score recomputation

</specifics>

<deferred>
## Deferred Ideas

- Dashboard migration view with recipe book stats, gap visualization, coverage heatmap — could be Phase 18
- AI orchestration engine chaining recipe execution + RAG context + Claude migration — v2 requirement (ORCH-01)
- Recipe book versioning / changelog tracking — not needed for v1
- Automatic migration step generation from OpenRewrite recipe metadata — future enhancement

</deferred>

---

*Phase: 17-migration-recipe-book-transitive-detection*
*Context gathered: 2026-03-28*
