package com.esmp.graph.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
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
 * Integration tests for {@link LexiconController} proving all REST API endpoints work
 * against a real Neo4j instance via Testcontainers.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>GET /api/lexicon/ returns all terms
 *   <li>GET /api/lexicon/?criticality=High filters by criticality
 *   <li>GET /api/lexicon/{termId} returns single term with relatedClassFqns
 *   <li>PUT /api/lexicon/{termId} updates term and sets curated=true
 *   <li>PUT /api/lexicon/nonexistent returns 404
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LexiconControllerTest {

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
  private TestRestTemplate restTemplate;

  @Autowired
  private Neo4jClient neo4jClient;

  @BeforeEach
  void clearDatabase() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    // Insert test business terms
    neo4jClient.query("""
        CREATE (t1:BusinessTerm {termId: 'invoice', displayName: 'Invoice',
                definition: 'A billing document', criticality: 'High',
                migrationSensitivity: 'Critical', synonyms: ['bill', 'receipt'],
                curated: false, status: 'auto', sourceType: 'CLASS_NAME',
                primarySourceFqn: 'com.test.InvoiceService', usageCount: 3})
        CREATE (t2:BusinessTerm {termId: 'payment', displayName: 'Payment',
                definition: 'A financial transaction', criticality: 'High',
                migrationSensitivity: 'Critical', synonyms: [],
                curated: false, status: 'auto', sourceType: 'CLASS_NAME',
                primarySourceFqn: 'com.test.PaymentService', usageCount: 2})
        CREATE (t3:BusinessTerm {termId: 'order', displayName: 'Order',
                definition: 'A purchase order', criticality: 'Low',
                migrationSensitivity: 'None', synonyms: [],
                curated: false, status: 'auto', sourceType: 'CLASS_NAME',
                primarySourceFqn: 'com.test.OrderService', usageCount: 1})
        CREATE (c1:JavaClass {fullyQualifiedName: 'com.test.InvoiceService',
                simpleName: 'InvoiceService', packageName: 'com.test',
                annotations: [], implementedInterfaces: [], extraLabels: []})
        CREATE (c1)-[:USES_TERM]->(t1)
        """).run();
  }

  // ---------------------------------------------------------------------------
  // GET /api/lexicon/ — list all terms
  // ---------------------------------------------------------------------------

  @Test
  void getAllTerms_returnsAllTermsInDatabase() {
    ResponseEntity<List<BusinessTermResponse>> response = restTemplate.exchange(
        "/api/lexicon/",
        HttpMethod.GET,
        null,
        new ParameterizedTypeReference<List<BusinessTermResponse>>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody()).hasSize(3);
  }

  @Test
  void getAllTerms_responseIncludesAllFields() {
    ResponseEntity<List<BusinessTermResponse>> response = restTemplate.exchange(
        "/api/lexicon/",
        HttpMethod.GET,
        null,
        new ParameterizedTypeReference<List<BusinessTermResponse>>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();

    BusinessTermResponse invoice = response.getBody().stream()
        .filter(t -> "invoice".equals(t.termId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("invoice term not found in response"));

    assertThat(invoice.displayName()).isEqualTo("Invoice");
    assertThat(invoice.definition()).isEqualTo("A billing document");
    assertThat(invoice.criticality()).isEqualTo("High");
    assertThat(invoice.curated()).isFalse();
    assertThat(invoice.status()).isEqualTo("auto");
    assertThat(invoice.sourceType()).isEqualTo("CLASS_NAME");
    assertThat(invoice.primarySourceFqn()).isEqualTo("com.test.InvoiceService");
    assertThat(invoice.usageCount()).isEqualTo(3);
  }

  @Test
  void getAllTerms_withCriticalityFilter_returnsFilteredTerms() {
    ResponseEntity<List<BusinessTermResponse>> response = restTemplate.exchange(
        "/api/lexicon/?criticality=High",
        HttpMethod.GET,
        null,
        new ParameterizedTypeReference<List<BusinessTermResponse>>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody()).hasSize(2); // invoice + payment are High
    assertThat(response.getBody()).allMatch(t -> "High".equals(t.criticality()));
  }

  @Test
  void getAllTerms_withSearchFilter_returnsMatchingTerms() {
    ResponseEntity<List<BusinessTermResponse>> response = restTemplate.exchange(
        "/api/lexicon/?search=inv",
        HttpMethod.GET,
        null,
        new ParameterizedTypeReference<List<BusinessTermResponse>>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody()).hasSize(1);
    assertThat(response.getBody().get(0).termId()).isEqualTo("invoice");
  }

  // ---------------------------------------------------------------------------
  // GET /api/lexicon/{termId} — single term detail
  // ---------------------------------------------------------------------------

  @Test
  void getTermById_returnsTermWithRelatedClassFqns() {
    ResponseEntity<BusinessTermResponse> response = restTemplate.getForEntity(
        "/api/lexicon/invoice",
        BusinessTermResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().termId()).isEqualTo("invoice");
    assertThat(response.getBody().relatedClassFqns()).contains("com.test.InvoiceService");
  }

  @Test
  void getTermById_nonExistentTerm_returns404() {
    ResponseEntity<BusinessTermResponse> response = restTemplate.getForEntity(
        "/api/lexicon/nonexistent",
        BusinessTermResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ---------------------------------------------------------------------------
  // PUT /api/lexicon/{termId} — update term
  // ---------------------------------------------------------------------------

  @Test
  void updateTerm_persistsChangesAndSetsCurated() {
    UpdateTermRequest request = new UpdateTermRequest(
        "An official billing document",
        "High",
        List.of("bill", "receipt", "invoice-doc"));

    ResponseEntity<BusinessTermResponse> response = restTemplate.exchange(
        "/api/lexicon/invoice",
        HttpMethod.PUT,
        new HttpEntity<>(request),
        BusinessTermResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().definition()).isEqualTo("An official billing document");
    assertThat(response.getBody().criticality()).isEqualTo("High");
    assertThat(response.getBody().synonyms()).containsExactly("bill", "receipt", "invoice-doc");
    assertThat(response.getBody().curated()).isTrue();
    assertThat(response.getBody().status()).isEqualTo("curated");
  }

  @Test
  void updateTerm_nonExistentTerm_returns404() {
    UpdateTermRequest request = new UpdateTermRequest(
        "Some definition",
        "Low",
        List.of());

    ResponseEntity<BusinessTermResponse> response = restTemplate.exchange(
        "/api/lexicon/nonexistent-term-xyz",
        HttpMethod.PUT,
        new HttpEntity<>(request),
        BusinessTermResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
