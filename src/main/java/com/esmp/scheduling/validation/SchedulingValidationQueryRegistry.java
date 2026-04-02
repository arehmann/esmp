package com.esmp.scheduling.validation;

import com.esmp.graph.validation.ValidationQuery;
import com.esmp.graph.validation.ValidationQueryRegistry;
import com.esmp.graph.validation.ValidationSeverity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scheduling-specific validation query registry.
 *
 * <p>Contributes 3 queries to the global validation report covering the pre-conditions required
 * for meaningful scheduling recommendations:
 * <ol>
 *   <li>{@code SCHEDULING_MODULES_EXIST} — fails if no JavaClass nodes with packageName exist
 *       (extraction has not been run)
 *   <li>{@code SCHEDULING_RISK_SCORES_POPULATED} — warns if any modules have zero average
 *       enhanced risk score (domain risk analysis may not have run)
 *   <li>{@code SCHEDULING_DEPENDENCY_EDGES_EXIST} — informs if no cross-module DEPENDS_ON edges
 *       exist (topological sort will assign all modules to wave 1)
 * </ol>
 */
@Component
public class SchedulingValidationQueryRegistry extends ValidationQueryRegistry {

  public SchedulingValidationQueryRegistry() {
    super(List.of(

        // 1. SCHEDULING_MODULES_EXIST (ERROR)
        // Violation: no JavaClass nodes with packageName found — extraction has not run yet.
        new ValidationQuery(
            "SCHEDULING_MODULES_EXIST",
            "At least one JavaClass node with a packageName must exist for scheduling recommendations",
            """
            OPTIONAL MATCH (c:JavaClass) WHERE c.packageName IS NOT NULL AND size(c.packageName) > 0
            WITH count(c) AS total
            WHERE total = 0
            RETURN 1 AS count, ['No JavaClass nodes found - run extraction first'] AS details
            """,
            ValidationSeverity.ERROR),

        // 2. SCHEDULING_RISK_SCORES_POPULATED (WARNING)
        // Violation: modules with zero avg enhancedRiskScore (domain risk not computed).
        new ValidationQuery(
            "SCHEDULING_RISK_SCORES_POPULATED",
            "All modules should have non-zero average enhanced risk scores for accurate scheduling",
            """
            MATCH (c:JavaClass)
            WHERE c.module IS NOT NULL AND c.module <> ''
            WITH c.module AS module, avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgRisk
            WHERE avgRisk = 0.0
            RETURN count(module) AS count, collect(module) AS details
            """,
            ValidationSeverity.WARNING),

        // 3. SCHEDULING_DEPENDENCY_EDGES_EXIST (WARNING)
        // Informational: no cross-module DEPENDS_ON edges means all modules land in wave 1.
        new ValidationQuery(
            "SCHEDULING_DEPENDENCY_EDGES_EXIST",
            "Cross-module DEPENDS_ON edges should exist for multi-wave topological scheduling",
            """
            MATCH (c1:JavaClass)-[:DEPENDS_ON]->(c2:JavaClass)
            WHERE c1.module IS NOT NULL AND c2.module IS NOT NULL
            WITH c1.module AS s, c2.module AS t
            WHERE s <> '' AND t <> '' AND s <> t
            WITH count(*) AS total
            WHERE total = 0
            RETURN 1 AS count, ['No cross-module DEPENDS_ON edges found - all modules will be Wave 1'] AS details
            """,
            ValidationSeverity.WARNING)
    ));
  }
}
