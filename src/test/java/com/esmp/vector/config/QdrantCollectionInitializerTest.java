package com.esmp.vector.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test that verifies {@link QdrantCollectionInitializer} creates the {@code
 * code_chunks} collection with the correct vector configuration and 5 payload indexes at
 * application startup.
 *
 * <p>Spins up Testcontainers for Neo4j, MySQL, and Qdrant to match the full application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class QdrantCollectionInitializerTest {

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
  private QdrantClient qdrantClient;

  @Autowired
  private VectorConfig vectorConfig;

  @Test
  void collectionExistsAfterStartup() throws Exception {
    boolean exists =
        qdrantClient
            .collectionExistsAsync(vectorConfig.getCollectionName())
            .get(5, TimeUnit.SECONDS);

    assertThat(exists)
        .as("code_chunks collection should be created on startup")
        .isTrue();
  }

  @Test
  void collectionHasFivePayloadIndexes() throws Exception {
    CollectionInfo info =
        qdrantClient
            .getCollectionInfoAsync(vectorConfig.getCollectionName())
            .get(5, TimeUnit.SECONDS);

    Map<String, ?> payloadSchema = info.getPayloadSchemaMap();

    assertThat(payloadSchema).as("Expected 5 payload indexes").hasSize(5);
    assertThat(payloadSchema).containsKey("classFqn");
    assertThat(payloadSchema).containsKey("module");
    assertThat(payloadSchema).containsKey("stereotype");
    assertThat(payloadSchema).containsKey("chunkType");
    assertThat(payloadSchema).containsKey("enhancedRiskScore");
  }

  @Test
  void collectionHasCorrectVectorDimension() throws Exception {
    CollectionInfo info =
        qdrantClient
            .getCollectionInfoAsync(vectorConfig.getCollectionName())
            .get(5, TimeUnit.SECONDS);

    // Single-vector collection stores params via VectorsConfig.getParams()
    long dimension =
        info.getConfig().getParams().getVectorsConfig().getParams().getSize();
    assertThat(dimension)
        .as("Vector dimension should match all-MiniLM-L6-v2 output (384)")
        .isEqualTo(384L);
  }
}
