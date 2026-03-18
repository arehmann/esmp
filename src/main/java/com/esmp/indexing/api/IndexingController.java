package com.esmp.indexing.api;

import com.esmp.indexing.application.IncrementalIndexingService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the incremental indexing pipeline to CI/CD hooks.
 *
 * <p>Supports two usage patterns via a single unified endpoint:
 *
 * <ul>
 *   <li><strong>Incremental run:</strong> provide {@code changedFiles} and/or {@code deletedFiles}.
 *       Only those files are processed.
 *   <li><strong>Full module re-index:</strong> omit {@code changedFiles} and {@code deletedFiles}
 *       (or leave them empty) but provide {@code sourceRoot}. All {@code .java} files under
 *       {@code sourceRoot} are treated as changed files.
 * </ul>
 *
 * <p>The endpoint is synchronous — it blocks until the pipeline completes and returns a full
 * {@link IncrementalIndexResponse} with per-stage counts. Partial success (some files errored) is
 * still returned as HTTP 200; callers should inspect the {@code errors} field.
 */
@RestController
@RequestMapping("/api/indexing")
public class IndexingController {

  private static final Logger log = LoggerFactory.getLogger(IndexingController.class);

  private final IncrementalIndexingService incrementalIndexingService;

  public IndexingController(IncrementalIndexingService incrementalIndexingService) {
    this.incrementalIndexingService = incrementalIndexingService;
  }

  /**
   * Runs the incremental (or full re-index) pipeline.
   *
   * <p>If both {@code changedFiles} and {@code deletedFiles} are null/empty and {@code sourceRoot}
   * is provided, this endpoint performs a full re-index of all {@code .java} files under
   * {@code sourceRoot}.
   *
   * @param request the indexing request
   * @return 200 with {@link IncrementalIndexResponse} (even on partial success); 400 if
   *     {@code sourceRoot} is blank/null and no changedFiles or deletedFiles are supplied
   */
  @PostMapping("/incremental")
  public ResponseEntity<?> incrementalIndex(@RequestBody IncrementalIndexRequest request) {
    boolean hasChangedFiles = request.changedFiles() != null && !request.changedFiles().isEmpty();
    boolean hasDeletedFiles = request.deletedFiles() != null && !request.deletedFiles().isEmpty();

    // Full re-index path: no explicit files provided — scan sourceRoot
    if (!hasChangedFiles && !hasDeletedFiles) {
      String sourceRoot = request.sourceRoot();
      if (sourceRoot == null || sourceRoot.isBlank()) {
        String error = "sourceRoot must be provided when changedFiles and deletedFiles are both empty";
        log.warn("Rejected incremental request: {}", error);
        return ResponseEntity.badRequest().body(error);
      }

      Path sourceRootPath = Path.of(sourceRoot);
      if (!Files.isDirectory(sourceRootPath)) {
        String error = "sourceRoot does not exist or is not a directory: " + sourceRoot;
        log.warn("Rejected incremental request: {}", error);
        return ResponseEntity.badRequest().body(error);
      }

      // Scan all .java files under sourceRoot
      List<String> allJavaFiles;
      try {
        allJavaFiles = Files.walk(sourceRootPath)
            .filter(p -> p.toString().endsWith(".java"))
            .map(Path::toString)
            .toList();
      } catch (IOException e) {
        String error = "Failed to scan sourceRoot for .java files: " + e.getMessage();
        log.error(error, e);
        return ResponseEntity.internalServerError().body(error);
      }

      log.info("Full re-index: found {} .java files under sourceRoot '{}'",
          allJavaFiles.size(), sourceRoot);

      // Rebuild request with all files as changedFiles
      request = new IncrementalIndexRequest(allJavaFiles, List.of(),
          request.sourceRoot(), request.classpathFile());
    }

    log.info("Incremental index request: changedFiles={}, deletedFiles={}",
        request.changedFiles().size(), request.deletedFiles().size());

    IncrementalIndexResponse response = incrementalIndexingService.runIncremental(request);
    return ResponseEntity.ok(response);
  }
}
