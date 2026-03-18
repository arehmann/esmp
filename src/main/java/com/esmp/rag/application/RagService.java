package com.esmp.rag.application;

import com.esmp.graph.api.SearchResponse;
import com.esmp.graph.api.SearchResponse.SearchEntry;
import com.esmp.graph.application.GraphQueryService;
import com.esmp.rag.api.ContextChunk;
import com.esmp.rag.api.ConeSummary;
import com.esmp.rag.api.DisambiguationResponse;
import com.esmp.rag.api.DisambiguationResponse.DisambiguationCandidate;
import com.esmp.rag.api.FocalClassDetail;
import com.esmp.rag.api.RagRequest;
import com.esmp.rag.api.RagResponse;
import com.esmp.rag.api.ScoreBreakdown;
import com.esmp.rag.config.RagWeightConfig;
import com.esmp.vector.api.ChunkSearchResult;
import com.esmp.vector.api.SearchRequest;
import com.esmp.vector.application.VectorSearchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the full RAG (Retrieval-Augmented Generation) pipeline for migration context
 * assembly.
 *
 * <p>Pipeline steps:
 * <ol>
 *   <li>Query resolution — FQN path, simple-name path (with disambiguation), or natural-language
 *       fallback.
 *   <li>Hop-distance cone expansion — traverses all 7 structural relationship types up to 10 hops
 *       via Neo4j, building a {@code Map<String,Integer>} from FQN to minimum hop distance.
 *   <li>Parallel execution — cone traversal and focal-class text embedding run concurrently via
 *       {@link CompletableFuture}.
 *   <li>Cone-constrained Qdrant search — vector search is filtered to only FQNs inside the cone
 *       using the pre-built payload index.
 *   <li>Merge and re-rank — every chunk is scored with a weighted combination of vector similarity,
 *       graph proximity (1 / hop), and enhanced risk score.
 *   <li>Response assembly — {@link RagResponse} wraps focal class detail, ranked context chunks,
 *       and an aggregate {@link ConeSummary}.
 * </ol>
 *
 * <p>This service does NOT write to Neo4j or JPA — it is a pure read orchestrator and must NOT be
 * annotated with {@code @Transactional}.
 */
@Service
public class RagService {

  private static final Logger log = LoggerFactory.getLogger(RagService.class);

  private static final List<String> STEREOTYPE_LABELS =
      List.of("Service", "Repository", "VaadinView", "VaadinDataBinding", "VaadinComponent",
          "Entity", "Enum");

  private final Neo4jClient neo4jClient;
  private final VectorSearchService vectorSearchService;
  private final EmbeddingModel embeddingModel;
  private final GraphQueryService graphQueryService;
  private final RagWeightConfig ragWeightConfig;
  private final ObjectMapper objectMapper;

