package com.esmp.extraction.module;

import java.util.List;

/**
 * Result of module detection for a multi-module project.
 *
 * @param buildSystem     the detected build system
 * @param waves           topologically sorted waves of modules; each inner list can be processed in
 *                        parallel (all modules in a wave have their dependencies satisfied by prior waves)
 * @param skippedModules  modules that were detected in build files but skipped due to missing
 *                        source or compiled-class directories
 * @param totalModules    total number of modules found in build files (including skipped)
 * @param totalJavaFiles  total number of {@code .java} files across all non-skipped modules
 */
public record ModuleDetectionResult(
    BuildSystem buildSystem,
    List<List<ModuleDescriptor>> waves,
    List<SkippedModule> skippedModules,
    int totalModules,
    int totalJavaFiles) {

  /**
   * A module that was listed in the build file but could not be used for extraction.
   *
   * @param name   module name
   * @param reason human-readable explanation (e.g. "src/main/java directory not found")
   */
  public record SkippedModule(String name, String reason) {}

  /**
   * Returns {@code true} when the project contains multiple modules with valid source directories.
   * Returns {@code false} for single-module projects or when detection fell back to {@link BuildSystem#NONE}.
   */
  public boolean isMultiModule() {
    return buildSystem != BuildSystem.NONE && !waves.isEmpty();
  }
}
