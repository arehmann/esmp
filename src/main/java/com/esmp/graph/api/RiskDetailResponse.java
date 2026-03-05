package com.esmp.graph.api;

import java.util.List;

/**
 * Response record for the full risk detail of a single class.
 *
 * <p>Returned by {@code GET /api/risk/class/{fqn}}. Includes all heatmap fields plus a
 * per-method complexity breakdown.
 *
 * @param fqn                 fully qualified class name
 * @param simpleName          simple class name
 * @param packageName         package containing this class
 * @param complexitySum       sum of cyclomatic complexity across all methods
 * @param complexityMax       max cyclomatic complexity of any single method
 * @param fanIn               number of other classes that depend on this class
 * @param fanOut              number of classes this class depends on
 * @param hasDbWrites         true if any method performs a DB write operation
 * @param dbWriteCount        number of DB write methods in this class
 * @param structuralRiskScore composite risk score (log-normalized weighted sum)
 * @param stereotypeLabels    list of stereotype labels applied to this class
 * @param methods             per-method cyclomatic complexity breakdown
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
    List<MethodComplexityEntry> methods) {}
