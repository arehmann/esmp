package com.esmp.mcp.tool;

import com.esmp.graph.application.LexiconService;
import com.esmp.mcp.api.MigrationContext;
import com.esmp.mcp.application.MigrationContextAssembler;
import com.esmp.migration.api.MigrationPlan;
import com.esmp.migration.api.MigrationResult;
import com.esmp.migration.api.RecipeRule;
import com.esmp.migration.application.MigrationRecipeService;
import com.esmp.migration.application.RecipeBookRegistry;
import com.esmp.vector.api.SearchRequest;
import com.esmp.vector.api.SearchResponse;
import com.esmp.vector.application.VectorSearchService;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * MCP tool service exposing 6 migration-assistance tools to AI assistants.
 *
 * <p>Tools:
 * <ol>
 *   <li>{@link #getMigrationContext} — primary context assembly for a class FQN
 *   <li>{@link #getMigrationPlan} — OpenRewrite recipe plan for a class
 *   <li>{@link #applyMigrationRecipes} — apply recipes and return diff (no disk write)
 *   <li>{@link #saveClassDescription} — persist curated business description
 *   <li>{@link #searchKnowledge} — semantic vector search across indexed code chunks
 *   <li>{@link #getRecipeBookGaps} — unmapped Vaadin 7 types, optionally filtered by class
 * </ol>
 */
@Component
public class MigrationToolService {

  private static final Logger log = LoggerFactory.getLogger(MigrationToolService.class);

  private final MigrationContextAssembler assembler;
  private final LexiconService lexiconService;
  private final VectorSearchService vectorSearchService;
  private final MigrationRecipeService migrationRecipeService;
  private final RecipeBookRegistry recipeBookRegistry;
  private final Neo4jClient neo4jClient;

  public MigrationToolService(
      MigrationContextAssembler assembler,
      LexiconService lexiconService,
      VectorSearchService vectorSearchService,
      MigrationRecipeService migrationRecipeService,
      RecipeBookRegistry recipeBookRegistry,
      Neo4jClient neo4jClient) {
    this.assembler = assembler;
    this.lexiconService = lexiconService;
    this.vectorSearchService = vectorSearchService;
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
      + "Returns automation score, automatable action list, manual action list, "
      + "hasAlfaIntermediaries flag (true when Layer 2 class uses Alfa* wrappers), and "
      + "alfaIntermediaryCount (number of distinct Alfa* wrapper classes in the inheritance chain). "
      + "For Layer 2 classes, actions include isInherited=true, inheritedFrom (Alfa* FQN), "
      + "vaadinAncestor (ultimate com.vaadin.* ancestor), and ownAlfaCalls count.")
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
   * Returns unmapped Vaadin 7 types (NEEDS_MAPPING) sorted by usageCount descending.
   *
   * <p>When {@code classFqn} is provided, filters to only gaps relevant to that specific
   * class (types used in its migration actions that have no recipe mapping).
   * When omitted or blank, returns all project-wide gaps.
   *
   * @param classFqn optional class FQN to filter gaps by
   * @return list of {@link RecipeRule} with status=NEEDS_MAPPING
   */
  @Tool(description = "Returns unmapped migration types (NEEDS_MAPPING) sorted by usageCount "
      + "descending. Includes both unmapped com.vaadin.* types and unmapped Alfa* wrapper types "
      + "(com.alfa.*) that have no migration mapping yet (e.g. AlfaStyloPanel, DTPEditorPanel). "
      + "Optional classFqn filter: when provided, returns only gaps affecting that specific class. "
      + "When omitted, returns all project-wide gaps. "
      + "Use to discover what Vaadin 7 and Alfa* types need migration mappings before automating.")
  public List<RecipeRule> getRecipeBookGaps(String classFqn) {
    log.info("MCP getRecipeBookGaps called classFqn={}", classFqn);

    List<RecipeRule> gaps = recipeBookRegistry.getRules().stream()
        .filter(r -> "NEEDS_MAPPING".equals(r.status()))
        .sorted(Comparator.comparingInt(RecipeRule::usageCount).reversed())
        .collect(java.util.stream.Collectors.toList());

    if (classFqn != null && !classFqn.isBlank()) {
      var classActions = neo4jClient.query("""
              MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
              RETURN DISTINCT ma.source AS source
              """)
          .bind(classFqn).to("fqn")
          .fetch().all();

      var classSources = classActions.stream()
          .map(row -> (String) row.get("source"))
          .filter(s -> s != null)
          .collect(java.util.stream.Collectors.toSet());

      gaps = gaps.stream()
          .filter(r -> classSources.contains(r.source()))
          .collect(java.util.stream.Collectors.toList());
    }

    return gaps;
  }
}
