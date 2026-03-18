package com.esmp.rag.api;

import java.util.List;

/**
 * Aggregate statistics for the dependency cone traversed during a RAG query.
 *
 * <p>Provides a high-level summary of the cone returned alongside the ranked
 * {@link ContextChunk} list so that callers can assess overall scope and risk.
 *
 * @param totalNodes             total number of nodes in the dependency cone
 * @param vaadin7Count           number of nodes with Vaadin 7 stereotype labels
 * @param avgEnhancedRisk        average enhanced risk score across all cone nodes
 * @param topDomainTerms         deduplicated domain term display names ordered by frequency
 * @param uniqueBusinessTermCount total count of unique business terms referenced in the cone
 */
public record ConeSummary(
    int totalNodes,
    int vaadin7Count,
    double avgEnhancedRisk,
    List<String> topDomainTerms,
    int uniqueBusinessTermCount) {}
