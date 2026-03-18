package com.esmp.dashboard.application;

import com.esmp.dashboard.api.BusinessTermSummary;
import com.esmp.dashboard.api.ClassDetail;
import com.esmp.dashboard.api.LexiconCoverage;
import com.esmp.dashboard.api.ModuleDependencyEdge;
import com.esmp.dashboard.api.ModuleSummary;
import com.esmp.dashboard.api.RiskCluster;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Aggregation service providing all data needed by the governance dashboard UI.
 *
 * <p>All six methods execute Neo4j Cypher aggregation queries via {@link Neo4jClient}. No
 * Java-side aggregation loops are used — all grouping, counting, averaging, and ordering happen
 * inside the Cypher query so that Neo4j's execution engine can push work down to the graph
 * storage layer.
 *
 * <p>Vaadin 7 detection follows the canonical labels() pattern established in Phase 9:
 * {@code ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])}
 * rather than checking the {@code vaadin7Detected} property, because the property is set on
 * vector chunks (CodeChunk), not on JavaClass graph nodes.
 *
 * <p>Methods covered:
 * <ul>
 *   <li>{@link #getModuleSummaries()} — DASH-01: per-module V7 density + heatmap score
 *   <li>{@link #getLexiconCoverage()} — DASH-06: total / curated term counts
 *   <li>{@link #getRiskClusters()} — DASH-05: per-module avg/max/highRisk
 *   <li>{@link #getModuleDependencyEdges()} — DASH-03: cross-module DEPENDS_ON weight
 *   <li>{@link #getClassesInModule(String)} — DASH-02: class-level drill-down
 *   <li>{@link #getBusinessTermGraph()} — DASH-04: business terms with linked class FQNs
 * </ul>
 */
@Service
public class DashboardService {

  private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

  private final Neo4jClient neo4jClient;

  public DashboardService(Neo4jClient neo4jClient) {
    this.neo4jClient = neo4jClient;
  }

  // ---------------------------------------------------------------------------
  // DASH-01: Module summaries with V7 density and heatmap score
  // ---------------------------------------------------------------------------

  /**
   * Returns per-module summaries including Vaadin 7 class count, V7 percentage, heatmap score,
   * average enhanced risk, and high-risk class count.
   *
   * <p>Results are ordered by {@code vaadin7Pct DESC} so the modules with the most Vaadin 7
   * surface area appear first in the dashboard overview.
   *
   * @return list of module summaries, ordered by Vaadin 7 percentage descending
   */
  public List<ModuleSummary> getModuleSummaries() {
    log.debug("Querying module summaries");

    String cypher = """
        MATCH (c:JavaClass)
        WHERE c.module IS NOT NULL AND c.module <> ''
        WITH c.module AS module,
             count(c) AS classCount,
             sum(CASE WHEN ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])
                      THEN 1 ELSE 0 END) AS vaadin7Count,
             avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgEnhancedRisk,
             sum(CASE WHEN coalesce(c.enhancedRiskScore, 0.0) > 0.7 THEN 1 ELSE 0 END) AS highRiskCount
        RETURN module, classCount, vaadin7Count,
               toFloat(vaadin7Count) / classCount AS vaadin7Pct,
               (toFloat(vaadin7Count) / classCount) * avgEnhancedRisk AS heatmapScore,
               avgEnhancedRisk, highRiskCount
        ORDER BY vaadin7Pct DESC
        """;

    Collection<ModuleSummary> results = neo4jClient.query(cypher)
        .fetchAs(ModuleSummary.class)
        .mappedBy((typeSystem, record) -> new ModuleSummary(
            record.get("module").asString(""),
            (int) record.get("classCount").asLong(0L),
            (int) record.get("vaadin7Count").asLong(0L),
            record.get("vaadin7Pct").asDouble(0.0),
            record.get("heatmapScore").asDouble(0.0),
            record.get("avgEnhancedRisk").asDouble(0.0),
            (int) record.get("highRiskCount").asLong(0L)))
        .all();

    return new ArrayList<>(results);
  }

  // ---------------------------------------------------------------------------
  // DASH-06: Lexicon coverage summary
  // ---------------------------------------------------------------------------

  /**
   * Returns the overall lexicon coverage: total business terms extracted, how many have been
   * curated by a human, and the coverage percentage.
   *
   * @return lexicon coverage record, with {@code total=0} and {@code coveragePct=0.0} if the graph
   *         contains no BusinessTerm nodes
   */
  public LexiconCoverage getLexiconCoverage() {
    log.debug("Querying lexicon coverage");

    String cypher = """
        MATCH (t:BusinessTerm)
        RETURN count(t) AS total,
               sum(CASE WHEN t.curated = true THEN 1 ELSE 0 END) AS curated
        """;

    return neo4jClient.query(cypher)
        .fetchAs(LexiconCoverage.class)
        .mappedBy((typeSystem, record) -> {
          long total = record.get("total").asLong(0L);
          long curated = record.get("curated").asLong(0L);
          double coveragePct = total > 0 ? (double) curated / total * 100.0 : 0.0;
          return new LexiconCoverage((int) total, (int) curated, coveragePct);
        })
        .one()
        .orElse(new LexiconCoverage(0, 0, 0.0));
  }

  // ---------------------------------------------------------------------------
  // DASH-05: Risk clusters per module
  // ---------------------------------------------------------------------------

  /**
   * Returns per-module risk cluster data: class count, average enhanced risk score, maximum
   * enhanced risk score, and count of classes above the high-risk threshold (0.7).
   *
   * <p>Results are ordered by {@code avgRisk DESC} so the most at-risk modules appear first.
   *
   * @return list of risk clusters, ordered by average risk descending
   */
  public List<RiskCluster> getRiskClusters() {
    log.debug("Querying risk clusters");

    String cypher = """
        MATCH (c:JavaClass)
        WHERE c.module IS NOT NULL AND c.module <> ''
        WITH c.module AS module,
             count(c) AS classCount,
             avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgRisk,
             max(coalesce(c.enhancedRiskScore, 0.0)) AS maxRisk,
             sum(CASE WHEN coalesce(c.enhancedRiskScore, 0.0) > 0.7 THEN 1 ELSE 0 END) AS highRiskCount
        RETURN module, classCount, avgRisk, maxRisk, highRiskCount
        ORDER BY avgRisk DESC
        """;

    Collection<RiskCluster> results = neo4jClient.query(cypher)
        .fetchAs(RiskCluster.class)
        .mappedBy((typeSystem, record) -> new RiskCluster(
            record.get("module").asString(""),
            (int) record.get("classCount").asLong(0L),
            record.get("avgRisk").asDouble(0.0),
            record.get("maxRisk").asDouble(0.0),
            (int) record.get("highRiskCount").asLong(0L)))
        .all();

    return new ArrayList<>(results);
  }

  // ---------------------------------------------------------------------------
  // DASH-03: Cross-module dependency edges
  // ---------------------------------------------------------------------------

  /**
   * Returns aggregated cross-module dependency edges derived from DEPENDS_ON relationships.
   *
   * <p>Only edges where the source and target modules differ are returned. The weight is the
   * number of individual class-to-class DEPENDS_ON edges between the two modules.
   * Results are capped at 200 edges and ordered by weight descending.
   *
   * @return list of module dependency edges, ordered by weight descending (max 200)
   */
  public List<ModuleDependencyEdge> getModuleDependencyEdges() {
    log.debug("Querying module dependency edges");

    String cypher = """
        MATCH (c1:JavaClass)-[:DEPENDS_ON]->(c2:JavaClass)
        WHERE c1.module IS NOT NULL AND c2.module IS NOT NULL AND c1.module <> c2.module
        WITH c1.module AS sourceModule, c2.module AS targetModule, count(*) AS edgeWeight
        RETURN sourceModule, targetModule, edgeWeight
        ORDER BY edgeWeight DESC LIMIT 200
        """;

    Collection<ModuleDependencyEdge> results = neo4jClient.query(cypher)
        .fetchAs(ModuleDependencyEdge.class)
        .mappedBy((typeSystem, record) -> new ModuleDependencyEdge(
            record.get("sourceModule").asString(""),
            record.get("targetModule").asString(""),
            (int) record.get("edgeWeight").asLong(0L)))
        .all();

    return new ArrayList<>(results);
  }

  // ---------------------------------------------------------------------------
  // DASH-02: Class-level drill-down within a module
  // ---------------------------------------------------------------------------

  /**
   * Returns class-level details for all classes in the given module.
   *
   * <p>Each result includes the class FQN, simple name, enhanced risk score, all non-JavaClass
   * labels (stereotypes such as Service, Repository, VaadinView), and intra-module DEPENDS_ON
   * targets (only dependencies within the same module are returned).
   *
   * @param module the module name to drill down into
   * @return list of class details for the specified module
   */
  public List<ClassDetail> getClassesInModule(String module) {
    log.debug("Querying classes in module={}", module);

    String cypher = """
        MATCH (c:JavaClass)
        WHERE c.module = $module
        OPTIONAL MATCH (c)-[:DEPENDS_ON]->(dep:JavaClass)
        WHERE dep.module = $module
        RETURN c.fullyQualifiedName AS fqn,
               c.simpleName AS simpleName,
               coalesce(c.enhancedRiskScore, 0.0) AS riskScore,
               [l IN labels(c) WHERE l <> 'JavaClass'] AS labels,
               collect(DISTINCT dep.fullyQualifiedName) AS dependsOn
        """;

    Collection<ClassDetail> results = neo4jClient.query(cypher)
        .bind(module).to("module")
        .fetchAs(ClassDetail.class)
        .mappedBy((typeSystem, record) -> new ClassDetail(
            record.get("fqn").asString(""),
            record.get("simpleName").asString(""),
            record.get("riskScore").asDouble(0.0),
            record.get("labels").asList(v -> v.asString("")),
            record.get("dependsOn").asList(v -> v.asString(""))))
        .all();

    return new ArrayList<>(results);
  }

  // ---------------------------------------------------------------------------
  // DASH-04: Business term graph
  // ---------------------------------------------------------------------------

  /**
   * Returns business terms with all classes that reference them via USES_TERM or DEFINES_RULE.
   *
   * <p>Results are ordered by usage count descending and capped at 100 terms so the most
   * important terms appear first in the concept graph view.
   *
   * @return list of business term summaries with linked class FQNs (max 100)
   */
  public List<BusinessTermSummary> getBusinessTermGraph() {
    log.debug("Querying business term graph");

    String cypher = """
        MATCH (t:BusinessTerm)
        OPTIONAL MATCH (c:JavaClass)-[:USES_TERM|DEFINES_RULE]->(t)
        WHERE c IS NOT NULL
        RETURN t.termId AS termId,
               t.displayName AS displayName,
               t.criticality AS criticality,
               t.curated AS curated,
               coalesce(t.usageCount, 0) AS usageCount,
               collect(DISTINCT c.fullyQualifiedName) AS classFqns
        ORDER BY usageCount DESC LIMIT 100
        """;

    Collection<BusinessTermSummary> results = neo4jClient.query(cypher)
        .fetchAs(BusinessTermSummary.class)
        .mappedBy((typeSystem, record) -> new BusinessTermSummary(
            record.get("termId").asString(""),
            record.get("displayName").asString(""),
            record.get("criticality").isNull() ? null : record.get("criticality").asString(""),
            !record.get("curated").isNull() && record.get("curated").asBoolean(false),
            record.get("classFqns").asList(v -> v.asString(""))))
        .all();

    return new ArrayList<>(results);
  }
}
