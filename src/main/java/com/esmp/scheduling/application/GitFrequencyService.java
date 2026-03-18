package com.esmp.scheduling.application;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Computes per-module git commit frequency by parsing {@code git log} output.
 *
 * <p>Uses {@link ProcessBuilder} to run git in the target source root directory and parses the
 * changed file paths from the log output to derive module-level commit counts.
 *
 * <p>Fails gracefully: if git is unavailable, the source root is invalid, or the process times
 * out, an empty map is returned and a WARN log is emitted. The caller
 * ({@link SchedulingService}) treats a missing frequency count as zero, so scheduling still
 * produces a valid recommendation.
 */
@Service
public class GitFrequencyService {

  private static final Logger log = LoggerFactory.getLogger(GitFrequencyService.class);

  /** Path prefix filter — only count files under the main Java source tree. */
  private static final String SOURCE_PREFIX = "src/main/java/com/esmp/";

  /**
   * Computes the number of git commits that touched each module in the given time window.
   *
   * <p>Module is derived by splitting the changed file path on {@code /} and taking the 5th
   * segment (index 4). E.g. {@code src/main/java/com/esmp/alpha/Foo.java} → module {@code alpha}.
   *
   * @param sourceRoot the root directory of the source tree (git repository root or subdirectory)
   * @param windowDays number of days to look back in git history
   * @return map from module name to commit count; empty map if git is unavailable or errors occur
   */
  public Map<String, Integer> computeModuleCommitCounts(String sourceRoot, int windowDays) {
    if (sourceRoot == null || sourceRoot.isBlank()) {
      log.debug("sourceRoot is blank — skipping git frequency analysis");
      return Collections.emptyMap();
    }

    String since = "--since=" + LocalDate.now().minusDays(windowDays);
    List<String> command = List.of(
        "git", "log", since, "--name-only", "--pretty=format:", "--");

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(new File(sourceRoot));
    pb.redirectErrorStream(true);

    Process proc;
    try {
      proc = pb.start();
    } catch (IOException e) {
      log.warn("Git unavailable at {}: {}", sourceRoot, e.getMessage());
      return Collections.emptyMap();
    }

    Map<String, Integer> counts = new HashMap<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        if (!line.startsWith(SOURCE_PREFIX)) {
          continue;
        }
        // Path: src/main/java/com/esmp/<module>/...
        // Indices: [0]=src [1]=main [2]=java [3]=com [4]=esmp [5]=<module>
        String[] parts = line.split("/");
        if (parts.length > 5) {
          String module = parts[5];
          counts.merge(module, 1, Integer::sum);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read git log output at {}: {}", sourceRoot, e.getMessage());
      return Collections.emptyMap();
    }

    boolean finished;
    try {
      finished = proc.waitFor(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Git log interrupted at {}", sourceRoot);
      proc.destroyForcibly();
      return Collections.emptyMap();
    }

    if (!finished) {
      log.warn("Git log timed out at {}", sourceRoot);
      proc.destroyForcibly();
      return Collections.emptyMap();
    }

    int exitCode = proc.exitValue();
    if (exitCode != 0) {
      log.warn("Git unavailable at {}: process exited with code {}", sourceRoot, exitCode);
      return Collections.emptyMap();
    }

    log.debug("Git frequency analysis complete: {} modules found in {}d window", counts.size(), windowDays);
    return Collections.unmodifiableMap(counts);
  }
}
