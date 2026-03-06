package com.esmp.pilot.api;

import com.esmp.pilot.application.PilotService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing pilot module recommendation and validation endpoints.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/pilot/recommend} — returns ranked list of modules scored for pilot suitability
 *   <li>{@code GET /api/pilot/validate/{module}} — returns comprehensive validation report for a module
 * </ul>
 *
 * <p>The FQN path variable uses {@code {module:.+}} regex to prevent Spring MVC dot-truncation
 * for module names that may contain dots (consistent with Phase 3 convention).
 */
@RestController
@RequestMapping("/api/pilot")
public class PilotController {

  private final PilotService pilotService;

  public PilotController(PilotService pilotService) {
    this.pilotService = pilotService;
  }

  /**
   * Returns the top 5 modules ranked by pilot suitability.
   *
   * <p>Scoring weights: Vaadin 7 density (0.4), risk diversity (0.3), size appropriateness (0.3).
   * Only modules with at least 5 classes are considered.
   *
   * @return 200 with a list of module recommendations (may be empty if no eligible modules)
   */
  @GetMapping("/recommend")
  public ResponseEntity<List<ModuleRecommendation>> recommend() {
    List<ModuleRecommendation> recommendations = pilotService.recommendModules();
    return ResponseEntity.ok(recommendations);
  }

  /**
   * Validates a module against the full pilot validation suite.
   *
   * <p>Runs global graph validation (all 32+ registered queries), module-specific Neo4j metrics,
   * Qdrant chunk count, 5 pilot-specific pass/fail checks, and generates a markdown report.
   *
   * @param module the module name to validate (e.g., "pilot")
   * @return 200 with a complete {@link PilotValidationReport}
   */
  @GetMapping("/validate/{module:.+}")
  public ResponseEntity<PilotValidationReport> validate(@PathVariable String module) {
    PilotValidationReport report = pilotService.validateModule(module);
    return ResponseEntity.ok(report);
  }
}
