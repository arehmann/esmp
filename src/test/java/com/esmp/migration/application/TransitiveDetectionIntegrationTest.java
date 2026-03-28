package com.esmp.migration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.migration.api.RecipeRule;
import java.util.List;
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
 * Integration tests for transitive Vaadin 7 detection via EXTENDS graph traversal.
 *
 * <p>Verifies requirements RB-04:
 * <ul>
 *   <li>RB-04-01: detectTransitiveMigrations creates inherited MigrationAction nodes
 *   <li>RB-04-02: inherited action has correct actionId format
 *   <li>RB-04-03: complexity profiling classifies pure wrapper
 *   <li>RB-04-04: complexity profiling classifies complex class (with overrides)
 *   <li>RB-04-05: recomputeMigrationScores updates ClassNode.migrationActionCount
 *   <li>RB-04-06: idempotent — running twice does not create duplicates
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class TransitiveDetectionIntegrationTest {

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
  private RecipeBookRegistry recipeBookRegistry;

  // --- Well-known Vaadin 7 ancestor in the seed book ---
  private static final String VAADIN_ANCESTOR_FQN = "com.vaadin.ui.CustomComponent";
  private static final String CHILD_FQN = "com.example.MyWidget";
  private static final String GRANDCHILD_FQN = "com.example.MySpecialWidget";
  private static final String VAADIN_BUTTON_FQN = "com.vaadin.ui.Button";

  private static boolean setUpDone = false;

  @BeforeEach
  void setupGraph() {
    if (setUpDone) return;

    // Clear graph
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // Create ancestor node (known Vaadin 7 type from recipe book)
    neo4jClient.query(
        """
        MERGE (a:JavaClass {fullyQualifiedName: $fqn})
        SET a.simpleName = 'CustomComponent', a.packageName = 'com.vaadin.ui',
            a.sourceFilePath = 'com/vaadin/ui/CustomComponent.java'
        """)
        .bind(VAADIN_ANCESTOR_FQN).to("fqn")
        .run();

    // Create ancestor methods: initContent(), setCompositionRoot()
    neo4jClient.query(
        """
        MATCH (a:JavaClass {fullyQualifiedName: $ancestor})
        MERGE (m1:JavaMethod {methodId: $ancestor + '#initContent'})
        SET m1.simpleName = 'initContent', m1.classFqn = $ancestor
        MERGE (a)-[:DECLARES_METHOD]->(m1)
        """)
        .bind(VAADIN_ANCESTOR_FQN).to("ancestor")
        .run();
    neo4jClient.query(
        """
        MATCH (a:JavaClass {fullyQualifiedName: $ancestor})
        MERGE (m2:JavaMethod {methodId: $ancestor + '#setCompositionRoot'})
        SET m2.simpleName = 'setCompositionRoot', m2.classFqn = $ancestor
        MERGE (a)-[:DECLARES_METHOD]->(m2)
        """)
        .bind(VAADIN_ANCESTOR_FQN).to("ancestor")
        .run();

    // Create a direct MigrationAction on the ancestor itself
    neo4jClient.query(
        """
        MERGE (ma:MigrationAction {actionId: $actionId})
        SET ma.classFqn = $ancestorFqn, ma.actionType = 'COMPLEX_REWRITE',
            ma.source = $ancestorFqn, ma.automatable = 'NO', ma.isInherited = false
        WITH ma
        MATCH (a:JavaClass {fullyQualifiedName: $ancestorFqn})
        MERGE (a)-[:HAS_MIGRATION_ACTION]->(ma)
        """)
        .bind(VAADIN_ANCESTOR_FQN + "#COMPLEX_REWRITE#" + VAADIN_ANCESTOR_FQN).to("actionId")
        .bind(VAADIN_ANCESTOR_FQN).to("ancestorFqn")
        .run();

    // Create child node: com.example.MyWidget — overrides initContent()
    neo4jClient.query(
        """
        MERGE (c:JavaClass {fullyQualifiedName: $fqn})
        SET c.simpleName = 'MyWidget', c.packageName = 'com.example',
            c.sourceFilePath = 'com/example/MyWidget.java'
        WITH c
        MATCH (a:JavaClass {fullyQualifiedName: $ancestor})
        MERGE (c)-[:EXTENDS]->(a)
        """)
        .bind(CHILD_FQN).to("fqn")
        .bind(VAADIN_ANCESTOR_FQN).to("ancestor")
        .run();

    // Child overrides initContent()
    neo4jClient.query(
        """
        MATCH (c:JavaClass {fullyQualifiedName: $childFqn})
        MERGE (cm:JavaMethod {methodId: $childFqn + '#initContent'})
        SET cm.simpleName = 'initContent', cm.classFqn = $childFqn
        MERGE (c)-[:DECLARES_METHOD]->(cm)
        """)
        .bind(CHILD_FQN).to("childFqn")
        .run();

    // Create grandchild node with VaadinComponent label
    neo4jClient.query(
        """
        MERGE (g:JavaClass:VaadinComponent {fullyQualifiedName: $fqn})
        SET g.simpleName = 'MySpecialWidget', g.packageName = 'com.example',
            g.sourceFilePath = 'com/example/MySpecialWidget.java'
        WITH g
        MATCH (c:JavaClass {fullyQualifiedName: $child})
        MERGE (g)-[:EXTENDS]->(c)
        """)
        .bind(GRANDCHILD_FQN).to("fqn")
        .bind(CHILD_FQN).to("child")
        .run();

    // Create a CALLS edge from grandchild to com.vaadin.ui.Button
    neo4jClient.query(
        """
        MERGE (btn:JavaClass {fullyQualifiedName: $btnFqn})
        SET btn.simpleName = 'Button', btn.packageName = 'com.vaadin.ui'
        WITH btn
        MATCH (g:JavaClass {fullyQualifiedName: $grandchild})
        MERGE (g)-[:CALLS]->(btn)
        """)
        .bind(VAADIN_BUTTON_FQN).to("btnFqn")
        .bind(GRANDCHILD_FQN).to("grandchild")
        .run();

    setUpDone = true;
  }

  // ---------------------------------------------------------------------------
  // RB-04-01: detectTransitiveMigrations creates inherited actions
  // ---------------------------------------------------------------------------

  @Test
  void detectTransitiveMigrations_createsInheritedActions() {
    migrationRecipeService.migrationPostProcessing();

    List<Map<String, Object>> inherited = new java.util.ArrayList<>();
    neo4jClient.query("MATCH (ma:MigrationAction {isInherited: true}) RETURN ma.actionId AS actionId")
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, Object> row = new java.util.HashMap<>();
          row.put("actionId", record.get("actionId").asString(""));
          return row;
        })
        .all()
        .forEach(inherited::add);

    assertThat(inherited)
        .as("Should have at least 2 inherited MigrationAction nodes (MyWidget + MySpecialWidget)")
        .hasSizeGreaterThanOrEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // RB-04-02: inherited action has correct actionId format
  // ---------------------------------------------------------------------------

  @Test
  void detectTransitiveMigrations_correctActionIdFormat() {
    migrationRecipeService.migrationPostProcessing();

    // Child inherits from ancestor directly — should have actionId = CHILD#INHERITED#ANCESTOR
    String expectedActionId = CHILD_FQN + "#INHERITED#" + VAADIN_ANCESTOR_FQN;

    long count = neo4jClient
        .query("MATCH (ma:MigrationAction {actionId: $actionId}) RETURN count(ma) AS cnt")
        .bind(expectedActionId).to("actionId")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong(0))
        .one()
        .orElse(0L);

    assertThat(count)
        .as("Should find exactly 1 MigrationAction with actionId " + expectedActionId)
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // RB-04-03: complexity profiling classifies pure wrapper (grandchild via child — no own overrides
  // of ancestor methods that it directly inherits from child)
  // ---------------------------------------------------------------------------

  @Test
  void detectTransitiveMigrations_grandchildHasOwnVaadinCalls() {
    migrationRecipeService.migrationPostProcessing();

    // Grandchild has VaadinComponent label and a CALLS edge to com.vaadin.ui.Button
    // So it should NOT be a pure wrapper (ownVaadinCalls > 0 OR hasOwnComponents=true)
    String actionId = GRANDCHILD_FQN + "#INHERITED#" + VAADIN_ANCESTOR_FQN;
    Map<String, Object> result = neo4jClient
        .query(
            """
            MATCH (ma:MigrationAction {actionId: $actionId})
            RETURN ma.pureWrapper AS pureWrapper, ma.transitiveComplexity AS complexity,
                   ma.ownVaadinCalls AS ownVaadinCalls, ma.automatable AS automatable
            """)
        .bind(actionId).to("actionId")
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, Object> row = new java.util.HashMap<>();
          row.put("pureWrapper", record.get("pureWrapper").asBoolean(false));
          row.put("complexity", record.get("complexity").asDouble(0.0));
          row.put("ownVaadinCalls", record.get("ownVaadinCalls").asInt(0));
          row.put("automatable", record.get("automatable").asString(""));
          return row;
        })
        .one()
        .orElse(null);

    assertThat(result).isNotNull();
    // Grandchild has CALLS edge to com.vaadin.ui.Button → ownVaadinCalls >= 1 OR VaadinComponent label
    assertThat((double) result.get("complexity"))
        .as("Grandchild transitiveComplexity should be > 0 (has own Vaadin calls + VaadinComponent)")
        .isGreaterThan(0.0);
    assertThat((boolean) result.get("pureWrapper"))
        .as("Grandchild should NOT be a pure wrapper")
        .isFalse();
  }

  // ---------------------------------------------------------------------------
  // RB-04-04: complexity profiling classifies complex class (child with override)
  // ---------------------------------------------------------------------------

  @Test
  void detectTransitiveMigrations_childWithOverrideHasNonZeroComplexity() {
    migrationRecipeService.migrationPostProcessing();

    String actionId = CHILD_FQN + "#INHERITED#" + VAADIN_ANCESTOR_FQN;
    Map<String, Object> result = neo4jClient
        .query(
            """
            MATCH (ma:MigrationAction {actionId: $actionId})
            RETURN ma.overrideCount AS overrideCount, ma.transitiveComplexity AS complexity
            """)
        .bind(actionId).to("actionId")
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, Object> row = new java.util.HashMap<>();
          row.put("overrideCount", record.get("overrideCount").asInt(0));
          row.put("complexity", record.get("complexity").asDouble(0.0));
          return row;
        })
        .one()
        .orElse(null);

    assertThat(result).isNotNull();
    assertThat((int) result.get("overrideCount"))
        .as("Child overrides initContent() — overrideCount should be >= 1")
        .isGreaterThanOrEqualTo(1);
    assertThat((double) result.get("complexity"))
        .as("Child transitiveComplexity should be > 0 due to override")
        .isGreaterThan(0.0);
  }

  // ---------------------------------------------------------------------------
  // RB-04-05: recomputeMigrationScores updates ClassNode.migrationActionCount
  // ---------------------------------------------------------------------------

  @Test
  void recomputeMigrationScores_updatesClassNodeActionCount() {
    migrationRecipeService.migrationPostProcessing();

    // After post-processing, MyWidget should have migrationActionCount set
    Map<String, Object> result = neo4jClient
        .query(
            """
            MATCH (c:JavaClass {fullyQualifiedName: $fqn})
            RETURN c.migrationActionCount AS actionCount, c.automationScore AS score
            """)
        .bind(CHILD_FQN).to("fqn")
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, Object> row = new java.util.HashMap<>();
          row.put("actionCount", record.get("actionCount").asInt(0));
          row.put("score", record.get("score").asDouble(-1.0));
          return row;
        })
        .one()
        .orElse(null);

    assertThat(result).isNotNull();
    assertThat((int) result.get("actionCount"))
        .as("MyWidget.migrationActionCount should be >= 1")
        .isGreaterThanOrEqualTo(1);
    assertThat((double) result.get("score"))
        .as("MyWidget.automationScore should be in [0.0, 1.0]")
        .isBetween(0.0, 1.0);
  }

  // ---------------------------------------------------------------------------
  // RB-04-06: idempotent — running twice does not create duplicates
  // ---------------------------------------------------------------------------

  @Test
  void migrationPostProcessing_idempotent_noDuplicates() {
    migrationRecipeService.migrationPostProcessing();

    // Count all inherited actions after first run
    long countAfterFirst = neo4jClient
        .query("MATCH (ma:MigrationAction {isInherited: true}) RETURN count(ma) AS cnt")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong(0))
        .one()
        .orElse(0L);

    // Run again
    migrationRecipeService.migrationPostProcessing();

    long countAfterSecond = neo4jClient
        .query("MATCH (ma:MigrationAction {isInherited: true}) RETURN count(ma) AS cnt")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong(0))
        .one()
        .orElse(0L);

    assertThat(countAfterSecond)
        .as("Running migrationPostProcessing twice should not create duplicate MigrationAction nodes")
        .isEqualTo(countAfterFirst);
  }
}
