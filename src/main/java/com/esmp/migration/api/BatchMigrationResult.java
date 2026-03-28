package com.esmp.migration.api;

import java.util.List;

/**
 * Result of applying OpenRewrite recipes to all automatable classes in a module.
 *
 * @param module the module name (derived from the third package segment)
 * @param classesProcessed total number of classes examined
 * @param classesModified number of classes where the recipe produced changes
 * @param totalRecipesApplied sum of recipesApplied across all modified classes
 * @param results per-class migration results (only includes classes with automationScore &gt; 0)
 * @param errors error messages from classes that failed during recipe execution
 * @param durationMs total batch execution time in milliseconds
 */
public record BatchMigrationResult(
    String module,
    int classesProcessed,
    int classesModified,
    int totalRecipesApplied,
    List<MigrationResult> results,
    List<String> errors,
    long durationMs) {}
