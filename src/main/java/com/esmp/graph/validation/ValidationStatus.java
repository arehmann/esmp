package com.esmp.graph.validation;

/**
 * Execution status of a single graph validation query.
 *
 * <p>PASS: the query found no violations (count == 0, or count > 0 for coverage queries).
 * FAIL: an ERROR-severity query found violations (count > 0).
 * WARN: a WARNING-severity query found violations (count > 0).
 */
public enum ValidationStatus {
  PASS,
  FAIL,
  WARN
}
