package com.esmp.extraction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the structural and domain-enhanced risk score weight coefficients.
 *
 * <p>Bound from the {@code esmp.risk.weight} prefix in {@code application.yml}.
 *
 * <p><b>Phase 6 structural weights</b> (sum to 1.0): used by {@code computeStructuralRiskScore()}.
 * <pre>
 *   structuralRiskScore = w_complexity * log(1 + complexitySum)
 *                       + w_fanIn     * log(1 + fanIn)
 *                       + w_fanOut    * log(1 + fanOut)
 *                       + w_dbWrites  * (hasDbWrites ? 1 : 0)
 * </pre>
 *
 * <p><b>Phase 7 enhanced weights</b> (sum to 1.0): used by {@code computeEnhancedRiskScore()}.
 * The enhanced formula combines the 4 structural raw metrics with the 4 domain dimensions:
 * <pre>
 *   enhancedRiskScore = domainComplexity  * log(1 + complexitySum)
 *                     + domainFanIn       * log(1 + fanIn)
 *                     + domainFanOut      * log(1 + fanOut)
 *                     + domainDbWrites    * (hasDbWrites ? 1 : 0)
 *                     + domainCriticality * domainCriticality
 *                     + securitySensitivity * securitySensitivity
 *                     + financialInvolvement * financialInvolvement
 *                     + businessRuleDensity  * businessRuleDensity
 * </pre>
 *
 * <p>All weights can be overridden in {@code application.yml}.
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

  // ---------- Phase 7: domain-enhanced composite weights ----------
  // These 8 weights are used in computeEnhancedRiskScore() and sum to 1.0.

  /** Enhanced structural complexity weight (structural CC component in the enhanced formula). Default: 0.24. */
  private double domainComplexity = 0.24;

  /** Enhanced fan-in weight in the domain-aware composite formula. Default: 0.12. */
  private double domainFanIn = 0.12;

  /** Enhanced fan-out weight in the domain-aware composite formula. Default: 0.12. */
  private double domainFanOut = 0.12;

  /** Enhanced DB-writes weight in the domain-aware composite formula. Default: 0.12. */
  private double domainDbWrites = 0.12;

  /** Weight for the domain criticality dimension (USES_TERM-derived). Default: 0.10. */
  private double domainCriticality = 0.10;

  /** Weight for the security sensitivity dimension (keyword/annotation/package heuristic). Default: 0.10. */
  private double securitySensitivity = 0.10;

  /** Weight for the financial involvement dimension (keyword/package/USES_TERM heuristic). Default: 0.10. */
  private double financialInvolvement = 0.10;

  /** Weight for the business rule density dimension (log-normalized DEFINES_RULE count). Default: 0.10. */
  private double businessRuleDensity = 0.10;

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

  public double getDomainComplexity() {
    return domainComplexity;
  }

  public void setDomainComplexity(double domainComplexity) {
    this.domainComplexity = domainComplexity;
  }

  public double getDomainFanIn() {
    return domainFanIn;
  }

  public void setDomainFanIn(double domainFanIn) {
    this.domainFanIn = domainFanIn;
  }

  public double getDomainFanOut() {
    return domainFanOut;
  }

  public void setDomainFanOut(double domainFanOut) {
    this.domainFanOut = domainFanOut;
  }

  public double getDomainDbWrites() {
    return domainDbWrites;
  }

  public void setDomainDbWrites(double domainDbWrites) {
    this.domainDbWrites = domainDbWrites;
  }

  public double getDomainCriticality() {
    return domainCriticality;
  }

  public void setDomainCriticality(double domainCriticality) {
    this.domainCriticality = domainCriticality;
  }

  public double getSecuritySensitivity() {
    return securitySensitivity;
  }

  public void setSecuritySensitivity(double securitySensitivity) {
    this.securitySensitivity = securitySensitivity;
  }

  public double getFinancialInvolvement() {
    return financialInvolvement;
  }

  public void setFinancialInvolvement(double financialInvolvement) {
    this.financialInvolvement = financialInvolvement;
  }

  public double getBusinessRuleDensity() {
    return businessRuleDensity;
  }

  public void setBusinessRuleDensity(double businessRuleDensity) {
    this.businessRuleDensity = businessRuleDensity;
  }
}
