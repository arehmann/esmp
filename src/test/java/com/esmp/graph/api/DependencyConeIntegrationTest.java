package com.esmp.graph.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the dependency cone endpoint:
 * GET /api/graph/class/{fqn}/dependency-cone
 *
 * <p>Verifies the transitive closure traversal across all 7 structural relationship types using a
 * real Neo4j instance via Testcontainers.
 *
 * <p>Test graph:
 * <ul>
 *   <li>com.example.BaseService (JavaClass, no outgoing edges beyond inherited)
 *   <li>com.example.SampleService (JavaClass:Service) -[:EXTENDS]-> BaseService
 *   <li>com.example.SampleService -[:DEPENDS_ON]-> SampleRepository
 *   <li>com.example.SampleRepository (JavaClass:Repository)
 *   <li>com.example.SampleService -[:DECLARES_METHOD]-> doWork method
 *   <li>doWork -[:CALLS]-> findAll method
 *   <li>com.example.SampleRepository -[:DECLARES_METHOD]-> findAll method
 *   <li>findAll -[:QUERIES]-> DBTable "sample"
 *   <li>com.example.IsolatedClass (JavaClass, no relationships)
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DependencyConeIntegrationTest {

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

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private Neo4jClient neo4jClient;

  @BeforeEach
  void setupTestGraph() {
    // Clear everything
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // BaseService (no outgoing edges to other classes)
    neo4jClient
        .query(
            "CREATE (n:JavaClass {fullyQualifiedName: 'com.example.BaseService',"
                + " simpleName: 'BaseService', packageName: 'com.example',"
                + " implementedInterfaces: [], annotations: []})")
        .run();

    // SampleRepository (Repository label)
    neo4jClient
        .query(
            "CREATE (n:JavaClass:Repository {fullyQualifiedName: 'com.example.SampleRepository',"
                + " simpleName: 'SampleRepository', packageName: 'com.example',"
                + " implementedInterfaces: [], annotations: []})")
        .run();

    // SampleService (Service label) — EXTENDS BaseService, DEPENDS_ON SampleRepository
    neo4jClient
        .query(
            "CREATE (n:JavaClass:Service {fullyQualifiedName: 'com.example.SampleService',"
                + " simpleName: 'SampleService', packageName: 'com.example',"
                + " superClass: 'com.example.BaseService',"
                + " implementedInterfaces: [], annotations: []})")
        .run();

    neo4jClient
        .query(
            "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.SampleService'}),"
                + " (base:JavaClass {fullyQualifiedName: 'com.example.BaseService'})"
                + " CREATE (svc)-[:EXTENDS]->(base)")
        .run();

    neo4jClient
        .query(
            "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.SampleService'}),"
                + " (repo:JavaClass {fullyQualifiedName: 'com.example.SampleRepository'})"
                + " CREATE (svc)-[:DEPENDS_ON {injectionType: 'FIELD'}]->(repo)")
        .run();

    // doWork method on SampleService
    neo4jClient
        .query(
            "CREATE (m:JavaMethod {methodId: 'com.example.SampleService#doWork()',"
                + " simpleName: 'doWork', returnType: 'void',"
                + " parameterTypes: [], annotations: []})")
        .run();

    neo4jClient
        .query(
            "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.SampleService'}),"
                + " (m:JavaMethod {methodId: 'com.example.SampleService#doWork()'})"
                + " CREATE (svc)-[:DECLARES_METHOD]->(m)")
        .run();

    // findAll method on SampleRepository
    neo4jClient
        .query(
            "CREATE (m:JavaMethod {methodId: 'com.example.SampleRepository#findAll()',"
                + " simpleName: 'findAll', returnType: 'java.util.List',"
                + " parameterTypes: [], annotations: []})")
        .run();

    neo4jClient
        .query(
            "MATCH (repo:JavaClass {fullyQualifiedName: 'com.example.SampleRepository'}),"
                + " (m:JavaMethod {methodId: 'com.example.SampleRepository#findAll()'})"
                + " CREATE (repo)-[:DECLARES_METHOD]->(m)")
        .run();

    // doWork CALLS findAll
    neo4jClient
        .query(
            "MATCH (caller:JavaMethod {methodId: 'com.example.SampleService#doWork()'}),"
                + " (callee:JavaMethod {methodId: 'com.example.SampleRepository#findAll()'})"
                + " CREATE (caller)-[:CALLS]->(callee)")
        .run();

    // findAll QUERIES DBTable "sample"
    neo4jClient
        .query(
            "CREATE (t:DBTable {tableName: 'sample'})")
        .run();

    neo4jClient
        .query(
            "MATCH (m:JavaMethod {methodId: 'com.example.SampleRepository#findAll()'}),"
                + " (t:DBTable {tableName: 'sample'})"
                + " CREATE (m)-[:QUERIES]->(t)")
        .run();

    // IsolatedClass — no outgoing or incoming relationships
    neo4jClient
        .query(
            "CREATE (n:JavaClass {fullyQualifiedName: 'com.example.IsolatedClass',"
                + " simpleName: 'IsolatedClass', packageName: 'com.example',"
                + " implementedInterfaces: [], annotations: []})")
        .run();
  }

  /**
   * Test 1: A well-connected class returns the full transitive closure.
   *
   * <p>SampleService has these reachable nodes (minimum):
   * - BaseService (via EXTENDS, 1 hop)
   * - SampleRepository (via DEPENDS_ON, 1 hop)
   * - doWork method (via DECLARES_METHOD — NOTE: DECLARES_METHOD is not one of the 7 cone
   *   relationship types, so doWork is only reachable if DECLARES_METHOD is included, but the plan
   *   specifies DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS|BINDS_TO|QUERIES|MAPS_TO_TABLE)
   *
   * Wait — DECLARES_METHOD is NOT in the 7 cone types. Let's trace what IS reachable:
   * - EXTENDS: SampleService -> BaseService (1 hop)
   * - DEPENDS_ON: SampleService -> SampleRepository (1 hop)
   * - CALLS: doWork -> findAll, but doWork is not directly reachable from SampleService
   *   via the 7 cone relationships (DECLARES_METHOD is not in the cone)
   *
   * So reachable nodes from SampleService via the 7 cone types: BaseService, SampleRepository.
   * That is coneSize >= 2.
   *
   * The plan says "coneSize >= 4 (BaseService, SampleRepository, doWork's callees, DBTable)" but
   * that relies on DECLARES_METHOD being in the traversal, which it is NOT.
   * We assert coneSize >= 2 (BaseService + SampleRepository) to match actual cone semantics.
   */
  @Test
  void coneForWellConnectedClass() {
    ResponseEntity<DependencyConeResponse> response =
        restTemplate.getForEntity(
            "/api/graph/class/com.example.SampleService/dependency-cone",
            DependencyConeResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().focalFqn()).isEqualTo("com.example.SampleService");
    assertThat(response.getBody().coneSize()).isGreaterThanOrEqualTo(2);

    List<String> fqns = response.getBody().coneNodes().stream()
        .map(DependencyConeResponse.ConeNode::fqn)
        .toList();

    assertThat(fqns).contains("com.example.BaseService");
    assertThat(fqns).contains("com.example.SampleRepository");
  }

  /**
   * Test 2: A class with no outgoing cone-relationship edges returns coneSize 0.
   */
  @Test
  void coneForIsolatedClass() {
    ResponseEntity<DependencyConeResponse> response =
        restTemplate.getForEntity(
            "/api/graph/class/com.example.IsolatedClass/dependency-cone",
            DependencyConeResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().focalFqn()).isEqualTo("com.example.IsolatedClass");
    assertThat(response.getBody().coneSize()).isEqualTo(0);
    assertThat(response.getBody().coneNodes()).isEmpty();
  }

  /**
   * Test 3: Non-existent FQN returns 404.
   */
  @Test
  void coneForNonExistentClass() {
    ResponseEntity<Map> response =
        restTemplate.getForEntity(
            "/api/graph/class/com.example.DoesNotExist/dependency-cone",
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  /**
   * Test 4: Cone nodes include their dynamic labels.
   *
   * <p>SampleRepository has the :Repository label in the test graph. The cone query uses
   * {@code labels(n)} directly, so the Repository label must appear in the cone node.
   */
  @Test
  void coneNodeLabels() {
    ResponseEntity<DependencyConeResponse> response =
        restTemplate.getForEntity(
            "/api/graph/class/com.example.SampleService/dependency-cone",
            DependencyConeResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();

    // Find the SampleRepository cone node and verify it carries the Repository label
    DependencyConeResponse.ConeNode repoNode = response.getBody().coneNodes().stream()
        .filter(n -> "com.example.SampleRepository".equals(n.fqn()))
        .findFirst()
        .orElseThrow(() ->
            new AssertionError("SampleRepository not found in cone nodes"));

    assertThat(repoNode.labels())
        .as("SampleRepository cone node must include its dynamic 'Repository' label")
        .contains("Repository");
  }
}
