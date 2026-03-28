# Phase 16: OpenRewrite Recipe-Based Migration Engine - Context

**Gathered:** 2026-03-28
**Status:** Ready for planning

<domain>
## Phase Boundary

Catalog Vaadin 7 API usages per class with automation scores, generate and execute OpenRewrite recipes for mechanical transforms (type renames, import swaps, package changes), and expose migration planning and execution via REST API and MCP tools — leaving only complex rewrites (data binding, navigation, custom components) for AI.

This phase adds the 8th extraction visitor (MigrationPatternVisitor), a new MigrationAction Neo4j node type, a MigrationRecipeService for recipe generation/execution, REST endpoints, and 3 new MCP tools. It does NOT perform the actual Vaadin 7→24 migration — it provides the tooling for Claude and developers to do so.

</domain>

<decisions>
## Implementation Decisions

### Migration action storage
- Separate `MigrationAction` Neo4j nodes linked via `HAS_MIGRATION_ACTION` edges (consistent with BusinessTermNode, AnnotationNode patterns)
- Enables Cypher queries for cross-class analysis ("find all classes using Table"), module-level aggregation, and dashboard integration
- Computed scores (migrationActionCount, automatableActionCount, automationScore, needsAiMigration) stored as direct properties on ClassNode
- Class-level granularity — actions tied to class FQN, not individual methods
- Simple `putAll` merge for parallel extraction — each class appears in exactly one partition, no deduplication needed

### Visitor design
- MigrationPatternVisitor is a separate visitor (8th), NOT extending VaadinPatternVisitor
- Clean separation: VaadinPatternVisitor detects Vaadin 7 presence (boolean labels), MigrationPatternVisitor catalogs specific type usages (migration actions)
- Follows existing pattern where each visitor has one job

### Recipe execution and output strategy
- **MCP tools return diff + modified source text only** — Claude handles all filesystem writes via its own tools. ESMP never writes to the target codebase through MCP.
- **REST API supports both preview and direct apply** — for dashboard batch operations where Claude isn't in the loop
- REST batch-apply uses the configured `esmp.source` path from SourceAccessService (no per-request source root)
- Batch module-apply is synchronous — recipes are pure transforms, fast enough for synchronous response even at 50 classes

### Type mapping catalog
- Hardcoded default catalog (~30 Vaadin 7→24 mappings + javax→jakarta package migrations) as Java constant maps
- Extensible via `application.yml` (`esmp.migration.custom-mappings`) for enterprise-specific types without code changes
- Unknown Vaadin 7 types (com.vaadin.* but not com.vaadin.flow.*) auto-classified as COMPLEX_REWRITE with automatable=NO — nothing silently skipped
- javax→jakarta included (javax.servlet.*, javax.validation.* etc.) since it's part of the same modernization path

### Automation classification
- Three-tier: YES / PARTIAL / NO
- PARTIAL for cases like Panel→Div where type change is mechanical but styling needs manual adjustment
- Gives AI better signal about what remains after recipe execution
- automationScore = automatableActionCount / migrationActionCount (PARTIAL counts as 0.5 for score calculation)

### Claude's Discretion
- Exact MigrationAction node properties and schema
- OpenRewrite recipe composition strategy (CompositeRecipe vs chained execution)
- MCP tool description strings
- Error handling for recipe execution failures (e.g., unparseable source)
- Validation query design for migration-specific queries
- AccumulatorToModelMapper extension for migration action mapping
- Dashboard migration view design (if included in this phase)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Extraction pipeline
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` — Visitor pipeline orchestrator, visitSequentially/visitInParallel/visitBatch methods where MigrationPatternVisitor will be added
- `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` — Accumulator with merge() method, needs new migrationActions section
- `src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java` — Existing Vaadin 7 detection (labels, not usages) — reference for what's already detected
- `src/main/java/com/esmp/extraction/visitor/ComplexityVisitor.java` — Reference for visitor with per-class state tracking pattern

### Graph model
- `src/main/java/com/esmp/extraction/model/ClassNode.java` — ClassNode entity, needs 4 new properties (migrationActionCount, automatableActionCount, automationScore, needsAiMigration)
- `src/main/java/com/esmp/extraction/model/BusinessTermNode.java` — Reference pattern for separate Neo4j node type with relationship edges

### MCP integration
- `src/main/java/com/esmp/mcp/tool/MigrationToolService.java` — Existing 6 MCP tools, 3 new migration tools will be added here
- `src/main/java/com/esmp/mcp/config/McpToolRegistration.java` — Tool registration bean

### Source access
- `src/main/java/com/esmp/source/application/SourceAccessService.java` — Resolves source root path for REST batch-apply operations
- `src/main/java/com/esmp/extraction/parser/JavaSourceParser.java` — LST parser, reused for recipe execution

### Research
- `.planning/phases/16-openrewrite-recipe-based-migration-engine/16-RESEARCH.md` — Full type mapping catalog (section 4), architecture proposal (section 5), dependency requirements (section 6)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `JavaSourceParser.parse()`: Already parses Java source into OpenRewrite LSTs — reuse for recipe execution input
- `ExtractionAccumulator.merge()`: Parallel merge pattern — extend with migrationActions map
- `MigrationToolService`: 6 existing MCP tools — add 3 new migration tools in same class
- `SourceAccessService`: Resolves source root path — use for REST apply endpoints
- `Neo4jSchemaInitializer`: Constraint/index creation — add migration-related indexes
- `LinkingService`: Edge creation pattern — reference for HAS_MIGRATION_ACTION edge creation

### Established Patterns
- Package-by-feature: new code in `com.esmp.migration` package
- `@ConfigurationProperties` for config (RiskWeightConfig, McpConfig, SchedulingWeightConfig)
- `@Tool` annotation + Spring AI for MCP tool methods
- Neo4jClient Cypher for complex queries, Spring Data Neo4j for entity persistence
- Testcontainers (Neo4j + MySQL + Qdrant) for integration tests
- Batched UNWIND MERGE for bulk node persistence

### Integration Points
- ExtractionService: Add MigrationPatternVisitor to visitBatch() and visitSequentially()
- AccumulatorToModelMapper: Map migration actions to ClassNode properties + MigrationAction nodes
- Neo4j persistence: New MigrationAction node type + HAS_MIGRATION_ACTION edges
- MigrationToolService: 3 new @Tool methods
- IncrementalIndexingService: Re-extraction updates migration actions for changed files

</code_context>

<specifics>
## Specific Ideas

- MCP tools should return enough data for Claude to make informed decisions — diff text, automation score, list of remaining manual items — so Claude can decide whether to apply the mechanical changes first or do everything together
- The type mapping catalog in research section 4.1 has ~30 confirmed mappings — use as the default hardcoded set
- OpenRewrite 8.74.3 is already a dependency for parsing; add `rewrite-recipe-bom` and `rewrite-test` for recipe execution and testing

</specifics>

<deferred>
## Deferred Ideas

- Dashboard migration view with automation scores and diff viewer — could be Phase 17 or bundled if time allows
- AI orchestration engine that chains recipe execution + RAG context + Claude migration — v2 requirement (ORCH-01)
- Behavioral diffing framework for pre/post migration comparison — v2 requirement (DIFF-01)

</deferred>

---

*Phase: 16-openrewrite-recipe-based-migration-engine*
*Context gathered: 2026-03-28*
