package com.esmp.extraction.audit;

import java.util.List;

/**
 * Audit report documenting the Vaadin 7 patterns detected during AST extraction.
 *
 * <p>This report addresses the STATE.md blocker: "OpenRewrite Vaadin 7 recipe coverage is LOW
 * confidence — hands-on audit required in Phase 2." By running the extraction visitors against real
 * Vaadin 7 code and recording what was found, this report proves what static analysis CAN and
 * CANNOT detect reliably.
 */
public class VaadinAuditReport {

  /** A single detected pattern with its name, count of occurrences, and example class FQNs. */
  public static class PatternEntry {
    private final String name;
    private final int count;
    private final List<String> exampleFqns;

    public PatternEntry(String name, int count, List<String> exampleFqns) {
      this.name = name;
      this.count = count;
      this.exampleFqns = exampleFqns;
    }

    public String getName() {
      return name;
    }

    public int getCount() {
      return count;
    }

    public List<String> getExampleFqns() {
      return exampleFqns;
    }
  }

  private final List<PatternEntry> detectedPatterns;
  private final List<String> knownLimitations;
  private final String summary;

  public VaadinAuditReport(
      List<PatternEntry> detectedPatterns, List<String> knownLimitations, String summary) {
    this.detectedPatterns = detectedPatterns;
    this.knownLimitations = knownLimitations;
    this.summary = summary;
  }

  public List<PatternEntry> getDetectedPatterns() {
    return detectedPatterns;
  }

  public List<String> getKnownLimitations() {
    return knownLimitations;
  }

  public String getSummary() {
    return summary;
  }
}
