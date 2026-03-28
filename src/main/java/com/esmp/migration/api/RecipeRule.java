package com.esmp.migration.api;

import java.util.List;

/**
 * Immutable record representing a single Vaadin 7 → Vaadin 24 migration rule in the recipe book.
 *
 * <p>Rules are loaded from the seed JSON file at startup and can be extended via a custom overlay
 * file. The {@code source} field is the merge key: overlay rules replace base rules with the same
 * source FQN.
 *
 * <p>The {@code isBase} flag is set by {@link RecipeBookRegistry} at load time and is NOT stored
 * in the JSON file.
 */
public record RecipeRule(
    /** Stable ID for this rule, e.g. "COMP-001". */
    String id,

    /** Rule category. One of: COMPONENT, DATA_BINDING, SERVER, JAVAX_JAKARTA, DISCOVERED. */
    String category,

    /** Vaadin 7 fully qualified name — primary key for overlay merge. */
    String source,

    /** Vaadin 24 fully qualified name, or {@code null} for NEEDS_MAPPING / COMPLEX_REWRITE rules. */
    String target,

    /** Action type. One of: CHANGE_TYPE, CHANGE_PACKAGE, COMPLEX_REWRITE. */
    String actionType,

    /** Automation classification. One of: YES, PARTIAL, NO. */
    String automatable,

    /** Human-readable explanation of the migration action; may be {@code null}. */
    String context,

    /** AI-actionable migration step list; may be empty but never null. */
    List<String> migrationSteps,

    /** Rule status. One of: MAPPED, NEEDS_MAPPING. */
    String status,

    /** Usage count, updated by enrichment during extraction. */
    int usageCount,

    /** ISO date when a DISCOVERED rule was first seen; {@code null} for base/overlay rules. */
    String discoveredAt,

    /** {@code true} for seed rules, {@code false} for overlay or discovered rules. Set by registry at load time. */
    boolean isBase
) {}
