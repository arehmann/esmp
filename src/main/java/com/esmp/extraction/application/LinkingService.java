package com.esmp.extraction.application;

import com.esmp.extraction.visitor.ExtractionAccumulator;
import com.esmp.extraction.visitor.ExtractionAccumulator.BindsToRecord;
import com.esmp.extraction.visitor.ExtractionAccumulator.BusinessTermData;
import com.esmp.extraction.visitor.ExtractionAccumulator.DependencyEdge;
import com.esmp.extraction.visitor.ExtractionAccumulator.QueryMethodRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Post-extraction linking service that creates cross-class graph relationships via idempotent
 * Cypher MERGE queries.
 *
 * <p>All linking runs after node persistence ({@code saveAll()}) is complete so that MATCH clauses
 * can find the previously persisted nodes. Each linking method is idempotent: running it multiple
 * times on the same data produces the same graph state (MERGE semantics).
 *
 * <p>Relationships created:
 *
 * <ul>
 *   <li>{@code EXTENDS} — from child class to parent class (resolved by superClass FQN)
 *   <li>{@code IMPLEMENTS} — from class to each implemented interface
 *   <li>{@code DEPENDS_ON} — from class to injected dependency class
 *   <li>{@code MAPS_TO_TABLE} — from entity class to DBTable node
 *   <li>{@code QUERIES} — from query method to DBTable node (best-effort)
 *   <li>{@code HAS_ANNOTATION} — from class to annotation node
 *   <li>{@code CONTAINS_CLASS} — from package node to class node
 *   <li>{@code CONTAINS_PACKAGE} — from module node to package node
 *   <li>{@code BINDS_TO} — from Vaadin view/form to the entity it binds to (via BeanFieldGroup,
 *       FieldGroup)
 *   <li>{@code USES_TERM} — from JavaClass to BusinessTerm (primary source + DEPENDS_ON dependents)
 *   <li>{@code DEFINES_RULE} — from business-rule-pattern classes to BusinessTerm nodes
 * </ul>
 */
@Service
public class LinkingService {

  private static final Logger log = LoggerFactory.getLogger(LinkingService.class);

  private final Neo4jClient neo4jClient;

  public LinkingService(Neo4jClient neo4jClient) {
    this.neo4jClient = neo4jClient;
  }

  /**
   * Runs all linking passes in sequence and aggregates edge counts.
   *
   * @param acc the accumulator from the current extraction run
   * @return a {@link LinkingResult} record with counts for each edge type
   */
  @Transactional("neo4jTransactionManager")
  public LinkingResult linkAllRelationships(ExtractionAccumulator acc) {
    int extendsCount = linkInheritanceRelationships();
    int dependsOnCount = linkDependencies(acc);
    int mapsToTableCount = linkTableMappings(acc);
    int queriesCount = linkQueryMethods(acc);
    int hasAnnotationCount = linkAnnotations(acc);
    int containsClassCount = linkPackageHierarchy(acc);
    int bindsToCount = linkBindsToEdges(acc);
    int usesTermCount = linkBusinessTermUsages(acc);
    int definesRuleCount = linkBusinessRules(acc);

    log.info(
        "Linking complete: EXTENDS={}, DEPENDS_ON={}, MAPS_TO_TABLE={}, "
            + "QUERIES={}, HAS_ANNOTATION={}, CONTAINS_CLASS/PACKAGE={}, BINDS_TO={}, "
            + "USES_TERM={}, DEFINES_RULE={}",
        extendsCount, dependsOnCount, mapsToTableCount,
        queriesCount, hasAnnotationCount, containsClassCount, bindsToCount,
        usesTermCount, definesRuleCount);

    return new LinkingResult(
        extendsCount, dependsOnCount, mapsToTableCount,
        queriesCount, hasAnnotationCount, containsClassCount, bindsToCount,
        usesTermCount, definesRuleCount);
  }

  // ---------------------------------------------------------------------------
  // Inheritance linking: EXTENDS and IMPLEMENTS
  // ---------------------------------------------------------------------------

