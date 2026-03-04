package com.esmp.graph.api;

import java.util.List;

/**
 * Response DTO for the inheritance chain query endpoint.
 *
 * <p>Returns the full ancestor chain for a class traversed via EXTENDS relationships, plus all
 * interfaces implemented anywhere in the chain.
 */
public record InheritanceChainResponse(
    /** FQN of the class that was queried. */
    String fullyQualifiedName,
    /** Ordered list of ancestors starting from depth 1 (direct parent). */
    List<AncestorEntry> chain,
    /** All interfaces implemented by any class in the inheritance chain. */
    List<String> implementedInterfaces) {

  /**
   * A single ancestor in the inheritance chain.
   *
   * @param depth 1 = direct parent, 2 = grandparent, etc.
   */
  public record AncestorEntry(String fullyQualifiedName, String simpleName, int depth) {}
}
