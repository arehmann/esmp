package com.esmp.extraction.persistence;

import com.esmp.extraction.model.BusinessTermNode;
import java.util.List;
import org.springframework.data.neo4j.repository.Neo4jRepository;

/**
 * Spring Data Neo4j repository for {@link BusinessTermNode} entities.
 *
 * <p>Standard CRUD operations are inherited from {@link Neo4jRepository}. Custom queries are
 * available for filtering by source type or curation status.
 */
public interface BusinessTermNodeRepository extends Neo4jRepository<BusinessTermNode, String> {

  /**
   * Finds all business terms extracted from the given source type.
   *
   * @param sourceType e.g., "CLASS_NAME", "ENUM_CONSTANT", "DB_TABLE"
   * @return list of matching terms
   */
  List<BusinessTermNode> findBySourceType(String sourceType);

  /**
   * Finds all business terms with the given curation status.
   *
   * @param curated true for human-curated terms, false for auto-extracted
   * @return list of matching terms
   */
  List<BusinessTermNode> findByCurated(boolean curated);
}
