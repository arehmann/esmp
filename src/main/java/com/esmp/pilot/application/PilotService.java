package com.esmp.pilot.application;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.WithPayloadSelectorFactory.include;

import com.esmp.graph.api.ValidationReport;
import com.esmp.graph.validation.ValidationService;
import com.esmp.pilot.api.ModuleRecommendation;
import com.esmp.pilot.api.PilotCheck;
import com.esmp.pilot.api.PilotValidationReport;
import com.esmp.vector.config.VectorConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.ScrollPoints;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Orchestration service for the golden module pilot.
 *
 * <p>Provides two primary capabilities:
 * <ol>
 *   <li>{@link #recommendModules()} — scores all modules in the graph by Vaadin 7 density,
 *       risk diversity, and size appropriateness, returning the top 5 candidates.
 *   <li>{@link #validateModule(String)} — runs global graph validation + module-specific metrics
 *       + Qdrant chunk count + 5 pilot pass/fail checks + markdown report generation.
 * </ol>
 *
 * <p>All Neo4j queries use parameterized Cypher with {@code .bind(value).to("param")} to prevent
 * injection and ensure correct parameter handling. The module name is never interpolated directly
 * into Cypher strings in service methods (only the PilotValidationQueryRegistry uses hardcoded
 * 'pilot' for static violation queries).
 */
@Service
public class PilotService {

  private static final Logger log = LoggerFactory.getLogger(PilotService.class);

  private final Neo4jClient neo4jClient;
  private final QdrantClient qdrantClient;
  private final VectorConfig vectorConfig;
  private final ValidationService validationService;

  public PilotService(
      Neo4jClient neo4jClient,
      QdrantClient qdrantClient,
      VectorConfig vectorConfig,
      ValidationService validationService) {
    this.neo4jClient = neo4jClient;
    this.qdrantClient = qdrantClient;
    this.vectorConfig = vectorConfig;
    this.validationService = validationService;
  }

  // ---------------------------------------------------------------------------
  // Module recommendation
  // ---------------------------------------------------------------------------

  /**
   * Scores and ranks all modules in the graph for pilot suitability.
   *
   * <p>Scoring formula (weights sum to 1.0):
   * <ul>
   *   <li>0.4 — Vaadin 7 density: fraction of classes with VaadinView/VaadinComponent/VaadinDataBinding labels
   *   <li>0.3 — risk diversity: standard deviation of enhancedRiskScore (higher = more variety)
   *   <li>0.3 — size appropriateness: 1.0 if 15-40 classes, 0.0 otherwise
   * </ul>
   *
   * @return top 5 modules ranked by composite score (descending), or empty list if no modules with >= 5 classes
   */
  public List<ModuleRecommendation> recommendModules() {
    log.info("Computing pilot module recommendations");

    String cypher = """
        MATCH (c:JavaClass)
        WHERE c.module IS NOT NULL AND c.module <> ''
        WITH c.module AS module,
             count(c) AS classCount,
             sum(CASE WHEN ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])
                      THEN 1 ELSE 0 END) AS vaadin7Count,
             avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgRisk,
             stDev(coalesce(c.enhancedRiskScore, 0.0)) AS riskDiversity
        WHERE classCount >= 5
        WITH module, classCount, vaadin7Count, avgRisk, riskDiversity,
             (0.4 * toFloat(vaadin7Count) / classCount
            + 0.3 * CASE WHEN riskDiversity IS NULL THEN 0.0 ELSE riskDiversity END
            + 0.3 * toFloat(CASE WHEN classCount >= 15 AND classCount <= 40 THEN 1 ELSE 0 END)) AS score
        RETURN module, classCount, vaadin7Count, avgRisk, riskDiversity, score
        ORDER BY score DESC
        LIMIT 5
        """;

    Collection<Map<String, Object>> rows = neo4jClient.query(cypher).fetch().all();

    List<ModuleRecommendation> recommendations = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      String moduleName = (String) row.get("module");
      long classCount = toLong(row.get("classCount"));
      long vaadin7Count = toLong(row.get("vaadin7Count"));
      double avgRisk = toDouble(row.get("avgRisk"));
      double riskDiv = toDouble(row.get("riskDiversity"));
      double score = toDouble(row.get("score"));

      String rationale = buildRationale(moduleName, classCount, vaadin7Count, avgRisk, riskDiv);
      recommendations.add(new ModuleRecommendation(
          moduleName,
          (int) classCount,
          (int) vaadin7Count,
          avgRisk,
          riskDiv,
          score,
          rationale));
    }

    log.info("Module recommendation complete: {} candidates found", recommendations.size());
    return recommendations;
  }

  // ---------------------------------------------------------------------------
  // Module validation
  // ---------------------------------------------------------------------------

  /**
   * Runs comprehensive pilot validation for the specified module.
   *
   * <p>Validation sequence:
   * <ol>
   *   <li>Global graph validation via all registered ValidationQueryRegistry beans
   *   <li>Module-specific Neo4j metrics (class count, Vaadin 7 count, risk scores, business terms)
   *   <li>Qdrant chunk count for the module (via scroll-based count)
   *   <li>5 pilot-specific pass/fail checks
   *   <li>Markdown report generation
   * </ol>
   *
   * @param moduleName the module name to validate (e.g., "pilot")
   * @return comprehensive validation report with all metrics and markdown summary
   */
  public PilotValidationReport validateModule(String moduleName) {
    log.info("Starting pilot validation for module='{}'", moduleName);

    // Step 1: Global graph validation
    ValidationReport graphValidation = validationService.runAllValidations();
    log.info("Graph validation complete: {} pass, {} warn, {} fail",
        graphValidation.passCount(), graphValidation.warnCount(), graphValidation.errorCount());

    // Step 2: Module-specific Neo4j metrics
    int classCount = queryCount("MATCH (c:JavaClass) WHERE c.module = $module RETURN count(c) AS count", moduleName);
    int vaadin7ClassCount = queryCount("""
        MATCH (c:JavaClass)
        WHERE c.module = $module
          AND ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])
        RETURN count(c) AS count
        """, moduleName);

    double avgEnhancedRiskScore = queryAvg(
        "MATCH (c:JavaClass) WHERE c.module = $module RETURN avg(coalesce(c.enhancedRiskScore, 0.0)) AS avg",
        moduleName);

    int businessTermCount = queryCount(
        "MATCH (c:JavaClass)-[:USES_TERM]->(bt:BusinessTerm) WHERE c.module = $module RETURN count(DISTINCT bt) AS count",
        moduleName);

    double domainTermCoveragePercent = queryDomainCoverage(moduleName);

    // Step 3: Qdrant chunk count for the module
    long chunkCount = countModuleChunks(moduleName);
    log.info("Module '{}' metrics: classes={}, vaadin7={}, chunks={}, terms={}",
        moduleName, classCount, vaadin7ClassCount, chunkCount, businessTermCount);

    // Step 4: Pilot-specific checks
    List<PilotCheck> pilotChecks = runPilotChecks(
        moduleName, classCount, vaadin7ClassCount, chunkCount, businessTermCount, avgEnhancedRiskScore);

    // Step 5: Generate markdown report
    String markdownReport = generateMarkdownReport(
        moduleName, graphValidation, classCount, vaadin7ClassCount, chunkCount,
        avgEnhancedRiskScore, businessTermCount, domainTermCoveragePercent, pilotChecks);

    return new PilotValidationReport(
        Instant.now().toString(),
        moduleName,
        graphValidation,
        classCount,
        vaadin7ClassCount,
        chunkCount,
        avgEnhancedRiskScore,
        businessTermCount,
        domainTermCoveragePercent,
        pilotChecks,
        markdownReport);
  }

  // ---------------------------------------------------------------------------
  // Pilot checks
  // ---------------------------------------------------------------------------

  private List<PilotCheck> runPilotChecks(
      String moduleName,
      int classCount,
      int vaadin7ClassCount,
      long chunkCount,
      int businessTermCount,
      double avgEnhancedRiskScore) {

    List<PilotCheck> checks = new ArrayList<>();

    // Check 1: Module has >= 15 classes
    if (classCount >= 15) {
      checks.add(new PilotCheck(
          "Module has >= 15 classes",
          "PASS",
          "Found " + classCount + " classes in module '" + moduleName + "'"));
    } else {
      checks.add(new PilotCheck(
          "Module has >= 15 classes",
          "FAIL",
          "Found only " + classCount + " classes (expected >= 15) in module '" + moduleName + "'"));
    }

    // Check 2: Module has Vaadin 7 classes
    if (vaadin7ClassCount > 0) {
      checks.add(new PilotCheck(
          "Module has Vaadin 7 classes",
          "PASS",
          "Found " + vaadin7ClassCount + " Vaadin 7 labeled classes (VaadinView/VaadinComponent/VaadinDataBinding)"));
    } else {
      checks.add(new PilotCheck(
          "Module has Vaadin 7 classes",
          "FAIL",
          "No Vaadin 7 labeled classes found in module '" + moduleName + "'"));
    }

    // Check 3: Module has vector chunks
    if (chunkCount > 0) {
      checks.add(new PilotCheck(
          "Module has vector chunks",
          "PASS",
          "Found " + chunkCount + " vector chunks indexed in Qdrant for module '" + moduleName + "'"));
    } else {
      checks.add(new PilotCheck(
          "Module has vector chunks",
          "WARN",
          "No vector chunks found for module '" + moduleName + "' — run POST /api/vector/index first"));
    }

    // Check 4: Module has business terms
    if (businessTermCount > 0) {
      checks.add(new PilotCheck(
          "Module has business terms",
          "PASS",
          "Found " + businessTermCount + " distinct business terms linked to module classes via USES_TERM"));
    } else {
      checks.add(new PilotCheck(
          "Module has business terms",
          "WARN",
          "No business terms found for module '" + moduleName + "' — LexiconVisitor may not have run"));
    }

    // Check 5: All risk scores populated
    int missingRiskCount = queryCount(
        "MATCH (c:JavaClass) WHERE c.module = $module AND (c.enhancedRiskScore IS NULL OR c.enhancedRiskScore = 0.0) RETURN count(c) AS count",
        moduleName);

    if (missingRiskCount == 0) {
      checks.add(new PilotCheck(
          "All risk scores populated",
          "PASS",
          "All " + classCount + " classes have non-zero enhancedRiskScore"));
    } else {
      checks.add(new PilotCheck(
          "All risk scores populated",
          "WARN",
          missingRiskCount + " classes have missing or zero enhancedRiskScore — RiskService may not have run"));
    }

    return checks;
  }

  // ---------------------------------------------------------------------------
  // Markdown report generation
  // ---------------------------------------------------------------------------

  private String generateMarkdownReport(
      String moduleName,
      ValidationReport graphValidation,
      int classCount,
      int vaadin7ClassCount,
      long chunkCount,
      double avgEnhancedRiskScore,
      int businessTermCount,
      double domainTermCoveragePercent,
      List<PilotCheck> pilotChecks) {

    StringBuilder sb = new StringBuilder();
    sb.append("# Pilot Validation Report: ").append(moduleName).append("\n");
    sb.append("Generated: ").append(Instant.now()).append("\n\n");

    sb.append("## Module Overview\n\n");
    sb.append("- Classes: ").append(classCount).append("\n");
    sb.append("- Vaadin 7 classes: ").append(vaadin7ClassCount).append("\n");
    sb.append("- Vector chunks: ").append(chunkCount).append("\n");
    sb.append("- Business terms: ").append(businessTermCount).append("\n");
    sb.append(String.format("- Domain term coverage: %.1f%%%n", domainTermCoveragePercent));
    sb.append(String.format("- Average enhanced risk score: %.3f%n%n", avgEnhancedRiskScore));

    sb.append("## Graph Validation\n\n");
    sb.append("- Total queries: ").append(graphValidation.results().size()).append("\n");
    sb.append("- Passed: ").append(graphValidation.passCount()).append("\n");
    sb.append("- Warnings: ").append(graphValidation.warnCount()).append("\n");
    sb.append("- Errors: ").append(graphValidation.errorCount()).append("\n\n");

    sb.append("## Pilot Checks\n\n");
    sb.append("| Check | Status | Detail |\n");
    sb.append("|-------|--------|--------|\n");
    for (PilotCheck check : pilotChecks) {
      sb.append("| ").append(check.name())
          .append(" | ").append(check.status())
          .append(" | ").append(check.detail())
          .append(" |\n");
    }
    sb.append("\n");

    sb.append("## Migration Readiness Assessment\n\n");
    sb.append("- Vaadin 7 class count: ").append(vaadin7ClassCount).append("\n");
    sb.append(String.format("- Average risk score: %.3f%n", avgEnhancedRiskScore));
    sb.append(String.format("- Domain term coverage: %.1f%%%n", domainTermCoveragePercent));
    sb.append("\n");

    long passCount = pilotChecks.stream().filter(c -> "PASS".equals(c.status())).count();
    long failCount = pilotChecks.stream().filter(c -> "FAIL".equals(c.status())).count();
    long warnCount = pilotChecks.stream().filter(c -> "WARN".equals(c.status())).count();

    if (failCount == 0) {
      sb.append("**Readiness: READY** — All critical checks passed (");
      sb.append(passCount).append(" pass, ").append(warnCount).append(" warn)\n");
    } else {
      sb.append("**Readiness: NOT READY** — ").append(failCount);
      sb.append(" critical checks failed. Resolve before proceeding.\n");
    }

    return sb.toString();
  }

  // ---------------------------------------------------------------------------
  // Internal Neo4j and Qdrant helpers
  // ---------------------------------------------------------------------------

  private int queryCount(String cypher, String moduleName) {
    Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
        .bind(moduleName).to("module")
        .fetch().all();
    if (rows.isEmpty()) return 0;
    Object val = rows.iterator().next().get("count");
    return (int) toLong(val);
  }

  private double queryAvg(String cypher, String moduleName) {
    Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
        .bind(moduleName).to("module")
        .fetch().all();
    if (rows.isEmpty()) return 0.0;
    Object val = rows.iterator().next().get("avg");
    return toDouble(val);
  }

  private double queryDomainCoverage(String moduleName) {
    String cypher = """
        MATCH (c:JavaClass) WHERE c.module = $module
        WITH count(c) AS total
        OPTIONAL MATCH (c2:JavaClass)-[:USES_TERM]->()
        WHERE c2.module = $module
        WITH total, count(DISTINCT c2) AS withTerms
        RETURN CASE WHEN total = 0 THEN 0.0
                    ELSE toFloat(withTerms) / total * 100.0 END AS coverage
        """;
    Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
        .bind(moduleName).to("module")
        .fetch().all();
    if (rows.isEmpty()) return 0.0;
    Object val = rows.iterator().next().get("coverage");
    return toDouble(val);
  }

  /**
   * Counts Qdrant points for the given module using scroll-based pagination.
   * Uses a filter on the "module" payload field.
   */
  private long countModuleChunks(String moduleName) {
    Filter moduleFilter = Filter.newBuilder()
        .addMust(matchKeyword("module", moduleName))
        .build();

    long count = 0;
    boolean hasMore = true;
    io.qdrant.client.grpc.Points.PointId nextOffset = null;

    while (hasMore) {
      try {
        ScrollPoints.Builder scrollBuilder = ScrollPoints.newBuilder()
            .setCollectionName(vectorConfig.getCollectionName())
            .setFilter(moduleFilter)
            .setLimit(500)
            .setWithPayload(include(List.of("classFqn")));
        if (nextOffset != null) {
          scrollBuilder.setOffset(nextOffset);
        }

        var result = qdrantClient.scrollAsync(scrollBuilder.build()).get(30, TimeUnit.SECONDS);
        count += result.getResultList().size();

        if (result.hasNextPageOffset()) {
          nextOffset = result.getNextPageOffset();
        } else {
          hasMore = false;
        }
      } catch (Exception e) {
        log.error("Failed to count chunks for module '{}': {}", moduleName, e.getMessage(), e);
        hasMore = false;
      }
    }

    return count;
  }

  // ---------------------------------------------------------------------------
  // Utility helpers
  // ---------------------------------------------------------------------------

  private static long toLong(Object val) {
    if (val instanceof Long l) return l;
    if (val instanceof Number n) return n.longValue();
    return 0L;
  }

  private static double toDouble(Object val) {
    if (val instanceof Double d) return d;
    if (val instanceof Number n) return n.doubleValue();
    return 0.0;
  }

  private static String buildRationale(
      String moduleName, long classCount, long vaadin7Count, double avgRisk, double riskDiversity) {
    StringBuilder sb = new StringBuilder();
    if (vaadin7Count > 0) {
      sb.append(String.format("High Vaadin 7 density (%d/%d classes)", vaadin7Count, classCount));
    } else {
      sb.append("No Vaadin 7 classes detected");
    }
    sb.append(String.format(", risk diversity=%.3f", riskDiversity));
    if (classCount >= 15 && classCount <= 40) {
      sb.append(", ideal size range (").append(classCount).append(" classes)");
    } else {
      sb.append(", size=").append(classCount).append(" (outside ideal 15-40)");
    }
    return sb.toString();
  }
}
