package com.esmp.migration.api;

import com.esmp.migration.application.RecipeBookRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing recipe book management endpoints.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/migration/recipe-book}            — list all rules (filterable by
 *       category, status, automatable)
 *   <li>{@code GET  /api/migration/recipe-book/gaps}        — NEEDS_MAPPING rules sorted by
 *       usageCount descending
 *   <li>{@code PUT  /api/migration/recipe-book/rules/{id}}  — create or replace a custom rule
 *   <li>{@code DELETE /api/migration/recipe-book/rules/{id}} — delete a custom rule (base rules
 *       are protected with 403)
 *   <li>{@code POST /api/migration/recipe-book/reload}      — re-reads the recipe book from disk
 * </ul>
 */
@RestController
@RequestMapping("/api/migration/recipe-book")
public class RecipeBookController {

  private static final Logger log = LoggerFactory.getLogger(RecipeBookController.class);

  private final RecipeBookRegistry recipeBookRegistry;

  public RecipeBookController(RecipeBookRegistry recipeBookRegistry) {
    this.recipeBookRegistry = recipeBookRegistry;
  }

  /**
   * Returns all recipe rules, optionally filtered by category, status, or automatable.
   *
   * @param category    optional category filter (e.g., COMPONENT, DATA_BINDING)
   * @param status      optional status filter (e.g., MAPPED, NEEDS_MAPPING)
   * @param automatable optional automatable filter (e.g., YES, PARTIAL, NO)
   * @return 200 with filtered rule list
   */
  @GetMapping
  public List<RecipeRule> getAllRules(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String automatable) {
    return recipeBookRegistry.getRules().stream()
        .filter(r -> category == null || category.equals(r.category()))
        .filter(r -> status == null || status.equals(r.status()))
        .filter(r -> automatable == null || automatable.equals(r.automatable()))
        .collect(Collectors.toList());
  }

  /**
   * Returns all NEEDS_MAPPING rules sorted by usageCount descending.
   *
   * <p>These are the gaps Claude Code needs to research and add mappings for before
   * migration can be automated.
   *
   * @return 200 with list of NEEDS_MAPPING rules sorted by usageCount descending
   */
  @GetMapping("/gaps")
  public List<RecipeRule> getGaps() {
    return recipeBookRegistry.getRules().stream()
        .filter(r -> "NEEDS_MAPPING".equals(r.status()))
        .sorted(Comparator.comparingInt(RecipeRule::usageCount).reversed())
        .collect(Collectors.toList());
  }

  /**
   * Creates or replaces a custom recipe rule by ID.
   *
   * <p>The rule is always stored with {@code isBase=false}, regardless of the value in the
   * request body. This prevents callers from falsely marking custom rules as base rules.
   *
   * @param id   rule ID (e.g., CUSTOM-001)
   * @param rule rule data from the request body
   * @return 200 with the created/updated rule
   */
  @PutMapping("/rules/{id}")
  public ResponseEntity<RecipeRule> upsertRule(
      @PathVariable String id, @RequestBody RecipeRule rule) {
    RecipeRule newRule = new RecipeRule(
        id,
        rule.category(),
        rule.source(),
        rule.target(),
        rule.actionType(),
        rule.automatable(),
        rule.context(),
        rule.migrationSteps() != null ? rule.migrationSteps() : java.util.List.of(),
        rule.status(),
        rule.usageCount(),
        rule.discoveredAt(),
        false); // custom rules are never base rules

    List<RecipeRule> updated = new ArrayList<>(recipeBookRegistry.getRules());
    updated.removeIf(r -> r.id().equals(id));
    updated.add(newRule);

    try {
      recipeBookRegistry.updateAndWrite(updated);
    } catch (java.io.IOException e) {
      log.error("Failed to persist recipe rule '{}': {}", id, e.getMessage(), e);
      return ResponseEntity.internalServerError().build();
    }

    log.info("Upserted recipe rule '{}' (source={})", id, newRule.source());
    return ResponseEntity.ok(newRule);
  }

  /**
   * Deletes a custom recipe rule by ID.
   *
   * <p>Base rules (isBase=true) cannot be deleted — attempts to delete them return 403.
   *
   * @param id rule ID
   * @return 204 on success, 403 if the rule is a base rule, 404 if not found
   */
  @DeleteMapping("/rules/{id}")
  public ResponseEntity<Void> deleteRule(@PathVariable String id) {
    Optional<RecipeRule> existing = recipeBookRegistry.getRules().stream()
        .filter(r -> r.id().equals(id))
        .findFirst();

    if (existing.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    if (existing.get().isBase()) {
      log.warn("Attempted to delete base rule '{}' — forbidden", id);
      return ResponseEntity.status(403).build();
    }

    List<RecipeRule> updated = recipeBookRegistry.getRules().stream()
        .filter(r -> !r.id().equals(id))
        .collect(Collectors.toList());

    try {
      recipeBookRegistry.updateAndWrite(updated);
    } catch (java.io.IOException e) {
      log.error("Failed to persist recipe book after deleting rule '{}': {}", id, e.getMessage(),
          e);
      return ResponseEntity.internalServerError().build();
    }

    log.info("Deleted recipe rule '{}'", id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Re-reads the recipe book from disk, picking up any manual edits or new overlay files.
   *
   * @return 200 with JSON body containing the rule count and status
   */
  @PostMapping("/reload")
  public ResponseEntity<Map<String, Object>> reload() {
    recipeBookRegistry.reload();
    int count = recipeBookRegistry.getRules().size();
    log.info("Recipe book reloaded: {} rules loaded", count);
    return ResponseEntity.ok(Map.of("count", count, "status", "reloaded"));
  }
}
