package com.esmp.extraction.model;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * Neo4j node representing a relational database table.
 *
 * <p>Uses a business-key {@code @Id} (the lowercased table name) with {@code @Version} to enable
 * idempotent MERGE semantics via Spring Data Neo4j's {@code save()} method.
 *
 * <p>Table names are stored in lowercase for case-insensitive deduplication across RDBMS dialects.
 */
@Node("DBTable")
public class DBTableNode {

  /** Business key: lowercased table name (e.g., {@code order_item}). */
  @Id private String tableName;

  /** Enables SDN to detect new vs. existing entities for MERGE semantics. */
  @Version private Long version;

  /** Database schema name, if applicable (e.g., {@code public} or {@code dbo}). May be null. */
  private String schemaName;

  public DBTableNode() {}

  public DBTableNode(String tableName) {
    this.tableName = tableName;
  }

  public String getTableName() {
    return tableName;
  }

  public void setTableName(String tableName) {
    this.tableName = tableName;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public String getSchemaName() {
    return schemaName;
  }

  public void setSchemaName(String schemaName) {
    this.schemaName = schemaName;
  }
}
