package com.esmp.extraction.api;

/**
 * Request body for POST /api/extraction/trigger.
 *
 * <p>All fields are optional — if omitted, the extraction service falls back to values from {@code
 * ExtractionConfig} (i.e., {@code esmp.extraction.source-root} and {@code
 * esmp.extraction.classpath-file} in {@code application.yml}).
 */
public class ExtractionRequest {

  /**
   * Absolute path to the Java source directory to extract. Overrides the config default if
   * provided.
   */
  private String sourceRoot;

  /**
   * Absolute path to the classpath text file (one JAR per line). Overrides the config default if
   * provided.
   */
  private String classpathFile;

  /**
   * Optional subdirectory filter within {@code sourceRoot}. If provided, only Java files under this
   * subdirectory path (relative to sourceRoot) are extracted. Useful for narrowing extraction to a
   * specific module.
   */
  private String moduleFilter;

  public ExtractionRequest() {}

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

  public String getModuleFilter() {
    return moduleFilter;
  }

  public void setModuleFilter(String moduleFilter) {
    this.moduleFilter = moduleFilter;
  }
}
