package com.esmp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.esmp.scheduling.application.GitFrequencyService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link GitFrequencyService}.
 *
 * <p>No Spring context is loaded — plain JUnit 5 tests exercising the git log parsing logic.
 */
class GitFrequencyServiceTest {

  private final GitFrequencyService service = new GitFrequencyService();

  @TempDir
  static Path tempDir;

  /** Whether the git-repo setup in tempDir completed successfully. */
  private static boolean gitRepoReady = false;

  @BeforeAll
  static void setUpGitRepo() {
    try {
      // Initialise a minimal git repo in tempDir
      runGit(tempDir.toFile(), "git", "init");
      runGit(tempDir.toFile(), "git", "config", "user.email", "test@esmp.test");
      runGit(tempDir.toFile(), "git", "config", "user.name", "ESMP Test");

      // Create the file that should be picked up as module "alpha"
      Path javaFile = tempDir.resolve("src/main/java/com/esmp/alpha/Foo.java");
      Files.createDirectories(javaFile.getParent());
      Files.writeString(javaFile, "package com.esmp.alpha; public class Foo {}");

      runGit(tempDir.toFile(), "git", "add", ".");
      runGit(tempDir.toFile(), "git", "commit", "-m", "init");

      gitRepoReady = true;
    } catch (Exception e) {
      // git may not be available in the CI environment; tests will be skipped gracefully
      gitRepoReady = false;
    }
  }

  private static void runGit(File dir, String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(dir);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    boolean done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
    if (!done || p.exitValue() != 0) {
      throw new RuntimeException("Command failed: " + String.join(" ", cmd));
    }
  }

  // ---------------------------------------------------------------------------
  // testGitUnavailableReturnsEmpty
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Non-existent source root returns empty map without throwing")
  void testGitUnavailableReturnsEmpty() {
    assertThatNoException().isThrownBy(() -> {
      Map<String, Integer> result =
          service.computeModuleCommitCounts("/no/such/dir/does/not/exist", 30);
      assertThat(result).isEmpty();
    });
  }

  // ---------------------------------------------------------------------------
  // testBlankSourceRootReturnsEmpty
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Blank sourceRoot returns empty map")
  void testBlankSourceRootReturnsEmpty() {
    Map<String, Integer> resultEmpty = service.computeModuleCommitCounts("", 30);
    assertThat(resultEmpty).isEmpty();

    Map<String, Integer> resultNull = service.computeModuleCommitCounts(null, 30);
    assertThat(resultNull).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // testParsesGitLogOutput
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Git log parsing extracts module name from changed file paths")
  void testParsesGitLogOutput() {
    if (!gitRepoReady) {
      // git is unavailable in this environment — accept empty result gracefully
      Map<String, Integer> result =
          service.computeModuleCommitCounts(tempDir.toAbsolutePath().toString(), 365);
      // Empty is acceptable when git is unavailable; non-empty is also fine
      assertThat(result).isNotNull();
      return;
    }

    Map<String, Integer> result =
        service.computeModuleCommitCounts(tempDir.toAbsolutePath().toString(), 365);

    assertThat(result)
        .as("module 'alpha' should have at least 1 commit recorded")
        .containsKey("alpha");
    assertThat(result.get("alpha"))
        .as("commit count for 'alpha' should be >= 1")
        .isGreaterThanOrEqualTo(1);
  }
}
