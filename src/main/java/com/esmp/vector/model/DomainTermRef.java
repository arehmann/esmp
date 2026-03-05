package com.esmp.vector.model;

/**
 * Lightweight reference to a {@code BusinessTerm} node for use in chunk enrichment payloads.
 *
 * <p>Stored in the Qdrant payload so that retrieval results carry term identifiers without
 * requiring a Neo4j round-trip. Full term details can be fetched from {@code /api/lexicon/{termId}}
 * when needed.
 */
public record DomainTermRef(String termId, String displayName) {}
