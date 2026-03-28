package com.esmp.migration.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.migration.application.RecipeBookRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link RecipeBookController} covering RB-05.
 *
 * <p>Tests the 5 recipe book management endpoints:
 * <ul>
 *   <li>GET /api/migration/recipe-book — list all rules with optional filters
 *   <li>GET /api/migration/recipe-book/gaps — NEEDS_MAPPING rules sorted by usageCount
 *   <li>PUT /api/migration/recipe-book/rules/{id} — create/replace custom rule
 *   <li>DELETE /api/migration/recipe-book/rules/{id} — delete custom rule (base rules protected)
 *   <li>POST /api/migration/recipe-book/reload — reload from disk
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class RecipeBookControllerIntegrationTest {

  @Container
  static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:2026.01.4").withoutAuthentication();

  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("esmp")
          .withUsername("esmp")
          .withPassword("esmp-test");

  @Container
  static GenericContainer<?> qdrant =
      new GenericContainer<>("qdrant/qdrant:latest")
          .withExposedPorts(6333, 6334)
          .waitingFor(Wait.forHttp("/healthz").forPort(6333));

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
    registry.add("spring.neo4j.authentication.username", () -> "neo4j");
    registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", mysql::getUsername);
    registry.add("spring.datasource.password", mysql::getPassword);
    registry.add("qdrant.host", qdrant::getHost);
    registry.add("qdrant.port", () -> qdrant.getMappedPort(6334));
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private RecipeBookRegistry recipeBookRegistry;

  @Autowired
  private ObjectMapper objectMapper;

  private static final String CUSTOM_RULE_ID = "CUSTOM-TEST-001";
  private static final String BASE_RULE_ID = "COMP-001";

  /** Cleanup: remove any custom test rule added during tests to leave registry clean. */
  @AfterEach
  void cleanupCustomRule() {
    List<RecipeRule> current = recipeBookRegistry.getRules();
    boolean hasCustom = current.stream().anyMatch(r -> r.id().equals(CUSTOM_RULE_ID));
    if (hasCustom) {
      List<RecipeRule> cleaned = current.stream()
          .filter(r -> !r.id().equals(CUSTOM_RULE_ID))
          .toList();
      try {
        recipeBookRegistry.updateAndWrite(cleaned);
      } catch (Exception e) {
        // Best-effort cleanup
      }
    }
  }

  // ---------------------------------------------------------------------------
  // RB-05-01: GET /api/migration/recipe-book — returns all rules
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-05-01: GET /api/migration/recipe-book returns non-empty list of >= 80 rules")
  void getAllRules_returnsAllRules() throws Exception {
    mockMvc.perform(get("/api/migration/recipe-book"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(80)));
  }

  // ---------------------------------------------------------------------------
  // RB-05-02: GET with category filter returns subset
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-05-02: GET /api/migration/recipe-book?category=COMPONENT returns only COMPONENT rules")
  void getAllRules_withCategoryFilter_returnsSubset() throws Exception {
    var result = mockMvc.perform(get("/api/migration/recipe-book").param("category", "COMPONENT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andReturn();

    String body = result.getResponse().getContentAsString();
    RecipeRule[] rules = objectMapper.readValue(body, RecipeRule[].class);
    assertThat(rules).isNotEmpty();
    assertThat(rules).allSatisfy(r -> assertThat(r.category()).isEqualTo("COMPONENT"));
  }

  // ---------------------------------------------------------------------------
  // RB-05-03: GET /api/migration/recipe-book/gaps — returns NEEDS_MAPPING only
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-05-03: GET /api/migration/recipe-book/gaps returns only NEEDS_MAPPING rules sorted by usageCount desc")
  void getGaps_returnsNeedsMappingOnly() throws Exception {
    // Seed a NEEDS_MAPPING rule to ensure at least one exists
    RecipeRule gapRule = new RecipeRule(
        "DISC-TEST-001", "DISCOVERED", "com.vaadin.ui.UnknownWidget", null,
        "COMPLEX_REWRITE", "NO", "Unmapped type", List.of(),
        "NEEDS_MAPPING", 5, "2026-03-28", false);
    List<RecipeRule> updated = new java.util.ArrayList<>(recipeBookRegistry.getRules());
    updated.removeIf(r -> r.id().equals("DISC-TEST-001"));
    updated.add(gapRule);
    recipeBookRegistry.updateAndWrite(updated);

    try {
      var result = mockMvc.perform(get("/api/migration/recipe-book/gaps"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isArray())
          .andReturn();

      String body = result.getResponse().getContentAsString();
      RecipeRule[] gaps = objectMapper.readValue(body, RecipeRule[].class);
      assertThat(gaps).isNotEmpty();
      assertThat(gaps).allSatisfy(r -> assertThat(r.status()).isEqualTo("NEEDS_MAPPING"));

      // Verify sorted by usageCount descending (if multiple)
      if (gaps.length > 1) {
        for (int i = 0; i < gaps.length - 1; i++) {
          assertThat(gaps[i].usageCount())
              .as("usageCount should be >= next item's usageCount (sorted desc)")
              .isGreaterThanOrEqualTo(gaps[i + 1].usageCount());
        }
      }
    } finally {
      // Remove the test gap rule
      List<RecipeRule> cleaned = recipeBookRegistry.getRules().stream()
          .filter(r -> !r.id().equals("DISC-TEST-001"))
          .toList();
      recipeBookRegistry.updateAndWrite(cleaned);
    }
  }

  // ---------------------------------------------------------------------------
  // RB-05-04: PUT creates a new custom rule
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-05-04: PUT /api/migration/recipe-book/rules/{id} creates a custom rule with isBase=false")
  void upsertRule_createsCustomRule() throws Exception {
    RecipeRule customRule = new RecipeRule(
        CUSTOM_RULE_ID,
        "COMPONENT",
        "com.example.custom.OldComponent",
        "com.vaadin.flow.component.html.Div",
        "CHANGE_TYPE",
        "YES",
        "Replace with plain Div wrapper",
        List.of("Replace OldComponent with Div", "Remove legacy styling"),
        "MAPPED",
        0,
        null,
        false);

    mockMvc.perform(
            put("/api/migration/recipe-book/rules/" + CUSTOM_RULE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(customRule)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(CUSTOM_RULE_ID))
        .andExpect(jsonPath("$.source").value("com.example.custom.OldComponent"))
        .andExpect(jsonPath("$.isBase").value(false));

    // Verify it appears in the rule list
    mockMvc.perform(get("/api/migration/recipe-book"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + CUSTOM_RULE_ID + "')]").exists());
  }

  // ---------------------------------------------------------------------------
  // RB-05-05: DELETE removes custom rule
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-05-05: DELETE /api/migration/recipe-book/rules/{id} removes a custom rule")
  void deleteRule_removesCustomRule() throws Exception {
    // First create the rule via PUT
    RecipeRule customRule = new RecipeRule(
        CUSTOM_RULE_ID,
        "COMPONENT",
        "com.example.toDelete.Widget",
        "com.vaadin.flow.component.html.Span",
        "CHANGE_TYPE",
        "YES",
        null,
        List.of(),
        "MAPPED",
        0,
        null,
        false);

    mockMvc.perform(
            put("/api/migration/recipe-book/rules/" + CUSTOM_RULE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(customRule)))
        .andExpect(status().isOk());

    // Delete it
    mockMvc.perform(delete("/api/migration/recipe-book/rules/" + CUSTOM_RULE_ID))
        .andExpect(status().isNoContent());

    // Verify it no longer appears
    mockMvc.perform(get("/api/migration/recipe-book"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + CUSTOM_RULE_ID + "')]").doesNotExist());
  }

  // ---------------------------------------------------------------------------
  // RB-05-06: DELETE protects base rules
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-05-06: DELETE of a base rule returns 403 Forbidden")
  void deleteRule_baseRule_returns403() throws Exception {
    // COMP-001 is a base seed rule (isBase=true)
    mockMvc.perform(delete("/api/migration/recipe-book/rules/" + BASE_RULE_ID))
        .andExpect(status().isForbidden());

    // Verify the base rule is still present
    mockMvc.perform(get("/api/migration/recipe-book"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + BASE_RULE_ID + "')]").exists());
  }

  // ---------------------------------------------------------------------------
  // RB-05-07: DELETE non-existent rule returns 404
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-05-07: DELETE of a non-existent rule returns 404")
  void deleteRule_notFound_returns404() throws Exception {
    mockMvc.perform(delete("/api/migration/recipe-book/rules/DOES-NOT-EXIST-999"))
        .andExpect(status().isNotFound());
  }

  // ---------------------------------------------------------------------------
  // RB-05-08: POST /api/migration/recipe-book/reload re-reads file
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-05-08: POST /api/migration/recipe-book/reload returns 200 and rules are still loaded")
  void reload_returnsOk() throws Exception {
    int countBefore = recipeBookRegistry.getRules().size();

    mockMvc.perform(post("/api/migration/recipe-book/reload"))
        .andExpect(status().isOk());

    // After reload, rules should still be present
    assertThat(recipeBookRegistry.getRules().size()).isGreaterThanOrEqualTo(80);
    // Count should be same or similar (no data loss on reload)
    assertThat(recipeBookRegistry.getRules().size())
        .as("Rule count after reload should match count before reload")
        .isEqualTo(countBefore);
  }
}
