# Phase 18: Module-Aware Batch Parsing for Enterprise Scale - Research

**Researched:** 2026-03-29
**Domain:** Java build system detection, topological ordering, OpenRewrite classpath, SSE progress streaming
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Module detection:**
- Auto-detect from `settings.gradle` (Gradle: parse `include` statements) or `pom.xml` (Maven: parse `<modules>` section)
- Derive module directories from the include/module declarations
- Parse each module's `build.gradle` for `project(':module-name')` dependencies to build the inter-module dependency graph
- Topological sort to determine parse order — leaf modules first, dependents after
- Independent modules (no dependency between them) grouped into parallel waves
- Fall back to current single-shot parsing when no `settings.gradle` or `pom.xml` found at sourceRoot

**Classpath strategy:**
- Compiled project classes ONLY — no external dependency JARs needed
- For Gradle: look in `build/classes/java/main/` per module
- For Maven: look in `target/classes/` per module
- User must run `./gradlew compileJava` (or `mvn compile`) before triggering extraction
- If compiled classes are missing for a module: SKIP that module, report error in response
- Classpath for module X = compiled classes directories of all modules that X depends on

**Progress reporting:**
- SSE events at 3 levels: per-module lifecycle, per-stage within module (PARSING → VISITING → PERSISTING), file count during parsing
- No dashboard changes — API/SSE only

**API design:**
- Enhance existing `POST /api/extraction/trigger` — backward compatible
- Auto-detects modules when `settings.gradle` or `pom.xml` exists at sourceRoot
- Response includes detected modules, dependency order, skipped modules

**Persistence strategy:**
- Persist to Neo4j after each module completes (parse → visit → persist per module)
- Cross-module linking (EXTENDS, CALLS, DEPENDS_ON, etc.) as a single final pass after all modules

**Failure handling:**
- Re-run does a clean re-extract (no resume logic)
- Modules with missing compiled classes: skipped with error in report
- Individual module parse failures: logged and reported, don't block other modules

**Incremental indexing:**
- Unchanged — `POST /api/indexing/incremental` stays as-is

### Claude's Discretion
- OpenRewrite progress callback implementation (how to get file-level progress from JavaParser)
- Build file parser implementation details (regex vs proper Groovy/XML parsing for settings.gradle/pom.xml)
- Thread pool configuration for parallel wave execution
- Memory management between module parse cycles (GC hints, type cache clearing)
- Exact SSE event JSON schema

### Deferred Ideas (OUT OF SCOPE)
- Dashboard extraction progress panel
- Module-aware incremental indexing
- Auto-compile support (ESMP runs gradlew/mvn inside container)
- Resume from failed module (skip completed modules on re-run)
- Stub generation from extracted metadata (Strategy B)
</user_constraints>

---

## Summary

Phase 18 replaces the single-shot `JavaSourceParser.parse(allFiles)` call with a module-aware orchestrator. The key insight from reading the actual AdSuite codebase is that the module graph is straightforward: `settings.gradle` declares 8 modules (persistent, integration, business, market, distribution, salespoint, runtime, buildSrc) and each module's `build.gradle` uses `project(':module-name')` in the `dependencies {}` block to declare inter-module dependencies. The dependency chain is: persistent/integration (leaf) → business → market. The real-world data confirms 18,536 Java files split across 4 non-trivial modules.

The implementation adds a new `ModuleDetectionService` that parses the Gradle/Maven build files, a `ModuleDependencyGraph` that topologically sorts modules into parse waves, and a `ModuleAwareExtractionService` that orchestrates the per-module pipeline (parse → visit → persist) with wave-level parallelism. The existing `JavaSourceParser`, `ExtractionAccumulator`, `visitInParallel()`, `LinkingService`, and `ExtractionProgressService` are all reused unchanged — the architecture just wraps them with module-aware orchestration.

