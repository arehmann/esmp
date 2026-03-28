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

  public MigrationActionNode() {}

  public MigrationActionNode(String actionId) {
    this.actionId = actionId;
  }

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
}
