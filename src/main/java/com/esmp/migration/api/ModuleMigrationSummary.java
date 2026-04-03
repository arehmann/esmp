package com.esmp.migration.api;

import java.util.List;
import com.esmp.migration.api.RecipeRule;

/**
 * Aggregated migration summary for a module.
 *
 * <p>Provides a high-level view of migration complexity and automation coverage across all classes
 * in the module, suitable for scheduling and prioritization decisions.
 *
 * @param module                       the module name (third package segment)
 * @param totalClasses                 total number of classes in the module
 * @param classesWithActions           classes that have at least one migration action
 * @param fullyAutomatableClasses      classes with automationScore == 1.0
 * @param partiallyAutomatableClasses  classes with 0 &lt; automationScore &lt; 1.0
 * @param needsAiOnlyClasses           classes with automationScore == 0.0 but migrationActionCount
 *                                     &gt; 0
 * @param averageAutomationScore       mean automationScore across all classes with actions
 * @param totalActions                 total migration action count across the module
 * @param totalAutomatableActions      total count of automatable=YES actions in the module
 * @param transitiveClassCount         number of classes in the module with at least one inherited
 *                                     (transitive) migration action
 * @param coverageByType               fraction of unique source types in this module that have a
 *                                     mapped recipe (0.0..1.0)
 * @param coverageByUsage              fraction of usage instances in this module that are mapped
 *                                     to a non-NEEDS_MAPPING recipe (0.0..1.0)
 * @param topGaps                      top-5 NEEDS_MAPPING source FQNs sorted by usageCount
 *                                     descending (from recipe book)
 * @param alfaAffectedClassCount       classes with at least one MigrationAction where source
 *                                     starts with "com.alfa.*"
 * @param layer2ClassCount             classes with isInherited=true AND inheritedFrom starts with
 *                                     "com.alfa.*" (Layer 2 business classes using Alfa* wrappers)
 * @param topAlfaGaps                  top-5 NEEDS_MAPPING Alfa* rules sorted by usageCount
 *                                     descending
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
    int totalAutomatableActions,
    // Phase 17 coverage fields
    int transitiveClassCount,
    double coverageByType,
    double coverageByUsage,
    List<String> topGaps,
    // Phase 19 Alfa* fields
    int alfaAffectedClassCount,
    int layer2ClassCount,
    List<RecipeRule> topAlfaGaps) {}
