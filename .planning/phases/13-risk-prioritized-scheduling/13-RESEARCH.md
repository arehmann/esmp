# Phase 13: Risk-Prioritized Scheduling - Research

**Researched:** 2026-03-18
**Domain:** Module scheduling, topological sort, git log integration, Vaadin 24 UI
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Fresh SchedulingService (not extending PilotService) — PilotService optimizes for pilot selection (small safe modules), scheduling ranks ALL modules for migration order
- Risk-dominant weighting: risk ~0.35, dependency count ~0.25, change frequency ~0.20, complexity ~0.20
- All weights externalized via `@ConfigurationProperties(prefix="esmp.scheduling.weight")` following RiskWeightConfig pattern, defaults match risk-dominant profile
- Full rationale per module: riskContribution, dependencyContribution, frequencyContribution, complexityContribution, finalScore — human-readable breakdown
- ProcessBuilder shelling out to `git log` on the sourceRoot path — no JGit dependency
- Configurable time window via `esmp.scheduling.git-window-days` property, default 180 days (6 months)
- Graceful fallback when git unavailable: Claude's discretion on degradation strategy (zero-weight or error)
- Topological sort + score: group modules into waves/tiers based on cross-module dependency DAG, then rank within each wave by composite score
- Dependencies must be migrated before dependents (wave ordering enforces this)
- Circular dependencies: detect strongly connected components (SCCs), merge into same wave, rank by score within the merged group
- New ScheduleView in Vaadin, integrated into MainLayout sidebar alongside Dashboard and Lexicon
- Both views: wave lane visualization (horizontal lanes per wave with module cards) AND sortable ranked table — toggle between them
- Click module to see CytoscapeGraph dependency visualization (reusing Phase 12 component), highlighting which dependencies are in earlier waves (safe) vs later (risky)
- Score breakdown displayed on module drill-down
- On-demand git frequency: `GET /api/scheduling/recommend?sourceRoot=/path/to/repo` computes git frequency at request time, no persistence needed

### Claude's Discretion
- Whether to build fresh SchedulingService or extend PilotService (recommended: fresh)
- File-to-module mapping strategy for git log output
- Git unavailability fallback strategy
- Direct vs transitive dependency depth for topological sort
- Wave lane visual design and card layout details
- Loading/empty states for the schedule view

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| SCHED-01 | System recommends module migration order based on composite risk score (structural + domain-aware) | SchedulingService Cypher aggregation over enhancedRiskScore per module, ordered lowest→highest risk |
| SCHED-02 | Recommendation accounts for dependency risk, change frequency, and complexity | Topological sort using getModuleDependencyEdges() pattern; git log via ProcessBuilder; complexityMax/complexitySum aggregation from ClassNode |
</phase_requirements>

## Summary

Phase 13 builds a scheduling recommendation layer on top of all prior ESMP risk infrastructure. The core algorithm has two sequential phases: first a topological sort of the module dependency DAG to assign wave numbers (dependencies before dependents), then a composite score rank within each wave using four dimensions: average enhancedRiskScore (~0.35 weight), inbound dependent count (~0.25), git change frequency (~0.20), and average cyclomatic complexity distribution (~0.20). Circular dependencies (SCCs) are detected and merged into the same wave.

The phase adds three new Java packages: `scheduling/application/` (SchedulingService, GitFrequencyService), `scheduling/config/` (SchedulingWeightConfig), and `scheduling/api/` (SchedulingController, response records). The Vaadin UI adds a ScheduleView with two toggle modes: wave lane visualization and sortable table. On module click, the existing CytoscapeGraph component renders the dependency sub-graph with wave-colored nodes. MainLayout gains a third SideNav entry.

All data for Neo4j dimensions is already materialized on ClassNode (enhancedRiskScore, complexitySum, complexityMax, fanIn, fanOut). The git frequency dimension requires a ProcessBuilder call at request time. No new Neo4j node types or edge types are needed. One new ValidationQueryRegistry bean adds 3 phase-specific integrity checks.

