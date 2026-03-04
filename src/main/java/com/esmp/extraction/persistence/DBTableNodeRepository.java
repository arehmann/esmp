package com.esmp.extraction.persistence;

import com.esmp.extraction.model.DBTableNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Neo4j repository for {@link DBTableNode} entities.
 *
 * <p>Uses the business-key {@code tableName} as the {@code @Id} so that {@code saveAll()}
 * performs idempotent MERGE semantics rather than always creating new nodes.
 */
@Repository
public interface DBTableNodeRepository extends Neo4jRepository<DBTableNode, String> {

  /** Returns the total number of DBTable nodes in the graph. */
  @Query("MATCH (t:DBTable) RETURN count(t)")
  long countAll();
}
