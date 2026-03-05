package com.esmp.graph.validation;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validation query registry for Phase 5 domain lexicon edges.
 *
 * <p>Extends {@link ValidationQueryRegistry} to add lexicon-specific validation queries to the
 * Phase 4 validation framework. {@link ValidationService} accepts a
 * {@code List<ValidationQueryRegistry>} and aggregates all registered beans, so this registry is
 * automatically discovered without modifying the core service.
 *
 * <p>Provides three validation queries:
 * <ol>
 *   <li>ORPHAN_BUSINESS_TERMS (WARNING) — BusinessTerm nodes with no incoming USES_TERM edge
 *   <li>DEFINES_RULE_COVERAGE (WARNING) — Business-rule-pattern classes with no DEFINES_RULE edge
 *   <li>USES_TERM_EDGE_INTEGRITY (ERROR) — USES_TERM edges with invalid endpoint types
 * </ol>
 */
@Component
public class LexiconValidationQueryRegistry extends ValidationQueryRegistry {

  public LexiconValidationQueryRegistry() {
    super(List.of(

        // 1. ORPHAN_BUSINESS_TERMS (WARNING)
        // BusinessTerm nodes with no incoming USES_TERM edge from any JavaClass.
        // These terms were extracted but no code class references them — may indicate stale terms
        // or missing linking after re-extraction.
        new ValidationQuery(
            "ORPHAN_BUSINESS_TERMS",
            "BusinessTerm nodes with no incoming USES_TERM edge (extracted but not linked to code)",
            """
            OPTIONAL MATCH (t:BusinessTerm) WHERE NOT ()-[:USES_TERM]->(t)
            RETURN count(t) AS count, collect(t.termId)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 2. DEFINES_RULE_COVERAGE (WARNING)
        // JavaClass nodes matching business-rule naming patterns that have no DEFINES_RULE edge.
        // These classes implement business rules but have not been linked to their domain terms.
        new ValidationQuery(
            "DEFINES_RULE_COVERAGE",
            "Business-rule-pattern classes (Validator/Rule/Policy/etc.) with no DEFINES_RULE edge",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.simpleName =~ '.*(Validator|Rule|Policy|Constraint|Calculator|Strategy).*'
              AND NOT (c)-[:DEFINES_RULE]->()
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 3. USES_TERM_EDGE_INTEGRITY (ERROR)
        // USES_TERM relationships where the source is not a JavaClass or the target is not a
        // BusinessTerm. Both violations indicate data integrity issues from incorrect linking.
        new ValidationQuery(
            "USES_TERM_EDGE_INTEGRITY",
            "USES_TERM edges with invalid endpoints: source must be JavaClass, target must be BusinessTerm",
            """
            OPTIONAL MATCH (src)-[r:USES_TERM]->(tgt)
            WHERE NOT src:JavaClass OR NOT tgt:BusinessTerm
            RETURN count(r) AS count,
                   collect(COALESCE(src.fullyQualifiedName, 'unknown') + ' -USES_TERM-> '
                     + COALESCE(tgt.termId, 'unknown'))[0..20] AS details
            """,
            ValidationSeverity.ERROR)
    ));
  }
}
