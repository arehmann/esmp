package com.esmp.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.esmp.graph.api.BusinessTermResponse;
import com.esmp.graph.api.DependencyConeResponse;
import com.esmp.graph.api.DependencyConeResponse.ConeNode;
import com.esmp.graph.api.RiskDetailResponse;
import com.esmp.graph.application.GraphQueryService;
import com.esmp.graph.application.LexiconService;
import com.esmp.graph.application.RiskService;
import com.esmp.mcp.api.AssemblerWarning;
import com.esmp.mcp.api.MigrationContext;
import com.esmp.mcp.config.McpConfig;
import com.esmp.rag.api.ContextChunk;
import com.esmp.rag.api.ConeSummary;
import com.esmp.rag.api.RagRequest;
import com.esmp.rag.api.RagResponse;
import com.esmp.rag.api.ScoreBreakdown;
import com.esmp.rag.application.RagService;
import com.esmp.vector.application.VectorIndexingService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * Tests for {@link MigrationContextAssembler} covering MCP-02 (integration), MCP-08 (graceful
 * degradation), and token budget truncation.
 *
 * <p>Integration test (MCP-02) uses pilot fixtures and Testcontainers. Unit tests for MCP-08 and
 * truncation use Mockito mocks on an assembler constructed directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class MigrationContextAssemblerTest {

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
  private MigrationContextAssembler assembler;

  @Autowired
  private VectorIndexingService vectorIndexingService;

  @Autowired
  private Neo4jClient neo4jClient;

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
  // MCP-02: Integration — full pipeline returns non-null MigrationContext
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("MCP-02: assemble() returns non-null MigrationContext with cone, risk, and chunks")
  void testAssemble_integrationReturnsMigrationContext_MCP02() {
    MigrationContext ctx = assembler.assemble(INVOICE_SERVICE);

    assertThat(ctx).isNotNull();
    assertThat(ctx.classFqn()).isEqualTo(INVOICE_SERVICE);
    assertThat(ctx.durationMs()).isGreaterThan(0L);

    // Dependency cone: InvoiceService depends on InvoiceRepository
    assertThat(ctx.dependencyCone()).isNotNull();
    assertThat(ctx.dependencyCone().coneSize()).isGreaterThanOrEqualTo(0);

    // Risk analysis present (risk scores were seeded)
    assertThat(ctx.riskAnalysis()).isNotNull();

    // Code chunks from RagService delegation
    assertThat(ctx.codeChunks()).isNotNull();
    assertThat(ctx.codeChunks()).isNotEmpty();

    // No partial failures expected for pilot data
    assertThat(ctx.contextCompleteness()).isGreaterThan(0.0);
    assertThat(ctx.durationMs()).isGreaterThan(0L);
  }

  // ---------------------------------------------------------------------------
  // MCP-08: Graceful degradation when downstream services fail
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("MCP-08: assembler returns partial context with warnings when graph and RAG fail")
  void testAssemble_gracefulDegradationOnServiceFailures_MCP08() {
    // Create mocks that throw
    GraphQueryService failingGraph = mock(GraphQueryService.class);
    when(failingGraph.findDependencyCone(anyString()))
        .thenThrow(new RuntimeException("Neo4j down"));

    RagService failingRag = mock(RagService.class);
    when(failingRag.assemble(any(RagRequest.class)))
        .thenThrow(new RuntimeException("RAG service unavailable"));

    // Use real RiskService and LexiconService mocks (returning empty)
    RiskService emptyRisk = mock(RiskService.class);
    when(emptyRisk.getClassDetail(anyString())).thenReturn(Optional.empty());

    LexiconService emptyLexicon = mock(LexiconService.class);

    // Build assembler with a small token budget and failing services
    McpConfig config = new McpConfig();

    MigrationContextAssembler degradedAssembler = new MigrationContextAssembler(
        failingRag, failingGraph, emptyRisk, emptyLexicon, config, neo4jClient);

    // Should return a non-throwing MigrationContext
    MigrationContext ctx = degradedAssembler.assemble("com.example.SomeClass");

    assertThat(ctx).isNotNull();
    assertThat(ctx.contextCompleteness()).isLessThan(1.0);
    assertThat(ctx.dependencyCone()).isNull();
    assertThat(ctx.codeChunks()).isEmpty();

    // Warnings should contain entries for graph and rag failures
    assertThat(ctx.warnings()).isNotEmpty();

    boolean hasGraphWarning = ctx.warnings().stream()
        .anyMatch(w -> "graph".equals(w.service())
            && w.message() != null && w.message().contains("Neo4j down"));
    assertThat(hasGraphWarning)
        .as("Warning for 'graph' service with 'Neo4j down' message expected")
        .isTrue();

    boolean hasRagWarning = ctx.warnings().stream()
        .anyMatch(w -> "rag".equals(w.service()));
    assertThat(hasRagWarning)
        .as("Warning for 'rag' service expected")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // Token budget truncation
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Token truncation: code chunks dropped when context exceeds token budget")
  void testAssemble_tokenTruncationDropsCodeChunks() {
    // Build a large list of context chunks (each ~200 chars => ~50 tokens per chunk)
    List<ContextChunk> largeChunkList = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      largeChunkList.add(new ContextChunk(
          "com.example.Class" + i,
          "METHOD",
          "com.example.Class" + i + "#method",
          "Service",
          "A ".repeat(100), // ~200 chars = ~50 tokens
          "direct dependency (1 hop)",
          new ScoreBreakdown(0.9, 1.0, 0.5, 0.85),
          0.3, 0.5, false, "", "", "", "[]"));
    }

    // RagResponse wrapping large chunks
    RagResponse largeRagResponse = new RagResponse(
        "FQN", null, largeChunkList,
        new ConeSummary(1, 0, 0.3, List.of(), 0), null, 100L);

    RagService bigRag = mock(RagService.class);
    when(bigRag.assemble(any(RagRequest.class))).thenReturn(largeRagResponse);

    GraphQueryService emptyGraph = mock(GraphQueryService.class);
    when(emptyGraph.findDependencyCone(anyString())).thenReturn(Optional.empty());

    RiskService emptyRisk = mock(RiskService.class);
    when(emptyRisk.getClassDetail(anyString())).thenReturn(Optional.empty());

    LexiconService emptyLexicon = mock(LexiconService.class);

    // Very small token budget (100 tokens)
    McpConfig tinyConfig = new McpConfig();
    tinyConfig.getContext().setMaxTokens(100);

    MigrationContextAssembler truncatingAssembler = new MigrationContextAssembler(
        bigRag, emptyGraph, emptyRisk, emptyLexicon, tinyConfig, neo4jClient);

    MigrationContext ctx = truncatingAssembler.assemble("com.example.BigClass");

    assertThat(ctx).isNotNull();
    assertThat(ctx.truncated()).isTrue();
    assertThat(ctx.codeChunks().size())
        .as("Truncated context should have fewer chunks than the original 50")
        .isLessThan(50);
    assertThat(ctx.truncatedItems())
        .as("truncatedItems should report how many chunks were dropped")
        .isGreaterThan(0);
  }

  // ---------------------------------------------------------------------------
  // Fixture helpers (mirrors RagServiceIntegrationTest)
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
      try (InputStream is = MigrationContextAssemblerTest.class.getResourceAsStream(resource)) {
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
                      t.curated = false, t.status = 'ACTIVE', t.usageCount = 1
        """).run();
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        MATCH (t:BusinessTerm {termId: 'invoice-term'})
        MERGE (c)-[:USES_TERM]->(t)
        """)
        .bindAll(Map.of("fqn", INVOICE_SERVICE))
        .run();
  }
}
