package com.esmp.rag.api;

import java.util.List;

/**
 * Main response wrapper for {@code POST /api/rag/context}.
 *
 * <p>Either {@code focalClass}/{@code contextChunks}/{@code coneSummary} are populated
 * (successful resolution) or {@code disambiguation} is populated (ambiguous simple name).
 * Use {@link #isDisambiguation()} to branch on the response type.
 *
 * @param queryType       how the focal class was resolved: "FQN", "SIMPLE_NAME", or
 *                        "NATURAL_LANGUAGE"
 * @param focalClass      full detail for the resolved focal class; null for disambiguation
 *                        responses
 * @param contextChunks   ranked context chunks from the dependency cone; null for
 *                        disambiguation responses
 * @param coneSummary     aggregate cone statistics; null for disambiguation responses
 * @param disambiguation  disambiguation candidates when multiple classes match; null for
 *                        non-ambiguous responses
 * @param durationMs      total wall-clock time for the RAG pipeline execution in milliseconds
 */
public record RagResponse(
    String queryType,
    FocalClassDetail focalClass,
    List<ContextChunk> contextChunks,
    ConeSummary coneSummary,
    DisambiguationResponse disambiguation,
    long durationMs) {

  /**
   * Returns {@code true} when this response is a disambiguation prompt rather than a full
   * RAG result. Callers should inspect {@link #disambiguation()} for candidate FQNs.
   */
  public boolean isDisambiguation() {
    return disambiguation != null;
  }
}
