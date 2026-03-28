package com.esmp.extraction.persistence;

import com.esmp.extraction.model.MigrationActionNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

/**
 * Spring Data Neo4j repository for {@link MigrationActionNode} entities.
 *
 * <p>Standard CRUD operations are inherited from {@link Neo4jRepository}. Migration actions
 * are persisted via batched UNWIND MERGE in {@link com.esmp.extraction.application.ExtractionService}
 * rather than through this repository's {@code saveAll()} — this repository is available for
 * read access and integration testing.
 */
public interface MigrationActionNodeRepository extends Neo4jRepository<MigrationActionNode, String> {
}
