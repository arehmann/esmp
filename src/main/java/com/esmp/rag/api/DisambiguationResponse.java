package com.esmp.rag.api;

import java.util.List;

/**
 * Response returned when a simple name query matches multiple classes and cannot be resolved
 * unambiguously to a single focal class.
 *
 * <p>The caller should re-submit the request with one of the candidate {@code fqn} values
 * to bypass resolution and proceed directly to cone traversal.
 *
 * @param query      the original query that produced ambiguous matches
 * @param candidates list of candidate classes to choose from
 */
public record DisambiguationResponse(
    String query,
    List<DisambiguationCandidate> candidates) {

  /**
   * A single candidate class returned in a disambiguation response.
   *
   * @param fqn         fully-qualified class name
   * @param simpleName  simple class name
   * @param packageName package name
   * @param labels      Neo4j node labels (e.g., ["JavaClass", "Service"])
   */
  public record DisambiguationCandidate(
      String fqn,
      String simpleName,
      String packageName,
      List<String> labels) {}
}
