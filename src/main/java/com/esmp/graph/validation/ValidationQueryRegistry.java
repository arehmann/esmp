package com.esmp.graph.validation;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Registry holding all 20 canonical graph validation queries for Phase 4.
 *
 * <p>This component is extensible: future phases can add their own {@code ValidationQueryRegistry}
 * beans. {@link com.esmp.graph.validation.ValidationService} accepts a
 * {@code List<ValidationQueryRegistry>} and aggregates all registered queries, so new registries
 * are picked up automatically without modifying the core service.
 *
 * <p>Queries are grouped into two categories:
 * <ol>
 *   <li>Structural integrity (queries 1-10): detect orphan nodes, dangling edges, duplicates,
 *       relationship endpoint violations, and inheritance chain breaks.
 *   <li>Architectural pattern checks (queries 11-20): flag services without dependencies,
 *       repositories without QUERIES edges, views without bindings, and coverage gaps.
 * </ol>
 *
 * <p>Each Cypher query returns exactly one row with columns:
 * <ul>
 *   <li>{@code count} — number of violations found (0 = passing)
 *   <li>{@code details} — list of up to 20 entity identifiers (FQNs, methodIds, etc.)
 * </ul>
 */
@Component
public class ValidationQueryRegistry {

  private final List<ValidationQuery> queries;

