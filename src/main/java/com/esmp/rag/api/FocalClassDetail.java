package com.esmp.rag.api;

import java.util.List;

/**
 * Full detail for the focal class resolved from the RAG request.
 *
 * <p>The focal class is the anchor of the dependency cone. Its {@code codeText} is sourced from
 * the Qdrant CLASS_HEADER chunk unless {@code includeFullSource} is true, in which case the
 * raw {@code .java} source is read from disk.
 *
 * @param fqn                 fully-qualified class name
 * @param simpleName          simple class name (last segment of FQN)
 * @param packageName         package name
 * @param stereotype          primary stereotype label (e.g., "Service", "VaadinView")
 * @param structuralRiskScore structural risk score (0.0 to 1.0)
 * @param enhancedRiskScore   domain-enhanced risk score (0.0 to 1.0)
 * @param vaadin7Detected     true if the class carries Vaadin 7 stereotype labels
 * @param domainTerms         list of domain term display names associated with this class
 * @param codeText            chunk text (CLASS_HEADER summary) or full source when
 *                            {@code includeFullSource} was true
 */
public record FocalClassDetail(
    String fqn,
    String simpleName,
    String packageName,
    String stereotype,
    double structuralRiskScore,
    double enhancedRiskScore,
    boolean vaadin7Detected,
    List<String> domainTerms,
    String codeText) {}
