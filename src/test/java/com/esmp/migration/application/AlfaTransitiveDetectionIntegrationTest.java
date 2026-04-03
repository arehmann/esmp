package com.esmp.migration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.migration.api.MigrationPlan;
import java.util.HashMap;
import java.util.Map;
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
 * Integration tests for deep transitive Alfa* detection via EXTENDS graph traversal.
 *
 * <p>Verifies requirements ALFA-02 and ALFA-04:
 * <ul>
 *   <li>ALFA-02a: Layer 2 class inheriting through single Alfa* gets MigrationAction with correct
 *       source (Alfa* FQN) and vaadinAncestor (com.vaadin.* FQN)
 *   <li>ALFA-02b: Multi-hop chain resolves vaadinAncestor to the ultimate com.vaadin.* leaf
 *   <li>ALFA-04a: Pure Layer 2 class (no overrides, no own Alfa calls) gets pureWrapper=true
 *   <li>ALFA-04b: Complex Layer 2 class (has own Alfa* field/call) gets pureWrapper=false and
 *       transitiveComplexity > 0
 *   <li>ALFA-04c: generatePlan() returns non-empty migrationSteps for Layer 2 class from Alfa* rule
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "esmp.migration.custom-recipe-book-path=src/test/resources/fixtures/migration/alfa/alfa-test-overlay.json")
@Testcontainers
class AlfaTransitiveDetectionIntegrationTest {

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

  // --- Class FQN constants ---
  private static final String ALFA_BUTTON_FQN = "com.alfa.ui.AlfaButton";
  private static final String ALFA_TABLE_FQN = "com.alfa.ui.AlfaTable";
  private static final String VAADIN_BUTTON_FQN = "com.vaadin.ui.Button";
  private static final String VAADIN_TABLE_FQN = "com.vaadin.ui.Table";
  private static final String ALFA_BUTTON_WRAPPER_FQN = "com.esmp.migration.alfa.AlfaButtonWrapper";
  private static final String BUSINESS_SERVICE_FQN = "com.esmp.migration.alfa.BusinessServiceImpl";
  private static final String COMPLEX_VIEW_FQN = "com.esmp.migration.alfa.ComplexBusinessView";
  private static final String ALFA_TABLE_EXT_FQN = "com.esmp.migration.alfa.AlfaTableExtension";

  private static boolean setUpDone = false;

  @BeforeEach
  void setupGraph() {
    if (setUpDone) return;

    // Clear graph
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // 1. Create com.vaadin.ui.Button (Layer 0 — Vaadin 7 core)
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'Button', n.packageName = 'com.vaadin.ui',
                n.sourceFilePath = 'com/vaadin/ui/Button.java'
            """)
        .bind(VAADIN_BUTTON_FQN).to("fqn")
        .run();

    // 2. Create com.vaadin.ui.Table (Layer 0 — Vaadin 7 core)
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'Table', n.packageName = 'com.vaadin.ui',
                n.sourceFilePath = 'com/vaadin/ui/Table.java'
            """)
        .bind(VAADIN_TABLE_FQN).to("fqn")
        .run();

    // 3. Create com.alfa.ui.AlfaButton + EXTENDS → Button
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

