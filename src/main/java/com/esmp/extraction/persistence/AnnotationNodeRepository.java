package com.esmp.extraction.persistence;

import com.esmp.extraction.model.AnnotationNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Neo4j repository for {@link AnnotationNode} entities.
 *
 * <p>Uses the business-key {@code fullyQualifiedName} as the {@code @Id} so that {@code saveAll()}
 * performs idempotent MERGE semantics rather than always creating new nodes.
 */
@Repository
public interface AnnotationNodeRepository extends Neo4jRepository<AnnotationNode, String> {

  /** Returns the total number of JavaAnnotation nodes in the graph. */
  @Query("MATCH (a:JavaAnnotation) RETURN count(a)")
  long countAll();
}