The biggest technical decision is build file parsing: the `settings.gradle` `include` syntax and `build.gradle` `project(':name')` syntax are simple enough that regex extraction is the right choice — the Groovy AST is not needed. For Maven, standard DOM XML parsing of `<modules>` and `<dependency>` blocks suffices. OpenRewrite file-level progress inside `JavaParser.parse()` is the one discretionary area: the `InMemoryExecutionContext` receives parse errors but does not fire per-file callbacks; the practical approach is a wrapping iterator or post-parse progress update.

**Primary recommendation:** Implement `ModuleDetectionService` with regex-based Gradle parsing and DOM-based Maven parsing. Orchestrate waves via `CompletableFuture.allOf` on the existing `extractionExecutor` thread pool. Extend `ProgressEvent` with module name, stage, and optional message fields. Keep the existing `@Transactional("neo4jTransactionManager")` boundary per module.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Java NIO (`Files`, `Path`) | JDK 21 | Build file reading, classpath directory resolution | Already used throughout extraction pipeline |
| Java regex (`Pattern`, `Matcher`) | JDK 21 | settings.gradle `include` parsing, build.gradle `project(':name')` parsing | Sufficient for the known syntax; no Groovy AST parser needed |
| `javax.xml.parsers.DocumentBuilder` | JDK 21 | Maven pom.xml DOM parsing | Standard JDK XML, no additional dependency |
| `CompletableFuture` | JDK 21 | Parallel wave execution across modules | Already used in Phase 15 extraction pipeline |
| `ExtractionExecutorConfig` (existing) | — | Thread pool for wave-parallel module execution | Already sized correctly for parallel extraction partitions |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `JavaSourceParser` (existing) | — | Per-module LST parsing with module-specific classpath | Called once per module with filtered file list |
| `ExtractionAccumulator.merge()` (existing) | — | Merging parallel partition results within a module | Internal to per-module parallel visitor execution |
| `ExtractionProgressService` (existing) | — | SSE event dispatch | Extended with module + stage fields |
| `LinkingService.linkAllRelationships()` (existing) | — | Cross-module linking final pass | Called once after all modules are persisted |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Regex for settings.gradle parsing | Groovy AST parser | Groovy parser adds a heavy dependency; regex covers the `include ":name"` pattern completely for this codebase |
| DOM parsing for pom.xml | Maven Model API (`maven-model`) | Maven API adds a dependency; DOM is sufficient for `<modules>/<module>` and `<dependencies>/<artifactId>` |
| Using existing `extractionExecutor` for wave parallelism | Separate wave thread pool | Same thread pool is fine — wave parallelism is at a coarser grain than partition parallelism |

**Installation:** No new dependencies required. All implementation uses JDK built-ins and existing project infrastructure.

---

## Architecture Patterns

### Recommended Project Structure
```
src/main/java/com/esmp/extraction/
├── module/
│   ├── ModuleDetectionService.java      # Gradle/Maven build file parsing → ModuleGraph
│   ├── ModuleGraph.java                 # Topological sort, wave grouping
│   ├── ModuleDescriptor.java            # Record: name, srcDir, classpathDirs, javaFiles
│   └── ModuleDetectionResult.java       # Record: modules, waves, skippedModules, detectedBuildSystem
├── application/
│   ├── ExtractionService.java           # MODIFIED: module-aware path in extract()
│   └── ExtractionProgressService.java   # MODIFIED: ProgressEvent gains module + message fields
```

### Pattern 1: Build File Detection and Parsing

**What:** Check for `settings.gradle` at sourceRoot → Gradle path. Check for `pom.xml` at sourceRoot with `<modules>` → Maven path. Neither found → single-shot fallback.

**When to use:** At the start of `extract()` before any parsing occurs.