    // 4. Create com.alfa.ui.AlfaTable + EXTENDS → Table
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'AlfaTable', n.packageName = 'com.alfa.ui',
                n.sourceFilePath = 'com/alfa/ui/AlfaTable.java'
            WITH n
            MATCH (parent:JavaClass {fullyQualifiedName: $parentFqn})
            MERGE (n)-[:EXTENDS]->(parent)
            """)
        .bind(ALFA_TABLE_FQN).to("fqn")
        .bind(VAADIN_TABLE_FQN).to("parentFqn")
        .run();

    // 5. Create AlfaButtonWrapper + EXTENDS → AlfaButton (no own methods — pure passthrough)
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'AlfaButtonWrapper', n.packageName = 'com.esmp.migration.alfa',
                n.sourceFilePath = 'com/esmp/migration/alfa/AlfaButtonWrapper.java'
            WITH n
            MATCH (parent:JavaClass {fullyQualifiedName: $parentFqn})
            MERGE (n)-[:EXTENDS]->(parent)
            """)
        .bind(ALFA_BUTTON_WRAPPER_FQN).to("fqn")
        .bind(ALFA_BUTTON_FQN).to("parentFqn")
        .run();

    // 6. Create BusinessServiceImpl + EXTENDS → AlfaButtonWrapper (pure — no own Alfa/Vaadin usage)
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'BusinessServiceImpl', n.packageName = 'com.esmp.migration.alfa',
                n.sourceFilePath = 'com/esmp/migration/alfa/BusinessServiceImpl.java'
            WITH n
            MATCH (parent:JavaClass {fullyQualifiedName: $parentFqn})
            MERGE (n)-[:EXTENDS]->(parent)
            """)
        .bind(BUSINESS_SERVICE_FQN).to("fqn")
        .bind(ALFA_BUTTON_WRAPPER_FQN).to("parentFqn")
        .run();

    // 7. Create ComplexBusinessView + EXTENDS → AlfaButton + CALLS → AlfaTable
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'ComplexBusinessView', n.packageName = 'com.esmp.migration.alfa',
                n.sourceFilePath = 'com/esmp/migration/alfa/ComplexBusinessView.java'
            WITH n
            MATCH (parent:JavaClass {fullyQualifiedName: $parentFqn})
            MERGE (n)-[:EXTENDS]->(parent)
            """)
        .bind(COMPLEX_VIEW_FQN).to("fqn")
        .bind(ALFA_BUTTON_FQN).to("parentFqn")
        .run();

    // ComplexBusinessView CALLS AlfaTable (simulates new AlfaTable() field usage)
    neo4jClient.query(
            """
            MATCH (n:JavaClass {fullyQualifiedName: $fqn})
            MATCH (callee:JavaClass {fullyQualifiedName: $calleeFqn})
            MERGE (n)-[:CALLS]->(callee)
            """)
        .bind(COMPLEX_VIEW_FQN).to("fqn")
        .bind(ALFA_TABLE_FQN).to("calleeFqn")
        .run();

    // 8. Create AlfaTableExtension + EXTENDS → AlfaTable + declares 'attach' method override
    neo4jClient.query(
            """
            MERGE (n:JavaClass {fullyQualifiedName: $fqn})
            SET n.simpleName = 'AlfaTableExtension', n.packageName = 'com.esmp.migration.alfa',
                n.sourceFilePath = 'com/esmp/migration/alfa/AlfaTableExtension.java'
            WITH n
            MATCH (parent:JavaClass {fullyQualifiedName: $parentFqn})
            MERGE (n)-[:EXTENDS]->(parent)
            """)
        .bind(ALFA_TABLE_EXT_FQN).to("fqn")
        .bind(ALFA_TABLE_FQN).to("parentFqn")
        .run();

    // AlfaTableExtension declares 'attach' method
    neo4jClient.query(
            """
            MATCH (n:JavaClass {fullyQualifiedName: $fqn})
            MERGE (m:JavaMethod {methodId: $fqn + '#attach'})
            SET m.simpleName = 'attach', m.classFqn = $fqn
            MERGE (n)-[:DECLARES_METHOD]->(m)
            """)
        .bind(ALFA_TABLE_EXT_FQN).to("fqn")
        .run();

    // AlfaTable also declares 'attach' so override count > 0 for AlfaTableExtension
    neo4jClient.query(
            """
            MATCH (n:JavaClass {fullyQualifiedName: $fqn})
            MERGE (m:JavaMethod {methodId: $fqn + '#attach'})
            SET m.simpleName = 'attach', m.classFqn = $fqn
            MERGE (n)-[:DECLARES_METHOD]->(m)
            """)
        .bind(ALFA_TABLE_FQN).to("fqn")
        .run();

    setUpDone = true;
  }

  /**
   * ALFA-02a: Layer 2 class inheriting through single Alfa* intermediary gets MigrationAction
   * with source=com.alfa.ui.AlfaButton and vaadinAncestor=com.vaadin.ui.Button.
   */
  @Test
  void layer2Class_throughSingleAlfaIntermediary_getsInheritedAction() {
    migrationRecipeService.migrationPostProcessing();

    // BusinessServiceImpl extends AlfaButtonWrapper extends AlfaButton extends Button
    // Should get a MigrationAction with source=com.alfa.ui.AlfaButton
    long count = neo4jClient.query(
            """
            MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
            WHERE ma.isInherited = true AND ma.source = $source
            RETURN count(ma) AS cnt
            """)
        .bind(BUSINESS_SERVICE_FQN).to("fqn")
        .bind(ALFA_BUTTON_FQN).to("source")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong(0))
        .one().orElse(0L);

    assertThat(count)
        .as("BusinessServiceImpl should have inherited MigrationAction from AlfaButton")
        .isGreaterThanOrEqualTo(1L);

    // vaadinAncestor should resolve to com.vaadin.ui.Button (not com.alfa.ui.AlfaButton)
    String vaadinAncestor = neo4jClient.query(
            """
            MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
            WHERE ma.isInherited = true AND ma.source = $source
            RETURN ma.vaadinAncestor AS vaadinAncestor
            LIMIT 1
            """)
        .bind(BUSINESS_SERVICE_FQN).to("fqn")
        .bind(ALFA_BUTTON_FQN).to("source")
        .fetchAs(String.class)
        .mappedBy((ts, record) -> record.get("vaadinAncestor").asString(null))
        .one().orElse(null);

    assertThat(vaadinAncestor)
        .as("vaadinAncestor must resolve to ultimate com.vaadin.* type, not com.alfa.* intermediary")
        .isEqualTo(VAADIN_BUTTON_FQN);
  }

  /**
   * ALFA-02b: Multi-hop chain: AlfaButtonWrapper is itself Layer 1.5; its inherited action also
   * has vaadinAncestor=com.vaadin.ui.Button (not AlfaButton).
   */
  @Test
  void alfaButtonWrapper_multiHop_vaadinAncestorResolvesToVaadinButton() {
    migrationRecipeService.migrationPostProcessing();

    String vaadinAncestor = neo4jClient.query(
            """
            MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
            WHERE ma.isInherited = true
            RETURN ma.vaadinAncestor AS vaadinAncestor
            LIMIT 1
            """)
        .bind(ALFA_BUTTON_WRAPPER_FQN).to("fqn")
        .fetchAs(String.class)
        .mappedBy((ts, record) -> record.get("vaadinAncestor").asString(null))
        .one().orElse(null);

    assertThat(vaadinAncestor)
        .as("AlfaButtonWrapper's inherited action must resolve vaadinAncestor to com.vaadin.ui.Button")
        .isEqualTo(VAADIN_BUTTON_FQN);
  }

  /**
   * ALFA-04a: Pure Layer 2 class (no own Alfa/Vaadin usage, no overrides) gets pureWrapper=true
   * and transitiveComplexity == 0.0.
   */
  @Test
  void pureLayer2Class_getsPureWrapperTrue() {
    migrationRecipeService.migrationPostProcessing();

    Map<String, Object> result = neo4jClient.query(
            """
            MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
            WHERE ma.isInherited = true
            RETURN ma.pureWrapper AS pureWrapper, ma.transitiveComplexity AS complexity
            LIMIT 1
            """)
        .bind(BUSINESS_SERVICE_FQN).to("fqn")
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, Object> row = new HashMap<>();
          row.put("pureWrapper", record.get("pureWrapper").asBoolean(false));
          row.put("complexity", record.get("complexity").asDouble(-1.0));
          return row;
        })
        .one().orElse(null);

    assertThat(result).isNotNull();
    assertThat((boolean) result.get("pureWrapper"))
        .as("BusinessServiceImpl has no overrides and no own Alfa/Vaadin calls — pureWrapper must be true")
        .isTrue();
    assertThat((double) result.get("complexity"))
        .as("Pure wrapper must have transitiveComplexity == 0.0")
        .isEqualTo(0.0);
  }

  /**
   * ALFA-04b: Complex Layer 2 class with own Alfa* usage (CALLS edge to AlfaTable) gets
   * pureWrapper=false and transitiveComplexity > 0.
   */
  @Test
  void complexLayer2Class_withOwnAlfaUsage_getsPureWrapperFalse() {
    migrationRecipeService.migrationPostProcessing();

    Map<String, Object> result = neo4jClient.query(
            """
            MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
            WHERE ma.isInherited = true
            RETURN ma.pureWrapper AS pureWrapper, ma.transitiveComplexity AS complexity,
                   ma.ownAlfaCalls AS ownAlfaCalls
            LIMIT 1
            """)
        .bind(COMPLEX_VIEW_FQN).to("fqn")
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, Object> row = new HashMap<>();
          row.put("pureWrapper", record.get("pureWrapper").asBoolean(true));
          row.put("complexity", record.get("complexity").asDouble(0.0));
          row.put("ownAlfaCalls", record.get("ownAlfaCalls").asInt(0));
          return row;
        })
        .one().orElse(null);

    assertThat(result).isNotNull();
    assertThat((boolean) result.get("pureWrapper"))
        .as("ComplexBusinessView uses AlfaTable — pureWrapper must be false")
        .isFalse();
    assertThat((double) result.get("complexity"))
        .as("ComplexBusinessView has own Alfa* calls — transitiveComplexity must be > 0")
        .isGreaterThan(0.0);
    assertThat((int) result.get("ownAlfaCalls"))
        .as("ComplexBusinessView has a CALLS edge to AlfaTable — ownAlfaCalls >= 1")
        .isGreaterThanOrEqualTo(1);
  }

  /**
   * ALFA-04c: generatePlan() returns non-empty migrationSteps for Layer 2 class sourced from the
   * Alfa* recipe rule (loaded from alfa-test-overlay.json).
   */
  @Test
  void generatePlan_layer2Class_returnsMigrationStepsFromAlfaRule() {
    migrationRecipeService.migrationPostProcessing();

    MigrationPlan plan = migrationRecipeService.generatePlan(BUSINESS_SERVICE_FQN);

    // Should have at least one action (inherited from AlfaButton chain)
    assertThat(plan.totalActions())
        .as("BusinessServiceImpl should have at least 1 migration action after transitive detection")
        .isGreaterThanOrEqualTo(1);

    // At least one action must have non-empty migrationSteps
    boolean hasMigrationSteps = plan.automatableActions().stream()
        .anyMatch(a -> a.migrationSteps() != null && !a.migrationSteps().isEmpty());
    boolean hasManualSteps = plan.manualActions().stream()
        .anyMatch(a -> a.migrationSteps() != null && !a.migrationSteps().isEmpty());

    assertThat(hasMigrationSteps || hasManualSteps)
        .as("At least one action on BusinessServiceImpl must have migrationSteps from the Alfa* recipe rule")
        .isTrue();
  }
}
