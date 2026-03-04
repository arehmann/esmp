package com.esmp.graph.api;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Integration test for {@link GraphQueryController} proving all 4 REST endpoints work against a
 * real Neo4j instance via Testcontainers.
 *
 * <p>Pre-populates Neo4j with a small test graph:
 * <ul>
 *   <li>com.example.AbstractBase (JavaClass node, no parent)
 *   <li>com.example.BaseService extends AbstractBase (EXTENDS edge)
 *   <li>com.example.SampleService extends BaseService, has Service label, methods, fields
 *   <li>com.example.SampleRepository has Repository label
 *   <li>DEPENDS_ON edge from SampleService to SampleRepository
 *   <li>AnnotationNode org.springframework.stereotype.Service linked to SampleService via HAS_ANNOTATION
 *   <li>MethodNode and FieldNode linked to SampleService via DECLARES_METHOD / DECLARES_FIELD
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GraphQueryControllerIntegrationTest {

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

    // AbstractBase class node
    neo4jClient
        .query(
            "CREATE (n:JavaClass {fullyQualifiedName: 'com.example.AbstractBase',"
                + " simpleName: 'AbstractBase', packageName: 'com.example',"
                + " implementedInterfaces: [], annotations: []})")
        .run();

    // BaseService extends AbstractBase
    neo4jClient
        .query(
            "CREATE (n:JavaClass {fullyQualifiedName: 'com.example.BaseService',"
                + " simpleName: 'BaseService', packageName: 'com.example',"
                + " superClass: 'com.example.AbstractBase',"
                + " implementedInterfaces: [], annotations: []})")
        .run();
    neo4jClient
        .query(
            "MATCH (child:JavaClass {fullyQualifiedName: 'com.example.BaseService'}),"
                + " (parent:JavaClass {fullyQualifiedName: 'com.example.AbstractBase'})"
                + " CREATE (child)-[:EXTENDS]->(parent)")
        .run();

    // SampleRepository
    neo4jClient
        .query(
            "CREATE (n:JavaClass:Repository {fullyQualifiedName: 'com.example.SampleRepository',"
                + " simpleName: 'SampleRepository', packageName: 'com.example',"
                + " implementedInterfaces: [], annotations: []})")
        .run();

    // SampleService (Service label, extends BaseService)
    neo4jClient
        .query(
            "CREATE (n:JavaClass:Service {fullyQualifiedName: 'com.example.SampleService',"
                + " simpleName: 'SampleService', packageName: 'com.example',"
                + " superClass: 'com.example.BaseService',"
                + " implementedInterfaces: ['com.example.ISampleService'],"
                + " annotations: ['org.springframework.stereotype.Service']})")
        .run();

    // EXTENDS: SampleService -> BaseService
    neo4jClient
        .query(
            "MATCH (child:JavaClass {fullyQualifiedName: 'com.example.SampleService'}),"
                + " (parent:JavaClass {fullyQualifiedName: 'com.example.BaseService'})"
                + " CREATE (child)-[:EXTENDS]->(parent)")
        .run();

    // DEPENDS_ON: SampleService -> SampleRepository
    neo4jClient
        .query(
            "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.SampleService'}),"
                + " (repo:JavaClass {fullyQualifiedName: 'com.example.SampleRepository'})"
                + " CREATE (svc)-[:DEPENDS_ON {injectionType: 'FIELD'}]->(repo)")
        .run();

    // AnnotationNode linked to SampleService
    neo4jClient
        .query(
            "CREATE (ann:JavaAnnotation {fullyQualifiedName: 'org.springframework.stereotype.Service'})")
        .run();
    neo4jClient
        .query(
            "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.SampleService'}),"
                + " (ann:JavaAnnotation {fullyQualifiedName: 'org.springframework.stereotype.Service'})"
                + " CREATE (svc)-[:HAS_ANNOTATION]->(ann)")
        .run();

    // MethodNode linked to SampleService
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

    // FieldNode linked to SampleService
    neo4jClient
        .query(
            "CREATE (f:JavaField {fieldId: 'com.example.SampleService#repository',"
                + " simpleName: 'repository', fieldType: 'com.example.SampleRepository',"
                + " annotations: []})")
        .run();
    neo4jClient
        .query(
            "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.SampleService'}),"
                + " (f:JavaField {fieldId: 'com.example.SampleService#repository'})"
                + " CREATE (svc)-[:DECLARES_FIELD]->(f)")
        .run();
  }

  @Test
  void testGetClassStructure_found() {
    ResponseEntity<ClassStructureResponse> response =
        restTemplate.getForEntity(
            "/api/graph/class/com.example.SampleService", ClassStructureResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().fullyQualifiedName()).isEqualTo("com.example.SampleService");
    assertThat(response.getBody().simpleName()).isEqualTo("SampleService");
    assertThat(response.getBody().methods()).isNotEmpty();
    assertThat(response.getBody().fields()).isNotEmpty();
    assertThat(response.getBody().dependencies()).isNotEmpty();
    assertThat(response.getBody().annotationNodes()).isNotEmpty();
  }

  @Test
  void testGetClassStructure_notFound() {
    ResponseEntity<Map> response =
        restTemplate.getForEntity("/api/graph/class/nonexistent.Class", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void testGetInheritanceChain() {
    ResponseEntity<InheritanceChainResponse> response =
        restTemplate.getForEntity(
            "/api/graph/class/com.example.SampleService/inheritance",
            InheritanceChainResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().fullyQualifiedName()).isEqualTo("com.example.SampleService");
    assertThat(response.getBody().chain()).isNotEmpty();

    // Chain should contain BaseService at depth 1 and AbstractBase at depth 2
    assertThat(response.getBody().chain())
        .anyMatch(a -> a.fullyQualifiedName().equals("com.example.BaseService") && a.depth() == 1);
    assertThat(response.getBody().chain())
        .anyMatch(
            a -> a.fullyQualifiedName().equals("com.example.AbstractBase") && a.depth() == 2);
  }

  @Test
  void testGetServiceDependents() {
    ResponseEntity<DependencyResponse> response =
        restTemplate.getForEntity(
            "/api/graph/repository/com.example.SampleRepository/service-dependents",
            DependencyResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().repositoryFqn()).isEqualTo("com.example.SampleRepository");
    assertThat(response.getBody().services())
        .anyMatch(s -> s.fullyQualifiedName().equals("com.example.SampleService"));
  }

  @Test
  void testSearch() {
    ResponseEntity<SearchResponse> response =
        restTemplate.getForEntity("/api/graph/search?name=Sample", SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().results())
        .anyMatch(r -> r.fullyQualifiedName().equals("com.example.SampleService"));
    assertThat(response.getBody().results())
        .anyMatch(r -> r.fullyQualifiedName().equals("com.example.SampleRepository"));
  }

  @Test
  void testSearch_emptyResult() {
    ResponseEntity<SearchResponse> response =
        restTemplate.getForEntity("/api/graph/search?name=NoSuchClass", SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().results()).isEmpty();
  }
}
