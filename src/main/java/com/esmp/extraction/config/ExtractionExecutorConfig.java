package com.esmp.extraction.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configures the bounded thread pool used for parallel extraction partitions.
 *
 * <p>The executor is bounded to avoid overwhelming the host machine during large-codebase
 * extraction. The {@link ThreadPoolExecutor.CallerRunsPolicy} rejection handler ensures that if the
 * queue is full, the calling thread processes the task directly rather than discarding it.
 */
@Configuration
public class ExtractionExecutorConfig {

  /**
   * Thread pool for parallel visitor execution during extraction.
   *
   * <ul>
   *   <li>Core pool: 4 threads always available
   *   <li>Max pool: number of available processors (scales with hardware)
   *   <li>Queue capacity: 100 pending partitions before backpressure kicks in
   *   <li>Rejection: CallerRunsPolicy — calling thread takes over if queue is saturated
   * </ul>
   *
   * @return configured {@link TaskExecutor} bound to the name {@code extractionExecutor}
   */
  @Bean("extractionExecutor")
  public TaskExecutor extractionExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors());
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("extraction-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }
}
