package com.esmp.extraction.api;

import com.esmp.extraction.application.ExtractionService;
import com.esmp.extraction.application.ExtractionService.ExtractionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the extraction subsystem.
 *
 * <p>Exposes a synchronous POST endpoint that triggers the full AST extraction pipeline and returns
 * a summary of what was extracted and persisted to Neo4j.
 */
@RestController
@RequestMapping("/api/extraction")
public class ExtractionController {

  private static final Logger log = LoggerFactory.getLogger(ExtractionController.class);

  private final ExtractionService extractionService;

  public ExtractionController(ExtractionService extractionService) {
    this.extractionService = extractionService;
  }

  /**
   * Triggers AST extraction on the given source directory.
   *
   * @param request body with optional {@code sourceRoot} and {@code classpathFile} overrides
   * @return 200 with extraction summary, or 400 if sourceRoot is invalid
   */
  @PostMapping("/trigger")
  public ResponseEntity<ExtractionResponse> trigger(@RequestBody ExtractionRequest request) {
    String sourceRoot = request.getSourceRoot();

    // Validate sourceRoot if explicitly provided
    if (sourceRoot != null && !sourceRoot.isBlank()) {
      Path sourceRootPath = Path.of(sourceRoot);
      if (!Files.exists(sourceRootPath) || !Files.isDirectory(sourceRootPath)) {
        log.warn("Invalid sourceRoot in extraction request: {}", sourceRoot);
        return ResponseEntity.badRequest().build();
      }
    }

    ExtractionResult result =
        extractionService.extract(request.getSourceRoot(), request.getClasspathFile());

    ExtractionResponse response =
        new ExtractionResponse(
            result.classCount(),
            result.methodCount(),
            result.fieldCount(),
            result.callEdgeCount(),
            result.vaadinViewCount(),
            result.vaadinComponentCount(),
            result.vaadinDataBindingCount(),
            result.annotationCount(),
            result.packageCount(),
            result.moduleCount(),
            result.tableCount(),
            result.businessTermCount(),
            result.errorCount(),
            result.errors(),
            result.auditReport(),
            result.durationMs());

    return ResponseEntity.ok(response);
  }
}
