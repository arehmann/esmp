package com.esmp.graph.application;

import com.esmp.extraction.model.BusinessTermNode;
import com.esmp.extraction.persistence.BusinessTermNodeRepository;
import com.esmp.graph.api.BusinessTermResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Service for lexicon CRUD operations against BusinessTerm nodes in the graph.
 *
 * <p>Provides list/filter, single-term detail (with USES_TERM related classes), and curation
 * update operations. All read operations use Neo4jClient for flexible Cypher queries. Write
 * operations use Neo4jClient Cypher to apply the curated-guard update pattern.
 */
@Service
public class LexiconService {

  private static final Logger log = LoggerFactory.getLogger(LexiconService.class);

  private final Neo4jClient neo4jClient;
  private final BusinessTermNodeRepository businessTermNodeRepository;

  public LexiconService(
      Neo4jClient neo4jClient,
      BusinessTermNodeRepository businessTermNodeRepository) {
    this.neo4jClient = neo4jClient;
    this.businessTermNodeRepository = businessTermNodeRepository;
  }

  /**
   * Returns all business terms as {@link BusinessTermNode} entities for in-memory grid binding.
   *
   * @return all persisted BusinessTerm nodes
   */
  public List<BusinessTermNode> findAll() {
    return new ArrayList<>(businessTermNodeRepository.findAll());
  }

  /**
   * Returns all business terms with optional filtering by criticality, curation status, and
   * search substring. For the list view, {@code relatedClassFqns} is always empty.
   *
   * @param criticality optional filter: "High", "Medium", or "Low"
   * @param curated     optional filter: true for curated terms only, false for auto-extracted only
   * @param search      optional case-insensitive substring matched against termId or displayName
   * @param sourceType  optional filter by source type (e.g., "CLASS_NAME", "ENUM", "JAVADOC")
   * @return list of matching terms as response records
   */
  public List<BusinessTermResponse> findByFilters(
      String criticality, Boolean curated, String search, String sourceType, Integer limit) {
    return findByFilters(criticality, curated, search, sourceType, null, null, false, limit);
  }

  /**
   * Returns all business terms with optional filtering including uiRole and domainArea.
   *
   * @param criticality optional filter: "High", "Medium", or "Low"
   * @param curated     optional filter: true for curated terms only
   * @param search      optional case-insensitive substring on termId or displayName
   * @param sourceType  optional filter by source type
   * @param uiRole      optional filter by UI role (LABEL, MESSAGE, TOOLTIP, etc.)
   * @param domainArea  optional filter by domain area (ORDER_MANAGEMENT, COMMON, etc.)
   * @param nlsOnly     if true, only return NLS-sourced terms (sourceType starts with NLS_)
   * @param limit       max results (default 500)
   */
  public List<BusinessTermResponse> findByFilters(
      String criticality, Boolean curated, String search, String sourceType,
      String uiRole, String domainArea, boolean nlsOnly, Integer limit) {
    StringBuilder cypher = new StringBuilder("""
        MATCH (t:BusinessTerm)
        WHERE 1=1
        """);

    List<String> conditions = new ArrayList<>();
    Map<String, Object> params = new java.util.HashMap<>();

    if (criticality != null && !criticality.isBlank()) {
      conditions.add("t.criticality = $criticality");
      params.put("criticality", criticality);
    }
    if (curated != null) {
      conditions.add("t.curated = $curated");
      params.put("curated", curated);
    }
    if (search != null && !search.isBlank()) {
      conditions.add("(toLower(t.termId) CONTAINS toLower($search) OR toLower(t.displayName) CONTAINS toLower($search))");
      params.put("search", search);
    }
    if (sourceType != null && !sourceType.isBlank()) {
      conditions.add("t.sourceType = $sourceType");
      params.put("sourceType", sourceType);
    }
    if (uiRole != null && !uiRole.isBlank()) {
      conditions.add("t.uiRole = $uiRole");
      params.put("uiRole", uiRole);
    }
    if (domainArea != null && !domainArea.isBlank()) {
      conditions.add("t.domainArea = $domainArea");
      params.put("domainArea", domainArea);
    }
    if (nlsOnly) {
      conditions.add("t.sourceType STARTS WITH 'NLS_'");
    }

    for (String condition : conditions) {
      cypher.append("  AND ").append(condition).append("\n");
    }
    cypher.append("RETURN t ORDER BY t.usageCount DESC");

    int effectiveLimit = (limit != null && limit > 0) ? limit : 500;
    cypher.append(" LIMIT ").append(effectiveLimit);

    Collection<BusinessTermResponse> results = neo4jClient.query(cypher.toString())
        .bindAll(params)
        .fetchAs(BusinessTermResponse.class)
        .mappedBy((typeSystem, record) -> mapNodeToResponse(record.get("t").asNode(), List.of()))
        .all();

    return new ArrayList<>(results);
  }

