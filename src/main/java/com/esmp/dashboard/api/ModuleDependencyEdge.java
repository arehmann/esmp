package com.esmp.dashboard.api;

/**
 * A directed cross-module dependency edge with an aggregated weight.
 *
 * <p>Each instance represents the count of DEPENDS_ON relationships between two distinct modules.
 * Used by the governance dashboard dependency graph view.
 */
public record ModuleDependencyEdge(String source, String target, int weight) {}