  /**
   * Creates EXTENDS edges (child.superClass → parent) and IMPLEMENTS edges (child → each interface)
   * using idempotent Cypher MERGE. Unresolved external types are silently skipped.
   *
   * @return total count of inheritance edges created (EXTENDS + IMPLEMENTS)
   */
  @Transactional("neo4jTransactionManager")
  public int linkInheritanceRelationships() {
    // EXTENDS edges: match child class to its superclass by FQN
    String extendsCypher = """
        MATCH (child:JavaClass)
        WHERE child.superClass IS NOT NULL AND child.superClass <> ''
        MATCH (parent:JavaClass {fullyQualifiedName: child.superClass})
        MERGE (child)-[r:EXTENDS]->(parent)
        ON CREATE SET r.resolutionConfidence = 'RESOLVED'
        RETURN count(r) AS cnt
        """;

    Long extendsCount = neo4jClient.query(extendsCypher)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    // IMPLEMENTS edges: UNWIND implementedInterfaces list on each class
    String implementsCypher = """
        MATCH (child:JavaClass)
        WHERE child.implementedInterfaces IS NOT NULL AND size(child.implementedInterfaces) > 0
        UNWIND child.implementedInterfaces AS ifaceFqn
        MATCH (iface:JavaClass {fullyQualifiedName: ifaceFqn})
        MERGE (child)-[r:IMPLEMENTS]->(iface)
        ON CREATE SET r.resolutionConfidence = 'RESOLVED'
        RETURN count(r) AS cnt
        """;

    Long implementsCount = neo4jClient.query(implementsCypher)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    return (int) (extendsCount + implementsCount);
  }

  // ---------------------------------------------------------------------------
  // Dependency linking: DEPENDS_ON
  // ---------------------------------------------------------------------------

  /**
   * Creates idempotent DEPENDS_ON edges from the dependency edges in the accumulator.
   * Clears existing DEPENDS_ON edges first for full idempotency on re-extraction.
   *
   * @param acc the accumulator containing dependency edges
   * @return count of DEPENDS_ON edges created
   */
  @Transactional("neo4jTransactionManager")
  public int linkDependencies(ExtractionAccumulator acc) {
    if (acc.getDependencyEdges().isEmpty()) {
      return 0;
    }

    // Delete existing DEPENDS_ON edges for idempotency
    neo4jClient.query("MATCH (:JavaClass)-[r:DEPENDS_ON]->(:JavaClass) DELETE r").run();

    int count = 0;
    for (DependencyEdge edge : acc.getDependencyEdges()) {
      String cypher = """
          MATCH (from:JavaClass {fullyQualifiedName: $fromFqn})
          MATCH (to:JavaClass {fullyQualifiedName: $toFqn})
          MERGE (from)-[r:DEPENDS_ON {injectionType: $injectionType, fieldName: $fieldName}]->(to)
          RETURN count(r) AS cnt
          """;

      Long cnt = neo4jClient.query(cypher)
          .bindAll(Map.of(
              "fromFqn", edge.fromFqn(),
              "toFqn", edge.toFqn(),
              "injectionType", edge.injectionType(),
              "fieldName", edge.fieldName() != null ? edge.fieldName() : ""))
          .fetchAs(Long.class)
          .mappedBy((ts, record) -> record.get("cnt").asLong())
          .one()
          .orElse(0L);
      count += cnt.intValue();
    }

    return count;
  }

  // ---------------------------------------------------------------------------
  // JPA table mapping: MAPS_TO_TABLE
  // ---------------------------------------------------------------------------

