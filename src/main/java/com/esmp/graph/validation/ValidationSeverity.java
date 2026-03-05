package com.esmp.graph.validation;

/**
 * Severity level for a graph validation query.
 *
 * <p>ERROR indicates a structural break that must be remediated (orphan nodes, missing required
 * edges, constraint violations). WARNING indicates a suspicious condition that may be acceptable
 * depending on the graph state (e.g., an empty package, a service with no dependencies).
 */
public enum ValidationSeverity {
  ERROR,
  WARNING
}
