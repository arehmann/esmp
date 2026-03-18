package com.esmp.indexing.api;

import java.util.List;

/**
 * Request for an incremental indexing run.
 *
 * <p>Carries the set of changed and deleted source files detected by the CI/CD pipeline, along
 * with the source root needed for parse context and module derivation.
 *
 * @param changedFiles   absolute or relative paths to {@code .java} files that were added or
 *                       modified since the last index run; may be empty
 * @param deletedFiles   absolute or relative paths to {@code .java} files that were deleted or
 *                       renamed since the last index run; may be empty
 * @param sourceRoot     base path prepended to relative source-file paths when parsing; if blank,
 *                       falls back to {@code esmp.extraction.source-root} configuration property
 * @param classpathFile  optional path to the classpath text file for type resolution; if blank,
 *                       falls back to {@code esmp.extraction.classpath-file} configuration property
 */
public record IncrementalIndexRequest(
    List<String> changedFiles,
    List<String> deletedFiles,
    String sourceRoot,
    String classpathFile) {

  /** Compact canonical constructor — replaces null lists with empty lists. */
  public IncrementalIndexRequest {
    changedFiles  = changedFiles  != null ? changedFiles  : List.of();
    deletedFiles  = deletedFiles  != null ? deletedFiles  : List.of();
    sourceRoot    = sourceRoot    != null ? sourceRoot    : "";
    classpathFile = classpathFile != null ? classpathFile : "";
  }
}
