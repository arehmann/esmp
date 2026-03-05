package com.esmp.graph.api;

import com.esmp.graph.validation.ValidationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the graph validation endpoint.
 *
 * <p>Exposes a single read-only endpoint that runs all registered validation queries and returns
 * a structured report. Intended as a "health check for the graph" — run after extraction to
 * confirm the code knowledge graph is structurally sound.
 *
 * <p>Example usage:
 * <pre>{@code
 * GET /api/graph/validation
 * }</pre>
 */
@RestController
@RequestMapping("/api/graph")
public class ValidationController {

  private final ValidationService validationService;

  public ValidationController(ValidationService validationService) {
    this.validationService = validationService;
  }

  /**
   * Runs all 20 canonical graph validation queries and returns a structured report.
   *
   * <p>Always returns HTTP 200. The report itself contains pass/fail/warn status per query.
   * Callers should inspect {@code errorCount} in the response body to determine if the graph
   * has structural errors.
   *
   * @return a {@link ValidationReport} with 20 query results and aggregate counts
   */
  @GetMapping("/validation")
  public ResponseEntity<ValidationReport> validate() {
    ValidationReport report = validationService.runAllValidations();
    return ResponseEntity.ok(report);
  }
}
