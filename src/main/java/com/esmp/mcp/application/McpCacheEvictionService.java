package com.esmp.mcp.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * Cache eviction service for MCP caches.
 *
 * <p>Called by {@link com.esmp.indexing.application.IncrementalIndexingService} after a reindex
 * completes to ensure stale cache entries are invalidated. This keeps the Caffeine caches fresh
 * after graph/vector data changes.
 *
 * <p>Cache eviction strategy:
 * <ul>
 *   <li>{@code dependencyCones} — evicted per-FQN via key-based eviction, since the cache is
 *       keyed by class FQN
 *   <li>{@code domainTermsByClass} — cleared entirely (not FQN-keyed; keyed by search+criticality
 *       query params), so selective FQN eviction would silently no-op
 *   <li>{@code semanticQueries} — cleared entirely (also query-keyed, not FQN-keyed)
 * </ul>
 */
@Service
public class McpCacheEvictionService {

  private static final Logger log = LoggerFactory.getLogger(McpCacheEvictionService.class);

  static final String CACHE_DEPENDENCY_CONES = "dependencyCones";
  static final String CACHE_DOMAIN_TERMS = "domainTermsByClass";
  static final String CACHE_SEMANTIC_QUERIES = "semanticQueries";

  private final CacheManager cacheManager;

  public McpCacheEvictionService(CacheManager cacheManager) {
    this.cacheManager = cacheManager;
  }

  /**
   * Evicts cache entries for the specified class FQNs.
   *
   * <p>For {@code dependencyCones}, evicts each FQN key individually.
   * For {@code domainTermsByClass} and {@code semanticQueries}, clears the entire cache because
   * these are keyed by query parameters (not class FQNs) and selective eviction is not possible.
   *
   * @param classFqns list of fully-qualified class names that were re-indexed
   */
  public void evictForClasses(List<String> classFqns) {
    if (classFqns == null || classFqns.isEmpty()) {
      return;
    }

    // Evict dependency cones per-FQN (cache is keyed by classFqn)
    Cache dependencyCones = cacheManager.getCache(CACHE_DEPENDENCY_CONES);
    if (dependencyCones != null) {
      for (String fqn : classFqns) {
        dependencyCones.evict(fqn);
        log.debug("Evicted dependencyCones cache entry for fqn={}", fqn);
      }
    }

    // Clear query-keyed caches entirely (domain terms and semantic queries are not FQN-keyed)
    clearCache(CACHE_DOMAIN_TERMS);
    clearCache(CACHE_SEMANTIC_QUERIES);

    log.info("MCP cache eviction complete: evicted {} FQNs from dependencyCones, "
        + "cleared domainTermsByClass and semanticQueries", classFqns.size());
  }

  /**
   * Evicts all 3 MCP caches entirely.
   *
   * <p>Called on full re-index to ensure no stale data remains in any cache.
   */
  public void evictAll() {
    clearCache(CACHE_DEPENDENCY_CONES);
    clearCache(CACHE_DOMAIN_TERMS);
    clearCache(CACHE_SEMANTIC_QUERIES);
    log.info("MCP full cache eviction complete: cleared all 3 caches");
  }

  private void clearCache(String cacheName) {
    Cache cache = cacheManager.getCache(cacheName);
    if (cache != null) {
      cache.clear();
      log.debug("Cleared cache '{}'", cacheName);
    } else {
      log.warn("Cache '{}' not found in CacheManager — eviction skipped", cacheName);
    }
  }
}
