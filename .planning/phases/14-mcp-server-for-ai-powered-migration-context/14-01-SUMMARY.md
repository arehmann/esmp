---
phase: 14-mcp-server-for-ai-powered-migration-context
plan: 01
subsystem: mcp
tags: [spring-ai, mcp, caffeine, micrometer, rag, neo4j, qdrant]

# Dependency graph
requires:
  - phase: 11-rag-pipeline
    provides: RagService.assemble() with weighted re-ranking (vectorSimilarity + graphProximity + riskScore)
  - phase: 12-governance-dashboard
    provides: DashboardService Cypher aggregation patterns
  - phase: 08-smart-chunking-vector-indexing
    provides: VectorIndexingService, ChunkingService, Qdrant code_chunks collection
provides:
  - MCP SSE endpoint at /mcp/sse registered by spring-ai-starter-mcp-server-webmvc
  - MigrationContextAssembler.assemble(classFqn) - 5-parallel-service context aggregator
  - MigrationContext record with cone, risk, terms, rules, code chunks, completeness, warnings
  - McpCacheConfig with 3 named Caffeine caches (dependencyCones, domainTermsByClass, semanticQueries)
  - McpObservabilityConfig with TimedAspect for @Timed annotation support
  - Token budget truncation (code chunks first, then cone nodes)
  - Graceful degradation with AssemblerWarning records
affects: [14-02 mcp-tools-and-tool-service — uses MigrationContextAssembler and McpConfig]

# Tech tracking
tech-stack:
  added:
    - spring-ai-starter-mcp-server-webmvc:1.1.2 — MCP SSE/WebMVC transport layer
    - spring-boot-starter-cache — provides CaffeineCache and SimpleCacheManager
    - caffeine:3.2.3 — in-memory cache backend with TTL and recordStats()
  patterns:
    - Pure read orchestrator: MigrationContextAssembler is NOT @Transactional (same convention as RagService, SchedulingService)
    - Parallel futures: CompletableFuture.supplyAsync for 5 independent I/O calls
    - Token estimation: JSON serialization length / 4 for chars-per-token budget
    - ConfigurationProperties: McpConfig with nested ContextConfig and CacheConfig inner classes
    - Caffeine multi-cache: SimpleCacheManager with individual CaffeineCache instances for different TTLs

key-files:
  created:
    - src/main/java/com/esmp/mcp/config/McpConfig.java
    - src/main/java/com/esmp/mcp/config/McpCacheConfig.java
    - src/main/java/com/esmp/mcp/config/McpObservabilityConfig.java
    - src/main/java/com/esmp/mcp/api/MigrationContext.java
    - src/main/java/com/esmp/mcp/api/AssemblerWarning.java
    - src/main/java/com/esmp/mcp/application/MigrationContextAssembler.java
    - src/test/java/com/esmp/mcp/McpServerStartupTest.java
    - src/test/java/com/esmp/mcp/application/MigrationContextAssemblerTest.java
  modified:
    - gradle/libs.versions.toml — added spring-ai-starter-mcp-server-webmvc, spring-boot-starter-cache, caffeine
    - build.gradle.kts — 3 new implementation dependencies
    - src/main/resources/application.yml — spring.ai.mcp.server SSE config + esmp.mcp properties

key-decisions:
  - "CacheManager uses SimpleCacheManager with individual CaffeineCache instances instead of CaffeineCacheManager.setCacheSpecification() — only way to assign different TTLs per cache"
  - "spring-boot-starter-cache is required in addition to caffeine — provides CaffeineCache/SimpleCacheManager from spring-context-support (not in MCP starter transitive deps)"
  - "McpServerStartupTest uses raw TCP socket with SO_TIMEOUT to read HTTP status line — TestRestTemplate.getForEntity() blocks indefinitely on SSE streaming connections that never close"
  - "MigrationContextAssembler delegates to RagService.assemble() for code chunks (not EmbeddingModel + VectorSearchService directly) — preserves weighted re-ranking per user decision in CONTEXT.md"
  - "Token budget safety factor 0.90 — truncate when over 90% of maxTokens to leave headroom for MCP tool wrapping overhead"

patterns-established:
  - "MCP context assembly: 5-service parallel futures with individual try-catch for graceful degradation"
  - "Completeness scoring: each service contributes a fixed weight (cone=0.25, risk=0.20, terms=0.15, rules=0.10, chunks=0.30)"
  - "Token budget: drop code chunks from end first, then truncate cone nodes to first 50"

requirements-completed: [MCP-01, MCP-02, MCP-08, SLO-MCP-01]

# Metrics
duration: 75min
completed: 2026-03-19
---

# Phase 14 Plan 01: MCP Server Foundation Summary

**Spring AI MCP SSE server with MigrationContextAssembler aggregating 5 ESMP services (cone, risk, terms, rules, RAG chunks) in parallel via CompletableFuture with Caffeine caching and graceful degradation**

## Performance

- **Duration:** 75 min
- **Started:** 2026-03-19T07:19:40Z
- **Completed:** 2026-03-19T09:25:00Z
- **Tasks:** 2
- **Files modified:** 11

