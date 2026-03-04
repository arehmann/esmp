package com.esmp.extraction.persistence;

import com.esmp.extraction.model.MethodNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

/** Spring Data Neo4j repository for {@link MethodNode} entities. */
@Repository
public interface MethodNodeRepository extends Neo4jRepository<MethodNode, String> {}
