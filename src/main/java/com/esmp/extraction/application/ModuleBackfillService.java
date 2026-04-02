package com.esmp.extraction.application;

import com.esmp.extraction.util.ModuleDeriver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * One-time backfill: sets {@code module} property on all JavaClass nodes
 * by resolving the relative {@code sourceFilePath} against the source root's Gradle module
 * directories. If the file is found under {@code <sourceRoot>/<module>/src/main/java/}, the
 * Gradle module name is used. Otherwise falls back to package-name derivation.
 */
@Service
public class ModuleBackfillService {

  private static final Logger log = LoggerFactory.getLogger(ModuleBackfillService.class);

  private final Neo4jClient neo4jClient;

  public ModuleBackfillService(Neo4jClient neo4jClient) {
    this.neo4jClient = neo4jClient;
  }

  /**
   * Backfills the {@code module} property on all JavaClass nodes.
   *
   * @param sourceRoot path to the source root (e.g. "/mnt/source") — used to resolve
   *                   relative sourceFilePaths to Gradle module directories
   * @return number of nodes updated
   */
  public int backfill(String sourceRoot) {
    // Build a lookup: relative source path → Gradle module name
    Map<String, String> pathToModule = buildPathToModuleMap(sourceRoot);
    log.info("ModuleBackfill: built path→module lookup with {} entries from sourceRoot='{}'",
        pathToModule.size(), sourceRoot);

    Collection<Map<String, Object>> rows = neo4jClient.query("""
            MATCH (c:JavaClass)
            RETURN c.fullyQualifiedName AS fqn,
                   c.sourceFilePath AS sourceFilePath,
                   c.packageName AS packageName
            """)
        .fetch().all();

    log.info("ModuleBackfill: {} total classes to process", rows.size());

    int updated = 0;
    for (Map<String, Object> row : rows) {
      String fqn = (String) row.get("fqn");
      String sourceFilePath = row.get("sourceFilePath") instanceof String s ? s : null;
      String packageName = row.get("packageName") instanceof String s ? s : null;

      String module = "";

      // Strategy 1: look up the relative path in the filesystem map
      if (sourceFilePath != null && !sourceFilePath.isBlank()) {
        String normalized = sourceFilePath.replace('\\', '/');
        module = pathToModule.getOrDefault(normalized, "");

        // Strategy 2: try ModuleDeriver on the full path (handles absolute paths)
        if (module.isBlank()) {
          module = ModuleDeriver.fromSourceFilePath(sourceFilePath);
        }
      }

      // Strategy 3: fall back to package name derivation
      if (module.isBlank() && packageName != null && !packageName.isBlank()) {
        module = ModuleDeriver.fromPackageName(packageName);
      }

      if (!module.isBlank()) {
        neo4jClient.query("""
                MATCH (c:JavaClass {fullyQualifiedName: $fqn})
                SET c.module = $module
                """)
            .bind(fqn).to("fqn")
            .bind(module).to("module")
            .run();
        updated++;
      }
    }

    log.info("ModuleBackfill: updated {} classes", updated);
    return updated;
  }

  /**
   * Overload for backward compatibility — uses empty sourceRoot (package-name fallback only).
   */
  public int backfill() {
    return backfill("");
  }

  /**
   * Scans {@code <sourceRoot>/<module>/src/main/java/} directories and builds a map from
   * relative Java file path → Gradle module name.
   *
   * <p>Example: if sourceRoot is {@code /mnt/source} and the file
   * {@code /mnt/source/adsuite-market/src/main/java/de/alfa/Foo.java} exists,
   * the entry {@code "de/alfa/Foo.java" → "adsuite-market"} is added.
   */
  private Map<String, String> buildPathToModuleMap(String sourceRoot) {
    Map<String, String> map = new HashMap<>();
    if (sourceRoot == null || sourceRoot.isBlank()) {
      return map;
    }

    Path root = Path.of(sourceRoot);
    if (!Files.isDirectory(root)) {
      log.warn("ModuleBackfill: sourceRoot '{}' is not a directory", sourceRoot);
      return map;
    }

    try {
      Files.list(root)
          .filter(Files::isDirectory)
          .forEach(moduleDir -> {
            String moduleName = moduleDir.getFileName().toString();
            Path javaDir = moduleDir.resolve("src/main/java");
            if (!Files.isDirectory(javaDir)) return;

            try {
              Files.walk(javaDir)
                  .filter(p -> p.toString().endsWith(".java"))
                  .forEach(javaFile -> {
                    // Relative path from src/main/java/ (e.g. "de/alfa/openMedia/Foo.java")
                    String relativePath = javaDir.relativize(javaFile).toString().replace('\\', '/');
                    map.put(relativePath, moduleName);
                  });
            } catch (IOException e) {
              log.warn("ModuleBackfill: failed to walk {}: {}", javaDir, e.getMessage());
            }
          });
    } catch (IOException e) {
      log.warn("ModuleBackfill: failed to list sourceRoot '{}': {}", sourceRoot, e.getMessage());
    }

    return map;
  }
}
