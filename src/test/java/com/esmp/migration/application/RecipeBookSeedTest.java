package com.esmp.migration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.migration.api.RecipeBook;
import com.esmp.migration.api.RecipeRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates the structural integrity of the seed recipe book JSON.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Rule count >= 80
 *   <li>All rules have non-null id, category, source, actionType, automatable, status
 *   <li>All rule IDs are unique
 *   <li>All categories are one of: COMPONENT, DATA_BINDING, SERVER, JAVAX_JAKARTA, DISCOVERED,
 *       EVENT, NAVIGATION, THEME, and all 10 ALFA_* categories
 *   <li>All actionTypes are one of: CHANGE_TYPE, CHANGE_PACKAGE, COMPLEX_REWRITE
 *   <li>All automatable values are one of: YES, PARTIAL, NO
 *   <li>All status values are one of: MAPPED, NEEDS_MAPPING
 *   <li>MAPPED rules with CHANGE_TYPE or CHANGE_PACKAGE have non-null target
 * </ul>
 */
class RecipeBookSeedTest {

  private static final String SEED_RESOURCE = "/migration/vaadin-recipe-book-seed.json";

  private static List<RecipeRule> rules;

  private static final Set<String> VALID_CATEGORIES = Set.of(
      "COMPONENT", "DATA_BINDING", "SERVER", "JAVAX_JAKARTA", "DISCOVERED",
      "EVENT", "NAVIGATION", "THEME",
      "ALFA_LAYOUT", "ALFA_TABSHEET", "ALFA_BUTTON", "ALFA_INPUT", "ALFA_DATETIME",
      "ALFA_TABLE", "ALFA_WINDOW", "ALFA_PORTAL", "ALFA_DND", "ALFA_SPECIALIZED"
  );

  private static final Set<String> VALID_ACTION_TYPES =
      Set.of("CHANGE_TYPE", "CHANGE_PACKAGE", "COMPLEX_REWRITE");

  private static final Set<String> VALID_AUTOMATABLE =
      Set.of("YES", "PARTIAL", "NO");

  private static final Set<String> VALID_STATUS =
      Set.of("MAPPED", "NEEDS_MAPPING");

  @BeforeAll
  static void loadSeedJson() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    try (InputStream in = RecipeBookSeedTest.class.getResourceAsStream(SEED_RESOURCE)) {
      assertThat(in)
          .as("Seed JSON must be present at classpath: " + SEED_RESOURCE)
          .isNotNull();
      RecipeBook book = mapper.readValue(in, RecipeBook.class);
      rules = book.rules();
    }
  }

  // =========================================================================
  // Rule count
  // =========================================================================

  @Test
  void seedContainsAtLeast80Rules() {
    assertThat(rules).hasSizeGreaterThanOrEqualTo(80);
  }

  // =========================================================================
  // Required fields non-null
  // =========================================================================

  @Test
  void allRulesHaveNonNullId() {
    assertThat(rules).allMatch(r -> r.id() != null && !r.id().isBlank(),
        "All rules must have a non-blank id");
  }

  @Test
  void allRulesHaveNonNullCategory() {
    assertThat(rules).allMatch(r -> r.category() != null && !r.category().isBlank(),
        "All rules must have a non-blank category");
  }

  @Test
  void allRulesHaveNonNullSource() {
    assertThat(rules).allMatch(r -> r.source() != null && !r.source().isBlank(),
        "All rules must have a non-blank source FQN");
  }

  @Test
  void allRulesHaveNonNullActionType() {
    assertThat(rules).allMatch(r -> r.actionType() != null && !r.actionType().isBlank(),
        "All rules must have a non-blank actionType");
  }

  @Test
  void allRulesHaveNonNullAutomatable() {
    assertThat(rules).allMatch(r -> r.automatable() != null && !r.automatable().isBlank(),
        "All rules must have a non-blank automatable value");
  }

  @Test
  void allRulesHaveNonNullStatus() {
    assertThat(rules).allMatch(r -> r.status() != null && !r.status().isBlank(),
        "All rules must have a non-blank status");
  }

  // =========================================================================
  // Unique IDs
  // =========================================================================

  @Test
  void allRuleIdsAreUnique() {
    List<String> ids = rules.stream().map(RecipeRule::id).toList();
    Set<String> uniqueIds = Set.copyOf(ids);
    assertThat(uniqueIds).hasSameSizeAs(ids)
        .as("Duplicate rule IDs detected: %s",
            ids.stream().filter(id -> ids.stream().filter(id::equals).count() > 1)
               .collect(Collectors.toSet()));
  }

  // =========================================================================
  // Valid enum values
  // =========================================================================

  @Test
  void allCategoriesAreValid() {
    List<String> invalidCategories = rules.stream()
        .map(RecipeRule::category)
        .filter(c -> !VALID_CATEGORIES.contains(c))
        .distinct()
        .toList();
    assertThat(invalidCategories)
        .as("Invalid categories found: %s. Allowed: %s", invalidCategories, VALID_CATEGORIES)
        .isEmpty();
  }

  @Test
  void allActionTypesAreValid() {
    List<String> invalidTypes = rules.stream()
        .map(RecipeRule::actionType)
        .filter(t -> !VALID_ACTION_TYPES.contains(t))
        .distinct()
        .toList();
    assertThat(invalidTypes)
        .as("Invalid actionTypes found: %s. Allowed: %s", invalidTypes, VALID_ACTION_TYPES)
        .isEmpty();
  }

  @Test
  void allAutomatableValuesAreValid() {
    List<String> invalidValues = rules.stream()
        .map(RecipeRule::automatable)
        .filter(a -> !VALID_AUTOMATABLE.contains(a))
        .distinct()
        .toList();
    assertThat(invalidValues)
        .as("Invalid automatable values found: %s. Allowed: %s", invalidValues, VALID_AUTOMATABLE)
        .isEmpty();
  }

  @Test
  void allStatusValuesAreValid() {
    List<String> invalidStatuses = rules.stream()
        .map(RecipeRule::status)
        .filter(s -> !VALID_STATUS.contains(s))
        .distinct()
        .toList();
    assertThat(invalidStatuses)
        .as("Invalid status values found: %s. Allowed: %s", invalidStatuses, VALID_STATUS)
        .isEmpty();
  }

  // =========================================================================
  // MAPPED rules with type/package actions must have non-null target
  // =========================================================================

  @Test
  void mappedTypeOrPackageRulesHaveNonNullTarget() {
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
  // Spot-check: known key types present
  // =========================================================================

  @Test
  void seedContainsTextFieldRule() {
    assertThat(rules).anyMatch(r -> "com.vaadin.ui.TextField".equals(r.source()));
  }

  @Test
  void seedContainsTableRule() {
    assertThat(rules).anyMatch(r -> "com.vaadin.ui.Table".equals(r.source()));
  }

  @Test
  void seedContainsJavaxServletRule() {
    assertThat(rules).anyMatch(r -> "javax.servlet".equals(r.source()));
  }

  @Test
  void seedContainsJavaxPersistenceRule() {
    assertThat(rules).anyMatch(r -> "javax.persistence".equals(r.source()));
  }
}
