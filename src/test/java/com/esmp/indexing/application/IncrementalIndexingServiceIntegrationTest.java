package com.esmp.indexing.application;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.indexing.api.IncrementalIndexRequest;
import com.esmp.indexing.api.IncrementalIndexResponse;
import com.esmp.vector.config.VectorConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.ScrollPoints;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * Full pipeline integration tests for the incremental indexing service.
 *
 * <p>Validates CI-01, CI-02, CI-03, SLO-03, and SLO-04 end-to-end with real Neo4j, MySQL, Qdrant,
 * and ONNX embedding via Testcontainers.
 *
 * <p>Each test runs its own clean setup (Neo4j DETACH DELETE + Qdrant clear) to ensure isolation,
 * since tests exercise mutually conflicting state (deletion, hash modification, large bulk loads).
 *
 * <ul>
 *   <li>CI-01: Only changed files are extracted on an incremental run.
 *   <li>CI-02: Hash detection skips unchanged files; hash update on change; cascade delete.
 *   <li>CI-03: Selective vector re-embedding for changed/deleted classes.
 *   <li>SLO-03: 5-file incremental run completes in under 30 seconds.
 *   <li>SLO-04: 100-class full re-index completes in under 5 minutes (300,000ms).
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class IncrementalIndexingServiceIntegrationTest {

  // ---------------------------------------------------------------------------
  // Testcontainers
  // ---------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------
  // Dependencies
  // ---------------------------------------------------------------------------

  @Autowired
  private IncrementalIndexingService incrementalIndexingService;

  @Autowired
  private Neo4jClient neo4jClient;

  @Autowired
  private QdrantClient qdrantClient;

  @Autowired
  private VectorConfig vectorConfig;

  @TempDir
  Path tempDir;

  // ---------------------------------------------------------------------------
  // Constants
  // ---------------------------------------------------------------------------

  private static final String BASE_SERVICE_FQN = "com.esmp.incremental.BaseService";
  private static final String BASE_REPO_FQN = "com.esmp.incremental.BaseRepository";
  private static final String BASE_ENTITY_FQN = "com.esmp.incremental.BaseEntity";

  // ---------------------------------------------------------------------------
  // Per-test setup: reset state and run baseline extraction for isolation
  // ---------------------------------------------------------------------------

  @BeforeEach
  void setUpBaseline() throws Exception {
    // Reset Neo4j and Qdrant for clean test isolation
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    clearQdrantCollection();

    // Copy the 3 core fixture files into tempDir
    copyFixture("BaseService.java", tempDir);
    copyFixture("BaseRepository.java", tempDir);
    copyFixture("BaseEntity.java", tempDir);

    // Run incremental with all 3 as changedFiles to establish baseline graph + vectors
    List<String> changedFiles = List.of(
        tempDir.resolve("BaseService.java").toString(),
        tempDir.resolve("BaseRepository.java").toString(),
        tempDir.resolve("BaseEntity.java").toString()
    );
    IncrementalIndexRequest baselineRequest = new IncrementalIndexRequest(
        changedFiles, List.of(), tempDir.toString(), null);
    incrementalIndexingService.runIncremental(baselineRequest);
  }

  // ---------------------------------------------------------------------------
  // CI-01: incrementalRun_extractsOnlyChangedFiles
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("CI-01: incremental run extracts only 3 changed files — nodes exist with contentHash")
  void incrementalRun_extractsOnlyChangedFiles() {
    // Baseline already ran in @BeforeEach. Verify Neo4j has JavaClass nodes with contentHash set.
    long classCount = neo4jClient.query(
            "MATCH (c:JavaClass) WHERE c.contentHash IS NOT NULL RETURN count(c) AS cnt")
        .fetchAs(Long.class).one().orElse(0L);

    assertThat(classCount)
        .as("Baseline extraction should persist at least 3 JavaClass nodes with non-null contentHash")
        .isGreaterThanOrEqualTo(3L);
  }

  // ---------------------------------------------------------------------------
  // CI-02: unchangedFile_isSkipped
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("CI-02: unchanged files are skipped on second run — classesSkipped=3, extracted=0")
  void unchangedFile_isSkipped() {
    // Run incremental again with the same 3 files — hashes match stored values, all skipped
    List<String> changedFiles = List.of(
        tempDir.resolve("BaseService.java").toString(),
        tempDir.resolve("BaseRepository.java").toString(),
        tempDir.resolve("BaseEntity.java").toString()
    );
    IncrementalIndexRequest request = new IncrementalIndexRequest(
        changedFiles, List.of(), tempDir.toString(), null);
    IncrementalIndexResponse response = incrementalIndexingService.runIncremental(request);

    assertThat(response.classesSkipped())
        .as("All 3 files should be skipped because their SHA-256 hash is unchanged")
        .isEqualTo(3);
    assertThat(response.classesExtracted())
        .as("No files should be extracted when all hashes match")
        .isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // CI-02: changedFile_updatesContentHashOnClassNode
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("CI-02: modified file gets re-extracted and contentHash updated in Neo4j")
  void changedFile_updatesContentHashOnClassNode() throws Exception {
    // Record stored hash for BaseService before modification
    String hashBefore = neo4jClient.query(
            "MATCH (c:JavaClass {fullyQualifiedName: $fqn}) RETURN c.contentHash AS h")
        .bind(BASE_SERVICE_FQN).to("fqn")
        .fetchAs(String.class).one().orElse(null);

    // Overwrite BaseService.java with ModifiedService content (same FQN, different body)
    copyFixture("ModifiedService.java", tempDir, "BaseService.java");

    List<String> changedFiles = List.of(tempDir.resolve("BaseService.java").toString());
    IncrementalIndexRequest request = new IncrementalIndexRequest(
        changedFiles, List.of(), tempDir.toString(), null);
    IncrementalIndexResponse response = incrementalIndexingService.runIncremental(request);

    assertThat(response.classesExtracted())
        .as("Modified BaseService should be re-extracted")
        .isEqualTo(1);
    assertThat(response.classesSkipped())
        .as("No file should be skipped when hash changed")
        .isEqualTo(0);

    // Verify contentHash in Neo4j was updated
    String hashAfter = neo4jClient.query(
            "MATCH (c:JavaClass {fullyQualifiedName: $fqn}) RETURN c.contentHash AS h")
        .bind(BASE_SERVICE_FQN).to("fqn")
        .fetchAs(String.class).one().orElse(null);

    assertThat(hashAfter)
        .as("contentHash should be non-null after re-extraction")
        .isNotNull();
    assertThat(hashAfter)
        .as("contentHash should differ from the pre-modification hash")
        .isNotEqualTo(hashBefore);

    // Verify it equals SHA-256 of the modified file on disk
    String expectedHash = sha256(tempDir.resolve("BaseService.java"));
    assertThat(hashAfter)
        .as("contentHash should match SHA-256 of modified file")
        .isEqualTo(expectedHash);
  }

  // ---------------------------------------------------------------------------
  // CI-02: deletedFile_removesClassNodeFromNeo4j
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("CI-02: deleted file causes cascade deletion of JavaClass node from Neo4j")
  void deletedFile_removesClassNodeFromNeo4j() {
    // Verify BaseEntity exists in Neo4j before deletion
    long beforeCount = neo4jClient.query(
            "MATCH (c:JavaClass {fullyQualifiedName: $fqn}) RETURN count(c) AS cnt")
        .bind(BASE_ENTITY_FQN).to("fqn")
        .fetchAs(Long.class).one().orElse(0L);
    assertThat(beforeCount).as("BaseEntity should exist in Neo4j before deletion").isGreaterThan(0L);

    // Run incremental treating BaseEntity.java as a deleted file
    String entityPath = tempDir.resolve("BaseEntity.java").toString();
    IncrementalIndexRequest request = new IncrementalIndexRequest(
        List.of(), List.of(entityPath), tempDir.toString(), null);
    IncrementalIndexResponse response = incrementalIndexingService.runIncremental(request);

    assertThat(response.classesDeleted())
        .as("classesDeleted should be 1 for the removed BaseEntity")
        .isEqualTo(1);

    // JavaClass node for BaseEntity should be gone
    long afterCount = neo4jClient.query(
            "MATCH (c:JavaClass {fullyQualifiedName: $fqn}) RETURN count(c) AS cnt")
        .bind(BASE_ENTITY_FQN).to("fqn")
        .fetchAs(Long.class).one().orElse(0L);
    assertThat(afterCount)
        .as("JavaClass node for com.esmp.incremental.BaseEntity should be absent after deletion")
        .isEqualTo(0L);
  }

  // ---------------------------------------------------------------------------
  // CI-03: deletedFile_removesQdrantChunks
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("CI-03: deleted file causes Qdrant chunks for that class to be removed")
  void deletedFile_removesQdrantChunks() throws Exception {
    String entityPath = tempDir.resolve("BaseEntity.java").toString();
    IncrementalIndexRequest request = new IncrementalIndexRequest(
        List.of(), List.of(entityPath), tempDir.toString(), null);
    IncrementalIndexResponse response = incrementalIndexingService.runIncremental(request);

    // After deletion, Qdrant should have 0 chunks for BaseEntity
    long chunksAfter = countQdrantChunks(BASE_ENTITY_FQN);
    assertThat(chunksAfter)
        .as("Qdrant should have 0 chunks for deleted class BaseEntity")
        .isEqualTo(0L);

    assertThat(response.classesDeleted())
        .as("classesDeleted count should reflect the deleted class")
        .isGreaterThanOrEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // CI-03: changedFile_updatesQdrantChunks
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("CI-03: changed file causes Qdrant chunks to be re-embedded with updated contentHash")
  void changedFile_updatesQdrantChunks() throws Exception {
    // Overwrite BaseService.java with ModifiedService content
    copyFixture("ModifiedService.java", tempDir, "BaseService.java");

    List<String> changedFiles = List.of(tempDir.resolve("BaseService.java").toString());
    IncrementalIndexRequest request = new IncrementalIndexRequest(
        changedFiles, List.of(), tempDir.toString(), null);
    IncrementalIndexResponse response = incrementalIndexingService.runIncremental(request);

    assertThat(response.chunksReEmbedded())
        .as("Should have re-embedded at least 1 chunk for the modified BaseService")
        .isGreaterThan(0);

    // Qdrant points for BaseService should exist with a contentHash payload
    var scrollResult = qdrantClient.scrollAsync(
        ScrollPoints.newBuilder()
            .setCollectionName(vectorConfig.getCollectionName())
            .setFilter(Filter.newBuilder()
                .addMust(matchKeyword("classFqn", BASE_SERVICE_FQN))
                .build())
            .setLimit(50)
            .setWithVectors(WithVectorsSelectorFactory.enable(false))
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build()
    ).get(15, TimeUnit.SECONDS);

    assertThat(scrollResult.getResultList())
        .as("Qdrant should contain re-indexed chunks for BaseService after modification")
        .isNotEmpty();

    boolean anyHaveHash = scrollResult.getResultList().stream()
        .anyMatch(p -> p.getPayload().containsKey("contentHash")
            && !p.getPayload().get("contentHash").getStringValue().isBlank());
    assertThat(anyHaveHash)
        .as("At least one re-indexed chunk should carry a non-blank contentHash payload")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // SLO-03: incrementalRun_5files_completesUnder30Seconds
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("SLO-03: incremental run of 5 changed files completes in under 30 seconds")
  void incrementalRun_5files_completesUnder30Seconds() throws Exception {
    // Modify the 3 core files slightly to force hash changes
    for (String name : List.of("BaseService.java", "BaseRepository.java", "BaseEntity.java")) {
      Path file = tempDir.resolve(name);
      if (Files.exists(file)) {
        String current = Files.readString(file);
        Files.writeString(file, current + "\n// SLO-03 timing annotation\n");
      }
    }

    // Add 2 more files for a total of 5
    copyFixture("NewController.java", tempDir);
    copyFixture("ModifiedService.java", tempDir, "ExtraService.java");

    List<String> changedFiles = List.of(
        tempDir.resolve("BaseService.java").toString(),
        tempDir.resolve("BaseRepository.java").toString(),
        tempDir.resolve("BaseEntity.java").toString(),
        tempDir.resolve("NewController.java").toString(),
        tempDir.resolve("ExtraService.java").toString()
    );

    IncrementalIndexRequest request = new IncrementalIndexRequest(
        changedFiles, List.of(), tempDir.toString(), null);
    IncrementalIndexResponse response = incrementalIndexingService.runIncremental(request);

    assertThat(response.durationMs())
        .as("SLO-03: 5-file incremental run must complete in under 30,000ms")
        .isLessThan(30_000L);
  }

  // ---------------------------------------------------------------------------
  // SLO-04: fullReindex_100classes_completesUnder5Minutes
  // ---------------------------------------------------------------------------

  @Test
  @Tag("slow")
  @DisplayName("SLO-04: full re-index of ~100 classes completes in under 5 minutes (300,000ms)")
  void fullReindex_100classes_completesUnder5Minutes() throws Exception {
    // Reset Neo4j and Qdrant so there are no stale hashes that could cause files to be skipped
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    clearQdrantCollection();

    // Use a subdirectory to separate bulk files from the core fixtures in tempDir
    Path bulkDir = tempDir.resolve("bulk-slo04");
    Files.createDirectories(bulkDir);

    // Copy 3 core fixture files into bulkDir
    copyFixture("BaseService.java", bulkDir);
    copyFixture("BaseRepository.java", bulkDir);
    copyFixture("BaseEntity.java", bulkDir);

    // Copy all 97 bulk stubs from classpath
    copyBulkFixtures(bulkDir);

    // Verify we have >= 100 files
    List<String> allJavaFiles = Files.walk(bulkDir)
        .filter(p -> p.toString().endsWith(".java"))
        .map(Path::toString)
        .toList();

    assertThat(allJavaFiles.size())
        .as("Bulk dir should contain >= 100 .java files for SLO-04")
        .isGreaterThanOrEqualTo(100);

    // Run full re-index by passing all files as changedFiles (mirrors IndexingController full path)
    IncrementalIndexRequest request = new IncrementalIndexRequest(
        allJavaFiles, List.of(), bulkDir.toString(), null);
    IncrementalIndexResponse response = incrementalIndexingService.runIncremental(request);

    assertThat(response.classesExtracted())
        .as("SLO-04: full re-index should extract >= 100 classes")
        .isGreaterThanOrEqualTo(100);

    assertThat(response.durationMs())
        .as("SLO-04: full re-index of 100 classes must complete in under 5 minutes (300,000ms)")
        .isLessThan(300_000L);

    assertThat(response.errors())
        .as("SLO-04: full re-index should complete without errors")
        .isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Fixture helpers
  // ---------------------------------------------------------------------------

  private static void copyFixture(String resourceName, Path destination) throws IOException {
    copyFixture(resourceName, destination, resourceName);
  }

  private static void copyFixture(String resourceName, Path destination, String targetName)
      throws IOException {
    String resource = "/fixtures/incremental/" + resourceName;
    try (InputStream is = IncrementalIndexingServiceIntegrationTest.class.getResourceAsStream(resource)) {
      Objects.requireNonNull(is, "Fixture resource not found: " + resource);
      Files.copy(is, destination.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Copies all 97 bulk fixture stubs from classpath into the destination directory.
   */
  private static void copyBulkFixtures(Path destination) throws IOException {
    for (int i = 1; i <= 30; i++) {
      copyBulkFixture(String.format("BulkEntity%02d.java", i), destination);
    }
    for (int i = 1; i <= 30; i++) {
      copyBulkFixture(String.format("BulkService%02d.java", i), destination);
    }
    for (int i = 1; i <= 20; i++) {
      copyBulkFixture(String.format("BulkRepo%02d.java", i), destination);
    }
    for (int i = 1; i <= 17; i++) {
      copyBulkFixture(String.format("BulkUtil%02d.java", i), destination);
    }
  }

  private static void copyBulkFixture(String name, Path destination) throws IOException {
    String resource = "/fixtures/incremental/bulk/" + name;
    try (InputStream is = IncrementalIndexingServiceIntegrationTest.class.getResourceAsStream(resource)) {
      if (is == null) {
        throw new IOException("Bulk fixture resource not found: " + resource);
      }
      Files.copy(is, destination.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // ---------------------------------------------------------------------------
  // Qdrant helpers
  // ---------------------------------------------------------------------------

  /** Counts Qdrant points for a given classFqn payload value. */
  private long countQdrantChunks(String classFqn) throws Exception {
    Filter filter = Filter.newBuilder()
        .addMust(matchKeyword("classFqn", classFqn))
        .build();
    var scrollResult = qdrantClient.scrollAsync(
        ScrollPoints.newBuilder()
            .setCollectionName(vectorConfig.getCollectionName())
            .setFilter(filter)
            .setLimit(1000)
            .setWithVectors(WithVectorsSelectorFactory.enable(false))
            .setWithPayload(WithPayloadSelectorFactory.enable(false))
            .build()
    ).get(15, TimeUnit.SECONDS);
    return scrollResult.getResultList().size();
  }

  /** Clears all points from the Qdrant collection (for test isolation). */
  private void clearQdrantCollection() {
    try {
      var scrollResult = qdrantClient.scrollAsync(
          ScrollPoints.newBuilder()
              .setCollectionName(vectorConfig.getCollectionName())
              .setLimit(10000)
              .setWithVectors(WithVectorsSelectorFactory.enable(false))
              .setWithPayload(WithPayloadSelectorFactory.enable(false))
              .build()
      ).get(15, TimeUnit.SECONDS);

      if (!scrollResult.getResultList().isEmpty()) {
        var ids = scrollResult.getResultList().stream()
            .map(p -> p.getId())
            .toList();
        qdrantClient.deleteAsync(vectorConfig.getCollectionName(), ids)
            .get(15, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      // Non-fatal — collection may be empty or not yet created
    }
  }

  // ---------------------------------------------------------------------------
  // SHA-256 helper (mirrors FileHashUtil without depending on it directly)
  // ---------------------------------------------------------------------------

  private static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] bytes = Files.readAllBytes(file);
    byte[] hashBytes = digest.digest(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : hashBytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
