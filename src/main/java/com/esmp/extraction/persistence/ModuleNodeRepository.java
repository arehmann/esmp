package com.esmp.extraction.persistence;

import com.esmp.extraction.model.ModuleNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Neo4j repository for {@link ModuleNode} entities.
 *
 * <p>Uses the business-key {@code moduleName} as the {@code @Id} so that {@code saveAll()}
 * performs idempotent MERGE semantics rather than always creating new nodes.
 */
@Repository
public interface ModuleNodeRepository extends Neo4jRepository<ModuleNode, String> {

  /** Returns the total number of JavaModule nodes in the graph. */
  @Query("MATCH (m:JavaModule) RETURN count(m)")
  long countAll();
}
