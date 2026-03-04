package com.esmp.extraction.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.DynamicLabels;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

/**
 * Neo4j node representing a Java class, interface, or enum declaration.
 *
 * <p>Uses a business-key {@code @Id} (the fully qualified name) with {@code @Version} to enable
 * idempotent MERGE semantics via Spring Data Neo4j's {@code save()} method. Re-running extraction
 * updates existing nodes rather than creating duplicates.
 *
 * <p>Vaadin-specific secondary labels (e.g., {@code VaadinView}, {@code VaadinComponent}, {@code
 * VaadinDataBinding}) are stored in {@code extraLabels} and persisted as additional Neo4j node
 * labels via {@code @DynamicLabels}.
 */
@Node("JavaClass")
public class ClassNode {

  /** Business key: fully qualified name of the class (e.g., {@code com.example.MyClass}). */
  @Id private String fullyQualifiedName;

  /** Enables SDN to detect new vs. existing entities for MERGE semantics. */
  @Version private Long version;

  /**
   * Dynamic secondary labels for Vaadin-specific classification. Values: {@code VaadinView}, {@code
   * VaadinComponent}, {@code VaadinDataBinding}.
   */
  @DynamicLabels private Set<String> extraLabels = new HashSet<>();

  private String simpleName;

  private String packageName;

  @Property("annotations")
  private List<String> annotations = new ArrayList<>();

  @Property("modifiers")
  private List<String> modifiers = new ArrayList<>();

  @Property("imports")
  private List<String> imports = new ArrayList<>();

  private boolean isInterface;

  private boolean isAbstract;

  private boolean isEnum;

  private String superClass;

  @Property("implementedInterfaces")
  private List<String> implementedInterfaces = new ArrayList<>();

  /** Absolute path to the source file containing this class. */
  private String sourceFilePath;

  /** SHA-256 hash of the source file content for change detection and idempotency. */
  private String contentHash;

  /** Methods declared by this class. */
  @Relationship(type = "DECLARES_METHOD", direction = Relationship.Direction.OUTGOING)
  private List<MethodNode> methods = new ArrayList<>();

  /** Fields declared by this class. */
  @Relationship(type = "DECLARES_FIELD", direction = Relationship.Direction.OUTGOING)
  private List<FieldNode> fields = new ArrayList<>();

  /** Vaadin layout hierarchy — child components contained by this class. */
  @Relationship(type = "CONTAINS_COMPONENT", direction = Relationship.Direction.OUTGOING)
  private List<ContainsComponentRelationship> componentChildren = new ArrayList<>();

  public ClassNode() {}

  public ClassNode(String fullyQualifiedName) {
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

  public Set<String> getExtraLabels() {
    return extraLabels;
  }

  public void setExtraLabels(Set<String> extraLabels) {
    this.extraLabels = extraLabels;
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

  public List<String> getAnnotations() {
    return annotations;
  }

  public void setAnnotations(List<String> annotations) {
    this.annotations = annotations;
  }

  public List<String> getModifiers() {
    return modifiers;
  }

  public void setModifiers(List<String> modifiers) {
    this.modifiers = modifiers;
  }

  public List<String> getImports() {
    return imports;
  }

  public void setImports(List<String> imports) {
    this.imports = imports;
  }

  public boolean isInterface() {
    return isInterface;
  }

  public void setInterface(boolean anInterface) {
    isInterface = anInterface;
  }

  public boolean isAbstract() {
    return isAbstract;
  }

  public void setAbstract(boolean anAbstract) {
    isAbstract = anAbstract;
  }

  public boolean isEnum() {
    return isEnum;
  }

  public void setEnum(boolean anEnum) {
    isEnum = anEnum;
  }

  public String getSuperClass() {
    return superClass;
  }

  public void setSuperClass(String superClass) {
    this.superClass = superClass;
  }

  public List<String> getImplementedInterfaces() {
    return implementedInterfaces;
  }

  public void setImplementedInterfaces(List<String> implementedInterfaces) {
    this.implementedInterfaces = implementedInterfaces;
  }

  public String getSourceFilePath() {
    return sourceFilePath;
  }

  public void setSourceFilePath(String sourceFilePath) {
    this.sourceFilePath = sourceFilePath;
  }

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
  }

  public List<MethodNode> getMethods() {
    return methods;
  }

  public void setMethods(List<MethodNode> methods) {
    this.methods = methods;
  }

  public List<FieldNode> getFields() {
    return fields;
  }

  public void setFields(List<FieldNode> fields) {
    this.fields = fields;
  }

  public List<ContainsComponentRelationship> getComponentChildren() {
    return componentChildren;
  }

  public void setComponentChildren(List<ContainsComponentRelationship> componentChildren) {
    this.componentChildren = componentChildren;
  }
}
