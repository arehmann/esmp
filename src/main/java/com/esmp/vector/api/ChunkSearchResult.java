package com.esmp.vector.api;

/**
 * A single ranked result from {@code POST /api/vector/search}.
 *
 * <p>Fields are extracted from the Qdrant point payload. All payload fields stored by
 * {@link com.esmp.vector.application.VectorIndexingService} are included except {@code text}
 * (the embedding source text is not stored as payload — only the vector embedding is persisted).
 *
 * @param score                Qdrant cosine similarity score (0.0 to 1.0, higher = more similar)
 * @param classFqn             fully-qualified class name (e.g., "com.esmp.pilot.InvoiceService")
 * @param chunkType            "CLASS_HEADER" or "METHOD"
 * @param methodId             method identifier for METHOD chunks (empty string for CLASS_HEADER)
 * @param module               derived module name (e.g., "pilot")
 * @param stereotype           primary stereotype label (e.g., "Service", "Repository")
 * @param structuralRiskScore  structural risk score (0.0 to 1.0)
 * @param enhancedRiskScore    domain-enhanced risk score (0.0 to 1.0)
 * @param vaadin7Detected      true if the class has any Vaadin 7 stereotype labels
 * @param callers              comma-separated list of caller class FQNs
 * @param callees              comma-separated list of callee method IDs
 * @param dependencies         comma-separated list of DEPENDS_ON target FQNs
 * @param domainTerms          JSON array of domain term references (compact format)
 */
public record ChunkSearchResult(
    float score,
    String classFqn,
    String chunkType,
    String methodId,
    String module,
    String stereotype,
    double structuralRiskScore,
    double enhancedRiskScore,
    boolean vaadin7Detected,
    String callers,
    String callees,
    String dependencies,
    String domainTerms) {}
