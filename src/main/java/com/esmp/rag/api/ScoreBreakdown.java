package com.esmp.rag.api;

/**
 * Per-chunk score decomposition showing how each ranking dimension contributed to the final score.
 *
 * <p>Weights are controlled by {@link com.esmp.rag.config.RagWeightConfig}:
 * vector-similarity (0.40), graph-proximity (0.35), risk-score (0.25).
 *
 * @param vectorScore         raw cosine similarity score from Qdrant (0.0 to 1.0)
 * @param graphProximityScore proximity score derived from hop distance in the dependency cone
 *                            (1.0 = direct neighbor, decreasing with hop count)
 * @param riskScore           normalized risk contribution from {@code enhancedRiskScore}
 * @param finalScore          weighted composite of the three dimensions
 */
public record ScoreBreakdown(
    double vectorScore,
    double graphProximityScore,
    double riskScore,
    double finalScore) {}
