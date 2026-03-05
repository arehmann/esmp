package com.esmp.extraction.model;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

/** Neo4j node representing a Java method declaration. */
@Node("JavaMethod")
public class MethodNode {

  /**
   * Business key: format is {@code com.example.MyClass#myMethod(String,int)} — unique per overload.
   */
  @Id private String methodId;

  @Version private Long version;

  private String simpleName;

  private String returnType;

  @Property("parameterTypes")
  private List<String> parameterTypes = new ArrayList<>();

  @Property("annotations")
  private List<String> annotations = new ArrayList<>();

  @Property("modifiers")
  private List<String> modifiers = new ArrayList<>();

  private boolean isConstructor;

  /** FQN of the declaring class, for reverse lookup. */
  private String declaringClass;

  /** Cyclomatic complexity of this method (1 = no branches, higher = more complex). */
  private int cyclomaticComplexity;

  /** Outgoing call graph edges — methods this method calls. */
  @Relationship(type = "CALLS", direction = Relationship.Direction.OUTGOING)
  private List<CallsRelationship> callsOut = new ArrayList<>();

  public MethodNode() {}

  public MethodNode(String methodId) {
    this.methodId = methodId;
  }

  public String getMethodId() {
    return methodId;
  }

  public void setMethodId(String methodId) {
    this.methodId = methodId;
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

  public String getReturnType() {
    return returnType;
  }

  public void setReturnType(String returnType) {
    this.returnType = returnType;
  }

  public List<String> getParameterTypes() {
    return parameterTypes;
  }

  public void setParameterTypes(List<String> parameterTypes) {
    this.parameterTypes = parameterTypes;
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

  public boolean isConstructor() {
    return isConstructor;
  }

  public void setConstructor(boolean constructor) {
    isConstructor = constructor;
  }

  public String getDeclaringClass() {
    return declaringClass;
  }

  public void setDeclaringClass(String declaringClass) {
    this.declaringClass = declaringClass;
  }

  public List<CallsRelationship> getCallsOut() {
    return callsOut;
  }

  public void setCallsOut(List<CallsRelationship> callsOut) {
    this.callsOut = callsOut;
  }

  public int getCyclomaticComplexity() {
    return cyclomaticComplexity;
  }

  public void setCyclomaticComplexity(int cyclomaticComplexity) {
    this.cyclomaticComplexity = cyclomaticComplexity;
  }
}
