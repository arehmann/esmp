package com.esmp.vector.api;

import com.esmp.vector.application.VectorIndexingService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that exposes triggers for vector indexing operations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/vector/index} — full index of all source files in the given source root.
 *       Embeds all chunks and upserts to Qdrant. Idempotent.
 *   <li>{@code POST /api/vector/reindex} — incremental reindex: scrolls Qdrant for stored content
 *       hashes, compares with current file hashes, and re-embeds only changed files.
 * </ul>
 *
 * <p>Both endpoints return an {@link IndexStatusResponse} with counts and duration on success,
 * or HTTP 500 with an error message string body on failure.
 */
@RestController
@RequestMapping("/api/vector")
public class VectorIndexController {

  private static final Logger log = LoggerFactory.getLogger(VectorIndexController.class);

  private final VectorIndexingService vectorIndexingService;

  public VectorIndexController(VectorIndexingService vectorIndexingService) {
    this.vectorIndexingService = vectorIndexingService;
  }

  /**
   * Triggers a full vector index of all classes reachable from the given source root.
   *
   * @param sourceRoot base directory prepended to relative source file paths stored in Neo4j;
   *                   may be empty string if Neo4j stores absolute paths
   * @return {@link IndexStatusResponse} with filesProcessed, chunksIndexed, chunksSkipped, durationMs
   */
  @PostMapping("/index")
  public ResponseEntity<?> index(@RequestParam String sourceRoot) {
    log.info("POST /api/vector/index (sourceRoot='{}')", sourceRoot);
    try {
      IndexStatusResponse response = vectorIndexingService.indexAll(sourceRoot);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Full index failed for sourceRoot='{}': {}", sourceRoot, e.getMessage(), e);
      return ResponseEntity.internalServerError().body("Index failed: " + e.getMessage());
    }
  }

  /**
   * Triggers an incremental reindex that re-embeds only files whose content hash changed.
   *
   * @param sourceRoot base directory prepended to relative source file paths stored in Neo4j;
   *                   may be empty string if Neo4j stores absolute paths
   * @return {@link IndexStatusResponse} where filesProcessed = changed classes, chunksSkipped = unchanged
   */
  @PostMapping("/reindex")
  public ResponseEntity<?> reindex(@RequestParam String sourceRoot) {
    log.info("POST /api/vector/reindex (sourceRoot='{}')", sourceRoot);
    try {
      IndexStatusResponse response = vectorIndexingService.reindex(sourceRoot);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Incremental reindex failed for sourceRoot='{}': {}", sourceRoot, e.getMessage(), e);
      return ResponseEntity.internalServerError().body("Reindex failed: " + e.getMessage());
    }
  }

  /**
   * Forces a full re-embed for all classes in the given modules, ignoring content hash.
   *
   * <p>Use this after a Neo4j module backfill ({@code POST /api/extraction/backfill-modules}) to
   * refresh stale Qdrant payloads whose {@code module} field was wrong but whose source file hash
   * hasn't changed (so the normal {@code /reindex} would skip them).
   *
   * @param modules    comma-separated list of module names to re-index (e.g. {@code adsuite-market,adsuite-business})
   * @param sourceRoot base directory prepended to relative source file paths
   * @return {@link IndexStatusResponse} with counts and duration
   */
  @PostMapping("/reindex-modules")
  public ResponseEntity<?> reindexByModules(
      @RequestParam List<String> modules,
      @RequestParam String sourceRoot) {
    log.info("POST /api/vector/reindex-modules (modules={}, sourceRoot='{}')", modules, sourceRoot);
    try {
      IndexStatusResponse response = vectorIndexingService.reindexByModules(modules, sourceRoot);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Module re-index failed for modules={}: {}", modules, e.getMessage(), e);
      return ResponseEntity.internalServerError().body("Module reindex failed: " + e.getMessage());
    }
  }
}
