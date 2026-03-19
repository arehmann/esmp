package com.esmp.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the ESMP MCP server.
 *
 * <p>Bound from the {@code esmp.mcp} prefix in {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "esmp.mcp")
public class McpConfig {

  private ContextConfig context = new ContextConfig();
  private CacheConfig cache = new CacheConfig();

  public ContextConfig getContext() {
    return context;
  }

  public void setContext(ContextConfig context) {
    this.context = context;
  }

  public CacheConfig getCache() {
    return cache;
  }

  public void setCache(CacheConfig cache) {
    this.cache = cache;
  }

  /** Context assembly configuration. */
  public static class ContextConfig {

    /** Maximum token budget for the assembled migration context. Default: 8000. */
    private int maxTokens = 8000;

    public int getMaxTokens() {
      return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
      this.maxTokens = maxTokens;
    }
  }

  /** Caffeine cache configuration for MCP caches. */
  public static class CacheConfig {

    /** TTL in minutes for the dependency cone cache. Default: 5. */
    private int dependencyConeTtlMinutes = 5;

    /** TTL in minutes for the domain terms by class cache. Default: 10. */
    private int domainTermsTtlMinutes = 10;

    /** TTL in minutes for the semantic query cache. Default: 3. */
    private int semanticQueryTtlMinutes = 3;

    /** Maximum number of entries per cache. Default: 500. */
    private int maxSize = 500;

    public int getDependencyConeTtlMinutes() {
      return dependencyConeTtlMinutes;
    }

    public void setDependencyConeTtlMinutes(int dependencyConeTtlMinutes) {
      this.dependencyConeTtlMinutes = dependencyConeTtlMinutes;
    }

    public int getDomainTermsTtlMinutes() {
      return domainTermsTtlMinutes;
    }

    public void setDomainTermsTtlMinutes(int domainTermsTtlMinutes) {
      this.domainTermsTtlMinutes = domainTermsTtlMinutes;
    }

    public int getSemanticQueryTtlMinutes() {
      return semanticQueryTtlMinutes;
    }

    public void setSemanticQueryTtlMinutes(int semanticQueryTtlMinutes) {
      this.semanticQueryTtlMinutes = semanticQueryTtlMinutes;
    }

    public int getMaxSize() {
      return maxSize;
    }

    public void setMaxSize(int maxSize) {
      this.maxSize = maxSize;
    }
  }
}
