package com.esmp.graph.api;

import com.esmp.graph.application.RiskService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for structural and domain-aware risk analysis endpoints.
 *
 * <p>Exposes 2 endpoints:
 * <ol>
 *   <li>GET /api/risk/heatmap — list all JavaClass nodes sorted by descending risk score,
 *       with optional filtering by module, packageName, stereotype and sortBy parameter
 *   <li>GET /api/risk/class/{fqn} — full risk detail for a single class with method breakdown
 *       and domain score breakdown
 * </ol>
 *
 * <p>FQN path variables use the {@code :.+} regex suffix to prevent Spring MVC from truncating
 * at the first dot.
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController {

  private final RiskService riskService;

  public RiskController(RiskService riskService) {
    this.riskService = riskService;
  }

  /**
   * Returns all JavaClass nodes sorted by descending risk score.
   *
   * <p>Optional query parameters narrow the result set and control sort order:
   * <ul>
   *   <li>{@code module} — only classes contained in the given JavaModule
   *   <li>{@code packageName} — only classes whose package starts with this prefix
   *   <li>{@code stereotype} — only classes carrying this label (e.g., "Service", "Repository")
   *   <li>{@code limit} — maximum number of results returned (default 50)
   *   <li>{@code sortBy} — {@code "enhanced"} (default) sorts by enhancedRiskScore DESC;
   *       {@code "structural"} sorts by structuralRiskScore DESC
   * </ul>
   *
   * @param module      optional JavaModule name filter
   * @param packageName optional package name prefix filter
   * @param stereotype  optional stereotype label filter
   * @param limit       maximum results (default 50)
   * @param sortBy      sort field: "enhanced" (default) or "structural"
   * @return 200 with list of {@link RiskHeatmapEntry} sorted by descending risk score
   */
  @GetMapping("/heatmap")
  public ResponseEntity<List<RiskHeatmapEntry>> getHeatmap(
      @RequestParam(required = false) String module,
      @RequestParam(required = false) String packageName,
      @RequestParam(required = false) String stereotype,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "enhanced") String sortBy) {
    List<RiskHeatmapEntry> entries = riskService.getHeatmap(module, packageName, stereotype, limit, sortBy);
    return ResponseEntity.ok(entries);
  }

  /**
   * Returns the full risk detail for a single class, including per-method complexity breakdown
   * and domain score breakdown.
   *
   * @param fqn fully qualified class name (e.g., {@code com.example.MyService})
   * @return 200 with {@link RiskDetailResponse}, or 404 if the class is not in the graph
   */
  @GetMapping("/class/{fqn:.+}")
  public ResponseEntity<RiskDetailResponse> getClassDetail(@PathVariable String fqn) {
    return riskService.getClassDetail(fqn)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