  /**
   * Creates MAPS_TO_TABLE edges from entity ClassNodes to DBTableNodes using the accumulator's
   * table mappings.
   *
   * @param acc the accumulator containing table mappings
   * @return count of MAPS_TO_TABLE edges created
   */
  @Transactional("neo4jTransactionManager")
  public int linkTableMappings(ExtractionAccumulator acc) {
    if (acc.getTableMappings().isEmpty()) {
      return 0;
    }

    int count = 0;
    for (Map.Entry<String, String> entry : acc.getTableMappings().entrySet()) {
      String classFqn = entry.getKey();
      String tableName = entry.getValue();

      String cypher = """
          MATCH (c:JavaClass {fullyQualifiedName: $classFqn})
          MATCH (t:DBTable {tableName: $tableName})
          MERGE (c)-[r:MAPS_TO_TABLE]->(t)
          RETURN count(r) AS cnt
          """;

      Long cnt = neo4jClient.query(cypher)
          .bindAll(Map.of("classFqn", classFqn, "tableName", tableName))
          .fetchAs(Long.class)
          .mappedBy((ts, record) -> record.get("cnt").asLong())
          .one()
          .orElse(0L);
      count += cnt.intValue();
    }

    return count;
  }

  // ---------------------------------------------------------------------------
  // Query method linking: QUERIES
  // ---------------------------------------------------------------------------

  /**
   * Creates QUERIES edges from MethodNodes to DBTableNodes. Resolves the target table by looking up
   * the declaring class's table mapping.
   *
   * <p>If the declaring class has no direct table mapping (e.g., it is a Spring Data repository
   * interface), the linking is skipped and a WARN is logged — this is a best-effort operation.
   *
   * @param acc the accumulator containing query method records and table mappings
   * @return count of QUERIES edges created
   */
  /**
   * Creates QUERIES edges from MethodNodes to DBTableNodes by traversing the graph from the
   * repository class to the entity it manages (via DEPENDS_ON or IMPLEMENTS edges), and then
   * from that entity to its mapped DBTable (via MAPS_TO_TABLE).
   *
   * <p>This graph-native approach avoids the need to parse generic type parameters from
   * repository interface declarations (e.g., {@code JpaRepository<SampleEntity, Long>}). Instead,
   * it finds the entity by traversing the graph and locating an entity class that has a
   * MAPS_TO_TABLE edge.
   *
   * @param acc the accumulator containing query method records
   * @return count of QUERIES edges created
   */
  @Transactional("neo4jTransactionManager")
  public int linkQueryMethods(ExtractionAccumulator acc) {
    if (acc.getQueryMethods().isEmpty()) {
      return 0;
    }

    int count = 0;
    for (QueryMethodRecord qm : acc.getQueryMethods()) {
      // Graph-native traversal: from the repository class, traverse up to 3 hops through
      // DEPENDS_ON or IMPLEMENTS edges to find an entity class with a MAPS_TO_TABLE edge.
      // This handles the common pattern: Repository -[DEPENDS_ON|IMPLEMENTS*1..3]-> Entity
      //                                   Entity -[MAPS_TO_TABLE]-> DBTable
      String cypher = """
          MATCH (m:JavaMethod {methodId: $methodId})
          MATCH (repo:JavaClass {fullyQualifiedName: $repoFqn})
          MATCH (repo)-[:DEPENDS_ON|IMPLEMENTS*1..3]->(entity:JavaClass)-[:MAPS_TO_TABLE]->(t:DBTable)
          MERGE (m)-[r:QUERIES]->(t)
          RETURN count(r) AS cnt
          """;

      Long cnt = neo4jClient.query(cypher)
          .bindAll(Map.of("methodId", qm.methodId(), "repoFqn", qm.declaringClassFqn()))
          .fetchAs(Long.class)
          .mappedBy((ts, record) -> record.get("cnt").asLong())
          .one()
          .orElse(0L);

      if (cnt == 0L) {
        log.debug(
            "Skipping QUERIES link for method {} — no entity-table path found from declaring class {}",
            qm.methodId(), qm.declaringClassFqn());
      }
      count += cnt.intValue();
    }

    return count;
  }

  // ---------------------------------------------------------------------------
  // Annotation linking: HAS_ANNOTATION
  // ---------------------------------------------------------------------------