  public RagService(
      Neo4jClient neo4jClient,
      VectorSearchService vectorSearchService,
      EmbeddingModel embeddingModel,
      GraphQueryService graphQueryService,
      RagWeightConfig ragWeightConfig) {
    this.neo4jClient = neo4jClient;
    this.vectorSearchService = vectorSearchService;
    this.embeddingModel = embeddingModel;
    this.graphQueryService = graphQueryService;
    this.ragWeightConfig = ragWeightConfig;
    this.objectMapper = new ObjectMapper();
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Assembles a RAG context package for the given request.
   *
   * @param request the RAG request (must have either {@code fqn} or {@code query})
   * @return full RAG response or a disambiguation response
   */
  public RagResponse assemble(RagRequest request) {
    long startMs = System.currentTimeMillis();

    // -----------------------------------------------------------------------
    // Step 1: Query Resolution
    // -----------------------------------------------------------------------

    // Path A: explicit FQN provided
    if (request.fqn() != null && !request.fqn().isBlank()) {
      return assembleFqn(request.fqn(), "FQN", request, startMs);
    }

    // Path B: attempt simple name resolution
    if (request.query() != null && !request.query().isBlank()) {
      SearchResponse searchResponse = graphQueryService.searchByName(request.query());
      List<SearchEntry> exactMatches = searchResponse.results().stream()
          .filter(e -> e.simpleName().equalsIgnoreCase(request.query())
              || e.fullyQualifiedName().equals(request.query()))
          .collect(Collectors.toList());

      if (exactMatches.size() == 1) {
        // Single unambiguous match
        return assembleFqn(exactMatches.get(0).fullyQualifiedName(), "SIMPLE_NAME", request, startMs);
      }

      if (exactMatches.size() > 1) {
        // Ambiguous — return disambiguation response
        List<DisambiguationCandidate> candidates = exactMatches.stream()
            .map(e -> new DisambiguationCandidate(
                e.fullyQualifiedName(), e.simpleName(), e.packageName(), e.labels()))
            .collect(Collectors.toList());
        long durationMs = System.currentTimeMillis() - startMs;
        return new RagResponse(
            "SIMPLE_NAME",
            null,
            null,
            null,
            new DisambiguationResponse(request.query(), candidates),
            durationMs);
      }

      // Path C: natural language fallback — no exact class name match
      return assembleNaturalLanguage(request, startMs);
    }

    // No usable query or FQN
    long durationMs = System.currentTimeMillis() - startMs;
    return new RagResponse("UNKNOWN", null, List.of(), buildEmptyConeSummary(), null, durationMs);
  }

  // ---------------------------------------------------------------------------
  // Assembly paths
  // ---------------------------------------------------------------------------

  private RagResponse assembleFqn(String resolvedFqn, String queryType, RagRequest request,
      long startMs) {
    // Fetch focal class metadata
    Map<String, Object> focalMetadata = findFocalClassMetadata(resolvedFqn);
    if (focalMetadata == null) {
      // Class not in graph — return null focalClass so controller returns 404
      long durationMs = System.currentTimeMillis() - startMs;
      return new RagResponse(queryType, null, List.of(), buildEmptyConeSummary(), null, durationMs);
    }

    // Run cone traversal and embedding in parallel
    CompletableFuture<Map<String, Integer>> coneFuture =
        CompletableFuture.supplyAsync(() -> findDependencyConeWithHops(resolvedFqn));

    String embeddingText = readFocalClassText(focalMetadata);
    CompletableFuture<float[]> embeddingFuture =
        CompletableFuture.supplyAsync(() -> embeddingModel.embed(embeddingText));

    Map<String, Integer> coneWithHops = coneFuture.join();
    float[] queryVector = embeddingFuture.join();

    return buildResponse(resolvedFqn, queryType, request, focalMetadata, coneWithHops, queryVector,
        startMs);
  }

  private RagResponse assembleNaturalLanguage(RagRequest request, long startMs) {
    // Embed the natural language query
    String query = request.query();
    float[] queryVector = embeddingModel.embed(query);

    // Get top 3 hits from vector search (no cone constraint)
    List<ChunkSearchResult> topHits = vectorSearchService.search(
        new SearchRequest(query, 3, request.module(), request.stereotype(), null)).results();

    if (topHits.isEmpty()) {
      long durationMs = System.currentTimeMillis() - startMs;
      return new RagResponse("NATURAL_LANGUAGE", null, List.of(), buildEmptyConeSummary(), null,
          durationMs);
    }

    // Get top 3 distinct class FQNs from hits
    List<String> topFqns = topHits.stream()
        .map(ChunkSearchResult::classFqn)
        .filter(f -> f != null && !f.isBlank())
        .distinct()
        .limit(3)
        .collect(Collectors.toList());

    // Merge cones from all top-3 FQNs — take minimum hop distance for overlapping nodes
    Map<String, Integer> mergedCone = new HashMap<>();
    String primaryFqn = null;
    Map<String, Object> primaryMetadata = null;

    for (String fqn : topFqns) {
      Map<String, Object> meta = findFocalClassMetadata(fqn);
      if (meta != null && primaryFqn == null) {
        primaryFqn = fqn;
        primaryMetadata = meta;
      }
      Map<String, Integer> cone = findDependencyConeWithHops(fqn);
      for (Map.Entry<String, Integer> entry : cone.entrySet()) {
        mergedCone.merge(entry.getKey(), entry.getValue(), Math::min);
      }
    }

    if (primaryFqn == null) {
      long durationMs = System.currentTimeMillis() - startMs;
      return new RagResponse("NATURAL_LANGUAGE", null, List.of(), buildEmptyConeSummary(), null,
          durationMs);
    }

    return buildResponse(primaryFqn, "NATURAL_LANGUAGE", request, primaryMetadata, mergedCone,
        queryVector, startMs);
  }

  private RagResponse buildResponse(
      String resolvedFqn,
      String queryType,
      RagRequest request,
      Map<String, Object> focalMetadata,
      Map<String, Integer> coneWithHops,
      float[] queryVector,
      long startMs) {

    // -----------------------------------------------------------------------
    // Step 4: Cone-constrained Qdrant search
    // -----------------------------------------------------------------------
    int limit = request.limit() != null ? Math.min(request.limit(), 100) : 20;
    List<String> coneFqns = new ArrayList<>(coneWithHops.keySet());
    List<ChunkSearchResult> vectorResults;
    if (coneFqns.isEmpty()) {
      vectorResults = List.of();
    } else {
      vectorResults = vectorSearchService.searchByCone(queryVector, coneFqns, limit * 3);
    }

    // -----------------------------------------------------------------------
    // Step 5: Merge and re-rank
    // -----------------------------------------------------------------------
    List<ContextChunk> allChunks = new ArrayList<>();
    for (ChunkSearchResult result : vectorResults) {
      Integer hopDist = coneWithHops.getOrDefault(result.classFqn(), Integer.MAX_VALUE);
      double graphProximity = 1.0 / Math.max(hopDist, 1);
      double finalScore = ragWeightConfig.getVectorSimilarity() * result.score()
          + ragWeightConfig.getGraphProximity() * graphProximity
          + ragWeightConfig.getRiskScore() * result.enhancedRiskScore();

      ScoreBreakdown scores = new ScoreBreakdown(
          result.score(), graphProximity, result.enhancedRiskScore(), finalScore);

      String relationshipPath = buildRelationshipPath(hopDist, result.classFqn(), resolvedFqn);

      allChunks.add(new ContextChunk(
          result.classFqn(),
          result.chunkType(),
          result.methodId(),
          result.stereotype(),
          result.classFqn(), // codeText: use classFqn as placeholder; enriched with actual text below
          relationshipPath,
          scores,
          result.structuralRiskScore(),
          result.enhancedRiskScore(),
          result.vaadin7Detected(),
          result.callers(),
          result.callees(),
          result.dependencies(),
          result.domainTerms()));
    }

    // Sort by finalScore descending, take top limit
    allChunks.sort(Comparator.comparingDouble(c -> -c.scores().finalScore()));
    List<ContextChunk> contextChunks = allChunks.stream().limit(limit).collect(Collectors.toList());

    // -----------------------------------------------------------------------
    // Step 6: Build ConeSummary
    // -----------------------------------------------------------------------
    ConeSummary coneSummary = buildConeSummary(coneWithHops.size(), vectorResults);

    // -----------------------------------------------------------------------
    // Step 7: Build FocalClassDetail
    // -----------------------------------------------------------------------
    FocalClassDetail focalClassDetail = buildFocalClassDetail(resolvedFqn, focalMetadata, request,
        contextChunks);

    // -----------------------------------------------------------------------
    // Step 8: Return RagResponse
    // -----------------------------------------------------------------------
    long durationMs = System.currentTimeMillis() - startMs;
    return new RagResponse(queryType, focalClassDetail, contextChunks, coneSummary, null,
        durationMs);
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Traverses all 7 structural relationship types up to 10 hops from the focal class, returning a
   * map from reachable FQN to minimum hop distance. The focal class itself is always included at
   * hop 0.
   */
  private Map<String, Integer> findDependencyConeWithHops(String fqn) {
    String cypher = """
        MATCH (focal:JavaClass {fullyQualifiedName: $fqn})
        OPTIONAL MATCH path = (focal)-[:DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS|BINDS_TO|QUERIES|MAPS_TO_TABLE*1..10]->(reachable:JavaClass)
        WITH focal, reachable, min(length(path)) AS hopDist
        WHERE reachable IS NOT NULL
        RETURN reachable.fullyQualifiedName AS fqn, hopDist
        """;

    Collection<Map<String, Object>> rows = neo4jClient
        .query(cypher)
        .bind(fqn).to("fqn")
        .fetch()
        .all();

    Map<String, Integer> result = new HashMap<>();
    // Focal class itself at hop 0
    result.put(fqn, 0);

    for (Map<String, Object> row : rows) {
      String reachableFqn = (String) row.get("fqn");
      Object hopObj = row.get("hopDist");
      if (reachableFqn != null && hopObj != null) {
        int hopDist = ((Long) hopObj).intValue();
        result.put(reachableFqn, hopDist);
      }
    }

    return result;
  }

  /**
   * Fetches focal class metadata from Neo4j.
   *
   * @return map with simpleName, packageName, sourceFilePath, enhancedRiskScore,
   *         structuralRiskScore, vaadin7Detected, labels — or null if class not found
   */
  private Map<String, Object> findFocalClassMetadata(String fqn) {
    String cypher = """
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        RETURN c.simpleName AS simpleName,
               c.packageName AS packageName,
               c.sourceFilePath AS sourceFilePath,
               c.enhancedRiskScore AS enhancedRiskScore,
               c.structuralRiskScore AS structuralRiskScore,
               c.vaadin7Detected AS vaadin7Detected,
               [label IN labels(c) WHERE label <> 'JavaClass'] AS labels
        """;

    Collection<Map<String, Object>> rows = neo4jClient
        .query(cypher)
        .bind(fqn).to("fqn")
        .fetch()
        .all();

    if (rows.isEmpty()) {
      return null;
    }
    return rows.iterator().next();
  }

  /**
   * Reads source file text for the focal class. Falls back to a synthetic text string if the file
   * is not accessible.
   */
  private String readFocalClassText(Map<String, Object> focalMetadata) {
    String sourceFilePath = (String) focalMetadata.get("sourceFilePath");
    if (sourceFilePath != null && !sourceFilePath.isBlank()) {
      String text = readSourceFile(sourceFilePath);
      if (text != null) {
        return text;
      }
    }
    // Fallback: construct from metadata fields
    String simpleName = (String) focalMetadata.getOrDefault("simpleName", "");
    String packageName = (String) focalMetadata.getOrDefault("packageName", "");
    String stereotype = extractStereotype(focalMetadata);
    return simpleName + " " + stereotype + " " + packageName;
  }

  /**
   * Reads a Java source file from disk.
   *
   * @return file contents as a string, or null if file not accessible
   */
  private String readSourceFile(String sourceFilePath) {
    if (sourceFilePath == null || sourceFilePath.isBlank()) {
      return null;
    }
    try {
      return Files.readString(Path.of(sourceFilePath));
    } catch (IOException e) {
      log.debug("Could not read source file '{}': {}", sourceFilePath, e.getMessage());
      return null;
    }
  }

  /**
   * Extracts the primary stereotype from the labels list in the metadata map.
   */
  private String extractStereotype(Map<String, Object> metadata) {
    Object labelsObj = metadata.get("labels");
    if (labelsObj instanceof List<?> labelList) {
      for (String candidate : STEREOTYPE_LABELS) {
        for (Object label : labelList) {
          if (candidate.equals(label)) {
            return candidate;
          }
        }
      }
    }
    return "";
  }

  /**
   * Builds a human-readable relationship path string based on hop distance.
   */
  private String buildRelationshipPath(int hopDist, String classFqn, String focalFqn) {
    if (classFqn.equals(focalFqn) || hopDist == 0) {
      return "focal class";
    } else if (hopDist == 1) {
      return "direct dependency (1 hop)";
    } else if (hopDist < Integer.MAX_VALUE) {
      return "multi-hop (" + hopDist + " hops)";
    } else {
      return "cone member";
    }
  }

  /**
   * Builds the aggregate cone summary from all vector results.
   */
  private ConeSummary buildConeSummary(int totalNodes, List<ChunkSearchResult> vectorResults) {
    Set<String> vaadin7Fqns = new HashSet<>();
    double riskSum = 0.0;
    int riskCount = 0;
    Map<String, Integer> termFrequency = new LinkedHashMap<>();
    Set<String> uniqueTermIds = new HashSet<>();

    for (ChunkSearchResult result : vectorResults) {
      if (result.vaadin7Detected()) {
        vaadin7Fqns.add(result.classFqn());
      }
      riskSum += result.enhancedRiskScore();
      riskCount++;

      // Parse domain terms JSON
      String domainTermsJson = result.domainTerms();
      if (domainTermsJson != null && !domainTermsJson.isBlank()
          && domainTermsJson.startsWith("[")) {
        try {
          List<Map<String, String>> terms = objectMapper.readValue(domainTermsJson,
              new TypeReference<>() {});
          for (Map<String, String> term : terms) {
            String termId = term.get("termId");
            String displayName = term.get("displayName");
            if (termId != null && displayName != null) {
              uniqueTermIds.add(termId);
              termFrequency.merge(displayName, 1, Integer::sum);
            }
          }
        } catch (Exception e) {
          log.debug("Could not parse domain terms JSON: {}", domainTermsJson);
        }
      }
    }

    double avgEnhancedRisk = riskCount > 0 ? riskSum / riskCount : 0.0;

    // Top 10 domain terms by frequency
    List<String> topDomainTerms = termFrequency.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(10)
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());

    return new ConeSummary(
        totalNodes,
        vaadin7Fqns.size(),
        avgEnhancedRisk,
        topDomainTerms,
        uniqueTermIds.size());
  }

  /**
   * Builds an empty cone summary for error/null paths.
   */
  private ConeSummary buildEmptyConeSummary() {
    return new ConeSummary(0, 0, 0.0, List.of(), 0);
  }

  /**
   * Builds the FocalClassDetail record from metadata and optionally from the vector result set.
   */
  private FocalClassDetail buildFocalClassDetail(
      String resolvedFqn,
      Map<String, Object> focalMetadata,
      RagRequest request,
      List<ContextChunk> contextChunks) {

    String simpleName = (String) focalMetadata.getOrDefault("simpleName", resolvedFqn);
    String packageName = (String) focalMetadata.getOrDefault("packageName", "");
    String stereotype = extractStereotype(focalMetadata);
    Object srsObj = focalMetadata.get("structuralRiskScore");
    Object ersObj = focalMetadata.get("enhancedRiskScore");
    double structuralRiskScore = srsObj instanceof Number n ? n.doubleValue() : 0.0;
    double enhancedRiskScore = ersObj instanceof Number n ? n.doubleValue() : 0.0;
    Object vaadinObj = focalMetadata.get("vaadin7Detected");
    boolean vaadin7Detected = vaadinObj instanceof Boolean b && b;

    // Collect domain terms from any CLASS_HEADER chunk that belongs to the focal class
    List<String> domainTerms = new ArrayList<>();
    String codeText = null;

    for (ContextChunk chunk : contextChunks) {
      if (resolvedFqn.equals(chunk.classFqn()) && "CLASS_HEADER".equals(chunk.chunkType())) {
        codeText = chunk.codeText();
        // Parse domain terms from chunk
        String dtJson = chunk.domainTerms();
        if (dtJson != null && !dtJson.isBlank() && dtJson.startsWith("[")) {
          try {
            List<Map<String, String>> terms = objectMapper.readValue(dtJson,
                new TypeReference<>() {});
            for (Map<String, String> term : terms) {
              String displayName = term.get("displayName");
              if (displayName != null) {
                domainTerms.add(displayName);
              }
            }
          } catch (Exception e) {
            log.debug("Could not parse domain terms for focal class: {}", dtJson);
          }
        }
        break;
      }
    }

    // If includeFullSource requested, read from disk
    if (request.includeFullSource()) {
      String sourceFilePath = (String) focalMetadata.get("sourceFilePath");
      String sourceText = readSourceFile(sourceFilePath);
      if (sourceText != null) {
        codeText = sourceText;
      }
    }

    // If no codeText found from chunks or disk, use a summary string
    if (codeText == null || codeText.isBlank()) {
      codeText = "[CLASS_HEADER] " + simpleName + " (" + stereotype + ") - " + packageName;
    }

    return new FocalClassDetail(
        resolvedFqn,
        simpleName,
        packageName,
        stereotype,
        structuralRiskScore,
        enhancedRiskScore,
        vaadin7Detected,
        domainTerms,
        codeText);
  }
}
