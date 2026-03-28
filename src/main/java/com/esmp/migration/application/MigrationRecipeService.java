package com.esmp.migration.application;

import com.esmp.extraction.parser.JavaSourceParser;
import com.esmp.migration.api.BatchMigrationResult;
import com.esmp.migration.api.MigrationActionEntry;
import com.esmp.migration.api.MigrationPlan;
import com.esmp.migration.api.MigrationResult;
import com.esmp.migration.api.ModuleMigrationSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.config.CompositeRecipe;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.ChangePackage;
import org.openrewrite.java.ChangeType;
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

  public MigrationRecipeService(Neo4jClient neo4jClient, JavaSourceParser javaSourceParser) {
    this.neo4jClient = neo4jClient;
    this.javaSourceParser = javaSourceParser;
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

    Path sourcePath = Path.of(sourceFilePath);
    if (!Files.exists(sourcePath)) {
      log.warn("Source file '{}' does not exist on disk for class '{}'", sourceFilePath, classFqn);
      return MigrationResult.noChanges(classFqn);
    }

    Path projectRoot = sourcePath.getParent();

    List<SourceFile> lsts = javaSourceParser.parse(List.of(sourcePath), projectRoot, null);
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
   * @param module the module name (third segment of the package name)
   * @return summary with class counts, action counts, and average automation score
   */
  public ModuleMigrationSummary getModuleSummary(String module) {
    String query =
        """
        MATCH (c:JavaClass)
        WHERE split(c.packageName, '.')[2] = $module
        OPTIONAL MATCH (c)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        WITH c, count(ma) AS actionCount,
             sum(CASE WHEN ma.automatable = 'YES' THEN 1 ELSE 0 END) AS yesCount
        RETURN
          count(c) AS totalClasses,
          sum(CASE WHEN actionCount > 0 THEN 1 ELSE 0 END) AS classesWithActions,
          sum(CASE WHEN c.automationScore = 1.0 THEN 1 ELSE 0 END) AS fullyAutomatable,
          sum(CASE WHEN c.automationScore > 0.0 AND c.automationScore < 1.0 THEN 1 ELSE 0 END) AS partiallyAutomatable,
          sum(CASE WHEN actionCount > 0 AND c.automationScore = 0.0 THEN 1 ELSE 0 END) AS needsAiOnly,
          avg(CASE WHEN actionCount > 0 THEN c.automationScore ELSE null END) AS avgScore,
          sum(actionCount) AS totalActions,
          sum(yesCount) AS totalAutomatable
        """;

    return neo4jClient
        .query(query)
        .bind(module)
        .to("module")
        .fetchAs(ModuleMigrationSummary.class)
        .mappedBy(
            (ts, record) ->
                new ModuleMigrationSummary(
                    module,
                    record.get("totalClasses").asInt(0),
                    record.get("classesWithActions").asInt(0),
                    record.get("fullyAutomatable").asInt(0),
                    record.get("partiallyAutomatable").asInt(0),
                    record.get("needsAiOnly").asInt(0),
                    record.get("avgScore").isNull() ? 0.0 : record.get("avgScore").asDouble(0.0),
                    record.get("totalActions").asInt(0),
                    record.get("totalAutomatable").asInt(0)))
        .one()
        .orElse(
            new ModuleMigrationSummary(module, 0, 0, 0, 0, 0, 0.0, 0, 0));
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Loads all migration actions for a class from Neo4j via HAS_MIGRATION_ACTION edges.
   *
   * @param classFqn fully qualified class name
   * @return list of migration action entries (empty if none found)
   */
  private List<MigrationActionEntry> loadActionsFromGraph(String classFqn) {
    String query =
        """
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
        RETURN ma.actionType AS actionType, ma.source AS source, ma.target AS target,
               ma.automatable AS automatable, ma.context AS context
        """;

    return new ArrayList<>(
        neo4jClient
            .query(query)
            .bind(classFqn)
            .to("fqn")
            .fetchAs(MigrationActionEntry.class)
            .mappedBy(
                (ts, record) ->
                    new MigrationActionEntry(
                        record.get("actionType").asString(null),
                        record.get("source").asString(null),
                        record.get("target").asString(null),
                        record.get("automatable").asString(null),
                        record.get("context").isNull() ? null : record.get("context").asString()))
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
        WHERE split(c.packageName, '.')[2] = $module
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
