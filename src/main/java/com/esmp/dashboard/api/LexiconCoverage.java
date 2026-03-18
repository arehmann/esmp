package com.esmp.dashboard.api;

/**
 * Lexicon coverage summary for the governance dashboard.
 *
 * <p>Reports total business terms extracted, how many have been curated by a human,
 * and the coverage percentage (curated / total * 100).
 */
public record LexiconCoverage(int total, int curated, double coveragePct) {}
