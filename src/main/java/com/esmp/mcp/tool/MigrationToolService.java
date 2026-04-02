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
import com.esmp.migration.api.MigrationPlan;
import com.esmp.migration.api.MigrationResult;
import com.esmp.migration.api.ModuleMigrationSummary;
import com.esmp.migration.api.RecipeRule;
import com.esmp.migration.application.MigrationRecipeService;
import com.esmp.migration.application.RecipeBookRegistry;
import com.esmp.vector.api.SearchRequest;
import com.esmp.vector.api.SearchResponse;
import com.esmp.vector.application.VectorSearchService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * MCP tool service exposing 12 migration-assistance tools to AI assistants.
 *
 * <p>Each method is annotated with {@link Tool} so that the Spring AI MCP server can discover
 * and expose it as a named tool via the SSE transport.
 *
 * <p>Tools:
 * <ol>
 *   <li>{@link #getMigrationContext} — primary context assembly for a class FQN
 *   <li>{@link #searchKnowledge} — semantic vector search across indexed code chunks
 *   <li>{@link #getDependencyCone} — graph-based dependency traversal
 *   <li>{@link #getRiskAnalysis} — risk heatmap or class-level risk detail
 *   <li>{@link #browseDomainTerms} — lexicon browsing and search
 *   <li>{@link #getDomainGlossary} — project-wide domain glossary
 *   <li>{@link #validateSystemHealth} — full graph + vector validation report
 *   <li>{@link #getMigrationPlan} — OpenRewrite recipe plan for a class
 *   <li>{@link #applyMigrationRecipes} — apply recipes and return diff (no disk write)
 *   <li>{@link #getModuleMigrationSummary} — module-level automation statistics
 *   <li>{@link #getRecipeBookGaps} — NEEDS_MAPPING rules sorted by usageCount
 *   <li>{@link #getSourceCode} — read Java source from file system via graph path
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
  private final MigrationRecipeService migrationRecipeService;
  private final RecipeBookRegistry recipeBookRegistry;
  private final Neo4jClient neo4jClient;

  public MigrationToolService(
      MigrationContextAssembler assembler,
      GraphQueryService graphQueryService,
      RiskService riskService,
      LexiconService lexiconService,
      VectorSearchService vectorSearchService,
      ValidationService validationService,
      MigrationRecipeService migrationRecipeService,
      RecipeBookRegistry recipeBookRegistry,
      Neo4jClient neo4jClient) {
    this.assembler = assembler;
    this.graphQueryService = graphQueryService;
    this.riskService = riskService;
    this.lexiconService = lexiconService;
    this.vectorSearchService = vectorSearchService;
    this.validationService = validationService;
    this.migrationRecipeService = migrationRecipeService;
    this.recipeBookRegistry = recipeBookRegistry;
    this.neo4jClient = neo4jClient;
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
  public MigrationContext getMigrationContext(String classFqn) {
    long startMs = System.currentTimeMillis();

    MigrationContext result = assembler.assemble(classFqn);

    log.info("MCP_REQUEST tool=getMigrationContext classFqn={} latencyMs={} completeness={} "
        + "truncated={} warnings={}",
        classFqn, System.currentTimeMillis() - startMs, result.contextCompleteness(),
        result.truncated(), result.warnings().size());

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
  public SearchResponse searchKnowledge(String query, String module, String stereotype, int topK) {
    long startMs = System.currentTimeMillis();

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
  @Cacheable(value = "dependencyCones", key = "#classFqn")
  public Optional<DependencyConeResponse> getDependencyCone(String classFqn, int maxDepth) {
    long startMs = System.currentTimeMillis();

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
  public Object getRiskAnalysis(String classFqn, String module, String sortBy, int limit) {
    long startMs = System.currentTimeMillis();

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
   * Browse or search the domain lexicon with structured domain intelligence.
   *
   * <p>By default, returns only NLS-sourced terms (genuine business vocabulary with UI roles and
   * domain areas). Set {@code includeAll=true} to also include CLASS_NAME/ENUM noise terms.
   *
   * @param search      optional case-insensitive search text matched against term names
   * @param criticality optional criticality filter: "High", "Medium", or "Low"
   * @param uiRole      optional UI role filter: LABEL, MESSAGE, TOOLTIP, BUTTON, TITLE, ERROR, etc.
   * @param domainArea  optional domain area filter: ORDER_MANAGEMENT, CONTRACT_MANAGEMENT, COMMON, etc.
   * @param includeAll  if true, include CLASS_NAME/ENUM terms; default false (NLS-only)
   * @return list of matching business terms with definitions, UI roles, domain areas, and usage counts
   */
  @Tool(description = "Browse or search the domain lexicon. Returns business terms with UI roles "
      + "(LABEL, MESSAGE, TOOLTIP, BUTTON, TITLE, ERROR, WARNING) and domain areas "
      + "(ORDER_MANAGEMENT, CONTRACT_MANAGEMENT, COMMON, PRODUCTION, SALES, etc.). "
      + "Input: optional search text, criticality (High/Medium/Low), uiRole filter, "
      + "domainArea filter. By default returns only NLS-sourced terms (real business vocabulary). "
      + "Set includeAll=true to also get CLASS_NAME/ENUM terms. "
      + "UI roles tell you which Vaadin 24 component to use: LABEL→field.setLabel(), "
      + "MESSAGE→Notification.show(), TOOLTIP→Tooltip.forComponent(), TITLE→dialog.setHeaderTitle().")
  @Cacheable(value = "domainTermsByClass",
      key = "#search + '_' + #criticality + '_' + #uiRole + '_' + #domainArea + '_' + #includeAll")
  public List<BusinessTermResponse> browseDomainTerms(
      String search, String criticality, String uiRole, String domainArea, boolean includeAll) {
    long startMs = System.currentTimeMillis();

    boolean nlsOnly = !includeAll;
    List<BusinessTermResponse> raw = lexiconService.findByFilters(
        criticality, null, search, null, uiRole, domainArea, nlsOnly, 100);

    // Strip documentContext from list responses to keep MCP payloads compact.
    // Agents can get full doc context via getMigrationContext for a specific class.
    List<BusinessTermResponse> result = raw.stream()
        .map(t -> new BusinessTermResponse(
            t.termId(), t.displayName(), t.definition(), t.criticality(),
            t.migrationSensitivity(), t.synonyms(), t.curated(), t.status(),
            t.sourceType(), t.primarySourceFqn(), t.usageCount(), t.relatedClassFqns(),
            t.uiRole(), t.domainArea(), t.nlsFileName(), null, null))
        .toList();

    log.info("MCP_REQUEST tool=browseDomainTerms params='search={} criticality={} uiRole={} "
        + "domainArea={} includeAll={}' results={} latencyMs={}",
        search, criticality, uiRole, domainArea, includeAll,
        result.size(), System.currentTimeMillis() - startMs);

    return result;
  }

  /**
   * Returns a project-wide domain glossary: domain area overview, UI role distribution,
   * and top business terms by usage. Unlike browseDomainTerms (per-class), this gives
   * the migration agent a high-level map of the entire business domain.
   *
   * @return structured glossary with domain areas, UI role counts, and top terms
   */
  @Tool(description = "Get project-wide domain glossary. Returns: domain areas with term counts "
      + "(ORDER_MANAGEMENT, CONTRACT_MANAGEMENT, PRODUCTION, SALES, etc.), "
      + "UI role distribution (how many LABEL/MESSAGE/TOOLTIP/BUTTON terms exist), "
      + "top 30 business terms by usage, and abbreviation glossary (SC=Schedule Composition, "
      + "AEP=Ad Edition Part, DS=Distribution Schedule, BP=Business Partner, etc.). "
      + "Use to understand what the business does, decode abbreviations in method/class names, "
      + "and orient yourself before diving into specific classes.")
  public com.esmp.graph.api.DomainGlossaryResponse getDomainGlossary() {
    long startMs = System.currentTimeMillis();

    var result = lexiconService.getDomainGlossary();

    log.info("MCP_REQUEST tool=getDomainGlossary params='' areas={} topTerms={} latencyMs={}",
        result.domainAreas().size(), result.topTerms().size(),
        System.currentTimeMillis() - startMs);

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
  public ValidationReport validateSystemHealth() {
    long startMs = System.currentTimeMillis();

    ValidationReport result = validationService.runAllValidations();

    log.info("MCP_REQUEST tool=validateSystemHealth params='' latencyMs={}",
        System.currentTimeMillis() - startMs);

    return result;
  }

  /**
   * Saves a curated natural language business description for a class.
   * This description is never overwritten by re-extraction and takes priority
   * over the auto-generated businessDescription in all responses.
   *
   * @param classFqn    fully-qualified class name
   * @param description natural language description of the class's business role
   * @return confirmation message
   */
  @Tool(description = "Save a curated business description for a Java class. Input: classFqn + "
      + "description (2-3 sentences explaining what the class does in business terms). "
      + "This is permanent — never overwritten by re-extraction. Use after reading "
      + "getMigrationContext to write a human-quality description of the class's purpose.")
  public String saveClassDescription(String classFqn, String description) {
    long startMs = System.currentTimeMillis();

    boolean updated = lexiconService.updateCuratedClassDescription(classFqn, description);

    log.info("MCP_REQUEST tool=saveClassDescription classFqn={} updated={} latencyMs={}",
        classFqn, updated, System.currentTimeMillis() - startMs);

    return updated
        ? "Description saved for " + classFqn
        : "Class not found: " + classFqn;
  }

  /**
   * Returns the OpenRewrite migration plan for a Java class, showing which Vaadin 7 transforms
   * can be automated and which require AI-assisted migration.
   *
   * @param classFqn fully-qualified class name (e.g. com.example.PaymentView)
   * @return {@link MigrationPlan} with automatable actions, manual actions, and automation score
   */
  @Tool(description = "Get migration plan for a Java class showing which Vaadin 7 to Vaadin 24 "
      + "transforms are automatable by OpenRewrite recipe and which need AI-assisted migration. "
      + "Returns automation score, automatable action list (type renames, import swaps), and "
      + "manual action list (complex rewrites like Table to Grid, data binding changes).")
  public MigrationPlan getMigrationPlan(String classFqn) {
    log.info("MCP getMigrationPlan called for: {}", classFqn);
    try {
      return migrationRecipeService.generatePlan(classFqn);
    } catch (Exception e) {
      log.error("getMigrationPlan failed for {}: {}", classFqn, e.getMessage(), e);
      throw e;
    }
  }

  /**
   * Applies OpenRewrite recipes to automate mechanical Vaadin 7 to Vaadin 24 transforms for a
   * class. Returns unified diff and modified source text. Does NOT write to disk.
   *
   * @param classFqn fully-qualified class name
   * @return {@link MigrationResult} with diff and modified source (no file is written)
   */
  @Tool(description = "Apply OpenRewrite recipes to automate mechanical Vaadin 7 to Vaadin 24 "
      + "transforms for a class. Returns unified diff and modified source text. Does NOT write "
      + "to disk — use your own file writing tools to apply the changes. Only applies transforms "
      + "classified as automatable (type renames, import swaps, package changes). Complex rewrites "
      + "like Table-to-Grid and data binding are left for AI.")
  public MigrationResult applyMigrationRecipes(String classFqn) {
    log.info("MCP applyMigrationRecipes called for: {}", classFqn);
    try {
      return migrationRecipeService.preview(classFqn);
    } catch (Exception e) {
      log.error("applyMigrationRecipes failed for {}: {}", classFqn, e.getMessage(), e);
      throw e;
    }
  }

  /**
   * Returns module-level migration automation statistics showing how many classes are fully
   * automatable, partially automatable, or need AI-only migration.
   *
   * @param module the module name (third segment of the package name, e.g., "billing")
   * @return {@link ModuleMigrationSummary} with class counts, action counts, average score,
   *         transitiveClassCount, coverageByType, coverageByUsage, and topGaps
   */
  @Tool(description = "Get migration automation summary for a module showing how many classes are "
      + "fully automatable by OpenRewrite recipes, partially automatable, and need AI-only migration. "
      + "Also returns transitiveClassCount (classes inheriting Vaadin 7 types), coverageByType, "
      + "coverageByUsage (0.0-1.0), and topGaps (top unmapped types). "
      + "Use this to assess migration effort and decide which modules to tackle first.")
  public ModuleMigrationSummary getModuleMigrationSummary(String module) {
    log.info("MCP getModuleMigrationSummary called for: {}", module);
    try {
      return migrationRecipeService.getModuleSummary(module);
    } catch (Exception e) {
      log.error("getModuleMigrationSummary failed for {}: {}", module, e.getMessage(), e);
      throw e;
    }
  }

  /**
   * Returns unmapped Vaadin 7 types (NEEDS_MAPPING) sorted by usageCount descending.
   *
   * <p>These are types that ESMP detected during extraction but the recipe book does not yet have
   * a Vaadin 24 mapping for. Claude Code should research these types and add mappings via
   * {@code PUT /api/migration/recipe-book/rules/{id}}.
   *
   * @return list of {@link RecipeRule} with status=NEEDS_MAPPING, sorted by usageCount descending
   */
  @Tool(description = "Returns unmapped Vaadin 7 types (NEEDS_MAPPING) sorted by usageCount "
      + "descending. Use to discover what types Claude needs to research and add to the recipe book. "
      + "Returns: list of RecipeRule with source FQN, usageCount, and category. "
      + "Add mappings via PUT /api/migration/recipe-book/rules/{id}.")
  public List<RecipeRule> getRecipeBookGaps() {
    log.info("MCP getRecipeBookGaps called");
    return recipeBookRegistry.getRules().stream()
        .filter(r -> "NEEDS_MAPPING".equals(r.status()))
        .sorted(Comparator.comparingInt(RecipeRule::usageCount).reversed())
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * Returns the full Java source code for a class by its fully-qualified name.
   *
   * <p>The source file path is resolved from the Neo4j graph ({@code ClassNode.sourceFilePath})
   * and read from the local file system.
   *
   * @param classFqn fully-qualified class name (e.g. com.example.PaymentService)
   * @return the source code text, or an error message if not found / unreadable
   */
  @Tool(description = "Returns the full Java source code for a class by its fully-qualified name. "
      + "Use this when you need to see the actual implementation before rewriting or migrating a class. "
      + "The source is read from the file system path stored in the Neo4j graph.")
  public String getSourceCode(String classFqn) {
    long startMs = System.currentTimeMillis();

    // Query Neo4j for the source file path
    String sourceFilePath = neo4jClient.query(
            "MATCH (c:JavaClass {fullyQualifiedName: $fqn}) RETURN c.sourceFilePath AS path")
        .bind(classFqn).to("fqn")
        .fetchAs(String.class)
        .mappedBy((ts, record) -> record.get("path").asString(null))
        .one()
        .orElse(null);

    if (sourceFilePath == null || sourceFilePath.isBlank()) {
      log.warn("MCP_REQUEST tool=getSourceCode fqn={} result=NOT_FOUND", classFqn);
      return "Source file path not found for class: " + classFqn;
    }

    try {
      String source = Files.readString(Path.of(sourceFilePath));
      log.info("MCP_REQUEST tool=getSourceCode fqn={} bytes={} latencyMs={}",
          classFqn, source.length(), System.currentTimeMillis() - startMs);
      return source;
    } catch (IOException e) {
      log.warn("MCP_REQUEST tool=getSourceCode fqn={} error={}", classFqn, e.getMessage());
      return "Could not read source file at " + sourceFilePath + ": " + e.getMessage();
    }
  }
}
