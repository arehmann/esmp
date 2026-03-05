package com.esmp.vector.validation;

import com.esmp.graph.validation.ValidationQuery;
import com.esmp.graph.validation.ValidationQueryRegistry;
import com.esmp.graph.validation.ValidationSeverity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validation query registry for Phase 8 vector indexing checks.
 *
 * <p>Extends {@link ValidationQueryRegistry} to add vector-specific validation queries to the
 * existing Phase 4 validation framework. {@link com.esmp.graph.validation.ValidationService}
 * accepts a {@code List<ValidationQueryRegistry>} and aggregates all registered beans, so this
 * registry is automatically discovered without modifying the core service.
 *
 * <p>All three queries target the Neo4j side of the vector pipeline. The Qdrant side (point count,
 * vector dimension) is verified at REST level via {@code POST /api/vector/index} responses and
 * the Qdrant collection info endpoint.
 *
 * <p>Provides three validation queries:
 * <ol>
 *   <li>VECTOR_INDEX_POPULATED (WARNING) — JavaClass nodes with non-null sourceFilePath that
 *       are expected to produce chunks; provides expected count for comparison with Qdrant.
 *   <li>VECTOR_CHUNKS_HAVE_CONTENT_HASH (ERROR) — JavaClass nodes with sourceFilePath set but
 *       missing contentHash, which would break incremental reindexing.
 *   <li>VECTOR_SOURCE_FILES_ACCESSIBLE (INFO/WARNING) — total count of classes with source paths,
 *       plus a sample of paths for manual spot-check.
 * </ol>
 */
@Component
public class VectorValidationQueryRegistry extends ValidationQueryRegistry {

  public VectorValidationQueryRegistry() {
    super(List.of(

        // 1. VECTOR_INDEX_POPULATED (WARNING)
        // Counts JavaClass nodes with non-null sourceFilePath — these are the classes expected
        // to produce CodeChunks. Count > 0 = PASS (there are classes to index).
        // Count = 0 warns that no indexable classes were found, which may mean extraction has
        // not been run or sourceFilePath was not set during extraction.
        new ValidationQuery(
            "VECTOR_INDEX_POPULATED",
            "JavaClass nodes with non-null sourceFilePath (expected vector index candidates); should be > 0",
            """
            MATCH (c:JavaClass) WHERE c.sourceFilePath IS NOT NULL
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 2. VECTOR_CHUNKS_HAVE_CONTENT_HASH (ERROR)
        // JavaClass nodes with a sourceFilePath but a null contentHash would produce chunks
        // without a hash payload, breaking the incremental reindex hash comparison.
        // Every class with a source path MUST have a contentHash populated during extraction.
        new ValidationQuery(
            "VECTOR_CHUNKS_HAVE_CONTENT_HASH",
            "JavaClass nodes with sourceFilePath set but contentHash null (breaks incremental reindex)",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.sourceFilePath IS NOT NULL AND c.contentHash IS NULL
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 3. VECTOR_SOURCE_FILES_ACCESSIBLE (WARNING)
        // Returns total count of classes with source paths and a sample for manual spot-check.
        // Informational: confirms source paths are populated after extraction. count = 0 warns
        // that source paths were never set (extraction may have run without source file scanning).
        new ValidationQuery(
            "VECTOR_SOURCE_FILES_ACCESSIBLE",
            "JavaClass nodes with sourceFilePath populated (informational: verify source path coverage)",
            """
            OPTIONAL MATCH (c:JavaClass) WHERE c.sourceFilePath IS NOT NULL
            RETURN count(c) AS count, collect(c.sourceFilePath)[0..20] AS details
            """,
            ValidationSeverity.WARNING)
    ));
  }
}
