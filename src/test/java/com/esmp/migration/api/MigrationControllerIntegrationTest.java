package com.esmp.migration.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.esmp.extraction.application.ExtractionService;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
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
 * Integration tests for {@link MigrationController} covering MIG-05 and MIG-06.
 *
 * <p>Tests the 5 REST endpoints:
 * <ul>
 *   <li>GET /api/migration/plan/{fqn} — migration plan retrieval
 *   <li>GET /api/migration/summary — module migration statistics
 *   <li>POST /api/migration/preview/{fqn} — dry-run recipe execution
 *   <li>POST /api/migration/apply/{fqn} — recipe application with disk write
 *   <li>POST /api/migration/apply-module — batch module migration
 * </ul>
 *
 * <p>Uses Testcontainers (Neo4j + MySQL + Qdrant) with migration fixtures populated via
 * ExtractionService before tests run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class MigrationControllerIntegrationTest {

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
  private Neo4jClient neo4jClient;

  @Autowired
  private ExtractionService extractionService;

  private static final String SIMPLE_VIEW_FQN = "com.example.migration.SimpleVaadinView";
  private static final String MODULE = "migration";

  /** One-time setup guard — extraction runs only before the first test. */
  private static boolean extractionDone = false;

  @BeforeEach
  void runExtractionOnce() {
    if (extractionDone) return;
    extractionDone = true;

    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    Path fixturesDir = Path.of("src/test/resources/fixtures/migration");
    String sourceRoot = fixturesDir.toAbsolutePath().toString();

    extractionService.extract(sourceRoot, "");

    // Resolve relative sourceFilePaths to absolute so MigrationRecipeService can read them
    neo4jClient
        .query(
            """
            MATCH (c:JavaClass)
            WHERE c.sourceFilePath IS NOT NULL AND NOT c.sourceFilePath STARTS WITH '/'
              AND NOT c.sourceFilePath CONTAINS ':'
            SET c.sourceFilePath = $prefix + '/' + c.sourceFilePath
            """)
        .bind(sourceRoot)
        .to("prefix")
        .run();
  }

  // ---------------------------------------------------------------------------
  // GET /api/migration/plan/{fqn} — happy path
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("MIG-05: getPlan returns 200 with valid plan for known Vaadin 7 class")
  void getPlan_returnsValidPlan() throws Exception {
    mockMvc.perform(get("/api/migration/plan/" + SIMPLE_VIEW_FQN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.classFqn").value(SIMPLE_VIEW_FQN))
        .andExpect(jsonPath("$.totalActions").isNumber())
        .andExpect(jsonPath("$.automationScore").isNumber())
        .andExpect(jsonPath("$.automatableActions").isArray())
        .andExpect(jsonPath("$.manualActions").isArray());
  }

  // ---------------------------------------------------------------------------
  // GET /api/migration/plan/{fqn} — unknown class returns plan with 0 actions (not 404)
  // because generatePlan always returns a valid plan record; 404 requires explicit null
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("MIG-05: getPlan for unknown class returns 200 with empty plan (0 actions)")
  void getPlan_unknownClass_returnsEmptyPlan() throws Exception {
    // generatePlan returns an empty plan for unknown classes (no HAS_MIGRATION_ACTION edges found)
    mockMvc.perform(get("/api/migration/plan/com.does.not.Exist"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalActions").value(0));
  }

  // ---------------------------------------------------------------------------
  // GET /api/migration/summary — module stats
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("MIG-05: getSummary returns 200 with module stats for populated module")
  void getSummary_returnsModuleStats() throws Exception {
    mockMvc.perform(get("/api/migration/summary").param("module", MODULE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.module").value(MODULE))
        .andExpect(jsonPath("$.totalClasses").isNumber())
        .andExpect(jsonPath("$.totalActions").isNumber());
  }

  @Test
  @DisplayName("MIG-05: getSummary with missing module param returns 400")
  void getSummary_missingModule_returns400() throws Exception {
    mockMvc.perform(get("/api/migration/summary").param("module", "  "))
        .andExpect(status().isBadRequest());
  }

  // ---------------------------------------------------------------------------
  // POST /api/migration/preview/{fqn} — dry-run diff
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("MIG-05: preview returns 200 with diff for Vaadin 7 class")
  void preview_returnsValidDiff() throws Exception {
    mockMvc.perform(post("/api/migration/preview/" + SIMPLE_VIEW_FQN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.classFqn").value(SIMPLE_VIEW_FQN))
        .andExpect(jsonPath("$.hasChanges").isBoolean())
        .andExpect(jsonPath("$.recipesApplied").isNumber());
  }

  // ---------------------------------------------------------------------------
  // POST /api/migration/apply-module — bad input returns 400
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("MIG-05: applyModule with blank module field returns 400")
  void applyModule_missingModule_returns400() throws Exception {
    mockMvc.perform(
            post("/api/migration/apply-module")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"module\": \"\"}"))
        .andExpect(status().isBadRequest());
  }

  // ---------------------------------------------------------------------------
  // POST /api/migration/apply/{fqn} — apply returns 200
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("MIG-06: apply returns 200 with migration result")
  void apply_returnsValidResult() throws Exception {
    mockMvc.perform(post("/api/migration/apply/" + SIMPLE_VIEW_FQN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.classFqn").value(SIMPLE_VIEW_FQN))
        .andExpect(jsonPath("$.hasChanges").isBoolean());
  }
}
