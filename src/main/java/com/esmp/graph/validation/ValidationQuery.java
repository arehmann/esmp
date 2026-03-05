package com.esmp.graph.validation;

/**
 * Immutable definition of a single graph validation query.
 *
 * <p>Each Cypher query MUST return exactly two columns:
 * <ul>
 *   <li>{@code count AS count} — long count of violations (0 means passing)
 *   <li>{@code details AS details} — list of strings describing each violation (capped at 20)
 * </ul>
 *
 * @param name        unique identifier (e.g., "ORPHAN_CLASS_NODES")
 * @param description human-readable description for the report
 * @param cypher      the Cypher query; must return columns: count AS count, details AS details
 * @param severity    ERROR for structural breaks, WARNING for suspicious but non-fatal conditions
 */
public record ValidationQuery(
    String name,
    String description,
    String cypher,
    ValidationSeverity severity) {}
