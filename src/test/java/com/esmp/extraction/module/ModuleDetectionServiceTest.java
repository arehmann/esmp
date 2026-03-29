package com.esmp.extraction.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ModuleDetectionService} covering Gradle and Maven multi-module detection.
 */
class ModuleDetectionServiceTest {

  private final ModuleDetectionService service = new ModuleDetectionService();

  private Path fixtureDir(String name) throws URISyntaxException {
    URL url = getClass().getClassLoader().getResource("fixtures/modules/" + name);
    if (url == null) throw new IllegalStateException("Fixture not found: fixtures/modules/" + name);
    return Path.of(url.toURI());
  }

  // -------------------------------------------------------------------------
  // Gradle tests
  // -------------------------------------------------------------------------

  @Test
  void testGradleModuleDetection() throws Exception {
    Path root = fixtureDir("gradle-multi");
    ModuleDetectionResult result = service.detect(root);

    assertThat(result.buildSystem()).isEqualTo(BuildSystem.GRADLE);
    // 3 modules should be detected from settings.gradle: module-a, module-b, module-c
    int totalModules = result.waves().stream().mapToInt(List::size).sum() + result.skippedModules().size();
    assertThat(totalModules).isEqualTo(3);
  }

  @Test
  void testGradleDependencyGraph() throws Exception {
    Path root = fixtureDir("gradle-multi");
    ModuleDetectionResult result = service.detect(root);

    // Find module-b descriptor — should depend on module-a
    ModuleDescriptor moduleB = result.waves().stream()
        .flatMap(List::stream)
        .filter(d -> d.name().equals("module-b"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("module-b not found in waves"));
    assertThat(moduleB.dependsOn()).contains("module-a");

    // Find module-c descriptor — should depend on module-b and module-a
    ModuleDescriptor moduleC = result.waves().stream()
        .flatMap(List::stream)
        .filter(d -> d.name().equals("module-c"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("module-c not found in waves"));
    assertThat(moduleC.dependsOn()).containsExactlyInAnyOrder("module-b", "module-a");
  }

  @Test
  void testGradleModuleWithExternalDepsOnly() throws Exception {
    Path root = fixtureDir("gradle-multi");
    ModuleDetectionResult result = service.detect(root);

    // module-a only has external deps — dependsOn should be empty
    ModuleDescriptor moduleA = result.waves().stream()
        .flatMap(List::stream)
        .filter(d -> d.name().equals("module-a"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("module-a not found in waves"));
    assertThat(moduleA.dependsOn()).isEmpty();
  }

  @Test
  void testGradleWaveOrdering() throws Exception {
    Path root = fixtureDir("gradle-multi");
    ModuleDetectionResult result = service.detect(root);

    assertThat(result.waves()).isNotEmpty();
    // module-a must appear before module-b, module-b before module-c
    List<String> waveFlat = result.waves().stream()
        .flatMap(List::stream)
        .map(ModuleDescriptor::name)
        .toList();
    int idxA = waveFlat.indexOf("module-a");
    int idxB = waveFlat.indexOf("module-b");
    int idxC = waveFlat.indexOf("module-c");
    assertThat(idxA).isLessThan(idxB);
    assertThat(idxB).isLessThan(idxC);
  }

  // -------------------------------------------------------------------------
  // Maven tests
  // -------------------------------------------------------------------------

  @Test
  void testMavenModuleDetection() throws Exception {
    Path root = fixtureDir("maven-multi");
    ModuleDetectionResult result = service.detect(root);

    assertThat(result.buildSystem()).isEqualTo(BuildSystem.MAVEN);
    int totalModules = result.waves().stream().mapToInt(List::size).sum() + result.skippedModules().size();
    assertThat(totalModules).isEqualTo(2);
  }

  @Test
  void testMavenDependencyGraph() throws Exception {
    Path root = fixtureDir("maven-multi");
    ModuleDetectionResult result = service.detect(root);

    // module-b should depend on module-a
    ModuleDescriptor moduleB = result.waves().stream()
        .flatMap(List::stream)
        .filter(d -> d.name().equals("module-b"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("module-b not found in waves"));
    assertThat(moduleB.dependsOn()).contains("module-a");
  }

  // -------------------------------------------------------------------------
  // Fallback / edge case tests
  // -------------------------------------------------------------------------

  @Test
  void testNoSettingsGradleOrPom(@TempDir Path emptyDir) {
    ModuleDetectionResult result = service.detect(emptyDir);

    assertThat(result.buildSystem()).isEqualTo(BuildSystem.NONE);
    assertThat(result.waves()).isEmpty();
    assertThat(result.totalModules()).isEqualTo(0);
  }

  @Test
  void testMissingSourceDir(@TempDir Path tempRoot) throws IOException {
    // Create a Gradle multi-module project where module-x has no src/main/java
    Path settingsFile = tempRoot.resolve("settings.gradle");
    Files.writeString(settingsFile, "include \":module-x\"\n");
    // module-x directory exists but no src/main/java
    Files.createDirectories(tempRoot.resolve("module-x"));

    ModuleDetectionResult result = service.detect(tempRoot);

    assertThat(result.skippedModules()).hasSize(1);
    assertThat(result.skippedModules().get(0).name()).isEqualTo("module-x");
    assertThat(result.skippedModules().get(0).reason()).contains("src/main/java");
  }

  @Test
  void testMissingCompiledClasses(@TempDir Path tempRoot) throws IOException {
    // Create module-y with src/main/java but no build/classes/java/main
    Path settingsFile = tempRoot.resolve("settings.gradle");
    Files.writeString(settingsFile, "include \":module-y\"\n");
    Files.createDirectories(tempRoot.resolve("module-y/src/main/java"));
    // No build/classes/java/main directory

    ModuleDetectionResult result = service.detect(tempRoot);

    assertThat(result.skippedModules()).hasSize(1);
    assertThat(result.skippedModules().get(0).name()).isEqualTo("module-y");
    assertThat(result.skippedModules().get(0).reason()).containsIgnoringCase("compiled classes");
  }
}
