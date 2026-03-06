package com.esmp.pilot.api;

/**
 * Ranked module recommendation produced by the pilot module scoring algorithm.
 *
 * <p>Modules are scored using a weighted combination of:
 * <ul>
 *   <li>Vaadin 7 stereotype density (weight 0.4) — fraction of classes with VaadinView/VaadinComponent/VaadinDataBinding labels
 *   <li>Risk diversity (weight 0.3) — standard deviation of enhancedRiskScore across classes
 *   <li>Size appropriateness (weight 0.3) — 1.0 if 15-40 classes, 0.0 otherwise
 * </ul>
 *
 * @param moduleName           the derived module name (e.g., "billing", "pilot")
 * @param classCount           total number of JavaClass nodes in this module
 * @param vaadin7Count         number of classes with VaadinView/VaadinComponent/VaadinDataBinding labels
 * @param avgEnhancedRiskScore average enhancedRiskScore across all classes in the module
 * @param riskDiversity        standard deviation of enhancedRiskScore (higher = more variety)
 * @param score                composite pilot suitability score (0.0 to 1.0+)
 * @param rationale            human-readable explanation of the score components
 */
public record ModuleRecommendation(
    String moduleName,
    int classCount,
    int vaadin7Count,
    double avgEnhancedRiskScore,
    double riskDiversity,
    double score,
    String rationale) {}
