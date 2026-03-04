package com.esmp.extraction.model;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

/**
 * Neo4j node representing a Gradle module or source root directory.
 *
 * <p>Uses a business-key {@code @Id} (the module name) with {@code @Version} to enable idempotent
 * MERGE semantics via Spring Data Neo4j's {@code save()} method.
 *
 * <p>The module name is either the Gradle subproject name (e.g., {@code :core}) or the source root
 * directory name for single-module projects.
 */
@Node("JavaModule")
public class ModuleNode {

  /** Business key: Gradle subproject name or source root directory name. */
  @Id private String moduleName;

  /** Enables SDN to detect new vs. existing entities for MERGE semantics. */
  @Version private Long version;

  /** Absolute path to the source root directory of this module. */
  private String sourceRoot;

  /** {@code true} if this module is a subproject in a multi-module Gradle build. */
  private boolean isMultiModuleSubproject;

  /** Packages contained in this module. */
  @Relationship(type = "CONTAINS_PACKAGE", direction = Relationship.Direction.OUTGOING)
  private List<PackageNode> packages = new ArrayList<>();

  public ModuleNode() {}

  public ModuleNode(String moduleName) {
    this.moduleName = moduleName;
  }

  public String getModuleName() {
    return moduleName;
  }

  public void setModuleName(String moduleName) {
    this.moduleName = moduleName;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public String getSourceRoot() {
    return sourceRoot;
  }

  public void setSourceRoot(String sourceRoot) {
    this.sourceRoot = sourceRoot;
  }

  public boolean isMultiModuleSubproject() {
    return isMultiModuleSubproject;
  }

  public void setMultiModuleSubproject(boolean multiModuleSubproject) {
    isMultiModuleSubproject = multiModuleSubproject;
  }

  public List<PackageNode> getPackages() {
    return packages;
  }

  public void setPackages(List<PackageNode> packages) {
    this.packages = packages;
  }
}