**Primary recommendation:** Build SchedulingService as a pure @Service with Neo4jClient aggregation queries (no SDN repositories), a separate GitFrequencyService wrapping ProcessBuilder, and in-memory Kahn's algorithm for topological sort. This matches every established pattern in the codebase.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Data Neo4j (Neo4jClient) | Already on classpath | Module aggregation Cypher queries | Project standard — DashboardService, PilotService all use Neo4jClient directly |
| Java ProcessBuilder | JDK 21 built-in | Shell out to `git log` | Locked decision; no new dependency |
| Vaadin Flow 24.9.12 | Already on classpath | ScheduleView UI | Project standard — all views use Vaadin |
| Jackson ObjectMapper | Already on classpath | Serialize Cytoscape elements JSON for wave graph | Used in DashboardView, same pattern |
| Spring @ConfigurationProperties | Spring Boot 3.5 | Externalize scoring weights | Matches RiskWeightConfig pattern exactly |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Vaadin Grid | 24.9.12 | Sortable ranked table view | Already used in DashboardView and LexiconView |
| Vaadin Button / ToggleButton | 24.9.12 | Toggle between wave view and table view | Match existing ButtonVariant.LUMO_PRIMARY pattern |
| CytoscapeGraph (existing) | Internal | Module dependency drill-down with wave coloring | Reuse from Phase 12 — no changes needed |
| Testcontainers (Neo4j + MySQL + Qdrant) | Already in test deps | Integration tests | Standard test setup from Phase 9 onward |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| ProcessBuilder git log | JGit library | JGit adds ~10MB dep; CONTEXT.md explicitly locks ProcessBuilder |
| In-memory Kahn's algorithm | Neo4j APOC topological sort | APOC is not installed in this project; in-memory is simpler and graph is small (tens of modules) |
| Separate GitFrequencyService class | Inline in SchedulingService | Separation of concerns for testing; git subprocess logic is independently testable |

**Installation:** No new dependencies needed. Everything runs on the existing classpath.

## Architecture Patterns

### Recommended Project Structure
```
src/main/java/com/esmp/scheduling/
├── application/
│   ├── SchedulingService.java        # orchestrates: Neo4j aggregation + git freq + topo sort + score
│   └── GitFrequencyService.java      # ProcessBuilder git log, returns Map<module, commitCount>
├── config/
│   └── SchedulingWeightConfig.java   # @ConfigurationProperties(prefix="esmp.scheduling.weight")
├── api/
│   ├── SchedulingController.java     # GET /api/scheduling/recommend?sourceRoot=...
│   ├── ScheduleResponse.java         # record: waves, flatRanking, generatedAt, durationMs
│   ├── WaveGroup.java                # record: waveNumber, modules (List<ModuleSchedule>)
│   └── ModuleSchedule.java           # record: module, waveNumber, finalScore, riskContribution,
│                                     #         dependencyContribution, frequencyContribution,
│                                     #         complexityContribution, dependentCount,
│                                     #         commitCount, avgComplexity, avgEnhancedRisk, rationale
└── validation/
    └── SchedulingValidationQueryRegistry.java  # 3 validation queries
```

### Pattern 1: SchedulingWeightConfig — @ConfigurationProperties
**What:** Externalized weights for the 4 scoring dimensions, following RiskWeightConfig exactly.
**When to use:** Always — never hardcode weights in SchedulingService.

```java
// Source: RiskWeightConfig pattern (src/main/java/com/esmp/extraction/config/RiskWeightConfig.java)
@Component
@ConfigurationProperties(prefix = "esmp.scheduling.weight")
public class SchedulingWeightConfig {
    private double risk = 0.35;
    private double dependency = 0.25;
    private double frequency = 0.20;
    private double complexity = 0.20;
    // getters + setters
}
```

application.yml addition:
```yaml
esmp:
  scheduling:
    weight:
      risk: 0.35
      dependency: 0.25
      frequency: 0.20
      complexity: 0.20
    git-window-days: 180
```

### Pattern 2: Module Aggregation Cypher — Neo4jClient
**What:** Aggregate per-module metrics from ClassNode properties.
**When to use:** All Neo4j reads in SchedulingService — never use SDN repositories for multi-property aggregation.

```java
// Source: DashboardService.getModuleSummaries() and getRiskClusters() patterns
// (src/main/java/com/esmp/dashboard/application/DashboardService.java)
String cypher = """
    MATCH (c:JavaClass)
    WHERE c.packageName IS NOT NULL AND size(c.packageName) > 0
    WITH split(c.packageName, '.')[2] AS module, c
    WHERE module IS NOT NULL
    WITH module,
         count(c) AS classCount,
         avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgEnhancedRisk,
         avg(coalesce(c.complexitySum, 0.0)) AS avgComplexity,
         max(coalesce(c.complexityMax, 0)) AS maxComplexity
    RETURN module, classCount, avgEnhancedRisk, avgComplexity, maxComplexity
    """;
Collection<Map<String, Object>> rows = neo4jClient.query(cypher).fetch().all();
```

