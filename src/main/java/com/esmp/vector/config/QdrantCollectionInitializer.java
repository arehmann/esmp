package com.esmp.vector.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the {@code code_chunks} Qdrant collection with vector config and payload indexes at
 * application startup.
 *
 * <p>This follows the same pattern as {@link com.esmp.extraction.config.Neo4jSchemaInitializer}:
 * idempotent startup registration that fails fast on misconfiguration. The collection uses:
 *
 * <ul>
 *   <li>384-dimensional cosine distance vectors (all-MiniLM-L6-v2 output)
 *   <li>Keyword indexes on: classFqn, module, stereotype, chunkType
 *   <li>Float index on: enhancedRiskScore (for range-filtered similarity queries)
 * </ul>
 */
@Component
public class QdrantCollectionInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final QdrantClient qdrantClient;
  private final VectorConfig vectorConfig;

  public QdrantCollectionInitializer(QdrantClient qdrantClient, VectorConfig vectorConfig) {
    this.qdrantClient = qdrantClient;
    this.vectorConfig = vectorConfig;
  }

  @Override
  public void run(ApplicationArguments args) {
    String collection = vectorConfig.getCollectionName();
    log.info("Ensuring Qdrant collection '{}' exists with vector config and payload indexes...", collection);

    try {
      boolean exists = qdrantClient.collectionExistsAsync(collection).get(5, java.util.concurrent.TimeUnit.SECONDS);

      if (!exists) {
        log.info("Collection '{}' not found — creating with {} dimensions, cosine distance.",
            collection, vectorConfig.getVectorDimension());
        VectorParams vectorParams = VectorParams.newBuilder()
            .setSize(vectorConfig.getVectorDimension())
            .setDistance(Distance.Cosine)
            .build();
        qdrantClient.createCollectionAsync(collection, vectorParams).get(5, java.util.concurrent.TimeUnit.SECONDS);
        log.info("Collection '{}' created.", collection);
      } else {
        log.info("Collection '{}' already exists — skipping creation.", collection);
      }

      // Create payload indexes for efficient filtered similarity queries
      createPayloadIndex(collection, "classFqn", PayloadSchemaType.Keyword);
      createPayloadIndex(collection, "module", PayloadSchemaType.Keyword);
      createPayloadIndex(collection, "stereotype", PayloadSchemaType.Keyword);
      createPayloadIndex(collection, "chunkType", PayloadSchemaType.Keyword);
      createPayloadIndex(collection, "enhancedRiskScore", PayloadSchemaType.Float);

      log.info("Qdrant collection '{}' is ready with 5 payload indexes.", collection);

    } catch (Exception e) {
      log.error("Failed to initialize Qdrant collection '{}': {}", collection, e.getMessage(), e);
      throw new IllegalStateException("Qdrant collection initialization failed for: " + collection, e);
    }
  }

  private void createPayloadIndex(String collection, String field, PayloadSchemaType schemaType) {
    try {
      qdrantClient
          .createPayloadIndexAsync(collection, field, schemaType, null, true, null, TIMEOUT)
          .get(5, java.util.concurrent.TimeUnit.SECONDS);
      log.info("Payload index ensured on '{}' field '{}' ({}).", collection, field, schemaType);
    } catch (Exception e) {
      log.warn("Could not create payload index on '{}' field '{}': {}", collection, field, e.getMessage());
    }
  }
}
