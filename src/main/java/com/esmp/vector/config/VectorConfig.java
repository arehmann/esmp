package com.esmp.vector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the vector indexing subsystem.
 *
 * <p>Bound from the {@code esmp.vector} prefix in {@code application.yml}. All values have
 * sensible defaults that match the all-MiniLM-L6-v2 ONNX model used for local embeddings.
 */
@Component
@ConfigurationProperties(prefix = "esmp.vector")
public class VectorConfig {

  /** Name of the Qdrant collection used for code chunks. Default: "code_chunks". */
  private String collectionName = "code_chunks";

  /** Embedding vector dimension. Matches all-MiniLM-L6-v2 output size. Default: 384. */
  private int vectorDimension = 384;

  /** Number of chunks per Qdrant upsert batch. Default: 64. */
  private int batchSize = 64;

  public String getCollectionName() {
    return collectionName;
  }

  public void setCollectionName(String collectionName) {
    this.collectionName = collectionName;
  }

  public int getVectorDimension() {
    return vectorDimension;
  }

  public void setVectorDimension(int vectorDimension) {
    this.vectorDimension = vectorDimension;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }
}
