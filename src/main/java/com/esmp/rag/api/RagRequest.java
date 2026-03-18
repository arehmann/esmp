package com.esmp.rag.api;

/**
 * Request body for {@code POST /api/rag/context}.
 *
 * <p>Either {@code query} (natural language or simple class name) or {@code fqn} (explicit
 * fully-qualified name) drives focal class resolution. When {@code fqn} is provided it bypasses
 * the search resolution step entirely.
 *
 * @param query           natural language query or simple class name (required)
 * @param fqn             optional explicit FQN; bypasses resolution when provided
 * @param limit           maximum number of context chunks to return; default 20, max 100
 * @param module          optional module filter applied to cone search
 * @param stereotype      optional stereotype filter applied to cone search
 * @param includeFullSource when true, reads raw {@code .java} source files from disk;
 *                          when false (default) uses chunk text from Qdrant payload
 */
public record RagRequest(
    String query,
    String fqn,
    Integer limit,
    String module,
    String stereotype,
    boolean includeFullSource) {}
