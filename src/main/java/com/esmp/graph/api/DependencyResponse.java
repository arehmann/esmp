package com.esmp.graph.api;

import java.util.List;

/**
 * Response DTO for the transitive service-to-repository dependency query endpoint.
 *
 * <p>Returns all Service-labeled classes that transitively depend on a Repository class through
 * DEPENDS_ON relationships (up to 10 hops).
 */
public record DependencyResponse(
    /** FQN of the repository that was queried. */
    String repositoryFqn,
    /** Services that depend on this repository, ordered by number of hops ascending. */
    List<ServiceEntry> services) {

  /**
   * A service that depends on the queried repository.
   *
   * @param hops 1 = direct dependency, 2+ = transitive via intermediate services
   */
  public record ServiceEntry(String fullyQualifiedName, String simpleName, int hops) {}
}
