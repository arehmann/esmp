package com.esmp.extraction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the extraction subsystem, bound from the {@code esmp.extraction}
 * prefix in {@code application.yml}.
 */
@Configuration
@ConfigurationProperties("esmp.extraction")
public class ExtractionConfig {

  /**
   * Root directory of the target project's Java source code. All {@code .java} files under this
   * path are eligible for extraction.
   */
  private String sourceRoot = "";

  /**
   * Path to the pre-exported classpath file produced by the target project's {@code
   * exportClasspath} Gradle task. Each line is an absolute path to a JAR on the target project's
   * runtime classpath.
   */
  private String classpathFile = "";

  public String getSourceRoot() {
    return sourceRoot;
  }

  public void setSourceRoot(String sourceRoot) {
    this.sourceRoot = sourceRoot;
  }

  public String getClasspathFile() {
    return classpathFile;
  }

  public void setClasspathFile(String classpathFile) {
    this.classpathFile = classpathFile;
  }
}
