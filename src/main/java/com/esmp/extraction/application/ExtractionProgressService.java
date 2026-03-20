package com.esmp.extraction.application;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Manages SSE emitters for streaming extraction progress to clients.
 *
 * <p>Each async extraction job registers an emitter here via its {@code jobId}. The extraction
 * pipeline calls {@link #send} to push incremental progress events, and {@link #complete} or
 * {@link #error} to finalise the stream when the job finishes.
 */
@Service
public class ExtractionProgressService {

  private static final Logger log = LoggerFactory.getLogger(ExtractionProgressService.class);

  private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

  /**
   * Registers an SSE emitter for the given job. The emitter is automatically removed when it
   * completes, times out, or encounters an error.
   *
   * @param jobId  unique job identifier
   * @param emitter the SSE emitter to register
   */
  public void register(String jobId, SseEmitter emitter) {
    emitters.put(jobId, emitter);
    emitter.onCompletion(() -> emitters.remove(jobId));
    emitter.onTimeout(() -> emitters.remove(jobId));
    emitter.onError(e -> emitters.remove(jobId));
  }

  /**
   * Sends a progress event to the registered emitter for the given job.
   * No-op if no emitter is registered for {@code jobId}.
   *
   * @param jobId  unique job identifier
   * @param event  progress event payload
   */
  public void send(String jobId, ProgressEvent event) {
    SseEmitter emitter = emitters.get(jobId);
    if (emitter != null) {
      try {
        emitter.send(SseEmitter.event()
            .name("progress")
            .data(event));
      } catch (IOException e) {
        log.warn("Failed to send SSE event for job {}: {}", jobId, e.getMessage());
        emitters.remove(jobId);
      }
    }
  }

  /**
   * Sends a {@code done} event and completes the SSE stream for the given job.
   *
   * @param jobId unique job identifier
   */
  public void complete(String jobId) {
    SseEmitter emitter = emitters.get(jobId);
    if (emitter != null) {
      try {
        emitter.send(SseEmitter.event().name("done").data("complete"));
        emitter.complete();
      } catch (IOException e) {
        log.warn("Failed to complete SSE for job {}: {}", jobId, e.getMessage());
      } finally {
        emitters.remove(jobId);
      }
    }
  }

  /**
   * Sends an {@code error} event and closes the SSE stream for the given job.
   *
   * @param jobId   unique job identifier
   * @param message error description
   */
  public void error(String jobId, String message) {
    SseEmitter emitter = emitters.get(jobId);
    if (emitter != null) {
      try {
        emitter.send(SseEmitter.event().name("error").data(message));
        emitter.complete();
      } catch (IOException e) {
        log.warn("Failed to send error for job {}: {}", jobId, e.getMessage());
      } finally {
        emitters.remove(jobId);
      }
    }
  }

  /**
   * Progress event payload sent to SSE clients during extraction.
   *
   * @param phase          current extraction phase name (e.g. SCANNING, PARSING, VISITING)
   * @param filesProcessed number of files processed so far in this phase
   * @param totalFiles     total number of files in the current phase
   */
  public record ProgressEvent(String phase, int filesProcessed, int totalFiles) {}
}
