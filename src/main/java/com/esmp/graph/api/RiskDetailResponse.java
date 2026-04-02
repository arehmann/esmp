package com.esmp.graph.api;

import java.util.List;

/**
 * Response record for the full risk detail of a single class.
 *
 * <p>Returned by {@code GET /api/risk/class/{fqn}}. Includes all heatmap fields plus a
 * per-method complexity breakdown and the full domain score breakdown.
 *
 * @param fqn                    fully qualified class name
 * @param simpleName             simple class name
 * @param packageName            package containing this class
 * @param complexitySum          sum of cyclomatic complexity across all methods
 * @param complexityMax          max cyclomatic complexity of any single method
 * @param fanIn                  number of other classes that depend on this class
 * @param fanOut                 number of classes this class depends on
 * @param hasDbWrites            true if any method performs a DB write operation
 * @param dbWriteCount           number of DB write methods in this class
 * @param structuralRiskScore    composite structural risk score (log-normalized weighted sum)
 * @param stereotypeLabels       list of stereotype labels applied to this class
 * @param domainCriticality      0.0–1.0 from USES_TERM BusinessTerm criticality (DRISK-01)
 * @param securitySensitivity    0.0–1.0 from keyword/annotation/package heuristics (DRISK-02)
 * @param financialInvolvement   0.0–1.0 from keyword/package/USES_TERM heuristics (DRISK-03)
 * @param businessRuleDensity    log-normalized DEFINES_RULE count (DRISK-04)
 * @param enhancedRiskScore      8-dimension composite score combining structural and domain (DRISK-05)
 * @param businessDescription    human-readable description of the class's business purpose (nullable)
 * @param methods                per-method cyclomatic complexity breakdown
 */
public record RiskDetailResponse(
    String fqn,
    String simpleName,
    String packageName,
    int complexitySum,
    int complexityMax,
    int fanIn,
    int fanOut,
    boolean hasDbWrites,
    int dbWriteCount,
    double structuralRiskScore,
    List<String> stereotypeLabels,
    double domainCriticality,
    double securitySensitivity,
    double financialInvolvement,
    double businessRuleDensity,
    double enhancedRiskScore,
    String businessDescription,
    String curatedClassDescription,
    List<MethodComplexityEntry> methods) {}
