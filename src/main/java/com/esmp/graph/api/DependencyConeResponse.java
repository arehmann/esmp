package com.esmp.graph.api;

import java.util.List;

/**
 * Response record for the dependency cone endpoint.
 *
 * <p>Captures all nodes transitively reachable from the focal class via any structural relationship
 * (DEPENDS_ON, EXTENDS, IMPLEMENTS, CALLS, BINDS_TO, QUERIES, MAPS_TO_TABLE) up to 10 hops.
 *
 * <p>Used by Phase 11 (RAG Pipeline) for retrieval context scoping: the cone defines the boundary
 * of nodes whose embeddings should be included when answering queries about a focal class.
 *
 * @param focalFqn the fully qualified name of the focal class (starting point of the cone)
 * @param coneNodes all nodes transitively reachable from the focal class
 * @param coneSize number of reachable nodes (equals {@code coneNodes.size()})
 */
public record DependencyConeResponse(
    String focalFqn,
    List<ConeNode> coneNodes,
    int coneSize) {

  /**
   * A node within the dependency cone.
   *
   * <p>The {@code fqn} field contains the node's primary identifier — the exact property depends on
   * node type:
   * <ul>
   *   <li>JavaClass / JavaAnnotation — {@code fullyQualifiedName}
   *   <li>JavaMethod — {@code methodId}
   *   <li>JavaField — {@code fieldId}
   *   <li>JavaPackage — {@code packageName}
   *   <li>JavaModule — {@code moduleName}
   *   <li>DBTable — {@code tableName}
   * </ul>
   *
   * @param fqn node identifier (see above)
   * @param labels all Neo4j labels on this node (e.g., ["JavaClass", "Service"])
   */
  public record ConeNode(String fqn, List<String> labels) {}
}