CRITICAL: Module is derived from `split(c.packageName, '.')[2]` — NOT `c.module` which does not exist on ClassNode.

### Pattern 3: Cross-Module Dependency Edges — Reuse DashboardService Query
**What:** Build the module dependency graph for topological sort using the existing DEPENDS_ON aggregation.
**When to use:** SchedulingService needs the module DAG. Call DashboardService or replicate its query.

```java
// Source: DashboardService.getModuleDependencyEdges()
// (src/main/java/com/esmp/dashboard/application/DashboardService.java)
String cypher = """
    MATCH (c1:JavaClass)-[:DEPENDS_ON]->(c2:JavaClass)
    WHERE c1.packageName IS NOT NULL AND c2.packageName IS NOT NULL
    WITH split(c1.packageName, '.')[2] AS sourceModule,
         split(c2.packageName, '.')[2] AS targetModule,
         c1, c2
    WHERE sourceModule IS NOT NULL AND targetModule IS NOT NULL AND sourceModule <> targetModule
    WITH sourceModule, targetModule, count(*) AS edgeWeight
    RETURN sourceModule, targetModule, edgeWeight
    ORDER BY edgeWeight DESC LIMIT 200
    """;
```

The result is the input for topological sort. Dependent count per module = how many other modules have a `targetModule` equal to this module.

### Pattern 4: Topological Sort (Kahn's Algorithm) — In-Memory Java
**What:** Assign wave numbers to modules based on dependency DAG. Dependencies get lower wave numbers. Modules with no dependencies go in wave 1.
**When to use:** After building the module adjacency list from Neo4j dependency edges.

```java
// Kahn's algorithm implementation pattern
// Input: Map<String, Set<String>> dependencies (module -> set of modules it depends on)
// Output: Map<String, Integer> moduleWave
Map<String, Integer> inDegree = new HashMap<>();
Map<String, List<String>> dependents = new HashMap<>(); // who depends on me

// Initialize
for (String module : allModules) {
    inDegree.put(module, 0);
    dependents.put(module, new ArrayList<>());
}
// Build graph
for (ModuleDependencyEdge edge : edges) {
    // sourceModule depends on targetModule → targetModule must come first
    inDegree.merge(edge.sourceModule(), 1, Integer::sum);
    dependents.get(edge.targetModule()).add(edge.sourceModule());
}
// BFS by wave
Queue<String> queue = new ArrayDeque<>();
allModules.stream().filter(m -> inDegree.get(m) == 0).forEach(queue::add);
int wave = 1;
while (!queue.isEmpty()) {
    List<String> currentWave = new ArrayList<>(queue);
    queue.clear();
    for (String m : currentWave) {
        moduleWave.put(m, wave);
        for (String dependent : dependents.get(m)) {
            if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                queue.add(dependent);
            }
        }
    }
    wave++;
}
```

### Pattern 5: SCC Detection for Circular Dependencies
**What:** Before Kahn's algorithm, detect SCCs using Tarjan's or Kosaraju's algorithm. Treat all nodes in an SCC as the same virtual module (assigned same wave number).
**When to use:** Always run SCC detection before topological sort to handle circular deps gracefully.

Simple approach: if after Kahn's algorithm some modules have no wave assignment, they are in a cycle — assign them to wave `maxWave + 1` and include a note in their rationale: "Circular dependency detected — assigned to last wave."

This is simpler than full Tarjan's and sufficient for the small number of modules expected.

### Pattern 6: GitFrequencyService — ProcessBuilder
**What:** Shell out to `git log` to count commits touching files in a given module over the configured window.
**When to use:** Called once per `recommend` request. Never persisted.

```java
// ProcessBuilder pattern for git log
// Source: CONTEXT.md locked decision
public Map<String, Integer> computeModuleCommitCounts(String sourceRoot, int windowDays) {
    LocalDate cutoff = LocalDate.now().minusDays(windowDays);
    String since = cutoff.toString(); // "2025-09-18"

    List<String> command = List.of(
        "git", "log",
        "--since=" + since,
        "--name-only",         // list changed files per commit
        "--pretty=format:",    // suppress commit header lines
        "--"                   // end of options
    );

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(new File(sourceRoot));
    pb.redirectErrorStream(true);

    Process proc = pb.start();
    // read stdout, count file occurrences per module
    // file path: src/main/java/com/esmp/{module}/...
    // module = path.split("/")[4]  (index 4 in "src/main/java/com/esmp/{module}/...")
}
```

