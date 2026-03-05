package com.esmp.graph.api;

import java.util.List;

/**
 * Top-level validation report returned by {@code GET /api/graph/validation}.
 *
 * <p>Contains the results of all 20 canonical validation queries along with aggregate counts.
 * Designed to be used as a "health check for the graph" — run after extraction to confirm the
 * graph is structurally sound before building semantic layers on top.
 *
 * @param generatedAt  ISO-8601 timestamp of when the report was generated
 * @param results      one result per validation query (20 total for Phase 4)
 * @param errorCount   number of queries with FAIL status (ERROR severity with violations)
 * @param warnCount    number of queries with WARN status (WARNING severity with violations)
 * @param passCount    number of queries with PASS status (no violations found)
 */
public record ValidationReport(
    String generatedAt,
    List<ValidationQueryResult> results,
    long errorCount,
    long warnCount,
    long passCount) {}