  /**
   * Creates HAS_ANNOTATION edges from ClassNodes to AnnotationNodes based on the annotations
   * list stored on each class.
   *
   * @param acc the accumulator (used to know which annotation FQNs are valid nodes)
   * @return count of HAS_ANNOTATION edges created
   */
  @Transactional("neo4jTransactionManager")
  public int linkAnnotations(ExtractionAccumulator acc) {
    if (acc.getAnnotations().isEmpty()) {
      return 0;
    }

    // Create HAS_ANNOTATION edges from class annotations list to annotation nodes
    String cypher = """
        MATCH (c:JavaClass)
        WHERE c.annotations IS NOT NULL AND size(c.annotations) > 0
        UNWIND c.annotations AS annotFqn
        MATCH (a:JavaAnnotation {fullyQualifiedName: annotFqn})
        MERGE (c)-[r:HAS_ANNOTATION]->(a)
        RETURN count(r) AS cnt
        """;

    Long cnt = neo4jClient.query(cypher)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    return cnt.intValue();
  }

  // ---------------------------------------------------------------------------
  // Package / module hierarchy: CONTAINS_CLASS and CONTAINS_PACKAGE
  // ---------------------------------------------------------------------------

  /**
   * Creates CONTAINS_CLASS edges from PackageNodes to ClassNodes (by matching packageName) and
   * CONTAINS_PACKAGE edges from ModuleNodes to PackageNodes (by matching moduleName).
   *
   * @param acc the accumulator (used to determine module name)
   * @return count of CONTAINS_CLASS + CONTAINS_PACKAGE edges created
   */
  @Transactional("neo4jTransactionManager")
  public int linkPackageHierarchy(ExtractionAccumulator acc) {
    // CONTAINS_CLASS: match package by packageName to class by packageName field
    String containsClassCypher = """
        MATCH (pkg:JavaPackage)
        MATCH (c:JavaClass {packageName: pkg.packageName})
        MERGE (pkg)-[r:CONTAINS_CLASS]->(c)
        RETURN count(r) AS cnt
        """;

    Long classCount = neo4jClient.query(containsClassCypher)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    // CONTAINS_PACKAGE: match module to package by moduleName
    String containsPkgCypher = """
        MATCH (m:JavaModule)
        MATCH (pkg:JavaPackage {moduleName: m.moduleName})
        MERGE (m)-[r:CONTAINS_PACKAGE]->(pkg)
        RETURN count(r) AS cnt
        """;

    Long pkgCount = neo4jClient.query(containsPkgCypher)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    return (int) (classCount + pkgCount);
  }

  // ---------------------------------------------------------------------------
  // Vaadin data binding: BINDS_TO
  // ---------------------------------------------------------------------------

  /**
   * Creates BINDS_TO edges from Vaadin view ClassNodes to entity ClassNodes via idempotent Cypher
   * MERGE. Each edge carries a {@code bindingMechanism} property ("BeanFieldGroup" or "FieldGroup").
   *
   * <p>Skips execution if the accumulator has no BINDS_TO edge data.
   *
   * @param acc the accumulator containing BINDS_TO edge records
   * @return count of BINDS_TO edges created (or matched by MERGE)
   */
  @Transactional("neo4jTransactionManager")
  public int linkBindsToEdges(ExtractionAccumulator acc) {
    if (acc.getBindsToEdges().isEmpty()) {
      return 0;
    }

    int count = 0;
    for (BindsToRecord edge : acc.getBindsToEdges()) {
      String cypher = """
          MATCH (view:JavaClass {fullyQualifiedName: $viewFqn})
          MATCH (entity:JavaClass {fullyQualifiedName: $entityFqn})
          MERGE (view)-[r:BINDS_TO {bindingMechanism: $mechanism}]->(entity)
          RETURN count(r) AS cnt
          """;

      Long cnt = neo4jClient.query(cypher)
          .bindAll(Map.of(
              "viewFqn", edge.viewClassFqn(),
              "entityFqn", edge.entityClassFqn(),
              "mechanism", edge.bindingMechanism()))
          .fetchAs(Long.class)
          .mappedBy((ts, record) -> record.get("cnt").asLong())
          .one()
          .orElse(0L);
      count += cnt.intValue();
    }

    return count;
  }

