package com.esmp.graph.validation;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validation query registry for Phase 7 domain-aware risk metrics.
 *
 * <p>Extends {@link ValidationQueryRegistry} to add domain risk validation queries to the Phase 4
 * validation framework. {@link ValidationService} accepts a {@code List<ValidationQueryRegistry>}
 * and aggregates all registered beans, so this registry is automatically discovered without
 * modifying the core service.
 *
 * <p>Provides three validation queries:
 * <ol>
 *   <li>DOMAIN_SCORES_POPULATED (ERROR) — JavaClass nodes where enhancedRiskScore IS NULL;
 *       after extraction + domain risk computation, all classes should have enhanced scores
 *   <li>HIGH_DOMAIN_RISK_NO_BUSINESS_TERMS (WARNING) — Classes with domainCriticality greater than 0
 *       but no USES_TERM edges (possible stale data or graph linking issue)
 *   <li>SECURITY_FINANCIAL_FLAGGED (WARNING) — Total classes with non-zero security or financial
 *       scores (sanity check — should not be zero for most codebases after computation)
 * </ol>
 */
@Component
public class DomainRiskValidationQueryRegistry extends ValidationQueryRegistry {

  public DomainRiskValidationQueryRegistry() {
    super(List.of(

        // 1. DOMAIN_SCORES_POPULATED (ERROR)
        // JavaClass nodes where enhancedRiskScore IS NULL. After a full extraction run,
        // computeAndPersistRiskScores() must have been called for all classes to have enhanced scores.
        new ValidationQuery(
            "DOMAIN_SCORES_POPULATED",
            "JavaClass nodes where enhancedRiskScore IS NULL (domain risk computation may not have run)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.enhancedRiskScore IS NULL
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 2. HIGH_DOMAIN_RISK_NO_BUSINESS_TERMS (WARNING)
        // Classes with domainCriticality > 0 but no USES_TERM edges. This indicates possible stale
        // data (domainCriticality was set but the USES_TERM edges were later removed) or a graph
        // linking issue where computeDomainCriticality() ran before linkBusinessTermUsages().
        new ValidationQuery(
            "HIGH_DOMAIN_RISK_NO_BUSINESS_TERMS",
            "Classes with domainCriticality > 0 but no USES_TERM edges (possible stale data or linking issue)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.domainCriticality > 0.0 AND NOT (c)-[:USES_TERM]->()
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 3. SECURITY_FINANCIAL_FLAGGED (WARNING)
        // Total classes with non-zero security or financial scores. This is a sanity check:
        // after domain risk computation, at least some classes in a real codebase should have
        // non-zero security or financial sensitivity. A count of 0 may indicate the computation
        // did not run or that the codebase has no detectable security/financial patterns.
        new ValidationQuery(
            "SECURITY_FINANCIAL_FLAGGED",
            "Total classes with non-zero security or financial scores (should be > 0 for real codebases)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.securitySensitivity > 0.0 OR c.financialInvolvement > 0.0
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING)
    ));
  }
}
