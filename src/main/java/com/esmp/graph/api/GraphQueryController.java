package com.esmp.graph.api;

import com.esmp.graph.application.GraphQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for querying the code knowledge graph.
 *
 * <p>Exposes 5 read-only endpoints:
 * <ol>
 *   <li>GET /api/graph/class/{fqn} — structural context (methods, fields, dependencies, annotations)
 *   <li>GET /api/graph/class/{fqn}/inheritance — full inheritance chain via EXTENDS traversal
 *   <li>GET /api/graph/class/{fqn}/dependency-cone — transitive closure via all 7 structural edges
 *   <li>GET /api/graph/repository/{fqn}/service-dependents — transitive service dependents
 *   <li>GET /api/graph/search?name=X — class search by simple name substring
 * </ol>
 *
 * <p>FQN path variables use the {@code :.+} regex suffix to prevent Spring MVC from truncating the
 * variable at the first dot (e.g., {@code com.example.MyClass} must not become {@code com}).
 */
@RestController
@RequestMapping("/api/graph")
public class GraphQueryController {

  private final GraphQueryService graphQueryService;

  public GraphQueryController(GraphQueryService graphQueryService) {
    this.graphQueryService = graphQueryService;
  }

  /**
   * Returns the structural context for a Java class: methods, fields, transitive dependencies,
   * and annotation nodes.
   *
   * @param fqn fully qualified class name (dots preserved via regex suffix)
   * @return 200 with {@link ClassStructureResponse}, or 404 if no class with that FQN exists
   */
  @GetMapping("/class/{fqn:.+}")
  public ResponseEntity<ClassStructureResponse> getClassStructure(
      @PathVariable String fqn) {
    return graphQueryService
        .findClassStructure(fqn)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /**
   * Returns the full inheritance chain for a class by traversing EXTENDS relationships up to 10
   * hops.
   *
   * <p>Always returns 200. The chain will be empty if the class has no ancestors or does not exist.
   *
   * @param fqn fully qualified class name
   * @return 200 with {@link InheritanceChainResponse}
   */
  @GetMapping("/class/{fqn:.+}/inheritance")
  public ResponseEntity<InheritanceChainResponse> getInheritanceChain(
      @PathVariable String fqn) {
    return ResponseEntity.ok(graphQueryService.findInheritanceChain(fqn));
  }

  /**
   * Returns all nodes transitively reachable from the focal class via any structural relationship.
   *
   * <p>Traverses all 7 structural relationship types (DEPENDS_ON, EXTENDS, IMPLEMENTS, CALLS,
   * BINDS_TO, QUERIES, MAPS_TO_TABLE) up to 10 hops.
   *
   * <p>Returns 404 if the focal class does not exist. Returns 200 with empty coneNodes and
   * coneSize 0 if the focal class exists but has no outgoing structural edges.
   *
   * <p>This endpoint is designed for reuse by Phase 11 (RAG Pipeline) for retrieval context
   * scoping — the cone defines the set of nodes whose embeddings should be included when answering
   * queries about the focal class.
   *
   * @param fqn fully qualified class name of the focal node
   * @return 200 with {@link DependencyConeResponse}, or 404 if no class with that FQN exists
   */
  @GetMapping("/class/{fqn:.+}/dependency-cone")
  public ResponseEntity<DependencyConeResponse> getDependencyCone(
      @PathVariable String fqn) {
    return graphQueryService
        .findDependencyCone(fqn)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /**
   * Returns all Service-labeled classes that transitively depend on the given Repository class via
   * DEPENDS_ON relationships (up to 10 hops).
   *
   * <p>Always returns 200. The services list will be empty if the repository is not found or has no
   * dependent services.
   *
   * @param fqn fully qualified repository class name
   * @return 200 with {@link DependencyResponse}
   */
  @GetMapping("/repository/{fqn:.+}/service-dependents")
  public ResponseEntity<DependencyResponse> getServiceDependents(
      @PathVariable String fqn) {
    return ResponseEntity.ok(graphQueryService.findServiceDependents(fqn));
  }

  /**
   * Searches for Java classes whose simple name contains the given string (case-insensitive).
   *
   * <p>Always returns 200. An empty result list is returned if no matches are found.
   *
   * @param name the substring to search for
   * @return 200 with {@link SearchResponse}
   */
  @GetMapping("/search")
  public ResponseEntity<SearchResponse> search(@RequestParam String name) {
    return ResponseEntity.ok(graphQueryService.searchByName(name));
  }
}
