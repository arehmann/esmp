package com.esmp.extraction.api;

import com.esmp.extraction.application.ExtractionProgressService;
import com.esmp.extraction.application.ExtractionService;
import com.esmp.source.application.SourceAccessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for the extraction subsystem.
 *
 * <p>Exposes an asynchronous POST trigger endpoint that immediately returns 202 Accepted with a
 * {@code jobId}, and a GET SSE endpoint that streams real-time progress events for the given job.
 *
 * <p>This async design is necessary for enterprise-scale extractions (40K+ files) that can take
 * minutes to complete — clients should not hold a synchronous HTTP connection open for that long.
 */
@RestController
@RequestMapping("/api/extraction")
public class ExtractionController {

  private static final Logger log = LoggerFactory.getLogger(ExtractionController.class);

  private final ExtractionService extractionService;
  private final ExtractionProgressService progressService;
  private final SourceAccessService sourceAccessService;
  private final TaskExecutor extractionExecutor;

  public ExtractionController(
      ExtractionService extractionService,
      ExtractionProgressService progressService,
      SourceAccessService sourceAccessService,
      @Qualifier("extractionExecutor") TaskExecutor extractionExecutor) {
    this.extractionService = extractionService;
    this.progressService = progressService;
    this.sourceAccessService = sourceAccessService;
    this.extractionExecutor = extractionExecutor;
  }

  /**
   * Triggers AST extraction asynchronously.
   *
   * <p>Returns 202 Accepted with a {@code jobId} immediately. Clients can stream progress by
   * calling {@code GET /api/extraction/progress?jobId=<jobId>}.
   *
   * @param request body with optional {@code sourceRoot} and {@code classpathFile} overrides
   * @return 202 with {@code jobId} and {@code status=accepted}, or 400 if sourceRoot is invalid
   */
  @PostMapping("/trigger")
  public ResponseEntity<Map<String, String>> trigger(@RequestBody(required = false) ExtractionRequest request) {
    if (request == null) {
      request = new ExtractionRequest();
    }

    // Resolve sourceRoot — use provided value, fall back to SourceAccessService resolved root
    String sourceRoot = request.getSourceRoot();
    if (sourceRoot == null || sourceRoot.isBlank()) {
      sourceRoot = sourceAccessService.getResolvedSourceRoot();
    }

    // Validate sourceRoot if explicitly provided (non-blank)
    if (sourceRoot != null && !sourceRoot.isBlank()) {
      Path sourceRootPath = Path.of(sourceRoot);
      if (!Files.exists(sourceRootPath) || !Files.isDirectory(sourceRootPath)) {
        log.warn("Invalid sourceRoot in extraction request: {}", sourceRoot);
        return ResponseEntity.badRequest()
            .body(Map.of("error", "sourceRoot does not exist or is not a directory: " + sourceRoot));
      }
    }

    String jobId = UUID.randomUUID().toString();
    final String resolvedSourceRoot = sourceRoot;
    final String classpathFile = request.getClasspathFile();

    log.info("Accepted extraction job {} for sourceRoot={}", jobId, resolvedSourceRoot);

    CompletableFuture.runAsync(() -> {
      try {
        extractionService.extract(resolvedSourceRoot, classpathFile, jobId);
        progressService.complete(jobId);
        log.info("Extraction job {} completed successfully", jobId);
      } catch (Exception e) {
        log.error("Extraction job {} failed: {}", jobId, e.getMessage(), e);
        progressService.error(jobId, e.getMessage());
      }
    }, extractionExecutor);

    return ResponseEntity.accepted()
        .body(Map.of("jobId", jobId, "status", "accepted"));
  }

  /**
   * Streams SSE progress events for an active extraction job.
   *
   * <p>The stream emits {@code progress} events with a {@link ExtractionProgressService.ProgressEvent}
   * payload during extraction, then a {@code done} event when the job finishes. If the job fails,
   * an {@code error} event is sent instead of {@code done}.
   *
   * @param jobId the job identifier returned by {@code POST /api/extraction/trigger}
   * @return SSE emitter (60-minute timeout)
   */
  @GetMapping(value = "/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamProgress(@RequestParam String jobId) {
    SseEmitter emitter = new SseEmitter(60L * 60 * 1000); // 60 min timeout
    progressService.register(jobId, emitter);
    return emitter;
  }
}
