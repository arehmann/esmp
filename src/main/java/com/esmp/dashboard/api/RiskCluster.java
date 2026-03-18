package com.esmp.dashboard.api;

/**
 * Risk cluster data for a single module.
 *
 * <p>Used by the governance dashboard risk heatmap to show per-module risk profile.
 */
public record RiskCluster(
    String module,
    int classCount,
    double avgRisk,
    double maxRisk,
    int highRiskCount) {}
