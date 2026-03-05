package com.esmp.graph.api;

import com.esmp.graph.validation.ValidationSeverity;
import com.esmp.graph.validation.ValidationStatus;
import java.util.List;

/**
 * Result of executing a single graph validation query.
 *
 * @param name        unique query identifier (e.g., "ORPHAN_CLASS_NODES")
 * @param description human-readable description of what the query checks
 * @param severity    ERROR or WARNING
 * @param status      PASS, FAIL, or WARN
 * @param count       number of violations found (0 for PASS)
 * @param details     up to 20 specific entity identifiers (FQNs, methodIds, etc.) that failed
 */
public record ValidationQueryResult(
    String name,
    String description,
    ValidationSeverity severity,
    ValidationStatus status,
    long count,
    List<String> details) {}
