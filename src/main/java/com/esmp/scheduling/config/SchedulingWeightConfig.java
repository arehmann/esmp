package com.esmp.scheduling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the module scheduling score weight coefficients.
 *
 * <p>Bound from the {@code esmp.scheduling.weight} prefix in {@code application.yml}.
 *
 * <p>The composite score formula is:
 * <pre>
 *   finalScore = risk        * normalizedRisk
 *              + dependency  * normalizedDependency
 *              + frequency   * normalizedFrequency
 *              + complexity  * normalizedComplexity
 * </pre>
 *
 * <p>Default weights sum to 1.0. All weights and the git window can be overridden in
 * {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "esmp.scheduling.weight")
public class SchedulingWeightConfig {

  /** Weight for the average enhanced risk score dimension. Default: 0.35. */
  private double risk = 0.35;

  /** Weight for the number of dependent modules (fan-in at module level). Default: 0.25. */
  private double dependency = 0.25;

  /** Weight for the git commit frequency dimension. Default: 0.20. */
  private double frequency = 0.20;

  /** Weight for the average cyclomatic complexity dimension. Default: 0.20. */
  private double complexity = 0.20;

  /** Number of days to look back in git log for commit frequency. Default: 180. */
  private int gitWindowDays = 180;

  public double getRisk() {
    return risk;
  }

  public void setRisk(double risk) {
    this.risk = risk;
  }

  public double getDependency() {
    return dependency;
  }

  public void setDependency(double dependency) {
    this.dependency = dependency;
  }

  public double getFrequency() {
    return frequency;
  }

  public void setFrequency(double frequency) {
    this.frequency = frequency;
  }

  public double getComplexity() {
    return complexity;
  }

  public void setComplexity(double complexity) {
    this.complexity = complexity;
  }

  public int getGitWindowDays() {
    return gitWindowDays;
  }

  public void setGitWindowDays(int gitWindowDays) {
    this.gitWindowDays = gitWindowDays;
  }
}
