package com.esmp.extraction.persistence;

import com.esmp.extraction.model.PackageNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Neo4j repository for {@link PackageNode} entities.
 *
 * <p>Uses the business-key {@code packageName} as the {@code @Id} so that {@code saveAll()}
 * performs idempotent MERGE semantics rather than always creating new nodes.
 */
@Repository
public interface PackageNodeRepository extends Neo4jRepository<PackageNode, String> {

  /** Returns the total number of JavaPackage nodes in the graph. */
  @Query("MATCH (p:JavaPackage) RETURN count(p)")
  long countAll();
}
