package com.esmp.migration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.esmp.extraction.application.ExtractionService;
import com.esmp.migration.api.MigrationActionEntry;
import com.esmp.migration.api.MigrationPlan;
import com.esmp.migration.api.ModuleMigrationSummary;
import com.esmp.migration.application.MigrationRecipeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
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

  @Autowired
  private MigrationRecipeService migrationRecipeService;

  @Autowired
  private ObjectMapper objectMapper;

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

  // ---------------------------------------------------------------------------
  // RB-06-01: getMigrationPlan returns migrationSteps for direct actions
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-06-01: getMigrationPlan returns migrationSteps and isInherited=false for direct Vaadin 7 actions")
  void getMigrationPlan_returnsMigrationStepsAndIsInherited() throws Exception {
    // The plan response now contains enriched MigrationActionEntry with migrationSteps
    var result = mockMvc.perform(get("/api/migration/plan/" + SIMPLE_VIEW_FQN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.classFqn").value(SIMPLE_VIEW_FQN))
        .andExpect(jsonPath("$.totalActions").isNumber())
        // Each action should have the isInherited field
        .andExpect(jsonPath("$.automatableActions").isArray())
        .andExpect(jsonPath("$.manualActions").isArray())
        .andReturn();

    String body = result.getResponse().getContentAsString();
    MigrationPlan plan = objectMapper.readValue(body, MigrationPlan.class);

    // All direct actions should have isInherited=false and migrationSteps non-null
    List<MigrationActionEntry> allActions = new java.util.ArrayList<>();
    allActions.addAll(plan.automatableActions());
    allActions.addAll(plan.manualActions());

    assertThat(allActions).isNotEmpty()
        .as("SimpleVaadinView should have at least one migration action");

    allActions.forEach(action -> {
      assertThat(action.isInherited())
          .as("Direct actions from extraction should have isInherited=false")
          .isFalse();
      assertThat(action.migrationSteps())
          .as("migrationSteps should never be null (may be empty)")
          .isNotNull();
    });
  }

  // ---------------------------------------------------------------------------
  // RB-06-02: getMigrationPlan returns transitive fields for inherited actions
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-06-02: getMigrationPlan returns isInherited=true and vaadinAncestor for inherited actions")
  void getMigrationPlan_returnsTransitiveFieldsForInheritedActions() throws Exception {
    // Synthesize a transitive MigrationAction via Cypher to simulate inheritance detection
    String inheritedActionFqn = "com.example.migration.TransitiveTestView";
    String ancestorFqn = "com.vaadin.ui.VerticalLayout";

    // Create a class node and an inherited MigrationAction
    neo4jClient.query(
        """
        MERGE (c:JavaClass {fullyQualifiedName: $classFqn})
        SET c.packageName = 'com.example.migration', c.simpleName = 'TransitiveTestView'
        """)
        .bind(inheritedActionFqn).to("classFqn")
        .run();

    neo4jClient.query(
        """
        MERGE (ma:MigrationAction {actionId: $actionId})
        SET ma.classFqn = $classFqn, ma.actionType = 'COMPLEX_REWRITE',
            ma.source = $source, ma.target = 'com.vaadin.flow.component.orderedlayout.VerticalLayout',
            ma.automatable = 'PARTIAL', ma.context = 'Inherited from VerticalLayout (pure wrapper)',
            ma.isInherited = true, ma.pureWrapper = true, ma.transitiveComplexity = 0.0,
            ma.vaadinAncestor = $source, ma.overrideCount = 0, ma.ownVaadinCalls = 0
        WITH ma
        MATCH (c:JavaClass {fullyQualifiedName: $classFqn})
        MERGE (c)-[:HAS_MIGRATION_ACTION]->(ma)
        """)
        .bind(inheritedActionFqn + "#INHERITED#" + ancestorFqn).to("actionId")
        .bind(inheritedActionFqn).to("classFqn")
        .bind(ancestorFqn).to("source")
        .run();

    try {
      var result = mockMvc.perform(get("/api/migration/plan/" + inheritedActionFqn))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.classFqn").value(inheritedActionFqn))
          .andExpect(jsonPath("$.totalActions").value(1))
          .andReturn();

      String body = result.getResponse().getContentAsString();
      MigrationPlan plan = objectMapper.readValue(body, MigrationPlan.class);

      // The inherited action should be in manualActions (automatable=PARTIAL)
      List<MigrationActionEntry> allActions = new java.util.ArrayList<>();
      allActions.addAll(plan.automatableActions());
      allActions.addAll(plan.manualActions());

      assertThat(allActions).hasSize(1);
      MigrationActionEntry inherited = allActions.get(0);

      assertThat(inherited.isInherited())
          .as("Synthesized inherited action should have isInherited=true")
          .isTrue();
      assertThat(inherited.vaadinAncestor())
          .as("vaadinAncestor should be set for inherited actions")
          .isEqualTo(ancestorFqn);
      assertThat(inherited.pureWrapper())
          .as("pureWrapper should be true for this synthesized action")
          .isTrue();
      assertThat(inherited.transitiveComplexity())
          .as("transitiveComplexity should be 0.0 for pure wrapper")
          .isEqualTo(0.0);
    } finally {
      // Cleanup the synthesized data
      neo4jClient.query(
          "MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:HAS_MIGRATION_ACTION]->(ma) "
              + "DETACH DELETE c, ma")
          .bind(inheritedActionFqn).to("fqn")
          .run();
    }
  }

  // ---------------------------------------------------------------------------
  // RB-06-03: getModuleSummary returns coverage fields
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RB-06-03: getModuleSummary returns transitiveClassCount, coverageByType, coverageByUsage, topGaps")
  void getModuleSummary_returnsCoverageFields() throws Exception {
    var result = mockMvc.perform(get("/api/migration/summary").param("module", MODULE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.module").value(MODULE))
        .andExpect(jsonPath("$.transitiveClassCount").isNumber())
        .andExpect(jsonPath("$.coverageByType").isNumber())
        .andExpect(jsonPath("$.coverageByUsage").isNumber())
        .andExpect(jsonPath("$.topGaps").isArray())
        .andReturn();

    String body = result.getResponse().getContentAsString();
    ModuleMigrationSummary summary = objectMapper.readValue(body, ModuleMigrationSummary.class);

    assertThat(summary.coverageByType())
        .as("coverageByType should be between 0.0 and 1.0")
        .isBetween(0.0, 1.0);

    assertThat(summary.coverageByUsage())
        .as("coverageByUsage should be between 0.0 and 1.0")
        .isBetween(0.0, 1.0);

    assertThat(summary.topGaps())
        .as("topGaps should not be null (may be empty if no NEEDS_MAPPING rules)")
        .isNotNull();

    assertThat(summary.transitiveClassCount())
        .as("transitiveClassCount should be >= 0")
        .isGreaterThanOrEqualTo(0);
  }
}
