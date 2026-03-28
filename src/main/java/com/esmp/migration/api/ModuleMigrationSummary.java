package com.esmp.migration.api;

/**
 * Aggregated migration summary for a module.
 *
 * <p>Provides a high-level view of migration complexity and automation coverage across all classes
 * in the module, suitable for scheduling and prioritization decisions.
 *
 * @param module the module name (third package segment)
 * @param totalClasses total number of classes in the module
 * @param classesWithActions classes that have at least one migration action
 * @param fullyAutomatableClasses classes with automationScore == 1.0
 * @param partiallyAutomatableClasses classes with 0 &lt; automationScore &lt; 1.0
 * @param needsAiOnlyClasses classes with automationScore == 0.0 but migrationActionCount &gt; 0
 * @param averageAutomationScore mean automationScore across all classes with actions
 * @param totalActions total migration action count across the module
 * @param totalAutomatableActions total count of automatable=YES actions in the module
 */
public record ModuleMigrationSummary(
    String module,
    int totalClasses,
    int classesWithActions,
    int fullyAutomatableClasses,
    int partiallyAutomatableClasses,
    int needsAiOnlyClasses,
    double averageAutomationScore,
    int totalActions,
    int totalAutomatableActions) {}
