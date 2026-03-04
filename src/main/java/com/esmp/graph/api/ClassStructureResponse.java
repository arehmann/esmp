package com.esmp.graph.api;

import java.util.List;

/**
 * Response DTO for the class structure query endpoint.
 *
 * <p>Returns structural context for a single Java class node including its methods, fields,
 * dependencies (DEPENDS_ON edges), and annotation nodes (HAS_ANNOTATION edges).
 */
public record ClassStructureResponse(
    String fullyQualifiedName,
    String simpleName,
    String packageName,
    /** Dynamic labels applied to this class node (e.g., Service, Repository, VaadinView). */
    List<String> labels,
    /** Annotation FQNs stored as a property on the class node. */
    List<String> annotations,
    String superClass,
    List<String> implementedInterfaces,
    List<MethodSummary> methods,
    List<FieldSummary> fields,
    List<DependencySummary> dependencies,
    /** FQNs of AnnotationNode entities linked via HAS_ANNOTATION relationship. */
    List<String> annotationNodes) {

  /** Summary of a method declared by this class. */
  public record MethodSummary(
      String methodId,
      String simpleName,
      String returnType,
      List<String> parameterTypes,
      List<String> annotations) {}

  /** Summary of a field declared by this class. */
  public record FieldSummary(
      String fieldId, String simpleName, String fieldType, List<String> annotations) {}

  /** Summary of a class that this class depends on (injected dependency). */
  public record DependencySummary(
      String fullyQualifiedName, String simpleName, String injectionType) {}
}
