package com.esmp.mcp.tool;

import com.esmp.graph.api.DependencyConeResponse;
import com.esmp.graph.api.RiskHeatmapEntry;
import com.esmp.graph.api.RiskDetailResponse;
import com.esmp.graph.api.BusinessTermResponse;
import com.esmp.graph.api.ValidationReport;
import com.esmp.graph.application.GraphQueryService;
import com.esmp.graph.application.LexiconService;
import com.esmp.graph.application.RiskService;
import com.esmp.graph.validation.ValidationService;
import com.esmp.mcp.api.MigrationContext;
import com.esmp.mcp.application.MigrationContextAssembler;
import com.esmp.vector.api.SearchRequest;
import com.esmp.vector.api.SearchResponse;
import com.esmp.vector.application.VectorSearchService;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * MCP tool service exposing 6 migration-assistance tools to AI assistants.
 *
 * <p>Each method is annotated with {@link Tool} so that the Spring AI MCP server can discover
 * and expose it as a named tool via the SSE transport. All methods are also instrumented with
 * Micrometer {@link Timed} and {@link Counter} metrics.
 *
 * <p>Tools:
 * <ol>
 *   <li>{@link #getMigrationContext} — primary context assembly for a class FQN
 *   <li>{@link #searchKnowledge} — semantic vector search across indexed code chunks
 *   <li>{@link #getDependencyCone} — graph-based dependency traversal
 *   <li>{@link #getRiskAnalysis} — risk heatmap or class-level risk detail
 *   <li>{@link #browseDomainTerms} — lexicon browsing and search
 *   <li>{@link #validateSystemHealth} — full graph + vector validation report
 * </ol>
 */
@Component
public class MigrationToolService {

  private static final Logger log = LoggerFactory.getLogger(MigrationToolService.class);

  private final MigrationContextAssembler assembler;
  private final GraphQueryService graphQueryService;
  private final RiskService riskService;
  private final LexiconService lexiconService;
  private final VectorSearchService vectorSearchService;
  private final ValidationService validationService;
  private final MeterRegistry meterRegistry;

  public MigrationToolService(
      MigrationContextAssembler assembler,
      GraphQueryService graphQueryService,
      RiskService riskService,
      LexiconService lexiconService,
      VectorSearchService vectorSearchService,
      ValidationService validationService,
      MeterRegistry meterRegistry) {
    this.assembler = assembler;
    this.graphQueryService = graphQueryService;
    this.riskService = riskService;
    this.lexiconService = lexiconService;
    this.vectorSearchService = vectorSearchService;
    this.validationService = validationService;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Retrieves comprehensive migration context for a Java class.
   *
   * @param classFqn fully-qualified class name (e.g. com.example.PaymentService)
   * @return assembled {@link MigrationContext} with dependency cone, code chunks, business terms,
   *         risk analysis, and business rules
   */
  @Tool(description = "Retrieves comprehensive migration context for a Java class. Input: "
      + "fully-qualified class name (e.g. com.example.PaymentService). Returns: dependency cone, "
      + "code chunks, business terms, risk analysis, business rules. Use as primary context tool "
      + "before migrating any class.")
  @Timed(value = "esmp.mcp.request.duration", extraTags = {"tool", "getMigrationContext"})
  public MigrationContext getMigrationContext(String classFqn) {
    long startMs = System.currentTimeMillis();
    Counter.builder("esmp.mcp.tool.invocations")
        .tag("tool", "getMigrationContext")
        .register(meterRegistry)
        .increment();

    MigrationContext result = assembler.assemble(classFqn);
    long latencyMs = System.currentTimeMillis() - startMs;

    log.info("MCP_REQUEST tool=getMigrationContext classFqn={} latencyMs={} completeness={} "
        + "truncated={} warnings={}",
        classFqn, latencyMs, result.contextCompleteness(), result.truncated(),
        result.warnings().size());

    return result;
  }

  /**
   * Semantic vector search across indexed code chunks.
   *
   * @param query     text query to embed and search (required)
   * @param module    optional module filter (e.g. "pilot", "billing")
   * @param stereotype optional stereotype filter (e.g. "Service", "Repository")
   * @param topK      maximum results to return (default 10 when 0 or less)
   * @return ranked search response with similarity scores
   */
  @Tool(description = "Semantic vector search across indexed code chunks. Input: query string "
      + "+ optional filters (module, stereotype, topK). Returns: ranked code chunks with "
      + "similarity scores. Use for finding relevant code when you don't have a specific class FQN.")
  @Timed(value = "esmp.mcp.request.duration", extraTags = {"tool", "searchKnowledge"})
  public SearchResponse searchKnowledge(String query, String module, String stereotype, int topK) {
    long startMs = System.currentTimeMillis();
    Counter.builder("esmp.mcp.tool.invocations")
        .tag("tool", "searchKnowledge")
        .register(meterRegistry)
        .increment();

    SearchRequest request = new SearchRequest(query, topK > 0 ? topK : 10, module, stereotype, null);
    SearchResponse result = vectorSearchService.search(request);

    log.info("MCP_REQUEST tool=searchKnowledge params='query={} module={} stereotype={} topK={}' "
        + "latencyMs={}", query, module, stereotype, topK, System.currentTimeMillis() - startMs);

    return result;
  }

  /**
   * Explores the dependency graph for a class.
   *
   * @param classFqn fully-qualified class name
   * @param maxDepth optional traversal depth (default 10)
   * @return dependency cone with all transitively reachable nodes
   */
  @Tool(description = "Explores the dependency graph for a class. Input: class FQN + optional "
      + "depth (default 10). Returns: all transitively reachable nodes via DEPENDS_ON, EXTENDS, "
      + "IMPLEMENTS, CALLS, BINDS_TO, QUERIES, MAPS_TO_TABLE edges. Use to understand what a "
      + "class depends on.")
  @Timed(value = "esmp.mcp.request.duration", extraTags = {"tool", "getDependencyCone"})
  @Cacheable(value = "dependencyCones", key = "#classFqn")
  public Optional<DependencyConeResponse> getDependencyCone(String classFqn, int maxDepth) {
    long startMs = System.currentTimeMillis();
    Counter.builder("esmp.mcp.tool.invocations")
        .tag("tool", "getDependencyCone")
        .register(meterRegistry)
        .increment();

    Optional<DependencyConeResponse> result = graphQueryService.findDependencyCone(classFqn);

    log.info("MCP_REQUEST tool=getDependencyCone params='classFqn={} maxDepth={}' latencyMs={}",
        classFqn, maxDepth, System.currentTimeMillis() - startMs);

    return result;
  }

  /**
   * Risk analysis: class detail or heatmap.
   *
   * @param classFqn if provided, returns detailed risk for that class; if blank returns heatmap
   * @param module   optional module filter for heatmap mode
   * @param sortBy   sort order for heatmap: "structural" or "enhanced" (default)
   * @param limit    maximum heatmap entries (default 20 when 0 or less)
   * @return {@link RiskDetailResponse} when classFqn provided, or {@link List} of
   *         {@link RiskHeatmapEntry} for heatmap mode
   */
  @Tool(description = "Risk analysis: if classFqn provided, returns detailed risk for that class "
      + "(complexity, fan-in/out, domain criticality, security, financial). If classFqn is "
      + "empty/null, returns risk heatmap filterable by module and sortable by 'structural' or "
      + "'enhanced'. Use when you need risk-specific info.")
  @Timed(value = "esmp.mcp.request.duration", extraTags = {"tool", "getRiskAnalysis"})
  public Object getRiskAnalysis(String classFqn, String module, String sortBy, int limit) {
    long startMs = System.currentTimeMillis();
    Counter.builder("esmp.mcp.tool.invocations")
        .tag("tool", "getRiskAnalysis")
        .register(meterRegistry)
        .increment();

    Object result;
    if (classFqn != null && !classFqn.isBlank()) {
      result = riskService.getClassDetail(classFqn);
    } else {
      result = riskService.getHeatmap(module, null, null, limit > 0 ? limit : 20,
          sortBy != null ? sortBy : "enhanced");
    }

    log.info("MCP_REQUEST tool=getRiskAnalysis params='classFqn={} module={} sortBy={} limit={}' "
        + "latencyMs={}", classFqn, module, sortBy, limit,
        System.currentTimeMillis() - startMs);

    return result;
  }

  /**
   * Browse or search the domain lexicon.
   *
   * @param search      optional case-insensitive search text matched against term names
   * @param criticality optional criticality filter: "High", "Medium", or "Low"
   * @return list of matching business terms with definitions and usage counts
   */
  @Tool(description = "Browse or search the domain lexicon. Input: optional search text and/or "
      + "criticality filter (High, Medium, Low). Returns: business terms with definitions, "
      + "related classes, usage counts. Use to understand business terminology in the codebase.")
  @Timed(value = "esmp.mcp.request.duration", extraTags = {"tool", "browseDomainTerms"})
  @Cacheable(value = "domainTermsByClass", key = "#search + '_' + #criticality")
  public List<BusinessTermResponse> browseDomainTerms(String search, String criticality) {
    long startMs = System.currentTimeMillis();
    Counter.builder("esmp.mcp.tool.invocations")
        .tag("tool", "browseDomainTerms")
        .register(meterRegistry)
        .increment();

    List<BusinessTermResponse> result = lexiconService.findByFilters(criticality, null, search);

    log.info("MCP_REQUEST tool=browseDomainTerms params='search={} criticality={}' latencyMs={}",
        search, criticality, System.currentTimeMillis() - startMs);

    return result;
  }

  /**
   * Runs all validation queries against the knowledge graph and vector store.
   *
   * @return validation report with per-query pass/fail/warn results and aggregate counts
   */
  @Tool(description = "Runs all validation queries against the knowledge graph and vector store. "
      + "Returns: pass/fail/warn counts and detailed results for each query. Call before starting "
      + "migration to confirm data integrity.")
  @Timed(value = "esmp.mcp.request.duration", extraTags = {"tool", "validateSystemHealth"})
  public ValidationReport validateSystemHealth() {
    long startMs = System.currentTimeMillis();
    Counter.builder("esmp.mcp.tool.invocations")
        .tag("tool", "validateSystemHealth")
        .register(meterRegistry)
        .increment();

    ValidationReport result = validationService.runAllValidations();

    log.info("MCP_REQUEST tool=validateSystemHealth params='' latencyMs={}",
        System.currentTimeMillis() - startMs);

    return result;
  }
}
