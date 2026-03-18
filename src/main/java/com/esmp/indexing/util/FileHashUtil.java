package com.esmp.indexing.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility for computing SHA-256 content hashes of source files.
 *
 * <p>Used by {@link com.esmp.indexing.application.IncrementalIndexingService} to compare
 * the stored {@code contentHash} on a {@code ClassNode} against the current file on disk,
 * allowing the incremental pipeline to skip files whose content has not changed.
 *
 * <p>All methods are static — this class is not meant to be instantiated.
 */
public final class FileHashUtil {

  private FileHashUtil() {}

  /**
   * Computes the SHA-256 hash of a file's content.
   *
   * @param path absolute or relative path to the file
   * @return lowercase hex string of the SHA-256 digest (64 characters)
   * @throws IOException if the file cannot be read
   * @throws IllegalStateException if SHA-256 is unavailable (should never happen on any JVM)
   */
  public static String sha256(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
    byte[] hash = digest.digest(bytes);
    return HexFormat.of().formatHex(hash);
  }

  /**
   * Returns the relative path string of {@code absoluteFile} with respect to {@code sourceRoot}.
   *
   * <p>Used to convert CI-provided absolute paths to the relative {@code sourceFilePath} format
   * stored in Neo4j's {@code ClassNode.sourceFilePath} field (e.g., {@code com/example/Foo.java}).
   *
   * @param sourceRoot the base directory (e.g., {@code /repo/src/main/java})
   * @param absoluteFile the absolute file path to relativize
   * @return the relative path string using forward slashes
   */
  public static String relativize(Path sourceRoot, Path absoluteFile) {
    return sourceRoot.relativize(absoluteFile).toString().replace('\\', '/');
  }
}
