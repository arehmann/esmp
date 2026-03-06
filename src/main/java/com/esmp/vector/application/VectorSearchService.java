package com.esmp.vector.application;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import com.esmp.vector.api.ChunkSearchResult;
import com.esmp.vector.api.SearchRequest;
import com.esmp.vector.api.SearchResponse;
import com.esmp.vector.config.VectorConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.JsonWithInt.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Service for text-to-vector similarity search over the Qdrant {@code code_chunks} collection.
 *
 * <p>Embeds a natural-language query using the Spring AI {@code EmbeddingModel} (all-MiniLM-L6-v2,
 * 384 dimensions) and executes a Qdrant HNSW similarity search. Results are mapped from Qdrant
 * {@link ScoredPoint} payload fields to {@link ChunkSearchResult} records.
 *
 * <p>Optional filter parameters ({@code module}, {@code stereotype}, {@code chunkType}) are
 * applied server-side via Qdrant payload filters to avoid post-processing overhead.
 *
 * <p>This service is the backend for {@code POST /api/vector/search} and forms the retrieval
 * foundation for the Phase 11 RAG pipeline.
 */
@Service
public class VectorSearchService {

  private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

  private final EmbeddingModel embeddingModel;
  private final QdrantClient qdrantClient;
  private final VectorConfig vectorConfig;

  public VectorSearchService(
      EmbeddingModel embeddingModel,
      QdrantClient qdrantClient,
      VectorConfig vectorConfig) {
    this.embeddingModel = embeddingModel;
    this.qdrantClient = qdrantClient;
    this.vectorConfig = vectorConfig;
  }

  /**
   * Embeds the query text and searches Qdrant for the most similar code chunks.
   *
   * @param request the search request with query text and optional filters
   * @return ranked search results with enriched payload fields
   * @throws IllegalArgumentException if the query is null or blank
   */
  public SearchResponse search(SearchRequest request) {
    if (request == null || request.query() == null || request.query().isBlank()) {
      throw new IllegalArgumentException("Search query must not be null or blank");
    }

    log.debug("Searching for query='{}' limit={} module={} stereotype={} chunkType={}",
        request.query(), request.limit(), request.module(), request.stereotype(), request.chunkType());

    // Step 1: Embed query text to float vector
    float[] queryVector = embeddingModel.embed(request.query());

    // Step 2: Build SearchPoints with optional filters
    SearchPoints.Builder builder = SearchPoints.newBuilder()
        .setCollectionName(vectorConfig.getCollectionName())
        .setLimit(request.limit() != null ? request.limit() : 10)
        .setWithPayload(enable(true));

    // Add query vector
    for (float v : queryVector) {
      builder.addVector(v);
    }

    // Apply optional payload filters
    boolean hasFilters = request.module() != null
        || request.stereotype() != null
        || request.chunkType() != null;

    if (hasFilters) {
      Filter.Builder filterBuilder = Filter.newBuilder();
      if (request.module() != null) {
        filterBuilder.addMust(matchKeyword("module", request.module()));
      }
      if (request.stereotype() != null) {
        filterBuilder.addMust(matchKeyword("stereotype", request.stereotype()));
      }
      if (request.chunkType() != null) {
        filterBuilder.addMust(matchKeyword("chunkType", request.chunkType()));
      }
      builder.setFilter(filterBuilder.build());
    }

    // Step 3: Execute similarity search
    List<ScoredPoint> scoredPoints;
    try {
      scoredPoints = qdrantClient.searchAsync(builder.build()).get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.error("Qdrant search failed for query='{}': {}", request.query(), e.getMessage(), e);
      throw new RuntimeException("Vector search failed: " + e.getMessage(), e);
    }

    // Step 4: Map ScoredPoint payloads to ChunkSearchResult records
    List<ChunkSearchResult> results = new ArrayList<>(scoredPoints.size());
    for (ScoredPoint point : scoredPoints) {
      results.add(mapToResult(point));
    }

    log.debug("Search returned {} results for query='{}'", results.size(), request.query());
    return new SearchResponse(results, results.size(), request.query());
  }

  /**
   * Maps a Qdrant {@link ScoredPoint} to a {@link ChunkSearchResult}.
   *
   * <p>All payload fields are extracted from the point's payload map. Missing fields are handled
   * gracefully with type-appropriate defaults (empty string, 0.0, false).
   */
  private ChunkSearchResult mapToResult(ScoredPoint point) {
    Map<String, Value> payload = point.getPayloadMap();

    return new ChunkSearchResult(
        point.getScore(),
        getString(payload, "classFqn"),
        getString(payload, "chunkType"),
        getString(payload, "methodId"),
        getString(payload, "module"),
        getString(payload, "stereotype"),
        getDouble(payload, "structuralRiskScore"),
        getDouble(payload, "enhancedRiskScore"),
        getBool(payload, "vaadin7Detected"),
        getString(payload, "callers"),
        getString(payload, "callees"),
        getString(payload, "dependencies"),
        getString(payload, "domainTerms"));
  }

  // ---------------------------------------------------------------------------
  // Payload extraction helpers
  // ---------------------------------------------------------------------------

  private static String getString(Map<String, Value> payload, String key) {
    Value v = payload.get(key);
    if (v == null) return "";
    return v.getStringValue();
  }

  private static double getDouble(Map<String, Value> payload, String key) {
    Value v = payload.get(key);
    if (v == null) return 0.0;
    return v.getDoubleValue();
  }

  private static boolean getBool(Map<String, Value> payload, String key) {
    Value v = payload.get(key);
    if (v == null) return false;
    return v.getBoolValue();
  }
}