File-to-module mapping: parse the file path from `git log --name-only` output. Split on `/` and take element at index 4 (after `src/main/java/com/esmp/`). This is the same derivation as `split(packageName, '.')[2]` in Cypher. Filter out blank lines and lines not matching `src/main/java/com/esmp/` prefix.

**Fallback strategy when git unavailable:** Return an empty map (all modules get commitCount=0). Log a WARN. The frequency dimension contributes 0 to all scores — this is the zero-weight approach. The recommendation still produces a valid ordered list based on the remaining 3 dimensions. This is preferable to throwing an error because git may not be initialized in test environments.

### Pattern 7: Composite Score Formula
**What:** Calculate the final migration safety score per module. Lower score = safer to migrate first.

```
// All sub-scores normalized to [0.0, 1.0] before weighting
normalizedRisk       = avgEnhancedRisk                      // already [0,1] by Phase 7 design
normalizedDependency = min(1.0, dependentCount / maxDependentCount)
normalizedFrequency  = min(1.0, commitCount / maxCommitCount)
normalizedComplexity = min(1.0, log(1 + avgComplexity) / log(1 + maxComplexityInDataset))

finalScore = w_risk * normalizedRisk
           + w_dependency * normalizedDependency
           + w_frequency * normalizedFrequency
           + w_complexity * normalizedComplexity

// Sort ascending: lowest finalScore = safest = migrate first (Wave 1)
// Within each wave: sort ascending by finalScore
```

Rationale string format (from CONTEXT.md specifics):
```
"Avg risk 0.12 (safe), 2 dependents, 45 commits/6mo, moderate complexity (avg CC 3.2) — safe early target"
```

### Pattern 8: ScheduleView Vaadin UI
**What:** New view with toggle between wave lanes and sortable table.
**When to use:** Users navigate to "/schedule" via the SideNav.

UI structure:
- Route: `@Route(value = "schedule", layout = MainLayout.class)`
- Top bar: two Buttons for "Wave View" / "Table View" toggle (Button with LUMO_PRIMARY variant on active)
- Wave View: horizontal FlexLayout per wave number, each containing module "cards" (Div with styled content — module name, score, rationale snippet)
- Table View: Grid<ModuleSchedule> with sortable columns (wave, module, finalScore, dependentCount, commitCount, avgComplexity, avgEnhancedRisk)
- Drill-down: on module card click or row selection click, reveal a Details panel below with CytoscapeGraph showing cross-module dependencies, nodes colored by wave (green=earlier, red=later)
- MainLayout: add third SideNavItem for ScheduleView with VaadinIcon.CALENDAR.create()

CytoscapeGraph node color encoding for wave drill-down:
```java
// Build elements JSON with wave-aware coloring
// node color data field: wave < selectedWave → "#4CAF50" (green/safe), wave > selectedWave → "#F44336" (red/risky), same → "#2196F3" (blue/current)
```

### Anti-Patterns to Avoid
- **Using `c.module` property in Cypher:** Does not exist on ClassNode. Always use `split(c.packageName, '.')[2]`. Established in Phase 12.
- **Wrapping SchedulingService in @Transactional:** RagService and DashboardService are NOT @Transactional — pure read orchestrators. Same applies here.
- **Blocking the Vaadin UI thread on git subprocess:** ProcessBuilder call can take seconds on large repos. Wrap in `UI.getCurrent().access()` if called asynchronously, or call synchronously at request time (REST endpoint handles this naturally; Vaadin view should show loading indicator).
- **Persisting git frequency data to Neo4j:** Locked decision: on-demand only, no persistence.
- **Hardcoding wave count:** Wave count is derived from the graph topology — it varies per project.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Cytoscape graph rendering | Custom D3 or SVG rendering | Existing CytoscapeGraph component | Already battle-tested with WeakMap bridge, node-click events, pendingData queue |
| Module aggregation queries | Java-side grouping after raw Neo4j rows | Neo4jClient Cypher aggregation | Established DashboardService pattern; push work to graph engine |
| Weight configuration | Hardcoded constants in SchedulingService | SchedulingWeightConfig @ConfigurationProperties | Same as RiskWeightConfig — allows per-deployment tuning |
| Topological sort library | Apache Commons Graph or JGraphT | Simple in-memory Kahn's algorithm | Graph is tiny (10-100 modules); no library dependency needed |

