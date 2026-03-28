package com.esmp.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.esmp.extraction.application.ExtractionService;
import com.esmp.migration.api.MigrationPlan;
import com.esmp.migration.api.MigrationResult;
import com.esmp.migration.api.ModuleMigrationSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the 3 new MCP migration tools in {@link MigrationToolService}.
 *
 * <p>Tests MIG-05 and MIG-06 acceptance criteria:
 * <ul>
 *   <li>{@link MigrationToolService#getMigrationPlan} — returns plan with automation score
 *   <li>{@link MigrationToolService#applyMigrationRecipes} — returns diff without writing to disk
 *   <li>{@link MigrationToolService#getModuleMigrationSummary} — returns module statistics
 * </ul>
 *
 * <p>Uses migration fixtures (com.example.migration package) with Testcontainers (Neo4j + MySQL +
 * Qdrant). ExtractionService populates the Neo4j graph before tests run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class MigrationMcpToolIntegrationTest {

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
  private MigrationToolService migrationToolService;

  @Autowired
  private Neo4jClient neo4jClient;

  @Autowired
  private ExtractionService extractionService;

  private static final String SIMPLE_VIEW_FQN = "com.example.migration.SimpleVaadinView";
  private static final String MODULE = "migration";

  /** Path to migration fixture used for verifying "no disk write" assertion. */
  private static final Path SIMPLE_VIEW_FIXTURE =
      Path.of("src/test/resources/fixtures/migration/SimpleVaadinView.java");

  /** One-time setup guard — extraction runs only before the first test. */
  private static boolean extractionDone = false;

  @BeforeEach
  void runExtractionOnce() throws IOException {
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
  // getMigrationPlan — returns valid plan for Vaadin 7 class
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getMigrationPlan returns valid plan with automationScore > 0 for SimpleVaadinView")
  void getMigrationPlan_returnsValidPlan() {
    MigrationPlan plan = migrationToolService.getMigrationPlan(SIMPLE_VIEW_FQN);

    assertThat(plan).isNotNull();
    assertThat(plan.classFqn()).isEqualTo(SIMPLE_VIEW_FQN);
    assertThat(plan.totalActions())
        .as("SimpleVaadinView should have migration actions")
        .isGreaterThan(0);
    assertThat(plan.automationScore())
        .as("AutomationScore should be > 0 for SimpleVaadinView")
        .isGreaterThan(0.0);
    assertThat(plan.automatableActions())
        .as("Should have automatable actions for Vaadin 7 type mappings")
        .isNotEmpty();
  }

  // ---------------------------------------------------------------------------
  // applyMigrationRecipes — returns diff without writing to disk
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("applyMigrationRecipes returns diff without modifying source file on disk")
  void applyMigrationRecipes_returnsDiffNotWrite() throws IOException {
    // Capture original file content before calling the tool
    String originalContent = Files.readString(SIMPLE_VIEW_FIXTURE.toAbsolutePath());

    MigrationResult result = migrationToolService.applyMigrationRecipes(SIMPLE_VIEW_FQN);

    assertThat(result).isNotNull();
    assertThat(result.classFqn()).isEqualTo(SIMPLE_VIEW_FQN);
    assertThat(result.hasChanges())
        .as("SimpleVaadinView should have automatable changes")
        .isTrue();
    assertThat(result.diff())
        .as("Diff should contain Vaadin 24 import reference")
        .contains("com.vaadin.flow");

    // CRITICAL: verify the original source file was NOT modified on disk
    String fileContentAfter = Files.readString(SIMPLE_VIEW_FIXTURE.toAbsolutePath());
    assertThat(fileContentAfter)
        .as("Source file on disk must NOT be modified by applyMigrationRecipes MCP tool")
        .isEqualTo(originalContent);
  }

  // ---------------------------------------------------------------------------
  // getModuleMigrationSummary — returns module statistics
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getModuleMigrationSummary returns summary with totalClasses > 0")
  void getModuleMigrationSummary_returnsModuleStats() {
    ModuleMigrationSummary summary = migrationToolService.getModuleMigrationSummary(MODULE);

    assertThat(summary).isNotNull();
    assertThat(summary.module()).isEqualTo(MODULE);
    assertThat(summary.totalClasses())
        .as("Module 'migration' should have classes")
        .isGreaterThan(0);
    assertThat(summary.totalActions())
        .as("Module 'migration' should have migration actions")
        .isGreaterThan(0);
  }

  // ---------------------------------------------------------------------------
  // getMigrationPlan — unknown class returns empty plan (not exception)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getMigrationPlan for unknown class returns empty plan without throwing")
  void getMigrationPlan_unknownClass_handlesGracefully() {
    assertThatCode(() -> {
      MigrationPlan plan = migrationToolService.getMigrationPlan("com.does.not.Exist");
      // Should return an empty plan (0 actions), not throw
      assertThat(plan).isNotNull();
      assertThat(plan.totalActions()).isZero();
    }).doesNotThrowAnyException();
  }
}
