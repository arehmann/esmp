package com.esmp.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the RAG pipeline ranking weight coefficients.
 *
 * <p>Bound from the {@code esmp.rag.weight} prefix in {@code application.yml}.
 *
 * <p>The three weights control how the final chunk score is computed:
 * <pre>
 *   finalScore = vectorSimilarity * vectorScore
 *              + graphProximity  * graphProximityScore
 *              + riskScore       * normalizedRisk
 * </pre>
 *
 * <p>The weights should sum to 1.0. All values can be overridden in {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "esmp.rag.weight")
public class RagWeightConfig {

  /** Weight for the vector cosine similarity dimension. Default: 0.40. */
  private double vectorSimilarity = 0.40;

  /** Weight for the graph proximity dimension (hop distance from focal class). Default: 0.35. */
  private double graphProximity = 0.35;

  /** Weight for the risk score dimension (enhancedRiskScore contribution). Default: 0.25. */
  private double riskScore = 0.25;

  public double getVectorSimilarity() {
    return vectorSimilarity;
  }

  public void setVectorSimilarity(double vectorSimilarity) {
    this.vectorSimilarity = vectorSimilarity;
  }

  public double getGraphProximity() {
    return graphProximity;
  }

  public void setGraphProximity(double graphProximity) {
    this.graphProximity = graphProximity;
  }

  public double getRiskScore() {
    return riskScore;
  }

  public void setRiskScore(double riskScore) {
    this.riskScore = riskScore;
  }
}
