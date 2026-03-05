package com.esmp.graph.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for domain-aware risk scoring: DRISK-01 through DRISK-05.
 *
 * <p>Verifies:
 * <ul>
 *   <li>DRISK-01: domainCriticality from USES_TERM edges to BusinessTerm nodes
 *   <li>DRISK-02: securitySensitivity from keyword/annotation/package heuristics
 *   <li>DRISK-03: financialInvolvement from name/package/USES_TERM heuristics with boost
 *   <li>DRISK-04: businessRuleDensity as log-normalized DEFINES_RULE count
 *   <li>DRISK-05: enhancedRiskScore non-null for all classes, higher for domain-critical class
 *   <li>API: GET /api/risk/heatmap sortBy=enhanced, domain fields in response
 *   <li>API: GET /api/risk/class/{fqn} includes domain score breakdown
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class DomainRiskServiceIntegrationTest {

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
  private Neo4jClient neo4jClient;

  @Autowired
  private RiskService riskService;

  @Autowired
  private MockMvc mockMvc;

  @BeforeEach
  void clearDatabase() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
  }

  // ---------------------------------------------------------------------------
  // Helper: create JavaClass nodes
  // ---------------------------------------------------------------------------

  private void createClassNode(String fqn, String simpleName, String packageName,
      int complexitySum, boolean hasDbWrites) {
    neo4jClient.query("""
        CREATE (c:JavaClass {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $packageName,
            complexitySum: $complexitySum,
            complexityMax: $complexitySum,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: $hasDbWrites,
            dbWriteCount: CASE WHEN $hasDbWrites THEN 1 ELSE 0 END,
            structuralRiskScore: 0.0
        })
        """)
        .bindAll(Map.of(
            "fqn", fqn,
            "simpleName", simpleName,
            "packageName", packageName,
            "complexitySum", complexitySum,
            "hasDbWrites", hasDbWrites))
        .run();
  }

  private void createClassNodeWithLabel(String fqn, String simpleName, String packageName,
      int complexitySum, boolean hasDbWrites, String extraLabel) {
    createClassNode(fqn, simpleName, packageName, complexitySum, hasDbWrites);
    neo4jClient.query("MATCH (c:JavaClass {fullyQualifiedName: $fqn}) SET c:" + extraLabel)
        .bindAll(Map.of("fqn", fqn))
        .run();
  }

  private void createClassNodeWithAnnotations(String fqn, String simpleName, String packageName,
      int complexitySum, boolean hasDbWrites, List<String> annotations) {
    neo4jClient.query("""
        CREATE (c:JavaClass {
            fullyQualifiedName: $fqn,
            simpleName: $simpleName,
            packageName: $packageName,
            complexitySum: $complexitySum,
            complexityMax: $complexitySum,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: $hasDbWrites,
            dbWriteCount: CASE WHEN $hasDbWrites THEN 1 ELSE 0 END,
            structuralRiskScore: 0.0,
            annotations: $annotations
        })
        """)
        .bindAll(Map.of(
            "fqn", fqn,
            "simpleName", simpleName,
            "packageName", packageName,
            "complexitySum", complexitySum,
            "hasDbWrites", hasDbWrites,
            "annotations", annotations))
        .run();
  }

  private void createDependsOnEdge(String fromFqn, String toFqn) {
    neo4jClient.query("""
        MATCH (from:JavaClass {fullyQualifiedName: $from})
        MATCH (to:JavaClass {fullyQualifiedName: $to})
        CREATE (from)-[:DEPENDS_ON]->(to)
        """)
        .bindAll(Map.of("from", fromFqn, "to", toFqn))
        .run();
  }

  private void createMethodNode(String methodId, String simpleName, String declaringClass,
      int cyclomaticComplexity) {
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $declaringClass})
        CREATE (m:JavaMethod {
            methodId: $methodId,
            simpleName: $simpleName,
            declaringClass: $declaringClass,
            cyclomaticComplexity: $cc,
            parameterTypes: []
        })
        CREATE (c)-[:DECLARES_METHOD]->(m)
        """)
        .bindAll(Map.of(
            "methodId", methodId,
            "simpleName", simpleName,
            "declaringClass", declaringClass,
            "cc", cyclomaticComplexity))
        .run();
  }

  // ---------------------------------------------------------------------------
  // Helper: create BusinessTerm nodes and edges
  // ---------------------------------------------------------------------------

  private void createBusinessTerm(String termId, String displayName, String criticality) {
    neo4jClient.query("""
        CREATE (t:BusinessTerm {
            termId: $termId,
            displayName: $displayName,
            criticality: $criticality,
            curated: false,
            status: 'active'
        })
        """)
        .bindAll(Map.of(
            "termId", termId,
            "displayName", displayName,
            "criticality", criticality))
        .run();
  }

  private void createUsesTermEdge(String classFqn, String termId) {
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $classFqn})
        MATCH (t:BusinessTerm {termId: $termId})
        CREATE (c)-[:USES_TERM]->(t)
        """)
        .bindAll(Map.of("classFqn", classFqn, "termId", termId))
        .run();
  }

  private void createDefinesRuleEdge(String classFqn, String termId) {
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $classFqn})
        MATCH (t:BusinessTerm {termId: $termId})
        CREATE (c)-[:DEFINES_RULE]->(t)
        """)
        .bindAll(Map.of("classFqn", classFqn, "termId", termId))
        .run();
  }

  // ---------------------------------------------------------------------------
  // DRISK-01: Domain Criticality from USES_TERM
  // ---------------------------------------------------------------------------

  @Test
  void domainCriticality_highForClassWithHighBusinessTerm() {
    // Arrange: class linked to a High criticality BusinessTerm
    createClassNode("com.test.OrderService", "OrderService", "com.test", 5, false);
    createBusinessTerm("order", "Order", "High");
    createUsesTermEdge("com.test.OrderService", "order");

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: domainCriticality = 1.0
    Double criticality = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.OrderService'})
        RETURN c.domainCriticality AS dc
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("dc").asDouble())
        .one()
        .orElse(-1.0);

    assertThat(criticality)
        .as("Class with High criticality USES_TERM edge should have domainCriticality=1.0")
        .isEqualTo(1.0);
  }

  @Test
  void domainCriticality_zeroForClassWithNoTerms() {
    // Arrange: isolated class with no USES_TERM edges
    createClassNode("com.test.PlainUtil", "PlainUtil", "com.test", 2, false);

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: domainCriticality = 0.0
    Double criticality = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.PlainUtil'})
        RETURN c.domainCriticality AS dc
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("dc").asDouble())
        .one()
        .orElse(-1.0);

    assertThat(criticality)
        .as("Class with no USES_TERM edges should have domainCriticality=0.0")
        .isEqualTo(0.0);
  }

  @Test
  void domainCriticality_zeroForClassWithOnlyLowTerms() {
    // Arrange: class linked to only Low criticality terms
    createClassNode("com.test.LowRiskService", "LowRiskService", "com.test", 3, false);
    createBusinessTerm("term-low", "LowTerm", "Low");
    createUsesTermEdge("com.test.LowRiskService", "term-low");

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: domainCriticality = 0.0 (Low criticality does not contribute)
    Double criticality = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.LowRiskService'})
        RETURN c.domainCriticality AS dc
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("dc").asDouble())
        .one()
        .orElse(-1.0);

    assertThat(criticality)
        .as("Class with only Low criticality USES_TERM edges should have domainCriticality=0.0")
        .isEqualTo(0.0);
  }

  // ---------------------------------------------------------------------------
  // DRISK-02: Security Sensitivity heuristics
  // ---------------------------------------------------------------------------

  @Test
  void securitySensitivity_nonZeroForAuthNamedClass() {
    // Arrange: class with 'auth' in its simple name
    createClassNode("com.test.AuthService", "AuthService", "com.test", 3, false);

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: securitySensitivity > 0
    Double sensitivity = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.AuthService'})
        RETURN c.securitySensitivity AS ss
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("ss").asDouble())
        .one()
        .orElse(0.0);

    assertThat(sensitivity)
        .as("Class named AuthService should have securitySensitivity > 0")
        .isGreaterThan(0.0);
  }

  @Test
  void securitySensitivity_nonZeroForSecuredAnnotation() {
    // Arrange: class with @Secured annotation
    createClassNodeWithAnnotations(
        "com.test.AdminController", "AdminController", "com.test", 4, false,
        List.of("org.springframework.security.access.annotation.Secured"));

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: securitySensitivity > 0
    Double sensitivity = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.AdminController'})
        RETURN c.securitySensitivity AS ss
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("ss").asDouble())
        .one()
        .orElse(0.0);

    assertThat(sensitivity)
        .as("Class with @Secured annotation should have securitySensitivity > 0")
        .isGreaterThan(0.0);
  }

  @Test
  void securitySensitivity_zeroForPlainClass() {
    // Arrange: utility class with no security signals
    createClassNode("com.test.StringHelper", "StringHelper", "com.test.util", 2, false);

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: securitySensitivity = 0.0
    Double sensitivity = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.StringHelper'})
        RETURN c.securitySensitivity AS ss
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("ss").asDouble())
        .one()
        .orElse(-1.0);

    assertThat(sensitivity)
        .as("Utility class with no security signals should have securitySensitivity=0.0")
        .isEqualTo(0.0);
  }

  // ---------------------------------------------------------------------------
  // DRISK-03: Financial Involvement heuristics
  // ---------------------------------------------------------------------------

  @Test
  void financialInvolvement_nonZeroForPaymentNamedClass() {
    // Arrange: class with 'payment' in its simple name
    createClassNode("com.test.PaymentService", "PaymentService", "com.test", 5, false);

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: financialInvolvement > 0
    Double involvement = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.PaymentService'})
        RETURN c.financialInvolvement AS fi
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("fi").asDouble())
        .one()
        .orElse(0.0);

    assertThat(involvement)
        .as("Class named PaymentService should have financialInvolvement > 0")
        .isGreaterThan(0.0);
  }

  @Test
  void financialInvolvement_boostedByFinancialTerm() {
    // Arrange: two classes — one with USES_TERM to a financial term, one without
    createClassNode("com.test.InvoiceService", "InvoiceService", "com.test", 3, false);
    createClassNode("com.test.InvoiceHelper", "InvoiceHelper", "com.test", 3, false);
    createBusinessTerm("invoice", "Invoice", "High");
    createUsesTermEdge("com.test.InvoiceService", "invoice");

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: InvoiceService has higher financialInvolvement than InvoiceHelper
    Double serviceInvolvement = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.InvoiceService'})
        RETURN c.financialInvolvement AS fi
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("fi").asDouble())
        .one()
        .orElse(0.0);

    Double helperInvolvement = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.InvoiceHelper'})
        RETURN c.financialInvolvement AS fi
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("fi").asDouble())
        .one()
        .orElse(0.0);

    assertThat(serviceInvolvement)
        .as("Class with USES_TERM to financial term should have higher financialInvolvement than class without")
        .isGreaterThan(helperInvolvement);
  }

  // ---------------------------------------------------------------------------
  // DRISK-04: Business Rule Density from DEFINES_RULE
  // ---------------------------------------------------------------------------

  @Test
  void businessRuleDensity_logNormalizedFromDefinesRule() {
    // Arrange: class with 3 DEFINES_RULE edges
    createClassNode("com.test.OrderValidator", "OrderValidator", "com.test", 8, false);
    createBusinessTerm("rule1", "Rule1", "Medium");
    createBusinessTerm("rule2", "Rule2", "Medium");
    createBusinessTerm("rule3", "Rule3", "Medium");
    createDefinesRuleEdge("com.test.OrderValidator", "rule1");
    createDefinesRuleEdge("com.test.OrderValidator", "rule2");
    createDefinesRuleEdge("com.test.OrderValidator", "rule3");

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: businessRuleDensity = log(1 + 3) = log(4) ≈ 1.386
    Double density = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.OrderValidator'})
        RETURN c.businessRuleDensity AS brd
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("brd").asDouble())
        .one()
        .orElse(0.0);

    double expectedDensity = Math.log(1.0 + 3);
    assertThat(density)
        .as("Class with 3 DEFINES_RULE edges should have businessRuleDensity = log(4)")
        .isCloseTo(expectedDensity, org.assertj.core.api.Assertions.within(0.001));
  }

  @Test
  void businessRuleDensity_zeroForNoRules() {
    // Arrange: class with no DEFINES_RULE edges
    createClassNode("com.test.SimpleService", "SimpleService", "com.test", 3, false);

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: businessRuleDensity = log(1 + 0) = 0.0
    Double density = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.SimpleService'})
        RETURN c.businessRuleDensity AS brd
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("brd").asDouble())
        .one()
        .orElse(-1.0);

    assertThat(density)
        .as("Class with no DEFINES_RULE edges should have businessRuleDensity=0.0")
        .isEqualTo(0.0);
  }

  // ---------------------------------------------------------------------------
  // DRISK-05: Enhanced composite risk score
  // ---------------------------------------------------------------------------

  @Test
  void enhancedScore_nonNullForAllClasses() {
    // Arrange: several classes with varying characteristics
    createClassNode("com.test.ClassA", "ClassA", "com.test", 5, false);
    createClassNode("com.test.ClassB", "ClassB", "com.test", 10, true);
    createClassNode("com.test.ClassC", "ClassC", "com.test", 1, false);

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: no JavaClass should have NULL enhancedRiskScore
    Long nullCount = neo4jClient.query("""
        MATCH (c:JavaClass)
        WHERE c.enhancedRiskScore IS NULL
        RETURN count(c) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(1L);

    assertThat(nullCount)
        .as("After computation, no JavaClass should have NULL enhancedRiskScore")
        .isEqualTo(0L);
  }

  @Test
  void enhancedScore_higherForDomainCriticalClass() {
    // Arrange: two classes with identical structural metrics but one has domain criticality
    createClassNode("com.test.CriticalService", "CriticalService", "com.test", 5, false);
    createClassNode("com.test.PlainService", "PlainService", "com.test", 5, false);
    createBusinessTerm("critical-term", "CriticalTerm", "High");
    createUsesTermEdge("com.test.CriticalService", "critical-term");

    // Act
    riskService.computeAndPersistRiskScores();

    // Assert: CriticalService has higher enhancedRiskScore than PlainService
    Double criticalScore = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.CriticalService'})
        RETURN c.enhancedRiskScore AS score
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("score").asDouble())
        .one()
        .orElse(0.0);

    Double plainScore = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.PlainService'})
        RETURN c.enhancedRiskScore AS score
        """)
        .fetchAs(Double.class)
        .mappedBy((ts, record) -> record.get("score").asDouble())
        .one()
        .orElse(0.0);

    assertThat(criticalScore)
        .as("Domain-critical class should have higher enhancedRiskScore than identical plain class")
        .isGreaterThan(plainScore);
  }

  // ---------------------------------------------------------------------------
  // API: heatmap sortBy=enhanced
  // ---------------------------------------------------------------------------

  @Test
  void heatmap_sortByEnhanced() throws Exception {
    // Arrange: two classes — one with domain criticality (higher enhanced score), one without
    createClassNode("com.test.CriticalClass", "CriticalClass", "com.test", 5, false);
    createClassNode("com.test.PlainClass", "PlainClass", "com.test", 5, false);
    createBusinessTerm("high-term", "HighTerm", "High");
    createUsesTermEdge("com.test.CriticalClass", "high-term");

    riskService.computeAndPersistRiskScores();

    // Act + Assert: sortBy=enhanced returns CriticalClass first
    mockMvc.perform(get("/api/risk/heatmap").param("sortBy", "enhanced"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].fqn").value("com.test.CriticalClass"));
  }

  @Test
  void heatmap_includesDomainFields() throws Exception {
    // Arrange: a class with domain signals
    createClassNode("com.test.PaymentProcessor", "PaymentProcessor", "com.test.payment", 5, false);
    createBusinessTerm("payment-term", "Payment", "High");
    createUsesTermEdge("com.test.PaymentProcessor", "payment-term");

    riskService.computeAndPersistRiskScores();

    // Act + Assert: response includes all 5 domain fields
    mockMvc.perform(get("/api/risk/heatmap"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].domainCriticality").isNumber())
        .andExpect(jsonPath("$[0].securitySensitivity").isNumber())
        .andExpect(jsonPath("$[0].financialInvolvement").isNumber())
        .andExpect(jsonPath("$[0].businessRuleDensity").isNumber())
        .andExpect(jsonPath("$[0].enhancedRiskScore").isNumber());
  }

  // ---------------------------------------------------------------------------
  // API: classDetail includes domain breakdown
  // ---------------------------------------------------------------------------

  @Test
  void classDetail_includesDomainBreakdown() throws Exception {
    // Arrange: class with domain signals and methods
    createClassNode("com.test.AccountService", "AccountService", "com.test.finance", 8, true);
    createMethodNode("com.test.AccountService#process()", "process", "com.test.AccountService", 5);
    createBusinessTerm("account-term", "Account", "High");
    createUsesTermEdge("com.test.AccountService", "account-term");

    riskService.computeAndPersistRiskScores();

    // Act + Assert: detail response includes domain score fields
    mockMvc.perform(get("/api/risk/class/com.test.AccountService"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fqn").value("com.test.AccountService"))
        .andExpect(jsonPath("$.domainCriticality").isNumber())
        .andExpect(jsonPath("$.securitySensitivity").isNumber())
        .andExpect(jsonPath("$.financialInvolvement").isNumber())
        .andExpect(jsonPath("$.businessRuleDensity").isNumber())
        .andExpect(jsonPath("$.enhancedRiskScore").isNumber())
        .andExpect(jsonPath("$.methods").isArray());
  }
}
