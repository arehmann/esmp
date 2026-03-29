# Phase 18: Module-Aware Batch Parsing for Enterprise Scale - Context

**Gathered:** 2026-03-29
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace the single-shot `JavaParser.parse(allFiles)` call with module-aware extraction that auto-detects Gradle/Maven modules, resolves inter-module dependency order via topological sort, parses each module independently with compiled project classes as classpath, persists to Neo4j after each module, and runs cross-module linking as a final pass. Falls back to current single-shot behavior when no build files are detected.

This phase does NOT add dashboard UI for extraction, does NOT change incremental indexing, and does NOT auto-compile user projects.

</domain>

<decisions>
## Implementation Decisions

### Module detection
- Auto-detect from `settings.gradle` (Gradle: parse `include` statements) or `pom.xml` (Maven: parse `<modules>` section)
- Derive module directories from the include/module declarations
- Parse each module's `build.gradle` for `project(':module-name')` dependencies, or `pom.xml` for inter-module `<dependency>` references
- Topological sort to determine parse order — leaf modules first, dependents after
- Independent modules (no dependency between them) grouped into parallel waves
- If no `settings.gradle` or `pom.xml` found: fall back to current single-shot parsing (backward compatible)

### Classpath strategy
- Compiled project classes ONLY — no external dependency JARs needed
- For Gradle: look in `build/classes/java/main/` per module
- For Maven: look in `target/classes/` per module
- All modules require compiled classes — user must run `./gradlew compileJava` (or `mvn compile`) before triggering extraction
- If compiled classes are missing for a module: SKIP that module entirely, report error in extraction response
- Classpath for module X = compiled classes directories of all modules that X depends on (from dependency graph)

### Progress reporting
- SSE events at 3 levels: per-module lifecycle, per-stage within module (PARSING → VISITING → PERSISTING), and file count during parsing
- Example SSE stream:
  ```
  {"module":"adsuite-persistent","stage":"PARSING","filesProcessed":500,"totalFiles":1597}
  {"module":"adsuite-persistent","stage":"PARSING","filesProcessed":1597,"totalFiles":1597}
  {"module":"adsuite-persistent","stage":"VISITING","filesProcessed":1597,"totalFiles":1597}
  {"module":"adsuite-persistent","stage":"PERSISTING","filesProcessed":1597,"totalFiles":1597}
  {"module":"adsuite-persistent","stage":"COMPLETE","filesProcessed":1597,"totalFiles":1597,"durationMs":12000}
  {"module":"adsuite-integration","stage":"PARSING","filesProcessed":500,"totalFiles":7717}
  ...
  {"stage":"LINKING","message":"Cross-module linking pass"}
  {"stage":"RISK_SCORING","message":"Computing risk scores"}
  {"stage":"MIGRATION","message":"Migration post-processing"}
  {"stage":"EXTRACTION_COMPLETE","totalModules":4,"totalFiles":18536,"durationMs":900000}
  ```
- No dashboard changes — API/SSE only for Phase 18

### API design
- Enhance existing `POST /api/extraction/trigger` — auto-detects modules when `settings.gradle` or `pom.xml` exists at sourceRoot
- Same endpoint, smarter behavior — backward compatible
- Response includes module detection info: detected modules, dependency order, skipped modules (if any)
- No new parameters required — module detection is automatic

### Persistence strategy
- Persist to Neo4j after each module completes (parse → visit → persist per module)
- Completed modules survive if a later module crashes
- Cross-module linking (EXTENDS, CALLS, DEPENDS_ON, etc.) runs as a single final pass after ALL modules are persisted — uses existing LinkingService which already resolves by FQN via Cypher MERGE
- Risk scoring and migration post-processing run after linking (unchanged from today)

### Failure handling
- If extraction crashes mid-way, re-run does a clean re-extract (no resume logic)
- Modules with missing compiled classes are skipped with error in report
- Individual module parse failures are logged and reported but don't block other modules

### Incremental indexing
- Unchanged — `POST /api/indexing/incremental` stays as-is (operates on individual files, not modules)

