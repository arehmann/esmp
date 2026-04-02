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
 * completes to ensure stale cache entries are invalidated.
 *
 * <p>Cache eviction strategy:
 * <ul>
 *   <li>{@code semanticQueries} — cleared entirely (query-keyed, not FQN-keyed)
 * </ul>
 */
@Service
public class McpCacheEvictionService {

  private static final Logger log = LoggerFactory.getLogger(McpCacheEvictionService.class);

  static final String CACHE_SEMANTIC_QUERIES = "semanticQueries";

  private final CacheManager cacheManager;

  public McpCacheEvictionService(CacheManager cacheManager) {
    this.cacheManager = cacheManager;
  }

  /**
   * Evicts cache entries after classes have been re-indexed.
   *
   * <p>Clears the {@code semanticQueries} cache entirely because it is keyed by query
   * parameters (not class FQNs) and selective eviction is not possible.
   *
   * @param classFqns list of fully-qualified class names that were re-indexed
   */
  public void evictForClasses(List<String> classFqns) {
    if (classFqns == null || classFqns.isEmpty()) {
      return;
    }

    clearCache(CACHE_SEMANTIC_QUERIES);

    log.info("MCP cache eviction complete: cleared semanticQueries for {} re-indexed classes",
        classFqns.size());
  }

  /**
   * Evicts all MCP caches entirely.
   */
  public void evictAll() {
    clearCache(CACHE_SEMANTIC_QUERIES);
    log.info("MCP full cache eviction complete: cleared semanticQueries cache");
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
