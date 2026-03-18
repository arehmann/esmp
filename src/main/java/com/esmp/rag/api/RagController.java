package com.esmp.rag.api;

import com.esmp.rag.application.RagService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the RAG context assembly endpoint.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code POST /api/rag/context} — resolves the focal class, expands the dependency cone,
 *       performs cone-constrained vector search, and returns a ranked migration context package.
 * </ul>
 *
 * <p>Either {@code fqn} or {@code query} must be provided in the request body. When the query
 * matches multiple class simple names, a disambiguation response (HTTP 200) is returned instead
 * of the full context package. When the resolved class is not found in the graph, HTTP 404 is
 * returned.
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

  private final RagService ragService;

  public RagController(RagService ragService) {
    this.ragService = ragService;
  }

  /**
   * Assembles a RAG migration context package for the given focal class or query.
   *
   * @param request the RAG request body
   * @return 200 with {@link RagResponse} (or disambiguation), 400 for invalid input, 404 if class
   *         not found
   */
  @PostMapping("/context")
  public ResponseEntity<?> getContext(@RequestBody RagRequest request) {
    // Validate request
    if (request == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
    }
    boolean noQuery = request.query() == null || request.query().isBlank();
    boolean noFqn = request.fqn() == null || request.fqn().isBlank();
    if (noQuery && noFqn) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "Either 'query' or 'fqn' must be provided"));
    }
    if (request.limit() != null && (request.limit() < 1 || request.limit() > 100)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "Limit must be between 1 and 100"));
    }

    RagResponse response = ragService.assemble(request);

    // Disambiguation response is still HTTP 200 — caller branches on isDisambiguation()
    if (response.isDisambiguation()) {
      return ResponseEntity.ok(response);
    }

    // Class not found in graph
    if (response.focalClass() == null) {
      String querySummary = request.fqn() != null ? request.fqn() : request.query();
      return ResponseEntity.status(404)
          .body(Map.of("error", "No class found for query: " + querySummary));
    }

    return ResponseEntity.ok(response);
  }
}
