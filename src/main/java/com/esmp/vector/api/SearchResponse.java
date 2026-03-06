package com.esmp.vector.api;

import java.util.List;

/**
 * Response from {@code POST /api/vector/search}.
 *
 * <p>Contains ranked search results from Qdrant similarity search, enriched with
 * graph metadata from the vector chunk payload.
 *
 * @param results       ordered list of chunk search results (highest similarity first)
 * @param totalReturned number of results returned (may be less than requested limit)
 * @param query         the original query text that was embedded and searched
 */
public record SearchResponse(
    List<ChunkSearchResult> results,
    int totalReturned,
    String query) {}