**Key insight:** All the hard data (risk scores, dependency edges, complexity metrics) is already pre-computed on ClassNode nodes in Neo4j. This phase is primarily orchestration and presentation — avoid re-implementing anything already done.

## Common Pitfalls

### Pitfall 1: Module Name Derivation Inconsistency
**What goes wrong:** Using `c.module` in Cypher returns null/empty because it doesn't exist on ClassNode, leading to all modules being grouped under a null bucket.
**Why it happens:** `module` is a field on CodeChunk (the vector document), not on the JavaClass Neo4j node. ClassNode has `packageName`.
**How to avoid:** Always use `split(c.packageName, '.')[2]` in Cypher. Double-check against DashboardService queries as the reference.
**Warning signs:** Query returns a single null-module bucket containing all classes.

### Pitfall 2: git log Output Includes Blank Lines
**What goes wrong:** Parsing `git log --name-only --pretty=format:` output line-by-line yields many empty strings between commit file lists.
**Why it happens:** git log inserts empty lines as separators between commits.
**How to avoid:** Filter `line.isBlank()` before processing. Filter lines not starting with `src/main/java/com/esmp/`.
**Warning signs:** Module map contains an empty-string key with a very high commit count.

### Pitfall 3: ProcessBuilder Working Directory
**What goes wrong:** `git log` fails or scans the wrong repository because ProcessBuilder's working directory defaults to the JVM process directory.
**Why it happens:** `pb.directory()` defaults to null (JVM working directory). The sourceRoot might be a different path.
**How to avoid:** Always call `pb.directory(new File(sourceRoot))`. Validate that `sourceRoot` is a non-null, non-blank path before calling ProcessBuilder. Return an empty map if sourceRoot is blank.
**Warning signs:** `git` returns "not a git repository" error in stderr.

### Pitfall 4: Division by Zero in Score Normalization
**What goes wrong:** `normalizedDependency = dependentCount / maxDependentCount` throws ArithmeticException or produces NaN when all modules have 0 dependents (leaf-only graphs).
**Why it happens:** max is 0 when no cross-module DEPENDS_ON edges exist.
**How to avoid:** Check `maxDependentCount == 0` and substitute 1.0 (or 0.0) for the entire dimension. Same check for maxCommitCount.
**Warning signs:** NaN or Infinity in finalScore values.

### Pitfall 5: Vaadin UI Deadlock on git Subprocess
**What goes wrong:** Calling ProcessBuilder synchronously inside a Vaadin component constructor or init block freezes the UI thread.
**Why it happens:** The ScheduleView cannot make a REST call to itself; it must call SchedulingService directly. If the view constructor triggers git log, it blocks the HTTP session thread.
**How to avoid:** The ScheduleView should either (a) call a pre-computed REST endpoint and display results, or (b) call SchedulingService via a Button click ("Generate Schedule") rather than on construction. Button-click approach is recommended — mirrors the on-demand nature of the REST endpoint. Add a loading Span between button click and result display.
**Warning signs:** Browser hangs on page load; no timeout.

### Pitfall 6: Circular Dependency Leaves Modules Unassigned
**What goes wrong:** Modules in a cycle are never added to Kahn's queue, so they get no wave assignment, causing a NullPointerException when looking up `moduleWave.get(module)`.
**Why it happens:** Kahn's algorithm only processes nodes with in-degree 0 after prior nodes are removed.
**How to avoid:** After Kahn's BFS, check for any module not in `moduleWave`. Assign these to `maxWave + 1`. Append a note to their rationale string: "Circular dependency detected — assigned to final wave."
**Warning signs:** `moduleWave.size() < allModules.size()` after the algorithm completes.

## Code Examples

Verified patterns from project source:

### Neo4j Module Aggregation (from DashboardService)
```java
// Source: DashboardService.getModuleSummaries() — identical pattern for SchedulingService
Collection<Map<String, Object>> rows = neo4jClient.query("""
    MATCH (c:JavaClass)
    WHERE c.packageName IS NOT NULL AND size(c.packageName) > 0
    WITH split(c.packageName, '.')[2] AS module, c
    WHERE module IS NOT NULL
    WITH module,
         count(c) AS classCount,
         avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgEnhancedRisk,
         avg(coalesce(c.complexitySum, 0.0)) AS avgComplexity
    RETURN module, classCount, avgEnhancedRisk, avgComplexity
    """).fetch().all();
```

