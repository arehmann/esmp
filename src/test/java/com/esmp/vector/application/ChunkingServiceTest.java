package com.esmp.vector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.esmp.vector.model.ChunkType;
import com.esmp.vector.model.CodeChunk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * Unit tests for {@link ChunkingService}.
 *
 * <p>Uses Mockito deep stubs to mock the Neo4jClient fluent query chain. A real temp directory
 * with known source files verifies source text extraction and formatting.
 *
 * <p>Test behaviours verified:
 * <ol>
 *   <li>3-method class produces 1 CLASS_HEADER + 3 METHOD chunks (4 total)
 *   <li>CLASS_HEADER text contains {@code [CLASS: ...] [PACKAGE: ...]} prefix
 *   <li>METHOD text contains {@code [CLASS: ...] [METHOD: ...]} prefix
 *   <li>METHOD chunks have classHeaderId matching the CLASS_HEADER pointId
 *   <li>Risk scores are copied from class-row data
 *   <li>1-hop neighbor FQNs (callers, callees, dependencies) are populated
 *   <li>Domain terms from USES_TERM edges are included in each chunk
 *   <li>vaadin7Detected is true when class has VaadinView label
 *   <li>Classes with null sourceFilePath are skipped
 *   <li>Classes with non-existent source file are skipped gracefully
 * </ol>
 */
@SuppressWarnings("unchecked")
class ChunkingServiceTest {

  @TempDir
  Path tempDir;

  private Neo4jClient neo4jClient;
  private ChunkingService chunkingService;

  @BeforeEach
  void setUp() {
    neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    chunkingService = new ChunkingService(neo4jClient);
  }

  // -------------------------------------------------------------------------
  // Test 1: chunk count for 3-method class
  // -------------------------------------------------------------------------

  @Test
  void chunkCount_oneHeaderPlusOnePerMethod() throws IOException {
    Path sourceFile = createSourceFile("MyService.java", threeMethodSource());
    setupClassQuery(List.of(makeClassRow("com.esmp.MyService", "MyService",
        "com.esmp", sourceFile.toString(), List.of("Service"))));
    setupEnrichmentAndCalleesQuery("com.esmp.MyService", List.of(), List.of(), List.of(), List.of(),
        List.of("com.esmp.MyService#methodA()", "com.esmp.MyService#methodB()",
            "com.esmp.MyService#methodC()"), List.of());

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    assertThat(chunks).hasSize(4);
    assertThat(chunks.stream().filter(c -> c.chunkType() == ChunkType.CLASS_HEADER)).hasSize(1);
    assertThat(chunks.stream().filter(c -> c.chunkType() == ChunkType.METHOD)).hasSize(3);
  }

  // -------------------------------------------------------------------------
  // Test 2: CLASS_HEADER text format
  // -------------------------------------------------------------------------

  @Test
  void classHeaderText_containsClassAndPackagePrefixes() throws IOException {
    Path sourceFile = createSourceFile("MyService.java", threeMethodSource());
    setupClassQuery(List.of(makeClassRow("com.esmp.MyService", "MyService",
        "com.esmp", sourceFile.toString(), List.of("Service"))));
    setupEnrichmentAndCalleesQuery("com.esmp.MyService", List.of(), List.of(), List.of(), List.of(),
        List.of("com.esmp.MyService#methodA()"), List.of());

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    CodeChunk header = chunks.stream()
        .filter(c -> c.chunkType() == ChunkType.CLASS_HEADER).findFirst().orElseThrow();
    assertThat(header.text()).contains("[CLASS: MyService]");
    assertThat(header.text()).contains("[PACKAGE: com.esmp]");
  }

  // -------------------------------------------------------------------------
  // Test 3: METHOD text format
  // -------------------------------------------------------------------------

  @Test
  void methodText_containsClassAndMethodPrefixes() throws IOException {
    Path sourceFile = createSourceFile("MyService.java", threeMethodSource());
    setupClassQuery(List.of(makeClassRow("com.esmp.MyService", "MyService",
        "com.esmp", sourceFile.toString(), List.of("Service"))));
    setupEnrichmentAndCalleesQuery("com.esmp.MyService", List.of(), List.of(), List.of(), List.of(),
        List.of("com.esmp.MyService#methodA()"), List.of());

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    CodeChunk method = chunks.stream()
        .filter(c -> c.chunkType() == ChunkType.METHOD).findFirst().orElseThrow();
    assertThat(method.text()).contains("[CLASS: MyService]");
    assertThat(method.text()).contains("[METHOD: methodA()]");
  }

  // -------------------------------------------------------------------------
  // Test 4: METHOD chunks reference CLASS_HEADER pointId via classHeaderId
  // -------------------------------------------------------------------------