**Example (Gradle settings.gradle parsing):**
```java
// Source: direct analysis of C:/frontoffice/migration/source/AdSuite/settings.gradle
// Line: include ":adsuite-business", ":adsuite-persistent", ":adsuite-market" ,":adsuite-salespoint",":adsuite-integration","adsuite-runtime","adsuite-distribution"
// Note: colon prefix is optional (both ":name" and "name" appear in AdSuite)

private static final Pattern INCLUDE_PATTERN =
    Pattern.compile("include\\s+(['\"]:[^'\"]+['\"](?:\\s*,\\s*['\"][^'\"]*['\"])*)", Pattern.MULTILINE);
private static final Pattern MODULE_NAME_PATTERN =
    Pattern.compile("['\"][:.]?([^'\":/]+)['\"]");

List<String> parseGradleIncludes(Path settingsFile) {
    String content = Files.readString(settingsFile);
    List<String> modules = new ArrayList<>();
    Matcher m = INCLUDE_PATTERN.matcher(content);
    while (m.find()) {
        Matcher nm = MODULE_NAME_PATTERN.matcher(m.group(1));
        while (nm.find()) {
            modules.add(nm.group(1));  // "adsuite-business" without colon
        }
    }
    return modules;
}
```

**Example (Gradle build.gradle inter-module dependency parsing):**
```java
// Source: adsuite-market/build.gradle line 123: otherProjects project(':adsuite-business')
// Source: adsuite-business/build.gradle line 120: implementation project(':adsuite-persistent')
// Pattern covers all configuration names (implementation, otherProjects, compile, etc.)

private static final Pattern PROJECT_DEP_PATTERN =
    Pattern.compile("project\\s*\\(\\s*['\"][:.]?([^'\"]+)['\"]\\s*\\)");

List<String> parseGradleProjectDeps(Path buildFile) {
    String content = Files.readString(buildFile);
    return PROJECT_DEP_PATTERN.matcher(content).results()
        .map(mr -> mr.group(1))
        .distinct().collect(Collectors.toList());
}
```

### Pattern 2: Topological Sort with Wave Grouping

**What:** Kahn's BFS algorithm over the module dependency graph. Modules with in-degree 0 are in wave 0 (parallel). After removing wave 0 nodes, recalculate in-degrees for wave 1, etc. Same algorithm already implemented in `SchedulingService`.

**When to use:** After `ModuleDetectionService` builds the dependency map.

**Example:**
```java
// Source: SchedulingService.java (Phase 13) — identical Kahn's BFS pattern
List<List<String>> buildWaves(Map<String, List<String>> dependsOn) {
    Map<String, Integer> inDegree = new HashMap<>();
    dependsOn.keySet().forEach(m -> inDegree.put(m, 0));
    dependsOn.forEach((m, deps) -> deps.forEach(d -> inDegree.merge(d, 1, Integer::sum)));
    // Kahn's BFS produces waves: each wave = modules with inDegree 0 after removing prior waves
}
```

### Pattern 3: Per-Module Transaction Boundary

**What:** Each module's persist step runs in its own `@Transactional("neo4jTransactionManager")` method. The module orchestrator is NOT `@Transactional` itself (same pattern as `IncrementalIndexingService`).

**When to use:** Module orchestration method calls a transactional `persistModule()` helper per module.

**Example:**
```java
// Source: IncrementalIndexingService.java (Phase 10) — established pattern
// NOT @Transactional: the orchestrator
public ExtractionResult extractModuleAware(String sourceRoot, String classpathFile, String jobId) {
    // ... detect modules, for each wave ...
    for (ModuleDescriptor module : wave) {
        persistModule(module, jobId);  // @Transactional method
    }
    linkingService.linkAllRelationships(mergedAccumulator);
    riskService.computeAndPersistRiskScores();
    migrationRecipeService.migrationPostProcessing();
}

@Transactional("neo4jTransactionManager")
void persistModule(ModuleDescriptor module, String jobId) {
    // parse → visit → persist
}
```

### Pattern 4: Extended ProgressEvent

**What:** Extend `ExtractionProgressService.ProgressEvent` record to include `module` name, `stage` string, and `message` for cross-module stages.

