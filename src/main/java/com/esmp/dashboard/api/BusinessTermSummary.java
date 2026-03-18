package com.esmp.dashboard.api;

import java.util.List;

/**
 * Business term summary with linked class FQNs for the governance dashboard concept graph.
 *
 * <p>Captures term metadata and all classes that reference the term via USES_TERM or DEFINES_RULE.
 */
public record BusinessTermSummary(
    String termId,
    String displayName,
    String criticality,
    boolean curated,
    List<String> classFqns) {}