  public ValidationQueryRegistry() {
    this.queries = List.of(

        // -----------------------------------------------------------------------
        // STRUCTURAL INTEGRITY CHECKS (queries 1-10)
        // -----------------------------------------------------------------------

        // 1. ORPHAN_CLASS_NODES (WARNING)
        // JavaClass nodes with no incoming CONTAINS_CLASS edge from any JavaPackage.
        new ValidationQuery(
            "ORPHAN_CLASS_NODES",
            "JavaClass nodes not contained in any JavaPackage via CONTAINS_CLASS edge",
            """
            OPTIONAL MATCH (c:JavaClass) WHERE NOT ()-[:CONTAINS_CLASS]->(c)
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 2. DANGLING_METHOD_NODES (ERROR)
        // JavaMethod nodes with no incoming DECLARES_METHOD edge.
        new ValidationQuery(
            "DANGLING_METHOD_NODES",
            "JavaMethod nodes not declared by any JavaClass via DECLARES_METHOD edge",
            """
            OPTIONAL MATCH (m:JavaMethod) WHERE NOT ()-[:DECLARES_METHOD]->(m)
            RETURN count(m) AS count, collect(m.methodId)[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 3. DANGLING_FIELD_NODES (ERROR)
        // JavaField nodes with no incoming DECLARES_FIELD edge.
        new ValidationQuery(
            "DANGLING_FIELD_NODES",
            "JavaField nodes not declared by any JavaClass via DECLARES_FIELD edge",
            """
            OPTIONAL MATCH (f:JavaField) WHERE NOT ()-[:DECLARES_FIELD]->(f)
            RETURN count(f) AS count, collect(f.fieldId)[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 4. DANGLING_ANNOTATION_NODES (WARNING)
        // JavaAnnotation nodes with no incoming HAS_ANNOTATION edge.
        new ValidationQuery(
            "DANGLING_ANNOTATION_NODES",
            "JavaAnnotation nodes not linked to any class via HAS_ANNOTATION edge",
            """
            OPTIONAL MATCH (a:JavaAnnotation) WHERE NOT ()-[:HAS_ANNOTATION]->(a)
            RETURN count(a) AS count, collect(a.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 5. DUPLICATE_CLASS_FQNS (ERROR)
        // JavaClass nodes sharing the same fullyQualifiedName (constraint violation check).
        new ValidationQuery(
            "DUPLICATE_CLASS_FQNS",
            "JavaClass nodes with non-unique fullyQualifiedName (uniqueness constraint violation)",
            """
            MATCH (c:JavaClass) WITH c.fullyQualifiedName AS fqn, count(*) AS cnt
            WHERE cnt > 1
            RETURN count(fqn) AS count, collect(fqn)[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 6. EXTENDS_CHAIN_DANGLING (WARNING)
        // Classes with superClass property set but no EXTENDS outgoing edge.
        new ValidationQuery(
            "EXTENDS_CHAIN_DANGLING",
            "Classes with superClass property set but missing EXTENDS edge (parent not in graph)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.superClass IS NOT NULL AND c.superClass <> ''
              AND NOT (c)-[:EXTENDS]->()
            RETURN count(c) AS count,
                   collect(c.fullyQualifiedName + ' -> ' + c.superClass)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 7. IMPLEMENTS_MISSING (WARNING)
        // Classes with implementedInterfaces containing FQNs that have no IMPLEMENTS edge.
        new ValidationQuery(
            "IMPLEMENTS_MISSING",
            "Classes with implementedInterfaces property set but missing IMPLEMENTS outgoing edge",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE size(c.implementedInterfaces) > 0 AND NOT (c)-[:IMPLEMENTS]->()
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 8. MAPS_TO_TABLE_ORPHAN_TABLE (WARNING)
        // DBTable nodes with no incoming MAPS_TO_TABLE edge.
        new ValidationQuery(
            "MAPS_TO_TABLE_ORPHAN_TABLE",
            "DBTable nodes not mapped to any class via MAPS_TO_TABLE edge",
            """
            OPTIONAL MATCH (t:DBTable) WHERE NOT ()-[:MAPS_TO_TABLE]->(t)
            RETURN count(t) AS count, collect(t.tableName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 9. QUERIES_EDGE_INTEGRITY (ERROR)
        // QUERIES relationships where source is not JavaMethod or target is not DBTable.
        new ValidationQuery(
            "QUERIES_EDGE_INTEGRITY",
            "QUERIES edges with invalid endpoints: source must be JavaMethod, target must be DBTable",
            """
            OPTIONAL MATCH (src)-[r:QUERIES]->(tgt)
            WHERE NOT src:JavaMethod OR NOT tgt:DBTable
            RETURN count(r) AS count,
                   collect(COALESCE(src.methodId, 'unknown') + ' -QUERIES-> '
                     + COALESCE(tgt.tableName, 'unknown'))[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 10. BINDS_TO_EDGE_INTEGRITY (ERROR)
        // BINDS_TO relationships where source or target is not JavaClass.
        new ValidationQuery(
            "BINDS_TO_EDGE_INTEGRITY",
            "BINDS_TO edges with invalid endpoints: both source and target must be JavaClass nodes",
            """
            OPTIONAL MATCH (src)-[r:BINDS_TO]->(tgt)
            WHERE NOT src:JavaClass OR NOT tgt:JavaClass
            RETURN count(r) AS count,
                   collect(COALESCE(src.fullyQualifiedName, 'unknown') + ' -BINDS_TO-> '
                     + COALESCE(tgt.fullyQualifiedName, 'unknown'))[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // -----------------------------------------------------------------------
        // ARCHITECTURAL PATTERN CHECKS (queries 11-20)
        // -----------------------------------------------------------------------

        // 11. SERVICE_HAS_DEPENDENCIES (WARNING)
        // Service-labeled classes with zero DEPENDS_ON outgoing edges.
        new ValidationQuery(
            "SERVICE_HAS_DEPENDENCIES",
            "Service-labeled classes with no DEPENDS_ON edges (every service should inject something)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE ANY(label IN labels(c) WHERE label = 'Service')
              AND NOT (c)-[:DEPENDS_ON]->()
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 12. REPOSITORY_HAS_QUERIES (WARNING)
        // Repository-labeled classes whose methods have zero QUERIES outgoing edges.
        new ValidationQuery(
            "REPOSITORY_HAS_QUERIES",
            "Repository-labeled classes whose methods have no QUERIES edges to any DBTable",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE ANY(label IN labels(c) WHERE label = 'Repository')
              AND NOT (c)-[:DECLARES_METHOD]->()-[:QUERIES]->()
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 13. UI_VIEW_HAS_BINDS_TO (WARNING)
        // VaadinView-labeled classes with zero BINDS_TO outgoing edges.
        new ValidationQuery(
            "UI_VIEW_HAS_BINDS_TO",
            "VaadinView-labeled classes with no BINDS_TO edges (every view should bind to an entity)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE ANY(label IN labels(c) WHERE label = 'VaadinView')
              AND NOT (c)-[:BINDS_TO]->()
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 14. ENTITY_MAPS_TO_TABLE (WARNING)
        // Classes with @Entity annotation but no MAPS_TO_TABLE outgoing edge.
        new ValidationQuery(
            "ENTITY_MAPS_TO_TABLE",
            "Classes annotated with @Entity but missing MAPS_TO_TABLE edge to a DBTable",
            """
            OPTIONAL MATCH (c:JavaClass)-[:HAS_ANNOTATION]->(a:JavaAnnotation)
            WHERE (a.fullyQualifiedName = 'javax.persistence.Entity'
                OR a.fullyQualifiedName = 'jakarta.persistence.Entity')
              AND NOT (c)-[:MAPS_TO_TABLE]->()
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 15. INHERITANCE_CHAIN_COMPLETENESS (ERROR)
        // Classes whose superClass FQN exists as a JavaClass node but the EXTENDS edge is missing.
        new ValidationQuery(
            "INHERITANCE_CHAIN_COMPLETENESS",
            "Classes whose superClass exists in the graph but the EXTENDS edge is missing",
            """
            OPTIONAL MATCH (c:JavaClass), (parent:JavaClass)
            WHERE c.superClass = parent.fullyQualifiedName
              AND NOT (c)-[:EXTENDS]->(parent)
            RETURN count(c) AS count,
                   collect(c.fullyQualifiedName + ' -> ' + c.superClass)[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 16. TRANSITIVE_REPO_DEPENDENCY (WARNING)
        // Service classes with no transitive path to any Repository class (max 10 hops).
        new ValidationQuery(
            "TRANSITIVE_REPO_DEPENDENCY",
            "Service classes with no transitive DEPENDS_ON path to any Repository class (max 10 hops)",
            """
            OPTIONAL MATCH (s:JavaClass)
            WHERE ANY(label IN labels(s) WHERE label = 'Service')
              AND NOT EXISTS {
                MATCH (s)-[:DEPENDS_ON*1..10]->(r:JavaClass)
                WHERE ANY(l IN labels(r) WHERE l = 'Repository')
              }
            RETURN count(s) AS count, collect(s.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 17. NO_ISOLATED_MODULES (WARNING)
        // JavaModule nodes with no CONTAINS_PACKAGE outgoing edges.
        new ValidationQuery(
            "NO_ISOLATED_MODULES",
            "JavaModule nodes with no CONTAINS_PACKAGE edges (isolated/empty modules)",
            """
            OPTIONAL MATCH (m:JavaModule) WHERE NOT (m)-[:CONTAINS_PACKAGE]->()
            RETURN count(m) AS count, collect(m.moduleName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 18. NO_EMPTY_PACKAGES (WARNING)
        // JavaPackage nodes with no CONTAINS_CLASS outgoing edges.
        new ValidationQuery(
            "NO_EMPTY_PACKAGES",
            "JavaPackage nodes with no CONTAINS_CLASS edges (empty packages)",
            """
            OPTIONAL MATCH (p:JavaPackage) WHERE NOT (p)-[:CONTAINS_CLASS]->()
            RETURN count(p) AS count, collect(p.packageName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 19. ANNOTATION_COVERAGE (WARNING)
        // JavaClass nodes with empty annotations and no HAS_ANNOTATION edge (fully unannotated).
        new ValidationQuery(
            "ANNOTATION_COVERAGE",
            "JavaClass nodes with no annotations property and no HAS_ANNOTATION edge (fully unannotated classes)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE (c.annotations IS NULL OR size(c.annotations) = 0)
              AND NOT (c)-[:HAS_ANNOTATION]->()
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 20. CALLS_EDGE_COVERAGE (WARNING — inverted logic)
        // Total CALLS edges in graph — sanity check. count > 0 = PASS, count == 0 = WARN.
        // NOTE: ValidationService special-cases this query: count == 0 => WARN, count > 0 => PASS.
        new ValidationQuery(
            "CALLS_EDGE_COVERAGE",
            "Total CALLS edges in graph (sanity check: should be > 0 after extraction)",
            """
            OPTIONAL MATCH ()-[r:CALLS]->()
            RETURN count(r) AS count, [] AS details
            """,
            ValidationSeverity.WARNING)
    );
  }

  /**
   * Returns an unmodifiable view of all registered validation queries.
   *
   * @return all validation queries in registration order
   */
  public List<ValidationQuery> getQueries() {
    return queries;
  }
}
