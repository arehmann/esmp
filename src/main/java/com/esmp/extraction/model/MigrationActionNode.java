package com.esmp.extraction.model;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * Neo4j node representing a single migration action for a Vaadin 7 → Vaadin 24 migration.
 *
 * <p>One MigrationActionNode is created per import-based migration action detected by
 * {@link com.esmp.extraction.visitor.MigrationPatternVisitor}. Nodes are linked to their
 * containing JavaClass via {@code HAS_MIGRATION_ACTION} edges.
 *
 * <p>The {@code actionId} is a business key composed as
 * {@code classFqn + "#" + actionType + "#" + source} for stable deduplication across
 * extraction runs.
 *
 * <h3>Transitive Detection Fields (Plan 02)</h3>
 *
 * <p>Fields prefixed with "transitive" or "vaadin" are populated by the transitive detection
 * algorithm in Plan 02. They are {@code null} / default for directly-detected actions and
 * populated only when {@link #isInherited} is {@code true}.
 */
@Node("MigrationAction")
public class MigrationActionNode {

  /**
   * Business key: composite of {@code classFqn + "#" + actionType + "#" + source}.
   * This provides stable, deterministic IDs for idempotent MERGE semantics.
   */
  @Id private String actionId;

  /** Enables SDN to detect new vs. existing entities for MERGE semantics. */
  @Version private Long version;

  /** Fully qualified name of the class that requires this migration action. */
  private String classFqn;

  /**
   * Action type name (enum name string from {@link
   * com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.ActionType}).
   * Examples: CHANGE_TYPE, CHANGE_PACKAGE, COMPLEX_REWRITE.
   */
  private String actionType;

  /** Fully qualified source type or package prefix (Vaadin 7 or javax). */
  private String source;

  /**
   * Fully qualified target type or package prefix (Vaadin 24 or jakarta), or a descriptive
   * label for complex migrations (e.g., "DataProvider").
   */
  private String target;

  /**
   * Automation classification name (enum name string from
   * {@link com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.Automatable}).
   * Values: YES, PARTIAL, NO.
   */
  private String automatable;

  /** Optional context note explaining why the action is complex or what manual steps are needed. */
  private String context;

  // =========================================================================
  // Transitive detection fields (Phase 17 Plan 02)
  // =========================================================================

  /**
   * {@code true} when this action was detected transitively (the class inherits Vaadin 7
   * ancestry but does not directly import the Vaadin 7 type itself). {@code false} for
   * directly-detected actions.
   */
  private boolean isInherited;

  /**
   * {@code true} when the class is a pure wrapper — it inherits Vaadin 7 ancestry but adds
   * no Vaadin-specific logic ({@link #transitiveComplexity} == 0). {@code null} for directly-
   * detected actions where this concept does not apply.
   */
  private Boolean pureWrapper;

  /**
   * Composite transitive complexity score in the range [0.0, 1.0]. Computed from override count,
   * own Vaadin call count, binding and component usage. {@code null} for direct actions.
   */
  private Double transitiveComplexity;

  /**
   * Fully qualified name of the first Vaadin 7 ancestor class that triggered this transitive
   * detection. {@code null} for direct actions.
   */
  private String vaadinAncestor;

  /**
   * Number of methods in this class that override Vaadin ancestor methods. Contributes to
   * {@link #transitiveComplexity}. {@code null} for direct actions.
   */
  private Integer overrideCount;

  /**
   * Number of direct Vaadin 7 API calls in this class's own methods (not counting inherited
   * methods). Contributes to {@link #transitiveComplexity}. {@code null} for direct actions.
   */
  private Integer ownVaadinCalls;

  /**
   * Number of distinct Alfa* wrapper types (com.alfa.*) the class calls or uses directly in its
   * own declared methods. Contributes to {@link #transitiveComplexity} via alfaCallsWeight.
   * {@code null} for direct (non-inherited) actions.
   */
  private Integer ownAlfaCalls;

  // =========================================================================
  // Constructors
  // =========================================================================

  public MigrationActionNode() {}

  public MigrationActionNode(String actionId) {
    this.actionId = actionId;
  }

  // =========================================================================
  // Getters and setters
  // =========================================================================

  public String getActionId() {
    return actionId;
  }

  public void setActionId(String actionId) {
    this.actionId = actionId;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public String getClassFqn() {
    return classFqn;
  }

  public void setClassFqn(String classFqn) {
    this.classFqn = classFqn;
  }

  public String getActionType() {
    return actionType;
  }

  public void setActionType(String actionType) {
    this.actionType = actionType;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getTarget() {
    return target;
  }

  public void setTarget(String target) {
    this.target = target;
  }

  public String getAutomatable() {
    return automatable;
  }

  public void setAutomatable(String automatable) {
    this.automatable = automatable;
  }

  public String getContext() {
    return context;
  }

  public void setContext(String context) {
    this.context = context;
  }

  public boolean isInherited() {
    return isInherited;
  }

  public void setInherited(boolean inherited) {
    isInherited = inherited;
  }

  public Boolean getPureWrapper() {
    return pureWrapper;
  }

  public void setPureWrapper(Boolean pureWrapper) {
    this.pureWrapper = pureWrapper;
  }

  public Double getTransitiveComplexity() {
    return transitiveComplexity;
  }

  public void setTransitiveComplexity(Double transitiveComplexity) {
    this.transitiveComplexity = transitiveComplexity;
  }

  public String getVaadinAncestor() {
    return vaadinAncestor;
  }

  public void setVaadinAncestor(String vaadinAncestor) {
    this.vaadinAncestor = vaadinAncestor;
  }

  public Integer getOverrideCount() {
    return overrideCount;
  }

  public void setOverrideCount(Integer overrideCount) {
    this.overrideCount = overrideCount;
  }

  public Integer getOwnVaadinCalls() {
    return ownVaadinCalls;
  }

  public void setOwnVaadinCalls(Integer ownVaadinCalls) {
    this.ownVaadinCalls = ownVaadinCalls;
  }

  public Integer getOwnAlfaCalls() {
    return ownAlfaCalls;
  }

  public void setOwnAlfaCalls(Integer ownAlfaCalls) {
    this.ownAlfaCalls = ownAlfaCalls;
  }
}
