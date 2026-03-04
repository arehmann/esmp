package com.esmp.extraction.application;

import com.esmp.extraction.visitor.ExtractionAccumulator;
import com.esmp.extraction.visitor.ExtractionAccumulator.DependencyEdge;
import com.esmp.extraction.visitor.ExtractionAccumulator.QueryMethodRecord;
import java.util.Map;
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

    log.info(
        "Linking complete: EXTENDS={}, DEPENDS_ON={}, MAPS_TO_TABLE={}, "
            + "QUERIES={}, HAS_ANNOTATION={}, CONTAINS_CLASS/PACKAGE={}",
        extendsCount, dependsOnCount, mapsToTableCount,
        queriesCount, hasAnnotationCount, containsClassCount);

    return new LinkingResult(
        extendsCount, dependsOnCount, mapsToTableCount,
        queriesCount, hasAnnotationCount, containsClassCount);
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
  @Transactional("neo4jTransactionManager")
  public int linkQueryMethods(ExtractionAccumulator acc) {
    if (acc.getQueryMethods().isEmpty()) {
      return 0;
    }

    int count = 0;
    for (QueryMethodRecord qm : acc.getQueryMethods()) {
      // Look up direct table mapping for the declaring class
      String tableName = acc.getTableMappings().get(qm.declaringClassFqn());
      if (tableName == null) {
        log.debug(
            "Skipping QUERIES link for method {} — no table mapping for declaring class {}",
            qm.methodId(), qm.declaringClassFqn());
        continue;
      }

      String cypher = """
          MATCH (m:JavaMethod {methodId: $methodId})
          MATCH (t:DBTable {tableName: $tableName})
          MERGE (m)-[r:QUERIES]->(t)
          RETURN count(r) AS cnt
          """;

      Long cnt = neo4jClient.query(cypher)
          .bindAll(Map.of("methodId", qm.methodId(), "tableName", tableName))
          .fetchAs(Long.class)
          .mappedBy((ts, record) -> record.get("cnt").asLong())
          .one()
          .orElse(0L);
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
  // Result record
  // ---------------------------------------------------------------------------

  /** Counts of edges created per relationship type in a single linking run. */
  public record LinkingResult(
      int extendsCount,
      int dependsOnCount,
      int mapsToTableCount,
      int queriesCount,
      int hasAnnotationCount,
      int containsHierarchyCount) {}
}
