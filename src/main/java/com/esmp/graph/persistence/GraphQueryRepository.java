package com.esmp.graph.persistence;

import com.esmp.extraction.model.ClassNode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

/**
 * Read-only Spring Data Neo4j repository for querying {@link ClassNode} entities.
 *
 * <p>This repository is intentionally separate from the extraction package's write-side
 * repositories. It provides query-side access to the code knowledge graph for the REST API.
 *
 * <p>Simple SDN derived queries are declared here. Complex variable-length path queries
 * (inheritance chains, transitive dependencies) are handled in {@link
 * com.esmp.graph.application.GraphQueryService} via {@code Neo4jClient} to avoid SDN's inability
 * to map Cypher path objects.
 */
@Repository
public interface GraphQueryRepository extends Neo4jRepository<ClassNode, String> {

  /**
   * Finds a class node by its fully qualified name.
   *
   * @param fqn the fully qualified class name, e.g. {@code com.example.MyClass}
   * @return the class node if found
   */
  Optional<ClassNode> findByFullyQualifiedName(String fqn);

  /**
   * Finds class nodes whose simple name contains the given string (case-insensitive).
   *
   * @param name the substring to match against simple names
   * @return matching class nodes
   */
  List<ClassNode> findBySimpleNameContainingIgnoreCase(String name);
}
