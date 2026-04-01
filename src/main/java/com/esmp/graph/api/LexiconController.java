package com.esmp.graph.api;

import com.esmp.graph.application.LexiconService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for domain lexicon operations.
 *
 * <p>Exposes 4 endpoints:
 * <ol>
 *   <li>GET /api/lexicon/ — list all business terms with optional filtering
 *   <li>GET /api/lexicon/{termId} — single term detail with related class FQNs (USES_TERM edges)
 *   <li>GET /api/lexicon/by-class/{fqn} — all terms linked to a class via USES_TERM edges
 *   <li>PUT /api/lexicon/{termId} — update a term's definition, criticality, synonyms;
 *       sets curated=true
 * </ol>
 *
 * <p>FQN-style termIds use the {@code :.+} regex suffix to prevent Spring MVC from truncating
 * at the first dot.
 */
@RestController
@RequestMapping("/api/lexicon")
public class LexiconController {

  private final LexiconService lexiconService;

  public LexiconController(LexiconService lexiconService) {
    this.lexiconService = lexiconService;
  }

  /**
   * Returns all business terms with optional filtering.
   *
   * @param criticality optional filter by criticality ("High", "Medium", "Low")
   * @param curated     optional filter by curation status (true/false)
   * @param search      optional case-insensitive substring matched against termId or displayName
   * @param sourceType  optional filter by source type (e.g., "CLASS_NAME", "ENUM", "JAVADOC")
   * @return 200 with list of {@link BusinessTermResponse} (relatedClassFqns is empty for list view)
   */
  @GetMapping({"", "/"})
  public ResponseEntity<List<BusinessTermResponse>> listTerms(
      @RequestParam(required = false) String criticality,
      @RequestParam(required = false) Boolean curated,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) Integer limit) {
    List<BusinessTermResponse> terms = lexiconService.findByFilters(criticality, curated, search, sourceType, limit);
    return ResponseEntity.ok(terms);
  }

  /**
   * Returns the detail view for a single business term, including the FQNs of all JavaClass nodes
   * that have an incoming USES_TERM edge.
   *
   * @param termId the lowercase term identifier (e.g., "invoice")
   * @return 200 with {@link BusinessTermResponse}, or 404 if not found
   */
  @GetMapping("/{termId:.+}")
  public ResponseEntity<BusinessTermResponse> getTerm(@PathVariable String termId) {
    return lexiconService.findByTermId(termId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /**
   * Returns all business terms linked to the given class via USES_TERM edges.
   *
   * @param fqn the fully-qualified class name (e.g., "com.example.OrderService")
   * @return 200 with list of {@link BusinessTermResponse} (relatedClassFqns is empty); empty list
   *     if the class has no linked terms or does not exist
   */
  @GetMapping("/by-class/{fqn:.+}")
  public ResponseEntity<List<BusinessTermResponse>> getTermsByClass(@PathVariable String fqn) {
    return ResponseEntity.ok(lexiconService.findByClassFqn(fqn));
  }

  /**
   * Updates a business term's definition, criticality, and synonyms, marking it as curated.
   * Curated terms are protected from re-extraction overwrites (curated=true guard).
   *
   * @param termId  the lowercase term identifier
   * @param request the update request with definition, criticality, and synonyms
   * @return 200 with updated {@link BusinessTermResponse}, or 404 if term not found
   */
  @PutMapping("/{termId:.+}")
  public ResponseEntity<BusinessTermResponse> updateTerm(
      @PathVariable String termId,
      @RequestBody UpdateTermRequest request) {
    return lexiconService.updateTerm(termId, request.definition(), request.criticality(), request.synonyms())
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
