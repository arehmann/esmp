package com.esmp.pilot.api;

/**
 * A single pass/fail/warn check in the pilot validation report.
 *
 * <p>Each check tests one specific criterion for migration readiness:
 * class count, Vaadin 7 presence, vector chunk coverage, business term extraction, or risk scoring.
 *
 * @param name   short descriptive name of the check (e.g., "Module has >= 15 classes")
 * @param status result of the check: "PASS", "FAIL", or "WARN"
 * @param detail additional context explaining the result (e.g., "Found 20 classes" or "Found 0 classes, expected >= 15")
 */
public record PilotCheck(String name, String status, String detail) {}
