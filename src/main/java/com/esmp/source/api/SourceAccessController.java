package com.esmp.source.api;

import com.esmp.source.application.SourceAccessService;
import com.esmp.source.config.SourceAccessConfig;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing source access status.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/source/status} — returns the active strategy, resolved source root path,
 *       and whether resolution succeeded.
 * </ul>
 */
@RestController
@RequestMapping("/api/source")
public class SourceAccessController {

  private final SourceAccessService sourceAccessService;
  private final SourceAccessConfig sourceAccessConfig;

  public SourceAccessController(
      SourceAccessService sourceAccessService, SourceAccessConfig sourceAccessConfig) {
    this.sourceAccessService = sourceAccessService;
    this.sourceAccessConfig = sourceAccessConfig;
  }

  /**
   * Returns the source access status as a JSON object.
   *
   * <p>Example response:
   *
   * <pre>{@code
   * {
   *   "strategy": "VOLUME_MOUNT",
   *   "sourceRoot": "/mnt/source",
   *   "resolved": true
   * }
   * }</pre>
   *
   * @return map with strategy, sourceRoot, and resolved fields
   */
  @GetMapping("/status")
  public Map<String, Object> getStatus() {
    return Map.of(
        "strategy", sourceAccessConfig.getStrategy().name(),
        "sourceRoot", sourceAccessService.getResolvedSourceRoot(),
        "resolved", sourceAccessService.isResolved());
  }
}
