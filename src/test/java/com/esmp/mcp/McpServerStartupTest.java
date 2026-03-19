package com.esmp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Startup smoke test for the MCP server.
 *
 * <p>Verifies that the Spring AI MCP Server WebMVC starter correctly registers the SSE endpoint at
 * {@code /mcp/sse} and that the endpoint is reachable (MCP-01).
 *
 * <p>This test only verifies infrastructure wiring — it does not test any MCP tool behaviour.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpServerStartupTest {

  @Container
  static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:2026.01.4").withoutAuthentication();

  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("esmp")
          .withUsername("esmp")
          .withPassword("esmp-test");

  @Container
  static GenericContainer<?> qdrant =
      new GenericContainer<>("qdrant/qdrant:latest")
          .withExposedPorts(6333, 6334)
          .waitingFor(Wait.forHttp("/healthz").forPort(6333));

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
    registry.add("spring.neo4j.authentication.username", () -> "neo4j");
    registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", mysql::getUsername);
    registry.add("spring.datasource.password", mysql::getPassword);
    registry.add("qdrant.host", qdrant::getHost);
    registry.add("qdrant.port", () -> qdrant.getMappedPort(6334));
  }

  @Autowired
  private TestRestTemplate testRestTemplate;

  /**
   * MCP-01: Verifies that the MCP SSE endpoint is registered and reachable.
   *
   * <p>A GET request to {@code /mcp/sse} should return HTTP 200 with {@code text/event-stream}
   * content type. Spring AI MCP Server auto-configuration maps this endpoint when the
   * {@code spring-ai-starter-mcp-server-webmvc} dependency is on the classpath.
   */
  @Test
  @DisplayName("MCP-01: /mcp/sse endpoint is registered and returns 200")
  void testMcpSseEndpointReachable_MCP01() {
    ResponseEntity<String> response = testRestTemplate.getForEntity("/mcp/sse", String.class);

    assertThat(response.getStatusCode())
        .as("MCP SSE endpoint must return 200 OK")
        .isEqualTo(HttpStatus.OK);
  }
}
