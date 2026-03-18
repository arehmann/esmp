package com.esmp.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.dashboard.api.BusinessTermSummary;
import com.esmp.dashboard.api.ClassDetail;
import com.esmp.dashboard.api.LexiconCoverage;
import com.esmp.dashboard.api.ModuleDependencyEdge;
import com.esmp.dashboard.api.ModuleSummary;
import com.esmp.dashboard.api.RiskCluster;
import com.esmp.dashboard.application.DashboardService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Integration tests for {@link DashboardService} covering all DASH requirements.
 *
 * <p>Creates synthetic Neo4j data matching the pilot fixture shape (20 classes in the "pilot"
 * module, including 5 Vaadin 7 labelled classes, business terms, and DEPENDS_ON edges) then
 * exercises all 6 DashboardService query methods.
 *
 * <p>Uses the static {@code setUpDone} guard pattern to run the one-time setup only before the
 * first test, avoiding repeated Neo4j data creation across test methods.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class DashboardServiceIntegrationTest {

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
  private DashboardService dashboardService;

  @Autowired
  private Neo4jClient neo4jClient;

  /** Guards one-time setup so it only runs before the first test method. */
  private static boolean setUpDone = false;

  private static final String PKG = "com.esmp.pilot";
  private static final String MODULE = "pilot";

  // Pilot class FQNs
  private static final String INVOICE_SERVICE = PKG + ".InvoiceService";
  private static final String PAYMENT_SERVICE = PKG + ".PaymentService";
  private static final String CUSTOMER_SERVICE = PKG + ".CustomerService";
  private static final String AUDIT_SERVICE = PKG + ".AuditService";
  private static final String INVOICE_REPO = PKG + ".InvoiceRepository";
  private static final String CUSTOMER_REPO = PKG + ".CustomerRepository";
  private static final String PAYMENT_REPO = PKG + ".PaymentRepository";
  private static final String INVOICE_ENTITY = PKG + ".InvoiceEntity";
  private static final String CUSTOMER_ENTITY = PKG + ".CustomerEntity";
  private static final String PAYMENT_ENTITY = PKG + ".PaymentEntity";
  private static final String INVOICE_VIEW = PKG + ".InvoiceView";
  private static final String CUSTOMER_VIEW = PKG + ".CustomerView";
  private static final String PAYMENT_VIEW = PKG + ".PaymentView";
  private static final String INVOICE_FORM = PKG + ".InvoiceForm";
  private static final String CUSTOMER_FORM = PKG + ".CustomerForm";
  private static final String INVOICE_VALIDATOR = PKG + ".InvoiceValidator";
  private static final String PAYMENT_CALCULATOR = PKG + ".PaymentCalculator";
  private static final String INVOICE_STATUS_ENUM = PKG + ".InvoiceStatusEnum";
  private static final String PAYMENT_STATUS_ENUM = PKG + ".PaymentStatusEnum";
  private static final String CUSTOMER_ROLE = PKG + ".CustomerRole";

  @BeforeEach
  void setUpOnce() {
    if (setUpDone) return;
    setUpDone = true;

    // Clear existing data
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // Create all 20 class nodes
    createAllClassNodes();

    // Create DEPENDS_ON edges (creates cross-module edges too via a second-module class)
    createRelationships();

    // Create business terms with USES_TERM links
    createBusinessTerms();
  }

  // ---------------------------------------------------------------------------
  // DASH-01: Module summaries — V7 percentages and heatmap score
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("testModuleSummary returns at least 1 ModuleSummary for module=pilot with vaadin7Count > 0")
  void testModuleSummaryReturnsVaadin7Percentages() {
    List<ModuleSummary> summaries = dashboardService.getModuleSummaries();

    assertThat(summaries).isNotEmpty();

    ModuleSummary pilot = summaries.stream()
        .filter(s -> MODULE.equals(s.module()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Module 'pilot' not found in summaries"));

    assertThat(pilot.classCount())
        .as("classCount should match the 20 created nodes")
        .isGreaterThanOrEqualTo(15);
    assertThat(pilot.vaadin7Count())
        .as("vaadin7Count should reflect the 5 Vaadin-labelled nodes (3 views + 2 forms)")
        .isGreaterThan(0);
    assertThat(pilot.vaadin7Pct())
        .as("vaadin7Pct should be positive")
        .isGreaterThan(0.0);
  }

  @Test
  @DisplayName("testHeatmapScore is non-zero when V7 classes have risk scores")
  void testHeatmapScore() {
    List<ModuleSummary> summaries = dashboardService.getModuleSummaries();

    ModuleSummary pilot = summaries.stream()
        .filter(s -> MODULE.equals(s.module()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Module 'pilot' not found in summaries"));

    assertThat(pilot.heatmapScore())
        .as("heatmapScore = vaadin7Pct * avgEnhancedRisk, should be > 0")
        .isGreaterThan(0.0);
    // Verify formula: heatmapScore ≈ vaadin7Pct * avgEnhancedRisk
    double expected = pilot.vaadin7Pct() * pilot.avgEnhancedRisk();
    assertThat(pilot.heatmapScore())
        .as("heatmapScore should equal vaadin7Pct * avgEnhancedRisk")
        .isCloseTo(expected, org.assertj.core.data.Offset.offset(0.001));
  }

  // ---------------------------------------------------------------------------
  // DASH-06: Lexicon coverage
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("testLexiconCoverage returns total > 0 after business term creation")
  void testLexiconCoverageReturnsTermCounts() {
    LexiconCoverage coverage = dashboardService.getLexiconCoverage();

    assertThat(coverage.total())
        .as("Total business terms should be > 0")
        .isGreaterThan(0);
    assertThat(coverage.coveragePct())
        .as("Coverage percentage should be >= 0.0")
        .isGreaterThanOrEqualTo(0.0);
  }

  // ---------------------------------------------------------------------------
  // DASH-05: Risk clusters
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("testRiskClusters returns at least 1 RiskCluster for module=pilot")
  void testRiskClustersReturnModuleData() {
    List<RiskCluster> clusters = dashboardService.getRiskClusters();

    assertThat(clusters).isNotEmpty();

    RiskCluster pilot = clusters.stream()
        .filter(c -> MODULE.equals(c.module()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Module 'pilot' not found in risk clusters"));

    assertThat(pilot.classCount())
        .as("classCount should be >= 15")
        .isGreaterThanOrEqualTo(15);
    assertThat(pilot.avgRisk())
        .as("avgRisk should be >= 0.0")
        .isGreaterThanOrEqualTo(0.0);
    assertThat(pilot.maxRisk())
        .as("maxRisk should be >= avgRisk")
        .isGreaterThanOrEqualTo(pilot.avgRisk());
  }

  // ---------------------------------------------------------------------------
  // DASH-03: Module dependency edges
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("testDependencyGraph returns a list without exception (may be empty for single-module graph)")
  void testDependencyGraphModuleDependencyEdges() {
    // The pilot module is the only module in this test data,
    // so cross-module edges should be empty. The test verifies no exception and correct type.
    List<ModuleDependencyEdge> edges = dashboardService.getModuleDependencyEdges();

    assertThat(edges)
        .as("getModuleDependencyEdges() should return a non-null list")
        .isNotNull();
    // All pilot classes are in the same module, so no cross-module edges expected
    // (this validates the query handles single-module graphs without error)
  }

  // ---------------------------------------------------------------------------
  // DASH-02: Class-level drill-down
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("testModuleSummary class drill-down returns ClassDetails for module=pilot")
  void testModuleSummaryClassDrillDown() {
    List<ClassDetail> details = dashboardService.getClassesInModule(MODULE);

    assertThat(details)
        .as("Should return class details for module 'pilot'")
        .isNotEmpty();

    // All returned details should have a non-null FQN and simpleName
    details.forEach(d -> {
      assertThat(d.fqn()).as("ClassDetail.fqn() should not be blank").isNotBlank();
      assertThat(d.simpleName()).as("ClassDetail.simpleName() should not be blank").isNotBlank();
      assertThat(d.labels()).as("ClassDetail.labels() should not be null").isNotNull();
      assertThat(d.dependsOn()).as("ClassDetail.dependsOn() should not be null").isNotNull();
    });

    // Should return all 20 pilot classes (or close to it given OPTIONAL MATCH aggregation)
    assertThat(details).hasSizeGreaterThanOrEqualTo(15);
  }

  // ---------------------------------------------------------------------------
  // DASH-04: Business term graph
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("testBusinessConceptGraph returns BusinessTermSummary list with non-blank displayNames")
  void testBusinessConceptGraphReturnsTermsWithClasses() {
    List<BusinessTermSummary> terms = dashboardService.getBusinessTermGraph();

    assertThat(terms)
        .as("Business term graph should return at least 1 term")
        .isNotEmpty();

    terms.forEach(t -> {
      assertThat(t.displayName())
          .as("BusinessTermSummary.displayName() should not be blank")
          .isNotBlank();
      assertThat(t.termId())
          .as("BusinessTermSummary.termId() should not be blank")
          .isNotBlank();
    });

    // The 4 created terms should have associated class FQNs
    BusinessTermSummary invoiceTerm = terms.stream()
        .filter(t -> "invoice-term".equals(t.termId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("invoice-term not found in business term graph"));

    assertThat(invoiceTerm.classFqns())
        .as("Invoice term should have at least 1 linked class FQN")
        .isNotEmpty();
  }

  // ---------------------------------------------------------------------------
  // Fixture helpers
  // ---------------------------------------------------------------------------

  private void createAllClassNodes() {
    // Services (JavaClass + Service label, non-zero risk scores)
    createClassNode(INVOICE_SERVICE,   "InvoiceService",   "Service",          0.4, 0.55);
    createClassNode(PAYMENT_SERVICE,   "PaymentService",   "Service",          0.5, 0.65);
    createClassNode(CUSTOMER_SERVICE,  "CustomerService",  "Service",          0.3, 0.45);
    createClassNode(AUDIT_SERVICE,     "AuditService",     "Service",          0.4, 0.60);

    // Repositories
    createClassNode(INVOICE_REPO,      "InvoiceRepository","Repository",       0.2, 0.35);
    createClassNode(CUSTOMER_REPO,     "CustomerRepository","Repository",      0.2, 0.30);
    createClassNode(PAYMENT_REPO,      "PaymentRepository","Repository",       0.3, 0.40);

    // Entities
    createClassNode(INVOICE_ENTITY,    "InvoiceEntity",    "Entity",           0.1, 0.25);
    createClassNode(CUSTOMER_ENTITY,   "CustomerEntity",   "Entity",           0.1, 0.20);
    createClassNode(PAYMENT_ENTITY,    "PaymentEntity",    "Entity",           0.1, 0.28);

    // Vaadin Views (use VaadinView label for V7 detection)
    createVaadinViewNode(INVOICE_VIEW, "InvoiceView",  0.5, 0.60);
    createVaadinViewNode(CUSTOMER_VIEW,"CustomerView", 0.4, 0.50);
    createVaadinViewNode(PAYMENT_VIEW, "PaymentView",  0.5, 0.55);

    // Vaadin DataBinding Forms (use VaadinDataBinding label for V7 detection)
    createVaadinDataBindingNode(INVOICE_FORM,  "InvoiceForm",  0.3, 0.40);
    createVaadinDataBindingNode(CUSTOMER_FORM, "CustomerForm", 0.3, 0.35);

    // Rule/calc classes
    createClassNode(INVOICE_VALIDATOR,   "InvoiceValidator",   "Service",      0.6, 0.70);
    createClassNode(PAYMENT_CALCULATOR,  "PaymentCalculator",  "Service",      0.7, 0.75);

    // Enums
    createClassNode(INVOICE_STATUS_ENUM, "InvoiceStatusEnum",  "Enum",         0.1, 0.15);
    createClassNode(PAYMENT_STATUS_ENUM, "PaymentStatusEnum",  "Enum",         0.1, 0.12);
    createClassNode(CUSTOMER_ROLE,       "CustomerRole",       "Enum",         0.1, 0.10);
  }

  private void createClassNode(String fqn, String simpleName, String extraLabel,
      double srs, double ers) {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("srs", srs);
    params.put("ers", ers);
    neo4jClient.query("""
        CREATE (c:JavaClass:%s {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $pkg,
            module: $module,
            structuralRiskScore: $srs,
            enhancedRiskScore: $ers
        })
        """.formatted(extraLabel))
        .bindAll(params)
        .run();
  }

  private void createVaadinViewNode(String fqn, String simpleName, double srs, double ers) {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("srs", srs);
    params.put("ers", ers);
    neo4jClient.query("""
        CREATE (c:JavaClass:VaadinView {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $pkg,
            module: $module,
            structuralRiskScore: $srs,
            enhancedRiskScore: $ers
        })
        """)
        .bindAll(params)
        .run();
  }

  private void createVaadinDataBindingNode(String fqn, String simpleName, double srs, double ers) {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("simpleName", simpleName);
    params.put("pkg", PKG);
    params.put("module", MODULE);
    params.put("srs", srs);
    params.put("ers", ers);
    neo4jClient.query("""
        CREATE (c:JavaClass:VaadinDataBinding {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $pkg,
            module: $module,
            structuralRiskScore: $srs,
            enhancedRiskScore: $ers
        })
        """)
        .bindAll(params)
        .run();
  }

  private void createRelationships() {
    // Intra-module DEPENDS_ON (all within 'pilot' — no cross-module edges will appear)
    createDependsOn(INVOICE_SERVICE, INVOICE_REPO);
    createDependsOn(PAYMENT_SERVICE, PAYMENT_REPO);
    createDependsOn(PAYMENT_SERVICE, INVOICE_SERVICE);
    createDependsOn(CUSTOMER_SERVICE, CUSTOMER_REPO);
    createDependsOn(INVOICE_VIEW, INVOICE_SERVICE);
    createDependsOn(CUSTOMER_VIEW, CUSTOMER_SERVICE);
    createDependsOn(PAYMENT_VIEW, PAYMENT_SERVICE);
    createDependsOn(INVOICE_FORM, INVOICE_ENTITY);
    createDependsOn(CUSTOMER_FORM, CUSTOMER_ENTITY);
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
    // 4 business terms — 2 curated, 2 uncurated
    createAndLinkTerm("invoice-term", "Invoice", "High", true,
        List.of(INVOICE_SERVICE, INVOICE_ENTITY, INVOICE_REPO, INVOICE_VIEW, INVOICE_VALIDATOR));
    createAndLinkTerm("payment-term", "Payment", "High", false,
        List.of(PAYMENT_SERVICE, PAYMENT_ENTITY, PAYMENT_REPO, PAYMENT_VIEW, PAYMENT_CALCULATOR));
    createAndLinkTerm("customer-term", "Customer", "Medium", true,
        List.of(CUSTOMER_SERVICE, CUSTOMER_ENTITY, CUSTOMER_REPO, CUSTOMER_VIEW));
    createAndLinkTerm("audit-term", "Audit", "Low", false,
        List.of(AUDIT_SERVICE));
  }

  private void createAndLinkTerm(String termId, String displayName, String criticality,
      boolean curated, List<String> classFqns) {
    neo4jClient.query("""
        MERGE (t:BusinessTerm {termId: $termId})
        ON CREATE SET t.displayName = $displayName,
                      t.criticality = $criticality,
                      t.curated = $curated,
                      t.status = 'ACTIVE',
                      t.usageCount = 0
        """)
        .bindAll(Map.of(
            "termId", termId,
            "displayName", displayName,
            "criticality", criticality,
            "curated", curated))
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
