package com.esmp.extraction.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.visitor.ExtractionAccumulator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
 * Integration test for USES_TERM and DEFINES_RULE edge creation in {@link LinkingService}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>USES_TERM edges are created from primary source JavaClass nodes to BusinessTerm nodes
 *   <li>USES_TERM edges are created from classes that DEPENDS_ON the primary source class
 *   <li>DEFINES_RULE edges are created for classes matching business-rule naming patterns
 *   <li>Both USES_TERM and DEFINES_RULE are idempotent (MERGE semantics)
 *   <li>Curated term definitions survive re-extraction (curated=true is preserved)
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class LexiconIntegrationTest {

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
  private LinkingService linkingService;

  @BeforeEach
  void clearDatabase() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
  }

  // ---------------------------------------------------------------------------
  // Helper: create test data
  // ---------------------------------------------------------------------------

  private void createClassNode(String fqn, String simpleName, String sourceFilePath) {
    neo4jClient.query("""
        CREATE (c:JavaClass {fullyQualifiedName: $fqn, simpleName: $simpleName,
                packageName: 'com.test', sourceFilePath: $sourceFilePath,
                annotations: [], implementedInterfaces: [], extraLabels: []})
        """)
        .bindAll(java.util.Map.of("fqn", fqn, "simpleName", simpleName,
            "sourceFilePath", sourceFilePath))
        .run();
  }

  private void createBusinessTermNode(String termId, String displayName) {
    neo4jClient.query("""
        CREATE (t:BusinessTerm {termId: $termId, displayName: $displayName,
                definition: $definition, criticality: 'Low', curated: false,
                status: 'auto', sourceType: 'CLASS_NAME', primarySourceFqn: $primaryFqn,
                usageCount: 1, synonyms: [], migrationSensitivity: 'None'})
        """)
        .bindAll(java.util.Map.of(
            "termId", termId,
            "displayName", displayName,
            "definition", "Auto-extracted definition for " + displayName,
            "primaryFqn", "com.test.SomeClass"))
        .run();
  }

  // ---------------------------------------------------------------------------
  // USES_TERM edge tests
  // ---------------------------------------------------------------------------

  @Test
  void linkBusinessTermUsages_createsUsesTermEdge_fromPrimarySourceClass() {
    // Arrange: create a JavaClass and a BusinessTerm that was extracted from it
    createClassNode("com.test.InvoiceService", "InvoiceService", "/main/com/test/InvoiceService.java");
    createBusinessTermNode("invoice", "Invoice");

    // Build accumulator with business term data
    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addBusinessTerm("invoice", "com.test.InvoiceService", "CLASS_NAME", null);

    // Act
    int count = linkingService.linkBusinessTermUsages(acc);

    // Assert: at least one USES_TERM edge created
    assertThat(count).as("Should create at least one USES_TERM edge").isGreaterThanOrEqualTo(1);

    Long edgeCount = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.InvoiceService'})
              -[r:USES_TERM]->(t:BusinessTerm {termId: 'invoice'})
        RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(edgeCount).as("Expected USES_TERM edge from InvoiceService to invoice term")
        .isEqualTo(1L);
  }

  @Test
  void linkBusinessTermUsages_createsUsesTermEdge_fromDependentClasses() {
    // Arrange: InvoiceService (primary source), PaymentController (depends on InvoiceService)
    createClassNode("com.test.InvoiceService", "InvoiceService", "/main/com/test/InvoiceService.java");
    createClassNode("com.test.PaymentController", "PaymentController", "/main/com/test/PaymentController.java");
    createBusinessTermNode("invoice", "Invoice");

    // Create DEPENDS_ON edge: PaymentController -> InvoiceService
    neo4jClient.query("""
        MATCH (dep:JavaClass {fullyQualifiedName: 'com.test.PaymentController'})
        MATCH (src:JavaClass {fullyQualifiedName: 'com.test.InvoiceService'})
        CREATE (dep)-[:DEPENDS_ON]->(src)
        """).run();

    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addBusinessTerm("invoice", "com.test.InvoiceService", "CLASS_NAME", null);

    // Act
    linkingService.linkBusinessTermUsages(acc);

    // Assert: dependent PaymentController also gets USES_TERM edge
    Long depEdgeCount = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.PaymentController'})
              -[r:USES_TERM]->(t:BusinessTerm {termId: 'invoice'})
        RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(depEdgeCount).as("PaymentController depending on InvoiceService should get USES_TERM edge")
        .isEqualTo(1L);
  }

  @Test
  void linkBusinessTermUsages_excludesTestClasses() {
    // Arrange: test class should not get a USES_TERM edge
    createClassNode("com.test.InvoiceServiceTest", "InvoiceServiceTest",
        "/src/test/java/com/test/InvoiceServiceTest.java");
    createBusinessTermNode("invoice", "Invoice");

    // Make the test class a dependent of the primary source
    createClassNode("com.test.InvoiceService", "InvoiceService", "/main/com/test/InvoiceService.java");
    neo4jClient.query("""
        MATCH (dep:JavaClass {fullyQualifiedName: 'com.test.InvoiceServiceTest'})
        MATCH (src:JavaClass {fullyQualifiedName: 'com.test.InvoiceService'})
        CREATE (dep)-[:DEPENDS_ON]->(src)
        """).run();

    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addBusinessTerm("invoice", "com.test.InvoiceService", "CLASS_NAME", null);

    // Act
    linkingService.linkBusinessTermUsages(acc);

    // Assert: InvoiceServiceTest should NOT have a USES_TERM edge (it's a test class)
    Long testClassEdgeCount = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.InvoiceServiceTest'})
              -[r:USES_TERM]->(:BusinessTerm)
        RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(testClassEdgeCount).as("Test classes should be excluded from USES_TERM edges")
        .isEqualTo(0L);
  }

  @Test
  void linkBusinessTermUsages_isIdempotent() {
    // Arrange
    createClassNode("com.test.InvoiceService", "InvoiceService", "/main/com/test/InvoiceService.java");
    createBusinessTermNode("invoice", "Invoice");

    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addBusinessTerm("invoice", "com.test.InvoiceService", "CLASS_NAME", null);

    // Act: run linking twice
    linkingService.linkBusinessTermUsages(acc);
    linkingService.linkBusinessTermUsages(acc);

    // Assert: still exactly one USES_TERM edge (MERGE semantics)
    Long edgeCount = neo4jClient.query("""
        MATCH (:JavaClass)-[r:USES_TERM]->(:BusinessTerm) RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(edgeCount).as("USES_TERM edges should not double on second run (idempotent MERGE)")
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // DEFINES_RULE edge tests
  // ---------------------------------------------------------------------------

  @Test
  void linkBusinessRules_createsDefinesRuleEdge_forValidatorNamedClass() {
    // Arrange: a Validator-named class and a matching BusinessTerm
    createClassNode("com.test.InvoiceValidator", "InvoiceValidator", "/main/com/test/InvoiceValidator.java");
    createBusinessTermNode("invoice", "Invoice");

    ExtractionAccumulator acc = new ExtractionAccumulator();
    // termId "invoice" should be derivable from simpleName "InvoiceValidator"

    // Act
    int count = linkingService.linkBusinessRules(acc);

    // Assert: DEFINES_RULE edge from InvoiceValidator to invoice term
    Long edgeCount = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.InvoiceValidator'})
              -[r:DEFINES_RULE]->(t:BusinessTerm {termId: 'invoice'})
        RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(edgeCount).as("Expected DEFINES_RULE edge from InvoiceValidator to invoice term")
        .isEqualTo(1L);
  }

  @Test
  void linkBusinessRules_isIdempotent() {
    // Arrange
    createClassNode("com.test.PaymentPolicy", "PaymentPolicy", "/main/com/test/PaymentPolicy.java");
    createBusinessTermNode("payment", "Payment");

    ExtractionAccumulator acc = new ExtractionAccumulator();

    // Act: run twice
    linkingService.linkBusinessRules(acc);
    linkingService.linkBusinessRules(acc);

    // Assert: exactly one DEFINES_RULE edge
    Long edgeCount = neo4jClient.query("""
        MATCH (:JavaClass)-[r:DEFINES_RULE]->(:BusinessTerm) RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(edgeCount).as("DEFINES_RULE edges should not double on second run (idempotent MERGE)")
        .isEqualTo(1L);
  }

  @Test
  void curationGuard_curatedTermDefinitionSurvivesRelinking() {
    // Arrange: a curated BusinessTerm node with a specific definition
    neo4jClient.query("""
        CREATE (t:BusinessTerm {termId: 'invoice', displayName: 'Invoice',
                definition: 'A curated human definition', criticality: 'High',
                curated: true, status: 'curated', sourceType: 'CLASS_NAME',
                primarySourceFqn: 'com.test.InvoiceService', usageCount: 1,
                synonyms: [], migrationSensitivity: 'Critical'})
        """).run();
    createClassNode("com.test.InvoiceService", "InvoiceService", "/main/com/test/InvoiceService.java");

    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addBusinessTerm("invoice", "com.test.InvoiceService", "CLASS_NAME", null);

    // Act: run linking (simulating re-extraction)
    linkingService.linkBusinessTermUsages(acc);

    // Assert: the curated definition is unchanged
    String definition = neo4jClient.query("""
        MATCH (t:BusinessTerm {termId: 'invoice'}) RETURN t.definition AS def
        """)
        .fetchAs(String.class)
        .mappedBy((ts, record) -> record.get("def").asString())
        .one()
        .orElse("");

    assertThat(definition).as("Curated term definition should survive re-linking")
        .isEqualTo("A curated human definition");

    // Also verify the curated flag is still true
    Boolean curated = neo4jClient.query("""
        MATCH (t:BusinessTerm {termId: 'invoice'}) RETURN t.curated AS cur
        """)
        .fetchAs(Boolean.class)
        .mappedBy((ts, record) -> record.get("cur").asBoolean())
        .one()
        .orElse(false);

    assertThat(curated).as("Curated flag should remain true after re-linking").isTrue();
  }

  @Test
  void linkAllRelationships_includesUsesTermAndDefinesRuleCounts() {
    // Arrange
    createClassNode("com.test.InvoiceService", "InvoiceService", "/main/com/test/InvoiceService.java");
    createClassNode("com.test.InvoiceValidator", "InvoiceValidator", "/main/com/test/InvoiceValidator.java");
    createBusinessTermNode("invoice", "Invoice");

    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addBusinessTerm("invoice", "com.test.InvoiceService", "CLASS_NAME", null);

    // Act
    LinkingService.LinkingResult result = linkingService.linkAllRelationships(acc);

    // Assert: result contains usesTermCount and definesRuleCount fields
    assertThat(result.usesTermCount()).as("usesTermCount should be > 0").isGreaterThanOrEqualTo(1);
    assertThat(result.definesRuleCount()).as("definesRuleCount should be >= 0").isGreaterThanOrEqualTo(0);
  }
}
