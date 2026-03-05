package com.esmp.extraction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the structural risk score weight coefficients.
 *
 * <p>Bound from the {@code esmp.risk.weight} prefix in {@code application.yml}. All four weights
 * contribute to the composite structural risk score formula:
 *
 * <pre>
 *   score = w_complexity * log(1 + complexitySum)
 *         + w_fanIn     * log(1 + fanIn)
 *         + w_fanOut    * log(1 + fanOut)
 *         + w_dbWrites  * (hasDbWrites ? 1 : 0)
 * </pre>
 *
 * <p>Default weights sum to 1.0 and can be overridden in {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "esmp.risk.weight")
public class RiskWeightConfig {

  /** Weight for the cyclomatic complexity component. Default: 0.4. */
  private double complexity = 0.4;

  /** Weight for the fan-in (inbound dependencies) component. Default: 0.2. */
  private double fanIn = 0.2;

  /** Weight for the fan-out (outbound dependencies) component. Default: 0.2. */
  private double fanOut = 0.2;

  /** Weight for the DB writes component. Default: 0.2. */
  private double dbWrites = 0.2;

  public double getComplexity() {
    return complexity;
  }

  public void setComplexity(double complexity) {
    this.complexity = complexity;
  }

  public double getFanIn() {
    return fanIn;
  }

  public void setFanIn(double fanIn) {
    this.fanIn = fanIn;
  }

  public double getFanOut() {
    return fanOut;
  }

  public void setFanOut(double fanOut) {
    this.fanOut = fanOut;
  }

  public double getDbWrites() {
    return dbWrites;
  }

  public void setDbWrites(double dbWrites) {
    this.dbWrites = dbWrites;
  }
}
