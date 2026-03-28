package com.esmp.source.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for runtime source access strategy, bound from the {@code esmp.source}
 * prefix in {@code application.yml}.
 *
 * <p>Two strategies are supported:
 *
 * <ul>
 *   <li>{@code VOLUME_MOUNT} — source code is bind-mounted into the container at a known path
 *       (default {@code /mnt/source}).
 *   <li>{@code GITHUB_URL} — source code is cloned at startup from a GitHub repository using a
 *       PAT.
 * </ul>
 */
@Configuration
@ConfigurationProperties("esmp.source")
public class SourceAccessConfig {

  /** Strategy for resolving the source root at runtime. */
  public enum Strategy {
    VOLUME_MOUNT,
    GITHUB_URL
  }

  /** Active strategy. Defaults to {@code VOLUME_MOUNT}. */
  private Strategy strategy = Strategy.VOLUME_MOUNT;

  /** Path at which the source code volume is mounted inside the container. */
  private String volumeMountPath = "/mnt/source";

  /** GitHub repository HTTPS URL (e.g. {@code https://github.com/org/repo}). */
  private String githubUrl = "";

  /** GitHub Personal Access Token used for HTTPS authentication. */
  private String githubToken = "";

  /** Container-local directory where the repository is cloned. */
  private String cloneDirectory = "/tmp/esmp-source-clone";

  /** Git branch to check out when cloning. */
  private String branch = "main";

  public Strategy getStrategy() {
    return strategy;
  }

  public void setStrategy(Strategy strategy) {
    this.strategy = strategy;
  }

  public String getVolumeMountPath() {
    return volumeMountPath;
  }

  public void setVolumeMountPath(String volumeMountPath) {
    this.volumeMountPath = volumeMountPath;
  }

  public String getGithubUrl() {
    return githubUrl;
  }

  public void setGithubUrl(String githubUrl) {
    this.githubUrl = githubUrl;
  }

  public String getGithubToken() {
    return githubToken;
  }

  public void setGithubToken(String githubToken) {
    this.githubToken = githubToken;
  }

  public String getCloneDirectory() {
    return cloneDirectory;
  }

  public void setCloneDirectory(String cloneDirectory) {
    this.cloneDirectory = cloneDirectory;
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }
}
