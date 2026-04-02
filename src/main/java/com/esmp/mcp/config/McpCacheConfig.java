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
 * <p>Creates a single named Caffeine cache with TTL driven by {@link McpConfig}.
 * The cache enables {@code recordStats()} so that Micrometer can expose hit/miss metrics via the
 * {@code cache.*} meter family.
 *
 * <ul>
 *   <li>{@code semanticQueries} — RAG assembly results keyed by query text (default TTL 3 min)
 * </ul>
 */
@Configuration
@EnableCaching
public class McpCacheConfig {

  @Bean
  public CacheManager cacheManager(McpConfig mcpConfig) {
    McpConfig.CacheConfig cfg = mcpConfig.getCache();
    int maxSize = cfg.getMaxSize();

    CaffeineCache semanticQueries =
        buildCache(
            "semanticQueries",
            cfg.getSemanticQueryTtlMinutes(),
            maxSize);

    SimpleCacheManager manager = new SimpleCacheManager();
    manager.setCaches(List.of(semanticQueries));
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