  // ---------------------------------------------------------------------------
  // Domain lexicon linking: USES_TERM and DEFINES_RULE
  // ---------------------------------------------------------------------------

  /**
   * Camel-case split pattern: splits on transitions from lower to upper case and on acronym
   * boundaries. E.g., "InvoiceService" -> ["Invoice", "Service"].
   */
  private static final Pattern CAMEL_SPLIT = Pattern.compile(
      "(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

  /**
   * Stop suffixes to exclude from term extraction when splitting class simple names.
   * Mirrors the STOP_SUFFIXES in LexiconVisitor.
   */
  private static final Set<String> STOP_SUFFIXES = Set.of(
      "service", "repository", "controller", "manager", "handler", "factory",
      "builder", "config", "configuration", "impl", "base", "abstract",
      "helper", "util", "utils", "test", "validator", "rule", "policy",
      "constraint", "calculator", "strategy", "enum", "type");

  /**
   * Business-rule-pattern class name suffixes. Classes whose simpleName ends in one of these
   * are eligible for DEFINES_RULE edges.
   */
  private static final Pattern BUSINESS_RULE_PATTERN = Pattern.compile(
      ".*(Validator|Rule|Policy|Constraint|Calculator|Strategy).*");

  /**
   * Creates USES_TERM edges from the primary source JavaClass to its extracted BusinessTerm nodes,
   * and also from classes that DEPENDS_ON the primary source class. Test classes (sourceFilePath
   * contains '/test/') are excluded from dependent linking.
   *
   * @param acc the accumulator containing extracted business terms
   * @return total count of USES_TERM edges created (primary + dependent)
   */
  @Transactional("neo4jTransactionManager")
  public int linkBusinessTermUsages(ExtractionAccumulator acc) {
    if (acc.getBusinessTerms().isEmpty()) {
      return 0;
    }

    int count = 0;
    for (BusinessTermData term : acc.getBusinessTerms().values()) {
      String termId = term.termId;
      String primaryFqn = term.primarySourceFqn;

      // 1. Primary source class -> BusinessTerm
      String primaryCypher = """
          MATCH (c:JavaClass {fullyQualifiedName: $classFqn})
          MATCH (t:BusinessTerm {termId: $termId})
          MERGE (c)-[r:USES_TERM]->(t)
          RETURN count(r) AS cnt
          """;

      Long primaryCnt = neo4jClient.query(primaryCypher)
          .bindAll(Map.of("classFqn", primaryFqn, "termId", termId))
          .fetchAs(Long.class)
          .mappedBy((ts, record) -> record.get("cnt").asLong())
          .one()
          .orElse(0L);
      count += primaryCnt.intValue();

      // 2. Classes that DEPENDS_ON the primary source class (excluding test classes)
      String dependentCypher = """
          MATCH (c:JavaClass {fullyQualifiedName: $primaryFqn})<-[:DEPENDS_ON]-(dep:JavaClass)
          WHERE NOT (dep.sourceFilePath CONTAINS '/src/test/' OR dep.sourceFilePath CONTAINS '/test/java/')
          MATCH (t:BusinessTerm {termId: $termId})
          MERGE (dep)-[r:USES_TERM]->(t)
          RETURN count(r) AS cnt
          """;

      Long depCnt = neo4jClient.query(dependentCypher)
          .bindAll(Map.of("primaryFqn", primaryFqn, "termId", termId))
          .fetchAs(Long.class)
          .mappedBy((ts, record) -> record.get("cnt").asLong())
          .one()
          .orElse(0L);
      count += depCnt.intValue();
    }

    return count;
  }

  /**
   * Creates DEFINES_RULE edges from JavaClass nodes matching business-rule naming patterns
   * (Validator, Rule, Policy, Constraint, Calculator, Strategy) to their extracted BusinessTerm
   * nodes. Terms are derived by splitting the class simpleName with camelCase split logic and
   * filtering stop suffixes, then matching against existing BusinessTerm nodes.
   *
   * <p>Also creates DEFINES_RULE edges for classes with validation annotations
   * (javax/jakarta.validation.Constraint).
   *
   * @param acc the accumulator (used for context but not for term lookup — queries graph directly)
   * @return count of DEFINES_RULE edges created
   */
  @Transactional("neo4jTransactionManager")
  public int linkBusinessRules(ExtractionAccumulator acc) {
    // Query for all JavaClass nodes matching the business-rule naming pattern
    String queryClassesCypher = """
        MATCH (c:JavaClass)
        WHERE c.simpleName =~ '.*(Validator|Rule|Policy|Constraint|Calculator|Strategy).*'
           OR EXISTS {
             MATCH (c)-[:HAS_ANNOTATION]->(a:JavaAnnotation)
             WHERE a.fullyQualifiedName IN
               ['javax.validation.Constraint', 'jakarta.validation.Constraint']
           }
        RETURN c.fullyQualifiedName AS fqn, c.simpleName AS simpleName
        """;

    // Collect matching classes from the graph
    List<Map<String, String>> matchedClasses = new ArrayList<>();
    neo4jClient.query(queryClassesCypher)
        .fetchAs(Map.class)
        .mappedBy((ts, record) -> {
          Map<String, String> row = new java.util.HashMap<>();
          row.put("fqn", record.get("fqn").asString(""));
          row.put("simpleName", record.get("simpleName").asString(""));
          return row;
        })
        .all()
        .forEach(row -> matchedClasses.add(row));

    int count = 0;
    for (Map<String, String> classRow : matchedClasses) {
      String classFqn = classRow.get("fqn");
      String simpleName = classRow.get("simpleName");

      // Extract term IDs from simpleName using camelCase split + stop suffix filter
      List<String> termIds = extractTermIds(simpleName);

      if (termIds.isEmpty()) {
        continue;
      }

      String createEdgesCypher = """
          MATCH (c:JavaClass {fullyQualifiedName: $classFqn})
          MATCH (t:BusinessTerm)
          WHERE t.termId IN $termIds
          MERGE (c)-[r:DEFINES_RULE]->(t)
          RETURN count(r) AS cnt
          """;

      Long cnt = neo4jClient.query(createEdgesCypher)
          .bindAll(Map.of("classFqn", classFqn, "termIds", termIds))
          .fetchAs(Long.class)
          .mappedBy((ts, record) -> record.get("cnt").asLong())
          .one()
          .orElse(0L);
      count += cnt.intValue();
    }

    return count;
  }

  /**
   * Splits a PascalCase/camelCase class simpleName into lowercase term IDs, filtering out
   * stop suffixes (technical terms that don't represent domain concepts).
   *
   * @param simpleName the class simple name
   * @return list of lowercase term IDs
   */
  private List<String> extractTermIds(String simpleName) {
    if (simpleName == null || simpleName.isBlank()) {
      return List.of();
    }
    String[] parts = CAMEL_SPLIT.split(simpleName);
    List<String> termIds = new ArrayList<>();
    for (String part : parts) {
      String lower = part.toLowerCase();
      if (!lower.isBlank() && !STOP_SUFFIXES.contains(lower) && lower.length() > 1) {
        termIds.add(lower);
      }
    }
    return termIds;
  }

  // ---------------------------------------------------------------------------
  // Result record
  // ---------------------------------------------------------------------------

  /** Counts of edges created per relationship type in a single linking run. */
  public record LinkingResult(
      int extendsCount,
      int dependsOnCount,
      int mapsToTableCount,
      int queriesCount,
      int hasAnnotationCount,
      int containsHierarchyCount,
      int bindsToCount,
      int usesTermCount,
      int definesRuleCount) {}
}
