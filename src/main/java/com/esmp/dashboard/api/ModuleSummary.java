package com.esmp.dashboard.api;

/**
 * Per-module summary combining Vaadin 7 API density, heatmap score, and risk data.
 *
 * <p>Used by the governance dashboard to display a module-level overview grid.
 */
public record ModuleSummary(
    String module,
    int classCount,
    int vaadin7Count,
    double vaadin7Pct,
    double heatmapScore,
    double avgEnhancedRisk,
    int highRiskCount) {}