### Neo4j Dependent Count per Module
```java
// Count how many other modules depend on each module (inbound dependency count)
// sourceModule depends on targetModule — targetModule has higher dependents
String dependentCypher = """
    MATCH (c1:JavaClass)-[:DEPENDS_ON]->(c2:JavaClass)
    WHERE c1.packageName IS NOT NULL AND c2.packageName IS NOT NULL
    WITH split(c1.packageName, '.')[2] AS sourceModule,
         split(c2.packageName, '.')[2] AS targetModule
    WHERE sourceModule IS NOT NULL AND targetModule IS NOT NULL AND sourceModule <> targetModule
    WITH targetModule AS module, count(DISTINCT sourceModule) AS dependentCount
    RETURN module, dependentCount
    """;
```

### ValidationQueryRegistry Pattern (from PilotValidationQueryRegistry)
```java
// Source: PilotValidationQueryRegistry.java — identical structure for SchedulingValidationQueryRegistry
@Component
public class SchedulingValidationQueryRegistry extends ValidationQueryRegistry {
  public SchedulingValidationQueryRegistry() {
    super(List.of(
        new ValidationQuery(
            "SCHEDULING_MODULES_EXIST",
            "At least one module must exist in the graph for scheduling to produce output",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.packageName IS NOT NULL AND size(c.packageName) > 0
            WITH count(c) AS total
            WHERE total = 0
            RETURN 1 AS count, ['No JavaClass nodes found — run extraction first'] AS details
            """,
            ValidationSeverity.ERROR),
        // ... 2 more queries
    ));
  }
}
```

### CytoscapeGraph Node Color by Wave (from DashboardView pattern)
```java
// Source: DashboardView.java — ObjectMapper + CytoscapeGraph.setGraphData(json) pattern
private String buildWaveGraphJson(List<ModuleSchedule> allModules, String selectedModule, int selectedWave) {
    List<Map<String, Object>> elements = new ArrayList<>();
    for (ModuleSchedule ms : allModules) {
        String color = ms.waveNumber() < selectedWave ? "#4CAF50"
                     : ms.waveNumber() > selectedWave ? "#F44336"
                     : "#2196F3";
        elements.add(Map.of("data", Map.of(
            "id", ms.module(),
            "label", ms.module() + " (W" + ms.waveNumber() + ")",
            "type", "module",
            "color", color)));
    }
    // add edge elements from ModuleDependencyEdge list
    try {
        return OBJECT_MAPPER.writeValueAsString(elements);
    } catch (JsonProcessingException e) {
        return "[]";
    }
}
```

