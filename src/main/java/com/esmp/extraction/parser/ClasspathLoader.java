package com.esmp.extraction.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a classpath text file (one JAR path per line) and returns the list of existing {@link Path}
 * entries. Non-existent paths are skipped with a warning. If the classpath file itself does not
 * exist, returns an empty list and logs a warning — this allows parsing to proceed with degraded
 * type resolution rather than failing entirely.
 */
@Component
public class ClasspathLoader {

  private static final Logger log = LoggerFactory.getLogger(ClasspathLoader.class);

  /**
   * Loads classpath entries from the given file.
   *
   * @param classpathFilePath path to the text file containing one JAR path per line; may be null or
   *     empty
   * @return list of {@link Path} objects that exist on disk; never null
   */
  public List<Path> load(String classpathFilePath) {
    if (classpathFilePath == null || classpathFilePath.isBlank()) {
      log.warn("Classpath file path is null or empty — parsing will proceed without classpath");
      return Collections.emptyList();
    }

    Path cpFile = Paths.get(classpathFilePath);
    if (!Files.exists(cpFile)) {
      log.warn(
          "Classpath file does not exist: {} — parsing will proceed without classpath",
          classpathFilePath);
      return Collections.emptyList();
    }

    try {
      List<String> lines = Files.readAllLines(cpFile);
      List<Path> resolved =
          lines.stream()
              .filter(line -> !line.isBlank())
              .map(line -> normalizePath(line.trim()))
              .filter(
                  p -> {
                    if (!Files.exists(p)) {
                      log.warn("Classpath entry does not exist, skipping: {}", p);
                      return false;
                    }
                    return true;
                  })
              .collect(Collectors.toList());
      log.debug("Loaded {} classpath entries from {}", resolved.size(), classpathFilePath);
      return resolved;
    } catch (IOException e) {
      log.warn("Could not read classpath file {}: {}", classpathFilePath, e.getMessage());
      return Collections.emptyList();
    }
  }

  /**
   * Normalizes path separators to the current OS convention. This handles the case where a
   * classpath file written on Windows (backslashes) is read on Linux (forward slashes).
   */
  private Path normalizePath(String rawPath) {
    // Paths.get() handles both forward and backward slashes on the current OS
    return Paths.get(rawPath.replace('\\', '/'));
  }
}
