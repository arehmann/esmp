package com.esmp.migration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.config.MigrationConfig;
import com.esmp.migration.api.RecipeRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link RecipeBookRegistry}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Load reads seed file correctly and returns expected rule count
 *   <li>findBySource returns correct rule
 *   <li>Custom overlay replaces base rule by source key
 *   <li>reload() re-reads file without errors
 *   <li>updateAndWrite() persists and re-reads updated list
 *   <li>Missing overlay file does not fail
 *   <li>All base rules have isBase=true; overlay rules have isBase=false
 * </ul>
 */
class RecipeBookRegistryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // =========================================================================
  // Test: load from test fixture
  // =========================================================================

  @Test
  void loadReadsTestFixtureAndReturnsExpectedRuleCount(@TempDir Path tempDir) throws IOException {
    RecipeBookRegistry registry = buildRegistry(tempDir, "test-recipe-book.json", null);

    List<RecipeRule> rules = registry.getRules();
    assertThat(rules).hasSize(5);
  }

  @Test
  void findBySourceReturnsCorrectRule(@TempDir Path tempDir) throws IOException {
    RecipeBookRegistry registry = buildRegistry(tempDir, "test-recipe-book.json", null);

    Optional<RecipeRule> found = registry.findBySource("com.vaadin.ui.TextField");
    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo("TEST-001");
    assertThat(found.get().target()).isEqualTo("com.vaadin.flow.component.textfield.TextField");
    assertThat(found.get().automatable()).isEqualTo("YES");
  }

  @Test
  void findBySourceReturnsEmptyForUnknownFqn(@TempDir Path tempDir) throws IOException {
    RecipeBookRegistry registry = buildRegistry(tempDir, "test-recipe-book.json", null);

    Optional<RecipeRule> notFound = registry.findBySource("com.vaadin.ui.NonExistent");
    assertThat(notFound).isEmpty();
  }

  // =========================================================================
  // Test: base rules have isBase=true
  // =========================================================================

  @Test
  void baseRulesHaveIsBaseTrue(@TempDir Path tempDir) throws IOException {
    RecipeBookRegistry registry = buildRegistry(tempDir, "test-recipe-book.json", null);

    assertThat(registry.getRules()).allMatch(RecipeRule::isBase);
  }

  // =========================================================================
  // Test: overlay replaces base rule by source key
  // =========================================================================

  @Test
  void customOverlayReplacesBaseRuleBySourceKey(@TempDir Path tempDir) throws IOException {
    RecipeBookRegistry registry = buildRegistry(
        tempDir, "test-recipe-book.json", "test-custom-overlay.json");

    List<RecipeRule> rules = registry.getRules();

    // Still 5 rules total (overlay replaces, does not add)
    assertThat(rules).hasSize(5);

    // TextField rule should now point to the custom target
    Optional<RecipeRule> textFieldRule = registry.findBySource("com.vaadin.ui.TextField");
    assertThat(textFieldRule).isPresent();
    assertThat(textFieldRule.get().id()).isEqualTo("CUSTOM-001");
    assertThat(textFieldRule.get().target()).isEqualTo("com.myapp.CustomTextField");
    assertThat(textFieldRule.get().context()).isEqualTo("Custom override");

    // Overlay rule should have isBase=false
    assertThat(textFieldRule.get().isBase()).isFalse();
  }

  @Test
  void overlayRuleHasIsBaseFalse(@TempDir Path tempDir) throws IOException {
    RecipeBookRegistry registry = buildRegistry(
        tempDir, "test-recipe-book.json", "test-custom-overlay.json");

    RecipeRule textField = registry.findBySource("com.vaadin.ui.TextField").orElseThrow();
    assertThat(textField.isBase()).isFalse();

    // Non-overridden rules remain isBase=true
    RecipeRule table = registry.findBySource("com.vaadin.ui.Table").orElseThrow();
    assertThat(table.isBase()).isTrue();
  }

  // =========================================================================
  // Test: missing overlay file does not fail
  // =========================================================================

  @Test
  void missingOverlayFileDoesNotFail(@TempDir Path tempDir) throws IOException {
    MigrationConfig config = new MigrationConfig();
    config.setRecipeBookPath(tempDir.resolve("recipe-book.json").toString());
    config.setCustomRecipeBookPath(tempDir.resolve("nonexistent-overlay.json").toString());

    copyTestFixture("test-recipe-book.json", tempDir.resolve("recipe-book.json"));

    RecipeBookRegistry registry = new RecipeBookRegistry(config, MAPPER);
    registry.load();

    // Should load base rules without error
    assertThat(registry.getRules()).hasSize(5);
  }

  // =========================================================================
  // Test: reload() re-reads the file
  // =========================================================================

  @Test
  void reloadReReadsFileCorrectly(@TempDir Path tempDir) throws IOException {
    Path runtimePath = tempDir.resolve("recipe-book.json");
    copyTestFixture("test-recipe-book.json", runtimePath);

    MigrationConfig config = new MigrationConfig();
    config.setRecipeBookPath(runtimePath.toString());
    config.setCustomRecipeBookPath("");

    RecipeBookRegistry registry = new RecipeBookRegistry(config, MAPPER);
    registry.load();

    assertThat(registry.getRules()).hasSize(5);

    // reload() should succeed and keep the same count
    registry.reload();
    assertThat(registry.getRules()).hasSize(5);
  }

  // =========================================================================
  // Test: updateAndWrite() persists and re-reads updated list
  // =========================================================================

  @Test
  void updateAndWritePersistsAndReadsBackUpdatedList(@TempDir Path tempDir) throws IOException {
    Path runtimePath = tempDir.resolve("recipe-book.json");
    copyTestFixture("test-recipe-book.json", runtimePath);

    MigrationConfig config = new MigrationConfig();
    config.setRecipeBookPath(runtimePath.toString());
    config.setCustomRecipeBookPath("");

    RecipeBookRegistry registry = new RecipeBookRegistry(config, MAPPER);
    registry.load();

    // Create a modified list with an extra rule
    RecipeRule newRule = new RecipeRule(
        "TEST-EXTRA", "DISCOVERED", "com.vaadin.ui.Extra",
        null, "COMPLEX_REWRITE", "NO", null,
        List.of(), "NEEDS_MAPPING", 0, "2026-03-28", false
    );
    List<RecipeRule> updated = new java.util.ArrayList<>(registry.getRules());
    updated.add(newRule);

    registry.updateAndWrite(updated);

    // In-memory should reflect 6 rules
    assertThat(registry.getRules()).hasSize(6);
    assertThat(registry.findBySource("com.vaadin.ui.Extra")).isPresent();

    // File should also contain 6 rules — reload to verify
    registry.reload();
    assertThat(registry.getRules()).hasSize(6);
  }

  // =========================================================================
  // Test: seed is copied from classpath when runtime file does not exist
  // =========================================================================

  @Test
  void seedIsCopiedFromClasspathWhenRuntimeFileMissing(@TempDir Path tempDir) {
    Path runtimePath = tempDir.resolve("subdir/recipe-book.json");
    assertThat(runtimePath).doesNotExist();

    MigrationConfig config = new MigrationConfig();
    config.setRecipeBookPath(runtimePath.toString());
    config.setCustomRecipeBookPath("");

    RecipeBookRegistry registry = new RecipeBookRegistry(config, MAPPER);
    registry.load();

    // Should have copied seed from classpath (94 rules in seed)
    assertThat(registry.getRules()).hasSizeGreaterThanOrEqualTo(80);
    assertThat(runtimePath).exists();
  }

  // =========================================================================
  // Helper
  // =========================================================================

  /**
   * Builds a registry backed by a temp-dir copy of the named test fixture.
   *
   * @param tempDir     JUnit-provided temp directory
   * @param baseFixture test fixture filename to use as the base recipe book
   * @param overlayFixture test fixture filename to use as overlay, or null for no overlay
   */
  private RecipeBookRegistry buildRegistry(
      Path tempDir, String baseFixture, String overlayFixture) throws IOException {

    Path runtimePath = tempDir.resolve("recipe-book.json");
    copyTestFixture(baseFixture, runtimePath);

    MigrationConfig config = new MigrationConfig();
    config.setRecipeBookPath(runtimePath.toString());

    if (overlayFixture != null) {
      Path overlayPath = tempDir.resolve("overlay.json");
      copyTestFixture(overlayFixture, overlayPath);
      config.setCustomRecipeBookPath(overlayPath.toString());
    } else {
      config.setCustomRecipeBookPath("");
    }

    RecipeBookRegistry registry = new RecipeBookRegistry(config, MAPPER);
    registry.load();
    return registry;
  }

  /**
   * Copies a test resource file from the classpath to a target path.
   */
  private void copyTestFixture(String resourceName, Path target) throws IOException {
    String resourcePath = "/migration/" + resourceName;
    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalArgumentException("Test fixture not found on classpath: " + resourcePath);
      }
      Files.createDirectories(target.getParent());
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
