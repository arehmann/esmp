package com.esmp.extraction.model;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

/**
 * Neo4j node representing a Java package.
 *
 * <p>Uses a business-key {@code @Id} (the package name) with {@code @Version} to enable idempotent
 * MERGE semantics via Spring Data Neo4j's {@code save()} method.
 *
 * <p>Example: {@code com.example.sample} → simpleName {@code sample}, contained by a
 * {@link ModuleNode}.
 */
@Node("JavaPackage")
public class PackageNode {

  /** Business key: fully qualified package name (e.g., {@code com.example.sample}). */
  @Id private String packageName;

  /** Enables SDN to detect new vs. existing entities for MERGE semantics. */
  @Version private Long version;

  /** Last segment of the package name (e.g., {@code sample} for {@code com.example.sample}). */
  private String simpleName;

  /** Name of the owning Gradle module or source root directory. */
  private String moduleName;

  /** Classes contained in this package. */
  @Relationship(type = "CONTAINS_CLASS", direction = Relationship.Direction.OUTGOING)
  private List<ClassNode> classes = new ArrayList<>();

  public PackageNode() {}

  public PackageNode(String packageName) {
    this.packageName = packageName;
  }

  public String getPackageName() {
    return packageName;
  }

  public void setPackageName(String packageName) {
    this.packageName = packageName;
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

  public String getModuleName() {
    return moduleName;
  }

  public void setModuleName(String moduleName) {
    this.moduleName = moduleName;
  }

  public List<ClassNode> getClasses() {
    return classes;
  }

  public void setClasses(List<ClassNode> classes) {
    this.classes = classes;
  }
}
