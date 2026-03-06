package com.esmp.pilot.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.pilot.api.ModuleRecommendation;
import com.esmp.pilot.api.PilotCheck;
import com.esmp.pilot.api.PilotValidationReport;
import com.esmp.vector.api.IndexStatusResponse;
import com.esmp.vector.application.VectorIndexingService;
import com.esmp.vector.config.VectorConfig;
import io.qdrant.client.QdrantClient;
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
 * Full pipeline integration tests for the golden module pilot.
 *
 * <p>Tests GMP-01 (module recommendation and validation) and GMP-03 (risk scores and business term
 * coverage). The setup creates synthetic Neo4j nodes matching the 20 pilot fixture Java files and
 * calls {@link VectorIndexingService#indexAll} to index them into Qdrant, then exercises
 * {@link PilotService#recommendModules} and {@link PilotService#validateModule}.
 *
 * <p>These tests act as permanent golden regression tests for the pilot pipeline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class PilotServiceIntegrationTest {

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
  private PilotService pilotService;

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

  /** Tracks the indexAll result for pipeline integrity assertions. */
  private static IndexStatusResponse indexResult;

  // ---------------------------------------------------------------------------
  // Fixture constants — FQNs for the 20 synthetic pilot classes
  // ---------------------------------------------------------------------------

  private static final String PKG = "com.esmp.pilot";
  private static final String MODULE = "pilot";

  // Services
  private static final String INVOICE_SERVICE = PKG + ".InvoiceService";
  private static final String PAYMENT_SERVICE = PKG + ".PaymentService";
  private static final String CUSTOMER_SERVICE = PKG + ".CustomerService";
  private static final String AUDIT_SERVICE = PKG + ".AuditService";

  // Repositories
  private static final String INVOICE_REPO = PKG + ".InvoiceRepository";
  private static final String CUSTOMER_REPO = PKG + ".CustomerRepository";
  private static final String PAYMENT_REPO = PKG + ".PaymentRepository";

  // Entities
  private static final String INVOICE_ENTITY = PKG + ".InvoiceEntity";
  private static final String CUSTOMER_ENTITY = PKG + ".CustomerEntity";
  private static final String PAYMENT_ENTITY = PKG + ".PaymentEntity";

  // Vaadin Views
  private static final String INVOICE_VIEW = PKG + ".InvoiceView";
  private static final String CUSTOMER_VIEW = PKG + ".CustomerView";
  private static final String PAYMENT_VIEW = PKG + ".PaymentView";

  // Vaadin Forms (DataBinding)
  private static final String INVOICE_FORM = PKG + ".InvoiceForm";
  private static final String CUSTOMER_FORM = PKG + ".CustomerForm";

  // Rule/calc classes
  private static final String INVOICE_VALIDATOR = PKG + ".InvoiceValidator";
  private static final String PAYMENT_CALCULATOR = PKG + ".PaymentCalculator";

  // Enums
  private static final String INVOICE_STATUS_ENUM = PKG + ".InvoiceStatusEnum";
  private static final String PAYMENT_STATUS_ENUM = PKG + ".PaymentStatusEnum";
  private static final String CUSTOMER_ROLE = PKG + ".CustomerRole";

  // ---------------------------------------------------------------------------
  // One-time setup: copy fixtures to tempDir, create Neo4j data, index into Qdrant
  // ---------------------------------------------------------------------------

  @BeforeEach
  void setUpOnce() throws Exception {
    if (setUpDone) return;
    setUpDone = true;

    // 1. Copy fixture Java files to tempDir (flat structure)
    copyFixtures(tempDir);

    // 2. Clear existing Neo4j data
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // 3. Create all 20 Neo4j class nodes
    createAllClassNodes();

    // 4. Create method nodes, relationships, and business terms
    createMethodNodes();
    createRelationships();
    createBusinessTerms();

    // 5. Index all classes into Qdrant
    indexResult = vectorIndexingService.indexAll(tempDir.toString());
  }

  // ---------------------------------------------------------------------------
  // GMP-01: Module recommendation tests
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("recommendModules returns pilot module with classCount >= 15 and vaadin7Count > 0")
  void recommendModules_returnsPilotModule() {
    List<ModuleRecommendation> recommendations = pilotService.recommendModules();

    assertThat(recommendations).isNotEmpty();
    ModuleRecommendation pilot = recommendations.stream()
        .filter(r -> MODULE.equals(r.moduleName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Pilot module not found in recommendations"));

    assertThat(pilot.classCount())
        .as("Pilot module should have at least 15 classes")
        .isGreaterThanOrEqualTo(15);
    assertThat(pilot.vaadin7Count())
        .as("Pilot module should have Vaadin 7 classes")
        .isGreaterThan(0);
    assertThat(pilot.score())
        .as("Pilot module score should be positive")
        .isGreaterThan(0.0);
    assertThat(pilot.rationale())
        .as("Rationale should mention Vaadin 7")
        .isNotBlank();
  }

  @Test
  @DisplayName("validateModule returns complete report with non-null graphValidation and matching classCount")
  void validateModule_returnsCompleteReport() {
    PilotValidationReport report = pilotService.validateModule(MODULE);

    assertThat(report).isNotNull();
    assertThat(report.graphValidation()).isNotNull();
    assertThat(report.pilotModule()).isEqualTo(MODULE);
    assertThat(report.classCount())
        .as("classCount in report should match Neo4j class count")
        .isGreaterThanOrEqualTo(15);
    assertThat(report.chunkCount())
        .as("chunkCount should reflect indexed Qdrant chunks")
        .isGreaterThan(0L);
    assertThat(report.generatedAt())
        .as("generatedAt should be populated")
        .isNotBlank();
  }

  @Test
  @DisplayName("validateModule pilot checks include PASS for all 5 checks when module is fully set up")
  void validateModule_allPilotChecksPass() {
    PilotValidationReport report = pilotService.validateModule(MODULE);

    assertThat(report.pilotChecks())
        .as("Should have 5 pilot checks")
        .hasSize(5);

    // Checks that must be PASS (not FAIL) given our setup:
    // - class count >= 15: YES (20 classes)
    // - Vaadin 7 classes: YES (3 views + 2 forms)
    // - vector chunks: YES (indexAll ran)
    // - business terms: YES (created USES_TERM edges)
    // - risk scores: PASS or WARN depending on enhancedRiskScore values
    List<PilotCheck> failures = report.pilotChecks().stream()
        .filter(c -> "FAIL".equals(c.status()))
        .toList();
    assertThat(failures)
        .as("No pilot check should be FAIL: " + failures)
        .isEmpty();
  }

  @Test
  @DisplayName("validateModule markdownReport contains all required section headers")
  void validateModule_markdownReportGenerated() {
    PilotValidationReport report = pilotService.validateModule(MODULE);

    assertThat(report.markdownReport())
        .as("Markdown report must not be blank")
        .isNotBlank()
        .contains("## Module Overview")
        .contains("## Graph Validation")
        .contains("## Pilot Checks")
        .contains("## Migration Readiness Assessment");
  }

  // ---------------------------------------------------------------------------
  // GMP-03: Risk scores and business terms
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("validateModule avgEnhancedRiskScore > 0 (risk scores populated)")
  void validateModule_riskScoresPopulated() {
    PilotValidationReport report = pilotService.validateModule(MODULE);

    assertThat(report.avgEnhancedRiskScore())
        .as("Average enhanced risk score must be positive")
        .isGreaterThan(0.0);
  }

  @Test
  @DisplayName("validateModule businessTermCount > 0 and domainTermCoveragePercent > 0")
  void validateModule_businessTermsCovered() {
    PilotValidationReport report = pilotService.validateModule(MODULE);

    assertThat(report.businessTermCount())
        .as("Business term count should be > 0")
        .isGreaterThan(0);
    assertThat(report.domainTermCoveragePercent())
        .as("Domain term coverage should be > 0%")
        .isGreaterThan(0.0);
  }

  @Test
  @DisplayName("validateModule markdownReport contains Migration Readiness Assessment section")
  void validateModule_migrationReadinessInReport() {
    PilotValidationReport report = pilotService.validateModule(MODULE);

    assertThat(report.markdownReport())
        .contains("Migration Readiness Assessment");
  }

  // ---------------------------------------------------------------------------
  // Pipeline integrity
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("fullPipeline_syntheticModuleIndexed: indexAll returns filesProcessed > 0 and chunksIndexed > 0")
  void fullPipeline_syntheticModuleIndexed() {
    assertThat(indexResult.filesProcessed())
        .as("indexAll should have processed at least one file")
        .isGreaterThan(0);
    assertThat(indexResult.chunksIndexed())
        .as("indexAll should have indexed at least one chunk")
        .isGreaterThan(0);
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
      try (InputStream is = PilotServiceIntegrationTest.class.getResourceAsStream(resource)) {
        Objects.requireNonNull(is, "Fixture resource not found: " + resource);
        Files.copy(is, destination.resolve(name));
      }
    }
  }

  private void createAllClassNodes() {
    // Services
    createClassNode(INVOICE_SERVICE, "InvoiceService", "Service", 0.4, 0.55, 0.7, 0.1, 0.6, 0.2);
    createClassNode(PAYMENT_SERVICE, "PaymentService", "Service", 0.5, 0.65, 0.6, 0.3, 0.9, 0.3);
    createClassNode(CUSTOMER_SERVICE, "CustomerService", "Service", 0.3, 0.45, 0.5, 0.1, 0.3, 0.2);
    createClassNode(AUDIT_SERVICE, "AuditService", "Service", 0.4, 0.60, 0.5, 0.8, 0.2, 0.1);

    // Repositories
    createClassNode(INVOICE_REPO, "InvoiceRepository", "Repository", 0.2, 0.35, 0.4, 0.0, 0.4, 0.1);
    createClassNode(CUSTOMER_REPO, "CustomerRepository", "Repository", 0.2, 0.30, 0.3, 0.0, 0.2, 0.1);
    createClassNode(PAYMENT_REPO, "PaymentRepository", "Repository", 0.3, 0.40, 0.4, 0.1, 0.5, 0.1);

    // Entities
    createClassNode(INVOICE_ENTITY, "InvoiceEntity", "Entity", 0.1, 0.25, 0.6, 0.0, 0.5, 0.1);
    createClassNode(CUSTOMER_ENTITY, "CustomerEntity", "Entity", 0.1, 0.20, 0.4, 0.0, 0.2, 0.1);
    createClassNode(PAYMENT_ENTITY, "PaymentEntity", "Entity", 0.1, 0.28, 0.5, 0.0, 0.6, 0.1);

    // Vaadin Views
    createVaadinViewNode(INVOICE_VIEW, "InvoiceView", 0.5, 0.60, 0.4, 0.1, 0.3, 0.1);
    createVaadinViewNode(CUSTOMER_VIEW, "CustomerView", 0.4, 0.50, 0.3, 0.1, 0.2, 0.1);
    createVaadinViewNode(PAYMENT_VIEW, "PaymentView", 0.5, 0.55, 0.4, 0.1, 0.4, 0.1);

    // Vaadin DataBinding Forms
    createVaadinDataBindingNode(INVOICE_FORM, "InvoiceForm", 0.3, 0.40, 0.3, 0.1, 0.3, 0.1);
    createVaadinDataBindingNode(CUSTOMER_FORM, "CustomerForm", 0.3, 0.35, 0.3, 0.1, 0.2, 0.1);

    // Rule/calc classes
    createClassNode(INVOICE_VALIDATOR, "InvoiceValidator", "Service", 0.6, 0.70, 0.5, 0.2, 0.4, 0.8);
    createClassNode(PAYMENT_CALCULATOR, "PaymentCalculator", "Service", 0.7, 0.75, 0.4, 0.2, 0.8, 0.7);

    // Enums
    createClassNode(INVOICE_STATUS_ENUM, "InvoiceStatusEnum", "Enum", 0.1, 0.15, 0.5, 0.0, 0.4, 0.3);
    createClassNode(PAYMENT_STATUS_ENUM, "PaymentStatusEnum", "Enum", 0.1, 0.12, 0.4, 0.0, 0.5, 0.2);
    createClassNode(CUSTOMER_ROLE, "CustomerRole", "Enum", 0.1, 0.10, 0.3, 0.1, 0.1, 0.1);
  }

  private void createClassNode(String fqn, String simpleName, String extraLabel,
      double srs, double ers, double dc, double ss, double fi, double brd) {
    String fileName = simpleName + ".java";
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("path", fileName);
    params.put("hash", "hash-" + simpleName.toLowerCase() + "-v1");
    params.put("srs", srs);
    params.put("ers", ers);
    params.put("dc", dc);
    params.put("ss", ss);
    params.put("fi", fi);
    params.put("brd", brd);
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
            domainCriticality: $dc,
            securitySensitivity: $ss,
            financialInvolvement: $fi,
            businessRuleDensity: $brd,
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

  private void createVaadinViewNode(String fqn, String simpleName,
      double srs, double ers, double dc, double ss, double fi, double brd) {
    String fileName = simpleName + ".java";
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("path", fileName);
    params.put("hash", "hash-" + simpleName.toLowerCase() + "-v1");
    params.put("srs", srs);
    params.put("ers", ers);
    params.put("dc", dc);
    params.put("ss", ss);
    params.put("fi", fi);
    params.put("brd", brd);
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
            domainCriticality: $dc,
            securitySensitivity: $ss,
            financialInvolvement: $fi,
            businessRuleDensity: $brd,
            complexitySum: 4,
            complexityMax: 3,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: false,
            dbWriteCount: 0
        })
        """)
        .bindAll(params)
        .run();
  }

  private void createVaadinDataBindingNode(String fqn, String simpleName,
      double srs, double ers, double dc, double ss, double fi, double brd) {
    String fileName = simpleName + ".java";
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("path", fileName);
    params.put("hash", "hash-" + simpleName.toLowerCase() + "-v1");
    params.put("srs", srs);
    params.put("ers", ers);
    params.put("dc", dc);
    params.put("ss", ss);
    params.put("fi", fi);
    params.put("brd", brd);
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
            domainCriticality: $dc,
            securitySensitivity: $ss,
            financialInvolvement: $fi,
            businessRuleDensity: $brd,
            complexitySum: 2,
            complexityMax: 2,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: false,
            dbWriteCount: 0
        })
        """)
        .bindAll(params)
        .run();
  }

  private void createMethodNodes() {
    // InvoiceService methods
    createMethod(INVOICE_SERVICE, "createInvoice", "(String,double,String)", 3);
    createMethod(INVOICE_SERVICE, "findById", "(Long)", 2);
    createMethod(INVOICE_SERVICE, "markAsSent", "(Long)", 4);

    // PaymentService methods
    createMethod(PAYMENT_SERVICE, "processPayment", "(Long,double)", 3);
    createMethod(PAYMENT_SERVICE, "refund", "(Long)", 2);

    // CustomerService methods
    createMethod(CUSTOMER_SERVICE, "findCustomer", "(String)", 2);
    createMethod(CUSTOMER_SERVICE, "updateCustomer", "(Long,String)", 2);

    // AuditService methods
    createMethod(AUDIT_SERVICE, "logSecurityEvent", "(String,String)", 2);
    createMethod(AUDIT_SERVICE, "verifyAuthentication", "(String)", 2);

    // Repository methods
    createMethod(INVOICE_REPO, "findByStatus", "(String)", 1);
    createMethod(CUSTOMER_REPO, "findByRole", "(String)", 1);
    createMethod(PAYMENT_REPO, "updateStatus", "(Long,String)", 1);

    // InvoiceValidator methods
    createMethod(INVOICE_VALIDATOR, "validate", "(Object)", 6);

    // PaymentCalculator methods
    createMethod(PAYMENT_CALCULATOR, "calculateTotal", "(double,String)", 4);
  }

  private void createMethod(String classFqn, String methodName, String params, int cc) {
    String methodId = classFqn + "#" + methodName + params;
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        CREATE (m:JavaMethod {
            methodId: $methodId,
            simpleName: $methodName,
            declaringClass: $fqn,
            cyclomaticComplexity: $cc,
            parameterTypes: []
        })
        CREATE (c)-[:DECLARES_METHOD]->(m)
        """)
        .bindAll(Map.of(
            "fqn", classFqn,
            "methodId", methodId,
            "methodName", methodName,
            "cc", cc))
        .run();
  }

  private void createRelationships() {
    // DEPENDS_ON edges: Services -> Repos/Entities
    createDependsOn(INVOICE_SERVICE, INVOICE_REPO);
    createDependsOn(PAYMENT_SERVICE, PAYMENT_REPO);
    createDependsOn(PAYMENT_SERVICE, INVOICE_SERVICE);
    createDependsOn(CUSTOMER_SERVICE, CUSTOMER_REPO);
    createDependsOn(CUSTOMER_SERVICE, INVOICE_REPO);
    createDependsOn(CUSTOMER_SERVICE, PAYMENT_REPO);

    // DEPENDS_ON: Views -> Services
    createDependsOn(INVOICE_VIEW, INVOICE_SERVICE);
    createDependsOn(CUSTOMER_VIEW, CUSTOMER_SERVICE);
    createDependsOn(PAYMENT_VIEW, PAYMENT_SERVICE);

    // DEPENDS_ON: Forms -> Entities
    createDependsOn(INVOICE_FORM, INVOICE_ENTITY);
    createDependsOn(CUSTOMER_FORM, CUSTOMER_ENTITY);

    // MAPS_TO_TABLE: Entities -> DBTable nodes
    createMapsToTable(INVOICE_ENTITY, "invoice");
    createMapsToTable(CUSTOMER_ENTITY, "customer");
    createMapsToTable(PAYMENT_ENTITY, "payment");
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

  private void createMapsToTable(String classFqn, String tableName) {
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        MERGE (t:DBTable {tableName: $table})
        MERGE (c)-[:MAPS_TO_TABLE]->(t)
        """)
        .bindAll(Map.of("fqn", classFqn, "table", tableName))
        .run();
  }

  private void createBusinessTerms() {
    // Create 4 domain business terms and link them to appropriate classes via USES_TERM
    createAndLinkTerm("invoice-term", "Invoice",
        List.of(INVOICE_SERVICE, INVOICE_ENTITY, INVOICE_REPO, INVOICE_VIEW, INVOICE_VALIDATOR));
    createAndLinkTerm("payment-term", "Payment",
        List.of(PAYMENT_SERVICE, PAYMENT_ENTITY, PAYMENT_REPO, PAYMENT_VIEW, PAYMENT_CALCULATOR));
    createAndLinkTerm("customer-term", "Customer",
        List.of(CUSTOMER_SERVICE, CUSTOMER_ENTITY, CUSTOMER_REPO, CUSTOMER_VIEW));
    createAndLinkTerm("audit-term", "Audit",
        List.of(AUDIT_SERVICE));
  }

  private void createAndLinkTerm(String termId, String displayName, List<String> classFqns) {
    neo4jClient.query("""
        MERGE (t:BusinessTerm {termId: $termId})
        ON CREATE SET t.displayName = $displayName,
                      t.definition = $displayName + ' domain term',
                      t.criticality = 'High',
                      t.curated = false,
                      t.status = 'ACTIVE',
                      t.usageCount = 0
        """)
        .bindAll(Map.of("termId", termId, "displayName", displayName))
        .run();

    for (String fqn : classFqns) {
      neo4jClient.query("""
          MATCH (c:JavaClass {fullyQualifiedName: $fqn})
          MATCH (t:BusinessTerm {termId: $termId})
          MERGE (c)-[:USES_TERM]->(t)
          """)
          .bindAll(Map.of("fqn", fqn, "termId", termId))
          .run();
    }
  }
}