### Claude's Discretion
- OpenRewrite progress callback implementation (how to get file-level progress from JavaParser)
- Build file parser implementation details (regex vs proper Groovy/XML parsing for settings.gradle/pom.xml)
- Thread pool configuration for parallel wave execution
- Memory management between module parse cycles (GC hints, type cache clearing)
- Exact SSE event JSON schema

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Extraction pipeline (current implementation)
- `src/main/java/com/esmp/extraction/parser/JavaSourceParser.java` — Current single-shot parser, OpenRewrite JavaParser.parse() call, classpath handling
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` — Pipeline orchestrator: scan → parse → visit → persist → link → risk → migration. Contains visitSequentially() and visitInParallel() methods
- `src/main/java/com/esmp/extraction/application/LinkingService.java` — Post-extraction relationship creation via Cypher MERGE (already FQN-based, works cross-module)
- `src/main/java/com/esmp/extraction/config/ExtractionConfig.java` — parallelThreshold, partitionSize configuration
- `src/main/java/com/esmp/extraction/parser/ClasspathLoader.java` — Current classpath file loading logic

### Progress reporting (current implementation)
- `src/main/java/com/esmp/extraction/application/ExtractionProgressService.java` — SSE emitter management, ProgressEvent record
- `src/main/java/com/esmp/extraction/api/ExtractionController.java` — POST /api/extraction/trigger (async 202), GET /api/extraction/progress (SSE)

### Parallel extraction (Phase 15 foundation)
- `src/main/java/com/esmp/extraction/config/ExtractionExecutorConfig.java` — ThreadPoolTaskExecutor bean for parallel visitor execution
- `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` — merge() method for combining parallel partition results

### Real-world project structure (AdSuite)
- `C:/frontoffice/migration/source/AdSuite/settings.gradle` — Module include pattern: `include ":adsuite-business", ":adsuite-persistent", ":adsuite-market", ...`
- `C:/frontoffice/migration/source/AdSuite/adsuite-market/build.gradle` — Inter-module deps via `project(':adsuite-business')`, `project(':adsuite-persistent')`, `project(':adsuite-integration')`
- `C:/frontoffice/migration/source/AdSuite/adsuite-persistent/build.gradle` — Leaf module (no project deps), Java 6 source compat with JDK 11 fork for SignSoft enhancer

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `JavaSourceParser.parse()`: Accepts `List<Path>` and classpath — can be called per-module with filtered file list and module-specific classpath
- `ExtractionService.visitInParallel()`: Already partitions files and runs visitors in parallel — can be reused within each module
- `LinkingService.linkAllRelationships()`: Uses Cypher MERGE by FQN — naturally handles cross-module links when all nodes are in Neo4j
- `ExtractionProgressService`: SSE infrastructure — extend ProgressEvent to include module name and stage
- `ExtractionExecutorConfig`: ThreadPoolTaskExecutor — reusable for parallel module wave execution

### Established Patterns
- `@ConfigurationProperties` for extraction config — extend with module-aware settings
- Batched UNWIND MERGE for bulk persistence (2000-row batches) — already handles large datasets
- AccumulatorToModelMapper for visitor output → Neo4j model conversion — unchanged per module
- Async extraction via CompletableFuture.runAsync — same pattern for module waves

### Integration Points
- `ExtractionService.extract()` — main entry point to refactor: add module detection before parse
- `ExtractionController` — SSE progress events need module-level granularity
- `ExtractionConfig` — may need new properties for module-aware behavior
- `application.yml` — module detection toggle if needed

</code_context>

<specifics>
## Specific Ideas

- Real-world test case: AdSuite has 4 modules (persistent, integration, business, market) with 18K files. Dependency chain: persistent/integration (leaf) → business → market
- AdSuite persistent module requires Java 6 source compat with JDK 11 fork for SignSoft JDO enhancer — ESMP doesn't need to know about this, user handles compilation
- The compiled classes at `build/classes/java/main/` include enhanced bytecode (JDO enhancement) — this is fine as classpath for OpenRewrite
- The `adsuite-market` module is the Vaadin 7 module — it should be parsed LAST since it depends on all other modules
- For file-level progress inside OpenRewrite parsing: investigate `InMemoryExecutionContext` message passing or wrap the file list with a counting proxy

</specifics>

<deferred>
## Deferred Ideas

- Dashboard extraction progress panel — future phase after Phase 18 is proven
- Module-aware incremental indexing — adapt incremental to detect module boundaries
- Auto-compile support (ESMP runs gradlew/mvn inside container) — requires JDK in runtime image
- Resume from failed module (skip completed modules on re-run) — adds complexity, clean re-extract is simpler for now
- Stub generation from extracted metadata (Strategy B) — not needed since compiled classes strategy works

</deferred>

---

*Phase: 18-module-aware-batch-parsing-for-enterprise-scale*
*Context gathered: 2026-03-29*
