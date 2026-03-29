package com.esmp.extraction.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ModuleGraph} topological sort and wave grouping.
 */
class ModuleGraphTest {

  private static ModuleDescriptor desc(String name, List<String> deps) {
    return new ModuleDescriptor(name, Path.of("/project/" + name + "/src/main/java"),
        Path.of("/project/" + name + "/build/classes/java/main"), deps, List.of());
  }

  @Test
  void testEmptyGraph() {
    ModuleGraph graph = new ModuleGraph(Map.of());
    List<List<ModuleDescriptor>> waves = graph.computeWaves();
    assertThat(waves).isEmpty();
  }

  @Test
  void testLinearChain() {
    // a has no deps, b depends on a, c depends on b
    Map<String, ModuleDescriptor> modules = Map.of(
        "module-a", desc("module-a", List.of()),
        "module-b", desc("module-b", List.of("module-a")),
        "module-c", desc("module-c", List.of("module-b"))
    );
    ModuleGraph graph = new ModuleGraph(modules);
    List<List<ModuleDescriptor>> waves = graph.computeWaves();

    assertThat(waves).hasSize(3);
    assertThat(waves.get(0)).extracting(ModuleDescriptor::name).containsExactly("module-a");
    assertThat(waves.get(1)).extracting(ModuleDescriptor::name).containsExactly("module-b");
    assertThat(waves.get(2)).extracting(ModuleDescriptor::name).containsExactly("module-c");
  }

  @Test
  void testParallelLeaves() {
    // a and b are independent (no deps between them)
    Map<String, ModuleDescriptor> modules = Map.of(
        "module-a", desc("module-a", List.of()),
        "module-b", desc("module-b", List.of())
    );
    ModuleGraph graph = new ModuleGraph(modules);
    List<List<ModuleDescriptor>> waves = graph.computeWaves();

    assertThat(waves).hasSize(1);
    assertThat(waves.get(0)).extracting(ModuleDescriptor::name)
        .containsExactlyInAnyOrder("module-a", "module-b");
  }

  @Test
  void testDiamondDeps() {
    // a and b are independent; c depends on both
    Map<String, ModuleDescriptor> modules = Map.of(
        "module-a", desc("module-a", List.of()),
        "module-b", desc("module-b", List.of()),
        "module-c", desc("module-c", List.of("module-a", "module-b"))
    );
    ModuleGraph graph = new ModuleGraph(modules);
    List<List<ModuleDescriptor>> waves = graph.computeWaves();

    assertThat(waves).hasSize(2);
    assertThat(waves.get(0)).extracting(ModuleDescriptor::name)
        .containsExactlyInAnyOrder("module-a", "module-b");
    assertThat(waves.get(1)).extracting(ModuleDescriptor::name).containsExactly("module-c");
  }

  @Test
  void testCycleDetection() {
    // a depends on b, b depends on a — circular
    Map<String, ModuleDescriptor> modules = Map.of(
        "module-a", desc("module-a", List.of("module-b")),
        "module-b", desc("module-b", List.of("module-a"))
    );
    ModuleGraph graph = new ModuleGraph(modules);
    List<List<ModuleDescriptor>> waves = graph.computeWaves();

    // Both should appear in a single "cycle wave" — the BFS cannot resolve them
    Set<String> allInWaves = waves.stream()
        .flatMap(List::stream)
        .map(ModuleDescriptor::name)
        .collect(Collectors.toSet());
    assertThat(allInWaves).containsExactlyInAnyOrder("module-a", "module-b");
    // They must share the same final wave (cycle fallback)
    assertThat(waves).hasSize(1);
  }

  @Test
  void testSkippedModulesNotInWaves() {
    Map<String, ModuleDescriptor> modules = Map.of(
        "module-a", desc("module-a", List.of())
    );
    ModuleGraph graph = new ModuleGraph(modules);
    List<ModuleDetectionResult.SkippedModule> skipped =
        List.of(new ModuleDetectionResult.SkippedModule("module-skip", "missing src/main/java"));
    graph.setSkippedModules(skipped);

    List<List<ModuleDescriptor>> waves = graph.computeWaves();
    assertThat(waves).hasSize(1);
    assertThat(graph.getSkippedModules()).hasSize(1);
    assertThat(graph.getSkippedModules().get(0).name()).isEqualTo("module-skip");
  }
}
