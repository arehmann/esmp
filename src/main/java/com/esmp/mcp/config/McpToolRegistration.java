package com.esmp.mcp.config;

import com.esmp.mcp.tool.MigrationToolService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers MCP tools with the Spring AI MCP server.
 *
 * <p>Provides a {@link ToolCallbackProvider} bean backed by {@link MethodToolCallbackProvider},
 * which introspects {@link com.esmp.mcp.tool.MigrationToolService} for methods annotated with
 * {@code @Tool} and exposes them for discovery via the SSE transport at {@code /mcp/sse}.
 */
@Configuration
public class McpToolRegistration {

  /**
   * Creates a {@link ToolCallbackProvider} that exposes all 9 MCP tool methods from
   * {@link MigrationToolService}.
   *
   * @param toolService the migration tool service with @Tool-annotated methods
   * @return configured tool callback provider
   */
  @Bean
  public ToolCallbackProvider migrationTools(MigrationToolService toolService) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(toolService)
        .build();
  }
}
