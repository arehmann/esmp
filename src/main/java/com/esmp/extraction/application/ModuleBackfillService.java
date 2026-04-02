package com.esmp.extraction.application;

import com.esmp.extraction.util.ModuleDeriver;
import java.util.Collection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * One-time backfill: sets {@code module} property on all JavaClass nodes
 * by deriving from {@code sourceFilePath} (primary) or {@code packageName} (fallback).
 */
@Service
public class ModuleBackfillService {

  private static final Logger log = LoggerFactory.getLogger(ModuleBackfillService.class);

  private final Neo4jClient neo4jClient;

  public ModuleBackfillService(Neo4jClient neo4jClient) {
    this.neo4jClient = neo4jClient;
  }

  /**
   * Backfills the {@code module} property on all JavaClass nodes missing it.
   *
   * @return number of nodes updated
   */
  public int backfill() {
    Collection<Map<String, Object>> rows = neo4jClient.query("""
            MATCH (c:JavaClass)
            WHERE c.module IS NULL OR c.module = ''
            RETURN c.fullyQualifiedName AS fqn,
                   c.sourceFilePath AS sourceFilePath,
                   c.packageName AS packageName
            """)
        .fetch().all();

    log.info("ModuleBackfill: {} classes need module property", rows.size());

    int updated = 0;
    for (Map<String, Object> row : rows) {
      String fqn = (String) row.get("fqn");
      String sourceFilePath = row.get("sourceFilePath") instanceof String s ? s : null;
      String packageName = row.get("packageName") instanceof String s ? s : null;

      String module;
      if (sourceFilePath != null && !sourceFilePath.isBlank()) {
        module = ModuleDeriver.fromSourceFilePath(sourceFilePath);
      } else if (packageName != null && !packageName.isBlank()) {
        module = ModuleDeriver.fromPackageName(packageName);
      } else {
        continue;
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
}
