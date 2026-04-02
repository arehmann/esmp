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
 * Service for computing and querying structural and domain-aware risk metrics.
 *
 * <p>Provides two kinds of operations:
 * <ol>
 *   <li><b>Compute:</b> {@link #computeAndPersistRiskScores()} runs Cypher queries to compute
 *       fan-in/out from DEPENDS_ON edges, the composite structural risk score, and four domain
 *       dimensions (domain criticality, security sensitivity, financial involvement, business rule
 *       density) for every JavaClass node. Must be called after
 *       {@code LinkingService.linkAllRelationships()} so that DEPENDS_ON and USES_TERM edges exist.
 *   <li><b>Query:</b> {@link #getHeatmap} and {@link #getClassDetail} read the pre-computed scores
 *       from the graph and return them as response records.
 * </ol>
 *
 * <p>The Phase 6 structural risk score formula is:
 * <pre>
 *   structuralRiskScore = w_complexity * log(1 + complexitySum)
 *                       + w_fanIn     * log(1 + fanIn)
 *                       + w_fanOut    * log(1 + fanOut)
 *                       + w_dbWrites  * (hasDbWrites ? 1 : 0)
 * </pre>
 *
 * <p>The Phase 7 enhanced risk score formula combines all 8 dimensions:
 * <pre>
 *   enhancedRiskScore = domainComplexity  * log(1 + complexitySum)
 *                     + domainFanIn       * log(1 + fanIn)
 *                     + domainFanOut      * log(1 + fanOut)
 *                     + domainDbWrites    * (hasDbWrites ? 1 : 0)
 *                     + domainCriticality * domainCriticality
 *                     + securitySensitivity * securitySensitivity
 *                     + financialInvolvement * financialInvolvement
 *                     + businessRuleDensity  * businessRuleDensity
 * </pre>
 *
 * Weights are configurable via {@link RiskWeightConfig}.
 */
@Service
public class RiskService {

  private static final Logger log = LoggerFactory.getLogger(RiskService.class);

  /** Known stereotype labels assigned by the extraction pipeline. */
  private static final Set<String> STEREOTYPE_LABELS = Set.of(
      "Service", "Repository", "VaadinView", "VaadinComponent", "VaadinDataBinding",
      "Controller", "RestController", "Component", "Configuration");

  // ---------- Phase 7: domain scoring keyword constants ----------

  private static final List<String> SECURITY_NAME_KEYWORDS = List.of(
      "auth", "login", "security", "encrypt", "cipher", "credential",
      "token", "permission", "acl", "oauth", "jwt", "password", "secret",
      "session", "principal", "role", "privilege");

  private static final List<String> SECURITY_ANNOTATION_KEYWORDS = List.of(
      "secured", "preauthorize", "postauthorize", "rolesallowed",
      "permitall", "denyall", "withsecuritycontext");

  private static final List<String> SECURITY_PKG_KEYWORDS = List.of(
      "security", "auth", "crypto", "encryption", "authentication", "authorization");

  private static final List<String> FINANCIAL_NAME_KEYWORDS = List.of(
      "payment", "invoice", "billing", "ledger", "account", "transaction",
      "currency", "tax", "price", "fee", "charge", "refund", "balance",
      "credit", "debit", "wallet", "payable", "receivable");

  private static final List<String> FINANCIAL_PKG_KEYWORDS = List.of(
      "payment", "billing", "finance", "accounting", "ledger", "transaction");

  private static final List<String> FINANCIAL_TERM_KEYWORDS = List.of(
      "payment", "invoice", "billing", "ledger", "transaction", "currency",
      "tax", "price", "fee", "charge", "refund", "balance", "credit", "debit");

  /** Graduated scoring: class simple-name keyword hit contributes this weight. */
  private static final double NAME_HIT_WEIGHT = 0.3;

  /** Graduated scoring: security-annotation hit contributes this weight. */
  private static final double ANNOT_HIT_WEIGHT = 0.5;

  /** Graduated scoring: bonus when both name AND annotation match. */
  private static final double BOTH_HIT_BONUS = 0.2;

  /** Graduated scoring: package keyword match boosts the score by this amount. */
  private static final double PKG_HIT_BOOST = 0.2;

  /** Graduated scoring: USES_TERM financial term match boosts the score by this amount. */
  private static final double TERM_HIT_BOOST = 0.2;

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

    log.info("Computing domain criticality from USES_TERM edges...");
    computeDomainCriticality();

    log.info("Computing security sensitivity heuristics...");
    computeSecuritySensitivity();

    log.info("Computing financial involvement heuristics...");
    computeFinancialInvolvement();

    log.info("Computing business rule density from DEFINES_RULE edges...");
    computeBusinessRuleDensity();

    log.info("Computing enhanced composite risk scores...");
    computeEnhancedRiskScore();

    log.info("Domain-aware risk score computation complete.");
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

  /**
   * Step 3 (DRISK-01): Compute domain criticality for each JavaClass from USES_TERM edges.
   *
   * <p>Classes linked to at least one High-criticality BusinessTerm score 1.0.
   * Classes linked only to Medium-criticality terms score 0.5.
   * Classes with no USES_TERM edges score 0.0.
   */
  private void computeDomainCriticality() {
    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             size([(c)-[:USES_TERM]->(t:BusinessTerm) WHERE t.criticality = 'High' | t]) AS highCount,
             size([(c)-[:USES_TERM]->(t:BusinessTerm) WHERE t.criticality = 'Medium' | t]) AS medCount
        SET c.domainCriticality = CASE
            WHEN highCount > 0 THEN 1.0
            WHEN medCount > 0 THEN 0.5
            ELSE 0.0 END
        """;
    neo4jClient.query(cypher).run();
  }

  /**
   * Step 4 (DRISK-02): Compute security sensitivity score for each JavaClass.
   *
   * <p>Graduated heuristic: name keyword hit contributes {@code NAME_HIT_WEIGHT} (0.3),
   * security annotation hit contributes {@code ANNOT_HIT_WEIGHT} (0.5), both together add a
   * {@code BOTH_HIT_BONUS} (0.2), and package keyword match boosts by {@code PKG_HIT_BOOST} (0.2).
   * Final score is clamped to [0.0, 1.0].
   */
  private void computeSecuritySensitivity() {
    String namePattern = buildPattern(SECURITY_NAME_KEYWORDS);
    String annotPattern = buildPattern(SECURITY_ANNOTATION_KEYWORDS);
    String pkgPattern = buildPattern(SECURITY_PKG_KEYWORDS);

    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             CASE WHEN toLower(coalesce(c.simpleName, '')) =~ $namePattern THEN 1 ELSE 0 END AS nameHit,
             CASE WHEN ANY(a IN coalesce(c.annotations, []) WHERE toLower(a) =~ $annotPattern) THEN 1 ELSE 0 END AS annotHit,
             CASE WHEN toLower(coalesce(c.packageName, '')) =~ $pkgPattern THEN 1 ELSE 0 END AS pkgHit
        WITH c, nameHit, annotHit, pkgHit,
             nameHit  * $nameWeight +
             annotHit * $annotWeight +
             CASE WHEN nameHit = 1 AND annotHit = 1 THEN $bothBonus ELSE 0.0 END +
             pkgHit   * $pkgBoost AS rawScore
        SET c.securitySensitivity = CASE
            WHEN toFloat(nameHit) + toFloat(annotHit) = 0.0 AND pkgHit = 0 THEN 0.0
            WHEN rawScore > 1.0 THEN 1.0
            ELSE rawScore
            END
        """;
    neo4jClient.query(cypher)
        .bindAll(Map.of(
            "namePattern",  namePattern,
            "annotPattern", annotPattern,
            "pkgPattern",   pkgPattern,
            "nameWeight",   NAME_HIT_WEIGHT,
            "annotWeight",  ANNOT_HIT_WEIGHT,
            "bothBonus",    BOTH_HIT_BONUS,
            "pkgBoost",     PKG_HIT_BOOST))
        .run();
  }

  /**
   * Step 5 (DRISK-03): Compute financial involvement score for each JavaClass.
   *
   * <p>Same graduated heuristic as security sensitivity, plus a USES_TERM boost when the class
   * references a BusinessTerm whose termId or displayName matches financial keywords.
   * Financial annotations are not domain-specific in Java, so annotation matching is skipped
   * (annotPattern uses a never-match regex).
   */
  private void computeFinancialInvolvement() {
    String namePattern = buildPattern(FINANCIAL_NAME_KEYWORDS);
    String pkgPattern  = buildPattern(FINANCIAL_PKG_KEYWORDS);
    String termPattern = buildPattern(FINANCIAL_TERM_KEYWORDS);
    String neverMatch  = "(?!x)x";  // regex that never matches — no financial-specific Java annotations

    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             CASE WHEN toLower(coalesce(c.simpleName, '')) =~ $namePattern THEN 1 ELSE 0 END AS nameHit,
             CASE WHEN ANY(a IN coalesce(c.annotations, []) WHERE toLower(a) =~ $annotPattern) THEN 1 ELSE 0 END AS annotHit,
             CASE WHEN toLower(coalesce(c.packageName, '')) =~ $pkgPattern THEN 1 ELSE 0 END AS pkgHit,
             CASE WHEN EXISTS {
                 MATCH (c)-[:USES_TERM]->(t:BusinessTerm)
                 WHERE toLower(coalesce(t.termId, '')) =~ $termPattern
                    OR toLower(coalesce(t.displayName, '')) =~ $termPattern
             } THEN 1 ELSE 0 END AS termHit
        WITH c, nameHit, annotHit, pkgHit, termHit,
             nameHit  * $nameWeight +
             annotHit * $annotWeight +
             CASE WHEN nameHit = 1 AND annotHit = 1 THEN $bothBonus ELSE 0.0 END +
             pkgHit   * $pkgBoost +
             termHit  * $termBoost AS rawScore
        SET c.financialInvolvement = CASE
            WHEN toFloat(nameHit) + toFloat(annotHit) + toFloat(pkgHit) + toFloat(termHit) = 0.0 THEN 0.0
            WHEN rawScore > 1.0 THEN 1.0
            ELSE rawScore
            END
        """;
    neo4jClient.query(cypher)
        .bindAll(Map.of(
            "namePattern",  namePattern,
            "annotPattern", neverMatch,
            "pkgPattern",   pkgPattern,
            "termPattern",  termPattern,
            "nameWeight",   NAME_HIT_WEIGHT,
            "annotWeight",  ANNOT_HIT_WEIGHT,
            "bothBonus",    BOTH_HIT_BONUS,
            "pkgBoost",     PKG_HIT_BOOST,
            "termBoost",    TERM_HIT_BOOST))
        .run();
  }

  /**
   * Step 6 (DRISK-04): Compute business rule density as log(1 + count of DEFINES_RULE edges).
   *
   * <p>Zero for classes with no outgoing DEFINES_RULE edges; increases logarithmically for
   * highly rule-dense classes. Pattern comprehension avoids OPTIONAL MATCH grouping complexity.
   */
  private void computeBusinessRuleDensity() {
    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             size([(c)-[:DEFINES_RULE]->(t) | t]) AS ruleCount
        SET c.businessRuleDensity = log(1.0 + ruleCount)
        """;
    neo4jClient.query(cypher).run();
  }

  /**
   * Step 7 (DRISK-05): Compute the enhanced composite risk score combining all 8 dimensions.
   *
   * <p>Uses raw structural properties (complexitySum, fanIn, fanOut, hasDbWrites) re-weighted by
   * the Phase 7 domain-enhanced weights, plus the four domain dimension scores. Does NOT use
   * {@code structuralRiskScore} as input to avoid double-weighting.
   */
  private void computeEnhancedRiskScore() {
    String cypher = """
        MATCH (c:JavaClass)
        SET c.enhancedRiskScore = (
            $wComplexity  * log(1.0 + coalesce(c.complexitySum, 0)) +
            $wFanIn       * log(1.0 + coalesce(c.fanIn, 0)) +
            $wFanOut      * log(1.0 + coalesce(c.fanOut, 0)) +
            $wDbWrites    * CASE WHEN coalesce(c.hasDbWrites, false) THEN 1.0 ELSE 0.0 END +
            $wDomainCrit  * coalesce(c.domainCriticality, 0.0) +
            $wSecurity    * coalesce(c.securitySensitivity, 0.0) +
            $wFinancial   * coalesce(c.financialInvolvement, 0.0) +
            $wRuleDensity * coalesce(c.businessRuleDensity, 0.0)
        )
        """;
    neo4jClient.query(cypher)
        .bindAll(Map.of(
            "wComplexity",  riskWeightConfig.getDomainComplexity(),
            "wFanIn",       riskWeightConfig.getDomainFanIn(),
            "wFanOut",      riskWeightConfig.getDomainFanOut(),
            "wDbWrites",    riskWeightConfig.getDomainDbWrites(),
            "wDomainCrit",  riskWeightConfig.getDomainCriticality(),
            "wSecurity",    riskWeightConfig.getSecuritySensitivity(),
            "wFinancial",   riskWeightConfig.getFinancialInvolvement(),
            "wRuleDensity", riskWeightConfig.getBusinessRuleDensity()))
        .run();
  }

  /**
   * Builds a Cypher-compatible regex pattern from a list of keywords.
   *
   * <p>The resulting pattern matches any string containing at least one of the keywords as a
   * substring (case-insensitive when used with {@code toLower()} in the Cypher query).
   *
   * @param keywords list of lowercase keyword strings
   * @return regex string suitable for {@code =~} in Cypher (e.g., {@code ".*(auth|login|jwt).*"})
   */
  private String buildPattern(List<String> keywords) {
    return ".*(" + String.join("|", keywords) + ").*";
  }

  // ---------------------------------------------------------------------------
  // Query phase
  // ---------------------------------------------------------------------------

  /**
   * Returns a list of JavaClass nodes sorted by descending risk score, with optional filtering by
   * module, package prefix, stereotype, result limit, and sort field.
   *
   * @param module      optional JavaModule name to scope the query
   * @param packageName optional package name prefix (classes in that package or sub-packages)
   * @param stereotype  optional stereotype label (e.g., "Service", "Repository")
   * @param limit       maximum number of results (default 50 when called from controller)
   * @param sortBy      sort field: {@code "enhanced"} (default) sorts by enhancedRiskScore DESC;
   *                    {@code "structural"} sorts by structuralRiskScore DESC
   * @return list of heatmap entries sorted by descending risk score
   */
  public List<RiskHeatmapEntry> getHeatmap(
      String module, String packageName, String stereotype, int limit, String sortBy) {

    // Validate sortBy to one of two known values — safe because orderByProp is never user-interpolated
    // directly into Cypher; it's one of two hardcoded Java strings.
    String orderByProp = "structural".equals(sortBy) ? "structuralRiskScore" : "enhancedRiskScore";
    String notNullProp = "structural".equals(sortBy) ? "structuralRiskScore" : "enhancedRiskScore";

    StringBuilder cypher = new StringBuilder();
    Map<String, Object> params = new HashMap<>();

    if (module != null && !module.isBlank()) {
      cypher.append("""
          MATCH (m:JavaModule {moduleName: $module})-[:CONTAINS_PACKAGE]->(p:JavaPackage)
                -[:CONTAINS_CLASS]->(c:JavaClass)
          WHERE c.""" + notNullProp + " IS NOT NULL\n");
      params.put("module", module);
    } else {
      cypher.append("MATCH (c:JavaClass)\nWHERE c." + notNullProp + " IS NOT NULL\n");
    }

    // Only show classes with Vaadin 7 migration actions.
    // Filter out javax→jakarta, non-UI packages, and other non-Vaadin migration noise.
    // Uses a subquery to check that at least one migration action has a com.vaadin source.
    cypher.append("  AND c.migrationActionCount > 0\n");
    cypher.append("  AND EXISTS { MATCH (c)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction) "
        + "WHERE ma.source STARTS WITH 'com.vaadin' }\n");

    if (packageName != null && !packageName.isBlank()) {
      cypher.append("  AND c.packageName STARTS WITH $packageName\n");
      params.put("packageName", packageName);
    }

    if (stereotype != null && !stereotype.isBlank()) {
      cypher.append("  AND ANY(label IN labels(c) WHERE label = $stereotype)\n");
      params.put("stereotype", stereotype);
    }

    cypher.append("RETURN c\nORDER BY c." + orderByProp + " DESC\nLIMIT $limit\n");
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
        extractStereotypeLabels(node),
        node.get("domainCriticality").asDouble(0.0),
        node.get("securitySensitivity").asDouble(0.0),
        node.get("financialInvolvement").asDouble(0.0),
        node.get("businessRuleDensity").asDouble(0.0),
        node.get("enhancedRiskScore").asDouble(0.0));
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
        node.get("domainCriticality").asDouble(0.0),
        node.get("securitySensitivity").asDouble(0.0),
        node.get("financialInvolvement").asDouble(0.0),
        node.get("businessRuleDensity").asDouble(0.0),
        node.get("enhancedRiskScore").asDouble(0.0),
        node.get("businessDescription").isNull() ? null : node.get("businessDescription").asString(),
        node.get("curatedClassDescription").isNull() ? null : node.get("curatedClassDescription").asString(),
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
