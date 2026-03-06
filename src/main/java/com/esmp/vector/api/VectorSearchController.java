package com.esmp.vector.api;

import com.esmp.vector.application.VectorSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the vector similarity search endpoint.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code POST /api/vector/search} — embeds the query text and returns ranked chunks from Qdrant
 * </ul>
 *
 * <p>This endpoint forms the retrieval foundation for the Phase 11 RAG pipeline.
 */
@RestController
@RequestMapping("/api/vector")
public class VectorSearchController {

  private final VectorSearchService vectorSearchService;

  public VectorSearchController(VectorSearchService vectorSearchService) {
    this.vectorSearchService = vectorSearchService;
  }

  /**
   * Performs vector similarity search over the {@code code_chunks} Qdrant collection.
   *
   * <p>The request body {@code query} field is required. Returns 400 if it is null or blank.
   * Optional filter fields ({@code module}, {@code stereotype}, {@code chunkType}) narrow results
   * to specific subsets of the collection.
   *
   * @param request the search request with query text and optional filters
   * @return 200 with ranked search results, or 400 if the query is blank
   */
  @PostMapping("/search")
  public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest request) {
    if (request == null || request.query() == null || request.query().isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    SearchResponse response = vectorSearchService.search(request);
    return ResponseEntity.ok(response);
  }
}
