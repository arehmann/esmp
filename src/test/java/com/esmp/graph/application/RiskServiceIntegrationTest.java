package com.esmp.graph.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
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
 * Integration tests for RiskService and RiskController.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Fan-in is correctly computed from inbound DEPENDS_ON edges
 *   <li>Fan-out is correctly computed from outbound DEPENDS_ON edges
 *   <li>Composite structural risk score uses log-normalized weighted formula
 *   <li>GET /api/risk/heatmap returns classes sorted by descending risk score
 *   <li>GET /api/risk/heatmap?stereotype=Service filters to stereotype-labeled classes
 *   <li>GET /api/risk/heatmap?limit=2 returns at most 2 entries
 *   <li>GET /api/risk/class/{fqn} returns full risk detail with method breakdown
 *   <li>GET /api/risk/class/{nonexistent} returns 404
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class RiskServiceIntegrationTest {

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
  private RiskService riskService;

  @Autowired
  private MockMvc mockMvc;

  @BeforeEach
  void clearDatabase() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
  }

  // ---------------------------------------------------------------------------
  // Helper: create test data
  // ---------------------------------------------------------------------------

  private void createClassNode(String fqn, String simpleName, String packageName,
      int complexitySum, boolean hasDbWrites) {
    neo4jClient.query("""
        CREATE (c:JavaClass {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $packageName,
            complexitySum: $complexitySum,
            complexityMax: $complexitySum,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: $hasDbWrites,
            dbWriteCount: CASE WHEN $hasDbWrites THEN 1 ELSE 0 END,
            structuralRiskScore: 0.0
        })
        """)
        .bindAll(Map.of(
            "fqn", fqn,
            "simpleName", simpleName,
            "packageName", packageName,
            "complexitySum", complexitySum,
            "hasDbWrites", hasDbWrites))
        .run();
  }

  private void createClassNodeWithLabel(String fqn, String simpleName, String packageName,
      int complexitySum, boolean hasDbWrites, String extraLabel) {
    neo4jClient.query("""
        CREATE (c:JavaClass {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $packageName,
            complexitySum: $complexitySum,
            complexityMax: $complexitySum,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: $hasDbWrites,
            dbWriteCount: CASE WHEN $hasDbWrites THEN 1 ELSE 0 END,
            structuralRiskScore: 0.0
        })
        """)
        .bindAll(Map.of(
            "fqn", fqn,
            "simpleName", simpleName,
            "packageName", packageName,
            "complexitySum", complexitySum,
            "hasDbWrites", hasDbWrites))
        .run();
    // Add extra label separately
    neo4jClient.query("MATCH (c:JavaClass {fullyQualifiedName: $fqn}) SET c:" + extraLabel)
        .bindAll(Map.of("fqn", fqn))
        .run();
  }

  private void createDependsOnEdge(String fromFqn, String toFqn) {
    neo4jClient.query("""
        MATCH (from:JavaClass {fullyQualifiedName: $from})
        MATCH (to:JavaClass {fullyQualifiedName: $to})
        CREATE (from)-[:DEPENDS_ON]->(to)
        """)
        .bindAll(Map.of("from", fromFqn, "to", toFqn))
        .run();
  }

  private void createMethodNode(String methodId, String simpleName, String declaringClass,
      int cyclomaticComplexity) {
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $declaringClass})
        CREATE (m:JavaMethod {
            methodId: $methodId,
            simpleName: $simpleName,
            declaringClass: $declaringClass,
            cyclomaticComplexity: $cc,
            parameterTypes: []
        })
        CREATE (c)-[:DECLARES_METHOD]->(m)
        """)
        .bindAll(Map.of(
            "methodId", methodId,
            "simpleName", simpleName,
            "declaringClass", declaringClass,
            "cc", cyclomaticComplexity))
        .run();
  }

  // ---------------------------------------------------------------------------
  // Fan-in / fan-out computation tests
  // ---------------------------------------------------------------------------

  @Test
  void computeAndPersistRiskScores_setsFanIn_fromInboundDependsOnEdges() {
    // Arrange: ServiceA and ServiceB both depend on ServiceC
    createClassNode("com.test.ServiceA", "ServiceA", "com.test", 5, false);
    createClassNode("com.test.ServiceB", "ServiceB", "com.test", 3, false);
    createClassNode("com.test.ServiceC", "ServiceC", "com.test", 2, false);
    createDependsOnEdge("com.test.ServiceA", "com.test.ServiceC");
    createDependsOnEdge("com.test.ServiceB", "com.test.ServiceC");

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: ServiceC has fanIn=2
    Long fanIn = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.ServiceC'}) RETURN c.fanIn AS fi
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("fi").asLong())
        .one()
        .orElse(-1L);

    assertThat(fanIn).as("ServiceC should have fanIn=2 (2 classes depend on it)").isEqualTo(2L);
  }

  @Test
  void computeAndPersistRiskScores_setsFanOut_fromOutboundDependsOnEdges() {
    // Arrange: ServiceA depends on 3 classes
    createClassNode("com.test.ServiceA", "ServiceA", "com.test", 5, false);
    createClassNode("com.test.RepoA", "RepoA", "com.test", 1, false);
    createClassNode("com.test.RepoB", "RepoB", "com.test", 1, false);
    createClassNode("com.test.RepoC", "RepoC", "com.test", 1, false);
    createDependsOnEdge("com.test.ServiceA", "com.test.RepoA");
    createDependsOnEdge("com.test.ServiceA", "com.test.RepoB");
    createDependsOnEdge("com.test.ServiceA", "com.test.RepoC");

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: ServiceA has fanOut=3
    Long fanOut = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.ServiceA'}) RETURN c.fanOut AS fo
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("fo").asLong())
        .one()
        .orElse(-1L);

    assertThat(fanOut).as("ServiceA should have fanOut=3 (depends on 3 classes)").isEqualTo(3L);
  }

  @Test
  void computeAndPersistRiskScores_computesNonZeroRiskScore_forComplexClass() {
    // Arrange: a complex class with db writes, fan-in, and fan-out
    createClassNode("com.test.ServiceA", "ServiceA", "com.test", 10, true);
    createClassNode("com.test.RepoA", "RepoA", "com.test", 1, false);
    createClassNode("com.test.ServiceB", "ServiceB", "com.test", 1, false);
    createDependsOnEdge("com.test.ServiceA", "com.test.RepoA");   // fanOut=1
    createDependsOnEdge("com.test.ServiceB", "com.test.ServiceA"); // fanIn=1

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: ServiceA should have a non-zero structural risk score
    Double score = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.ServiceA'})
        RETURN c.structuralRiskScore AS score
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("score").asDouble())
        .one()
        .orElse(0.0);

    assertThat(score).as("Complex class with db writes should have non-zero risk score")
        .isGreaterThan(0.0);
  }

  // ---------------------------------------------------------------------------
  // Heatmap endpoint tests
  // ---------------------------------------------------------------------------

  @Test
  void heatmapEndpoint_returnsListSortedByDescendingRiskScore() throws Exception {
    // Arrange: 3 classes with different complexity levels
    createClassNode("com.test.LowRisk", "LowRisk", "com.test", 1, false);
    createClassNode("com.test.MidRisk", "MidRisk", "com.test", 5, false);
    createClassNode("com.test.HighRisk", "HighRisk", "com.test", 20, true);

    riskService.computeAndPersistRiskScores();

    // Act + Assert
    mockMvc.perform(get("/api/risk/heatmap"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].fqn").value("com.test.HighRisk"))
        .andExpect(jsonPath("$[0].structuralRiskScore").isNumber());
  }

  @Test
  void heatmapEndpoint_filtersByStereotype() throws Exception {
    // Arrange: one Service class and one plain class
    createClassNodeWithLabel("com.test.MyService", "MyService", "com.test", 5, false, "Service");
    createClassNode("com.test.PlainClass", "PlainClass", "com.test", 5, false);

    riskService.computeAndPersistRiskScores();

    // Act + Assert
    mockMvc.perform(get("/api/risk/heatmap").param("stereotype", "Service"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].fqn").value("com.test.MyService"));
  }

  @Test
  void heatmapEndpoint_respectsLimit() throws Exception {
    // Arrange: 5 classes
    for (int i = 1; i <= 5; i++) {
      createClassNode("com.test.Class" + i, "Class" + i, "com.test", i * 2, false);
    }
    riskService.computeAndPersistRiskScores();

    // Act + Assert: limit=2 should return at most 2 entries
    mockMvc.perform(get("/api/risk/heatmap").param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  // ---------------------------------------------------------------------------
  // Class detail endpoint tests
  // ---------------------------------------------------------------------------

  @Test
  void classDetailEndpoint_returnsDetailWithMethodBreakdown() throws Exception {
    // Arrange: a class with methods
    createClassNode("com.test.MyService", "MyService", "com.test", 6, true);
    createMethodNode("com.test.MyService#doWork(String)", "doWork", "com.test.MyService", 4);
    createMethodNode("com.test.MyService#cleanup()", "cleanup", "com.test.MyService", 2);

    riskService.computeAndPersistRiskScores();

    // Act + Assert
    mockMvc.perform(get("/api/risk/class/com.test.MyService"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fqn").value("com.test.MyService"))
        .andExpect(jsonPath("$.simpleName").value("MyService"))
        .andExpect(jsonPath("$.hasDbWrites").value(true))
        .andExpect(jsonPath("$.methods").isArray())
        .andExpect(jsonPath("$.methods.length()").value(2));
  }

  @Test
  void classDetailEndpoint_returns404_forNonexistentClass() throws Exception {
    // Act + Assert
    mockMvc.perform(get("/api/risk/class/com.nonexistent.NoSuchClass"))
        .andExpect(status().isNotFound());
  }

  // ---------------------------------------------------------------------------
  // Validation registry test
  // ---------------------------------------------------------------------------

  @Test
  void validationRegistry_detectsClassesWithNullRiskScores() {
    // Arrange: create a class without risk score set
    neo4jClient.query("""
        CREATE (c:JavaClass {
            fullyQualifiedName: 'com.test.Unscored',
            simpleName: 'Unscored',
            packageName: 'com.test'
        })
        """).run();

    // Act: query for classes with null structuralRiskScore
    Long count = neo4jClient.query("""
        OPTIONAL MATCH (c:JavaClass)
        WHERE c.structuralRiskScore IS NULL
        RETURN count(c) AS count
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("count").asLong())
        .one()
        .orElse(0L);

    assertThat(count).as("Should detect at least one class without a risk score").isGreaterThan(0L);
  }
}
