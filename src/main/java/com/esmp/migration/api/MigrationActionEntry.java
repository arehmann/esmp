package com.esmp.migration.api;

import java.util.List;

/**
 * Represents a single migration action entry returned to API consumers.
 *
 * <p>Maps to a {@code MigrationAction} Neo4j node, carrying the action type, source and target
 * FQNs, automation classification, optional context notes, and transitive enrichment fields added
 * in Phase 17.
 *
 * @param actionType          the action type (e.g., CHANGE_TYPE, CHANGE_PACKAGE, COMPLEX_REWRITE)
 * @param source              the Vaadin 7 or javax fully qualified name being replaced
 * @param target              the Vaadin 24 or jakarta fully qualified name to use instead
 * @param automatable         automation level: YES, PARTIAL, or NO
 * @param context             optional context note explaining manual steps or complexity (may be null)
 * @param isInherited         true if this action was detected via EXTENDS graph traversal
 * @param inheritedFrom       FQN of the ancestor Vaadin type this action is inherited from; null
 *                            for direct actions
 * @param vaadinAncestor      same as inheritedFrom for transitive actions; null for direct actions
 * @param pureWrapper         true if the child class has no overrides or own Vaadin calls (pure
 *                            delegation); false for complex inheritors; null for direct actions
 * @param transitiveComplexity complexity score (0.0..1.0) for inherited actions; null for direct
 *                            actions
 * @param overrideCount       number of ancestor methods overridden by the child class; null for
 *                            direct actions
 * @param ownVaadinCalls      number of distinct Vaadin 7 types the child calls directly (not via
 *                            ancestor); null for direct actions
 * @param migrationSteps      AI-actionable migration steps from the recipe book; may be empty but
 *                            never null
 */
public record MigrationActionEntry(
    String actionType,
    String source,
    String target,
    String automatable,
    String context,
    // Phase 17 transitive enrichment fields
    boolean isInherited,
    String inheritedFrom,
    String vaadinAncestor,
    Boolean pureWrapper,
    Double transitiveComplexity,
    Integer overrideCount,
    Integer ownVaadinCalls,
    List<String> migrationSteps) {}
