package com.esmp.extraction.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * Creates Neo4j uniqueness constraints for all AST node types at application startup.
 *
 * <p>Constraints are created with {@code IF NOT EXISTS} so this is safe to run repeatedly. They act
 * as a database-level safety net against duplicate nodes on re-extraction, complementing the
 * application-level idempotency provided by the {@code @Version} fields on each entity.
 */
@Component
public class Neo4jSchemaInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaInitializer.class);

  private final Neo4jClient neo4jClient;

  public Neo4jSchemaInitializer(Neo4jClient neo4jClient) {
    this.neo4jClient = neo4jClient;
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info("Creating Neo4j uniqueness constraints for AST node types...");

    createConstraint(
        "java_class_fqn_unique",
        "CREATE CONSTRAINT java_class_fqn_unique IF NOT EXISTS"
            + " FOR (n:JavaClass) REQUIRE n.fullyQualifiedName IS UNIQUE");

    createConstraint(
        "java_method_id_unique",
        "CREATE CONSTRAINT java_method_id_unique IF NOT EXISTS"
            + " FOR (n:JavaMethod) REQUIRE n.methodId IS UNIQUE");

    createConstraint(
        "java_field_id_unique",
        "CREATE CONSTRAINT java_field_id_unique IF NOT EXISTS"
            + " FOR (n:JavaField) REQUIRE n.fieldId IS UNIQUE");

    createConstraint(
        "java_annotation_fqn_unique",
        "CREATE CONSTRAINT java_annotation_fqn_unique IF NOT EXISTS"
            + " FOR (n:JavaAnnotation) REQUIRE n.fullyQualifiedName IS UNIQUE");

    createConstraint(
        "java_package_name_unique",
        "CREATE CONSTRAINT java_package_name_unique IF NOT EXISTS"
            + " FOR (n:JavaPackage) REQUIRE n.packageName IS UNIQUE");

    createConstraint(
        "java_module_name_unique",
        "CREATE CONSTRAINT java_module_name_unique IF NOT EXISTS"
            + " FOR (n:JavaModule) REQUIRE n.moduleName IS UNIQUE");

    createConstraint(
        "db_table_name_unique",
        "CREATE CONSTRAINT db_table_name_unique IF NOT EXISTS"
            + " FOR (n:DBTable) REQUIRE n.tableName IS UNIQUE");

    createConstraint(
        "business_term_id_unique",
        "CREATE CONSTRAINT business_term_id_unique IF NOT EXISTS"
            + " FOR (n:BusinessTerm) REQUIRE n.termId IS UNIQUE");

    // Phase 6: structural risk score range index for efficient heatmap ORDER BY queries
    createConstraint(
        "java_class_risk_score",
        "CREATE INDEX java_class_risk_score IF NOT EXISTS"
            + " FOR (n:JavaClass) ON (n.structuralRiskScore)");

    log.info("Neo4j uniqueness constraints for AST node types are in place.");
  }

  private void createConstraint(String name, String cypher) {
    try {
      neo4jClient.query(cypher).run();
      log.info("Constraint '{}' ensured.", name);
    } catch (Exception e) {
      log.warn("Could not create constraint '{}': {}", name, e.getMessage());
    }
  }
}
