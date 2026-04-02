package com.esmp.scheduling.api;

/**
 * Per-module migration scheduling recommendation with score breakdown.
 *
 * <p>Modules are scored across four weighted dimensions and grouped into topological waves.
 * Lower {@code finalScore} modules are safer to migrate earlier.
 *
 * @param module                  derived module name (from {@code c.module} property)
 * @param waveNumber              topological wave (1 = no dependencies, higher = depends on earlier waves)
 * @param finalScore              composite score [0, 1]; lower = safer earlier migration target
 * @param riskContribution        weighted risk component: {@code risk_weight * normalizedEnhancedRisk}
 * @param dependencyContribution  weighted dependency component: {@code dep_weight * normalizedDependentCount}
 * @param frequencyContribution   weighted frequency component: {@code freq_weight * normalizedCommitCount}
 * @param complexityContribution  weighted complexity component: {@code complexity_weight * normalizedAvgCC}
 * @param dependentCount          number of other modules that depend on this module (fan-in at module level)
 * @param commitCount             git commits touching this module in the configured window
 * @param avgComplexity           average cyclomatic complexity sum per class in this module
 * @param avgEnhancedRisk         average enhanced risk score per class in this module
 * @param classCount              total number of JavaClass nodes in this module
 * @param rationale               human-readable sentence summarising all scoring dimensions
 */
public record ModuleSchedule(
    String module,
    int waveNumber,
    double finalScore,
    double riskContribution,
    double dependencyContribution,
    double frequencyContribution,
    double complexityContribution,
    int dependentCount,
    int commitCount,
    double avgComplexity,
    double avgEnhancedRisk,
    int classCount,
    String rationale
) {}
