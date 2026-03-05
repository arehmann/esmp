package com.esmp.vector.api;

/**
 * Response record returned by vector indexing and re-indexing operations.
 *
 * <p>Provides a summary of the operation: how many source files were processed (or changed
 * in the case of incremental reindex), how many chunks were embedded and upserted, how many
 * were skipped (unchanged or failed), and the total wall-clock duration.
 *
 * @param filesProcessed  number of source files that were embedded and upserted to Qdrant
 * @param chunksIndexed   total number of chunk points upserted to Qdrant
 * @param chunksSkipped   number of chunks skipped (unchanged hash or batch failure)
 * @param durationMs      wall-clock duration of the operation in milliseconds
 */
public record IndexStatusResponse(
    int filesProcessed,
    int chunksIndexed,
    int chunksSkipped,
    long durationMs) {}
