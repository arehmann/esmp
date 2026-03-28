package com.esmp.migration.validation;

import com.esmp.graph.validation.ValidationQuery;
import com.esmp.graph.validation.ValidationQueryRegistry;
import com.esmp.graph.validation.ValidationSeverity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Migration analysis validation query registry.
 *
 * <p>Contributes 3 graph validation queries that verify the migration action data produced by
 * {@link com.esmp.extraction.visitor.MigrationPatternVisitor}. These checks ensure that the
 * OpenRewrite recipe engine has the required data to operate.
 *
 * <p>Queries:
 * <ol>
 *   <li>{@code MIGRATION_ACTIONS_POPULATED} — verifies that MigrationAction nodes exist in the
 *       graph (extraction was run).
 *   <li>{@code MIGRATION_SCORES_COMPUTED} — verifies that ClassNode migration scores were computed
 *       (automationScore is set on classes with actions).
 *   <li>{@code MIGRATION_ACTION_EDGES_INTACT} — verifies that the edge count from
 *       HAS_MIGRATION_ACTION edges matches the migrationActionCount property on each class.
 * </ol>
 *
 * <p>With these queries added, the total validation query count becomes 44 (41 from prior phases
 * + 3 migration queries).
 */
@Component
public class MigrationValidationQueryRegistry extends ValidationQueryRegistry {

  public MigrationValidationQueryRegistry() {
    super(
        List.of(

            // 1. MIGRATION_ACTIONS_POPULATED (WARNING)
            // Verifies that MigrationAction nodes exist — if count = 0, migration extraction
            // has not been run or found no Vaadin 7 usage.
            new ValidationQuery(
                "MIGRATION_ACTIONS_POPULATED",
                "MigrationAction nodes exist in the graph (migration extraction has been run)",
                """
                MATCH (ma:MigrationAction)
                WITH count(ma) AS total
                RETURN CASE WHEN total = 0 THEN 1 ELSE 0 END AS count,
                       CASE WHEN total = 0
                            THEN ['No MigrationAction nodes found — run extraction on Vaadin 7 source']
                            ELSE []
                       END AS details
                """,
                ValidationSeverity.WARNING),

            // 2. MIGRATION_SCORES_COMPUTED (WARNING)
            // Verifies that at least one class with migration actions has an automationScore set.
            // count = 0 means FAIL (scores were not computed during extraction).
            new ValidationQuery(
                "MIGRATION_SCORES_COMPUTED",
                "Classes with migration actions have automationScore computed",
                """
                MATCH (c:JavaClass)
                WHERE c.migrationActionCount > 0 AND c.automationScore IS NOT NULL
                WITH count(c) AS scored
                RETURN CASE WHEN scored = 0 THEN 1 ELSE 0 END AS count,
                       CASE WHEN scored = 0
                            THEN ['No classes with migrationActionCount > 0 have automationScore set']
                            ELSE []
                       END AS details
                """,
                ValidationSeverity.WARNING),

            // 3. MIGRATION_ACTION_EDGES_INTACT (ERROR)
            // Verifies that for each class, the number of HAS_MIGRATION_ACTION outgoing edges
            // matches the migrationActionCount property. Violations indicate partial linking failures.
            new ValidationQuery(
                "MIGRATION_ACTION_EDGES_INTACT",
                "HAS_MIGRATION_ACTION edge counts match migrationActionCount property on each class",
                """
                MATCH (c:JavaClass)-[:HAS_MIGRATION_ACTION]->(ma:MigrationAction)
                WITH c, count(ma) AS edgeCount
                WHERE edgeCount <> c.migrationActionCount
                RETURN count(c) AS count,
                       collect(c.fullyQualifiedName + ' edges=' + toString(edgeCount)
                               + ' prop=' + toString(c.migrationActionCount))[0..20] AS details
                """,
                ValidationSeverity.ERROR)));
  }
}
