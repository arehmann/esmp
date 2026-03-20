package com.esmp.extraction.application;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.esmp.extraction.application.ExtractionProgressService.ProgressEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unit tests for {@link ExtractionProgressService}.
 *
 * <p>Verifies the SseEmitter lifecycle: register, send, complete, error, and graceful no-op for
 * unknown job IDs.
 */
class ExtractionProgressServiceTest {

  private ExtractionProgressService service;

  @BeforeEach
  void setUp() {
    service = new ExtractionProgressService();
  }

  @Test
  void testRegisterAndSendProgress() {
    // Arrange
    String jobId = "job-register-send";
    SseEmitter emitter = new SseEmitter(1000L);
    service.register(jobId, emitter);

    // Act & Assert — no exception should be thrown when sending a valid progress event
    assertThatCode(() ->
        service.send(jobId, new ProgressEvent("VISITING", 5, 100))
    ).doesNotThrowAnyException();
  }

  @Test
  void testCompleteEmitter() {
    // Arrange
    String jobId = "job-complete";
    SseEmitter emitter = new SseEmitter(1000L);
    service.register(jobId, emitter);

    // Act & Assert — complete should not throw and should remove the emitter
    assertThatCode(() -> service.complete(jobId)).doesNotThrowAnyException();

    // After completion, sending to the same jobId should be a no-op (emitter removed)
    assertThatCode(() ->
        service.send(jobId, new ProgressEvent("VISITING", 10, 100))
    ).doesNotThrowAnyException();
  }

  @Test
  void testSendToUnregisteredJob() {
    // Sending to a job with no registered emitter should be a graceful no-op
    assertThatCode(() ->
        service.send("unknown-job-id", new ProgressEvent("SCANNING", 0, 50))
    ).doesNotThrowAnyException();
  }

  @Test
  void testErrorEmitter() {
    // Arrange
    String jobId = "job-error";
    SseEmitter emitter = new SseEmitter(1000L);
    service.register(jobId, emitter);

    // Act & Assert — error should not throw and should complete the emitter
    assertThatCode(() ->
        service.error(jobId, "Extraction failed due to a test error")
    ).doesNotThrowAnyException();

    // After error, sending again should be a no-op (emitter removed)
    assertThatCode(() ->
        service.send(jobId, new ProgressEvent("VISITING", 1, 10))
    ).doesNotThrowAnyException();
  }

  @Test
  void testCompleteUnregisteredJob() {
    // Completing a job with no registered emitter should be a graceful no-op
    assertThatCode(() ->
        service.complete("no-such-job")
    ).doesNotThrowAnyException();
  }

  @Test
  void testErrorUnregisteredJob() {
    // Sending an error for a job with no registered emitter should be a graceful no-op
    assertThatCode(() ->
        service.error("no-such-job", "some error message")
    ).doesNotThrowAnyException();
  }

  @Test
  void testProgressEventRecord() {
    // Verify ProgressEvent record construction and accessors
    ProgressEvent event = new ProgressEvent("PARSING", 42, 200);
    assert event.phase().equals("PARSING");
    assert event.filesProcessed() == 42;
    assert event.totalFiles() == 200;
  }
}
