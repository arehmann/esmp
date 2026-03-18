package com.esmp.rag.validation;

import com.esmp.graph.validation.ValidationQuery;
import com.esmp.graph.validation.ValidationQueryRegistry;
import com.esmp.graph.validation.ValidationSeverity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * RAG pipeline-specific validation query registry.
 *
 * <p>Contributes 3 graph validation queries that verify the data prerequisites for the RAG
 * pipeline. These checks ensure that the dependency cone traversal, vector index alignment, and
 * risk score ranking will function correctly.
 *
 * <p>Queries:
 * <ol>
 *   <li>{@code RAG_CONE_QUERY_FUNCTIONAL} — verifies that classes with reachable dependencies
 *       exist so cone traversal returns meaningful results.
 *   <li>{@code RAG_VECTOR_INDEX_ALIGNED} — verifies that classes with source paths are available
 *       for vector indexing.
 *   <li>{@code RAG_RISK_SCORES_AVAILABLE} — verifies that enhanced risk scores exist for the
 *       RAG ranking formula.
 * </ol>
 *
 * <p>Note: These queries use a PASS/FAIL convention rather than the violation-count convention
 * used by structural integrity queries. The Cypher returns a status column rather than a count
 * column to align with the intent of these checks as pre-conditions, not violation detectors.
 * The detail column carries the result count for observability.
 */
@Component
public class RagValidationQueryRegistry extends ValidationQueryRegistry {

  public RagValidationQueryRegistry() {
    super(List.of(

        // 1. RAG_CONE_QUERY_FUNCTIONAL (WARNING)
        // Verifies that at least one class has reachable dependencies so cone queries return data.
        // This uses violation convention: count > 0 means the sanity check found qualifying data.
        // We invert: count = 0 means FAIL (no classes with dependencies exist).
        new ValidationQuery(
            "RAG_CONE_QUERY_FUNCTIONAL",
            "Classes with reachable dependencies exist for cone queries",
            """
            MATCH (c:JavaClass)-[:DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS*1..3]->(target:JavaClass)
            WITH c, count(DISTINCT target) AS reachable
            WHERE reachable > 0
            WITH count(c) AS classesWithDeps
            RETURN CASE WHEN classesWithDeps = 0 THEN 1 ELSE 0 END AS count,
                   CASE WHEN classesWithDeps = 0
                        THEN ['No classes with reachable dependencies found — cone queries will return empty results']
                        ELSE []
                   END AS details
            """,
            ValidationSeverity.WARNING),

        // 2. RAG_VECTOR_INDEX_ALIGNED (WARNING)
        // Verifies that classes with source paths are available, which is required for vector indexing.
        new ValidationQuery(
            "RAG_VECTOR_INDEX_ALIGNED",
            "Classes with source paths available for vector indexing",
            """
            MATCH (c:JavaClass)
            WHERE c.sourceFilePath IS NOT NULL AND c.sourceFilePath <> ''
            WITH count(c) AS indexedClasses
            RETURN CASE WHEN indexedClasses = 0 THEN 1 ELSE 0 END AS count,
                   CASE WHEN indexedClasses = 0
                        THEN ['No classes with sourceFilePath found — vector index cannot be built']
                        ELSE []
                   END AS details
            """,
            ValidationSeverity.WARNING),

        // 3. RAG_RISK_SCORES_AVAILABLE (WARNING)
        // Verifies that enhanced risk scores exist so the RAG ranking formula has a risk dimension.
        new ValidationQuery(
            "RAG_RISK_SCORES_AVAILABLE",
            "Classes with enhanced risk scores for RAG ranking",
            """
            MATCH (c:JavaClass)
            WHERE c.enhancedRiskScore IS NOT NULL AND c.enhancedRiskScore > 0
            WITH count(c) AS scoredClasses
            RETURN CASE WHEN scoredClasses = 0 THEN 1 ELSE 0 END AS count,
                   CASE WHEN scoredClasses = 0
                        THEN ['No classes with enhancedRiskScore > 0 found — RAG risk dimension will be 0 for all chunks']
                        ELSE []
                   END AS details
            """,
            ValidationSeverity.WARNING)
    ));
  }
}