```java
// Existing: record ProgressEvent(String phase, int filesProcessed, int totalFiles)
// New:      record ProgressEvent(String module, String stage, int filesProcessed, int totalFiles, String message, Long durationMs)
// Null-safe: module=null for cross-module stages (LINKING, RISK_SCORING, EXTRACTION_COMPLETE)
```

The SSE client reads `event.module` to know which module is reporting. Cross-module stages use `module=null` and carry a `message` string.

### Pattern 5: Classpath Directory Resolution

**What:** For each module, resolve classpath = compiled class directories of all upstream dependency modules. No external JARs needed — OpenRewrite resolves types from `.class` files on disk.

```java
Path getCompiledClassesDir(Path projectRoot, String moduleName, BuildSystem buildSystem) {
    return buildSystem == GRADLE
        ? projectRoot.resolve(moduleName).resolve("build/classes/java/main")
        : projectRoot.resolve(moduleName).resolve("target/classes");
}

List<Path> buildModuleClasspath(ModuleDescriptor module, Map<String, ModuleDescriptor> allModules) {
    return module.dependsOn().stream()
        .map(dep -> allModules.get(dep))
        .filter(dep -> dep != null && Files.isDirectory(dep.compiledClassesDir()))
        .map(ModuleDescriptor::compiledClassesDir)
        .collect(Collectors.toList());
}
```

**Important:** `ClasspathLoader.load()` expects a text file of JAR paths. For Phase 18, pass the classpath directly as `List<Path>` to `JavaParser.Builder.classpath()` — bypass `ClasspathLoader` for module classpath. Only external JARs need the file-based path. For module-aware extraction, compiled class directories (not JARs) are passed directly.

### Anti-Patterns to Avoid

- **Parsing all modules in a single accumulator:** Each module must have its own fresh accumulator to avoid cross-module class/method deduplication errors. Merge after each module's visit is complete.
- **Single transaction for all modules:** Module N's data must be committed before module N+1 can be persisted. Keep per-module `@Transactional` boundaries.
- **JavaTypeCache shared across modules:** Each module's `JavaSourceParser.parse()` call uses a fresh `new JavaTypeCache()` — already the case since `JavaSourceParser` creates a new parser per call. No change needed.
- **Reusing ExtractionService.extract() for modules:** The existing `extract()` method scans ALL .java files under `sourceRoot`. Module-aware path must scan per-module `src/main/java/` directories, not the entire project root.
- **Blocking wave N+1 on wave N's vector indexing:** Vector indexing (Qdrant) is NOT part of the module-aware extraction. It runs separately via `/api/vector/index` as before. Phase 18 only covers Neo4j extraction.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Topological sort | Custom DFS sort | Kahn's BFS (already in `SchedulingService`) | Handles cycles (SCC fallback), already tested |
| Thread pool for wave parallelism | New `ThreadPoolTaskExecutor` | Existing `extractionExecutor` bean | Already bounded, CallerRunsPolicy, named threads |
| SSE event dispatch | New SseEmitter infrastructure | Existing `ExtractionProgressService` | Thread-safe ConcurrentHashMap, cleanup callbacks, 60-min timeout |
| Per-module Neo4j transaction | Custom session management | `@Transactional("neo4jTransactionManager")` on helper method | Matches Phase 10 `IncrementalIndexingService` pattern exactly |
| Cross-module linking | Custom FQN resolution | Existing `LinkingService.linkAllRelationships()` | Already FQN-based Cypher MERGE — works naturally cross-module |

---

## Common Pitfalls

### Pitfall 1: settings.gradle Include Syntax Variations
**What goes wrong:** AdSuite's `settings.gradle` uses both `":adsuite-business"` (with colon) and `"adsuite-distribution"` (without colon), and mixes quoted styles. A regex that only matches `":name"` will miss the colon-less entries.
**Why it happens:** Gradle allows both `include ":subproject"` and `include "subproject"` — both refer to the same module at `<rootDir>/subproject/`.
**How to avoid:** Strip the leading colon from matched names. The module directory is always `<rootDir>/<name>/` regardless of colon in the include.
**Warning signs:** Detected module count < actual directory count in project root.

