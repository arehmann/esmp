package com.esmp.dashboard.api;

import java.util.List;

/**
 * Class-level detail for drill-down within a module on the governance dashboard.
 *
 * <p>Contains the class FQN, simple name, risk score, all non-JavaClass labels (stereotypes),
 * and intra-module DEPENDS_ON targets.
 */
public record ClassDetail(
    String fqn,
    String simpleName,
    double riskScore,
    List<String> labels,
    List<String> dependsOn) {}