  /**
   * Returns a single business term by its termId, populated with the FQNs of all JavaClass nodes
   * that have an incoming USES_TERM edge to this term.
   *
   * @param termId the lowercase term identifier
   * @return Optional containing the response, or empty if not found
   */
  public Optional<BusinessTermResponse> findByTermId(String termId) {
    String cypher = """
        MATCH (t:BusinessTerm {termId: $termId})
        OPTIONAL MATCH (c:JavaClass)-[:USES_TERM]->(t)
        RETURN t, collect(c.fullyQualifiedName) AS relatedFqns
        """;

    return neo4jClient.query(cypher)
        .bindAll(Map.of("termId", termId))
        .fetchAs(BusinessTermResponse.class)
        .mappedBy((typeSystem, record) -> {
          org.neo4j.driver.types.Node node = record.get("t").asNode();
          List<String> relatedFqns = record.get("relatedFqns").asList(v -> v.asString())
              .stream()
              .filter(fqn -> fqn != null && !fqn.isEmpty())
              .collect(Collectors.toList());
          return mapNodeToResponse(node, relatedFqns);
        })
        .one();
  }

  /**
   * Updates a business term's definition, criticality, and synonyms, and marks it as curated.
   *
   * <p>The Cypher query updates all specified fields, sets {@code curated=true},
   * {@code status='curated'}, and auto-derives {@code migrationSensitivity} from criticality.
   * Returns empty Optional if no term with the given termId exists.
   *
   * @param termId     the lowercase term identifier
   * @param definition new human-curated definition
   * @param criticality new criticality level ("High", "Medium", or "Low")
   * @param synonyms   updated list of synonyms
   * @return Optional containing the updated term response, or empty if not found
   */
  public Optional<BusinessTermResponse> updateTerm(
      String termId, String definition, String criticality, List<String> synonyms) {
    String cypher = """
        MATCH (t:BusinessTerm {termId: $termId})
        SET t.definition = $definition,
            t.criticality = $criticality,
            t.synonyms = $synonyms,
            t.curated = true,
            t.status = 'curated',
            t.migrationSensitivity = CASE $criticality
              WHEN 'High' THEN 'Critical'
              WHEN 'Medium' THEN 'Moderate'
              ELSE 'None'
            END
        RETURN t
        """;

    Map<String, Object> params = Map.of(
        "termId", termId,
        "definition", definition != null ? definition : "",
        "criticality", criticality != null ? criticality : "Low",
        "synonyms", synonyms != null ? synonyms : List.of());

    return neo4jClient.query(cypher)
        .bindAll(params)
        .fetchAs(BusinessTermResponse.class)
        .mappedBy((typeSystem, record) -> mapNodeToResponse(record.get("t").asNode(), List.of()))
        .one();
  }

  /**
   * Returns all business terms linked to the given class via outgoing USES_TERM edges.
   *
   * <p>Performs a graph traversal from the JavaClass node identified by {@code classFqn} to all
   * BusinessTerm nodes it references. {@code relatedClassFqns} is always empty in the result — the
   * caller only needs the terms themselves, not their reverse linkages.
   *
   * @param classFqn the fully-qualified class name (e.g., "com.example.OrderService")
   * @return list of business terms linked to that class; empty list if the class has no terms or
   *     does not exist
   */
  public List<BusinessTermResponse> findByClassFqn(String classFqn) {
    if (classFqn == null || classFqn.isBlank()) {
      return List.of();
    }
    String cypher = """
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:USES_TERM]->(t:BusinessTerm)
        RETURN t
        """;

    Collection<BusinessTermResponse> results = neo4jClient.query(cypher)
        .bindAll(Map.of("fqn", classFqn))
        .fetchAs(BusinessTermResponse.class)
        .mappedBy((typeSystem, record) -> mapNodeToResponse(record.get("t").asNode(), List.of()))
        .all();

    return new ArrayList<>(results);
  }

