package com.esmp.graph.api;

import java.util.List;

/**
 * Response record for a single entry in the structural risk heatmap.
 *
 * <p>Each entry represents a JavaClass node with all computed risk metrics. Entries in the heatmap
 * response list are sorted by descending {@code structuralRiskScore}.
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
 */
public record RiskHeatmapEntry(
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
    List<String> stereotypeLabels) {}
