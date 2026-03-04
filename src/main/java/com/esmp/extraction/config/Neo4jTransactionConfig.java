package com.esmp.extraction.config;

import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.data.neo4j.core.mapping.Neo4jMappingContext;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * Explicit transaction manager configuration for projects with both JPA and Neo4j.
 *
 * <p>When both JPA and Neo4j are on the classpath, Spring Boot's auto-configurations conflict:
 * JPA's {@code JpaBaseConfiguration.transactionManager()} has {@code @Primary} and
 * {@code @ConditionalOnMissingBean(PlatformTransactionManager.class)}, and Neo4j's {@code
 * Neo4jDataAutoConfiguration.transactionManager()} has {@code @ConditionalOnMissingBean} too. If
 * one of them runs first, the other is suppressed. This causes {@code Neo4jTemplate} to have a null
 * {@code transactionTemplate}, throwing NPE on any write operation.
 *
 * <p>This configuration explicitly creates BOTH transaction managers with distinct names so that
 * neither suppresses the other. JPA's is named {@code "transactionManager"} (as expected by SDN
 * repository {@code @Transactional} annotations); Neo4j's is named {@code
 * "neo4jTransactionManager"} and injected into a custom {@code Neo4jTemplate}. The Neo4j extraction
 * service uses {@code @Transactional("neo4jTransactionManager")} explicitly.
 */
@Configuration
public class Neo4jTransactionConfig {

  /**
   * JPA transaction manager — named {@code "transactionManager"} to satisfy SDN repository
   * {@code @Transactional} annotations and JPA persistence operations. Marked {@code @Primary} so
   * it is chosen by default when no qualifier is specified.
   */
  @Bean
  @Primary
  public JpaTransactionManager transactionManager(
      jakarta.persistence.EntityManagerFactory entityManagerFactory) {
    return new JpaTransactionManager(entityManagerFactory);
  }

  /**
   * Neo4j-specific transaction manager registered under {@code "neo4jTransactionManager"} so it
   * coexists with JPA's {@code "transactionManager"} bean without triggering
   * {@code @ConditionalOnMissingBean} suppression.
   */
  @Bean("neo4jTransactionManager")
  public Neo4jTransactionManager neo4jTransactionManager(
      Driver driver, DatabaseSelectionProvider databaseSelectionProvider) {
    return new Neo4jTransactionManager(driver, databaseSelectionProvider);
  }

  /**
   * Replaces the auto-configured {@code Neo4jTemplate} with one that has the Neo4j transaction
   * manager injected, so that {@code saveAll()} and other write operations work correctly without
   * relying on a null internal {@code transactionTemplate}.
   */
  @Bean
  public Neo4jTemplate neo4jTemplate(
      Neo4jClient neo4jClient,
      Neo4jMappingContext mappingContext,
      Neo4jTransactionManager neo4jTransactionManager) {
    return new Neo4jTemplate(neo4jClient, mappingContext, neo4jTransactionManager);
  }
}