  /**
   * Returns a structured domain glossary with area summaries, UI role distribution, and top terms.
   */
  public com.esmp.graph.api.DomainGlossaryResponse getDomainGlossary() {
    // 1. Domain area summaries with top terms per area
    String areaCypher = """
        MATCH (t:BusinessTerm)
        WHERE t.sourceType STARTS WITH 'NLS_' AND t.domainArea IS NOT NULL
        WITH t.domainArea AS area, t
        ORDER BY t.usageCount DESC
        WITH area, count(t) AS cnt, collect(t.displayName)[0..5] AS topTerms
        RETURN area, cnt, topTerms
        ORDER BY cnt DESC
        """;

    var areaSummaries = neo4jClient.query(areaCypher)
        .fetchAs(com.esmp.graph.api.DomainGlossaryResponse.DomainAreaSummary.class)
        .mappedBy((ts, r) -> new com.esmp.graph.api.DomainGlossaryResponse.DomainAreaSummary(
            r.get("area").asString(),
            r.get("cnt").asLong(),
            r.get("topTerms").asList(v -> v.asString())))
        .all();

    // 2. UI role distribution
    String roleCypher = """
        MATCH (t:BusinessTerm)
        WHERE t.sourceType STARTS WITH 'NLS_' AND t.uiRole IS NOT NULL
        RETURN t.uiRole AS role, count(t) AS cnt
        ORDER BY cnt DESC
        """;

    Map<String, Long> uiRoleCounts = new java.util.LinkedHashMap<>();
    neo4jClient.query(roleCypher).fetch().all().forEach(row ->
        uiRoleCounts.put((String) row.get("role"), (Long) row.get("cnt")));

    // 3. Top NLS terms by usage
    String topCypher = """
        MATCH (t:BusinessTerm)
        WHERE t.sourceType STARTS WITH 'NLS_'
        RETURN t.termId AS termId, t.displayName AS displayName,
               t.definition AS definition, t.uiRole AS uiRole,
               t.domainArea AS domainArea, t.usageCount AS usageCount
        ORDER BY t.usageCount DESC
        LIMIT 30
        """;

    var topTerms = neo4jClient.query(topCypher)
        .fetchAs(com.esmp.graph.api.DomainGlossaryResponse.TopTerm.class)
        .mappedBy((ts, r) -> new com.esmp.graph.api.DomainGlossaryResponse.TopTerm(
            r.get("termId").asString(),
            r.get("displayName").asString(""),
            r.get("definition").isNull() ? null : r.get("definition").asString(),
            r.get("uiRole").isNull() ? null : r.get("uiRole").asString(),
            r.get("domainArea").isNull() ? null : r.get("domainArea").asString(),
            r.get("usageCount").asLong(0)))
        .all();

    // 4. Abbreviations
    String abbrevCypher = """
        MATCH (t:BusinessTerm)
        WHERE t.sourceType = 'ABBREVIATION'
        RETURN t.termId AS abbreviation, t.displayName AS displayName, t.definition AS evidence
        ORDER BY t.termId
        """;

    var abbreviations = neo4jClient.query(abbrevCypher)
        .fetchAs(com.esmp.graph.api.DomainGlossaryResponse.Abbreviation.class)
        .mappedBy((ts, r) -> {
          String displayName = r.get("displayName").asString("");
          // Extract expansion from "SC — Schedule Composition" format
          String expansion = displayName.contains(" — ")
              ? displayName.substring(displayName.indexOf(" — ") + 3)
              : displayName;
          return new com.esmp.graph.api.DomainGlossaryResponse.Abbreviation(
              r.get("abbreviation").asString(),
              expansion,
              r.get("evidence").isNull() ? null : r.get("evidence").asString());
        })
        .all();

    // 5. Totals
    String countCypher = """
        MATCH (t:BusinessTerm)
        RETURN count(t) AS total,
               count(CASE WHEN t.sourceType STARTS WITH 'NLS_' THEN 1 END) AS nlsTotal
        """;

    Map<String, Object> counts = neo4jClient.query(countCypher).fetch().one().orElse(Map.of());
    long totalAll = counts.get("total") instanceof Long l ? l : 0;
    long totalNls = counts.get("nlsTotal") instanceof Long l ? l : 0;

    return new com.esmp.graph.api.DomainGlossaryResponse(
        new ArrayList<>(areaSummaries),
        uiRoleCounts,
        new ArrayList<>(topTerms),
        new ArrayList<>(abbreviations),
        totalNls,
        totalAll);
  }

  // ---------------------------------------------------------------------------
  // Mapping helpers
  // ---------------------------------------------------------------------------

  private BusinessTermResponse mapNodeToResponse(
      org.neo4j.driver.types.Node node, List<String> relatedFqns) {
    return new BusinessTermResponse(
        node.get("termId").asString(""),
        node.get("displayName").asString(""),
        node.get("definition").asString(null),
        node.get("criticality").asString("Low"),
        node.get("migrationSensitivity").asString("None"),
        node.get("synonyms").asList(v -> v.asString()),
        node.get("curated").asBoolean(false),
        node.get("status").asString("auto"),
        node.get("sourceType").asString(""),
        node.get("primarySourceFqn").asString(""),
        (int) node.get("usageCount").asLong(0L),
        relatedFqns,
        node.get("uiRole").isNull() ? null : node.get("uiRole").asString(),
        node.get("domainArea").isNull() ? null : node.get("domainArea").asString(),
        node.get("nlsFileName").isNull() ? null : node.get("nlsFileName").asString(),
        node.get("documentContext").isNull() ? null : node.get("documentContext").asString(),
        node.get("documentSource").isNull() ? null : node.get("documentSource").asString());
  }
}
