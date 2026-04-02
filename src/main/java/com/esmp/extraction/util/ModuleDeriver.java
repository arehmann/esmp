package com.esmp.extraction.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the Gradle module name from a source file path or package name.
 *
 * <p>Primary strategy: extract the directory segment immediately before
 * {@code src/main/java} or {@code src/test/java} in the file path.
 * Example: {@code /mnt/source/adsuite-market/src/main/java/de/alfa/...} → {@code "adsuite-market"}
 *
 * <p>Fallback: for paths without standard Gradle layout, uses the 4th segment (index 3)
 * of the Java package name. For {@code de.alfa.openMedia.adSuite.*} → {@code "adSuite"}.
 */
public final class ModuleDeriver {

  // Matches: .../<module>/src/(main|test)/java/...
  // Works with both / and \ path separators.
  private static final Pattern GRADLE_MODULE_PATTERN =
      Pattern.compile("(?:^|[/\\\\])([^/\\\\]+)[/\\\\]src[/\\\\](?:main|test)[/\\\\]java[/\\\\]");

  private ModuleDeriver() {}

  /**
   * Derives module name from a source file path.
   *
   * @param sourceFilePath absolute or relative path to a .java file
   * @return module name, or empty string if undeterminable
   */
  public static String fromSourceFilePath(String sourceFilePath) {
    if (sourceFilePath == null || sourceFilePath.isBlank()) {
      return "";
    }
    Matcher m = GRADLE_MODULE_PATTERN.matcher(sourceFilePath);
    if (m.find()) {
      return m.group(1);
    }
    // Fallback: extract package from path and use package-based derivation
    String normalized = sourceFilePath.replace('\\', '/');
    String[] parts = normalized.split("/");
    for (int i = 0; i < parts.length; i++) {
      if ("de".equals(parts[i]) || "com".equals(parts[i]) || "org".equals(parts[i])) {
        StringBuilder pkg = new StringBuilder();
        for (int j = i; j < parts.length - 1; j++) {
          if (pkg.length() > 0) pkg.append('.');
          pkg.append(parts[j]);
        }
        return fromPackageName(pkg.toString());
      }
    }
    return "";
  }

  /**
   * Derives module name from a Java package name.
   * Uses index 3 — for {@code de.alfa.openMedia.adSuite.*} yields {@code "adSuite"}.
   *
   * @param packageName dot-separated Java package name
   * @return module name, or empty string if undeterminable
   */
  public static String fromPackageName(String packageName) {
    if (packageName == null || packageName.isBlank()) {
      return "";
    }
    String[] parts = packageName.split("\\.");
    if (parts.length > 3) return parts[3];
    if (parts.length > 2) return parts[2];
    return parts.length > 0 ? parts[0] : "";
  }
}
