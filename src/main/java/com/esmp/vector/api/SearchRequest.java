package com.esmp.vector.api;

/**
 * Request body for {@code POST /api/vector/search}.
 *
 * <p>Embeds the {@code query} text using the Spring AI EmbeddingModel (all-MiniLM-L6-v2) and
 * searches the Qdrant {@code code_chunks} collection. Optional filter fields narrow results to
 * specific modules, stereotypes, or chunk types.
 *
 * @param query      text query to embed and search (required, must not be blank)
 * @param limit      maximum number of results to return (default 10 if null)
 * @param module     optional module filter (e.g., "pilot", "billing") — maps to payload field "module"
 * @param stereotype optional stereotype filter (e.g., "Service", "Repository") — maps to payload field "stereotype"
 * @param chunkType  optional chunk type filter ("CLASS_HEADER" or "METHOD") — maps to payload field "chunkType"
 */
public record SearchRequest(
    String query,
    Integer limit,
    String module,
    String stereotype,
    String chunkType) {}
