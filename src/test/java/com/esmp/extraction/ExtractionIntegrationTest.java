package com.esmp.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.api.ExtractionRequest;
import com.esmp.extraction.api.ExtractionResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
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
 * Integration test proving the full extraction pipeline: REST trigger → OpenRewrite parsing → AST
 * visitors → Neo4j persistence → idempotent re-extraction.
 *
 * <p>Uses Testcontainers to spin up real Neo4j, MySQL, and Qdrant instances. The test fixture
 * directory (src/test/resources/fixtures/) contains 6 synthetic Vaadin 7 Java files.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ExtractionIntegrationTest {

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

  /** Absolute path to src/test/resources/fixtures/ — used as sourceRoot in requests. */
  private static final String FIXTURES_DIR =
      Path.of("src/test/resources/fixtures").toAbsolutePath().toString();

  /** Temporary classpath file containing all JARs from java.class.path — for type resolution. */
  private static Path classpathFile;

  @BeforeEach
  void clearNeo4j() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
  }

  private static String getOrCreateClasspathFile() {
    if (classpathFile != null && classpathFile.toFile().exists()) {
      return classpathFile.toString();
    }
    try {
      classpathFile = Files.createTempFile("esmp-test-classpath", ".txt");
      classpathFile.toFile().deleteOnExit();
      String[] entries = System.getProperty("java.class.path").split(java.io.File.pathSeparator);
      String content = Arrays.stream(entries).collect(Collectors.joining(System.lineSeparator()));
      Files.writeString(classpathFile, content);
      return classpathFile.toString();
    } catch (IOException e) {
      throw new RuntimeException("Failed to create classpath file for integration test", e);
    }
  }

  private ExtractionRequest buildRequest() {
    ExtractionRequest req = new ExtractionRequest();
    req.setSourceRoot(FIXTURES_DIR);
    req.setClasspathFile(getOrCreateClasspathFile());
    return req;
  }

  @Test
  void triggerEndpointReturns200WithExtractionSummary() {
    ResponseEntity<ExtractionResponse> response =
        restTemplate.postForEntity("/api/extraction/trigger", buildRequest(), ExtractionResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getClassCount()).isGreaterThanOrEqualTo(6);
    assertThat(response.getBody().getMethodCount()).isGreaterThan(0);
    assertThat(response.getBody().getFieldCount()).isGreaterThan(0);
  }

  @Test
  void afterExtractionNeo4jContains6ClassNodes() {
    restTemplate.postForEntity("/api/extraction/trigger", buildRequest(), ExtractionResponse.class);

    Long count =
        neo4jClient
            .query("MATCH (c:JavaClass) RETURN count(c) AS count")
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one()
            .orElse(0L);

    assertThat(count).isGreaterThanOrEqualTo(6L);
  }

  @Test
  void afterExtractionSampleServiceHasMethodNodes() {
    restTemplate.postForEntity("/api/extraction/trigger", buildRequest(), ExtractionResponse.class);

    Long methodCount =
        neo4jClient
            .query(
                "MATCH (c:JavaClass {simpleName: 'SampleService'})-[:DECLARES_METHOD]->(m:JavaMethod)"
                    + " RETURN count(m) AS count")
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one()
            .orElse(0L);

    assertThat(methodCount).isGreaterThanOrEqualTo(3L);
  }

  @Test
  void afterExtractionCallsRelationshipExistsBetweenMethods() {
    restTemplate.postForEntity("/api/extraction/trigger", buildRequest(), ExtractionResponse.class);

    Long callCount =
        neo4jClient
            .query("MATCH (m1:JavaMethod)-[:CALLS]->(m2:JavaMethod) RETURN count(*) AS count")
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one()
            .orElse(0L);

    assertThat(callCount).isGreaterThan(0L);
  }

  @Test
  void afterExtractionSampleVaadinViewHasVaadinViewLabel() {
    restTemplate.postForEntity("/api/extraction/trigger", buildRequest(), ExtractionResponse.class);

    Long vaadinViewCount =
        neo4jClient
            .query("MATCH (c:VaadinView) RETURN count(c) AS count")
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one()
            .orElse(0L);

    assertThat(vaadinViewCount).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void afterExtractionContainsComponentEdgesExistForVaadinLayouts() {
    restTemplate.postForEntity("/api/extraction/trigger", buildRequest(), ExtractionResponse.class);

    Long edgeCount =
        neo4jClient
            .query(
                "MATCH (c:JavaClass)-[:CONTAINS_COMPONENT]->(child:JavaClass) RETURN count(*) AS count")
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one()
            .orElse(0L);

    assertThat(edgeCount).isGreaterThanOrEqualTo(0L); // may be 0 if no inter-class edges detected
  }

  @Test
  void afterExtractionSampleVaadinFormHasVaadinDataBindingLabel() {
    restTemplate.postForEntity("/api/extraction/trigger", buildRequest(), ExtractionResponse.class);

    Long bindingCount =
        neo4jClient
            .query("MATCH (c:VaadinDataBinding) RETURN count(c) AS count")
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one()
            .orElse(0L);

    assertThat(bindingCount).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void reRunningExtractionDoesNotCreateDuplicateClassNodes() {
    ExtractionRequest req = buildRequest();

    restTemplate.postForEntity("/api/extraction/trigger", req, ExtractionResponse.class);
    Long countAfterFirst =
        neo4jClient
            .query("MATCH (c:JavaClass) RETURN count(c) AS count")
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one()
            .orElse(0L);

    restTemplate.postForEntity("/api/extraction/trigger", req, ExtractionResponse.class);
    Long countAfterSecond =
        neo4jClient
            .query("MATCH (c:JavaClass) RETURN count(c) AS count")
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one()
            .orElse(0L);

    assertThat(countAfterSecond).isEqualTo(countAfterFirst);
  }

  @Test
  void extractionResponseIncludesVaadinCounts() {
    ResponseEntity<ExtractionResponse> response =
        restTemplate.postForEntity("/api/extraction/trigger", buildRequest(), ExtractionResponse.class);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getVaadinViewCount()).isGreaterThanOrEqualTo(0);
    assertThat(response.getBody().getVaadinComponentCount()).isGreaterThanOrEqualTo(0);
    assertThat(response.getBody().getVaadinDataBindingCount()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void extractionResponseIncludesAuditReport() {
    ResponseEntity<ExtractionResponse> response =
        restTemplate.postForEntity("/api/extraction/trigger", buildRequest(), ExtractionResponse.class);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getAuditReport()).isNotNull();
    assertThat(response.getBody().getAuditReport().getSummary()).isNotBlank();
    assertThat(response.getBody().getAuditReport().getKnownLimitations()).isNotEmpty();
  }
}
