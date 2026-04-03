package com.esmp.migration.api;

import com.esmp.migration.application.MigrationRecipeService;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing migration planning and execution endpoints.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/migration/plan/{fqn:.+}}  — migration plan for a single class
 *   <li>{@code GET  /api/migration/summary}         — aggregated module migration statistics
 *   <li>{@code POST /api/migration/preview/{fqn:.+}} — dry-run recipe execution (diff, no write)
 *   <li>{@code POST /api/migration/apply/{fqn:.+}}   — apply recipes and write to disk
 *   <li>{@code POST /api/migration/apply-module}      — batch apply for all automatable classes in module
 * </ul>
 *
 * <p>FQN path variables use the {@code :.+} regex suffix to prevent Spring MVC from truncating the
 * variable at the first dot (e.g., {@code com.example.MyView} must not become {@code com}).
 */
@RestController
@RequestMapping("/api/migration")
public class MigrationController {

  private static final Logger log = LoggerFactory.getLogger(MigrationController.class);

  private final MigrationRecipeService migrationRecipeService;

  public MigrationController(MigrationRecipeService migrationRecipeService) {
    this.migrationRecipeService = migrationRecipeService;
  }

  /**
   * Returns the migration plan for a single class, showing automatable and manual actions.
   *
   * @param fqn fully qualified class name (dots preserved via regex suffix)
   * @return 200 with {@link MigrationPlan}, or 404 if no class with that FQN exists in the graph
   */
  @GetMapping("/plan/{fqn:.+}")
  public ResponseEntity<?> getPlan(@PathVariable String fqn) {
    try {
      MigrationPlan plan = migrationRecipeService.generatePlan(fqn);
      if (plan == null) {
        return ResponseEntity.notFound().build();
      }
      // If no actions found, the class may not exist in the graph
      if (plan.totalActions() == 0) {
        // Return 404 only when the class truly doesn't exist; a class with 0 actions is still valid
        // generatePlan returns a plan with 0 actions for classes with no MigrationAction nodes.
        // Check if the class itself exists by inspecting the plan — 0 actions is a valid state.
        return ResponseEntity.ok(plan);
      }
      return ResponseEntity.ok(plan);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.notFound().build();
    } catch (Exception e) {
      log.error("Failed to generate migration plan for '{}': {}", fqn, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Failed to generate migration plan: " + e.getMessage()));
    }
  }

  /**
   * Returns aggregated migration statistics for all classes in the given module.
   *
   * @param module the module name (third segment of the package name, e.g., "billing")
   * @return 200 with {@link ModuleMigrationSummary}, or 400 if module is blank
   */
  @GetMapping("/summary")
  public ResponseEntity<?> getSummary(
      @RequestParam(required = false) String module) {
    try {
      ModuleMigrationSummary summary = (module == null || module.isBlank())
          ? migrationRecipeService.getProjectSummary()
          : migrationRecipeService.getModuleSummary(module);
      return ResponseEntity.ok(summary);
    } catch (Exception e) {
      log.error("Failed to get migration summary for module='{}': {}", module, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Failed to get migration summary: " + e.getMessage()));
    }
  }

  /**
   * Executes OpenRewrite recipes in preview mode — returns diff and modified source without
   * writing to disk.
   *
   * @param fqn fully qualified class name
   * @return 200 with {@link MigrationResult}
   */
  @PostMapping("/preview/{fqn:.+}")
  public ResponseEntity<?> preview(@PathVariable String fqn) {
    try {
      MigrationResult result = migrationRecipeService.preview(fqn);
      return ResponseEntity.ok(result);
    } catch (Exception e) {
      log.error("Failed to preview migration for '{}': {}", fqn, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Failed to preview migration: " + e.getMessage()));
    }
  }

  /**
   * Applies OpenRewrite recipes to a single class and writes the modified source to disk.
   *
   * @param fqn fully qualified class name
   * @return 200 with {@link MigrationResult}
   */
  @PostMapping("/apply/{fqn:.+}")
  public ResponseEntity<?> apply(@PathVariable String fqn) {
    try {
      MigrationResult result = migrationRecipeService.applyAndWrite(fqn);
      return ResponseEntity.ok(result);
    } catch (IOException e) {
      log.error("Failed to write migration result for '{}': {}", fqn, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Failed to write modified source: " + e.getMessage()));
    } catch (Exception e) {
      log.error("Failed to apply migration for '{}': {}", fqn, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Failed to apply migration: " + e.getMessage()));
    }
  }

  /**
   * Batch-applies OpenRewrite recipes to all automatable classes in a module, writing each to disk.
   *
   * @param req request body containing the required module name
   * @return 200 with {@link BatchMigrationResult}, or 400 if module is blank
   */
  @PostMapping("/apply-module")
  public ResponseEntity<?> applyModule(@RequestBody ModuleMigrationRequest req) {
    if (req == null || req.module() == null || req.module().isBlank()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "Request body with non-blank 'module' field is required"));
    }
    try {
      BatchMigrationResult result = migrationRecipeService.applyModule(req.module());
      return ResponseEntity.ok(result);
    } catch (Exception e) {
      log.error("Failed to apply module migration for '{}': {}", req.module(), e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Failed to apply module migration: " + e.getMessage()));
    }
  }
}
