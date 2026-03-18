package com.esmp.scheduling.application;

import com.esmp.scheduling.api.ModuleSchedule;
import com.esmp.scheduling.api.ScheduleResponse;
import com.esmp.scheduling.api.WaveGroup;
import com.esmp.scheduling.config.SchedulingWeightConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Computes risk-prioritized module migration scheduling recommendations.
 *
 * <p>The recommendation pipeline:
 * <ol>
 *   <li>Aggregates per-module metrics from Neo4j (class count, avg risk, avg complexity)
 *   <li>Computes dependent counts (how many other modules depend on each module)
 *   <li>Builds a cross-module dependency graph for topological sorting
 *   <li>Retrieves git commit frequency via {@link GitFrequencyService}
 *   <li>Runs Kahn's BFS topological sort, assigning a wave number to each module
 *   <li>Detects circular dependencies (SCC fallback) and assigns them to the final wave
 *   <li>Computes 4-dimension composite score per module
 *   <li>Assembles {@link ScheduleResponse} with waves and flat ranking
 * </ol>
 *
 * <p>This service is NOT {@code @Transactional} — it is a pure read orchestrator using
 * {@link Neo4jClient} directly, following the same pattern as {@code RagService} and
 * {@code DashboardService}.
 */
@Service
public class SchedulingService {

  private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

  private final Neo4jClient neo4jClient;
  private final GitFrequencyService gitFrequencyService;
  private final SchedulingWeightConfig weightConfig;

  public SchedulingService(
      Neo4jClient neo4jClient,
      GitFrequencyService gitFrequencyService,
      SchedulingWeightConfig weightConfig) {
    this.neo4jClient = neo4jClient;
    this.gitFrequencyService = gitFrequencyService;
    this.weightConfig = weightConfig;
  }

  // ---------------------------------------------------------------------------
  // Private inner record for intermediate module metrics
  // ---------------------------------------------------------------------------

  private record ModuleMetrics(
      String module,
      long classCount,
      double avgEnhancedRisk,
      double avgComplexity,
      double maxComplexity) {}

  // ---------------------------------------------------------------------------
  // Main recommendation method
  // ---------------------------------------------------------------------------

  /**
   * Produces a full migration scheduling recommendation.
   *
   * @param sourceRoot path to the source root for git frequency analysis (may be blank)
   * @return ScheduleResponse with waves and flat ranking ordered by wave then score
   */
  public ScheduleResponse recommend(String sourceRoot) {
    long startMs = System.currentTimeMillis();
    log.info("Starting module scheduling recommendation for sourceRoot='{}'", sourceRoot);

    // Step 1: Aggregate module metrics
    Map<String, ModuleMetrics> metricsMap = aggregateModuleMetrics();
    if (metricsMap.isEmpty()) {
      log.info("No JavaClass nodes found — returning empty recommendation");
      return new ScheduleResponse(
          List.of(), List.of(), Instant.now().toString(), System.currentTimeMillis() - startMs);
    }

    // Step 2: Compute dependent counts per module
    Map<String, Integer> dependentCounts = computeDependentCounts();

    // Step 3: Build dependency graph (source depends on targets)
    Map<String, Set<String>> dependencies = buildDependencyGraph();

    // Step 4: Git frequency
    Map<String, Integer> commitCounts =
        gitFrequencyService.computeModuleCommitCounts(sourceRoot, weightConfig.getGitWindowDays());

    // Step 5: Topological sort (Kahn's BFS) — assigns wave numbers
    Map<String, Integer> moduleWave = topoSort(metricsMap.keySet(), dependencies);

    // Step 6 + 7: Compute composite scores and build ModuleSchedule records
    List<ModuleSchedule> schedules = computeScores(
        metricsMap, dependentCounts, commitCounts, moduleWave);

    // Step 8: Assemble response
    schedules.sort(Comparator.comparingInt(ModuleSchedule::waveNumber)
        .thenComparingDouble(ModuleSchedule::finalScore));

    Map<Integer, List<ModuleSchedule>> byWave = schedules.stream()
        .collect(Collectors.groupingBy(ModuleSchedule::waveNumber));

    List<WaveGroup> waves = byWave.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> new WaveGroup(e.getKey(), e.getValue()))
        .collect(Collectors.toList());

    long durationMs = System.currentTimeMillis() - startMs;
    log.info("Scheduling complete: {} modules in {} waves, {}ms", schedules.size(), waves.size(), durationMs);

