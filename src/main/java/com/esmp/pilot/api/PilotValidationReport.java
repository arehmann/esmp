package com.esmp.pilot.api;

import com.esmp.graph.api.ValidationReport;
import java.util.List;

/**
 * Comprehensive pilot validation report combining graph validation results with module-specific
 * migration readiness metrics and a human-readable markdown summary.
 *
 * <p>This report is returned by {@code GET /api/pilot/validate/{module}} and combines:
 * <ul>
 *   <li>Global graph validation — results of all 32+ registered ValidationQuery checks
 *   <li>Module-specific Neo4j metrics — class counts, Vaadin 7 class counts, risk scores, domain terms
 *   <li>Qdrant vector chunk count for the module
 *   <li>Pilot-specific pass/fail checks (e.g., "module has >= 15 classes")
 *   <li>A structured markdown report suitable for human review
 * </ul>
 *
 * @param generatedAt               ISO-8601 timestamp when the report was generated
 * @param pilotModule               name of the module being validated (e.g., "pilot")
 * @param graphValidation           global graph validation report from ValidationService
 * @param classCount                total JavaClass nodes in the pilot module
 * @param vaadin7ClassCount         JavaClass nodes with VaadinView/VaadinComponent/VaadinDataBinding labels
 * @param chunkCount                vector chunks indexed in Qdrant for this module
 * @param avgEnhancedRiskScore      average enhancedRiskScore across module classes
 * @param businessTermCount         distinct BusinessTerm nodes linked to module classes via USES_TERM
 * @param domainTermCoveragePercent percentage of module classes that have at least one USES_TERM edge
 * @param pilotChecks               ordered list of pass/fail pilot-specific checks
 * @param markdownReport            human-readable markdown report string
 */
public record PilotValidationReport(
    String generatedAt,
    String pilotModule,
    ValidationReport graphValidation,
    int classCount,
    int vaadin7ClassCount,
    long chunkCount,
    double avgEnhancedRiskScore,
    int businessTermCount,
    double domainTermCoveragePercent,
    List<PilotCheck> pilotChecks,
    String markdownReport) {}
