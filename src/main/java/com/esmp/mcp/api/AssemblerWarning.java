package com.esmp.mcp.api;

/**
 * A warning emitted by the {@link com.esmp.mcp.application.MigrationContextAssembler} when a
 * downstream service fails to contribute its portion of the migration context.
 *
 * <p>The assembler collects warnings instead of propagating exceptions, so callers always receive a
 * (potentially partial) {@link MigrationContext} rather than an error response.
 *
 * @param service the short service name that failed (e.g., "graph", "risk", "lexicon", "rag")
 * @param message the exception message or human-readable failure description
 */
public record AssemblerWarning(String service, String message) {}
