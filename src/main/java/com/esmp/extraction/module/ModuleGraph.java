package com.esmp.extraction.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a topologically sorted list of module waves using Kahn's BFS algorithm.
 *
 * <p>A "wave" is a set of modules whose inter-module dependencies are all satisfied by modules in
 * earlier waves. Modules within the same wave have no mutual dependencies and can therefore be
 * parsed in parallel.
 *
 * <p>Cycle detection: if the BFS terminates before all modules are assigned a wave, the remaining
 * modules form a circular dependency group and are collected into a single "cycle wave" appended at
 * the end.
 */
public class ModuleGraph {

  private static final Logger log = LoggerFactory.getLogger(ModuleGraph.class);

  private final Map<String, ModuleDescriptor> modules;
  private List<ModuleDetectionResult.SkippedModule> skippedModules = new ArrayList<>();

  /**
   * @param modules map from module name to its descriptor; must only contain valid (non-skipped) modules
   */
  public ModuleGraph(Map<String, ModuleDescriptor> modules) {
    this.modules = new HashMap<>(modules);
  }

  public void setSkippedModules(List<ModuleDetectionResult.SkippedModule> skippedModules) {
    this.skippedModules = skippedModules;
  }

  public List<ModuleDetectionResult.SkippedModule> getSkippedModules() {
    return skippedModules;
  }

  /**
   * Computes topologically sorted waves of modules.
   *
   * <p>Wave 0 contains all modules with no inter-module dependencies (leaf modules). Each
   * subsequent wave contains modules whose dependencies all appear in earlier waves. If circular
   * dependencies exist, those modules are collected into a final "cycle wave".
   *
   * @return immutable list of waves; each wave is a list of {@link ModuleDescriptor} that can be
   *         processed in parallel
   */
  public List<List<ModuleDescriptor>> computeWaves() {
    if (modules.isEmpty()) {
      return List.of();
    }

    // Build in-degree map and reverse adjacency (dependents list)
    Map<String, Integer> inDegree = new HashMap<>();
    Map<String, List<String>> dependents = new HashMap<>(); // target -> sources that depend on it

    for (String name : modules.keySet()) {
      inDegree.put(name, 0);
      dependents.put(name, new ArrayList<>());
    }

    for (ModuleDescriptor desc : modules.values()) {
      for (String dep : desc.dependsOn()) {
        if (!modules.containsKey(dep)) continue; // ignore external / unrecognized deps
        if (dep.equals(desc.name())) continue;   // ignore self-loops
        // desc.name() depends on dep → desc.name()'s in-degree increases
        inDegree.merge(desc.name(), 1, Integer::sum);
        dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(desc.name());
      }
    }

    List<List<ModuleDescriptor>> waves = new ArrayList<>();
    Queue<String> queue = new LinkedList<>();

    // Seed: modules with no unresolved dependencies
    for (String name : modules.keySet()) {
      if (inDegree.getOrDefault(name, 0) == 0) {
        queue.add(name);
      }
    }

    while (!queue.isEmpty()) {
      int waveSize = queue.size();
      List<ModuleDescriptor> wave = new ArrayList<>(waveSize);
      for (int i = 0; i < waveSize; i++) {
        String name = queue.poll();
        wave.add(modules.get(name));
        // Decrement in-degree for all modules that depend on this one
        for (String dependent : dependents.getOrDefault(name, List.of())) {
          int newDegree = inDegree.merge(dependent, -1, Integer::sum);
          if (newDegree == 0) {
            queue.add(dependent);
          }
        }
      }
      waves.add(wave);
    }

    // Any module still with inDegree > 0 is part of a cycle
    List<ModuleDescriptor> cycleWave = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() > 0) {
        log.warn("Module '{}' is part of a circular dependency — assigning to cycle wave", entry.getKey());
        cycleWave.add(modules.get(entry.getKey()));
      }
    }
    if (!cycleWave.isEmpty()) {
      waves.add(cycleWave);
    }

    return waves;
  }
}
