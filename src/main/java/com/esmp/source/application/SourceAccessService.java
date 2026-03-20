package com.esmp.source.application;

import com.esmp.source.config.SourceAccessConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

/**
 * Resolves the source root for the target codebase at application startup.
 *
 * <p>Supports two strategies controlled by {@link SourceAccessConfig}:
 *
 * <ul>
 *   <li>{@code VOLUME_MOUNT} — validates the configured mount path and returns it.
 *   <li>{@code GITHUB_URL} — clones (or pulls) the repository using JGit and returns the clone
 *       directory.
 * </ul>
 *
 * <p>Resolution happens on {@link ApplicationReadyEvent} so all Spring context is ready before any
 * Git network call is made. Failure is graceful — the app starts even if the source root cannot be
 * resolved.
 */
@Service
public class SourceAccessService implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(SourceAccessService.class);

  private final SourceAccessConfig config;

  /** The resolved source root path. {@code null} until {@link #onApplicationEvent} completes. */
  private volatile String resolvedSourceRoot;

  public SourceAccessService(SourceAccessConfig config) {
    this.config = config;
  }

  /**
   * Called once all Spring beans are ready. Resolves the source root and stores it for later use.
   */
  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    try {
      resolvedSourceRoot = resolveSourceRoot();
      log.info(
          "SourceAccessService: resolved sourceRoot='{}' via strategy={}",
          resolvedSourceRoot,
          config.getStrategy());
    } catch (Exception e) {
      log.error(
          "SourceAccessService: failed to resolve sourceRoot via strategy={} — {}",
          config.getStrategy(),
          e.getMessage(),
          e);
      resolvedSourceRoot = "";
    }
  }

  /**
   * Resolves and returns the source root path based on the configured strategy.
   *
   * <p>For {@code VOLUME_MOUNT}: returns the configured path (best-effort — logs a warning if the
   * directory does not exist at resolution time but does not throw).
   *
   * <p>For {@code GITHUB_URL}: clones or pulls the configured repository and returns the clone
   * directory.
   *
   * @return resolved source root path (may be empty if the strategy is not configured)
   * @throws IOException if a filesystem or Git operation fails
   * @throws GitAPIException if JGit encounters an error
   */
  public String resolveSourceRoot() throws IOException, GitAPIException {
    return switch (config.getStrategy()) {
      case VOLUME_MOUNT -> resolveVolumeMountPath();
      case GITHUB_URL -> cloneOrPull();
    };
  }

  /**
   * Returns the cached resolved source root.
   *
   * @return the resolved path, or an empty string if resolution failed or has not run yet
   */
  public String getResolvedSourceRoot() {
    return resolvedSourceRoot != null ? resolvedSourceRoot : "";
  }

  /**
   * Returns {@code true} if a non-blank source root has been resolved.
   *
   * @return {@code true} when resolved
   */
  public boolean isResolved() {
    return resolvedSourceRoot != null && !resolvedSourceRoot.isBlank();
  }

  // ---- private helpers -------------------------------------------------------

  private String resolveVolumeMountPath() {
    String path = config.getVolumeMountPath();
    if (!Files.isDirectory(Path.of(path))) {
      log.warn(
          "SourceAccessService: VOLUME_MOUNT path '{}' does not exist or is not a directory "
              + "(container source may not be mounted yet — proceeding anyway)",
          path);
    }
    return path;
  }

  private String cloneOrPull() throws IOException, GitAPIException {
    Path cloneDir = Path.of(config.getCloneDirectory());

    if (Files.exists(cloneDir.resolve(".git"))) {
      // Existing clone — verify remote matches; re-clone if URL changed
      try (Git git = Git.open(cloneDir.toFile())) {
        String existingUrl =
            git.getRepository().getConfig().getString("remote", "origin", "url");
        if (!config.getGithubUrl().equals(existingUrl)) {
          log.warn(
              "SourceAccessService: remote URL changed from '{}' to '{}' — deleting and re-cloning",
              existingUrl,
              config.getGithubUrl());
          deleteDirectory(cloneDir);
          return doClone(cloneDir);
        }
        log.info("SourceAccessService: pulling latest from '{}' branch '{}'", existingUrl, config.getBranch());
        git.pull().setCredentialsProvider(credentials()).call();
      }
    } else {
      return doClone(cloneDir);
    }

    return cloneDir.toString();
  }

  private String doClone(Path cloneDir) throws IOException, GitAPIException {
    Files.createDirectories(cloneDir);
    log.info(
        "SourceAccessService: cloning '{}' branch '{}' into '{}'",
        config.getGithubUrl(),
        config.getBranch(),
        cloneDir);
    Git.cloneRepository()
        .setURI(config.getGithubUrl())
        .setDirectory(cloneDir.toFile())
        .setBranch("refs/heads/" + config.getBranch())
        .setCredentialsProvider(credentials())
        .call()
        .close();
    return cloneDir.toString();
  }

  private UsernamePasswordCredentialsProvider credentials() {
    // GitHub PAT: username can be any non-empty string; password is the token
    return new UsernamePasswordCredentialsProvider("x-token", config.getGithubToken());
  }

  private static void deleteDirectory(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .map(Path::toFile)
          .forEach(java.io.File::delete);
    }
  }
}
