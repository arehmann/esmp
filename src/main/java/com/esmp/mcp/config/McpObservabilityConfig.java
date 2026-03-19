package com.esmp.mcp.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability configuration for the MCP server.
 *
 * <p>Registers a {@link TimedAspect} bean so that any Spring-managed method annotated with
 * {@code @Timed} is automatically instrumented via Micrometer. This is used in Plan 02's MCP tool
 * service to track per-tool invocation latency.
 */
@Configuration
public class McpObservabilityConfig {

  /**
   * Creates a {@link TimedAspect} that intercepts {@code @Timed}-annotated methods and records
   * timing metrics in the provided {@link MeterRegistry}.
   *
   * @param registry Micrometer meter registry (auto-configured by Spring Boot Actuator)
   * @return configured timed aspect
   */
  @Bean
  public TimedAspect timedAspect(MeterRegistry registry) {
    return new TimedAspect(registry);
  }
}