### Pitfall 2: Module Compile Classes Missing
**What goes wrong:** User hasn't run `./gradlew compileJava` before triggering extraction. The `build/classes/java/main/` directory doesn't exist, so ESMP silently parses with empty classpath, producing degraded type resolution.
**Why it happens:** The classpath strategy requires pre-compiled classes.
**How to avoid:** Check `Files.isDirectory(compiledClassesDir)` before including a module. If missing: add to `skippedModules` list with reason `"compiled classes not found"`, emit SSE warning event, continue with other modules.
**Warning signs:** Module has source files but zero classpath entries.

### Pitfall 3: ExtractionService.extract() @Transactional Scope
**What goes wrong:** The existing `extract()` is `@Transactional("neo4jTransactionManager")`. If module-aware orchestration wraps all modules in the same transaction, SDN session cache will accumulate all N modules' nodes in memory, causing OOM for 18K-file codebases.
**Why it happens:** SDN (Spring Data Neo4j) session cache holds all loaded entities until transaction commit.
**How to avoid:** Module-aware orchestrator must NOT be `@Transactional`. Each module calls a separate `@Transactional` `persistModule()` method that commits after persisting that module. This follows the `IncrementalIndexingService` pattern exactly.
**Warning signs:** Heap pressure grows linearly with module count, OOM at large module.

### Pitfall 4: adsuite-market Source Directory
**What goes wrong:** `adsuite-market` uses `sourceCompatibility = JavaVersion.VERSION_17` and `apply plugin: 'war'`, not the standard `src/main/java` Gradle convention. The war plugin still uses `src/main/java` as the default Java source directory. Verified: `C:/frontoffice/migration/source/AdSuite/adsuite-market/` has standard `src/main/java/` layout.
**Why it happens:** Concern about non-standard source dirs in Gradle war projects.
**How to avoid:** Always resolve source directory as `<moduleDir>/src/main/java/` (standard convention). The war plugin doesn't change this.
**Warning signs:** Zero Java files found for market module.

### Pitfall 5: adsuite-persistent Java 6 Source Compatibility
**What goes wrong:** `adsuite-persistent/build.gradle` declares `sourceCompatibility = JavaVersion.VERSION_1_6` with fork to an external JDK 11 compiler. OpenRewrite's `JavaParser.fromJavaVersion()` defaults to current JVM version (Java 21). This should NOT be a problem — OpenRewrite parses source, not bytecode.
**Why it happens:** Concern that Java 6 source syntax might trip up the Java 21 parser.
**How to avoid:** No special handling needed. Java 21 parser handles Java 6 syntax. The compiled classes in `build/classes/java/main/` are already compiled (possibly with JDK 11 fork) and are valid for classpath consumption.
**Warning signs:** Parse errors in persistent module only.

