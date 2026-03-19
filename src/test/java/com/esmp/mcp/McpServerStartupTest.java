package com.esmp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * <p>Uses a raw TCP socket with SO_TIMEOUT to read only the HTTP status line — SSE connections
 * never close, so using {@code TestRestTemplate.getForEntity()} would block indefinitely.
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

  @LocalServerPort
  private int port;

  /**
   * MCP-01: Verifies that the MCP SSE endpoint is registered and returns HTTP 200.
   *
   * <p>Uses a raw TCP socket with a 5-second read timeout to read only the HTTP status line.
   * SSE connections stream indefinitely so standard HTTP clients block forever — reading just the
   * first response line avoids that limitation while still confirming endpoint registration.
   */
  @Test
  @DisplayName("MCP-01: /mcp/sse endpoint is registered and returns 200")
  void testMcpSseEndpointReachable_MCP01() throws Exception {
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000); // 5-second read timeout — enough to see the HTTP status line

      PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
      BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

      // Send minimal HTTP/1.1 GET request
      out.println("GET /mcp/sse HTTP/1.1");
      out.println("Host: localhost:" + port);
      out.println("Accept: text/event-stream");
      out.println("Connection: close");
      out.println();

      // Read HTTP response status line (e.g., "HTTP/1.1 200 ")
      String statusLine = in.readLine();

      assertThat(statusLine)
          .as("MCP SSE endpoint must return HTTP 200 status line")
          .isNotNull()
          .contains("200");
    }
  }
}
