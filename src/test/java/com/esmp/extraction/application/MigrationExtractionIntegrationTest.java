package com.esmp.extraction.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.persistence.ClassNodeRepository;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
 * Integration test for the migration pattern extraction pipeline.
 *
 * <p>Verifies end-to-end extraction of migration actions from fixture files:
 * <ul>
 *   <li>SimpleVaadinView: 3 auto=YES actions (TextField, Button, VerticalLayout)
 *   <li>ComplexTableView: mix of NO actions (Table, BeanItemContainer, BeanFieldGroup) + YES (Button)
 *   <li>PureServiceClass: javax.servlet imports produce CHANGE_PACKAGE/YES actions
 *   <li>MigrationAction nodes exist in Neo4j with HAS_MIGRATION_ACTION edges
 *   <li>ClassNode migration properties (migrationActionCount, automationScore, etc.) are set
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class MigrationExtractionIntegrationTest {

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
  private Neo4jClient neo4jClient;

  @Autowired
  private ExtractionService extractionService;

  @Autowired
  private ClassNodeRepository classNodeRepository;

  private static boolean extractionDone = false;

  @BeforeEach
  void runExtractionOnce() {
    if (!extractionDone) {
      // Clear the database
      neo4jClient.query("MATCH (n) DETACH DELETE n").run();

      // Find the migration fixtures directory
      Path fixturesDir = Path.of("src/test/resources/fixtures/migration");
      String sourceRoot = fixturesDir.toAbsolutePath().toString();

      // Run full extraction on migration fixtures
      extractionService.extract(sourceRoot, "");
      extractionDone = true;
    }
  }

  // ---------------------------------------------------------------------------
  // MIG-01: SimpleVaadinView should have auto=YES actions and automationScore=1.0
  // ---------------------------------------------------------------------------

  @Test
  void simpleVaadinView_hasMigrationActionsAllYes() {
    var classNode = classNodeRepository.findById("com.example.migration.SimpleVaadinView");
    assertThat(classNode).isPresent();
    assertThat(classNode.get().getMigrationActionCount())
        .as("SimpleVaadinView should have 3 migration actions (TextField, Button, VerticalLayout)")
        .isEqualTo(3);
    assertThat(classNode.get().getAutomatableActionCount())
        .as("All 3 actions should be automatable (YES)")
        .isEqualTo(3);
    assertThat(classNode.get().getAutomationScore())
        .as("AutomationScore should be 1.0 (all YES)")
        .isEqualTo(1.0);
    assertThat(classNode.get().isNeedsAiMigration())
        .as("needsAiMigration should be false (no NO actions)")
        .isFalse();
  }

  // ---------------------------------------------------------------------------
  // MIG-02: ComplexTableView should have needsAiMigration=true and automationScore < 1.0
  // ---------------------------------------------------------------------------

  @Test
  void complexTableView_needsAiMigration() {
    var classNode = classNodeRepository.findById("com.example.migration.ComplexTableView");
    assertThat(classNode).isPresent();
    assertThat(classNode.get().getMigrationActionCount())
        .as("ComplexTableView should have migration actions > 0")
        .isGreaterThan(0);
    assertThat(classNode.get().isNeedsAiMigration())
        .as("needsAiMigration should be true (Table, BeanItemContainer, BeanFieldGroup are NO)")
        .isTrue();
    assertThat(classNode.get().getAutomationScore())
        .as("AutomationScore should be less than 1.0 (has NO actions)")
        .isLessThan(1.0);
  }

  // ---------------------------------------------------------------------------
  // MIG-03: PureServiceClass should have javax.servlet CHANGE_PACKAGE actions (auto=YES)
  // ---------------------------------------------------------------------------

  @Test
  void pureServiceClass_hasJavaxServletMigrationActions() {
    var classNode = classNodeRepository.findById("com.example.migration.PureServiceClass");
    assertThat(classNode).isPresent();
    assertThat(classNode.get().getMigrationActionCount())
        .as("PureServiceClass should have at least 1 migration action (javax.servlet imports)")
        .isGreaterThanOrEqualTo(1);
    assertThat(classNode.get().isNeedsAiMigration())
        .as("needsAiMigration should be false for pure javax.servlet class (all YES)")
        .isFalse();
  }

  // ---------------------------------------------------------------------------
  // MIG-04: MigrationAction nodes exist in Neo4j
  // ---------------------------------------------------------------------------

  @Test
  void migrationActionNodesExistInNeo4j() {
    Long count = neo4jClient.query(
        "MATCH (ma:MigrationAction) RETURN count(ma) AS cnt")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(count)
        .as("Should have MigrationAction nodes in Neo4j after extraction")
        .isGreaterThan(0L);
  }

  // ---------------------------------------------------------------------------
  // MIG-05: HAS_MIGRATION_ACTION edges connect JavaClass to MigrationAction
  // ---------------------------------------------------------------------------

  @Test
  void hasMigrationActionEdgesCreated() {
    Long count = neo4jClient.query("""
        MATCH (c:JavaClass)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        RETURN count(ma) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(count)
        .as("Should have HAS_MIGRATION_ACTION edges from JavaClass to MigrationAction nodes")
        .isGreaterThan(0L);
  }

  // ---------------------------------------------------------------------------
  // MIG-06: Migration action data is correctly stored in Neo4j
  // ---------------------------------------------------------------------------

  @Test
  void migrationActionDataHasCorrectAutomatable() {
    // Check that Table migration has automatable=NO
    Long noCount = neo4jClient.query("""
        MATCH (ma:MigrationAction)
        WHERE ma.source = 'com.vaadin.ui.Table' AND ma.automatable = 'NO'
        RETURN count(ma) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(noCount)
        .as("Should have MigrationAction with source=Table and automatable=NO")
        .isGreaterThan(0L);

    // Check that TextField migration has automatable=YES
    Long yesCount = neo4jClient.query("""
        MATCH (ma:MigrationAction)
        WHERE ma.source = 'com.vaadin.ui.TextField' AND ma.automatable = 'YES'
        RETURN count(ma) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(yesCount)
        .as("Should have MigrationAction with source=TextField and automatable=YES")
        .isGreaterThan(0L);
  }
}
