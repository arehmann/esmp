package com.esmp.migration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.config.MigrationConfig;
import com.esmp.migration.api.RecipeBook;
import com.esmp.migration.api.RecipeRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * Validates the structural integrity of the Alfa* recipe book overlay JSON
 * and verifies that {@link RecipeBookRegistry} auto-loads it at startup.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Overlay contains >= 150 rules
 *   <li>All 10 ALFA_* category strings are present
 *   <li>All rule IDs are non-null
 *   <li>All rule IDs are unique
 *   <li>All source FQNs are unique
 *   <li>4 NEEDS_MAPPING entries are present (AlfaStyloPanel, DTPEditorPanel, AlfaColorChooser, AlfaCalendarWindow)
 *   <li>MAPPED CHANGE_TYPE rules have non-null target
 *   <li>RecipeBookRegistry auto-loads the Alfa* overlay at startup
 *   <li>Alfa* rules loaded by the registry have isBase=false
 * </ul>
 */
class AlfaCatalogOverlayTest {

  private static final String OVERLAY_RESOURCE = "/migration/alfa-recipe-book-overlay.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static List<RecipeRule> rules;

  private static final Set<String> ALL_TEN_ALFA_CATEGORIES = Set.of(
      "ALFA_LAYOUT", "ALFA_TABSHEET", "ALFA_BUTTON", "ALFA_INPUT", "ALFA_DATETIME",
      "ALFA_TABLE", "ALFA_WINDOW", "ALFA_PORTAL", "ALFA_DND", "ALFA_SPECIALIZED"
  );

  @BeforeAll
  static void loadOverlayJson() throws Exception {
    try (InputStream in = AlfaCatalogOverlayTest.class.getResourceAsStream(OVERLAY_RESOURCE)) {
      assertThat(in)
          .as("Alfa* overlay JSON must be present at classpath: " + OVERLAY_RESOURCE)
          .isNotNull();
      RecipeBook book = MAPPER.readValue(in, RecipeBook.class);
      rules = book.rules();
    }
  }

  // =========================================================================
  // Rule count
  // =========================================================================

  @Test
  void overlayContainsAtLeast150Rules() {
    assertThat(rules).hasSizeGreaterThanOrEqualTo(150);
  }

  // =========================================================================
  // Category coverage
  // =========================================================================

  @Test
  void overlayCoversAllTenCategories() {
    Set<String> presentCategories = rules.stream()
        .map(RecipeRule::category)
        .collect(Collectors.toSet());
    assertThat(presentCategories)
        .as("Overlay must contain all 10 ALFA_* categories")
        .containsAll(ALL_TEN_ALFA_CATEGORIES);
  }

  // =========================================================================
  // Required field non-null
  // =========================================================================

  @Test
  void overlayHasNoNullIds() {
    assertThat(rules).allMatch(r -> r.id() != null && !r.id().isBlank(),
        "All overlay rules must have a non-blank id");
  }

  // =========================================================================
  // Unique IDs
  // =========================================================================

  @Test
  void overlayHasNoDuplicateIds() {
    List<String> ids = rules.stream().map(RecipeRule::id).toList();
    Set<String> uniqueIds = Set.copyOf(ids);
    assertThat(uniqueIds).hasSameSizeAs(ids)
        .as("Duplicate rule IDs detected: %s",
            ids.stream().filter(id -> ids.stream().filter(id::equals).count() > 1)
               .collect(Collectors.toSet()));
  }

  // =========================================================================
  // Unique sources
  // =========================================================================

  @Test
  void overlayHasNoDuplicateSources() {
    List<String> sources = rules.stream().map(RecipeRule::source).toList();
    Set<String> uniqueSources = Set.copyOf(sources);
    assertThat(uniqueSources).hasSameSizeAs(sources)
        .as("Duplicate source FQNs detected: %s",
            sources.stream().filter(s -> sources.stream().filter(s::equals).count() > 1)
               .collect(Collectors.toSet()));
  }

  // =========================================================================
  // NEEDS_MAPPING entries
  // =========================================================================

  @Test
  void overlayNeedsMappingEntriesPresent() {
    Set<String> needsMappingSources = rules.stream()
        .filter(r -> "NEEDS_MAPPING".equals(r.status()))
        .map(RecipeRule::source)
        .collect(Collectors.toSet());

    assertThat(needsMappingSources)
        .as("NEEDS_MAPPING entries must include all 4 spike/GWT classes")
        .contains(
            "com.alfa.ui.AlfaStyloPanel",
            "com.alfa.ui.DTPEditorPanel",
            "com.alfa.ui.AlfaColorChooser",
            "com.alfa.ui.AlfaCalendarWindow"
        );
  }

  // =========================================================================
  // MAPPED CHANGE_TYPE rules have non-null target
  // =========================================================================

  @Test
  void overlayMappedChangeTypeRulesHaveNonNullTarget() {
    List<RecipeRule> violations = rules.stream()
        .filter(r -> "MAPPED".equals(r.status()))
        .filter(r -> "CHANGE_TYPE".equals(r.actionType()) || "CHANGE_PACKAGE".equals(r.actionType()))
        .filter(r -> r.target() == null || r.target().isBlank())
        .toList();

    assertThat(violations)
        .as("MAPPED rules with CHANGE_TYPE or CHANGE_PACKAGE must have a non-null target: %s",
            violations.stream().map(RecipeRule::id).collect(Collectors.toList()))
        .isEmpty();
  }

  // =========================================================================
  // Registry auto-loads Alfa* overlay
  // =========================================================================

  @Test
  void registryLoadsAlfaOverlayAutomatically(@TempDir Path tempDir) throws IOException {
    MigrationConfig config = new MigrationConfig();
    config.setRecipeBookPath(tempDir.resolve("recipe-book.json").toString());
    config.setCustomRecipeBookPath("");
    config.setAlfaOverlayPath("classpath:/migration/alfa-recipe-book-overlay.json");

    RecipeBookRegistry registry = new RecipeBookRegistry(config, MAPPER);
    registry.load();

    // Registry should contain seed rules + alfa overlay rules
    assertThat(registry.getRules()).hasSizeGreaterThan(80);

    // AlfaButton rule (ALFA-B-001) must be present
    assertThat(registry.findBySource("com.alfa.ui.AlfaButton"))
        .as("AlfaButton rule must be found after Alfa* overlay is loaded")
        .isPresent();
  }

  // =========================================================================
  // Alfa* rules loaded by registry have isBase=false
  // =========================================================================

  @Test
  void alfaRulesHaveIsBaseFalse(@TempDir Path tempDir) throws IOException {
    MigrationConfig config = new MigrationConfig();
    config.setRecipeBookPath(tempDir.resolve("recipe-book.json").toString());
    config.setCustomRecipeBookPath("");
    config.setAlfaOverlayPath("classpath:/migration/alfa-recipe-book-overlay.json");

    RecipeBookRegistry registry = new RecipeBookRegistry(config, MAPPER);
    registry.load();

    List<RecipeRule> alfaRules = registry.getRules().stream()
        .filter(r -> r.source().startsWith("com.alfa."))
        .toList();

    assertThat(alfaRules)
        .as("All com.alfa.* rules must exist in the loaded registry")
        .isNotEmpty();

    assertThat(alfaRules)
        .as("All com.alfa.* rules must have isBase=false (loaded from overlay, not seed)")
        .allMatch(r -> !r.isBase());
  }
}
