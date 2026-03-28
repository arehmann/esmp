package com.esmp.migration.api;

/**
 * Represents a single migration action entry returned to API consumers.
 *
 * <p>Maps to a {@code MigrationAction} Neo4j node, carrying the action type, source and target
 * FQNs, automation classification, and optional context notes.
 *
 * @param actionType the action type (e.g., CHANGE_TYPE, CHANGE_PACKAGE, COMPLEX_REWRITE)
 * @param source the Vaadin 7 or javax fully qualified name being replaced
 * @param target the Vaadin 24 or jakarta fully qualified name to use instead
 * @param automatable automation level: YES, PARTIAL, or NO
 * @param context optional context note explaining manual steps or complexity (may be null)
 */
public record MigrationActionEntry(
    String actionType,
    String source,
    String target,
    String automatable,
    String context) {}
