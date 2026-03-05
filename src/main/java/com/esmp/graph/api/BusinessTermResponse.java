package com.esmp.graph.api;

import java.util.List;

/**
 * REST API response record representing a domain business term from the lexicon.
 *
 * <p>Used by both the list endpoint (GET /api/lexicon/) and the detail endpoint
 * (GET /api/lexicon/{termId}). For the list view, {@code relatedClassFqns} is an empty list
 * to minimize payload size. The detail endpoint populates {@code relatedClassFqns} from
 * incoming USES_TERM edges.
 *
 * @param termId           lowercased normalized term identifier (e.g., "invoice")
 * @param displayName      display-friendly term name (e.g., "Invoice")
 * @param definition       human-readable definition; null if not yet set
 * @param criticality      business criticality: "High", "Medium", or "Low"
 * @param migrationSensitivity migration-time data sensitivity: "Critical", "Moderate", or "None"
 * @param synonyms         alternative names or acronyms
 * @param curated          true if a human has manually reviewed/edited this term
 * @param status           lifecycle status: "auto", "curated", or "deprecated"
 * @param sourceType       extraction origin: "CLASS_NAME", "ENUM_CONSTANT", "DB_TABLE"
 * @param primarySourceFqn FQN of the class where this term was first extracted
 * @param usageCount       number of source classes referencing this term
 * @param relatedClassFqns FQNs of JavaClass nodes connected via USES_TERM edges (detail only)
 */
public record BusinessTermResponse(
    String termId,
    String displayName,
    String definition,
    String criticality,
    String migrationSensitivity,
    List<String> synonyms,
    boolean curated,
    String status,
    String sourceType,
    String primarySourceFqn,
    int usageCount,
    List<String> relatedClassFqns) {}