    return new ScheduleResponse(waves, schedules, Instant.now().toString(), durationMs);
  }

  // ---------------------------------------------------------------------------
  // Step 1: Aggregate per-module metrics
  // ---------------------------------------------------------------------------

  private Map<String, ModuleMetrics> aggregateModuleMetrics() {
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

    Collection<ModuleMetrics> results = neo4jClient.query(cypher)
        .fetchAs(ModuleMetrics.class)
        .mappedBy((typeSystem, record) -> new ModuleMetrics(
            record.get("module").asString(),
            record.get("classCount").asLong(),
            record.get("avgEnhancedRisk").asDouble(0.0),
            record.get("avgComplexity").asDouble(0.0),
            record.get("maxComplexity").asDouble(0.0)))
        .all();

    Map<String, ModuleMetrics> map = new HashMap<>();
    for (ModuleMetrics m : results) {
      map.put(m.module(), m);
    }
    return map;
  }

  // ---------------------------------------------------------------------------
  // Step 2: Dependent counts (how many modules depend on each module)
  // ---------------------------------------------------------------------------

  private Map<String, Integer> computeDependentCounts() {
    String dependentCypher = """
        MATCH (c1:JavaClass)-[:DEPENDS_ON]->(c2:JavaClass)
        WHERE c1.packageName IS NOT NULL AND c2.packageName IS NOT NULL
        WITH split(c1.packageName, '.')[2] AS sourceModule,
             split(c2.packageName, '.')[2] AS targetModule
        WHERE sourceModule IS NOT NULL AND targetModule IS NOT NULL AND sourceModule <> targetModule
        WITH targetModule AS module, count(DISTINCT sourceModule) AS dependentCount
        RETURN module, dependentCount
        """;

    Collection<Map<String, Object>> rows = neo4jClient.query(dependentCypher)
        .fetch().all();

    Map<String, Integer> counts = new HashMap<>();
    for (Map<String, Object> row : rows) {
      String module = (String) row.get("module");
      Number dependentCount = (Number) row.get("dependentCount");
      if (module != null && dependentCount != null) {
        counts.put(module, dependentCount.intValue());
      }
    }
    return counts;
  }

  // ---------------------------------------------------------------------------
  // Step 3: Build dependency graph (sourceModule depends on targetModules)
  // ---------------------------------------------------------------------------

  private Map<String, Set<String>> buildDependencyGraph() {
    String edgeCypher = """
        MATCH (c1:JavaClass)-[:DEPENDS_ON]->(c2:JavaClass)
        WHERE c1.packageName IS NOT NULL AND c2.packageName IS NOT NULL
        WITH split(c1.packageName, '.')[2] AS sourceModule,
             split(c2.packageName, '.')[2] AS targetModule
        WHERE sourceModule IS NOT NULL AND targetModule IS NOT NULL AND sourceModule <> targetModule
        RETURN DISTINCT sourceModule, targetModule
        """;

    Collection<Map<String, Object>> rows = neo4jClient.query(edgeCypher)
        .fetch().all();

    Map<String, Set<String>> deps = new HashMap<>();
    for (Map<String, Object> row : rows) {
      String source = (String) row.get("sourceModule");
      String target = (String) row.get("targetModule");
      if (source != null && target != null) {
        deps.computeIfAbsent(source, k -> new HashSet<>()).add(target);
      }
    }
    return deps;
  }

  // ---------------------------------------------------------------------------
  // Step 5: Kahn's BFS topological sort with SCC fallback for cycles
  // ---------------------------------------------------------------------------

  /**
   * Assigns a wave number to each module using Kahn's BFS algorithm.
   *
   * <p>A module in wave N depends only on modules in waves 1..N-1. Modules with no dependencies
   * start in wave 1. Circular dependencies (SCCs) are detected as modules not reached by BFS
   * and assigned to {@code maxWave + 1}.
   *
   * @param allModules  all module names to schedule
   * @param dependencies map from sourceModule to the set of modules it depends on
   * @return map from module name to its assigned wave number (1-based)
   */
  private Map<String, Integer> topoSort(
      Set<String> allModules, Map<String, Set<String>> dependencies) {

    // Build in-degree map and reverse adjacency (dependents list)
    Map<String, Integer> inDegree = new HashMap<>();
    Map<String, List<String>> dependents = new HashMap<>(); // target -> list of sources that depend on it

    for (String module : allModules) {
      inDegree.put(module, 0);
      dependents.put(module, new ArrayList<>());
    }

    for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
      String source = entry.getKey();
      if (!allModules.contains(source)) continue;
      for (String target : entry.getValue()) {
        if (!allModules.contains(target)) continue;
        if (source.equals(target)) continue;
        // source depends on target → source's in-degree increases
        inDegree.merge(source, 1, Integer::sum);
        dependents.computeIfAbsent(target, k -> new ArrayList<>()).add(source);
      }
    }

    Map<String, Integer> moduleWave = new HashMap<>();
    Queue<String> queue = new LinkedList<>();

    // Seed with modules that have no dependencies
    for (String module : allModules) {
      if (inDegree.getOrDefault(module, 0) == 0) {
        queue.add(module);
      }
    }

    int currentWave = 1;
    while (!queue.isEmpty()) {
      // Process all modules in the current wave before moving to the next
      int waveSize = queue.size();
      List<String> waveModules = new ArrayList<>();
      for (int i = 0; i < waveSize; i++) {
        waveModules.add(queue.poll());
      }
      for (String module : waveModules) {
        moduleWave.put(module, currentWave);
        // Decrement in-degree of dependents
        for (String dependent : dependents.getOrDefault(module, List.of())) {
          int newDegree = inDegree.merge(dependent, -1, Integer::sum);
          if (newDegree == 0) {
            queue.add(dependent);
          }
        }
      }
      currentWave++;
    }

    // Any module not yet assigned is in a cycle — assign to final wave
    int maxWave = moduleWave.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    int cycleWave = maxWave + 1;
    for (String module : allModules) {
      if (!moduleWave.containsKey(module)) {
        log.warn("Module '{}' is in a circular dependency — assigned to cycle wave {}", module, cycleWave);
        moduleWave.put(module, cycleWave);
      }
    }

    return moduleWave;
  }

  // ---------------------------------------------------------------------------
  // Step 6 + 7: Compute composite scores and build ModuleSchedule records
  // ---------------------------------------------------------------------------

  private List<ModuleSchedule> computeScores(
      Map<String, ModuleMetrics> metricsMap,
      Map<String, Integer> dependentCounts,
      Map<String, Integer> commitCounts,
      Map<String, Integer> moduleWave) {

    // Find normalisation denominators
    int maxDependentCount = dependentCounts.values().stream()
        .mapToInt(Integer::intValue).max().orElse(0);
    int maxCommitCount = commitCounts.values().stream()
        .mapToInt(Integer::intValue).max().orElse(0);
    double maxComplexity = metricsMap.values().stream()
        .mapToDouble(ModuleMetrics::avgComplexity).max().orElse(0.0);

    // Guard: avoid division by zero
    if (maxDependentCount == 0) maxDependentCount = 1;
    if (maxCommitCount == 0) maxCommitCount = 1;

    double logMaxComplexity = Math.log(1 + maxComplexity);

    List<ModuleSchedule> schedules = new ArrayList<>();

    for (Map.Entry<String, ModuleMetrics> entry : metricsMap.entrySet()) {
      String module = entry.getKey();
      ModuleMetrics m = entry.getValue();

      int dependentCount = dependentCounts.getOrDefault(module, 0);
      int commitCount = commitCounts.getOrDefault(module, 0);
      int wave = moduleWave.getOrDefault(module, 1);

      // Normalised dimensions
      double normalizedRisk = m.avgEnhancedRisk(); // already [0,1]
      double normalizedDependency = Math.min(1.0, (double) dependentCount / maxDependentCount);
      double normalizedFrequency = Math.min(1.0, (double) commitCount / maxCommitCount);
      double normalizedComplexity;
      if (logMaxComplexity == 0.0) {
        normalizedComplexity = 0.0;
      } else {
        normalizedComplexity = Math.min(1.0, Math.log(1 + m.avgComplexity()) / logMaxComplexity);
      }

      // Weighted contributions
      double riskContribution = weightConfig.getRisk() * normalizedRisk;
      double dependencyContribution = weightConfig.getDependency() * normalizedDependency;
      double frequencyContribution = weightConfig.getFrequency() * normalizedFrequency;
      double complexityContribution = weightConfig.getComplexity() * normalizedComplexity;
      double finalScore = riskContribution + dependencyContribution
          + frequencyContribution + complexityContribution;

      // Rationale
      String riskLabel = normalizedRisk < 0.3 ? "safe" : (normalizedRisk < 0.6 ? "moderate" : "high");
      int windowMonths = weightConfig.getGitWindowDays() / 30;
      String summary = finalScore < 0.3 ? "safe early target"
          : (finalScore < 0.6 ? "moderate priority" : "complex — schedule later");

      String rationale = String.format(
          "Avg risk %.2f (%s), %d dependents, %d commits/%dmo, avg CC %.1f — %s",
          normalizedRisk, riskLabel, dependentCount, commitCount, windowMonths,
          m.avgComplexity(), summary);

      schedules.add(new ModuleSchedule(
          module, wave, finalScore,
          riskContribution, dependencyContribution, frequencyContribution, complexityContribution,
          dependentCount, commitCount, m.avgComplexity(), m.avgEnhancedRisk(),
          (int) m.classCount(), rationale));
    }

    return schedules;
  }
}