### Pitfall 6: OpenRewrite Progress Inside parse()
**What goes wrong:** `JavaParser.parse(List<Path>, Path, ExecutionContext)` does not provide file-level progress callbacks. The `InMemoryExecutionContext` only receives error notifications.
**Why it happens:** OpenRewrite design — parse is a streaming operation.
**How to avoid:** Two options (Claude's discretion):
  1. Wrap the source file list in a `ProgressTrackingList` that calls `sendProgress()` as each element is consumed — requires implementing a custom `Iterable<Path>` that tracks access.
  2. Send a single "PARSING started" event before `parse()` and a "PARSING complete" event after — simpler but less granular.
  Option 2 is recommended for Phase 18 since file-level granularity during parse was marked as a discretionary area, and the CONTEXT.md SSE examples show file count updates that can be produced post-parse.
**Warning signs:** SSE stream has long gaps between PARSING start and complete events for large modules.

### Pitfall 7: Single ExtractionResult Across All Modules
**What goes wrong:** The existing `ExtractionResult` record accumulates counts from a single pass. Module-aware extraction needs to aggregate counts across modules.
**Why it happens:** ExtractionResult was designed for single-shot extraction.
**How to avoid:** Keep a running total `ExtractionResult` or create `ModuleAwareExtractionResult` that includes per-module breakdowns plus totals. The `ExtractionController` response body should include the new fields.
**Warning signs:** Response shows only last module's counts.

---

## Code Examples

Verified patterns from existing code:

### Existing ExtractionService Single-Shot Path (to be preserved as fallback)
```java
// Source: ExtractionService.java lines 151-255
// Pattern to replicate per-module, but scoped to module's src/main/java/
@Transactional("neo4jTransactionManager")
public ExtractionResult extract(String sourceRoot, String classpathFile, String jobId) {
    List<Path> javaPaths = scanJavaFiles(sourceRootPath);  // → per module: scan module src dir
    List<SourceFile> sourceFiles = javaSourceParser.parse(javaPaths, sourceRootPath, resolvedClasspathFile);
    ExtractionAccumulator accumulator = visitInParallel/Sequentially(sourceFiles, errors, jobId);
    // ... map → persist → link → risk → migration
}
```

### JavaSourceParser Called with Compiled Classes Directory (new usage)
```java
// Source: JavaSourceParser.java line 46-89 — accepts List<Path> classpaths via ClasspathLoader
// For module-aware extraction: bypass ClasspathLoader file reading, pass dirs directly to builder
JavaParser.Builder<?, ?> builder = JavaParser.fromJavaVersion()
    .typeCache(new JavaTypeCache())
    .logCompilationWarningsAndErrors(false);

if (!compiledClasspaths.isEmpty()) {
    builder = builder.classpath(compiledClasspaths);  // List<Path> of compiled class dirs
}
```

### SSE Progress Event Extended Format
```java
// Source: ExtractionProgressService.java line 105 — existing record
// Current: record ProgressEvent(String phase, int filesProcessed, int totalFiles)
// New:
public record ProgressEvent(
    String module,         // null for cross-module stages
    String stage,          // PARSING, VISITING, PERSISTING, COMPLETE, LINKING, RISK_SCORING, EXTRACTION_COMPLETE
    int filesProcessed,
    int totalFiles,
    String message,        // optional human-readable detail
    Long durationMs        // non-null for COMPLETE and EXTRACTION_COMPLETE
) {
    // Backward-compat factory for existing sendProgress() call sites
    public static ProgressEvent forPhase(String phase, int processed, int total) {
        return new ProgressEvent(null, phase, processed, total, null, null);
    }
}
```

### Topological Sort (Kahn's BFS — from SchedulingService pattern)
```java
// Source: SchedulingService.java Phase 13 — same algorithm
List<List<String>> computeWaves(Map<String, Set<String>> dependsOn) {
    Map<String, Integer> inDegree = new HashMap<>();
    dependsOn.keySet().forEach(m -> inDegree.putIfAbsent(m, 0));
    dependsOn.forEach((m, deps) ->
        deps.forEach(dep -> inDegree.merge(dep, 1, Integer::sum)));

    List<List<String>> waves = new ArrayList<>();
    while (!inDegree.isEmpty()) {
        List<String> wave = inDegree.entrySet().stream()
            .filter(e -> e.getValue() == 0)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        if (wave.isEmpty()) {
            // cycle: remaining modules assigned to final wave
            waves.add(new ArrayList<>(inDegree.keySet()));
            break;
        }
        waves.add(wave);
        wave.forEach(m -> {
            inDegree.remove(m);
            dependsOn.getOrDefault(m, Set.of()).forEach(dep ->
                inDegree.computeIfPresent(dep, (k, v) -> v - 1));
        });
    }
    return waves;
}
```

### AdSuite Module Dependency Graph (verified from actual build files)
```
Wave 0 (parallel):  adsuite-persistent, adsuite-integration
Wave 1:             adsuite-business (depends on persistent + integration)
Wave 2:             adsuite-market (depends on business, which depends on persistent + integration)

Modules to skip (no meaningful Java source):
  adsuite-distribution  — distribution packaging only (no src/main/java worth parsing)
  adsuite-runtime       — runtime scripts/libs only
  adsuite-salespoint    — verify src/main/java existence before including

File counts (actual):
  adsuite-persistent:  1,597 files
  adsuite-integration: 7,717 files
  adsuite-business:    7,451 files
  adsuite-market:      1,768 files  (Vaadin 7 UI module — parsed last)
  Total:               18,536 files
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Single-shot `parse(allFiles)` with single classpath file | Per-module parse with compiled class directories as classpath | Phase 18 | Accurate type resolution per module; market sees business types |
| Fixed classpath text file | Dynamic classpath from `build/classes/java/main/` directories | Phase 18 | No external classpath file needed; user just runs `gradlew compileJava` |
| Single SSE progress stream | Module-aware SSE with module + stage fields | Phase 18 | Client can render per-module progress bars |
| All-or-nothing extraction | Crash-safe per-module persistence | Phase 18 | Modules persisted up to crash point survive; re-run re-extracts all |

**No deprecated/outdated patterns to address in this phase.**

---

## Open Questions

1. **Source directory convention for non-standard modules (adsuite-salespoint, adsuite-runtime)**
   - What we know: Standard Gradle Java plugin uses `src/main/java`; verified for persistent, integration, business, market
   - What's unclear: Whether salespoint/runtime have `src/main/java` or are purely resource/distribution modules
   - Recommendation: Check `Files.isDirectory(moduleDir.resolve("src/main/java"))` before adding to module list; skip gracefully if absent

2. **Thread pool sizing for wave parallelism vs. partition parallelism within a module**
   - What we know: `extractionExecutor` is core=4, max=availableProcessors. Wave-parallel modules AND partition-parallel batches within a module share this pool.
   - What's unclear: Whether wave parallelism degrades partition parallelism within simultaneous modules (pool starvation)
   - Recommendation: For Phase 18 target scale (AdSuite has at most 2 parallel modules in wave 0), pool starvation is not a concern. Wave 0 has persistent (1,597 files) + integration (7,717 files) running in parallel — each internally uses sequential or parallel partitioning based on parallelThreshold. Consider adding `esmp.extraction.module-parallel-threads` config for future tuning.

3. **ExtractionResult backward compatibility**
   - What we know: `ExtractionController` serializes `ExtractionResult` to JSON response; existing clients may depend on field names
   - What's unclear: Whether to add module breakdown fields to existing record or create a new `ModuleAwareExtractionResult`
   - Recommendation: Create `ModuleAwareExtractionResult` as a separate record with both per-module list AND aggregate totals. `ExtractionController` returns the new type for module-aware mode, existing type for fallback mode — preserves backward compat at HTTP level.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers |
| Config file | `src/test/resources/application-test.yml` |
| Quick run command | `./gradlew test --tests "com.esmp.extraction.*" -x integrationTest` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

Phase 18 has no assigned requirement IDs (TBD), but the behaviors map to:

| Behavior | Test Type | Automated Command |
|----------|-----------|-------------------|
| Gradle `settings.gradle` parsing extracts correct module names | unit | `./gradlew test --tests "ModuleDetectionServiceTest.testGradleModuleDetection"` |
| `build.gradle` project dependency parsing builds correct dep graph | unit | `./gradlew test --tests "ModuleDetectionServiceTest.testGradleDependencyGraph"` |
| Maven `pom.xml` parsing extracts correct module hierarchy | unit | `./gradlew test --tests "ModuleDetectionServiceTest.testMavenModuleDetection"` |
| Topological sort produces correct wave order for known dependency chain | unit | `./gradlew test --tests "ModuleGraphTest.testWaveOrdering"` |
| Missing compiled classes causes module skip, not crash | unit | `./gradlew test --tests "ModuleGraphTest.testMissingCompiledClassesSkip"` |
| No `settings.gradle` falls back to single-shot extraction | integration | `./gradlew test --tests "ModuleAwareExtractionIntegrationTest.testSingleShotFallback"` |
| Module-aware extraction produces same graph as single-shot for small fixture | integration | `./gradlew test --tests "ModuleAwareExtractionIntegrationTest.testModuleAwareVsSingleShot"` |
| SSE events include module name and stage | integration | `./gradlew test --tests "ModuleAwareExtractionIntegrationTest.testProgressEvents"` |
| Cross-module EXTENDS links resolved after final linking pass | integration | `./gradlew test --tests "ModuleAwareExtractionIntegrationTest.testCrossModuleLinking"` |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.extraction.module.*"` (unit tests only)
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/extraction/module/ModuleDetectionServiceTest.java` — unit tests for Gradle/Maven parsing
- [ ] `src/test/java/com/esmp/extraction/module/ModuleGraphTest.java` — topological sort + wave grouping
- [ ] `src/test/java/com/esmp/extraction/application/ModuleAwareExtractionIntegrationTest.java` — full pipeline integration
- [ ] `src/test/resources/fixtures/modules/gradle-multi/settings.gradle` — test fixture mimicking AdSuite structure (2 modules: leaf + dependent)
- [ ] `src/test/resources/fixtures/modules/maven-multi/pom.xml` — Maven multi-module fixture

---

## Sources

### Primary (HIGH confidence)
- `C:/frontoffice/migration/source/AdSuite/settings.gradle` — Module include syntax verified directly
- `C:/frontoffice/migration/source/AdSuite/adsuite-market/build.gradle` — Inter-module project dependencies verified
- `C:/frontoffice/migration/source/AdSuite/adsuite-business/build.gradle` — Inter-module project dependencies verified
- `C:/frontoffice/migration/source/AdSuite/adsuite-persistent/build.gradle` — Leaf module (no project deps), Java 6 source compat
- `C:/frontoffice/migration/source/AdSuite/adsuite-integration/build.gradle` — Leaf module structure
- `C:/frontoffice/esmp/src/main/java/com/esmp/extraction/parser/JavaSourceParser.java` — OpenRewrite parser API usage verified
- `C:/frontoffice/esmp/src/main/java/com/esmp/extraction/application/ExtractionService.java` — Full pipeline verified, transaction boundaries
- `C:/frontoffice/esmp/src/main/java/com/esmp/extraction/application/ExtractionProgressService.java` — SSE infrastructure verified
- `C:/frontoffice/esmp/src/main/java/com/esmp/extraction/config/ExtractionExecutorConfig.java` — Thread pool config verified
- `.planning/phases/18-module-aware-batch-parsing-for-enterprise-scale/18-CONTEXT.md` — User decisions and canonical refs

### Secondary (MEDIUM confidence)
- OpenRewrite `JavaParser.fromJavaVersion().classpath(List<Path>)` — accepts directories not just JARs (confirmed from Phase 2 and Phase 15 implementation pattern)
- Gradle multi-project build convention — `src/main/java` is standard Java plugin default; war plugin does not change it

### Tertiary (LOW confidence — mark for validation)
- OpenRewrite `InMemoryExecutionContext` per-file progress: per-file callbacks not available in openrewrite 8.x (confirmed absent by inspection of existing ExtractionService — no callbacks registered)

---

## Metadata

**Confidence breakdown:**
- Module detection (Gradle regex): HIGH — verified against actual AdSuite settings.gradle and build.gradle files
- Module detection (Maven): MEDIUM — standard DOM parsing of well-known `<modules>` element; no Maven project to test against
- Architecture: HIGH — directly mirrors Phase 10 IncrementalIndexingService and Phase 13 SchedulingService patterns
- Pitfalls: HIGH — discovered by reading actual AdSuite build files and existing ESMP code
- OpenRewrite progress callbacks: MEDIUM — absent from current code, no official callback API found

**Research date:** 2026-03-29
**Valid until:** 2026-06-01 (stable domain — Gradle/Maven build conventions, OpenRewrite API)
