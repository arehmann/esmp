package com.esmp.vector.model;

import java.util.List;
import java.util.UUID;

/**
 * Domain record representing an enriched code chunk ready for embedding and Qdrant upsert.
 *
 * <p>Each chunk is either a {@link ChunkType#CLASS_HEADER} (one per class) or a
 * {@link ChunkType#METHOD} (one per method). The {@code pointId} is a deterministic UUID v5
 * derived from the class FQN and method signature so that upserts are idempotent.
 *
 * <p>Enrichment fields (risk scores, graph neighbors, domain terms, Vaadin state) are
 * populated by {@link com.esmp.vector.application.ChunkingService} from Neo4j at chunk
 * creation time and stored verbatim in the Qdrant payload.
 */
public record CodeChunk(
    /** Deterministic UUID v5 point ID for Qdrant upsert. */
    UUID pointId,

    /** Discriminates class-header vs. method chunks. */
    ChunkType chunkType,

    /** Fully-qualified name of the declaring class. */
    String classFqn,

    /**
     * Method ID in format {@code com.example.MyClass#myMethod(String,int)}.
     * {@code null} for {@link ChunkType#CLASS_HEADER} chunks.
     */
    String methodId,

    /**
     * UUID string of the corresponding CLASS_HEADER chunk.
     * Set on METHOD chunks so RAG can retrieve both together; {@code null} on CLASS_HEADER.
     */
    String classHeaderId,

    /** Formatted source text sent to the embedding model. */
    String text,

    /** Module segment extracted from package name (e.g. "extraction", "graph"). */
    String module,

    /** Primary stereotype label (e.g. "Service", "Repository") or empty string. */
    String stereotype,

    /** SHA-256 hash of the source file, used for incremental re-indexing detection. */
    String contentHash,

    // ---- Risk breakdown (Phase 6 / Phase 7) ----
    double structuralRiskScore,
    double enhancedRiskScore,
    double domainCriticality,
    double securitySensitivity,
    double financialInvolvement,
    double businessRuleDensity,

    // ---- Vaadin migration state (Phase 2) ----
    /** True when the class carries at least one VaadinView/VaadinComponent/VaadinDataBinding label. */
    boolean vaadin7Detected,

    /** List of Vaadin stereotype labels detected on this class (e.g. ["VaadinView"]). */
    List<String> vaadinPatterns,

    // ---- 1-hop graph neighbours (Phase 3 relationships) ----
    /** FQNs of classes that declare a DEPENDS_ON edge pointing TO this class. */
    List<String> callers,

    /** FQNs of classes that methods of this class CALLS into. */
    List<String> callees,

    /** FQNs of classes this class depends on via DEPENDS_ON edges. */
    List<String> dependencies,

    /** FQNs of interfaces this class IMPLEMENTS. */
    List<String> implementors,

    // ---- Domain terms (Phase 5 USES_TERM edges) ----
    List<DomainTermRef> domainTerms) {}
