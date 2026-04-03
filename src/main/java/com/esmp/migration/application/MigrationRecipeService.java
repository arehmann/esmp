package com.esmp.migration.application;

import com.esmp.extraction.config.MigrationConfig;
import com.esmp.extraction.parser.JavaSourceParser;
import com.esmp.migration.api.BatchMigrationResult;
import com.esmp.migration.api.MigrationActionEntry;
import com.esmp.migration.api.MigrationPlan;
import com.esmp.migration.api.MigrationResult;
import com.esmp.migration.api.ModuleMigrationSummary;
import com.esmp.migration.api.RecipeRule;
import com.esmp.source.application.SourceAccessService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.config.CompositeRecipe;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.ChangePackage;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * OpenRewrite recipe generation and execution engine for Vaadin 7 → Vaadin 24 migration.
 *
 * <p>Loads {@code MigrationAction} nodes from the Neo4j graph, builds composite OpenRewrite
 * recipes from automatable actions, and executes them in either preview mode (returns diff and
 * modified source without writing to disk) or apply mode (writes modified source back to the
 * original file path).
 *
 * <h3>Key methods</h3>
 *
 * <ul>
 *   <li>{@link #generatePlan(String)} — loads actions from graph and returns the migration plan
 *   <li>{@link #preview(String)} — executes the composite recipe and returns diff + modified source
 *   <li>{@link #applyAndWrite(String)} — executes the recipe and writes modified source to disk
 *   <li>{@link #applyModule(String)} — batch-applies recipes to all automatable classes in a module
 *   <li>{@link #getModuleSummary(String)} — aggregates migration stats for a module
 * </ul>
 */
@Service
public class MigrationRecipeService {

  private static final Logger log = LoggerFactory.getLogger(MigrationRecipeService.class);

  private final Neo4jClient neo4jClient;
  private final JavaSourceParser javaSourceParser;
  private final SourceAccessService sourceAccessService;
  private final RecipeBookRegistry recipeBookRegistry;
  private final MigrationConfig migrationConfig;

  public MigrationRecipeService(
      Neo4jClient neo4jClient,
      JavaSourceParser javaSourceParser,
      SourceAccessService sourceAccessService,
      RecipeBookRegistry recipeBookRegistry,
      MigrationConfig migrationConfig) {
    this.neo4jClient = neo4jClient;
    this.javaSourceParser = javaSourceParser;
    this.sourceAccessService = sourceAccessService;
    this.recipeBookRegistry = recipeBookRegistry;
    this.migrationConfig = migrationConfig;
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Generates a migration plan for a single class by loading its migration actions from Neo4j.
   *
   * @param classFqn fully qualified name of the class
   * @return migration plan with automatable and manual action lists
   */
  public MigrationPlan generatePlan(String classFqn) {
    List<MigrationActionEntry> actions = loadActionsFromGraph(classFqn);

    List<MigrationActionEntry> automatable =
        actions.stream().filter(a -> "YES".equals(a.automatable())).collect(Collectors.toList());

    List<MigrationActionEntry> manual =
        actions.stream()
            .filter(a -> !"YES".equals(a.automatable()))
            .collect(Collectors.toList());

    int total = actions.size();
    int yesCount = (int) actions.stream().filter(a -> "YES".equals(a.automatable())).count();
    int partialCount =
        (int) actions.stream().filter(a -> "PARTIAL".equals(a.automatable())).count();
    boolean needsAi = actions.stream().anyMatch(a -> "NO".equals(a.automatable()));

    double automationScore = total > 0 ? (yesCount + 0.5 * partialCount) / total : 0.0;

    return new MigrationPlan(
        classFqn,
        automatable,
        manual,
        total,
        yesCount,
        manual.size(),
        automationScore,
        needsAi);
  }

  /**
   * Executes OpenRewrite recipes for a class in preview mode.
   *
   * <p>Parses the class source, builds a composite recipe from automatable actions, runs it, and
   * returns the unified diff and modified source text. Does NOT write any files to disk.
   *
   * @param classFqn fully qualified name of the class
   * @return migration result with diff and modified source (or noChanges if nothing to automate)
   */
  public MigrationResult preview(String classFqn) {
    MigrationPlan plan = generatePlan(classFqn);

    if (plan.automatableActions().isEmpty()) {
      log.debug("No automatable actions for class '{}'", classFqn);
      return MigrationResult.noChanges(classFqn);
    }

    String sourceFilePath = loadSourceFilePath(classFqn);
    if (sourceFilePath == null || sourceFilePath.isBlank()) {
      log.warn("No sourceFilePath found in Neo4j for class '{}'", classFqn);
      return MigrationResult.noChanges(classFqn);
    }

    Path sourcePath = resolveSourcePath(sourceFilePath);
    if (!Files.exists(sourcePath)) {
      log.warn("Source file '{}' does not exist on disk for class '{}'", sourceFilePath, classFqn);
      return MigrationResult.noChanges(classFqn);
    }

    Path projectRoot = sourcePath.getParent();

    List<SourceFile> lsts = parseWithJvmClasspath(List.of(sourcePath), projectRoot);
    if (lsts.isEmpty()) {
      log.warn("Parser produced no LSTs for source file '{}'", sourceFilePath);
      return MigrationResult.noChanges(classFqn);
    }

    Recipe recipe = buildCompositeRecipe(plan.automatableActions());
    if (recipe == null) {
      log.debug("No supported recipes to build for class '{}'", classFqn);
      return MigrationResult.noChanges(classFqn);
    }

    InMemoryExecutionContext ctx =
        new InMemoryExecutionContext(t -> log.warn("Recipe execution error: {}", t.getMessage()));

    var recipeRun = recipe.run(new InMemoryLargeSourceSet(lsts), ctx);
    List<Result> changes = recipeRun.getChangeset().getAllResults();

    if (changes.isEmpty()) {
      return MigrationResult.noChanges(classFqn);
    }

    Result result = changes.get(0);
    String diff = result.diff(Path.of(""));
    String modifiedSource = Objects.requireNonNull(result.getAfter()).printAll();

    return new MigrationResult(
        classFqn,
        true,
        diff,
        modifiedSource,
        changes.size(),
        plan.manualActions(),
        plan.automationScore());
  }

  /**
   * Executes OpenRewrite recipes for a class and writes the modified source back to disk.
   *
   * @param classFqn fully qualified name of the class
   * @return migration result (same as preview, but file is written to disk if changes exist)
   * @throws IOException if the file cannot be written
   */
  public MigrationResult applyAndWrite(String classFqn) throws IOException {
    MigrationResult result = preview(classFqn);

    if (result.hasChanges()) {
      String sourceFilePath = loadSourceFilePath(classFqn);
      if (sourceFilePath != null && !sourceFilePath.isBlank()) {
        Files.writeString(Path.of(sourceFilePath), result.modifiedSource());
        log.info("Applied migration recipe to '{}'", sourceFilePath);
      }
    }

    return result;
  }

  /**
   * Applies OpenRewrite recipes to all automatable classes in the given module.
   *
   * <p>Loads all classes with migration actions in the module, filters to those with at least one
   * automatable action, and calls {@link #applyAndWrite(String)} for each. Errors from individual
   * classes are collected rather than propagated, so the batch continues on failure.
   *
   * @param module the module name (third segment of the package name, e.g., "migration")
   * @return batch result with per-class results and error summary
   */
  public BatchMigrationResult applyModule(String module) {
    long startTime = System.currentTimeMillis();

    List<Map<String, Object>> classRows = loadActionsForModule(module);

    List<MigrationResult> results = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    int classesModified = 0;
    int totalRecipesApplied = 0;

    for (Map<String, Object> row : classRows) {
      String classFqn = (String) row.get("classFqn");
      if (classFqn == null) continue;

      // Only process classes with automatable actions
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> actionMaps = (List<Map<String, Object>>) row.get("actions");
      boolean hasAutomatable =
          actionMaps != null
              && actionMaps.stream().anyMatch(a -> "YES".equals(a.get("automatable")));
      if (!hasAutomatable) continue;

      try {
        MigrationResult result = applyAndWrite(classFqn);
        results.add(result);
        if (result.hasChanges()) {
          classesModified++;
          totalRecipesApplied += result.recipesApplied();
        }
      } catch (Exception e) {
        String errorMsg = "Failed to apply recipe to '" + classFqn + "': " + e.getMessage();
        log.error(errorMsg, e);
        errors.add(errorMsg);
      }
    }

    long durationMs = System.currentTimeMillis() - startTime;
    return new BatchMigrationResult(
        module,
        results.size(),
        classesModified,
        totalRecipesApplied,
        results,
        errors,
        durationMs);
  }

  /**
   * Returns aggregated migration statistics for all classes in a module.
   *
   * <p>Extended in Phase 17 to also return:
   * <ul>
   *   <li>{@code transitiveClassCount} — classes with at least one inherited MigrationAction
   *   <li>{@code coverageByType} — fraction of unique source types with a mapped recipe
   *   <li>{@code coverageByUsage} — fraction of usage instances that are mapped
   *   <li>{@code topGaps} — top-5 NEEDS_MAPPING sources by usageCount from recipe book
   * </ul>
   *
   * @param module the module name (third segment of the package name)
   * @return summary with class counts, action counts, automation score, and coverage metrics
   */
  /**
   * Returns aggregated migration statistics across the entire project (all modules).
   */
  public ModuleMigrationSummary getProjectSummary() {
    String query =
        """
        MATCH (c:JavaClass)
        WHERE NOT (c.packageName CONTAINS 'castor'
                OR c.packageName CONTAINS 'persistentObjects'
                OR c.packageName CONTAINS 'businessObjects'
                OR c.packageName CONTAINS 'com4j')
        OPTIONAL MATCH (c)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        WITH c, count(ma) AS actionCount,
             sum(CASE WHEN ma.automatable = 'YES' THEN 1 ELSE 0 END) AS yesCount,
             sum(CASE WHEN ma.isInherited = true THEN 1 ELSE 0 END) AS inheritedCount
        RETURN
          count(c) AS totalClasses,
          sum(CASE WHEN actionCount > 0 THEN 1 ELSE 0 END) AS classesWithActions,
          sum(CASE WHEN c.automationScore = 1.0 THEN 1 ELSE 0 END) AS fullyAutomatable,
          sum(CASE WHEN c.automationScore > 0.0 AND c.automationScore < 1.0 THEN 1 ELSE 0 END) AS partiallyAutomatable,
          sum(CASE WHEN actionCount > 0 AND c.automationScore = 0.0 THEN 1 ELSE 0 END) AS needsAiOnly,
          avg(CASE WHEN actionCount > 0 THEN c.automationScore ELSE null END) AS avgScore,
          sum(actionCount) AS totalActions,
          sum(yesCount) AS totalAutomatable,
          sum(CASE WHEN inheritedCount > 0 THEN 1 ELSE 0 END) AS transitiveClassCount
        """;

    String coverageQuery =
        """
        MATCH (c:JavaClass)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        WHERE NOT (c.packageName CONTAINS 'castor'
                OR c.packageName CONTAINS 'persistentObjects'
                OR c.packageName CONTAINS 'businessObjects'
                OR c.packageName CONTAINS 'com4j')
          AND NOT ma.isInherited
        WITH DISTINCT ma.source AS src, ma.automatable AS auto
        RETURN count(src) AS totalTypes,
               sum(CASE WHEN auto <> 'NO' THEN 1 ELSE 0 END) AS mappedTypes
        """;

    String usageQuery =
        """
        MATCH (c:JavaClass)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        WHERE NOT (c.packageName CONTAINS 'castor'
                OR c.packageName CONTAINS 'persistentObjects'
                OR c.packageName CONTAINS 'businessObjects'
                OR c.packageName CONTAINS 'com4j')
          AND NOT ma.isInherited
        RETURN count(ma) AS totalUsages,
               sum(CASE WHEN ma.automatable <> 'NO' THEN 1 ELSE 0 END) AS mappedUsages
        """;

    List<String> topGaps = recipeBookRegistry.getRules().stream()
        .filter(r -> "NEEDS_MAPPING".equals(r.status()))
        .sorted(java.util.Comparator.comparingInt(RecipeRule::usageCount).reversed())
        .limit(5)
        .map(RecipeRule::source)
        .collect(Collectors.toList());

    var result = neo4jClient
        .query(query)
        .fetchAs(ModuleMigrationSummary.class)
        .mappedBy((ts, record) -> {
          int totalClasses = record.get("totalClasses").asInt(0);
          int classesWithActions = record.get("classesWithActions").asInt(0);
          int fullyAutomatable = record.get("fullyAutomatable").asInt(0);
          int partiallyAutomatable = record.get("partiallyAutomatable").asInt(0);
          int needsAiOnly = record.get("needsAiOnly").asInt(0);
          double avgScore = record.get("avgScore").isNull() ? 0.0
              : record.get("avgScore").asDouble(0.0);
          int totalActions = record.get("totalActions").asInt(0);
          int totalAutomatable = record.get("totalAutomatable").asInt(0);
          int transitiveClassCount = record.get("transitiveClassCount").asInt(0);
          return new ModuleMigrationSummary(
              "all", totalClasses, classesWithActions, fullyAutomatable,
              partiallyAutomatable, needsAiOnly, avgScore, totalActions,
              totalAutomatable, transitiveClassCount, 0.0, 0.0, topGaps);
        })
        .one();

    if (result.isEmpty()) {
      return new ModuleMigrationSummary("all", 0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0.0, 0.0, topGaps);
    }

    var coverageResult = neo4jClient
        .query(coverageQuery)
        .fetchAs(double[].class)
        .mappedBy((ts, record) -> {
          int totalTypes = record.get("totalTypes").asInt(0);
          int mappedTypes = record.get("mappedTypes").asInt(0);
          double byType = totalTypes > 0 ? (double) mappedTypes / totalTypes : 0.0;
          return new double[]{byType};
        })
        .one();

    double coverageByType = coverageResult.map(arr -> arr[0]).orElse(0.0);

    var usageResult = neo4jClient
        .query(usageQuery)
        .fetchAs(double[].class)
        .mappedBy((ts, record) -> {
          int totalUsages = record.get("totalUsages").asInt(0);
          int mappedUsages = record.get("mappedUsages").asInt(0);
          double byUsage = totalUsages > 0 ? (double) mappedUsages / totalUsages : 0.0;
          return new double[]{byUsage};
        })
        .one();

    double coverageByUsage = usageResult.map(arr -> arr[0]).orElse(0.0);

    ModuleMigrationSummary base = result.get();
    return new ModuleMigrationSummary(
        base.module(), base.totalClasses(), base.classesWithActions(),
        base.fullyAutomatableClasses(), base.partiallyAutomatableClasses(),
        base.needsAiOnlyClasses(), base.averageAutomationScore(),
        base.totalActions(), base.totalAutomatableActions(),
        base.transitiveClassCount(), coverageByType, coverageByUsage, topGaps);
  }

  public ModuleMigrationSummary getModuleSummary(String module) {
    String query =
        """
        MATCH (c:JavaClass)
        WHERE c.module = $module
          AND NOT (c.packageName CONTAINS 'castor'
                OR c.packageName CONTAINS 'persistentObjects'
                OR c.packageName CONTAINS 'businessObjects'
                OR c.packageName CONTAINS 'com4j')
        OPTIONAL MATCH (c)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        WITH c, count(ma) AS actionCount,
             sum(CASE WHEN ma.automatable = 'YES' THEN 1 ELSE 0 END) AS yesCount,
             sum(CASE WHEN ma.isInherited = true THEN 1 ELSE 0 END) AS inheritedCount
        RETURN
          count(c) AS totalClasses,
          sum(CASE WHEN actionCount > 0 THEN 1 ELSE 0 END) AS classesWithActions,
          sum(CASE WHEN c.automationScore = 1.0 THEN 1 ELSE 0 END) AS fullyAutomatable,
          sum(CASE WHEN c.automationScore > 0.0 AND c.automationScore < 1.0 THEN 1 ELSE 0 END) AS partiallyAutomatable,
          sum(CASE WHEN actionCount > 0 AND c.automationScore = 0.0 THEN 1 ELSE 0 END) AS needsAiOnly,
          avg(CASE WHEN actionCount > 0 THEN c.automationScore ELSE null END) AS avgScore,
          sum(actionCount) AS totalActions,
          sum(yesCount) AS totalAutomatable,
          sum(CASE WHEN inheritedCount > 0 THEN 1 ELSE 0 END) AS transitiveClassCount
        """;

    // Coverage by type query: unique source types in module and how many are mapped
    String coverageQuery =
        """
        MATCH (c:JavaClass)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        WHERE c.module = $module
          AND NOT (c.packageName CONTAINS 'castor'
                OR c.packageName CONTAINS 'persistentObjects'
                OR c.packageName CONTAINS 'businessObjects'
                OR c.packageName CONTAINS 'com4j')
          AND NOT ma.isInherited
        WITH DISTINCT ma.source AS src, ma.automatable AS auto
        RETURN count(src) AS totalTypes,
               sum(CASE WHEN auto <> 'NO' THEN 1 ELSE 0 END) AS mappedTypes
        """;

    // Top 5 NEEDS_MAPPING gaps from the recipe book
    List<String> topGaps = recipeBookRegistry.getRules().stream()
        .filter(r -> "NEEDS_MAPPING".equals(r.status()))
        .sorted(java.util.Comparator.comparingInt(RecipeRule::usageCount).reversed())
        .limit(5)
        .map(RecipeRule::source)
        .collect(Collectors.toList());

    // Execute primary query
    var result = neo4jClient
        .query(query)
        .bind(module)
        .to("module")
        .fetchAs(ModuleMigrationSummary.class)
        .mappedBy(
            (ts, record) -> {
              int totalClasses = record.get("totalClasses").asInt(0);
              int classesWithActions = record.get("classesWithActions").asInt(0);
              int fullyAutomatable = record.get("fullyAutomatable").asInt(0);
              int partiallyAutomatable = record.get("partiallyAutomatable").asInt(0);
              int needsAiOnly = record.get("needsAiOnly").asInt(0);
              double avgScore = record.get("avgScore").isNull() ? 0.0
                  : record.get("avgScore").asDouble(0.0);
              int totalActions = record.get("totalActions").asInt(0);
              int totalAutomatable = record.get("totalAutomatable").asInt(0);
              int transitiveClassCount = record.get("transitiveClassCount").asInt(0);
              return new ModuleMigrationSummary(
                  module, totalClasses, classesWithActions, fullyAutomatable,
                  partiallyAutomatable, needsAiOnly, avgScore, totalActions,
                  totalAutomatable, transitiveClassCount, 0.0, 0.0, topGaps);
            })
        .one();

    if (result.isEmpty()) {
      return new ModuleMigrationSummary(module, 0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0.0, 0.0,
          topGaps);
    }

    // Execute coverage query to fill coverageByType and coverageByUsage
    var coverageResult = neo4jClient
        .query(coverageQuery)
        .bind(module)
        .to("module")
        .fetchAs(double[].class)
        .mappedBy((ts, record) -> {
          int totalTypes = record.get("totalTypes").asInt(0);
          int mappedTypes = record.get("mappedTypes").asInt(0);
          double byType = totalTypes > 0 ? (double) mappedTypes / totalTypes : 0.0;
          return new double[]{byType};
        })
        .one();

    double coverageByType = coverageResult.map(arr -> arr[0]).orElse(0.0);

    // coverageByUsage: non-NO actions / total non-inherited actions for the module
    String usageQuery =
        """
        MATCH (c:JavaClass)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        WHERE c.module = $module
          AND NOT (c.packageName CONTAINS 'castor'
                OR c.packageName CONTAINS 'persistentObjects'
                OR c.packageName CONTAINS 'businessObjects'
                OR c.packageName CONTAINS 'com4j')
          AND NOT ma.isInherited
        RETURN count(ma) AS totalUsages,
               sum(CASE WHEN ma.automatable <> 'NO' THEN 1 ELSE 0 END) AS mappedUsages
        """;

    var usageResult = neo4jClient
        .query(usageQuery)
        .bind(module)
        .to("module")
        .fetchAs(double[].class)
        .mappedBy((ts, record) -> {
          int totalUsages = record.get("totalUsages").asInt(0);
          int mappedUsages = record.get("mappedUsages").asInt(0);
          double byUsage = totalUsages > 0 ? (double) mappedUsages / totalUsages : 0.0;
          return new double[]{byUsage};
        })
        .one();

    double coverageByUsage = usageResult.map(arr -> arr[0]).orElse(0.0);

    ModuleMigrationSummary base = result.get();
    return new ModuleMigrationSummary(
        base.module(), base.totalClasses(), base.classesWithActions(),
        base.fullyAutomatableClasses(), base.partiallyAutomatableClasses(),
        base.needsAiOnlyClasses(), base.averageAutomationScore(),
        base.totalActions(), base.totalAutomatableActions(),
        base.transitiveClassCount(), coverageByType, coverageByUsage, topGaps);
  }

  // ---------------------------------------------------------------------------
  // Migration post-processing pipeline
  // ---------------------------------------------------------------------------

  /**
   * Runs the migration post-processing pipeline after extraction and risk scoring:
   * <ol>
   *   <li>Transitive detection — finds classes that inherit from Vaadin 7 types and creates
   *       inherited {@code MigrationAction} nodes with complexity profiling.
   *   <li>Score recomputation — updates ClassNode migration properties (actionCount, automationScore).
   *   <li>Recipe book enrichment — updates usage counts and auto-discovers NEEDS_MAPPING types.
   * </ol>
   *
   * <p>This method must be called after {@code LinkingService.linkAllRelationships()} (EXTENDS edges
   * must exist) and after {@code RiskService.computeAndPersistRiskScores()}.
   */
  public void migrationPostProcessing() {
    log.info("Starting migration post-processing: transitive detection, score recompute, enrichment");
    long startMs = System.currentTimeMillis();
    int transitiveCount = detectTransitiveMigrations();
    recomputeMigrationScores();
    enrichRecipeBook();
    log.info("Migration post-processing complete: {} transitive actions created in {}ms",
        transitiveCount, System.currentTimeMillis() - startMs);
  }

  /**
   * Finds all classes that inherit from known Vaadin 7 types (via EXTENDS graph traversal) and
   * creates inherited {@code MigrationAction} nodes for them with per-class complexity profiling.
   *
   * @return the number of inherited MigrationAction nodes created or updated
   */
  int detectTransitiveMigrations() {
    // Step 1: Get known Vaadin 7 source FQNs from recipe book (excluding Flow and NEEDS_MAPPING).
    // Include com.vaadin.* (direct) AND com.alfa.* (Alfa wrapper) sources from recipe book.
    // com.alfa.* entries are loaded from the overlay added in Plan 19-01.
    List<String> vaadinSourceFqns = recipeBookRegistry.getRules().stream()
        .filter(r -> !"NEEDS_MAPPING".equals(r.status()))
        .filter(r -> (r.source().startsWith("com.vaadin.")
                          && !r.source().startsWith("com.vaadin.flow."))
                     || r.source().startsWith("com.alfa."))
        .map(RecipeRule::source)
        .distinct()
        .collect(Collectors.toList());

    if (vaadinSourceFqns.isEmpty()) {
      log.debug("No Vaadin 7 source FQNs in recipe book — skipping transitive detection");
      return 0;
    }

    // Step 2: Find transitive inheritors not already having a direct MigrationAction for the ancestor
    String transitiveQuery =
        """
        MATCH (c:JavaClass)-[:EXTENDS*1..10]->(ancestor:JavaClass)
        WHERE ancestor.fullyQualifiedName IN $vaadinSourceFqns
          AND NOT (c)-[:HAS_MIGRATION_ACTION]->(:MigrationAction {source: ancestor.fullyQualifiedName})
        RETURN c.fullyQualifiedName AS classFqn,
               ancestor.fullyQualifiedName AS ancestorFqn,
               labels(c) AS classLabels
        """;

    List<Map<String, Object>> transitiveRows = new ArrayList<>();
    neo4jClient.query(transitiveQuery)
        .bind(vaadinSourceFqns).to("vaadinSourceFqns")
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, Object> row = new java.util.HashMap<>();
          row.put("classFqn", record.get("classFqn").asString(null));
          row.put("ancestorFqn", record.get("ancestorFqn").asString(null));
          List<Object> labels = record.get("classLabels").asList(v -> (Object) v.asString(""));
          row.put("classLabels", labels);
          return row;
        })
        .all()
        .forEach(transitiveRows::add);

    log.info("Found {} transitive inheritance relationships to process", transitiveRows.size());

    MigrationConfig.TransitiveConfig config = migrationConfig.getTransitive();
    int createdCount = 0;

    for (Map<String, Object> row : transitiveRows) {
      String classFqn = (String) row.get("classFqn");
      String ancestorFqn = (String) row.get("ancestorFqn");
      @SuppressWarnings("unchecked")
      List<Object> classLabels = (List<Object>) row.get("classLabels");

      if (classFqn == null || ancestorFqn == null) continue;

      // If the matched ancestor is an Alfa* class, walk EXTENDS up to find the real Vaadin 7 type.
      // For direct com.vaadin.* ancestors, this is a no-op (returns ancestorFqn unchanged).
      String ultimateVaadinAncestor = ancestorFqn.startsWith("com.alfa.")
          ? resolveUltimateVaadinAncestor(ancestorFqn).orElse(ancestorFqn)
          : ancestorFqn;

      // Step 3: Compute complexity profile via second Cypher query
      String complexityQuery =
          """
          OPTIONAL MATCH (ancestor:JavaClass {fullyQualifiedName: $ancestorFqn})-[:DECLARES_METHOD]->(am:JavaMethod)
          WITH collect(DISTINCT am.simpleName) AS ancestorMethodNames
          OPTIONAL MATCH (c:JavaClass {fullyQualifiedName: $classFqn})-[:DECLARES_METHOD]->(cm:JavaMethod)
          WHERE cm.simpleName IN ancestorMethodNames
          WITH count(cm) AS overrideCount, ancestorMethodNames
          OPTIONAL MATCH (c:JavaClass {fullyQualifiedName: $classFqn})-[:CALLS]->(callee:JavaClass)
          WHERE callee.fullyQualifiedName STARTS WITH 'com.vaadin.'
            AND NOT callee.fullyQualifiedName STARTS WITH 'com.vaadin.flow.'
          WITH overrideCount, count(DISTINCT callee) AS ownVaadinCalls
          OPTIONAL MATCH (c2:JavaClass {fullyQualifiedName: $classFqn})-[:CALLS]->(alfaCallee:JavaClass)
          WHERE alfaCallee.fullyQualifiedName STARTS WITH 'com.alfa.'
          WITH overrideCount, ownVaadinCalls, count(DISTINCT alfaCallee) AS ownAlfaCalls
          RETURN overrideCount, ownVaadinCalls, ownAlfaCalls
          """;

      int overrideCount = 0;
      int ownVaadinCalls = 0;
      int ownAlfaCalls = 0;

      var complexityResult = neo4jClient.query(complexityQuery)
          .bind(classFqn).to("classFqn")
          .bind(ancestorFqn).to("ancestorFqn")
          .fetchAs(Map.class)
          .mappedBy((ts, record) -> {
            Map<String, Object> r = new java.util.HashMap<>();
            r.put("overrideCount", record.get("overrideCount").asInt(0));
            r.put("ownVaadinCalls", record.get("ownVaadinCalls").asInt(0));
            r.put("ownAlfaCalls", record.get("ownAlfaCalls").asInt(0));
            return r;
          })
          .one();

      if (complexityResult.isPresent()) {
        overrideCount = (int) complexityResult.get().get("overrideCount");
        ownVaadinCalls = (int) complexityResult.get().get("ownVaadinCalls");
        ownAlfaCalls = (int) complexityResult.get().get("ownAlfaCalls");
      }

      // Step 4: Check labels for VaadinDataBinding and VaadinComponent
      boolean hasOwnBinding = classLabels != null
          && classLabels.stream().anyMatch(l -> "VaadinDataBinding".equals(l.toString()));
      boolean hasOwnComponents = classLabels != null
          && classLabels.stream().anyMatch(l -> "VaadinComponent".equals(l.toString()));

      // Step 5: Compute transitiveComplexity score
      double rawScore = (overrideCount * config.getOverrideWeight())
          + (ownVaadinCalls * config.getOwnCallsWeight())
          + (ownAlfaCalls * config.getAlfaCallsWeight())
          + (hasOwnBinding ? config.getBindingWeight() : 0.0)
          + (hasOwnComponents ? config.getComponentWeight() : 0.0);
      double transitiveComplexity = Math.min(1.0, rawScore);
      boolean pureWrapper = transitiveComplexity == 0.0;
      String automatable;
      if (pureWrapper || transitiveComplexity <= config.getAiAssistedThreshold()) {
        automatable = "PARTIAL";
      } else {
        automatable = "NO";
      }

      // Step 6: Resolve target from recipe book
      String target = recipeBookRegistry.findBySource(ancestorFqn)
          .map(RecipeRule::target)
          .orElse("Manual migration required");

      String context;
      if (ancestorFqn.startsWith("com.alfa.")) {
        context = "Inherited via Alfa* intermediary " + ancestorFqn
            + " \u2192 Vaadin 7 ancestor: " + ultimateVaadinAncestor
            + (pureWrapper
                ? " (pure wrapper \u2014 mechanical wrapping)"
                : " (complex \u2014 overrides or own Alfa/Vaadin usage)");
      } else {
        context = "Inherited from " + ancestorFqn
            + (pureWrapper
                ? " (pure wrapper \u2014 mechanical wrapping)"
                : " (complex \u2014 overrides or own Vaadin usage)");
      }

      String actionId = classFqn + "#INHERITED#" + ancestorFqn;

      // Step 7: MERGE inherited MigrationAction node + HAS_MIGRATION_ACTION edge
      String mergeQuery =
          """
          MERGE (ma:MigrationAction {actionId: $actionId})
          ON CREATE SET ma.classFqn = $classFqn, ma.actionType = 'COMPLEX_REWRITE',
                        ma.source = $ancestorFqn, ma.target = $target,
                        ma.automatable = $automatable, ma.context = $context,
                        ma.isInherited = true, ma.pureWrapper = $pureWrapper,
                        ma.transitiveComplexity = $transitiveComplexity,
                        ma.vaadinAncestor = $ultimateVaadinAncestor,
                        ma.overrideCount = $overrideCount, ma.ownVaadinCalls = $ownVaadinCalls,
                        ma.ownAlfaCalls = $ownAlfaCalls
          ON MATCH SET  ma.automatable = $automatable, ma.transitiveComplexity = $transitiveComplexity,
                        ma.pureWrapper = $pureWrapper, ma.overrideCount = $overrideCount,
                        ma.ownVaadinCalls = $ownVaadinCalls, ma.ownAlfaCalls = $ownAlfaCalls,
                        ma.vaadinAncestor = $ultimateVaadinAncestor
          WITH ma
          MATCH (c:JavaClass {fullyQualifiedName: $classFqn})
          MERGE (c)-[:HAS_MIGRATION_ACTION]->(ma)
          """;

      neo4jClient.query(mergeQuery)
          .bind(actionId).to("actionId")
          .bind(classFqn).to("classFqn")
          .bind(ancestorFqn).to("ancestorFqn")
          .bind(target).to("target")
          .bind(automatable).to("automatable")
          .bind(context).to("context")
          .bind(pureWrapper).to("pureWrapper")
          .bind(transitiveComplexity).to("transitiveComplexity")
          .bind(ultimateVaadinAncestor).to("ultimateVaadinAncestor")
          .bind(overrideCount).to("overrideCount")
          .bind(ownVaadinCalls).to("ownVaadinCalls")
          .bind(ownAlfaCalls).to("ownAlfaCalls")
          .run();

      createdCount++;
    }

    log.info("Transitive detection complete: {} inherited MigrationAction nodes created/updated",
        createdCount);
    return createdCount;
  }

  /**
   * Recomputes migration-related aggregate properties on all ClassNode instances that have
   * at least one linked MigrationAction.
   */
  void recomputeMigrationScores() {
    String query =
        """
        MATCH (c:JavaClass)
        OPTIONAL MATCH (c)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        WITH c, count(ma) AS total,
             sum(CASE WHEN ma.automatable = 'YES' THEN 1 ELSE 0 END) AS yesCount,
             sum(CASE WHEN ma.automatable = 'PARTIAL' THEN 1 ELSE 0 END) AS partialCount,
             sum(CASE WHEN ma.automatable = 'NO' THEN 1 ELSE 0 END) AS noCount
        WHERE total > 0
        SET c.migrationActionCount = total,
            c.automatableActionCount = yesCount,
            c.automationScore = CASE WHEN total > 0 THEN (toFloat(yesCount) + 0.5 * partialCount) / total ELSE 0.0 END,
            c.needsAiMigration = (noCount > 0)
        """;

    neo4jClient.query(query).run();
    log.debug("recomputeMigrationScores complete");
  }

  /**
   * Enriches the recipe book by:
   * <ol>
   *   <li>Aggregating usage counts from MigrationAction nodes and updating corresponding rules.
   *   <li>Auto-discovering unmapped Vaadin 7 types and adding them as NEEDS_MAPPING/DISCOVERED entries.
   * </ol>
   *
   * <p>File I/O failures are logged as warnings and not propagated — the recipe book is a cache of
   * graph data, and stale counts until the next extraction run are acceptable.
   */
  void enrichRecipeBook() {
    // Step 1: Aggregate usage counts by source FQN (non-inherited actions only)
    String usageQuery =
        """
        MATCH (ma:MigrationAction)
        WHERE NOT ma.isInherited
        RETURN ma.source AS source, count(ma) AS usageCount
        """;

    Map<String, Integer> usageCounts = new java.util.HashMap<>();
    neo4jClient.query(usageQuery)
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, Object> r = new java.util.HashMap<>();
          r.put("source", record.get("source").asString(null));
          r.put("usageCount", record.get("usageCount").asInt(0));
          return r;
        })
        .all()
        .forEach(row -> {
          String source = (String) row.get("source");
          if (source != null) {
            usageCounts.put(source, (int) row.get("usageCount"));
          }
        });

    // Step 2: Find unmapped Vaadin 7 types (COMPLEX_REWRITE with "Unknown Vaadin 7 type" context)
    String unmappedQuery =
        """
        MATCH (ma:MigrationAction)
        WHERE ma.source STARTS WITH 'com.vaadin.'
          AND NOT ma.source STARTS WITH 'com.vaadin.flow.'
          AND ma.automatable = 'NO'
          AND ma.actionType = 'COMPLEX_REWRITE'
          AND ma.context CONTAINS 'Unknown Vaadin 7 type'
        RETURN DISTINCT ma.source AS source, count(ma) AS usageCount
        ORDER BY usageCount DESC
        """;

    Map<String, Integer> unmappedTypes = new java.util.LinkedHashMap<>();
    neo4jClient.query(unmappedQuery)
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, Object> r = new java.util.HashMap<>();
          r.put("source", record.get("source").asString(null));
          r.put("usageCount", record.get("usageCount").asInt(0));
          return r;
        })
        .all()
        .forEach(row -> {
          String source = (String) row.get("source");
          if (source != null) {
            unmappedTypes.put(source, (int) row.get("usageCount"));
          }
        });

    // Step 3: Update recipe book in memory
    List<RecipeRule> currentRules = recipeBookRegistry.getRules();
    List<RecipeRule> updatedRules = new ArrayList<>(currentRules);

    // Update usage counts for existing rules
    for (int i = 0; i < updatedRules.size(); i++) {
      RecipeRule rule = updatedRules.get(i);
      if (rule.source() != null && usageCounts.containsKey(rule.source())) {
        int newCount = usageCounts.get(rule.source());
        updatedRules.set(i, new RecipeRule(
            rule.id(), rule.category(), rule.source(), rule.target(),
            rule.actionType(), rule.automatable(), rule.context(),
            rule.migrationSteps(), rule.status(), newCount,
            rule.discoveredAt(), rule.isBase()));
      }
    }

    // Add DISCOVERED rules for unmapped types not already in the list
    java.util.Set<String> existingSources = updatedRules.stream()
        .map(RecipeRule::source)
        .collect(Collectors.toSet());

    long discoveredRuleCount = updatedRules.stream()
        .filter(r -> "DISCOVERED".equals(r.category()))
        .count();

    for (Map.Entry<String, Integer> entry : unmappedTypes.entrySet()) {
      String unmappedSource = entry.getKey();
      if (!existingSources.contains(unmappedSource)) {
        discoveredRuleCount++;
        String discoveredId = String.format("DISC-%03d", discoveredRuleCount);
        RecipeRule discoveredRule = new RecipeRule(
            discoveredId, "DISCOVERED", unmappedSource, null,
            "COMPLEX_REWRITE", "NO", null,
            List.of(), "NEEDS_MAPPING", entry.getValue(),
            LocalDate.now().toString(), false);
        updatedRules.add(discoveredRule);
        existingSources.add(unmappedSource);
        log.info("Auto-discovered unmapped Vaadin 7 type: {} (usageCount={})",
            unmappedSource, entry.getValue());
      }
    }

    // Step 4: Write back via registry (non-fatal on IOException)
    try {
      recipeBookRegistry.updateAndWrite(updatedRules);
      log.info("enrichRecipeBook complete: {} rules written ({} usage count updates, {} discovered)",
          updatedRules.size(), usageCounts.size(), unmappedTypes.size());
    } catch (IOException e) {
      log.warn("enrichRecipeBook: failed to write recipe book to disk (non-fatal): {}",
          e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Walks the EXTENDS chain from a given class FQN upward until it finds the first ancestor
   * whose FQN starts with {@code com.vaadin.} (excluding {@code com.vaadin.flow.}).
   *
   * <p>Used to resolve the true Vaadin 7 ancestor for Alfa* intermediary nodes. For example,
   * given {@code com.alfa.ui.AlfaButton} (which EXTENDS {@code com.vaadin.ui.Button}), returns
   * {@code Optional.of("com.vaadin.ui.Button")}.
   *
   * @param startFqn FQN of an Alfa* class or any class in the EXTENDS chain
   * @return the ultimate com.vaadin.* ancestor FQN, or empty if none found within 10 hops
   */
  private Optional<String> resolveUltimateVaadinAncestor(String startFqn) {
    String query =
        """
        MATCH (start:JavaClass {fullyQualifiedName: $startFqn})-[:EXTENDS*1..10]->(ancestor:JavaClass)
        WHERE ancestor.fullyQualifiedName STARTS WITH 'com.vaadin.'
          AND NOT ancestor.fullyQualifiedName STARTS WITH 'com.vaadin.flow.'
        RETURN ancestor.fullyQualifiedName AS ancestorFqn
        LIMIT 1
        """;

    return neo4jClient.query(query)
        .bind(startFqn).to("startFqn")
        .fetchAs(String.class)
        .mappedBy((ts, record) -> record.get("ancestorFqn").asString(null))
        .one()
        .filter(java.util.Objects::nonNull);
  }


  /**
   * Loads all migration actions for a class from Neo4j via HAS_MIGRATION_ACTION edges.
   *
   * <p>Extended in Phase 17 to include transitive fields (isInherited, vaadinAncestor,
   * pureWrapper, transitiveComplexity, overrideCount, ownVaadinCalls) and to enrich
   * each entry with migrationSteps from the recipe book.
   *
   * @param classFqn fully qualified class name
   * @return list of migration action entries (empty if none found)
   */
  private List<MigrationActionEntry> loadActionsFromGraph(String classFqn) {
    String query =
        """
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        RETURN ma.actionType AS actionType, ma.source AS source, ma.target AS target,
               ma.automatable AS automatable, ma.context AS context,
               COALESCE(ma.isInherited, false) AS isInherited,
               ma.vaadinAncestor AS vaadinAncestor,
               ma.pureWrapper AS pureWrapper,
               ma.transitiveComplexity AS transitiveComplexity,
               ma.overrideCount AS overrideCount,
               ma.ownVaadinCalls AS ownVaadinCalls
        """;

    return new ArrayList<>(
        neo4jClient
            .query(query)
            .bind(classFqn)
            .to("fqn")
            .fetchAs(MigrationActionEntry.class)
            .mappedBy(
                (ts, record) -> {
                  String source = record.get("source").asString(null);
                  // Enrich with migrationSteps from recipe book
                  List<String> steps = source != null
                      ? recipeBookRegistry.findBySource(source)
                          .map(RecipeRule::migrationSteps)
                          .orElse(List.of())
                      : List.of();
                  boolean isInherited = record.get("isInherited").asBoolean(false);
                  String vaadinAncestor = record.get("vaadinAncestor").isNull()
                      ? null : record.get("vaadinAncestor").asString();
                  Boolean pureWrapper = record.get("pureWrapper").isNull()
                      ? null : record.get("pureWrapper").asBoolean();
                  Double transitiveComplexity = record.get("transitiveComplexity").isNull()
                      ? null : record.get("transitiveComplexity").asDouble();
                  Integer overrideCount = record.get("overrideCount").isNull()
                      ? null : record.get("overrideCount").asInt();
                  Integer ownVaadinCalls = record.get("ownVaadinCalls").isNull()
                      ? null : record.get("ownVaadinCalls").asInt();
                  // inheritedFrom = source (direct ancestor — com.alfa.* or com.vaadin.*)
                  // vaadinAncestor = ma.vaadinAncestor (ultimate com.vaadin.* ancestor)
                  // For direct com.vaadin.* actions the values are identical.
                  // For Alfa-mediated actions, inheritedFrom is the Alfa* class, vaadinAncestor is the Vaadin 7 type.
                  return new MigrationActionEntry(
                      record.get("actionType").asString(null),
                      source,
                      record.get("target").asString(null),
                      record.get("automatable").asString(null),
                      record.get("context").isNull() ? null : record.get("context").asString(),
                      isInherited,
                      source,           // inheritedFrom = direct ancestor (source FQN in the action node)
                      vaadinAncestor,   // vaadinAncestor = ultimate com.vaadin.* ancestor
                      pureWrapper,
                      transitiveComplexity,
                      overrideCount,
                      ownVaadinCalls,
                      steps);
                })
            .all());
  }

  /**
   * Loads the source file path for a class from Neo4j.
   *
   * @param classFqn fully qualified class name
   * @return absolute source file path, or null if not found
   */
  private String loadSourceFilePath(String classFqn) {
    return neo4jClient
        .query("MATCH (c:JavaClass {fullyQualifiedName: $fqn}) RETURN c.sourceFilePath AS path")
        .bind(classFqn)
        .to("fqn")
        .fetchAs(String.class)
        .mappedBy((ts, record) -> record.get("path").asString(null))
        .one()
        .orElse(null);
  }

  /**
   * Loads all classes with migration actions in a module, grouped with their actions.
   *
   * @param module module name
   * @return list of rows, each with classFqn, sourceFilePath, and actions list
   */
  private List<Map<String, Object>> loadActionsForModule(String module) {
    String query =
        """
        MATCH (c:JavaClass)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        WHERE c.module = $module
        RETURN c.fullyQualifiedName AS classFqn, c.sourceFilePath AS sourceFilePath,
               collect({actionType: ma.actionType, source: ma.source, target: ma.target,
                        automatable: ma.automatable, context: ma.context}) AS actions
        """;

    List<Map<String, Object>> results = new ArrayList<>();
    neo4jClient
        .query(query)
        .bind(module)
        .to("module")
        .fetchAs(Map.class)
        .mappedBy(
            (ts, record) -> {
              List<Object> actionList =
                  record
                      .get("actions")
                      .asList(
                          v ->
                              (Object)
                                  Map.of(
                                      "actionType", v.get("actionType").asString(""),
                                      "source", v.get("source").asString(""),
                                      "target", v.get("target").asString(""),
                                      "automatable", v.get("automatable").asString(""),
                                      "context",
                                          v.get("context").isNull()
                                              ? ""
                                              : v.get("context").asString("")));
              Map<String, Object> row = new java.util.HashMap<>();
              row.put("classFqn", record.get("classFqn").asString(""));
              row.put("sourceFilePath", record.get("sourceFilePath").asString(""));
              row.put("actions", actionList);
              return row;
            })
        .all()
        .forEach(results::add);
    return results;
  }

  /**
   * Parses Java source files using the current JVM classpath for type resolution.
   *
   * <p>Type resolution is required for {@link ChangeType} to match and transform Vaadin 7 type
   * usages. The JVM classpath typically includes the necessary Vaadin 7 JARs when running inside
   * the Spring Boot application context (where Vaadin 7 is a test or compile-time dependency).
   *
   * @param paths the source files to parse
   * @param projectRoot the project root for relative path computation in the LST
   * @return list of parsed SourceFiles; empty if parsing fails
   */
  private List<SourceFile> parseWithJvmClasspath(List<Path> paths, Path projectRoot) {
    InMemoryExecutionContext ctx =
        new InMemoryExecutionContext(t -> log.warn("Parse error (file will be skipped): {}", t.getMessage()));

    try {
      List<Path> jvmClasspathJars =
          Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
              .map(Path::of)
              .filter(p -> p.toFile().isFile() && p.toString().endsWith(".jar"))
              .collect(Collectors.toList());

      JavaParser.Builder<? extends JavaParser, ?> builder =
          JavaParser.fromJavaVersion()
              .typeCache(new JavaTypeCache())
              .logCompilationWarningsAndErrors(false);

      if (!jvmClasspathJars.isEmpty()) {
        builder = builder.classpath(jvmClasspathJars);
      }

      List<SourceFile> result = builder.build().parse(paths, projectRoot, ctx).toList();
      log.debug("Parsed {}/{} source files for recipe execution", result.size(), paths.size());
      return result;
    } catch (Exception e) {
      log.warn("Unexpected error during parsing for recipe execution: {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * Resolves a source file path to an absolute {@link Path}.
   *
   * <p>If the stored path is already absolute and exists, it is returned as-is. If it is relative,
   * it is resolved against the configured source root from {@link SourceAccessService}. This
   * handles the case where extraction stored relative LST source paths (relative to the project
   * root used during parsing).
   *
   * @param storedPath the path string from the Neo4j ClassNode (may be relative)
   * @return absolute Path for the source file
   */
  private Path resolveSourcePath(String storedPath) {
    Path p = Path.of(storedPath);
    if (p.isAbsolute()) {
      return p;
    }
    String configuredRoot = sourceAccessService.getResolvedSourceRoot();
    if (configuredRoot != null && !configuredRoot.isBlank()) {
      return Path.of(configuredRoot, storedPath);
    }
    return p;
  }

  /**
   * Builds a composite OpenRewrite recipe from a list of automatable migration actions.
   *
   * <p>Supports {@code CHANGE_TYPE} and {@code CHANGE_PACKAGE} action types. Other action types
   * are logged as warnings and skipped — they represent future extension points.
   *
   * @param actions list of automatable migration actions (automatable=YES)
   * @return composite recipe, or null if no supported action types exist in the list
   */
  Recipe buildCompositeRecipe(List<MigrationActionEntry> actions) {
    List<Recipe> recipes = new ArrayList<>();

    for (MigrationActionEntry action : actions) {
      if (action.source() == null || action.target() == null) {
        continue;
      }
      switch (action.actionType()) {
        case "CHANGE_TYPE" ->
            // ignoreDefinition=true: do not modify the target type's own definition
            recipes.add(new ChangeType(action.source(), action.target(), true));
        case "CHANGE_PACKAGE" ->
            // recursive=false: only rename the exact package, not sub-packages
            recipes.add(new ChangePackage(action.source(), action.target(), false));
        default ->
            log.debug(
                "Skipping unsupported recipe action type '{}' for source '{}'",
                action.actionType(),
                action.source());
      }
    }

    if (recipes.isEmpty()) {
      return null;
    }

    return new CompositeRecipe(recipes);
  }
}
