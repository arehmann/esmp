package com.esmp.graph.api;

import java.util.List;

/**
 * Request body for PUT /api/lexicon/{termId} — update a business term's curated metadata.
 *
 * <p>All fields are required. Sending this request sets {@code curated=true} and
 * {@code status='curated'} on the target term, protecting it from future re-extraction overwrites.
 *
 * @param definition  new human-curated definition for this term
 * @param criticality new business criticality: "High", "Medium", or "Low"
 * @param synonyms    updated list of alternative names or acronyms (may be empty)
 */
public record UpdateTermRequest(
    String definition,
    String criticality,
    List<String> synonyms) {}
