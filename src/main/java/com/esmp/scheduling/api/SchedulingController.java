package com.esmp.scheduling.api;

import com.esmp.scheduling.application.SchedulingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the module migration scheduling recommendation.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /api/scheduling/recommend?sourceRoot=...} — returns a {@link ScheduleResponse}
 *       with modules ordered from lowest to highest migration risk, grouped into topological waves.
 * </ul>
 *
 * <p>The {@code sourceRoot} parameter is optional. When omitted or blank, the git frequency
 * dimension defaults to zero and the recommendation still succeeds.
 */
@RestController
@RequestMapping("/api/scheduling")
public class SchedulingController {

  private final SchedulingService schedulingService;

  public SchedulingController(SchedulingService schedulingService) {
    this.schedulingService = schedulingService;
  }

  /**
   * Returns a risk-prioritized migration scheduling recommendation.
   *
   * @param sourceRoot optional path to the source root for git frequency analysis
   * @return 200 with ScheduleResponse containing waves and flat ranking
   */
  @GetMapping("/recommend")
  public ResponseEntity<ScheduleResponse> recommend(
      @RequestParam(required = false, defaultValue = "") String sourceRoot) {
    ScheduleResponse response = schedulingService.recommend(sourceRoot);
    return ResponseEntity.ok(response);
  }
}
