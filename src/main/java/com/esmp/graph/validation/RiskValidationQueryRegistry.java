package com.esmp.graph.validation;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validation query registry for Phase 6 structural risk metrics.
 *
 * <p>Extends {@link ValidationQueryRegistry} to add risk-specific validation queries to the
 * Phase 4 validation framework. {@link ValidationService} accepts a
 * {@code List<ValidationQueryRegistry>} and aggregates all registered beans, so this registry is
 * automatically discovered without modifying the core service.
 *
 * <p>Provides three validation queries:
 * <ol>
 *   <li>RISK_SCORES_POPULATED (ERROR) — JavaClass nodes where structuralRiskScore IS NULL;
 *       after extraction + risk computation, all classes should have scores
 *   <li>FAN_IN_OUT_POPULATED (ERROR) — JavaClass nodes where fanIn IS NULL OR fanOut IS NULL;
 *       fan-in/out must be computed before risk scores
 *   <li>HIGH_RISK_NO_DEPENDENCIES (WARNING) — Classes with score greater than 2.0 and no
 *       DEPENDS_ON edges in either direction (possible false-positive utility classes)
 * </ol>
 */
@Component
public class RiskValidationQueryRegistry extends ValidationQueryRegistry {

  public RiskValidationQueryRegistry() {
    super(List.of(

        // 1. RISK_SCORES_POPULATED (ERROR)
        // JavaClass nodes where structuralRiskScore IS NULL. After a full extraction run,
        // computeAndPersistRiskScores() must have been called for all classes to have scores.
        new ValidationQuery(
            "RISK_SCORES_POPULATED",
            "JavaClass nodes where structuralRiskScore IS NULL (risk computation may not have run)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.structuralRiskScore IS NULL
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 2. FAN_IN_OUT_POPULATED (ERROR)
        // JavaClass nodes where fanIn or fanOut is NULL. Fan-in/out must be set before
        // the composite score can be computed from them.
        new ValidationQuery(
            "FAN_IN_OUT_POPULATED",
            "JavaClass nodes where fanIn IS NULL OR fanOut IS NULL (fan-in/out computation missing)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.fanIn IS NULL OR c.fanOut IS NULL
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 3. HIGH_RISK_NO_DEPENDENCIES (WARNING)
        // Classes with a high risk score (> 2.0) but zero DEPENDS_ON edges in either direction.
        // These may be false-positive utility classes with high CC but no coupling — worth reviewing.
        new ValidationQuery(
            "HIGH_RISK_NO_DEPENDENCIES",
            "High-risk classes (score > 2.0) with no DEPENDS_ON edges (possible false-positive utility classes)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.structuralRiskScore > 2.0
              AND NOT (c)-[:DEPENDS_ON]->()
              AND NOT ()-[:DEPENDS_ON]->(c)
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING)
    ));
  }
}