## Accomplishments
- MCP SSE endpoint `/mcp/sse` registered by Spring AI MCP WebMVC starter (verified with raw TCP socket test)
- MigrationContextAssembler delegates to RagService for re-ranked code chunks, runs 5 futures in parallel
- Caffeine cache manager with 3 named caches and per-cache TTLs (5/10/3 min)
- Full graceful degradation: each service wrapped in try-catch, partial context returned with AssemblerWarning entries
- Token budget truncation drops code chunks first then cone nodes when over 90% of max-tokens
- 4/4 tests pass: MCP-01 (SSE reachable), MCP-02 (integration), MCP-08 (degradation), token truncation

## Task Commits

Each task was committed atomically:

1. **Task 1: Dependencies, config, response records, cache, observability, and startup test** - `a87afed` (feat)
2. **Task 2: MigrationContextAssembler with parallel assembly, graceful degradation, and token budgeting** - `b78a235` (feat)

**Plan metadata:** (created with this summary commit)

## Files Created/Modified
- `gradle/libs.versions.toml` — Added spring-ai-starter-mcp-server-webmvc, spring-boot-starter-cache, caffeine entries
- `build.gradle.kts` — 3 new implementation dependencies
- `src/main/resources/application.yml` — spring.ai.mcp.server SSE config block + esmp.mcp context/cache properties
- `src/main/java/com/esmp/mcp/config/McpConfig.java` — @ConfigurationProperties with ContextConfig and CacheConfig inner classes
- `src/main/java/com/esmp/mcp/config/McpCacheConfig.java` — @EnableCaching, SimpleCacheManager with 3 CaffeineCache instances
- `src/main/java/com/esmp/mcp/config/McpObservabilityConfig.java` — TimedAspect bean for @Timed annotation support
- `src/main/java/com/esmp/mcp/api/MigrationContext.java` — Response record with List<ContextChunk> from RagService
- `src/main/java/com/esmp/mcp/api/AssemblerWarning.java` — Warning record for graceful degradation
- `src/main/java/com/esmp/mcp/application/MigrationContextAssembler.java` — Core 5-service aggregator
- `src/test/java/com/esmp/mcp/McpServerStartupTest.java` — Raw TCP socket test for SSE endpoint
- `src/test/java/com/esmp/mcp/application/MigrationContextAssemblerTest.java` — Integration + unit tests

## Decisions Made
- `CacheManager` uses `SimpleCacheManager` with individual `CaffeineCache` instances — only way to assign different TTLs per cache with the Caffeine Spring integration.
- `spring-boot-starter-cache` required in addition to `caffeine` — provides `CaffeineCache`/`SimpleCacheManager` from `spring-context-support` which is not in the MCP starter's transitive deps.
- `McpServerStartupTest` uses raw TCP socket with `SO_TIMEOUT` — `TestRestTemplate.getForEntity()` blocks indefinitely on SSE streaming endpoints. The socket reads just the HTTP status line (`HTTP/1.1 200`) then the timeout fires, confirming endpoint registration without hanging.
- `MigrationContextAssembler` delegates to `RagService.assemble()` for code chunks — preserves weighted re-ranking (vectorSimilarity 0.40, graphProximity 0.35, riskScore 0.25) per user architectural decision.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added spring-boot-starter-cache dependency**
- **Found during:** Task 1 (McpCacheConfig compilation)
- **Issue:** `CaffeineCache` and `SimpleCacheManager` classes are in `spring-context-support`, which is not in the transitive deps of `spring-ai-starter-mcp-server-webmvc` or `caffeine`. Compilation failed with "Package org.springframework.cache.caffeine is not available".
- **Fix:** Added `spring-boot-starter-cache` to `libs.versions.toml` and `build.gradle.kts`
- **Files modified:** gradle/libs.versions.toml, build.gradle.kts
- **Verification:** `compileJava` passes after addition
- **Committed in:** a87afed (Task 1 commit)

**2. [Rule 1 - Bug] Fixed McpServerStartupTest blocking on SSE stream**
- **Found during:** Task 1 (McpServerStartupTest execution hung indefinitely)
- **Issue:** SSE connections stream continuously and never close. `TestRestTemplate.getForEntity("/mcp/sse", String.class)` blocks waiting for the connection to terminate. The test process eventually times out after the Gradle test timeout.
- **Fix:** Replaced `TestRestTemplate` with a raw TCP `Socket` with 5-second `SO_TIMEOUT`. The socket sends a minimal HTTP request, reads the response status line (`HTTP/1.1 200`), then closes. This confirms the endpoint is registered without blocking.
- **Files modified:** src/test/java/com/esmp/mcp/McpServerStartupTest.java
- **Verification:** Test passes in ~1 second, `BUILD SUCCESSFUL`
- **Committed in:** b78a235 (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (1 blocking dependency, 1 test blocking bug)
**Impact on plan:** Both auto-fixes required for compilation and test completion. No scope creep.

## Issues Encountered
- Gradle daemon resource contention: 10+ busy daemons required using `--rerun-tasks` to force test execution during parallel test runs. No code impact.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- MCP SSE endpoint wired and tested
- MigrationContextAssembler.assemble(fqn) ready for Plan 02 MCP tool wrapping
- Caffeine cache manager with named caches ready for @Cacheable annotations in Plan 02
- TimedAspect bean ready for @Timed on MCP tool methods in Plan 02

---
*Phase: 14-mcp-server-for-ai-powered-migration-context*
*Completed: 2026-03-19*

## Self-Check: PASSED
- All 8 created files exist on disk
- Task commits a87afed and b78a235 verified in git log
- 4/4 tests pass: MCP-01, MCP-02, MCP-08, token truncation