### MainLayout SideNav Addition
```java
// Source: MainLayout.java — add after existing LexiconView SideNavItem
nav.addItem(
    new SideNavItem("Dashboard", DashboardView.class, VaadinIcon.DASHBOARD.create()),
    new SideNavItem("Lexicon", LexiconView.class, VaadinIcon.BOOK.create()),
    new SideNavItem("Schedule", ScheduleView.class, VaadinIcon.CALENDAR.create()));
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| PilotService module scoring (small safe modules) | Fresh SchedulingService (all modules, migration order) | Phase 13 | Two separate concerns — pilot selection vs migration scheduling |
| Manual migration order decisions | Composite 4-dimension scoring + topological sort | Phase 13 | Developer gets data-driven wave assignment |
| DashboardService module edges (display only) | Same DEPENDS_ON edges used as topological sort input | Phase 13 | Existing data repurposed for planning |

**Deprecated/outdated:**
- None — this phase adds new capability, does not replace existing functionality.

## Open Questions

1. **Dependency direction for "dependent count"**
   - What we know: `sourceModule DEPENDS_ON targetModule` means sourceModule needs targetModule to exist first
   - What's unclear: Should "dependentCount" in the score be the count of modules that depend on THIS module (inbound) or the count this module depends on (outbound)?
   - Recommendation: Use INBOUND count (how many modules depend on me). A module with many dependents (high fan-in at module level) is riskier to migrate because many things break if it changes. This aligns with "dependency risk" per SCHED-02.

2. **Transitive vs direct dependency depth for topological sort**
   - What we know: CONTEXT.md marks this as Claude's discretion
   - What's unclear: Using transitive deps could collapse many modules into the same wave; direct deps preserve more wave granularity
   - Recommendation: Use DIRECT dependencies only for wave assignment. This gives more granular waves and matches what DashboardService already computes. Transitive analysis adds complexity for marginal benefit.

3. **ScheduleView on-load vs on-button-click**
   - What we know: git log call happens at request time; large repos can be slow
   - Recommendation: Load on button click ("Generate Schedule" button in the ScheduleView) rather than on page load. Show an empty state until the user triggers it. This avoids blocking the Vaadin UI thread on page construction.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers |
| Config file | None — annotations only (@SpringBootTest, @Testcontainers) |
| Quick run command | `./gradlew test --tests "com.esmp.scheduling.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SCHED-01 | recommendModules() returns ordered list lowest→highest risk | integration | `./gradlew test --tests "com.esmp.scheduling.SchedulingServiceIntegrationTest.testRecommendReturnsOrderedModules"` | Wave 0 |
| SCHED-01 | Rationale string contains all 4 contribution fields | integration | `./gradlew test --tests "com.esmp.scheduling.SchedulingServiceIntegrationTest.testRationaleContainsAllFields"` | Wave 0 |
| SCHED-02 | Wave assignment: dependency must appear in earlier wave than dependent | integration | `./gradlew test --tests "com.esmp.scheduling.SchedulingServiceIntegrationTest.testTopologicalWaveOrdering"` | Wave 0 |
| SCHED-02 | Circular dependency modules assigned to final wave | integration | `./gradlew test --tests "com.esmp.scheduling.SchedulingServiceIntegrationTest.testCircularDependencyFallback"` | Wave 0 |
| SCHED-02 | Git unavailable → zeroes frequency dimension, result still valid | unit | `./gradlew test --tests "com.esmp.scheduling.GitFrequencyServiceTest.testGitUnavailableReturnsEmpty"` | Wave 0 |
| SCHED-02 | git log output parsed correctly — blank lines filtered, module derived | unit | `./gradlew test --tests "com.esmp.scheduling.GitFrequencyServiceTest.testParsesGitLogOutput"` | Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.scheduling.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/scheduling/SchedulingServiceIntegrationTest.java` — covers SCHED-01, SCHED-02 topological ordering and circular dep fallback
- [ ] `src/test/java/com/esmp/scheduling/GitFrequencyServiceTest.java` — unit tests for git log parsing and unavailability fallback

*(Testcontainer setup pattern: copy from DashboardServiceIntegrationTest — Neo4j + MySQL + Qdrant containers. Inject synthetic JavaClass nodes with packageName = "com.esmp.alpha.SomeClass" etc. to produce two modules "alpha" and "beta" with a DEPENDS_ON edge.)*

## Sources

### Primary (HIGH confidence)
- Project source: `DashboardService.java` — module derivation Cypher, `split(c.packageName, '.')[2]` canonical pattern
- Project source: `RiskWeightConfig.java` — @ConfigurationProperties template for SchedulingWeightConfig
- Project source: `PilotService.java` — Neo4jClient `.bind().to()` parameterization pattern, toDouble/toLong helpers
- Project source: `PilotValidationQueryRegistry.java` — ValidationQueryRegistry extension pattern
- Project source: `CytoscapeGraph.java` — setGraphData(json) + addNodeClickListener API
- Project source: `MainLayout.java` — SideNav.addItem() pattern
- Project source: `DashboardView.java` — ObjectMapper + loadSafe pattern, FlexLayout card layout
- Project source: `application.yml` — esmp.* config namespace conventions

### Secondary (MEDIUM confidence)
- Kahn's algorithm for topological sort — standard BFS approach, well-documented algorithm, no library needed
- ProcessBuilder for subprocess — standard Java API, established in project CONTEXT.md

### Tertiary (LOW confidence)
- Tarjan's SCC algorithm — alternative to simple post-Kahn cycle detection; not needed given the simpler fallback approach recommended

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already on classpath, no new deps
- Architecture: HIGH — patterns directly cloned from Phase 9, 11, and 12 implementations
- Pitfalls: HIGH — derived from accumulated decision log in STATE.md (12 phases of decisions)
- Git integration: MEDIUM — ProcessBuilder git log pattern is standard Java but not previously used in this codebase; file-path parsing is brittle if project package structure differs

**Research date:** 2026-03-18
**Valid until:** 2026-04-18 (stable stack — 30 days)
