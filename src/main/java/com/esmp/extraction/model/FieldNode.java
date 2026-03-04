package com.esmp.extraction.model;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/** Neo4j node representing a Java field declaration. */
@Node("JavaField")
public class FieldNode {

  /** Business key: format is {@code com.example.MyClass#fieldName}. */
  @Id private String fieldId;

  @Version private Long version;

  private String simpleName;

  private String fieldType;

  /** FQN of the declaring class, for reverse lookup. */
  private String declaringClass;

  @Property("annotations")
  private List<String> annotations = new ArrayList<>();

  @Property("modifiers")
  private List<String> modifiers = new ArrayList<>();

  public FieldNode() {}

  public FieldNode(String fieldId) {
    this.fieldId = fieldId;
  }

  public String getFieldId() {
    return fieldId;
  }

  public void setFieldId(String fieldId) {
    this.fieldId = fieldId;
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

  public String getFieldType() {
    return fieldType;
  }

  public void setFieldType(String fieldType) {
    this.fieldType = fieldType;
  }

  public String getDeclaringClass() {
    return declaringClass;
  }

  public void setDeclaringClass(String declaringClass) {
    this.declaringClass = declaringClass;
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
}
