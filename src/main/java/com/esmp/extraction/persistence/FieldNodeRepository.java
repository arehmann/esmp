package com.esmp.extraction.persistence;

import com.esmp.extraction.model.FieldNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

/** Spring Data Neo4j repository for {@link FieldNode} entities. */
@Repository
public interface FieldNodeRepository extends Neo4jRepository<FieldNode, String> {}