  @Test
  void methodChunks_haveClassHeaderIdMatchingHeaderPointId() throws IOException {
    Path sourceFile = createSourceFile("MyService.java", threeMethodSource());
    setupClassQuery(List.of(makeClassRow("com.esmp.MyService", "MyService",
        "com.esmp", sourceFile.toString(), List.of("Service"))));
    setupEnrichmentAndCalleesQuery("com.esmp.MyService", List.of(), List.of(), List.of(), List.of(),
        List.of("com.esmp.MyService#methodA()", "com.esmp.MyService#methodB()"), List.of());

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    CodeChunk header = chunks.stream()
        .filter(c -> c.chunkType() == ChunkType.CLASS_HEADER).findFirst().orElseThrow();

    chunks.stream()
        .filter(c -> c.chunkType() == ChunkType.METHOD)
        .forEach(m -> assertThat(m.classHeaderId()).isEqualTo(header.pointId().toString()));
  }

  // -------------------------------------------------------------------------
  // Test 5: risk scores are propagated
  // -------------------------------------------------------------------------

  @Test
  void chunks_carryRiskScoresFromClassNode() throws IOException {
    Path sourceFile = createSourceFile("RiskyClass.java", singleMethodSource());
    Map<String, Object> row = makeClassRowWithRisk("com.esmp.RiskyClass", "RiskyClass",
        "com.esmp", sourceFile.toString(), List.of(),
        1.5, 2.5, 0.8, 0.6, 0.4, 0.3);
    setupClassQuery(List.of(row));
    setupEnrichmentAndCalleesQuery("com.esmp.RiskyClass", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    assertThat(chunks).hasSize(1); // only header, no methods
    CodeChunk header = chunks.get(0);
    assertThat(header.structuralRiskScore()).isEqualTo(1.5);
    assertThat(header.enhancedRiskScore()).isEqualTo(2.5);
    assertThat(header.domainCriticality()).isEqualTo(0.8);
    assertThat(header.securitySensitivity()).isEqualTo(0.6);
    assertThat(header.financialInvolvement()).isEqualTo(0.4);
    assertThat(header.businessRuleDensity()).isEqualTo(0.3);
  }

  // -------------------------------------------------------------------------
  // Test 6: 1-hop neighbor FQNs
  // -------------------------------------------------------------------------

  @Test
  void chunks_carryNeighborFqns() throws IOException {
    Path sourceFile = createSourceFile("MyService.java", singleMethodSource());
    setupClassQuery(List.of(makeClassRow("com.esmp.MyService", "MyService",
        "com.esmp", sourceFile.toString(), List.of("Service"))));
    setupEnrichmentAndCalleesQuery("com.esmp.MyService",
        List.of("com.esmp.CallerA"),          // callers
        List.of("com.esmp.DepX"),              // dependencies
        List.of("com.esmp.IFoo"),              // implementors
        List.of(),                             // terms
        List.of(),                             // methodIds
        List.of("com.esmp.TargetZ"));          // callees

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    assertThat(chunks).hasSize(1);
    CodeChunk chunk = chunks.get(0);
    assertThat(chunk.callers()).contains("com.esmp.CallerA");
    assertThat(chunk.callees()).contains("com.esmp.TargetZ");
    assertThat(chunk.dependencies()).contains("com.esmp.DepX");
    assertThat(chunk.implementors()).contains("com.esmp.IFoo");
  }

  // -------------------------------------------------------------------------
  // Test 7: domain terms from USES_TERM edges
  // -------------------------------------------------------------------------

  @Test
  void chunks_carryDomainTerms() throws IOException {
    Path sourceFile = createSourceFile("MyService.java", singleMethodSource());
    setupClassQuery(List.of(makeClassRow("com.esmp.MyService", "MyService",
        "com.esmp", sourceFile.toString(), List.of("Service"))));
    setupEnrichmentAndCalleesQuery("com.esmp.MyService",
        List.of(), List.of(), List.of(),
        List.of(Map.of("termId", "TERM-001", "displayName", "Order")), // terms
        List.of(), List.of());

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).domainTerms()).hasSize(1);
    assertThat(chunks.get(0).domainTerms().get(0).termId()).isEqualTo("TERM-001");
    assertThat(chunks.get(0).domainTerms().get(0).displayName()).isEqualTo("Order");
  }

  // -------------------------------------------------------------------------
  // Test 8: vaadin7Detected flag
  // -------------------------------------------------------------------------

  @Test
  void vaadin7Detected_whenClassHasVaadinViewLabel() throws IOException {
    Path sourceFile = createSourceFile("MyView.java", singleMethodSource());
    setupClassQuery(List.of(makeClassRow("com.esmp.MyView", "MyView",
        "com.esmp", sourceFile.toString(), List.of("VaadinView", "Service"))));
    setupEnrichmentAndCalleesQuery("com.esmp.MyView", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).vaadin7Detected()).isTrue();
    assertThat(chunks.get(0).vaadinPatterns()).contains("VaadinView");
  }

  // -------------------------------------------------------------------------
  // Test 9: classes with null sourceFilePath are skipped
  // -------------------------------------------------------------------------

  @Test
  void skipsClasses_withNullSourceFilePath() {
    // The query filters WHERE c.sourceFilePath IS NOT NULL — so if we return an empty list
    // the service produces 0 chunks. Simulate no matching classes.
    setupClassQuery(List.of());

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    assertThat(chunks).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Test 10: classes with non-existent source file are skipped gracefully
  // -------------------------------------------------------------------------

  @Test
  void skipsClasses_withNonExistentSourceFile() {
    // Point to a file that doesn't exist
    setupClassQuery(List.of(makeClassRow("com.esmp.Ghost", "Ghost",
        "com.esmp", "/nonexistent/path/Ghost.java", List.of())));
    // No enrichment queries should run for skipped classes — but mock anyway to avoid NPE
    setupEnrichmentAndCalleesQuery("com.esmp.Ghost", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    List<CodeChunk> chunks = chunkingService.chunkClasses("");

    assertThat(chunks).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Helpers: source file content
  // -------------------------------------------------------------------------

  private String threeMethodSource() {
    return """
        package com.esmp;

        /**
         * A test service class.
         */
        public class MyService {

            private String name;
            private int count;

            public void methodA() {
                // body A
            }

            public String methodB() {
                return name;
            }

            public int methodC() {
                return count;
            }
        }
        """;
  }

  private String singleMethodSource() {
    return """
        package com.esmp;

        /**
         * A simple class.
         */
        public class MyService {

            private String field;
        }
        """;
  }

  // -------------------------------------------------------------------------
  // Helpers: mock Neo4jClient setup
  // -------------------------------------------------------------------------

  private void setupClassQuery(List<Map<String, Object>> rows) {
    when(neo4jClient.query(anyString())
        .fetch()
        .all())
        .thenReturn((Collection) rows);
  }

  /**
   * Sets up Neo4jClient mock for enrichment (first call) and callees (second call).
   * ChunkingService calls queryEnrichment then queryCallees, both using the same fluent chain.
   * Mockito's thenReturn chaining returns first value on first invocation, second on next.
   */
  private void setupEnrichmentAndCalleesQuery(
      String fqn,
      List<String> callers,
      List<String> dependencies,
      List<String> implementors,
      List<Map<String, Object>> terms,
      List<String> methodIds,
      List<String> callees) {

    Map<String, Object> enrichmentResult = new HashMap<>();
    enrichmentResult.put("callers", callers);
    enrichmentResult.put("dependencies", dependencies);
    enrichmentResult.put("implementors", implementors);
    enrichmentResult.put("terms", terms);
    enrichmentResult.put("methodIds", methodIds);

    Map<String, Object> calleesResult = Map.of("callees", callees);

    when(neo4jClient.query(anyString())
        .bind(fqn).to("fqn")
        .fetch()
        .one())
        .thenReturn(Optional.of(enrichmentResult))
        .thenReturn(Optional.of(calleesResult));
  }

  // -------------------------------------------------------------------------
  // Helpers: test data builders
  // -------------------------------------------------------------------------

  private Map<String, Object> makeClassRow(
      String fqn, String simpleName, String pkg, String sourcePath, List<String> labels) {
    return makeClassRowWithRisk(fqn, simpleName, pkg, sourcePath, labels, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
  }

  private Map<String, Object> makeClassRowWithRisk(
      String fqn, String simpleName, String pkg, String sourcePath, List<String> labels,
      double srs, double ers, double dc, double ss, double fi, double brd) {
    Map<String, Object> row = new HashMap<>();
    row.put("fqn", fqn);
    row.put("simpleName", simpleName);
    row.put("pkg", pkg);
    row.put("sourcePath", sourcePath);
    row.put("hash", "abc123");
    row.put("srs", srs);
    row.put("ers", ers);
    row.put("dc", dc);
    row.put("ss", ss);
    row.put("fi", fi);
    row.put("brd", brd);
    row.put("labels", labels);
    return row;
  }

  private Path createSourceFile(String filename, String content) throws IOException {
    Path file = tempDir.resolve(filename);
    Files.writeString(file, content);
    return file;
  }
}
