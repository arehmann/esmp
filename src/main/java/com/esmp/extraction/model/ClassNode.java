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

  // ---------- Phase 6: structural risk metrics ----------

  /** Sum of cyclomatic complexity values across all methods in this class. */
  private int complexitySum;

  /** Maximum cyclomatic complexity of any single method in this class. */
  private int complexityMax;

  /**
   * Fan-in: number of other classes that depend on this class (computed post-extraction via
   * Cypher in Plan 02).
   */
  private int fanIn;

  /**
   * Fan-out: number of classes this class depends on (computed post-extraction via Cypher in Plan
   * 02).
   */
  private int fanOut;

  /** True if any method in this class performs a database write operation. */
  private boolean hasDbWrites;

  /** Count of DB write methods detected in this class. */
  private int dbWriteCount;

  /**
   * Composite structural risk score (0.0–1.0), computed from complexity, fan-in/out, and DB writes
   * via Cypher in Plan 02.
   */
  private double structuralRiskScore;

  // ---------- Phase 7: domain risk metrics ----------

  /**
   * Domain criticality score (0.0–1.0) derived from USES_TERM edges to BusinessTerm nodes.
   * Classes linked to High-criticality terms score 1.0; Medium-criticality terms score 0.5;
   * no USES_TERM edges score 0.0.
   */
  private double domainCriticality;

  /**
   * Security sensitivity score (0.0–1.0) computed from name keywords, security annotations,
   * and package-name heuristics. Graduated: name hit=0.3, annotation hit=0.5, both=+0.2 bonus,
   * package match=+0.2 boost. Clamped to [0.0, 1.0].
   */
  private double securitySensitivity;

  /**
   * Financial involvement score (0.0–1.0) computed from name keywords, package-name heuristics,
   * and USES_TERM edges to financial domain terms. Same graduated weighting as security sensitivity.
   */
  private double financialInvolvement;

  /**
   * Business rule density: log-normalized count of outgoing DEFINES_RULE edges. Value is
   * log(1 + ruleCount), giving 0.0 for classes with no business-rule methods and increasing
   * unboundedly for highly rule-dense classes.
   */
  private double businessRuleDensity;

  /**
   * Enhanced composite risk score combining all 8 dimensions: the 4 structural raw metrics
   * (complexitySum, fanIn, fanOut, hasDbWrites) plus the 4 domain dimensions
   * (domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity).
   * Weights are configurable via {@link com.esmp.extraction.config.RiskWeightConfig}.
   */
  private double enhancedRiskScore;

  // ---------- Phase 16: migration analysis metrics ----------

  /** Total number of migration actions detected for this class. */
  private int migrationActionCount;

  /** Number of migration actions with automatable=YES (fully recipe-automatable). */
  private int automatableActionCount;

  /**
   * Ratio of automatable actions: {@code (yesCount + 0.5 * partialCount) / totalCount}.
   * Returns 0.0 if the class has no migration actions.
   */
  private double automationScore;

  /**
   * True if any migration action for this class has automatable=NO, meaning AI assistance or
   * manual developer effort is required.
   */
  private boolean needsAiMigration;

  // ---------- Phase C: documentation-enriched business context ----------

  /**
   * Concise English business description of what this class does in commercial terms.
   * Assembled from linked NLS terms, documentation context, and abbreviation glossary.
   * Example: "ForeignSupplementPanel — Ad order management. UI: LABEL, MESSAGE. Terms: Supplement, Edition."
   */
  private String businessDescription;

  /**
   * Human-written or LLM-generated natural language description of the class's business role.
   * Set via {@code PUT /api/lexicon/class-description/{fqn}} and never overwritten by re-extraction.
   * Takes priority over {@code businessDescription} in MCP responses and dashboard display.
   */
  private String curatedClassDescription;

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

  public int getComplexitySum() {
    return complexitySum;
  }

  public void setComplexitySum(int complexitySum) {
    this.complexitySum = complexitySum;
  }

  public int getComplexityMax() {
    return complexityMax;
  }

  public void setComplexityMax(int complexityMax) {
    this.complexityMax = complexityMax;
  }

  public int getFanIn() {
    return fanIn;
  }

  public void setFanIn(int fanIn) {
    this.fanIn = fanIn;
  }

  public int getFanOut() {
    return fanOut;
  }

  public void setFanOut(int fanOut) {
    this.fanOut = fanOut;
  }

  public boolean isHasDbWrites() {
    return hasDbWrites;
  }

  public void setHasDbWrites(boolean hasDbWrites) {
    this.hasDbWrites = hasDbWrites;
  }

  public int getDbWriteCount() {
    return dbWriteCount;
  }

  public void setDbWriteCount(int dbWriteCount) {
    this.dbWriteCount = dbWriteCount;
  }

  public double getStructuralRiskScore() {
    return structuralRiskScore;
  }

  public void setStructuralRiskScore(double structuralRiskScore) {
    this.structuralRiskScore = structuralRiskScore;
  }

  public double getDomainCriticality() {
    return domainCriticality;
  }

  public void setDomainCriticality(double domainCriticality) {
    this.domainCriticality = domainCriticality;
  }

  public double getSecuritySensitivity() {
    return securitySensitivity;
  }

  public void setSecuritySensitivity(double securitySensitivity) {
    this.securitySensitivity = securitySensitivity;
  }

  public double getFinancialInvolvement() {
    return financialInvolvement;
  }

  public void setFinancialInvolvement(double financialInvolvement) {
    this.financialInvolvement = financialInvolvement;
  }

  public double getBusinessRuleDensity() {
    return businessRuleDensity;
  }

  public void setBusinessRuleDensity(double businessRuleDensity) {
    this.businessRuleDensity = businessRuleDensity;
  }

  public double getEnhancedRiskScore() {
    return enhancedRiskScore;
  }

  public void setEnhancedRiskScore(double enhancedRiskScore) {
    this.enhancedRiskScore = enhancedRiskScore;
  }

  public int getMigrationActionCount() {
    return migrationActionCount;
  }

  public void setMigrationActionCount(int migrationActionCount) {
    this.migrationActionCount = migrationActionCount;
  }

  public int getAutomatableActionCount() {
    return automatableActionCount;
  }

  public void setAutomatableActionCount(int automatableActionCount) {
    this.automatableActionCount = automatableActionCount;
  }

  public double getAutomationScore() {
    return automationScore;
  }

  public void setAutomationScore(double automationScore) {
    this.automationScore = automationScore;
  }

  public boolean isNeedsAiMigration() {
    return needsAiMigration;
  }

  public void setNeedsAiMigration(boolean needsAiMigration) {
    this.needsAiMigration = needsAiMigration;
  }

  public String getBusinessDescription() {
    return businessDescription;
  }

  public void setBusinessDescription(String businessDescription) {
    this.businessDescription = businessDescription;
  }

  public String getCuratedClassDescription() {
    return curatedClassDescription;
  }

  public void setCuratedClassDescription(String curatedClassDescription) {
    this.curatedClassDescription = curatedClassDescription;
  }
}
