package com.esmp.graph.api;

import java.util.List;

/**
 * Response DTO for the class search endpoint.
 *
 * <p>Returns matching Java class nodes whose simple name contains the query string
 * (case-insensitive).
 */
public record SearchResponse(
    /** The search term that was submitted. */
    String query,
    /** List of matching class nodes. */
    List<SearchEntry> results) {

  /** A single search result entry. */
  public record SearchEntry(
      String fullyQualifiedName,
      String simpleName,
      String packageName,
      /** Dynamic labels applied to this class (e.g., Service, Repository, VaadinView). */
      List<String> labels) {}
}
