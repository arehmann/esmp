package com.esmp.migration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.migration.api.MigrationPlan;
import com.esmp.migration.api.RecipeRule;
import com.esmp.mcp.tool.MigrationToolService;
import java.util.List;
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
 * Integration tests for MCP tool layer covering Alfa* migration data exposure.
 *
 * <p>Verifies MCP-ALFA requirements:
 * <ul>
 *   <li>MCP-ALFA-01: getMigrationPlan for Layer 2 class returns hasAlfaIntermediaries=true
 *   <li>MCP-ALFA-02: getRecipeBookGaps(null) returns non-null list (NEEDS_MAPPING entries)
 *   <li>MCP-ALFA-03: getRecipeBookGaps(layer2Fqn) returns non-null list (filtered by class)
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "esmp.migration.custom-recipe-book-path=src/test/resources/fixtures/migration/alfa/alfa-test-overlay.json")
@Testcontainers
class AlfaMcpToolIntegrationTest {

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
  private MigrationRecipeService migrationRecipeService;

  @Autowired
  private MigrationToolService migrationToolService;

  private static final String ALFA_BUTTON_FQN = "com.alfa.ui.AlfaButton";
  private static final String VAADIN_BUTTON_FQN = "com.vaadin.ui.Button";
  private static final String COMPLEX_VIEW_FQN = "com.esmp.migration.alfa.ComplexBusinessView";

  private static boolean setUpDone = false;

  @BeforeEach
  void setupGraph() {
    if (setUpDone) return;
    // Clear graph
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // Create com.vaadin.ui.Button (Layer 0 — Vaadin 7 core)
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'Button', n.packageName = 'com.vaadin.ui',
                n.sourceFilePath = 'com/vaadin/ui/Button.java'
            """)
        .bind(VAADIN_BUTTON_FQN).to("fqn")
        .run();

    // Create com.alfa.ui.AlfaButton (Layer 1 — Alfa* wrapper) EXTENDS Button
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'AlfaButton', n.packageName = 'com.alfa.ui',
                n.sourceFilePath = 'com/alfa/ui/AlfaButton.java'
            WITH n
            MATCH (parent:JavaClass {fullyQualifiedName: $parentFqn})
            MERGE (n)-[:EXTENDS]->(parent)
            """)
        .bind(ALFA_BUTTON_FQN).to("fqn")
        .bind(VAADIN_BUTTON_FQN).to("parentFqn")
        .run();

    // Create ComplexBusinessView (Layer 2) EXTENDS AlfaButton + CALLS AlfaButton
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'ComplexBusinessView', n.packageName = 'com.esmp.migration.alfa',
                n.module = 'alfa',
                n.sourceFilePath = 'com/esmp/migration/alfa/ComplexBusinessView.java'
            WITH n
            MATCH (parent:JavaClass {fullyQualifiedName: $parentFqn})
            MERGE (n)-[:EXTENDS]->(parent)
            """)
        .bind(COMPLEX_VIEW_FQN).to("fqn")
        .bind(ALFA_BUTTON_FQN).to("parentFqn")
        .run();

    // ComplexBusinessView CALLS AlfaButton (contributes ownAlfaCalls)
    neo4jClient.query(
            """
            MATCH (n:JavaClass {fullyQualifiedName: $fqn})
            MATCH (callee:JavaClass {fullyQualifiedName: $calleeFqn})
            MERGE (n)-[:CALLS]->(callee)
            """)
        .bind(COMPLEX_VIEW_FQN).to("fqn")
        .bind(ALFA_BUTTON_FQN).to("calleeFqn")
        .run();

    // Run migration post-processing to create MigrationAction nodes
    migrationRecipeService.migrationPostProcessing();

    setUpDone = true;
  }

  @Test
  @DisplayName("MCP-ALFA-01: getMigrationPlan for Layer 2 class returns hasAlfaIntermediaries=true")
  void getMigrationPlanLayer2HasAlfaFlag() {
    MigrationPlan plan = migrationToolService.getMigrationPlan(COMPLEX_VIEW_FQN);
    assertThat(plan).isNotNull();
    assertThat(plan.hasAlfaIntermediaries()).isTrue();
    assertThat(plan.alfaIntermediaryCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("MCP-ALFA-02: getRecipeBookGaps(null) returns non-null list")
  void getRecipeBookGapsAllReturnsNonNull() {
    List<RecipeRule> gaps = migrationToolService.getRecipeBookGaps(null);
    assertThat(gaps).isNotNull();
    // All returned gaps must have status NEEDS_MAPPING
    gaps.forEach(r -> assertThat(r.status()).isEqualTo("NEEDS_MAPPING"));
  }

  @Test
  @DisplayName("MCP-ALFA-03: getRecipeBookGaps(layer2Fqn) returns non-null filtered list")
  void getRecipeBookGapsFilteredByLayer2ClassIsNonNull() {
    List<RecipeRule> gaps = migrationToolService.getRecipeBookGaps(COMPLEX_VIEW_FQN);
    // The call must not throw and must return a non-null list
    assertThat(gaps).isNotNull();
    // If the class has NEEDS_MAPPING action sources, all returned gaps must be NEEDS_MAPPING
    if (!gaps.isEmpty()) {
      gaps.forEach(r -> assertThat(r.status()).isEqualTo("NEEDS_MAPPING"));
    }
  }
}
