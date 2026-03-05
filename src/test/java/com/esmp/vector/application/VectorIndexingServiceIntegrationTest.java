package com.esmp.vector.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.vector.api.IndexStatusResponse;
import com.esmp.vector.config.VectorConfig;
import com.esmp.vector.util.ChunkIdGenerator;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
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

import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;

/**
 * Integration tests for {@link VectorIndexingService} with Neo4j, MySQL, and Qdrant Testcontainers.
 *
 * <p>Covers requirements VEC-01 through VEC-04:
 * <ul>
 *   <li>VEC-03: After indexAll, Qdrant code_chunks collection has points with 384-dim vectors
 *   <li>VEC-03: Point IDs are deterministic — indexAll is idempotent
 *   <li>VEC-02: Upserted points contain enrichment payload (callers, callees, domainTerms, risk scores, vaadin7Detected)
 *   <li>VEC-04: After indexAll, reindex with unchanged files returns 0 changed files
 *   <li>VEC-04: After modifying file hash, reindex re-embeds only that file's chunks
 *   <li>VEC-01: A class with 2 methods produces exactly 3 points (1 header + 2 methods)
 *   <li>VEC-03: Similarity search returns results with payloads
 * </ul>
 *
 * <p>Test setup creates synthetic Java source files in a temp directory and corresponding
 * JavaClass / JavaMethod nodes in Neo4j. Each test starts from a clean Qdrant collection state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class VectorIndexingServiceIntegrationTest {

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
  private VectorIndexingService vectorIndexingService;

  @Autowired
  private QdrantClient qdrantClient;

  @Autowired
  private VectorConfig vectorConfig;

  @Autowired
  private EmbeddingModel embeddingModel;

  @TempDir
  Path tempDir;

  // Test class FQNs used across tests
  private static final String SERVICE_FQN = "com.test.OrderService";
  private static final String ENTITY_FQN = "com.test.OrderEntity";

  @BeforeEach
  void setUp() throws IOException {
    // Clear Neo4j
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // Clear Qdrant collection (delete all points by scrolling)
    clearQdrantCollection();

    // Create synthetic Java source files in tempDir
    createServiceSourceFile();
    createEntitySourceFile();

    // Create corresponding Neo4j nodes
    createServiceClassNode();
    createEntityClassNode();
  }

  // ---------------------------------------------------------------------------
  // Test 1: After indexAll, Qdrant has points with 384-dim vectors (VEC-03)
  // ---------------------------------------------------------------------------

  @Test
  void indexAll_createsPointsInQdrant() throws Exception {
    // Act
    IndexStatusResponse response = vectorIndexingService.indexAll(tempDir.toString());

    // Assert response
    assertThat(response.filesProcessed()).as("Two classes should be processed").isEqualTo(2);
    assertThat(response.chunksIndexed()).as("Should index header + methods per class").isGreaterThan(0);
    assertThat(response.durationMs()).as("Duration should be recorded").isGreaterThanOrEqualTo(0);

    // Assert points in Qdrant
    var scrollResult = qdrantClient.scrollAsync(
        ScrollPoints.newBuilder()
            .setCollectionName(vectorConfig.getCollectionName())
            .setLimit(100)
            .setWithVectors(WithVectorsSelectorFactory.enable(true))
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build()
    ).get(10, TimeUnit.SECONDS);

    assertThat(scrollResult.getResultList())
        .as("Qdrant should have points after indexAll")
        .isNotEmpty();

    // Verify vector dimensions are correct via collection info (384-dim is asserted separately
    // by QdrantCollectionInitializerTest; here we confirm indexing actually populated the collection)
    var collectionInfo = qdrantClient.getCollectionInfoAsync(vectorConfig.getCollectionName())
        .get(10, TimeUnit.SECONDS);
    long configuredDimension = collectionInfo.getConfig().getParams()
        .getVectorsConfig().getParams().getSize();
    assertThat(configuredDimension)
        .as("Collection should be configured for 384-dim vectors (all-MiniLM-L6-v2)")
        .isEqualTo(384L);

    // Verify that the number of indexed points matches what was reported by the response
    assertThat((long) scrollResult.getResultList().size())
        .as("Qdrant point count should match chunksIndexed from indexAll")
        .isEqualTo(response.chunksIndexed());
  }

  // ---------------------------------------------------------------------------
  // Test 2: Point IDs are deterministic — indexAll is idempotent (VEC-03)
  // ---------------------------------------------------------------------------

  @Test
  void indexAll_isDeterministic() throws Exception {
    // Act: index twice
    vectorIndexingService.indexAll(tempDir.toString());
    IndexStatusResponse secondRun = vectorIndexingService.indexAll(tempDir.toString());

    // Assert: point count is the same (upsert, not duplicate)
    var scrollResult = qdrantClient.scrollAsync(
        ScrollPoints.newBuilder()
            .setCollectionName(vectorConfig.getCollectionName())
            .setLimit(200)
            .setWithPayload(WithPayloadSelectorFactory.enable(false))
            .build()
    ).get(10, TimeUnit.SECONDS);

    // Should be the same number of points as after the first run
    int pointCount = scrollResult.getResultList().size();
    assertThat(pointCount)
        .as("Idempotent upsert should not create duplicate points")
        .isEqualTo(secondRun.chunksIndexed());
  }

  // ---------------------------------------------------------------------------
  // Test 3: Payload contains enrichment fields (VEC-02)
  // ---------------------------------------------------------------------------

  @Test
  void enrichmentPayload_containsNeighborsAndTerms() throws Exception {
    // Arrange: add a business term and USES_TERM edge for the service
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        CREATE (t:BusinessTerm {termId: 'order-term-1', displayName: 'Order', definition: 'Order domain term'})
        CREATE (c)-[:USES_TERM]->(t)
        """)
        .bindAll(Map.of("fqn", SERVICE_FQN))
        .run();

    // Act
    vectorIndexingService.indexAll(tempDir.toString());

    // Assert: retrieve a point for the service class and check payload
    UUID headerPointId = ChunkIdGenerator.chunkId(SERVICE_FQN, "__HEADER__");
    var getResult = qdrantClient.retrieveAsync(
        vectorConfig.getCollectionName(),
        List.of(io.qdrant.client.PointIdFactory.id(headerPointId)),
        WithPayloadSelectorFactory.enable(true),
        WithVectorsSelectorFactory.enable(false),
        null
    ).get(10, TimeUnit.SECONDS);

    assertThat(getResult).as("Should find the service header point").isNotEmpty();
    var point = getResult.get(0);
    var payload = point.getPayloadMap();

    assertThat(payload).containsKey("classFqn");
    assertThat(payload.get("classFqn").getStringValue()).isEqualTo(SERVICE_FQN);
    assertThat(payload).containsKey("chunkType");
    assertThat(payload.get("chunkType").getStringValue()).isEqualTo("CLASS_HEADER");
    assertThat(payload).containsKey("contentHash");
    assertThat(payload).containsKey("structuralRiskScore");
    assertThat(payload).containsKey("enhancedRiskScore");
    assertThat(payload).containsKey("vaadin7Detected");
    assertThat(payload).containsKey("domainTerms");
    // Domain terms should contain our business term
    String domainTerms = payload.get("domainTerms").getStringValue();
    assertThat(domainTerms).as("domainTerms payload should contain order-term-1").contains("order-term-1");
  }

  // ---------------------------------------------------------------------------
  // Test 4: Reindex skips unchanged files (VEC-04)
  // ---------------------------------------------------------------------------

  @Test
  void reindex_skipsUnchangedFiles() throws Exception {
    // Arrange: index first, then reindex with same files
    vectorIndexingService.indexAll(tempDir.toString());

    // Act: reindex with same source — hashes should match
    IndexStatusResponse reindexResponse = vectorIndexingService.reindex(tempDir.toString());

    // Assert: no files changed
    assertThat(reindexResponse.filesProcessed())
        .as("No files changed — filesProcessed should be 0")
        .isEqualTo(0);
    assertThat(reindexResponse.chunksSkipped())
        .as("All chunks should be skipped as unchanged")
        .isGreaterThan(0);
    assertThat(reindexResponse.chunksIndexed())
        .as("No new chunks should be indexed")
        .isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // Test 5: Reindex re-embeds changed files (VEC-04)
  // ---------------------------------------------------------------------------

  @Test
  void reindex_reEmbedsChangedFiles() throws Exception {
    // Arrange: index first
    vectorIndexingService.indexAll(tempDir.toString());

    // Simulate source file change by updating contentHash in Neo4j
    neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        SET c.contentHash = 'new-hash-after-modification'
        """)
        .bindAll(Map.of("fqn", SERVICE_FQN))
        .run();

    // Modify the source file on disk to match the new state
    Path serviceFile = tempDir.resolve("OrderService.java");
    Files.writeString(serviceFile, Files.readString(serviceFile) + "\n// modified");

    // Act
    IndexStatusResponse reindexResponse = vectorIndexingService.reindex(tempDir.toString());

    // Assert: exactly 1 class changed and was re-embedded
    assertThat(reindexResponse.filesProcessed())
        .as("Only the modified class should be re-embedded")
        .isEqualTo(1);
    assertThat(reindexResponse.chunksIndexed())
        .as("Service class chunks should be re-embedded")
        .isGreaterThan(0);
  }

  // ---------------------------------------------------------------------------
  // Test 6: Chunk count matches header + methods (VEC-01)
  // ---------------------------------------------------------------------------

  @Test
  void chunkCount_matchesHeaderPlusMethods() throws Exception {
    // OrderService has 2 methods → 1 header + 2 methods = 3 chunks for that class
    vectorIndexingService.indexAll(tempDir.toString());

    // Count points for SERVICE_FQN
    var scrollResult = qdrantClient.scrollAsync(
        ScrollPoints.newBuilder()
            .setCollectionName(vectorConfig.getCollectionName())
            .setLimit(100)
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build()
    ).get(10, TimeUnit.SECONDS);

    long serviceChunks = scrollResult.getResultList().stream()
        .filter(p -> {
          var payload = p.getPayloadMap();
          return payload.containsKey("classFqn")
              && SERVICE_FQN.equals(payload.get("classFqn").getStringValue());
        })
        .count();

    assertThat(serviceChunks)
        .as("OrderService with 2 methods should produce 3 chunks (1 header + 2 methods)")
        .isEqualTo(3L);
  }

  // ---------------------------------------------------------------------------
  // Test 7: Similarity search returns results (VEC-03)
  // ---------------------------------------------------------------------------

  @Test
  void similaritySearch_returnsRelevantChunks() throws Exception {
    // Arrange
    vectorIndexingService.indexAll(tempDir.toString());

    // Embed a query text
    float[] queryVector = embeddingModel.embed("order processing service method");

    // Build the search request
    SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
        .setCollectionName(vectorConfig.getCollectionName())
        .setLimit(5)
        .setWithPayload(WithPayloadSelectorFactory.enable(true));
    for (float v : queryVector) {
      searchBuilder.addVector(v);
    }

    // Act: similarity search
    List<ScoredPoint> results = qdrantClient.searchAsync(searchBuilder.build())
        .get(30, TimeUnit.SECONDS);

    // Assert
    assertThat(results)
        .as("Similarity search should return results after indexing")
        .isNotEmpty();

    // Verify results have payloads
    var firstResult = results.get(0);
    assertThat(firstResult.getPayloadMap())
        .as("Search results should have populated payloads")
        .containsKey("classFqn");
    assertThat(firstResult.getScore())
        .as("Similarity score should be > 0")
        .isGreaterThan(0.0f);
  }

  // ---------------------------------------------------------------------------
  // Test helper: Neo4j data creation
  // ---------------------------------------------------------------------------

  private void createServiceClassNode() {
    neo4jClient.query("""
        CREATE (c:JavaClass:Service {
            fullyQualifiedName: $fqn,
            simpleName: 'OrderService',
            packageName: 'com.test',
            sourceFilePath: $path,
            contentHash: 'hash-service-v1',
            structuralRiskScore: 0.5,
            enhancedRiskScore: 0.6,
            domainCriticality: 0.7,
            securitySensitivity: 0.1,
            financialInvolvement: 0.8,
            businessRuleDensity: 0.3,
            complexitySum: 5,
            complexityMax: 3,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: false,
            dbWriteCount: 0
        })
        CREATE (m1:JavaMethod {
            methodId: $methodId1,
            simpleName: 'placeOrder',
            declaringClass: $fqn,
            cyclomaticComplexity: 3,
            parameterTypes: []
        })
        CREATE (m2:JavaMethod {
            methodId: $methodId2,
            simpleName: 'cancelOrder',
            declaringClass: $fqn,
            cyclomaticComplexity: 2,
            parameterTypes: []
        })
        CREATE (c)-[:DECLARES_METHOD]->(m1)
        CREATE (c)-[:DECLARES_METHOD]->(m2)
        """)
        .bindAll(Map.of(
            "fqn", SERVICE_FQN,
            "path", "OrderService.java",
            "methodId1", SERVICE_FQN + "#placeOrder(Order)",
            "methodId2", SERVICE_FQN + "#cancelOrder(Long)"))
        .run();
  }

  private void createEntityClassNode() {
    neo4jClient.query("""
        CREATE (c:JavaClass {
            fullyQualifiedName: $fqn,
            simpleName: 'OrderEntity',
            packageName: 'com.test',
            sourceFilePath: $path,
            contentHash: 'hash-entity-v1',
            structuralRiskScore: 0.2,
            enhancedRiskScore: 0.3,
            domainCriticality: 0.4,
            securitySensitivity: 0.0,
            financialInvolvement: 0.5,
            businessRuleDensity: 0.1,
            complexitySum: 2,
            complexityMax: 1,
            fanIn: 0,
            fanOut: 0,
            hasDbWrites: false,
            dbWriteCount: 0
        })
        CREATE (m:JavaMethod {
            methodId: $methodId,
            simpleName: 'getId',
            declaringClass: $fqn,
            cyclomaticComplexity: 1,
            parameterTypes: []
        })
        CREATE (c)-[:DECLARES_METHOD]->(m)
        """)
        .bindAll(Map.of(
            "fqn", ENTITY_FQN,
            "path", "OrderEntity.java",
            "methodId", ENTITY_FQN + "#getId()"))
        .run();
  }

  // ---------------------------------------------------------------------------
  // Test helper: Source file creation
  // ---------------------------------------------------------------------------

  private void createServiceSourceFile() throws IOException {
    String source = """
        package com.test;

        /**
         * Service for managing orders in the system.
         */
        public class OrderService {

            private final OrderRepository orderRepository;

            public void placeOrder(Order order) {
                orderRepository.save(order);
            }

            public void cancelOrder(Long orderId) {
                Order order = orderRepository.findById(orderId);
                if (order != null) {
                    order.cancel();
                    orderRepository.save(order);
                }
            }
        }
        """;
    Files.writeString(tempDir.resolve("OrderService.java"), source);
  }

  private void createEntitySourceFile() throws IOException {
    String source = """
        package com.test;

        public class OrderEntity {

            private Long id;
            private String status;

            public Long getId() {
                return id;
            }
        }
        """;
    Files.writeString(tempDir.resolve("OrderEntity.java"), source);
  }

  // ---------------------------------------------------------------------------
  // Test helper: Qdrant cleanup
  // ---------------------------------------------------------------------------

  private void clearQdrantCollection() {
    try {
      // Delete all points by scrolling and collecting IDs
      boolean hasMore = true;
      io.qdrant.client.grpc.Points.PointId nextOffset = null;
      List<io.qdrant.client.grpc.Points.PointId> allIds = new java.util.ArrayList<>();

      while (hasMore) {
        ScrollPoints.Builder builder = ScrollPoints.newBuilder()
            .setCollectionName(vectorConfig.getCollectionName())
            .setLimit(500);
        if (nextOffset != null) {
          builder.setOffset(nextOffset);
        }

        var result = qdrantClient.scrollAsync(builder.build()).get(10, TimeUnit.SECONDS);
        result.getResultList().forEach(p -> allIds.add(p.getId()));

        if (result.hasNextPageOffset()) {
          nextOffset = result.getNextPageOffset();
        } else {
          hasMore = false;
        }
      }

      if (!allIds.isEmpty()) {
        qdrantClient.deleteAsync(vectorConfig.getCollectionName(), allIds)
            .get(10, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      // If collection doesn't exist yet, that's fine
    }
  }
}
