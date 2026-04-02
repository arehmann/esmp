package com.esmp.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.mcp.api.MigrationContext;
import com.esmp.mcp.application.McpCacheEvictionService;
import com.esmp.vector.application.VectorIndexingService;
import com.esmp.vector.api.SearchResponse;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
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
 * Integration tests for {@link MigrationToolService} covering the 6 retained MCP tools.
 *
 * <p>Uses pilot fixtures (com.esmp.pilot package) with Testcontainers (Neo4j + MySQL + Qdrant).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class MigrationToolServiceIntegrationTest {

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
  private MigrationToolService toolService;

  @Autowired
  private McpCacheEvictionService mcpCacheEvictionService;

  @Autowired
  private VectorIndexingService vectorIndexingService;

  @Autowired
  private Neo4jClient neo4jClient;

  @Autowired
  private CacheManager cacheManager;

  private static final String PKG = "com.esmp.pilot";
  private static final String MODULE = "pilot";
  private static final String INVOICE_SERVICE = PKG + ".InvoiceService";

  /** One-time setup guard — pilot data seeded only before first test. */
  private static boolean setUpDone = false;

  // ---------------------------------------------------------------------------
  // One-time setup: seed Neo4j with pilot data and index into Qdrant
  // ---------------------------------------------------------------------------

  @BeforeEach
  void setUpOnce(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
    if (setUpDone) return;
    setUpDone = true;

    copyFixtures(tempDir);
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    createAllClassNodes();
    createRelationships();
    createBusinessTerms();

    vectorIndexingService.indexAll(tempDir.toString());
  }

  // ---------------------------------------------------------------------------
  // MCP-03: searchKnowledge
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("MCP-03: searchKnowledge returns non-null results for a keyword query")
  void testSearchKnowledge_returnsResults_MCP03() {
    SearchResponse response = toolService.searchKnowledge("InvoiceService", null, null, 5);

    assertThat(response).isNotNull();
    assertThat(response.results()).isNotNull();
    assertThat(response.results()).isNotEmpty();
  }

  // ---------------------------------------------------------------------------
  // getMigrationContext end-to-end
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getMigrationContext end-to-end returns MigrationContext with meaningful completeness")
  void testGetMigrationContext_endToEnd_returnsContext() {
    MigrationContext ctx = toolService.getMigrationContext(INVOICE_SERVICE);

    assertThat(ctx).isNotNull();
    assertThat(ctx.classFqn()).isEqualTo(INVOICE_SERVICE);
    assertThat(ctx.contextCompleteness()).isGreaterThan(0.5);
    assertThat(ctx.durationMs()).isGreaterThan(0L);
  }

  // ---------------------------------------------------------------------------
  // getRecipeBookGaps — project-wide (no classFqn filter)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getRecipeBookGaps returns list (possibly empty) without classFqn filter")
  void testGetRecipeBookGaps_noFilter_returnsList() {
    var gaps = toolService.getRecipeBookGaps(null);
    assertThat(gaps).isNotNull();
  }

  // ---------------------------------------------------------------------------
  // Fixture helpers (mirrors MigrationContextAssemblerTest)
  // ---------------------------------------------------------------------------

  private void copyFixtures(Path destination) throws IOException {
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
      try (InputStream is = MigrationToolServiceIntegrationTest.class.getResourceAsStream(resource)) {
        Objects.requireNonNull(is, "Fixture not found: " + resource);
        Files.copy(is, destination.resolve(name));
      }
    }
  }

  private void createAllClassNodes() {
    createClassNode(INVOICE_SERVICE, "InvoiceService", "Service", 0.4, 0.55, 0.7, 0.1, 0.6, 0.2);
    createClassNode(PKG + ".InvoiceRepository", "InvoiceRepository", "Repository",
        0.2, 0.35, 0.4, 0.0, 0.4, 0.1);
    createClassNode(PKG + ".InvoiceEntity", "InvoiceEntity", "Entity",
        0.1, 0.25, 0.6, 0.0, 0.5, 0.1);
    createClassNode(PKG + ".PaymentService", "PaymentService", "Service",
        0.5, 0.65, 0.6, 0.3, 0.9, 0.3);
    createClassNode(PKG + ".PaymentRepository", "PaymentRepository", "Repository",
        0.3, 0.40, 0.4, 0.1, 0.5, 0.1);
    createClassNode(PKG + ".PaymentEntity", "PaymentEntity", "Entity",
        0.1, 0.28, 0.5, 0.0, 0.6, 0.1);
    createClassNode(PKG + ".CustomerService", "CustomerService", "Service",
        0.3, 0.45, 0.5, 0.1, 0.3, 0.2);
    createClassNode(PKG + ".CustomerRepository", "CustomerRepository", "Repository",
        0.2, 0.30, 0.3, 0.0, 0.2, 0.1);
    createClassNode(PKG + ".CustomerEntity", "CustomerEntity", "Entity",
        0.1, 0.20, 0.4, 0.0, 0.2, 0.1);
    createVaadinViewNode(PKG + ".InvoiceView", "InvoiceView", 0.5, 0.60, 0.4, 0.1, 0.3, 0.1);
    createVaadinViewNode(PKG + ".CustomerView", "CustomerView", 0.4, 0.50, 0.3, 0.1, 0.2, 0.1);
    createVaadinViewNode(PKG + ".PaymentView", "PaymentView", 0.5, 0.55, 0.4, 0.1, 0.4, 0.1);
    createVaadinDataBindingNode(PKG + ".InvoiceForm", "InvoiceForm", 0.3, 0.40, 0.3, 0.1, 0.3, 0.1);
    createVaadinDataBindingNode(PKG + ".CustomerForm", "CustomerForm", 0.3, 0.35, 0.3, 0.1, 0.2, 0.1);
    createClassNode(PKG + ".InvoiceValidator", "InvoiceValidator", "Service",
        0.6, 0.70, 0.5, 0.2, 0.4, 0.8);
    createClassNode(PKG + ".PaymentCalculator", "PaymentCalculator", "Service",
        0.7, 0.75, 0.4, 0.2, 0.8, 0.7);
    createClassNode(PKG + ".AuditService", "AuditService", "Service",
        0.4, 0.60, 0.5, 0.8, 0.2, 0.1);
    createClassNode(PKG + ".InvoiceStatusEnum", "InvoiceStatusEnum", "Enum",
        0.1, 0.15, 0.5, 0.0, 0.4, 0.3);
    createClassNode(PKG + ".PaymentStatusEnum", "PaymentStatusEnum", "Enum",
        0.1, 0.12, 0.4, 0.0, 0.5, 0.2);
    createClassNode(PKG + ".CustomerRole", "CustomerRole", "Enum", 0.1, 0.10, 0.3, 0.1, 0.1, 0.1);
  }

  private void createClassNode(String fqn, String simpleName, String extraLabel,
      double srs, double ers, double dc, double ss, double fi, double brd) {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("path", simpleName + ".java");
    params.put("hash", "hash-" + simpleName.toLowerCase() + "-v1");
    params.put("srs", srs);
    params.put("ers", ers);
    params.put("dc", dc);
    params.put("ss", ss);
    params.put("fi", fi);
    params.put("brd", brd);
    neo4jClient.query("""
        CREATE (c:JavaClass:%s {
            fullyQualifiedName: $fqn, simpleName: $simpleName,
            packageName: $pkg, module: $module, sourceFilePath: $path,
            contentHash: $hash, structuralRiskScore: $srs, enhancedRiskScore: $ers,
            domainCriticality: $dc, securitySensitivity: $ss,
            financialInvolvement: $fi, businessRuleDensity: $brd,
            complexitySum: 3, complexityMax: 2, fanIn: 0, fanOut: 0,
            hasDbWrites: false, dbWriteCount: 0
        })
        """.formatted(extraLabel))
        .bindAll(params)
        .run();
  }

  private void createVaadinViewNode(String fqn, String simpleName,
      double srs, double ers, double dc, double ss, double fi, double brd) {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("path", simpleName + ".java");
    params.put("hash", "hash-" + simpleName.toLowerCase() + "-v1");
    params.put("srs", srs);
    params.put("ers", ers);
    params.put("dc", dc);
    params.put("ss", ss);
    params.put("fi", fi);
    params.put("brd", brd);
    neo4jClient.query("""
        CREATE (c:JavaClass:VaadinView {
            fullyQualifiedName: $fqn, simpleName: $simpleName,
            packageName: $pkg, module: $module, sourceFilePath: $path,
            contentHash: $hash, structuralRiskScore: $srs, enhancedRiskScore: $ers,
            domainCriticality: $dc, securitySensitivity: $ss,
            financialInvolvement: $fi, businessRuleDensity: $brd,
            complexitySum: 4, complexityMax: 3, fanIn: 0, fanOut: 0,
            hasDbWrites: false, dbWriteCount: 0
        })
        """)
        .bindAll(params)
        .run();
  }

  private void createVaadinDataBindingNode(String fqn, String simpleName,
      double srs, double ers, double dc, double ss, double fi, double brd) {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("path", simpleName + ".java");
    params.put("hash", "hash-" + simpleName.toLowerCase() + "-v1");
    params.put("srs", srs);
    params.put("ers", ers);
    params.put("dc", dc);
    params.put("ss", ss);
    params.put("fi", fi);
    params.put("brd", brd);
    neo4jClient.query("""
        CREATE (c:JavaClass:VaadinDataBinding {
            fullyQualifiedName: $fqn, simpleName: $simpleName,
            packageName: $pkg, module: $module, sourceFilePath: $path,
            contentHash: $hash, structuralRiskScore: $srs, enhancedRiskScore: $ers,
            domainCriticality: $dc, securitySensitivity: $ss,
            financialInvolvement: $fi, businessRuleDensity: $brd,
            complexitySum: 2, complexityMax: 2, fanIn: 0, fanOut: 0,
            hasDbWrites: false, dbWriteCount: 0
        })
        """)
        .bindAll(params)
        .run();
  }

  private void createRelationships() {
    createDependsOn(INVOICE_SERVICE, PKG + ".InvoiceRepository");
    createDependsOn(PKG + ".PaymentService", PKG + ".PaymentRepository");
    createDependsOn(PKG + ".CustomerService", PKG + ".CustomerRepository");
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

  private void createBusinessTerms() {
    neo4jClient.query("""
        MERGE (t:BusinessTerm {termId: 'invoice-term'})
        ON CREATE SET t.displayName = 'Invoice', t.criticality = 'High',
                      t.curated = false, t.status = 'ACTIVE', t.usageCount = 1,
                      t.definition = 'An invoice document', t.migrationSensitivity = 'Critical',
                      t.synonyms = [], t.sourceType = 'extraction', t.primarySourceFqn = $fqn
        """)
        .bindAll(Map.of("fqn", INVOICE_SERVICE))
        .run();
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        MATCH (t:BusinessTerm {termId: 'invoice-term'})
        MERGE (c)-[:USES_TERM]->(t)
        """)
        .bindAll(Map.of("fqn", INVOICE_SERVICE))
        .run();
  }
}
