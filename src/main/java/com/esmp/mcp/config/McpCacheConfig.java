package com.esmp.mcp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration for the MCP server.
 *
 * <p>Creates three named Caffeine caches with per-cache TTL values driven by {@link McpConfig}.
 * Each cache enables {@code recordStats()} so that Micrometer can expose hit/miss metrics via the
 * {@code cache.*} meter family.
 *
 * <ul>
 *   <li>{@code dependencyCones} — dependency cone traversal results (default TTL 5 min)
 *   <li>{@code domainTermsByClass} — USES_TERM lookups per class FQN (default TTL 10 min)
 *   <li>{@code semanticQueries} — RAG assembly results keyed by query text (default TTL 3 min)
 * </ul>
 */
@Configuration
@EnableCaching
public class McpCacheConfig {

  /**
   * Creates a {@link CacheManager} backed by three individually-configured Caffeine caches.
   *
   * <p>A {@link SimpleCacheManager} is used instead of {@link
   * org.springframework.cache.caffeine.CaffeineCacheManager} because the three caches require
   * different TTL values and {@code CaffeineCacheManager} applies a single specification to all
   * caches.
   *
   * @param mcpConfig MCP configuration properties
   * @return configured cache manager
   */
  @Bean
  public CacheManager cacheManager(McpConfig mcpConfig) {
    McpConfig.CacheConfig cfg = mcpConfig.getCache();
    int maxSize = cfg.getMaxSize();

    CaffeineCache dependencyCones =
        buildCache(
            "dependencyCones",
            cfg.getDependencyConeTtlMinutes(),
            maxSize);

    CaffeineCache domainTermsByClass =
        buildCache(
            "domainTermsByClass",
            cfg.getDomainTermsTtlMinutes(),
            maxSize);

    CaffeineCache semanticQueries =
        buildCache(
            "semanticQueries",
            cfg.getSemanticQueryTtlMinutes(),
            maxSize);

    SimpleCacheManager manager = new SimpleCacheManager();
    manager.setCaches(List.of(dependencyCones, domainTermsByClass, semanticQueries));
    return manager;
  }

  private CaffeineCache buildCache(String name, int ttlMinutes, int maxSize) {
    return new CaffeineCache(
        name,
        Caffeine.newBuilder()
            .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
            .maximumSize(maxSize)
            .recordStats()
            .build());
  }
}
