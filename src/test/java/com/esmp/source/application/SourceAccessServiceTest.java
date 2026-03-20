package com.esmp.source.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.source.config.SourceAccessConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Unit and slice tests for {@link SourceAccessService}.
 *
 * <p>Tests DOCK-03 (VOLUME_MOUNT strategy) and DOCK-04 (GITHUB_URL strategy).
 */
class SourceAccessServiceTest {

  /** DOCK-03: VOLUME_MOUNT with an existing directory returns that path. */
  @Test
  void testVolumeMountStrategy(@TempDir Path tempDir) throws Exception {
    SourceAccessConfig config = new SourceAccessConfig();
    config.setStrategy(SourceAccessConfig.Strategy.VOLUME_MOUNT);
    config.setVolumeMountPath(tempDir.toString());

    SourceAccessService service = new SourceAccessService(config);
    String resolved = service.resolveSourceRoot();

    assertThat(resolved).isEqualTo(tempDir.toString());
  }

  /**
   * DOCK-03 (best-effort): VOLUME_MOUNT with a non-existent path still returns the configured
   * path — resolution is best-effort and does not throw.
   */
  @Test
  void testVolumeMountMissingDirectory(@TempDir Path tempDir) throws Exception {
    Path missing = tempDir.resolve("does-not-exist");

    SourceAccessConfig config = new SourceAccessConfig();
    config.setStrategy(SourceAccessConfig.Strategy.VOLUME_MOUNT);
    config.setVolumeMountPath(missing.toString());

    SourceAccessService service = new SourceAccessService(config);
    String resolved = service.resolveSourceRoot();

    // Best-effort: returns the path even though the directory does not exist
    assertThat(resolved).isEqualTo(missing.toString());
  }

  /**
   * DOCK-04: GITHUB_URL strategy clones a small public repository and returns the clone directory.
   *
   * <p>Marked {@code @Tag("integration")} because it requires an outbound network connection.
   */
  @Test
  @Tag("integration")
  void testGithubUrlStrategyClone(@TempDir Path tempDir) throws Exception {
    Path cloneDir = tempDir.resolve("hello-world-clone");

    SourceAccessConfig config = new SourceAccessConfig();
    config.setStrategy(SourceAccessConfig.Strategy.GITHUB_URL);
    config.setGithubUrl("https://github.com/octocat/Hello-World.git");
    config.setGithubToken(""); // public repo — no token needed
    config.setCloneDirectory(cloneDir.toString());
    config.setBranch("master");

    SourceAccessService service = new SourceAccessService(config);
    String resolved = service.resolveSourceRoot();

    assertThat(resolved).isEqualTo(cloneDir.toString());
    assertThat(Files.exists(cloneDir.resolve(".git")))
        .as(".git directory should exist after clone")
        .isTrue();
  }

  /** Verifies isResolved() returns false when resolvedSourceRoot is empty (not yet resolved). */
  @Test
  void testIsResolvedReturnsFalseBeforeResolution() {
    SourceAccessConfig config = new SourceAccessConfig();
    SourceAccessService service = new SourceAccessService(config);

    assertThat(service.isResolved()).isFalse();
  }

  /** Verifies getResolvedSourceRoot() returns empty string before resolution runs. */
  @Test
  void testGetResolvedSourceRootBeforeResolution() {
    SourceAccessConfig config = new SourceAccessConfig();
    SourceAccessService service = new SourceAccessService(config);

    assertThat(service.getResolvedSourceRoot()).isEmpty();
  }

  /**
   * Integration test: verifies GET /api/source/status returns 200 with a strategy field.
   *
   * <p>Requires the full Spring context. Uses test properties to disable actual source resolution
   * side-effects.
   */
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
  @AutoConfigureMockMvc
  @TestPropertySource(
      properties = {
        "esmp.source.strategy=VOLUME_MOUNT",
        "esmp.source.volume-mount-path=/tmp"
      })
  static class SourceStatusEndpointTest {

    @Autowired MockMvc mockMvc;

    @Test
    void testSourceStatusEndpoint() throws Exception {
      mockMvc
          .perform(MockMvcRequestBuilders.get("/api/source/status"))
          .andExpect(MockMvcResultMatchers.status().isOk())
          .andExpect(MockMvcResultMatchers.jsonPath("$.strategy").value("VOLUME_MOUNT"));
    }
  }
}
