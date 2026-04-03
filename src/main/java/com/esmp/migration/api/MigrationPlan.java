package com.esmp.migration.api;

import java.util.List;

/**
 * Migration plan for a single Java class.
 *
 * <p>Contains the full breakdown of automatable vs. manual migration actions, along with an
 * automation score and AI migration flag for scheduling and prioritization.
 *
 * @param classFqn fully qualified name of the class being migrated
 * @param automatableActions actions that OpenRewrite recipes can apply automatically (automatable=YES)
 * @param manualActions actions requiring human or AI intervention (automatable=PARTIAL or NO)
 * @param totalActions total number of migration actions detected
 * @param automatableCount number of fully automatable actions (YES only)
 * @param manualCount number of non-automatable actions (PARTIAL + NO)
 * @param automationScore ratio: (yesCount + 0.5 * partialCount) / totalCount
 * @param needsAiMigration true if any action has automatable=NO
 * @param hasAlfaIntermediaries true if any action in this plan has inheritedFrom starting with
 *                              "com.alfa." — indicates Layer 2 classes using Alfa* wrappers
 * @param alfaIntermediaryCount count of distinct Alfa* intermediary class FQNs present in this
 *                              plan's inherited actions
 */
public record MigrationPlan(
    String classFqn,
    List<MigrationActionEntry> automatableActions,
    List<MigrationActionEntry> manualActions,
    int totalActions,
    int automatableCount,
    int manualCount,
    double automationScore,
    boolean needsAiMigration,
    // Phase 19 Alfa* fields
    boolean hasAlfaIntermediaries,
    int alfaIntermediaryCount) {}
