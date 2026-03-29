package com.esmp.extraction.application;

import static org.assertj.core.api.Assertions.assertThat;
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
        service.send(jobId, ProgressEvent.legacy("VISITING", 5, 100))
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
        service.send(jobId, ProgressEvent.legacy("VISITING", 10, 100))
    ).doesNotThrowAnyException();
  }

  @Test
  void testSendToUnregisteredJob() {
    // Sending to a job with no registered emitter should be a graceful no-op
    assertThatCode(() ->
        service.send("unknown-job-id", ProgressEvent.legacy("SCANNING", 0, 50))
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
        service.send(jobId, ProgressEvent.legacy("VISITING", 1, 10))
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
  void testProgressEventRecordLegacy() {
    // Verify legacy factory creates ProgressEvent with null module/message/durationMs
    ProgressEvent event = ProgressEvent.legacy("PARSING", 42, 200);
    assertThat(event.stage()).isEqualTo("PARSING");
    assertThat(event.filesProcessed()).isEqualTo(42);
    assertThat(event.totalFiles()).isEqualTo(200);
    assertThat(event.module()).isNull();
    assertThat(event.message()).isNull();
    assertThat(event.durationMs()).isNull();
  }

  @Test
  void testProgressEventRecordModuleAware() {
    // Verify module-aware ProgressEvent carries all fields
    ProgressEvent event = new ProgressEvent("module-a", "PARSING", 10, 50, "Parsing module-a", null);
    assertThat(event.module()).isEqualTo("module-a");
    assertThat(event.stage()).isEqualTo("PARSING");
    assertThat(event.filesProcessed()).isEqualTo(10);
    assertThat(event.totalFiles()).isEqualTo(50);
    assertThat(event.message()).isEqualTo("Parsing module-a");
    assertThat(event.durationMs()).isNull();
  }

  @Test
  void testProgressEventCompleteHasDurationMs() {
    // COMPLETE events should carry durationMs
    ProgressEvent event = new ProgressEvent("module-b", "COMPLETE", 30, 30, null, 1234L);
    assertThat(event.stage()).isEqualTo("COMPLETE");
    assertThat(event.durationMs()).isEqualTo(1234L);
  }
}
