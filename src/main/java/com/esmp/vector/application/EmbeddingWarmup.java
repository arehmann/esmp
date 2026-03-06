package com.esmp.vector.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Pre-loads the ONNX all-MiniLM-L6-v2 model on application startup to avoid cold-start latency
 * on the first actual embedding request.
 *
 * <p>Triggered on {@link ApplicationReadyEvent} (after all beans are initialised) by embedding a
 * single warmup string. The ONNX runtime loads lazily on first use, so this ensures the model is
 * resident in memory before the first indexing call arrives.
 */
@Component
public class EmbeddingWarmup {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingWarmup.class);

  private final EmbeddingModel embeddingModel;

  public EmbeddingWarmup(EmbeddingModel embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void warmUp() {
    log.info("Pre-loading ONNX embedding model with warmup embedding...");
    long start = System.currentTimeMillis();
    try {
      embeddingModel.embed(List.of("warmup"));
      long elapsed = System.currentTimeMillis() - start;
      log.info("ONNX embedding model warmed up in {}ms.", elapsed);
    } catch (Exception e) {
      log.warn("Embedding model warmup failed (non-fatal): {}", e.getMessage());
    }
  }
}
