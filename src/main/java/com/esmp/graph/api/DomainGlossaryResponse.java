package com.esmp.graph.api;

import java.util.List;
import java.util.Map;

/**
 * Structured domain glossary response for the MCP getDomainGlossary tool.
 *
 * <p>Provides project-wide domain intelligence: domain area overview with term counts,
 * UI role distribution showing which Vaadin 24 components are needed, and the top
 * business concepts by usage frequency.
 *
 * @param domainAreas     domain areas with their term counts and top terms
 * @param uiRoleCounts    distribution of terms by UI role (LABEL, MESSAGE, TOOLTIP, etc.)
 * @param topTerms        most-used business terms across all domain areas
 * @param abbreviations   domain-specific abbreviation glossary (SC, AEP, DS, BP, etc.)
 * @param totalNlsTerms   total count of NLS-sourced terms
 * @param totalAllTerms   total count of all terms (including CLASS_NAME, ENUM, etc.)
 */
public record DomainGlossaryResponse(
    List<DomainAreaSummary> domainAreas,
    Map<String, Long> uiRoleCounts,
    List<TopTerm> topTerms,
    List<Abbreviation> abbreviations,
    long totalNlsTerms,
    long totalAllTerms) {

  /**
   * Summary of a single domain area.
   *
   * @param domainArea  area identifier (e.g., "ORDER_MANAGEMENT")
   * @param termCount   number of NLS terms in this area
   * @param topTerms    sample of the highest-usage terms in this area
   */
  public record DomainAreaSummary(
      String domainArea,
      long termCount,
      List<String> topTerms) {}

  /**
   * A high-usage business term.
   *
   * @param termId      term identifier (NLS key)
   * @param displayName English display name
   * @param definition  German business definition
   * @param uiRole      UI role (LABEL, MESSAGE, etc.)
   * @param domainArea  business domain area
   * @param usageCount  number of source classes referencing this term
   */
  public record TopTerm(
      String termId,
      String displayName,
      String definition,
      String uiRole,
      String domainArea,
      long usageCount) {}

  /**
   * A domain-specific abbreviation with its expansion and evidence.
   *
   * @param abbreviation short form (e.g., "SC", "AEP")
   * @param expansion    full form (e.g., "Schedule Composition")
   * @param evidence     context or evidence for the expansion
   */
  public record Abbreviation(
      String abbreviation,
      String expansion,
      String evidence) {}
}
