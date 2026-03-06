package com.esmp.vector.application;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.include;

import com.esmp.vector.api.IndexStatusResponse;
import com.esmp.vector.config.VectorConfig;
import com.esmp.vector.model.CodeChunk;
import com.esmp.vector.model.DomainTermRef;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScrollPoints;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Embeds {@link CodeChunk} records via Spring AI {@code TransformersEmbeddingModel} and upserts
 * them to the Qdrant {@code code_chunks} collection.
 *
 * <p>Two indexing strategies are provided:
 * <ol>
 *   <li>{@link #indexAll(String)} — full index: embeds every chunk produced by
 *       {@link ChunkingService} and upserts them. Idempotent: subsequent runs update existing
 *       points (same deterministic UUID v5 point IDs).
 *   <li>{@link #reindex(String)} — incremental reindex: scrolls Qdrant to retrieve stored
 *       {@code contentHash} values, compares against current file hashes from Neo4j, and only
 *       re-embeds classes whose hash changed.
 * </ol>
 *
 * <p>Batching uses {@code esmp.vector.batch-size} (default 64) to control memory usage and
 * Qdrant request size. Individual batch failures are caught and logged; processing continues for
 * remaining batches.
 */
@Service
public class VectorIndexingService {

  private static final Logger log = LoggerFactory.getLogger(VectorIndexingService.class);

  private final EmbeddingModel embeddingModel;
  private final QdrantClient qdrantClient;
  private final ChunkingService chunkingService;
  private final VectorConfig vectorConfig;

  public VectorIndexingService(
      EmbeddingModel embeddingModel,
      QdrantClient qdrantClient,
      ChunkingService chunkingService,
      VectorConfig vectorConfig) {
    this.embeddingModel = embeddingModel;
    this.qdrantClient = qdrantClient;
    this.chunkingService = chunkingService;
    this.vectorConfig = vectorConfig;
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Full index: chunks all classes from the given source root, embeds every chunk, and upserts
   * to Qdrant. Idempotent — subsequent calls update existing points (same UUID v5 point IDs).
   *
   * @param sourceRoot base path prepended to relative source file paths (may be empty)
   * @return summary with counts and wall-clock duration
   */
  public IndexStatusResponse indexAll(String sourceRoot) {
    long start = System.currentTimeMillis();
    log.info("Starting full vector index (sourceRoot='{}')", sourceRoot);

    List<CodeChunk> chunks = chunkingService.chunkClasses(sourceRoot);
    log.info("Chunking produced {} chunks to embed and index.", chunks.size());

    int chunksIndexed = 0;
    int chunksSkipped = 0;

    List<List<CodeChunk>> batches = partition(chunks, vectorConfig.getBatchSize());
    for (List<CodeChunk> batch : batches) {
      try {
        int indexed = embedAndUpsert(batch);
        chunksIndexed += indexed;
      } catch (Exception e) {
        log.error("Batch upsert failed for {} chunks: {}", batch.size(), e.getMessage(), e);
        chunksSkipped += batch.size();
      }
    }

    // Count distinct classes as filesProcessed
    long filesProcessed = chunks.stream().map(CodeChunk::classFqn).distinct().count();
    long duration = System.currentTimeMillis() - start;

    log.info(
        "Full index complete: {} files, {} chunks indexed, {} skipped, {}ms",
        filesProcessed, chunksIndexed, chunksSkipped, duration);
    return new IndexStatusResponse((int) filesProcessed, chunksIndexed, chunksSkipped, duration);
  }

  /**
   * Incremental reindex: scrolls Qdrant for stored content hashes, compares against current
   * hashes from Neo4j, and re-embeds only classes whose source file changed.
   *
   * @param sourceRoot base path prepended to relative source file paths (may be empty)
   * @return summary with counts (filesProcessed = changed classes, chunksSkipped = unchanged)
   */
  public IndexStatusResponse reindex(String sourceRoot) {
    long start = System.currentTimeMillis();
    log.info("Starting incremental reindex (sourceRoot='{}')", sourceRoot);

    // Step 1: Retrieve stored hashes from Qdrant
    Map<String, String> storedHashes = scrollStoredHashes();
    log.info("Retrieved {} stored class hashes from Qdrant.", storedHashes.size());

    // Step 2: Get current chunks from Neo4j + source files
    List<CodeChunk> allChunks = chunkingService.chunkClasses(sourceRoot);

    // Step 3: Group by classFqn and filter to changed classes
    Map<String, List<CodeChunk>> byClass = allChunks.stream()
        .collect(Collectors.groupingBy(CodeChunk::classFqn));

    List<CodeChunk> changedChunks = new ArrayList<>();
    int unchangedChunkCount = 0;

    for (Map.Entry<String, List<CodeChunk>> entry : byClass.entrySet()) {
      String fqn = entry.getKey();
      List<CodeChunk> classChunks = entry.getValue();
      String currentHash = classChunks.get(0).contentHash(); // all chunks share the same hash
      String storedHash = storedHashes.get(fqn);

      if (currentHash != null && currentHash.equals(storedHash)) {
        // Unchanged — skip
        unchangedChunkCount += classChunks.size();
        log.debug("Skipping '{}' — hash unchanged ({}).", fqn, currentHash);
      } else {
        changedChunks.addAll(classChunks);
        log.debug("Queuing '{}' for re-embedding — hash changed ({} -> {}).", fqn, storedHash, currentHash);
      }
    }

    int changedClasses = (int) changedChunks.stream().map(CodeChunk::classFqn).distinct().count();
    log.info(
        "{} classes changed, {} class chunks unchanged. Re-embedding {} chunks.",
        changedClasses, unchangedChunkCount, changedChunks.size());

    // Step 4: Embed and upsert only changed chunks
    int chunksIndexed = 0;
    int chunksSkipped = unchangedChunkCount;

    List<List<CodeChunk>> batches = partition(changedChunks, vectorConfig.getBatchSize());
    for (List<CodeChunk> batch : batches) {
      try {
        int indexed = embedAndUpsert(batch);
        chunksIndexed += indexed;
      } catch (Exception e) {
        log.error("Batch upsert failed for {} chunks: {}", batch.size(), e.getMessage(), e);
        chunksSkipped += batch.size();
      }
    }

    long duration = System.currentTimeMillis() - start;
    log.info(
        "Incremental reindex complete: {} files changed, {} chunks indexed, {} skipped, {}ms",
        changedClasses, chunksIndexed, chunksSkipped, duration);
    return new IndexStatusResponse(changedClasses, chunksIndexed, chunksSkipped, duration);
  }

  /**
   * Deletes all Qdrant points for the given class FQN. Intended for use when a class is removed
   * from the codebase and its vectors should be cleaned up.
   *
   * @param classFqn fully-qualified class name whose points should be deleted
   */
  public void deleteByClass(String classFqn) {
    log.info("Deleting Qdrant points for class '{}'", classFqn);
    try {
      Filter filter = Filter.newBuilder()
          .addMust(matchKeyword("classFqn", classFqn))
          .build();
      qdrantClient.deleteAsync(vectorConfig.getCollectionName(), filter)
          .get(30, TimeUnit.SECONDS);
      log.info("Deleted points for class '{}'", classFqn);
    } catch (Exception e) {
      log.error("Failed to delete points for class '{}': {}", classFqn, e.getMessage(), e);
    }
  }

  // ---------------------------------------------------------------------------
  // Internal implementation
  // ---------------------------------------------------------------------------

  /**
   * Embeds a batch of chunks and upserts the resulting points to Qdrant.
   *
   * @return the number of points upserted
   */
  private int embedAndUpsert(List<CodeChunk> batch) throws Exception {
    List<String> texts = batch.stream().map(CodeChunk::text).toList();
    List<float[]> embeddings = embeddingModel.embed(texts);

    List<PointStruct> points = new ArrayList<>(batch.size());
    for (int i = 0; i < batch.size(); i++) {
      CodeChunk chunk = batch.get(i);
      float[] embedding = embeddings.get(i);
      points.add(buildPointStruct(chunk, embedding));
    }

    qdrantClient.upsertAsync(vectorConfig.getCollectionName(), points)
        .get(30, TimeUnit.SECONDS);
    return points.size();
  }

  /**
   * Builds a Qdrant {@link PointStruct} from a chunk and its embedding.
   */
  private PointStruct buildPointStruct(CodeChunk chunk, float[] embedding) {
    return PointStruct.newBuilder()
        .setId(id(chunk.pointId()))
        .setVectors(vectors(embedding))
        // String payload fields
        .putPayload("classFqn", value(chunk.classFqn()))
        .putPayload("chunkType", value(chunk.chunkType().name()))
        .putPayload("methodId", value(chunk.methodId() != null ? chunk.methodId() : ""))
        .putPayload("classHeaderId", value(chunk.classHeaderId() != null ? chunk.classHeaderId() : ""))
        .putPayload("module", value(chunk.module() != null ? chunk.module() : ""))
        .putPayload("stereotype", value(chunk.stereotype() != null ? chunk.stereotype() : ""))
        .putPayload("contentHash", value(chunk.contentHash() != null ? chunk.contentHash() : ""))
        // Risk score payload fields (double)
        .putPayload("structuralRiskScore", value(chunk.structuralRiskScore()))
        .putPayload("enhancedRiskScore", value(chunk.enhancedRiskScore()))
        .putPayload("domainCriticality", value(chunk.domainCriticality()))
        .putPayload("securitySensitivity", value(chunk.securitySensitivity()))
        .putPayload("financialInvolvement", value(chunk.financialInvolvement()))
        .putPayload("businessRuleDensity", value(chunk.businessRuleDensity()))
        // Boolean migration state
        .putPayload("vaadin7Detected", value(chunk.vaadin7Detected()))
        // Comma-joined graph neighbour lists
        .putPayload("callers", value(joinList(chunk.callers())))
        .putPayload("callees", value(joinList(chunk.callees())))
        .putPayload("dependencies", value(joinList(chunk.dependencies())))
        .putPayload("implementors", value(joinList(chunk.implementors())))
        .putPayload("vaadinPatterns", value(joinList(chunk.vaadinPatterns())))
        // JSON-serialised domain terms list
        .putPayload("domainTerms", value(serializeTerms(chunk.domainTerms())))
        .build();
  }

  /**
   * Scrolls all points in Qdrant to build a map of {@code classFqn -> contentHash}.
   */
  private Map<String, String> scrollStoredHashes() {
    Map<String, String> hashes = new HashMap<>();
    boolean hasMore = true;
    io.qdrant.client.grpc.Points.PointId nextOffset = null;

    while (hasMore) {
      try {
        ScrollPoints.Builder builder = ScrollPoints.newBuilder()
            .setCollectionName(vectorConfig.getCollectionName())
            .setLimit(500)
            .setWithPayload(include(List.of("classFqn", "contentHash")));
        if (nextOffset != null) {
          builder.setOffset(nextOffset);
        }

        var result = qdrantClient.scrollAsync(builder.build()).get(30, TimeUnit.SECONDS);

        for (var point : result.getResultList()) {
          var payload = point.getPayloadMap();
          if (payload.containsKey("classFqn") && payload.containsKey("contentHash")) {
            String fqn = payload.get("classFqn").getStringValue();
            String hash = payload.get("contentHash").getStringValue();
            if (fqn != null && !fqn.isBlank()) {
              // Keep only the first hash seen per class (all chunks of a class share the same hash)
              hashes.putIfAbsent(fqn, hash);
            }
          }
        }

        if (result.hasNextPageOffset()) {
          nextOffset = result.getNextPageOffset();
        } else {
          hasMore = false;
        }
      } catch (Exception e) {
        log.error("Error scrolling Qdrant for stored hashes: {}", e.getMessage(), e);
        hasMore = false;
      }
    }

    return hashes;
  }

  // ---------------------------------------------------------------------------
  // Utility helpers
  // ---------------------------------------------------------------------------

  /** Joins a list into a comma-separated string; null-safe. */
  private static String joinList(List<String> list) {
    if (list == null || list.isEmpty()) return "";
    return String.join(",", list);
  }

  /**
   * Serializes a list of domain term references as a compact JSON array string.
   * Format: {@code [{"termId":"...","displayName":"..."},...]}.
   */
  static String serializeTerms(List<DomainTermRef> terms) {
    if (terms == null || terms.isEmpty()) return "[]";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < terms.size(); i++) {
      DomainTermRef t = terms.get(i);
      sb.append("{\"termId\":\"")
          .append(escape(t.termId()))
          .append("\",\"displayName\":\"")
          .append(escape(t.displayName()))
          .append("\"}");
      if (i < terms.size() - 1) sb.append(",");
    }
    sb.append("]");
    return sb.toString();
  }

  /** Minimal JSON string escaping (backslash and double-quote). */
  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** Splits a list into sublists of the given size. */
  private static <T> List<List<T>> partition(List<T> list, int size) {
    List<List<T>> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
      result.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return result;
  }
}
