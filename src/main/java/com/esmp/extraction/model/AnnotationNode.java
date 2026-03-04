package com.esmp.extraction.model;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * Neo4j node representing a Java annotation type.
 *
 * <p>Uses a business-key {@code @Id} (the fully qualified name) with {@code @Version} to enable
 * idempotent MERGE semantics via Spring Data Neo4j's {@code save()} method. Re-running extraction
 * updates existing nodes rather than creating duplicates.
 *
 * <p>Example: {@code org.springframework.stereotype.Service} → simpleName {@code Service},
 * packageName {@code org.springframework.stereotype}.
 */
@Node("JavaAnnotation")
public class AnnotationNode {

  /** Business key: fully qualified name of the annotation type (e.g., {@code org.springframework.stereotype.Service}). */
  @Id private String fullyQualifiedName;

  /** Enables SDN to detect new vs. existing entities for MERGE semantics. */
  @Version private Long version;

  /** Simple (unqualified) name of the annotation type (e.g., {@code Service}). */
  private String simpleName;

  /** Java package containing this annotation type (e.g., {@code org.springframework.stereotype}). */
  private String packageName;

  /**
   * Retention policy of the annotation, if parsed.
   * One of {@code "RUNTIME"}, {@code "CLASS"}, {@code "SOURCE"}, or {@code null} if unknown.
   */
  private String retention;

  public AnnotationNode() {}

  public AnnotationNode(String fullyQualifiedName) {
    this.fullyQualifiedName = fullyQualifiedName;
  }

  public String getFullyQualifiedName() {
    return fullyQualifiedName;
  }

  public void setFullyQualifiedName(String fullyQualifiedName) {
    this.fullyQualifiedName = fullyQualifiedName;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public String getSimpleName() {
    return simpleName;
  }

  public void setSimpleName(String simpleName) {
    this.simpleName = simpleName;
  }

  public String getPackageName() {
    return packageName;
  }

  public void setPackageName(String packageName) {
    this.packageName = packageName;
  }

  public String getRetention() {
    return retention;
  }

  public void setRetention(String retention) {
    this.retention = retention;
  }
}
