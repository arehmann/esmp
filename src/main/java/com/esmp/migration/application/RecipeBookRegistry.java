package com.esmp.migration.application;

import com.esmp.extraction.config.MigrationConfig;
import com.esmp.migration.api.RecipeBook;
import com.esmp.migration.api.RecipeRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Spring component that loads, merges, and serves the Vaadin 7 → Vaadin 24 migration recipe book.
 *
 * <h3>Load sequence</h3>
 * <ol>
 *   <li>If the runtime file (from {@link MigrationConfig#getRecipeBookPath()}) does not exist,
 *       copy the seed JSON from the classpath ({@code /migration/vaadin-recipe-book-seed.json})
 *       to the runtime path.
 *   <li>Load all rules from the runtime JSON file and mark them {@code isBase=true}.
 *   <li>If a custom overlay path is configured and the file exists, load overlay rules and merge
 *       them on top of base rules using the {@code source} FQN as the merge key. Overlay rules
 *       are marked {@code isBase=false}.
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>{@link #load()} and {@link #updateAndWrite(List)} are {@code synchronized}. {@link #getRules()}
 * returns an unmodifiable snapshot captured at the last load. Callers should call {@link #getRules()}
 * once and pass the snapshot to visitor constructors rather than calling it per-file.
 */
@Component
public class RecipeBookRegistry {

  private static final Logger log = LoggerFactory.getLogger(RecipeBookRegistry.class);
  private static final String SEED_CLASSPATH_RESOURCE = "/migration/vaadin-recipe-book-seed.json";

  private final MigrationConfig migrationConfig;
  private final ObjectMapper objectMapper;

  /** Rule list snapshot, replaced atomically on each {@link #load()} or {@link #updateAndWrite}. */
  private volatile List<RecipeRule> rules = Collections.emptyList();

  /** O(1) lookup map from source FQN to rule, replaced atomically alongside {@link #rules}. */
  private volatile Map<String, RecipeRule> rulesBySource = Collections.emptyMap();

  public RecipeBookRegistry(MigrationConfig migrationConfig, ObjectMapper objectMapper) {
    this.migrationConfig = migrationConfig;
    this.objectMapper = objectMapper;
  }

  // =========================================================================
  // Lifecycle
  // =========================================================================

  /**
   * Loads (or re-loads) the recipe book from the runtime file, applying overlay merges.
   * Called automatically at startup via {@link PostConstruct}.
   */
  @PostConstruct
  public synchronized void load() {
    try {
      Path runtimePath = Path.of(migrationConfig.getRecipeBookPath());

      // Ensure runtime file exists — copy seed from classpath if missing
      if (!Files.exists(runtimePath)) {
        log.info("Recipe book not found at {}; copying seed from classpath", runtimePath);
        Files.createDirectories(runtimePath.getParent());
        try (InputStream seedStream = getClass().getResourceAsStream(SEED_CLASSPATH_RESOURCE)) {
          if (seedStream == null) {
            throw new IllegalStateException(
                "Seed recipe book not found on classpath: " + SEED_CLASSPATH_RESOURCE);
          }
          Files.copy(seedStream, runtimePath, StandardCopyOption.REPLACE_EXISTING);
          log.info("Seed recipe book copied to {}", runtimePath);
        }
      }

      // Load base rules from runtime file
      RecipeBook baseBook = objectMapper.readValue(runtimePath.toFile(), RecipeBook.class);
      Map<String, RecipeRule> merged = new LinkedHashMap<>();
      for (RecipeRule rule : baseBook.rules()) {
        // Set isBase=true for seed rules (JSON does not store isBase)
        RecipeRule baseRule = withIsBase(rule, true);
        merged.put(rule.source(), baseRule);
      }
      log.info("Loaded {} base rules from {}", merged.size(), runtimePath);

      // Apply custom overlay if configured and exists
      String overlayPath = migrationConfig.getCustomRecipeBookPath();
      if (overlayPath != null && !overlayPath.isBlank()) {
        Path overlayFile = Path.of(overlayPath);
        if (Files.exists(overlayFile)) {
          RecipeBook overlayBook = objectMapper.readValue(overlayFile.toFile(), RecipeBook.class);
          int overrideCount = 0;
          for (RecipeRule rule : overlayBook.rules()) {
            RecipeRule overlayRule = withIsBase(rule, false);
            merged.put(rule.source(), overlayRule);
            overrideCount++;
          }
          log.info("Applied {} overlay rules from {}", overrideCount, overlayFile);
        } else {
          log.debug("Custom overlay path configured but file does not exist: {}", overlayFile);
        }
      }

      List<RecipeRule> snapshot = Collections.unmodifiableList(new ArrayList<>(merged.values()));
      Map<String, RecipeRule> indexSnapshot = Collections.unmodifiableMap(new LinkedHashMap<>(merged));
      this.rules = snapshot;
      this.rulesBySource = indexSnapshot;
      log.info("RecipeBookRegistry ready: {} rules loaded", snapshot.size());

    } catch (IOException e) {
      throw new IllegalStateException("Failed to load recipe book: " + e.getMessage(), e);
    }
  }

  // =========================================================================
  // Public API
  // =========================================================================

  /**
   * Returns an unmodifiable snapshot of all loaded rules (base + overlay).
   * The snapshot is thread-safe and stable — callers may iterate freely.
   */
  public List<RecipeRule> getRules() {
    return rules;
  }

  /**
   * O(1) lookup of a rule by source FQN.
   *
   * @param sourceFqn the Vaadin 7 or javax fully qualified name
   * @return the matching rule, or {@link Optional#empty()} if not in the registry
   */
  public Optional<RecipeRule> findBySource(String sourceFqn) {
    return Optional.ofNullable(rulesBySource.get(sourceFqn));
  }

  /** Re-reads the recipe book from disk, applying overlays again. */
  public synchronized void reload() {
    load();
  }

  /**
   * Atomically replaces the in-memory rule list and writes it to the runtime file.
   *
   * @param updated the new rule list to persist and activate
   * @throws IOException if writing to the runtime file fails
   */
  public synchronized void updateAndWrite(List<RecipeRule> updated) throws IOException {
    Path runtimePath = Path.of(migrationConfig.getRecipeBookPath());
    Files.createDirectories(runtimePath.getParent());
    RecipeBook book = new RecipeBook(updated);
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(runtimePath.toFile(), book);

    // Rebuild in-memory index
    Map<String, RecipeRule> newIndex = new LinkedHashMap<>();
    for (RecipeRule rule : updated) {
      newIndex.put(rule.source(), rule);
    }
    this.rules = Collections.unmodifiableList(new ArrayList<>(updated));
    this.rulesBySource = Collections.unmodifiableMap(newIndex);
    log.info("RecipeBookRegistry updated: {} rules written to {}", updated.size(), runtimePath);
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /**
   * Returns a copy of the rule with the {@code isBase} field set to the specified value.
   * This is needed because records are immutable and JSON deserialization sets {@code isBase=false}
   * by default.
   */
  private static RecipeRule withIsBase(RecipeRule rule, boolean isBase) {
    return new RecipeRule(
        rule.id(),
        rule.category(),
        rule.source(),
        rule.target(),
        rule.actionType(),
        rule.automatable(),
        rule.context(),
        rule.migrationSteps() != null ? rule.migrationSteps() : List.of(),
        rule.status(),
        rule.usageCount(),
        rule.discoveredAt(),
        isBase
    );
  }
}
