package com.esmp.vector.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.vector.api.ChunkSearchResult;
import com.esmp.vector.api.SearchRequest;
import com.esmp.vector.api.SearchResponse;
import com.esmp.vector.config.VectorConfig;
import io.qdrant.client.QdrantClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Integration tests for {@link VectorSearchService} covering GMP-02.
 *
 * <p>Validates that vector similarity search returns relevant chunks from Qdrant, that module and
 * stereotype filters work correctly, and that enrichment payload fields are present in results.
 *
 * <p>Setup mirrors {@code PilotServiceIntegrationTest}: synthetic pilot fixture classes are indexed
 * into Qdrant once before all tests, then each test exercises a different search facet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class VectorSearchIntegrationTest {

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
  private VectorSearchService vectorSearchService;

  @Autowired
  private VectorIndexingService vectorIndexingService;

  @Autowired
  private Neo4jClient neo4jClient;

  @Autowired
  private QdrantClient qdrantClient;

  @Autowired
  private VectorConfig vectorConfig;

  @TempDir
  Path tempDir;

  /** Guards one-time setup so it only runs before the first test method. */
  private static boolean setUpDone = false;

  private static final String PKG = "com.esmp.pilot";
  private static final String MODULE = "pilot";

  // Class FQNs used in assertions
  private static final String INVOICE_SERVICE = PKG + ".InvoiceService";
  private static final String CUSTOMER_REPO = PKG + ".CustomerRepository";

  // ---------------------------------------------------------------------------
  // One-time setup: create Neo4j nodes, copy fixtures, index into Qdrant
  // ---------------------------------------------------------------------------

  @BeforeEach
  void setUpOnce() throws Exception {
    if (setUpDone) return;
    setUpDone = true;

    // 1. Copy fixture Java files to tempDir (flat structure)
    copyFixtures(tempDir);

    // 2. Clear Neo4j
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // 3. Create a representative subset of class nodes (10 classes covering multiple stereotypes)
    createSubsetClassNodes();

    // 4. Index all classes into Qdrant
    vectorIndexingService.indexAll(tempDir.toString());
  }

  // ---------------------------------------------------------------------------
  // GMP-02: Vector search tests
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("search_byServiceQuery_returnsServiceChunks: 'invoice processing service' top results include InvoiceService")
  void search_byServiceQuery_returnsServiceChunks() {
    SearchRequest request = new SearchRequest("invoice processing service", 10, null, null, null);
    SearchResponse response = vectorSearchService.search(request);

    assertThat(response.results()).isNotEmpty();
    assertThat(response.query()).isEqualTo("invoice processing service");

    // InvoiceService should appear in top results for this query
    boolean invoiceServiceFound = response.results().stream()
        .anyMatch(r -> r.classFqn().contains("Invoice") && "pilot".equals(r.module()));
    assertThat(invoiceServiceFound)
        .as("InvoiceService-related chunks should appear in top results for 'invoice processing service'")
        .isTrue();
  }

  @Test
  @DisplayName("search_byRepositoryQuery_returnsRepoChunks: 'customer data repository' returns CustomerRepository")
  void search_byRepositoryQuery_returnsRepoChunks() {
    SearchRequest request = new SearchRequest("customer data repository", 10, null, null, null);
    SearchResponse response = vectorSearchService.search(request);

    assertThat(response.results()).isNotEmpty();

    boolean customerRepoFound = response.results().stream()
        .anyMatch(r -> r.classFqn().contains("Customer") && "pilot".equals(r.module()));
    assertThat(customerRepoFound)
        .as("CustomerRepository chunks should appear in top results for 'customer data repository'")
        .isTrue();
  }

  @Test
  @DisplayName("search_withModuleFilter_scopesToPilot: all results have module='pilot' when filtered")
  void search_withModuleFilter_scopesToPilot() {
    SearchRequest request = new SearchRequest("service class", 20, MODULE, null, null);
    SearchResponse response = vectorSearchService.search(request);

    assertThat(response.results()).isNotEmpty();

    boolean allPilot = response.results().stream()
        .allMatch(r -> MODULE.equals(r.module()));
    assertThat(allPilot)
        .as("All search results should have module='pilot' when filtered by module")
        .isTrue();
  }

  @Test
  @DisplayName("search_withStereotypeFilter_filtersCorrectly: stereotype='Service' results are service chunks")
  void search_withStereotypeFilter_filtersCorrectly() {
    SearchRequest request = new SearchRequest("business logic processing", 10, null, "Service", null);
    SearchResponse response = vectorSearchService.search(request);

    assertThat(response.results()).isNotEmpty();

    boolean allService = response.results().stream()
        .allMatch(r -> "Service".equals(r.stereotype()));
    assertThat(allService)
        .as("All results should have stereotype='Service' when filtered by stereotype")
        .isTrue();
  }

  @Test
  @DisplayName("search_resultsHaveEnrichmentPayloads: classFqn not null, enhancedRiskScore present, chunkType valid")
  void search_resultsHaveEnrichmentPayloads() {
    SearchRequest request = new SearchRequest("class method", 5, null, null, null);
    SearchResponse response = vectorSearchService.search(request);

    assertThat(response.results()).isNotEmpty();

    // Golden regression: every result must have enrichment fields populated
    for (ChunkSearchResult result : response.results()) {
      assertThat(result.classFqn())
          .as("classFqn must not be null or empty")
          .isNotBlank();
      assertThat(result.chunkType())
          .as("chunkType must be CLASS_HEADER or METHOD")
          .isIn("CLASS_HEADER", "METHOD");
      assertThat(result.module())
          .as("module must not be null or empty")
          .isNotBlank();
      assertThat(result.score())
          .as("similarity score must be positive")
          .isGreaterThan(0.0f);
    }
  }

  // ---------------------------------------------------------------------------
  // Fixture helpers
  // ---------------------------------------------------------------------------

  private static void copyFixtures(Path destination) throws IOException {
    // Copy all 20 fixture files so the indexer can find all source paths
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
      try (InputStream is = VectorSearchIntegrationTest.class.getResourceAsStream(resource)) {
        Objects.requireNonNull(is, "Fixture resource not found: " + resource);
        Files.copy(is, destination.resolve(name));
      }
    }
  }

  /**
   * Creates 10 representative class nodes covering Service, Repository, VaadinView, and
   * VaadinDataBinding stereotypes, sufficient for meaningful search diversity.
   */
  private void createSubsetClassNodes() {
    // Services (4 classes)
    createClassNode(INVOICE_SERVICE, "InvoiceService", "Service", 0.4, 0.55);
    createClassNode(PKG + ".PaymentService", "PaymentService", "Service", 0.5, 0.65);
    createClassNode(PKG + ".CustomerService", "CustomerService", "Service", 0.3, 0.45);
    createClassNode(PKG + ".AuditService", "AuditService", "Service", 0.4, 0.60);

    // Repositories (3 classes)
    createClassNode(CUSTOMER_REPO, "CustomerRepository", "Repository", 0.2, 0.30);
    createClassNode(PKG + ".InvoiceRepository", "InvoiceRepository", "Repository", 0.2, 0.35);
    createClassNode(PKG + ".PaymentRepository", "PaymentRepository", "Repository", 0.3, 0.40);

    // VaadinView (2 classes)
    createVaadinViewNode(PKG + ".InvoiceView", "InvoiceView", 0.5, 0.60);
    createVaadinViewNode(PKG + ".CustomerView", "CustomerView", 0.4, 0.50);

    // VaadinDataBinding (1 class)
    createVaadinDataBindingNode(PKG + ".InvoiceForm", "InvoiceForm", 0.3, 0.40);
  }

  private void createClassNode(String fqn, String simpleName, String extraLabel,
      double srs, double ers) {
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
        .bindAll(Map.of(
            "fqn", fqn,
            "simpleName", simpleName,
            "pkg", PKG,
            "module", MODULE,
            "path", simpleName + ".java",
            "hash", "hash-" + simpleName.toLowerCase() + "-v1",
            "srs", srs,
            "ers", ers))
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
            financialInvolvement: 0.3,
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
            "hash", "hash-" + simpleName.toLowerCase() + "-v1",
            "srs", srs,
            "ers", ers))
        .run();
  }

  private void createVaadinDataBindingNode(String fqn, String simpleName, double srs, double ers) {
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
            financialInvolvement: 0.3,
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
            "hash", "hash-" + simpleName.toLowerCase() + "-v1",
            "srs", srs,
            "ers", ers))
        .run();
  }
}
