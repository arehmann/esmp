package com.esmp.rag.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.vector.api.IndexStatusResponse;
import com.esmp.vector.application.VectorIndexingService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Integration tests for {@link RagController} covering the RAG-04 REST endpoint.
 *
 * <p>Tests the full HTTP request/response cycle: input validation (400), unknown FQN (404),
 * valid FQN assembly (200), and natural language query (200).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RagControllerIntegrationTest {

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
  private VectorIndexingService vectorIndexingService;

  @Autowired
  private Neo4jClient neo4jClient;

  @TempDir
  Path tempDir;

  /** Guards one-time setup so it only runs before the first test method. */
  private static boolean setUpDone = false;

  private static final String PKG = "com.esmp.pilot";
  private static final String MODULE = "pilot";

  private static final String INVOICE_SERVICE  = PKG + ".InvoiceService";
  private static final String PAYMENT_SERVICE  = PKG + ".PaymentService";
  private static final String CUSTOMER_SERVICE = PKG + ".CustomerService";
  private static final String AUDIT_SERVICE    = PKG + ".AuditService";
  private static final String INVOICE_REPO     = PKG + ".InvoiceRepository";
  private static final String CUSTOMER_REPO    = PKG + ".CustomerRepository";
  private static final String PAYMENT_REPO     = PKG + ".PaymentRepository";
  private static final String INVOICE_ENTITY   = PKG + ".InvoiceEntity";
  private static final String CUSTOMER_ENTITY  = PKG + ".CustomerEntity";
  private static final String PAYMENT_ENTITY   = PKG + ".PaymentEntity";
  private static final String INVOICE_VIEW     = PKG + ".InvoiceView";
  private static final String CUSTOMER_VIEW    = PKG + ".CustomerView";
  private static final String PAYMENT_VIEW     = PKG + ".PaymentView";
  private static final String INVOICE_FORM     = PKG + ".InvoiceForm";
  private static final String CUSTOMER_FORM    = PKG + ".CustomerForm";
  private static final String INVOICE_VALIDATOR    = PKG + ".InvoiceValidator";
  private static final String PAYMENT_CALCULATOR   = PKG + ".PaymentCalculator";
  private static final String INVOICE_STATUS_ENUM  = PKG + ".InvoiceStatusEnum";
  private static final String PAYMENT_STATUS_ENUM  = PKG + ".PaymentStatusEnum";
  private static final String CUSTOMER_ROLE        = PKG + ".CustomerRole";

  // ---------------------------------------------------------------------------
  // One-time setup: seed Neo4j, copy fixtures, index into Qdrant
  // ---------------------------------------------------------------------------

  @BeforeEach
  void setUpOnce() throws Exception {
    if (setUpDone) return;
    setUpDone = true;

    copyFixtures(tempDir);
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    createAllClassNodes();
    createRelationships();

    vectorIndexingService.indexAll(tempDir.toString());
  }

  // ---------------------------------------------------------------------------
  // Tests: RAG-04 REST endpoint
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("RAG-04: POST /api/rag/context with valid FQN returns 200 with focalClass and contextChunks")
  void testPostContext_WithFqn_Returns200WithRagResponse() {
    RagRequest request = new RagRequest(null, INVOICE_SERVICE, 10, null, null, false);
    ResponseEntity<String> response = restTemplate.postForEntity(
        "/api/rag/context", request, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    String body = response.getBody();
    assertThat(body)
        .as("Response body must not be null")
        .isNotNull();
    assertThat(body)
        .as("Response should contain focalClass")
        .contains("focalClass");
    assertThat(body)
        .as("Response should contain coneSummary")
        .contains("coneSummary");
    assertThat(body)
        .as("Response should contain queryType FQN")
        .contains("FQN");
  }

  @Test
  @DisplayName("RAG-04: POST /api/rag/context with blank query returns 400")
  void testPostContext_WithBlankQuery_Returns400() {
    RagRequest request = new RagRequest("", null, 10, null, null, false);
    ResponseEntity<String> response = restTemplate.postForEntity(
        "/api/rag/context", request, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .as("Error response should mention missing query/fqn")
        .contains("query");
  }

  @Test
  @DisplayName("RAG-04: POST /api/rag/context with unknown FQN returns 404")
  void testPostContext_WithUnknownFqn_Returns404() {
    RagRequest request = new RagRequest(null, "com.nonexistent.Foo", 10, null, null, false);
    ResponseEntity<String> response = restTemplate.postForEntity(
        "/api/rag/context", request, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody())
        .as("Error response should mention the missing class")
        .contains("No class found");
  }

  @Test
  @DisplayName("RAG-04: POST /api/rag/context with natural language query returns 200")
  void testPostContext_NaturalLanguage_Returns200() {
    RagRequest request = new RagRequest("invoice processing", null, 10, null, null, false);
    ResponseEntity<String> response = restTemplate.postForEntity(
        "/api/rag/context", request, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .as("Natural language response should contain queryType")
        .contains("NATURAL_LANGUAGE");
  }

  // ---------------------------------------------------------------------------
  // Fixture helpers
  // ---------------------------------------------------------------------------

  private static void copyFixtures(Path destination) throws IOException {
    String[] fixtureNames = {
        "InvoiceService.java", "PaymentService.java", "CustomerService.java", "AuditService.java",
        "InvoiceRepository.java", "CustomerRepository.java", "PaymentRepository.java",
        "InvoiceEntity.java", "CustomerEntity.java", "PaymentEntity.java",
        "InvoiceView.java", "CustomerView.java", "PaymentView.java",
        "InvoiceForm.java", "CustomerForm.java",
        "InvoiceValidator.java", "PaymentCalculator.java",
        "InvoiceStatusEnum.java", "PaymentStatusEnum.java", "CustomerRole.java"
    };
    for (String name : fixtureNames) {
      String resource = "/fixtures/pilot/" + name;
      try (InputStream is = RagControllerIntegrationTest.class.getResourceAsStream(resource)) {
        Objects.requireNonNull(is, "Fixture not found: " + resource);
        Files.copy(is, destination.resolve(name));
      }
    }
  }

  private void createAllClassNodes() {
    createClassNode(INVOICE_SERVICE, "InvoiceService", "Service", 0.4, 0.55);
    createClassNode(PAYMENT_SERVICE, "PaymentService", "Service", 0.5, 0.65);
    createClassNode(CUSTOMER_SERVICE, "CustomerService", "Service", 0.3, 0.45);
    createClassNode(AUDIT_SERVICE, "AuditService", "Service", 0.4, 0.60);
    createClassNode(INVOICE_REPO, "InvoiceRepository", "Repository", 0.2, 0.35);
    createClassNode(CUSTOMER_REPO, "CustomerRepository", "Repository", 0.2, 0.30);
    createClassNode(PAYMENT_REPO, "PaymentRepository", "Repository", 0.3, 0.40);
    createClassNode(INVOICE_ENTITY, "InvoiceEntity", "Entity", 0.1, 0.25);
    createClassNode(CUSTOMER_ENTITY, "CustomerEntity", "Entity", 0.1, 0.20);
    createClassNode(PAYMENT_ENTITY, "PaymentEntity", "Entity", 0.1, 0.28);
    createVaadinViewNode(INVOICE_VIEW, "InvoiceView", 0.5, 0.60);
    createVaadinViewNode(CUSTOMER_VIEW, "CustomerView", 0.4, 0.50);
    createVaadinViewNode(PAYMENT_VIEW, "PaymentView", 0.5, 0.55);
    createVaadinDataBindingNode(INVOICE_FORM, "InvoiceForm", 0.3, 0.40);
    createVaadinDataBindingNode(CUSTOMER_FORM, "CustomerForm", 0.3, 0.35);
    createClassNode(INVOICE_VALIDATOR, "InvoiceValidator", "Service", 0.6, 0.70);
    createClassNode(PAYMENT_CALCULATOR, "PaymentCalculator", "Service", 0.7, 0.75);
    createClassNode(INVOICE_STATUS_ENUM, "InvoiceStatusEnum", "Enum", 0.1, 0.15);
    createClassNode(PAYMENT_STATUS_ENUM, "PaymentStatusEnum", "Enum", 0.1, 0.12);
    createClassNode(CUSTOMER_ROLE, "CustomerRole", "Enum", 0.1, 0.10);
  }

  private void createClassNode(String fqn, String simpleName, String extraLabel, double srs,
      double ers) {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("path", simpleName + ".java");
    params.put("hash", "hash-" + simpleName.toLowerCase() + "-ctrl-v1");
    params.put("srs", srs);
    params.put("ers", ers);
    neo4jClient.query("""
        CREATE (c:JavaClass:%s {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $pkg,
            module: $module,
            sourceFilePath: $path,
            contentHash: $hash,
            structuralRiskScore: $srs,
            enhancedRiskScore: $ers,
            domainCriticality: 0.5,
            securitySensitivity: 0.1,
            financialInvolvement: 0.3,
            businessRuleDensity: 0.2,
            complexitySum: 3,
            complexityMax: 2,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: false,
            dbWriteCount: 0
        })
        """.formatted(extraLabel))
        .bindAll(params)
        .run();
  }

  private void createVaadinViewNode(String fqn, String simpleName, double srs, double ers) {
    neo4jClient.query("""
        CREATE (c:JavaClass:VaadinView {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $pkg,
            module: $module,
            sourceFilePath: $path,
            contentHash: $hash,
            structuralRiskScore: $srs,
            enhancedRiskScore: $ers,
            domainCriticality: 0.4,
            securitySensitivity: 0.1,
            financialInvolvement: 0.2,
            businessRuleDensity: 0.1,
            complexitySum: 4,
            complexityMax: 3,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: false,
            dbWriteCount: 0
        })
        """)
        .bindAll(Map.of(
            "fqn", fqn,
            "simpleName", simpleName,
            "pkg", PKG,
            "module", MODULE,
            "path", simpleName + ".java",
            "hash", "hash-" + simpleName.toLowerCase() + "-ctrl-v1",
            "srs", srs,
            "ers", ers))
        .run();
  }

  private void createVaadinDataBindingNode(String fqn, String simpleName, double srs,
      double ers) {
    neo4jClient.query("""
        CREATE (c:JavaClass:VaadinDataBinding {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $pkg,
            module: $module,
            sourceFilePath: $path,
            contentHash: $hash,
            structuralRiskScore: $srs,
            enhancedRiskScore: $ers,
            domainCriticality: 0.3,
            securitySensitivity: 0.1,
            financialInvolvement: 0.2,
            businessRuleDensity: 0.1,
            complexitySum: 2,
            complexityMax: 2,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: false,
            dbWriteCount: 0
        })
        """)
        .bindAll(Map.of(
            "fqn", fqn,
            "simpleName", simpleName,
            "pkg", PKG,
            "module", MODULE,
            "path", simpleName + ".java",
            "hash", "hash-" + simpleName.toLowerCase() + "-ctrl-v1",
            "srs", srs,
            "ers", ers))
        .run();
  }

  private void createRelationships() {
    createDependsOn(INVOICE_SERVICE, INVOICE_REPO);
    createDependsOn(PAYMENT_SERVICE, PAYMENT_REPO);
    createDependsOn(PAYMENT_SERVICE, INVOICE_SERVICE);
    createDependsOn(CUSTOMER_SERVICE, CUSTOMER_REPO);
    createDependsOn(INVOICE_VIEW, INVOICE_SERVICE);
    createDependsOn(CUSTOMER_VIEW, CUSTOMER_SERVICE);
    createDependsOn(PAYMENT_VIEW, PAYMENT_SERVICE);
    createDependsOn(INVOICE_FORM, INVOICE_ENTITY);
  }

  private void createDependsOn(String from, String to) {
    neo4jClient.query("""
        MATCH (a:JavaClass {fullyQualifiedName: $from})
        MATCH (b:JavaClass {fullyQualifiedName: $to})
        MERGE (a)-[:DEPENDS_ON]->(b)
        """)
        .bindAll(Map.of("from", from, "to", to))
        .run();
  }
}
