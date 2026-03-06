package com.esmp.pilot.validation;

import com.esmp.graph.validation.ValidationQuery;
import com.esmp.graph.validation.ValidationQueryRegistry;
import com.esmp.graph.validation.ValidationSeverity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pilot-specific validation query registry extending the global ValidationQueryRegistry pattern.
 *
 * <p>Contributes 3 violation queries targeting the synthetic pilot module. These queries follow
 * the standard violation convention: {@code count = 0} means pass (no violation detected).
 *
 * <p>Important: These queries use static Cypher with the hardcoded {@code 'pilot'} module name.
 * For parameterized, module-scoped checks, use {@link com.esmp.pilot.application.PilotService}
 * which can bind parameters via {@code Neo4jClient.query(...).bind(moduleName).to("module")}.
 *
 * <p>Queries:
 * <ol>
 *   <li>{@code PILOT_MODULE_CLASS_COUNT} — violation if pilot module has fewer than 15 classes
 *   <li>{@code PILOT_VAADIN7_NODES_PRESENT} — violation if no Vaadin 7 labeled classes exist in pilot module
 *   <li>{@code PILOT_BUSINESS_TERMS_EXTRACTED} — violation if no USES_TERM edges exist for pilot module classes
 * </ol>
 */
@Component
public class PilotValidationQueryRegistry extends ValidationQueryRegistry {

  public PilotValidationQueryRegistry() {
    super(List.of(

        // 1. PILOT_MODULE_CLASS_COUNT (ERROR)
        // Violation: pilot module has fewer than 15 classes (pipeline underloaded).
        // count > 0 means we found the "total < 15" subquery returned a row — meaning too few classes.
        new ValidationQuery(
            "PILOT_MODULE_CLASS_COUNT",
            "Pilot module must have at least 15 JavaClass nodes for meaningful validation",
            """
            MATCH (c:JavaClass) WHERE c.module = 'pilot'
            WITH count(c) AS total
            WHERE total < 15
            RETURN total AS count, ['Expected >= 15 pilot classes, found: ' + toString(total)] AS details
            """,
            ValidationSeverity.ERROR),

        // 2. PILOT_VAADIN7_NODES_PRESENT (WARNING)
        // Violation: no VaadinView/VaadinComponent/VaadinDataBinding classes in pilot module.
        // OPTIONAL MATCH returns 0 rows (total=0) when no Vaadin classes exist — that's the violation.
        new ValidationQuery(
            "PILOT_VAADIN7_NODES_PRESENT",
            "Pilot module must contain at least one VaadinView, VaadinComponent, or VaadinDataBinding class",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.module = 'pilot'
              AND ANY(l IN labels(c) WHERE l IN ['VaadinView', 'VaadinComponent', 'VaadinDataBinding'])
            WITH count(c) AS total
            WHERE total = 0
            RETURN 1 AS count, ['No Vaadin 7 labeled classes found in pilot module'] AS details
            """,
            ValidationSeverity.WARNING),

        // 3. PILOT_BUSINESS_TERMS_EXTRACTED (WARNING)
        // Violation: no USES_TERM edges exist for pilot module classes.
        // OPTIONAL MATCH returns 0 rows when no business terms are linked — that's the violation.
        new ValidationQuery(
            "PILOT_BUSINESS_TERMS_EXTRACTED",
            "Pilot module classes must have at least one USES_TERM edge to a BusinessTerm node",
            """
            OPTIONAL MATCH (c:JavaClass)-[:USES_TERM]->(bt:BusinessTerm)
            WHERE c.module = 'pilot'
            WITH count(bt) AS total
            WHERE total = 0
            RETURN 1 AS count, ['No USES_TERM edges found for pilot module classes'] AS details
            """,
            ValidationSeverity.WARNING)
    ));
  }
}
