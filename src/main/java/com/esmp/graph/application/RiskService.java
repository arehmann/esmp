package com.esmp.graph.application;

import com.esmp.extraction.config.RiskWeightConfig;
import com.esmp.graph.api.MethodComplexityEntry;
import com.esmp.graph.api.RiskDetailResponse;
import com.esmp.graph.api.RiskHeatmapEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.neo4j.driver.types.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Service for computing and querying structural risk metrics.
 *
 * <p>Provides two kinds of operations:
 * <ol>
 *   <li><b>Compute:</b> {@link #computeAndPersistRiskScores()} runs two Cypher queries to compute
 *       fan-in/out from existing DEPENDS_ON edges and then the composite structural risk score for
 *       every JavaClass node. Must be called after {@code LinkingService.linkAllRelationships()} so
 *       that DEPENDS_ON edges exist.
 *   <li><b>Query:</b> {@link #getHeatmap} and {@link #getClassDetail} read the pre-computed scores
 *       from the graph and return them as response records.
 * </ol>
 *
 * <p>The composite risk score formula is:
 * <pre>
 *   score = w_complexity * log(1 + complexitySum)
 *         + w_fanIn     * log(1 + fanIn)
 *         + w_fanOut    * log(1 + fanOut)
 *         + w_dbWrites  * (hasDbWrites ? 1 : 0)
 * </pre>
 * Weights are configurable via {@link RiskWeightConfig} and default to (0.4, 0.2, 0.2, 0.2).
 */
@Service
public class RiskService {

  private static final Logger log = LoggerFactory.getLogger(RiskService.class);

  /** Known stereotype labels assigned by the extraction pipeline. */
  private static final Set<String> STEREOTYPE_LABELS = Set.of(
      "Service", "Repository", "VaadinView", "VaadinComponent", "VaadinDataBinding",
      "Controller", "RestController", "Component", "Configuration");

  private final Neo4jClient neo4jClient;
  private final RiskWeightConfig riskWeightConfig;

  public RiskService(Neo4jClient neo4jClient, RiskWeightConfig riskWeightConfig) {
    this.neo4jClient = neo4jClient;
    this.riskWeightConfig = riskWeightConfig;
  }

  // ---------------------------------------------------------------------------
  // Compute phase
  // ---------------------------------------------------------------------------

  /**
   * Computes fan-in/fan-out from DEPENDS_ON edges and then derives the composite structural risk
   * score for every JavaClass node. Updates all nodes in-place via Cypher SET.
   *
   * <p>This method MUST be called after {@code LinkingService.linkAllRelationships()} because the
   * DEPENDS_ON edges used for fan-in/out computation are created by the linking pass.
   */
  public void computeAndPersistRiskScores() {
    log.info("Computing fan-in/out from DEPENDS_ON edges for all JavaClass nodes...");
    computeFanInOut();

    log.info("Computing composite structural risk scores...");
    computeStructuralRiskScore();

    log.info("Risk score computation complete.");
  }

  /**
   * Step 1: Compute fan-in and fan-out from DEPENDS_ON edges using pattern comprehension.
   * A single Cypher query counts all edges in one pass and sets both properties atomically.
   */
  private void computeFanInOut() {
    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             size([(other)-[:DEPENDS_ON]->(c) | other]) AS fi,
             size([(c)-[:DEPENDS_ON]->(other) | other]) AS fo
        SET c.fanIn = fi, c.fanOut = fo
        """;
    neo4jClient.query(cypher).run();
  }

  /**
   * Step 2: Compute composite risk score using log normalization.
   * All weights are bound as parameters so no string interpolation is needed.
   */
  private void computeStructuralRiskScore() {
    String cypher = """
        MATCH (c:JavaClass)
        SET c.structuralRiskScore = (
            $wComplexity * log(1.0 + coalesce(c.complexitySum, 0)) +
            $wFanIn      * log(1.0 + coalesce(c.fanIn, 0)) +
            $wFanOut     * log(1.0 + coalesce(c.fanOut, 0)) +
            $wDbWrites   * CASE WHEN coalesce(c.hasDbWrites, false) THEN 1.0 ELSE 0.0 END
        )
        """;
    neo4jClient.query(cypher)
        .bindAll(Map.of(
            "wComplexity", riskWeightConfig.getComplexity(),
            "wFanIn", riskWeightConfig.getFanIn(),
            "wFanOut", riskWeightConfig.getFanOut(),
            "wDbWrites", riskWeightConfig.getDbWrites()))
        .run();
  }

  // ---------------------------------------------------------------------------
  // Query phase
  // ---------------------------------------------------------------------------

  /**
   * Returns a list of JavaClass nodes sorted by descending structural risk score, with optional
   * filtering by module, package prefix, stereotype, and result limit.
   *
   * @param module      optional JavaModule name to scope the query
   * @param packageName optional package name prefix (classes in that package or sub-packages)
   * @param stereotype  optional stereotype label (e.g., "Service", "Repository")
   * @param limit       maximum number of results (default 50 when called from controller)
   * @return list of heatmap entries sorted by descending structuralRiskScore
   */
  public List<RiskHeatmapEntry> getHeatmap(
      String module, String packageName, String stereotype, int limit) {

    StringBuilder cypher = new StringBuilder();
    Map<String, Object> params = new HashMap<>();

    if (module != null && !module.isBlank()) {
      cypher.append("""
          MATCH (m:JavaModule {moduleName: $module})-[:CONTAINS_PACKAGE]->(p:JavaPackage)
                -[:CONTAINS_CLASS]->(c:JavaClass)
          WHERE c.structuralRiskScore IS NOT NULL
          """);
      params.put("module", module);
    } else {
      cypher.append("""
          MATCH (c:JavaClass)
          WHERE c.structuralRiskScore IS NOT NULL
          """);
    }

    if (packageName != null && !packageName.isBlank()) {
      cypher.append("  AND c.packageName STARTS WITH $packageName\n");
      params.put("packageName", packageName);
    }

    if (stereotype != null && !stereotype.isBlank()) {
      cypher.append("  AND ANY(label IN labels(c) WHERE label = $stereotype)\n");
      params.put("stereotype", stereotype);
    }

    cypher.append("""
        RETURN c
        ORDER BY c.structuralRiskScore DESC
        LIMIT $limit
        """);
    params.put("limit", (long) limit);

    Collection<RiskHeatmapEntry> results = neo4jClient.query(cypher.toString())
        .bindAll(params)
        .fetchAs(RiskHeatmapEntry.class)
        .mappedBy((typeSystem, record) -> mapNodeToHeatmapEntry(record.get("c").asNode()))
        .all();

    return new ArrayList<>(results);
  }

  /**
   * Returns the full risk detail for a single class by FQN, including per-method complexity.
   *
   * @param fqn fully qualified class name
   * @return Optional containing the detail response, or empty if not found
   */
  public Optional<RiskDetailResponse> getClassDetail(String fqn) {
    String cypher = """
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        OPTIONAL MATCH (c)-[:DECLARES_METHOD]->(m:JavaMethod)
        RETURN c, collect(m) AS methods
        """;

    return neo4jClient.query(cypher)
        .bindAll(Map.of("fqn", fqn))
        .fetchAs(RiskDetailResponse.class)
        .mappedBy((typeSystem, record) -> {
          Node classNode = record.get("c").asNode();
          List<MethodComplexityEntry> methodEntries = record.get("methods").asList(v -> {
            if (v.isNull()) {
              return null;
            }
            Node m = v.asNode();
            return new MethodComplexityEntry(
                m.get("methodId").asString(""),
                m.get("simpleName").asString(""),
                (int) m.get("cyclomaticComplexity").asLong(0L),
                m.get("parameterTypes").asList(pv -> pv.asString(""))
            );
          }).stream()
              .filter(e -> e != null && e.methodId() != null && !e.methodId().isEmpty())
              .collect(Collectors.toList());

          return mapNodeToDetailResponse(classNode, methodEntries);
        })
        .one();
  }

  // ---------------------------------------------------------------------------
  // Mapping helpers
  // ---------------------------------------------------------------------------

  private RiskHeatmapEntry mapNodeToHeatmapEntry(Node node) {
    return new RiskHeatmapEntry(
        node.get("fullyQualifiedName").asString(""),
        node.get("simpleName").asString(""),
        node.get("packageName").asString(""),
        (int) node.get("complexitySum").asLong(0L),
        (int) node.get("complexityMax").asLong(0L),
        (int) node.get("fanIn").asLong(0L),
        (int) node.get("fanOut").asLong(0L),
        node.get("hasDbWrites").asBoolean(false),
        (int) node.get("dbWriteCount").asLong(0L),
        node.get("structuralRiskScore").asDouble(0.0),
        extractStereotypeLabels(node));
  }

  private RiskDetailResponse mapNodeToDetailResponse(Node node, List<MethodComplexityEntry> methods) {
    return new RiskDetailResponse(
        node.get("fullyQualifiedName").asString(""),
        node.get("simpleName").asString(""),
        node.get("packageName").asString(""),
        (int) node.get("complexitySum").asLong(0L),
        (int) node.get("complexityMax").asLong(0L),
        (int) node.get("fanIn").asLong(0L),
        (int) node.get("fanOut").asLong(0L),
        node.get("hasDbWrites").asBoolean(false),
        (int) node.get("dbWriteCount").asLong(0L),
        node.get("structuralRiskScore").asDouble(0.0),
        extractStereotypeLabels(node),
        methods);
  }

  /**
   * Extracts known stereotype labels from the node's label set.
   *
   * @param node the Neo4j node
   * @return list of stereotype labels present on the node
   */
  private List<String> extractStereotypeLabels(Node node) {
    List<String> result = new ArrayList<>();
    for (String label : node.labels()) {
      if (STEREOTYPE_LABELS.contains(label)) {
        result.add(label);
      }
    }
    return result;
  }
}
