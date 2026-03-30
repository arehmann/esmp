package com.esmp.graph.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
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
 * Integration tests for {@code GET /api/lexicon/by-class/{fqn}}.
 *
 * <p>Verifies that the endpoint returns the business terms linked to a given class via
 * USES_TERM edges, and returns an empty list when the class has no terms or does not exist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LexiconByClassIntegrationTest {

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
  void seed() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    neo4jClient.query("""
        CREATE (c:JavaClass {fullyQualifiedName: 'com.example.OrderService', simpleName: 'OrderService'})
        CREATE (t1:BusinessTerm {termId: 'order', displayName: 'Order', definition: 'A purchase order',
                criticality: 'High', migrationSensitivity: 'High', curated: false, status: 'ACTIVE',
                sourceType: 'EXTRACTED', primarySourceFqn: 'com.example.OrderService', usageCount: 5,
                synonyms: []})
        CREATE (t2:BusinessTerm {termId: 'invoice', displayName: 'Invoice', definition: 'A bill',
                criticality: 'Medium', migrationSensitivity: 'Low', curated: true, status: 'ACTIVE',
                sourceType: 'EXTRACTED', primarySourceFqn: 'com.example.InvoiceService', usageCount: 3,
                synonyms: []})
        CREATE (c)-[:USES_TERM]->(t1)
        CREATE (c)-[:USES_TERM]->(t2)
        """).run();
  }

  @Test
  void returnsTermsLinkedToClass() {
    ResponseEntity<BusinessTermResponse[]> response = restTemplate.getForEntity(
        "/api/lexicon/by-class/com.example.OrderService",
        BusinessTermResponse[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();

    List<String> termIds = Arrays.stream(response.getBody())
        .map(BusinessTermResponse::termId)
        .toList();

    assertThat(termIds).hasSize(2).containsExactlyInAnyOrder("order", "invoice");

    BusinessTermResponse orderTerm = Arrays.stream(response.getBody())
        .filter(t -> "order".equals(t.termId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected 'order' term not found in response"));
    assertThat(orderTerm.displayName()).isEqualTo("Order");
    assertThat(orderTerm.criticality()).isEqualTo("High");
  }

  @Test
  void returnsEmptyForClassWithNoTerms() {
    ResponseEntity<BusinessTermResponse[]> response = restTemplate.getForEntity(
        "/api/lexicon/by-class/com.example.Unknown",
        BusinessTermResponse[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull().isEmpty();
  }
}
