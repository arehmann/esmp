package com.esmp.indexing.api;

import java.util.List;

/**
 * Result of an incremental indexing run.
 *
 * <p>Reports counts for each pipeline stage so callers and operators can assess what work was
 * performed, what was skipped (content-hash matched), and whether any non-fatal errors occurred.
 *
 * @param classesExtracted  number of changed source files that were parsed and their nodes updated
 * @param classesDeleted    number of deleted source files whose Neo4j nodes and Qdrant points were
 *                          removed
 * @param classesSkipped    number of files in {@code changedFiles} that were skipped because the
 *                          SHA-256 hash matched the stored {@code contentHash}
 * @param nodesCreated      number of new Neo4j nodes persisted (across all entity types)
 * @param nodesUpdated      number of existing Neo4j nodes updated (SDN MERGE semantics)
 * @param edgesLinked       number of relationship edges created or matched by
 *                          {@code LinkingService.linkAllRelationships()}
 * @param chunksReEmbedded  number of Qdrant vector points upserted for changed classes
 * @param chunksDeleted     number of Qdrant vector points removed for deleted or re-indexed classes
 * @param durationMs        total wall-clock time for the full pipeline run in milliseconds
 * @param errors            non-fatal error messages accumulated during execution; empty if no
 *                          errors occurred
 */
public record IncrementalIndexResponse(
    int classesExtracted,
    int classesDeleted,
    int classesSkipped,
    int nodesCreated,
    int nodesUpdated,
    int edgesLinked,
    int chunksReEmbedded,
    int chunksDeleted,
    long durationMs,
    List<String> errors) {

  /** Compact canonical constructor — replaces null errors list with an empty list. */
  public IncrementalIndexResponse {
    errors = errors != null ? errors : List.of();
  }
}
