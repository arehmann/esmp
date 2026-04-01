package com.esmp.extraction.model;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * Neo4j node representing a domain business term extracted from the codebase.
 *
 * <p>Terms are extracted from class names (camelCase split), enum type names and constants, class-
 * level Javadoc, and DB table/column names. The {@code termId} is the lowercased normalized form of
 * the term and serves as the unique business key.
 *
 * <p>Uses {@code @Version} for SDN MERGE semantics (idempotent re-extraction). The curated-guard
 * MERGE in {@link com.esmp.extraction.application.ExtractionService} ensures that human-curated
 * definitions ({@code curated=true}) are never overwritten on re-extraction.
 */
@Node("BusinessTerm")
public class BusinessTermNode {

  /** Business key: lowercase normalized term identifier (e.g., {@code "invoice"}). */
  @Id private String termId;

  /** Enables SDN to detect new vs. existing entities for MERGE semantics. */
  @Version private Long version;

  /** Display-friendly term name (e.g., {@code "Invoice"}). */
  private String displayName;

  /**
   * Human-readable definition. Seeded from class-level Javadoc on first extraction. Preserved
   * on re-extraction when {@code curated=true}.
   */
  private String definition;

  /**
   * Business criticality of the term. Heuristic seeding: "High" for financial/security terms,
   * "Low" for all others. Default: "Low".
   */
  private String criticality = "Low";

  /**
   * Sensitivity of this term in the context of data migration. Default: "None".
   */
  private String migrationSensitivity = "None";

  /**
   * Alternative names or acronyms for this term. Stored as a property list in Neo4j.
   */
  @Property("synonyms")
  private List<String> synonyms = new ArrayList<>();

  /**
   * True when a human has manually reviewed/edited this term's definition or metadata. Curated
   * terms are protected from overwrite on re-extraction.
   */
  private boolean curated = false;

  /**
   * Lifecycle status of the term. Values: "auto" (system-extracted), "curated" (human-reviewed),
   * "deprecated".
   */
  private String status = "auto";

  /** Source type indicating where this term was extracted from (e.g., CLASS_NAME, ENUM_CONSTANT, DB_TABLE). */
  private String sourceType;

  /** FQN of the class/type where this term was first extracted. */
  private String primarySourceFqn;

  /** Number of source locations (class FQNs) that reference this term. */
  private int usageCount = 0;

  /**
   * UI role derived from NLS key prefix (e.g., LABEL, MESSAGE, TOOLTIP, BUTTON).
   * Tells the migration agent which Vaadin 24 component to use for this string.
   * Null for non-NLS terms.
   */
  private String uiRole;

  /**
   * Business domain area derived from the NLS XML source filename (e.g., ORDER_MANAGEMENT,
   * CONTRACT_MANAGEMENT, COMMON). Null for non-NLS terms.
   */
  private String domainArea;

  /**
   * Name of the NLS XML file this term was extracted from (e.g., "Order.xml").
   * Null for non-NLS terms.
   */
  private String nlsFileName;

  /**
   * Excerpt from legacy documentation matched to this term by keyword overlap.
   * Provides business context explaining what this concept means in the domain.
   * Null if no matching documentation was found.
   */
  private String documentContext;

  /**
   * Source reference for the documentation context (e.g., "AdSuite_Bedienungshandbuch.pdf § 4.3 Anzeigendisposition").
   * Null if no documentation context was matched.
   */
  private String documentSource;

  public BusinessTermNode() {}

  public BusinessTermNode(String termId) {
    this.termId = termId;
  }

  public String getTermId() {
    return termId;
  }

  public void setTermId(String termId) {
    this.termId = termId;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getDefinition() {
    return definition;
  }

  public void setDefinition(String definition) {
    this.definition = definition;
  }

  public String getCriticality() {
    return criticality;
  }

  public void setCriticality(String criticality) {
    this.criticality = criticality;
  }

  public String getMigrationSensitivity() {
    return migrationSensitivity;
  }

  public void setMigrationSensitivity(String migrationSensitivity) {
    this.migrationSensitivity = migrationSensitivity;
  }

  public List<String> getSynonyms() {
    return synonyms;
  }

  public void setSynonyms(List<String> synonyms) {
    this.synonyms = synonyms;
  }

  public boolean isCurated() {
    return curated;
  }

  public void setCurated(boolean curated) {
    this.curated = curated;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getPrimarySourceFqn() {
    return primarySourceFqn;
  }

  public void setPrimarySourceFqn(String primarySourceFqn) {
    this.primarySourceFqn = primarySourceFqn;
  }

  public int getUsageCount() {
    return usageCount;
  }

  public void setUsageCount(int usageCount) {
    this.usageCount = usageCount;
  }

  public String getUiRole() {
    return uiRole;
  }

  public void setUiRole(String uiRole) {
    this.uiRole = uiRole;
  }

  public String getDomainArea() {
    return domainArea;
  }

  public void setDomainArea(String domainArea) {
    this.domainArea = domainArea;
  }

  public String getNlsFileName() {
    return nlsFileName;
  }

  public void setNlsFileName(String nlsFileName) {
    this.nlsFileName = nlsFileName;
  }

  public String getDocumentContext() {
    return documentContext;
  }

  public void setDocumentContext(String documentContext) {
    this.documentContext = documentContext;
  }

  public String getDocumentSource() {
    return documentSource;
  }

  public void setDocumentSource(String documentSource) {
    this.documentSource = documentSource;
  }
}
