package com.esmp.mcp.application;

import com.esmp.graph.api.BusinessTermResponse;
import com.esmp.graph.api.DependencyConeResponse;
import com.esmp.graph.api.RiskDetailResponse;
import com.esmp.graph.application.GraphQueryService;
import com.esmp.graph.application.LexiconService;
import com.esmp.graph.application.RiskService;
import com.esmp.mcp.api.AssemblerWarning;
import com.esmp.mcp.api.MigrationContext;
import com.esmp.mcp.config.McpConfig;
import com.esmp.rag.api.ContextChunk;
import com.esmp.rag.api.RagRequest;
import com.esmp.rag.api.RagResponse;
import com.esmp.rag.application.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the assembly of a unified {@link MigrationContext} for a focal Java class.
 *
 * <p>This service is a pure read orchestrator — it must NOT be annotated with
 * {@code @Transactional} to avoid binding a Neo4j or JPA session across the long-running parallel
 * futures.
 *
 * <p>Assembly steps (all run in parallel via {@link CompletableFuture}):
 * <ol>
 *   <li>Dependency cone — via {@link GraphQueryService#findDependencyCone(String)}
 *   <li>Risk analysis — via {@link RiskService#getClassDetail(String)}
 *   <li>Domain terms — Neo4jClient Cypher traversing USES_TERM edges
 *   <li>Business rules — Neo4jClient Cypher traversing DEFINES_RULE edges
 *   <li>Code chunks — delegated to {@link RagService#assemble(RagRequest)} which provides
 *       weighted re-ranking (vectorSimilarity + graphProximity + riskScore)
 * </ol>
 *
 * <p>Each parallel call is wrapped in try-catch. On failure an {@link AssemblerWarning} is
 * recorded and the field defaults to null / empty list. The {@code contextCompleteness} field
 * reflects the fraction of services that contributed successfully.
 *
 * <p>Token budget truncation: if the assembled context exceeds 90% of the configured
 * {@code esmp.mcp.context.max-tokens}, code chunks are dropped from the end until the budget is
 * met. If still over budget after removing all code chunks, the dependency cone nodes are
 * truncated to the first 50.
 */
@Service
public class MigrationContextAssembler {

  private static final Logger log = LoggerFactory.getLogger(MigrationContextAssembler.class);

  // Completeness weights — must sum to 1.0
  private static final double WEIGHT_CONE = 0.25;
  private static final double WEIGHT_RISK = 0.20;
  private static final double WEIGHT_TERMS = 0.15;
  private static final double WEIGHT_RULES = 0.10;
  private static final double WEIGHT_CHUNKS = 0.30;

  // Token estimation: chars / 4 (conservative for mixed code/English)
  private static final int CHARS_PER_TOKEN = 4;

  // Safety factor: truncate when over 90% of the budget
  private static final double BUDGET_SAFETY_FACTOR = 0.90;

  // Cone node limit when truncating (after all code chunks removed)
  private static final int MAX_CONE_NODES_WHEN_TRUNCATED = 50;

  private final RagService ragService;
  private final GraphQueryService graphQueryService;
  private final RiskService riskService;
  private final LexiconService lexiconService;
  private final McpConfig mcpConfig;
  private final Neo4jClient neo4jClient;
  private final ObjectMapper objectMapper;

  public MigrationContextAssembler(
      RagService ragService,
      GraphQueryService graphQueryService,
      RiskService riskService,
      LexiconService lexiconService,
      McpConfig mcpConfig,
      Neo4jClient neo4jClient) {
    this.ragService = ragService;
    this.graphQueryService = graphQueryService;
    this.riskService = riskService;
    this.lexiconService = lexiconService;
    this.mcpConfig = mcpConfig;
    this.neo4jClient = neo4jClient;
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Assembles a unified migration context for the given class FQN.
   *
   * <p>Calls 5 knowledge services in parallel and combines their outputs. Partial failures produce
   * warnings and reduced {@code contextCompleteness} rather than exceptions.
   *
   * @param classFqn fully-qualified class name of the focal class
   * @return assembled {@link MigrationContext}, never null
   */
  public MigrationContext assemble(String classFqn) {
    long startMs = System.currentTimeMillis();
    List<AssemblerWarning> warnings = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Launch 4 graph/risk futures in parallel
    // -----------------------------------------------------------------------
    CompletableFuture<Optional<DependencyConeResponse>> coneFuture =
        CompletableFuture.supplyAsync(() -> graphQueryService.findDependencyCone(classFqn));

    CompletableFuture<Optional<RiskDetailResponse>> riskFuture =
        CompletableFuture.supplyAsync(() -> riskService.getClassDetail(classFqn));

    CompletableFuture<List<BusinessTermResponse>> termsFuture =
        CompletableFuture.supplyAsync(() -> queryDomainTerms(classFqn));

    CompletableFuture<List<String>> rulesFuture =
        CompletableFuture.supplyAsync(() -> queryBusinessRules(classFqn));

    // -----------------------------------------------------------------------
    // Delegate code chunk assembly to RagService (preserves weighted re-ranking)
    // -----------------------------------------------------------------------
    CompletableFuture<List<ContextChunk>> chunksFuture =
        CompletableFuture.supplyAsync(
            () -> {
              RagRequest req = new RagRequest(null, classFqn, 20, null, null, false);
              RagResponse resp = ragService.assemble(req);
              List<ContextChunk> chunks = resp.contextChunks();
              return chunks != null ? chunks : List.of();
            });

    // -----------------------------------------------------------------------
    // Collect results with graceful degradation
    // -----------------------------------------------------------------------
    double completeness = 0.0;

    DependencyConeResponse cone = null;
    try {
      Optional<DependencyConeResponse> opt = coneFuture.join();
      if (opt.isPresent()) {
        cone = opt.get();
        completeness += WEIGHT_CONE;
      } else {
        warnings.add(new AssemblerWarning("graph", "Class not found in dependency cone graph"));
      }
    } catch (Exception e) {
      log.warn("Dependency cone service failed for {}: {}", classFqn, e.getMessage());
      warnings.add(new AssemblerWarning("graph", e.getMessage()));
    }

    RiskDetailResponse risk = null;
    try {
      Optional<RiskDetailResponse> opt = riskFuture.join();
      if (opt.isPresent()) {
        risk = opt.get();
        completeness += WEIGHT_RISK;
      } else {
        warnings.add(new AssemblerWarning("risk", "No risk analysis found for class"));
      }
    } catch (Exception e) {
      log.warn("Risk service failed for {}: {}", classFqn, e.getMessage());
      warnings.add(new AssemblerWarning("risk", e.getMessage()));
    }

    List<BusinessTermResponse> terms = List.of();
    try {
      terms = termsFuture.join();
      completeness += WEIGHT_TERMS;
    } catch (Exception e) {
      log.warn("Domain terms query failed for {}: {}", classFqn, e.getMessage());
      warnings.add(new AssemblerWarning("lexicon", e.getMessage()));
    }

    List<String> rules = List.of();
    try {
      rules = rulesFuture.join();
      completeness += WEIGHT_RULES;
    } catch (Exception e) {
      log.warn("Business rules query failed for {}: {}", classFqn, e.getMessage());
      warnings.add(new AssemblerWarning("rules", e.getMessage()));
    }

    List<ContextChunk> codeChunks = List.of();
    try {
      codeChunks = chunksFuture.join();
      if (codeChunks != null && !codeChunks.isEmpty()) {
        completeness += WEIGHT_CHUNKS;
      }
    } catch (Exception e) {
      log.warn("RAG service failed for {}: {}", classFqn, e.getMessage());
      warnings.add(new AssemblerWarning("rag", e.getMessage()));
      codeChunks = List.of();
    }

    // -----------------------------------------------------------------------
    // Token budget truncation
    // -----------------------------------------------------------------------
    boolean truncated = false;
    int truncatedItems = 0;
    int tokenBudget = (int) (mcpConfig.getContext().getMaxTokens() * BUDGET_SAFETY_FACTOR);

    // Estimate total tokens
    int totalTokens = estimateTokens(cone)
        + estimateTokens(risk)
        + estimateTokens(terms)
        + estimateTokens(rules)
        + estimateTokens(codeChunks);

    if (totalTokens > tokenBudget && !codeChunks.isEmpty()) {
      List<ContextChunk> mutableChunks = new ArrayList<>(codeChunks);
      int originalSize = mutableChunks.size();

      // Drop chunks from the end until under budget
      while (totalTokens > tokenBudget && !mutableChunks.isEmpty()) {
        ContextChunk removed = mutableChunks.remove(mutableChunks.size() - 1);
        totalTokens -= estimateTokens(removed);
        truncatedItems++;
      }

      truncated = truncatedItems > 0;
      if (originalSize != mutableChunks.size()) {
        log.debug(
            "Token truncation: dropped {} code chunks for {} (budget={})",
            truncatedItems, classFqn, tokenBudget);
      }
      codeChunks = List.copyOf(mutableChunks);
    }

    // If still over budget after removing all code chunks, truncate cone nodes
    if (totalTokens > tokenBudget && cone != null
        && cone.coneNodes().size() > MAX_CONE_NODES_WHEN_TRUNCATED) {
      List<DependencyConeResponse.ConeNode> truncatedNodes =
          cone.coneNodes().subList(0, MAX_CONE_NODES_WHEN_TRUNCATED);
      cone = new DependencyConeResponse(
          cone.focalFqn(), truncatedNodes, cone.coneSize());
      truncated = true;
      log.debug(
          "Cone node truncation applied for {} — limited to {} nodes",
          classFqn, MAX_CONE_NODES_WHEN_TRUNCATED);
    }

    // Fetch class description: prefer curatedClassDescription (LLM/human-written),
    // fall back to auto-generated businessDescription
    String businessDescription = null;
    try {
      var descResult = neo4jClient.query("""
              MATCH (c:JavaClass {fullyQualifiedName: $fqn})
              RETURN c.curatedClassDescription AS curated, c.businessDescription AS auto
              """)
          .bind(classFqn).to("fqn")
          .fetch().one();
      if (descResult.isPresent()) {
        var row = descResult.get();
        Object curated = row.get("curated");
        Object auto = row.get("auto");
        if (curated instanceof String s && !s.isBlank()) {
          businessDescription = s;
        } else if (auto instanceof String s && !s.isBlank()) {
          businessDescription = s;
        }
      }
    } catch (Exception e) {
      log.debug("Failed to fetch business description for {}: {}", classFqn, e.getMessage());
    }

    long durationMs = System.currentTimeMillis() - startMs;
    return new MigrationContext(
        classFqn,
        businessDescription,
        cone,
        risk,
        terms,
        rules,
        codeChunks,
        truncated,
        truncatedItems,
        Math.min(completeness, 1.0),
        List.copyOf(warnings),
        durationMs);
  }

  // ---------------------------------------------------------------------------
  // Private Neo4j helpers
  // ---------------------------------------------------------------------------

  /**
   * Queries domain terms linked to the focal class via USES_TERM edges.
   */
  private List<BusinessTermResponse> queryDomainTerms(String fqn) {
    String cypher = """
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:USES_TERM]->(t:BusinessTerm)
        RETURN t.termId AS termId,
               t.displayName AS displayName,
               t.definition AS definition,
               t.criticality AS criticality,
               t.migrationSensitivity AS migrationSensitivity,
               t.synonyms AS synonyms,
               t.curated AS curated,
               t.status AS status,
               t.sourceType AS sourceType,
               t.primarySourceFqn AS primarySourceFqn,
               t.usageCount AS usageCount,
               t.uiRole AS uiRole,
               t.domainArea AS domainArea,
               t.nlsFileName AS nlsFileName,
               t.documentContext AS documentContext,
               t.documentSource AS documentSource
        """;

    Collection<Map<String, Object>> rows = neo4jClient
        .query(cypher)
        .bind(fqn).to("fqn")
        .fetch()
        .all();

    List<BusinessTermResponse> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      result.add(new BusinessTermResponse(
          (String) row.get("termId"),
          (String) row.get("displayName"),
          (String) row.get("definition"),
          (String) row.get("criticality"),
          (String) row.get("migrationSensitivity"),
          row.get("synonyms") instanceof List<?> list
              ? list.stream().filter(l -> l instanceof String).map(l -> (String) l).toList()
              : List.of(),
          Boolean.TRUE.equals(row.get("curated")),
          (String) row.get("status"),
          (String) row.get("sourceType"),
          (String) row.get("primarySourceFqn"),
          row.get("usageCount") instanceof Long l ? l.intValue() : 0,
          List.of(),
          (String) row.get("uiRole"),
          (String) row.get("domainArea"),
          (String) row.get("nlsFileName"),
          (String) row.get("documentContext"),
          (String) row.get("documentSource")));
    }
    return result;
  }

  /**
   * Queries business rules (DEFINES_RULE edges) for the focal class.
   */
  private List<String> queryBusinessRules(String fqn) {
    String cypher = """
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:DEFINES_RULE]->(rule:BusinessTerm)
        RETURN rule.displayName AS displayName
        """;

    Collection<Map<String, Object>> rows = neo4jClient
        .query(cypher)
        .bind(fqn).to("fqn")
        .fetch()
        .all();

    return rows.stream()
        .map(row -> (String) row.get("displayName"))
        .filter(name -> name != null)
        .toList();
  }

  // ---------------------------------------------------------------------------
  // Token estimation
  // ---------------------------------------------------------------------------

  /**
   * Estimates the token count for an arbitrary object by serializing it to JSON and dividing
   * by {@value CHARS_PER_TOKEN} (conservative chars-per-token estimate for mixed code/English).
   *
   * @param obj the object to estimate (null returns 0)
   * @return estimated token count
   */
  private int estimateTokens(Object obj) {
    if (obj == null) {
      return 0;
    }
    try {
      String json = objectMapper.writeValueAsString(obj);
      return json.length() / CHARS_PER_TOKEN;
    } catch (Exception e) {
      log.debug("Token estimation failed: {}", e.getMessage());
      return 0;
    }
  }
}
