package com.esmp.graph.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.graph.validation.ValidationStatus;
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
 * Integration tests for {@link ValidationController} proving all 20 validation queries work
 * against a real Neo4j instance via Testcontainers.
 *
 * <p>Tests cover:
 * <ol>
 *   <li>Well-formed graph produces 20 PASS results
 *   <li>Orphan and dangling nodes are detected
 *   <li>Broken inheritance chain is detected
 *   <li>Architectural pattern violations are detected
 *   <li>Report structure is valid (generatedAt, 20 results, counts sum to 20)
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ValidationControllerIntegrationTest {

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

  /**
   * Clears the graph and populates a well-formed test graph before each test.
   * The well-formed graph satisfies all 20 validation queries.
   */
  @BeforeEach
  void setupWellFormedGraph() {
    // Clear everything
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // JavaModule -> JavaPackages
    neo4jClient.query(
        "CREATE (m:JavaModule {moduleName: 'com.example'})"
    ).run();
    neo4jClient.query(
        "CREATE (p:JavaPackage {packageName: 'com.example.service'})"
    ).run();
    neo4jClient.query(
        "CREATE (p:JavaPackage {packageName: 'com.example.repo'})"
    ).run();
    neo4jClient.query(
        "CREATE (p:JavaPackage {packageName: 'com.example.model'})"
    ).run();
    neo4jClient.query(
        "CREATE (p:JavaPackage {packageName: 'com.example.view'})"
    ).run();

    // Module -> CONTAINS_PACKAGE -> all packages
    neo4jClient.query(
        "MATCH (m:JavaModule {moduleName: 'com.example'}), (p:JavaPackage) "
        + "CREATE (m)-[:CONTAINS_PACKAGE]->(p)"
    ).run();

    // BaseService class (plain, in service package)
    neo4jClient.query(
        "CREATE (n:JavaClass {fullyQualifiedName: 'com.example.service.BaseService', "
        + "simpleName: 'BaseService', packageName: 'com.example.service', "
        + "superClass: '', implementedInterfaces: [], annotations: []})"
    ).run();
    neo4jClient.query(
        "MATCH (p:JavaPackage {packageName: 'com.example.service'}), "
        + "(c:JavaClass {fullyQualifiedName: 'com.example.service.BaseService'}) "
        + "CREATE (p)-[:CONTAINS_CLASS]->(c)"
    ).run();

    // SampleRepository (Repository label, in repo package)
    neo4jClient.query(
        "CREATE (n:JavaClass:Repository {fullyQualifiedName: 'com.example.repo.SampleRepository', "
        + "simpleName: 'SampleRepository', packageName: 'com.example.repo', "
        + "superClass: '', implementedInterfaces: [], annotations: []})"
    ).run();
    neo4jClient.query(
        "MATCH (p:JavaPackage {packageName: 'com.example.repo'}), "
        + "(c:JavaClass {fullyQualifiedName: 'com.example.repo.SampleRepository'}) "
        + "CREATE (p)-[:CONTAINS_CLASS]->(c)"
    ).run();

    // SampleService (Service label, in service package, extends BaseService, depends on repo)
    neo4jClient.query(
        "CREATE (n:JavaClass:Service {fullyQualifiedName: 'com.example.service.SampleService', "
        + "simpleName: 'SampleService', packageName: 'com.example.service', "
        + "superClass: 'com.example.service.BaseService', "
        + "implementedInterfaces: [], annotations: ['org.springframework.stereotype.Service']})"
    ).run();
    neo4jClient.query(
        "MATCH (p:JavaPackage {packageName: 'com.example.service'}), "
        + "(c:JavaClass {fullyQualifiedName: 'com.example.service.SampleService'}) "
        + "CREATE (p)-[:CONTAINS_CLASS]->(c)"
    ).run();

    // SampleService -> EXTENDS -> BaseService
    neo4jClient.query(
        "MATCH (child:JavaClass {fullyQualifiedName: 'com.example.service.SampleService'}), "
        + "(parent:JavaClass {fullyQualifiedName: 'com.example.service.BaseService'}) "
        + "CREATE (child)-[:EXTENDS]->(parent)"
    ).run();

    // SampleService -> DEPENDS_ON -> SampleRepository
    neo4jClient.query(
        "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.service.SampleService'}), "
        + "(repo:JavaClass {fullyQualifiedName: 'com.example.repo.SampleRepository'}) "
        + "CREATE (svc)-[:DEPENDS_ON {injectionType: 'FIELD'}]->(repo)"
    ).run();

    // SampleService -> DECLARES_METHOD -> doWork method
    neo4jClient.query(
        "CREATE (m:JavaMethod {methodId: 'com.example.service.SampleService#doWork()', "
        + "simpleName: 'doWork', returnType: 'void', parameterTypes: [], annotations: []})"
    ).run();
    neo4jClient.query(
        "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.service.SampleService'}), "
        + "(m:JavaMethod {methodId: 'com.example.service.SampleService#doWork()'}) "
        + "CREATE (svc)-[:DECLARES_METHOD]->(m)"
    ).run();

    // SampleService -> DECLARES_FIELD -> repository field
    neo4jClient.query(
        "CREATE (f:JavaField {fieldId: 'com.example.service.SampleService#repository', "
        + "simpleName: 'repository', fieldType: 'com.example.repo.SampleRepository', annotations: []})"
    ).run();
    neo4jClient.query(
        "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.service.SampleService'}), "
        + "(f:JavaField {fieldId: 'com.example.service.SampleService#repository'}) "
        + "CREATE (svc)-[:DECLARES_FIELD]->(f)"
    ).run();

    // SampleService -> HAS_ANNOTATION -> Service annotation
    neo4jClient.query(
        "CREATE (a:JavaAnnotation {fullyQualifiedName: 'org.springframework.stereotype.Service'})"
    ).run();
    neo4jClient.query(
        "MATCH (svc:JavaClass {fullyQualifiedName: 'com.example.service.SampleService'}), "
        + "(a:JavaAnnotation {fullyQualifiedName: 'org.springframework.stereotype.Service'}) "
        + "CREATE (svc)-[:HAS_ANNOTATION]->(a)"
    ).run();

    // SampleRepository -> DECLARES_METHOD -> findAll method
    neo4jClient.query(
        "CREATE (m:JavaMethod {methodId: 'com.example.repo.SampleRepository#findAll()', "
        + "simpleName: 'findAll', returnType: 'java.util.List', parameterTypes: [], annotations: []})"
    ).run();
    neo4jClient.query(
        "MATCH (repo:JavaClass {fullyQualifiedName: 'com.example.repo.SampleRepository'}), "
        + "(m:JavaMethod {methodId: 'com.example.repo.SampleRepository#findAll()'}) "
        + "CREATE (repo)-[:DECLARES_METHOD]->(m)"
    ).run();

    // DBTable "sample"
    neo4jClient.query(
        "CREATE (t:DBTable {tableName: 'sample'})"
    ).run();

    // findAll -> QUERIES -> DBTable "sample"
    neo4jClient.query(
        "MATCH (m:JavaMethod {methodId: 'com.example.repo.SampleRepository#findAll()'}), "
        + "(t:DBTable {tableName: 'sample'}) "
        + "CREATE (m)-[:QUERIES]->(t)"
    ).run();

    // SampleEntity (Entity label, in model package) -> MAPS_TO_TABLE -> DBTable "sample"
    neo4jClient.query(
        "CREATE (n:JavaClass:Entity {fullyQualifiedName: 'com.example.model.SampleEntity', "
        + "simpleName: 'SampleEntity', packageName: 'com.example.model', "
        + "superClass: '', implementedInterfaces: [], annotations: ['javax.persistence.Entity']})"
    ).run();
    neo4jClient.query(
        "MATCH (p:JavaPackage {packageName: 'com.example.model'}), "
        + "(c:JavaClass {fullyQualifiedName: 'com.example.model.SampleEntity'}) "
        + "CREATE (p)-[:CONTAINS_CLASS]->(c)"
    ).run();
    neo4jClient.query(
        "MATCH (entity:JavaClass {fullyQualifiedName: 'com.example.model.SampleEntity'}), "
        + "(t:DBTable {tableName: 'sample'}) "
        + "CREATE (entity)-[:MAPS_TO_TABLE]->(t)"
    ).run();

    // SampleEntity -> HAS_ANNOTATION -> javax.persistence.Entity
    neo4jClient.query(
        "CREATE (a:JavaAnnotation {fullyQualifiedName: 'javax.persistence.Entity'})"
    ).run();
    neo4jClient.query(
        "MATCH (entity:JavaClass {fullyQualifiedName: 'com.example.model.SampleEntity'}), "
        + "(a:JavaAnnotation {fullyQualifiedName: 'javax.persistence.Entity'}) "
        + "CREATE (entity)-[:HAS_ANNOTATION]->(a)"
    ).run();

    // SampleView (VaadinView label, in view package) -> BINDS_TO -> SampleEntity
    neo4jClient.query(
        "CREATE (n:JavaClass:VaadinView {fullyQualifiedName: 'com.example.view.SampleView', "
        + "simpleName: 'SampleView', packageName: 'com.example.view', "
        + "superClass: '', implementedInterfaces: [], "
        + "annotations: ['com.vaadin.navigator.View']})"
    ).run();
    neo4jClient.query(
        "MATCH (p:JavaPackage {packageName: 'com.example.view'}), "
        + "(c:JavaClass {fullyQualifiedName: 'com.example.view.SampleView'}) "
        + "CREATE (p)-[:CONTAINS_CLASS]->(c)"
    ).run();
    neo4jClient.query(
        "MATCH (view:JavaClass {fullyQualifiedName: 'com.example.view.SampleView'}), "
        + "(entity:JavaClass {fullyQualifiedName: 'com.example.model.SampleEntity'}) "
        + "CREATE (view)-[:BINDS_TO]->(entity)"
    ).run();
    // SampleView annotation node
    neo4jClient.query(
        "CREATE (a:JavaAnnotation {fullyQualifiedName: 'com.vaadin.navigator.View'})"
    ).run();
    neo4jClient.query(
        "MATCH (view:JavaClass {fullyQualifiedName: 'com.example.view.SampleView'}), "
        + "(a:JavaAnnotation {fullyQualifiedName: 'com.vaadin.navigator.View'}) "
        + "CREATE (view)-[:HAS_ANNOTATION]->(a)"
    ).run();

    // Annotate BaseService and SampleRepository so ANNOTATION_COVERAGE passes
    // (every class in well-formed graph should have at least one annotation)
    neo4jClient.query(
        "CREATE (a:JavaAnnotation {fullyQualifiedName: 'org.springframework.stereotype.Component'})"
    ).run();
    neo4jClient.query(
        "MATCH (c:JavaClass {fullyQualifiedName: 'com.example.service.BaseService'}), "
        + "(a:JavaAnnotation {fullyQualifiedName: 'org.springframework.stereotype.Component'}) "
        + "CREATE (c)-[:HAS_ANNOTATION]->(a)"
    ).run();
    neo4jClient.query(
        "CREATE (a:JavaAnnotation {fullyQualifiedName: 'org.springframework.stereotype.Repository'})"
    ).run();
    neo4jClient.query(
        "MATCH (c:JavaClass {fullyQualifiedName: 'com.example.repo.SampleRepository'}), "
        + "(a:JavaAnnotation {fullyQualifiedName: 'org.springframework.stereotype.Repository'}) "
        + "CREATE (c)-[:HAS_ANNOTATION]->(a)"
    ).run();

    // CALLS: doWork -> findAll
    neo4jClient.query(
        "MATCH (caller:JavaMethod {methodId: 'com.example.service.SampleService#doWork()'}), "
        + "(callee:JavaMethod {methodId: 'com.example.repo.SampleRepository#findAll()'}) "
        + "CREATE (caller)-[:CALLS]->(callee)"
    ).run();
  }

  @Test
  void allQueriesPassOnWellFormedGraph() {
    ResponseEntity<ValidationReport> response =
        restTemplate.getForEntity("/api/graph/validation", ValidationReport.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();

    ValidationReport report = response.getBody();
    assertThat(report.results()).hasSize(20);

    // All queries should pass on a well-formed graph
    List<ValidationQueryResult> failing = report.results().stream()
        .filter(r -> r.status() != ValidationStatus.PASS)
        .toList();

    assertThat(failing)
        .as("Expected all 20 queries to PASS on well-formed graph, but these failed/warned: %s",
            failing.stream().map(r -> r.name() + "=" + r.status() + "(count=" + r.count() + ")").toList())
        .isEmpty();
  }

  @Test
  void detectsOrphanAndDanglingNodes() {
    // Add an orphan JavaClass (no CONTAINS_CLASS edge)
    neo4jClient.query(
        "CREATE (:JavaClass {fullyQualifiedName: 'com.example.OrphanClass', "
        + "simpleName: 'OrphanClass', packageName: 'com.example', "
        + "superClass: '', implementedInterfaces: [], annotations: []})"
    ).run();

    // Add a dangling JavaMethod (no DECLARES_METHOD edge)
    neo4jClient.query(
        "CREATE (:JavaMethod {methodId: 'com.example.OrphanClass#orphanMethod()', "
        + "simpleName: 'orphanMethod', returnType: 'void', parameterTypes: [], annotations: []})"
    ).run();

    // Add a dangling JavaField (no DECLARES_FIELD edge)
    neo4jClient.query(
        "CREATE (:JavaField {fieldId: 'com.example.OrphanClass#orphanField', "
        + "simpleName: 'orphanField', fieldType: 'java.lang.String', annotations: []})"
    ).run();

    ResponseEntity<ValidationReport> response =
        restTemplate.getForEntity("/api/graph/validation", ValidationReport.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ValidationReport report = response.getBody();
    assertThat(report).isNotNull();

    // ORPHAN_CLASS_NODES should warn
    ValidationQueryResult orphanResult = findByName(report, "ORPHAN_CLASS_NODES");
    assertThat(orphanResult.count()).isGreaterThan(0);
    assertThat(orphanResult.status()).isEqualTo(ValidationStatus.WARN);

    // DANGLING_METHOD_NODES should fail (ERROR severity)
    ValidationQueryResult methodResult = findByName(report, "DANGLING_METHOD_NODES");
    assertThat(methodResult.count()).isGreaterThan(0);
    assertThat(methodResult.status()).isEqualTo(ValidationStatus.FAIL);

    // DANGLING_FIELD_NODES should fail (ERROR severity)
    ValidationQueryResult fieldResult = findByName(report, "DANGLING_FIELD_NODES");
    assertThat(fieldResult.count()).isGreaterThan(0);
    assertThat(fieldResult.status()).isEqualTo(ValidationStatus.FAIL);
  }

  @Test
  void detectsBrokenInheritanceChain() {
    // Add a class whose parent exists in the graph but no EXTENDS edge
    neo4jClient.query(
        "CREATE (:JavaClass {fullyQualifiedName: 'com.example.service.ChildService', "
        + "simpleName: 'ChildService', packageName: 'com.example.service', "
        + "superClass: 'com.example.service.BaseService', "
        + "implementedInterfaces: [], annotations: []})"
    ).run();
    // Add to package
    neo4jClient.query(
        "MATCH (p:JavaPackage {packageName: 'com.example.service'}), "
        + "(c:JavaClass {fullyQualifiedName: 'com.example.service.ChildService'}) "
        + "CREATE (p)-[:CONTAINS_CLASS]->(c)"
    ).run();
    // NOTE: NO EXTENDS edge created — this is the broken state we want to detect

    ResponseEntity<ValidationReport> response =
        restTemplate.getForEntity("/api/graph/validation", ValidationReport.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ValidationReport report = response.getBody();
    assertThat(report).isNotNull();

    ValidationQueryResult result = findByName(report, "INHERITANCE_CHAIN_COMPLETENESS");
    assertThat(result.count()).isGreaterThan(0);
    assertThat(result.status()).isEqualTo(ValidationStatus.FAIL);
    // Details should contain the class FQN
    assertThat(result.details())
        .anyMatch(d -> d.contains("com.example.service.ChildService"));
  }

  @Test
  void detectsArchitecturalPatternViolations() {
    // Add a Service with no DEPENDS_ON edge
    neo4jClient.query(
        "CREATE (:JavaClass:Service {fullyQualifiedName: 'com.example.service.LonelyService', "
        + "simpleName: 'LonelyService', packageName: 'com.example.service', "
        + "superClass: '', implementedInterfaces: [], "
        + "annotations: ['org.springframework.stereotype.Service']})"
    ).run();
    neo4jClient.query(
        "MATCH (p:JavaPackage {packageName: 'com.example.service'}), "
        + "(c:JavaClass {fullyQualifiedName: 'com.example.service.LonelyService'}) "
        + "CREATE (p)-[:CONTAINS_CLASS]->(c)"
    ).run();

    // Add a Repository with no QUERIES edges on its methods
    neo4jClient.query(
        "CREATE (:JavaClass:Repository {fullyQualifiedName: 'com.example.repo.EmptyRepository', "
        + "simpleName: 'EmptyRepository', packageName: 'com.example.repo', "
        + "superClass: '', implementedInterfaces: [], annotations: []})"
    ).run();
    neo4jClient.query(
        "MATCH (p:JavaPackage {packageName: 'com.example.repo'}), "
        + "(c:JavaClass {fullyQualifiedName: 'com.example.repo.EmptyRepository'}) "
        + "CREATE (p)-[:CONTAINS_CLASS]->(c)"
    ).run();
    // No methods on EmptyRepository -> no QUERIES edges

    // Add a VaadinView with no BINDS_TO edge
    neo4jClient.query(
        "CREATE (:JavaClass:VaadinView {fullyQualifiedName: 'com.example.view.UnboundView', "
        + "simpleName: 'UnboundView', packageName: 'com.example.view', "
        + "superClass: '', implementedInterfaces: [], annotations: []})"
    ).run();
    neo4jClient.query(
        "MATCH (p:JavaPackage {packageName: 'com.example.view'}), "
        + "(c:JavaClass {fullyQualifiedName: 'com.example.view.UnboundView'}) "
        + "CREATE (p)-[:CONTAINS_CLASS]->(c)"
    ).run();

    ResponseEntity<ValidationReport> response =
        restTemplate.getForEntity("/api/graph/validation", ValidationReport.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ValidationReport report = response.getBody();
    assertThat(report).isNotNull();

    ValidationQueryResult serviceResult = findByName(report, "SERVICE_HAS_DEPENDENCIES");
    assertThat(serviceResult.count()).isGreaterThan(0);
    assertThat(serviceResult.status()).isEqualTo(ValidationStatus.WARN);

    ValidationQueryResult repoResult = findByName(report, "REPOSITORY_HAS_QUERIES");
    assertThat(repoResult.count()).isGreaterThan(0);
    assertThat(repoResult.status()).isEqualTo(ValidationStatus.WARN);

    ValidationQueryResult viewResult = findByName(report, "UI_VIEW_HAS_BINDS_TO");
    assertThat(viewResult.count()).isGreaterThan(0);
    assertThat(viewResult.status()).isEqualTo(ValidationStatus.WARN);
  }

  @Test
  void reportStructure() {
    ResponseEntity<ValidationReport> response =
        restTemplate.getForEntity("/api/graph/validation", ValidationReport.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ValidationReport report = response.getBody();
    assertThat(report).isNotNull();

    // generatedAt is an ISO-8601 string (not null, not empty)
    assertThat(report.generatedAt()).isNotNull().isNotEmpty();
    // Basic ISO-8601 check: contains 'T' and 'Z' or '+' offset
    assertThat(report.generatedAt()).contains("T");

    // Exactly 20 results
    assertThat(report.results()).hasSize(20);

    // Each result has required fields
    for (ValidationQueryResult result : report.results()) {
      assertThat(result.name()).isNotNull().isNotEmpty();
      assertThat(result.description()).isNotNull().isNotEmpty();
      assertThat(result.severity()).isNotNull();
      assertThat(result.status()).isNotNull();
      assertThat(result.count()).isGreaterThanOrEqualTo(0);
      assertThat(result.details()).isNotNull();
    }

    // errorCount + warnCount + passCount == 20
    assertThat(report.errorCount() + report.warnCount() + report.passCount())
        .isEqualTo(20);
  }

  // --- helpers ---

  private ValidationQueryResult findByName(ValidationReport report, String name) {
    return report.results().stream()
        .filter(r -> r.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Query result not found: " + name));
  }
}
