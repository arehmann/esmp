package com.esmp.rag.api;

/**
 * A ranked context chunk returned as part of a RAG response.
 *
 * <p>Each chunk represents either a {@code CLASS_HEADER} or a {@code METHOD} chunk from the
 * Qdrant {@code code_chunks} collection, constrained to the focal class's dependency cone.
 * The {@link ScoreBreakdown} shows how the final ranking score was computed.
 *
 * @param classFqn            fully-qualified class name owning this chunk
 * @param chunkType           "CLASS_HEADER" or "METHOD"
 * @param methodId            method identifier for METHOD chunks; empty string for CLASS_HEADER
 * @param stereotype          primary stereotype label (e.g., "Service", "Repository")
 * @param codeText            semantic summary text or full source depending on request flags
 * @param relationshipPath    human-readable description of graph distance from the focal class,
 *                            e.g., "DEPENDS_ON (1 hop)" or "multi-hop (3 hops)"
 * @param scores              decomposed score breakdown for this chunk
 * @param structuralRiskScore structural risk score (0.0 to 1.0)
 * @param enhancedRiskScore   domain-enhanced risk score (0.0 to 1.0)
 * @param vaadin7Detected     true if the owning class has Vaadin 7 stereotype labels
 * @param callers             comma-separated list of caller class FQNs
 * @param callees             comma-separated list of callee method IDs
 * @param dependencies        comma-separated list of DEPENDS_ON target FQNs
 * @param domainTerms         JSON array of domain term references ({@code termId}, {@code displayName})
 */
public record ContextChunk(
    String classFqn,
    String chunkType,
    String methodId,
    String stereotype,
    String codeText,
    String relationshipPath,
    ScoreBreakdown scores,
    double structuralRiskScore,
    double enhancedRiskScore,
    boolean vaadin7Detected,
    String callers,
    String callees,
    String dependencies,
    String domainTerms) {}
