# Phase 14: MCP Server for AI-Powered Migration Context - Research

**Researched:** 2026-03-19
**Domain:** Spring AI MCP Server (WebMVC SSE), Caffeine caching, Micrometer observability, MCP protocol
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- SSE (Server-Sent Events) transport embedded in the existing Spring Boot application
- NOT a separate process — MCP endpoint coexists with Vaadin UI and REST APIs
- Single process, single set of DB connections (Neo4j, Qdrant, MySQL)
- Claude Code connects via `http://localhost:8080/mcp/sse` (or configured port)
- Use Spring AI MCP Server Spring Boot Starter (`spring-ai-mcp-server-spring-boot-starter`)
- **6 tools — Hybrid Approach:**
  - `get_migration_context` — aggregate: FQN in, full migration context out
  - `search_knowledge` — semantic vector search with optional filters
  - `get_dependency_cone` — graph exploration (FQN + depth)
  - `get_risk_analysis` — risk heatmap OR class-level detail
  - `browse_domain_terms` — lexicon search/browse by criticality
  - `validate_system_health` — run all 41 validation queries
- MigrationContextAssembler `@Service`: parallel async (CompletableFuture), priority-ordered assembly, smart token budget truncation
- Token budget: `esmp.mcp.context.max-tokens=8000` (configurable), always preserve rules + terms + risk, truncate code chunks first
- Caffeine local cache: dependency cones (TTL ~5min), domain terms per class (TTL ~10min), frequent semantic queries
- Cache eviction on `POST /api/indexing/incremental` trigger
- Micrometer metrics: `esmp.mcp.request.duration` (timer by tool), `esmp.mcp.cache.hit.ratio` (gauge), `esmp.mcp.tool.invocations` (counter by tool)
- Structured request logging for every MCP request (requestId, tool, params, latency, payload size, truncation flag, warnings)
- Graceful degradation: partial context + `warnings[]` + `contextCompleteness` score (0.0-1.0)
- Performance SLOs: `get_migration_context` < 1.5s, `search_knowledge` < 500ms, `get_dependency_cone` < 300ms
- No OAuth2/RBAC — single-user localhost only
- `esmp.mcp.*` config prefix for all MCP configuration

### Claude's Discretion

- Exact Caffeine cache TTL values and eviction policies
- Internal method decomposition of MigrationContextAssembler
- Token estimation algorithm (character-based heuristic vs tiktoken-equivalent)
- Exact contextCompleteness scoring weights per service
- MCP tool description strings (what Claude sees in tool list)
- Error response format details

### Deferred Ideas (OUT OF SCOPE)

- Redis distributed cache
- OAuth2 / RBAC security
- Kubernetes deployment
- Git/CI metadata integration
- Temporal graph queries
- Behavioral diff integration
- Drift detection APIs
</user_constraints>

---

## Summary

Phase 14 adds an MCP server layer to the existing ESMP Spring Boot application, exposing all prior analysis capabilities (graph, vectors, risk, lexicon, validation) as structured tools that Claude Code can call during Vaadin 7 → Vaadin 24 migration work. The implementation is a protocol adapter: it does not add new analysis logic, only wraps existing services.

The critical technical decision is the transport: **SSE via `spring-ai-starter-mcp-server-webmvc`**. This is the correct choice for a Spring MVC + Vaadin application. The WebFlux variant causes `NoHandlerFoundException` because Spring Boot prioritizes the Servlet dispatcher over the reactive one. The WebMVC starter registers `/sse` and `/mcp/message` endpoints directly with the existing DispatcherServlet and has been confirmed to work alongside custom Spring MVC controllers (including Vaadin).

Caching with Caffeine is a first-class Spring Boot feature — no extra setup beyond a dependency and `@Cacheable` annotations. Micrometer is already on the classpath (Prometheus actuator configured). Observability requires only a `TimedAspect` bean and `MeterRegistry` injection, both auto-configured by Spring Boot 3.

**Primary recommendation:** Use `spring-ai-starter-mcp-server-webmvc` with the `@Tool` annotation pattern on a dedicated `McpToolService` component. Delegate all business logic to the existing 6 services. Keep MigrationContextAssembler as a separate `@Service` (not annotated with `@Tool`) and call it from the tool handler.

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-ai-starter-mcp-server-webmvc` | 1.1.2 (via spring-ai BOM) | MCP SSE server via Spring MVC | Coexists with Vaadin + REST; WebFlux variant breaks in MVC apps |
| `com.github.ben-manes.caffeine:caffeine` | Managed by Spring Boot BOM | In-process caching | Spring Boot auto-configures `CaffeineCacheManager`; already in Spring Boot BOM |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `io.micrometer:micrometer-core` | Managed by Spring Boot BOM | Metrics (already on classpath via actuator) | Always — Timer, Counter, Gauge for MCP observability |
| `org.springframework.boot:spring-boot-starter-actuator` | Already in project | Prometheus registry already configured | Already present — no new dep |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `spring-ai-starter-mcp-server-webmvc` | `spring-ai-starter-mcp-server-webflux` | WebFlux variant fails in MVC apps (confirmed issue #3499). NEVER use with Vaadin. |
| Caffeine `@Cacheable` | Manual `ConcurrentHashMap` | `@Cacheable` gives TTL, eviction, metrics; manual maps don't |
| `@Tool` annotation on tool class | `ToolCallbackProvider` bean | Both work; `@Tool` is cleaner for this many methods |

**Installation (add to `build.gradle.kts` dependencies):**
```bash
implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
implementation("com.github.ben-manes.caffeine:caffeine")
```

**Version verification:** Both are managed by existing BOMs (`spring-ai-bom` at 1.1.2 and `spring-boot-starter-parent` respectively). No explicit version needed.

---

## Architecture Patterns

### Recommended Package Structure
```
src/main/java/com/esmp/
├── mcp/
│   ├── application/
│   │   └── MigrationContextAssembler.java  # parallel async orchestrator
│   ├── api/
│   │   ├── MigrationContext.java           # response record
│   │   ├── MigrationContextSummary.java    # sub-record
│   │   ├── AssemblerWarning.java           # degradation warning record
│   │   └── (other response records)
│   ├── config/
│   │   ├── McpConfig.java                  # @ConfigurationProperties(prefix="esmp.mcp")
│   │   ├── McpCacheConfig.java             # CaffeineCacheManager bean + specs
│   │   └── McpObservabilityConfig.java     # TimedAspect bean, MeterRegistry wiring
│   ├── tool/
│   │   └── MigrationToolService.java       # @Component with all 6 @Tool methods
│   └── validation/
│       └── McpValidationQueryRegistry.java # (optional) MCP-specific health checks
```

### Pattern 1: Tool Registration with @Tool Annotation

**What:** Mark methods on a `@Component` class with `@Tool`. Spring AI's auto-configuration scans these and registers them with the MCP server automatically.

**When to use:** All 6 MCP tools. Each method = one tool.

```java
// Source: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
@Component
public class MigrationToolService {

    private final MigrationContextAssembler assembler;
    private final VectorSearchService vectorSearchService;
    // ... inject all needed services

    @Tool(description = """
        Retrieves comprehensive migration context for a Java class.
        Input: fully-qualified class name (e.g. com.example.PaymentService).
        Returns: dependency cone summary, relevant code chunks, business terms,
        risk analysis, and business rules. Use this as the primary context
        assembly tool before starting migration of any class.
        """)
    public MigrationContext getMigrationContext(String classFqn) {
        return assembler.assemble(classFqn);
    }
}
```

**Registration config (one bean registers all tools in the component):**
```java
// Source: https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/api/mcp/mcp-stdio-sse-server-boot-starter-docs.html
@Bean
public ToolCallbackProvider migrationTools(MigrationToolService toolService) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(toolService)
        .build();
}
```

### Pattern 2: MigrationContextAssembler — Parallel Async Assembly

**What:** Replication of `RagService`'s `CompletableFuture.supplyAsync` pattern, extended to coordinate 4 parallel services.

**When to use:** `get_migration_context` tool implementation. Must NOT be `@Transactional`.

```java
// Source: Established in RagService.java (com.esmp.rag.application)
@Service
public class MigrationContextAssembler {

    public MigrationContext assemble(String classFqn) {
        long startMs = System.currentTimeMillis();

        CompletableFuture<Map<String,Integer>> coneFuture =
            CompletableFuture.supplyAsync(() -> graphQueryService.getDependencyCone(classFqn));
        CompletableFuture<List<BusinessTermNode>> termsFuture =
            CompletableFuture.supplyAsync(() -> lexiconService.findByClass(classFqn));
        CompletableFuture<RiskDetailResponse> riskFuture =
            CompletableFuture.supplyAsync(() -> riskService.getClassDetail(classFqn));
        CompletableFuture<List<BusinessRuleResult>> rulesFuture =
            CompletableFuture.supplyAsync(() -> findBusinessRules(classFqn));

        // join all — gracefully handle individual failures
        // assemble in priority order, apply token budget truncation
    }
}
```

### Pattern 3: Caffeine Cache Configuration

**What:** Named caches with distinct TTLs via `CaffeineCacheManager`.

**When to use:** Cache expensive graph traversals and lexicon lookups.

```java
// Source: https://www.baeldung.com/spring-boot-caffeine-cache
@Configuration
@EnableCaching
public class McpCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(500)
                .recordStats());
        return manager;
    }
}
```

**Usage on service methods:**
```java
@Cacheable(value = "dependencyCones", key = "#classFqn")
public Map<String, Integer> getDependencyConeWithHops(String classFqn) { ... }
```

**Programmatic eviction (selective + full):**
```java
// Selective eviction for FQN-keyed caches:
cacheManager.getCache("dependencyCones").evict(classFqn);
// Full clear for query-keyed caches (domainTermsByClass uses #search + '_' + #criticality as key,
// so FQN-based eviction would silently no-op):
cacheManager.getCache("domainTermsByClass").clear();
cacheManager.getCache("semanticQueries").clear();
```

### Pattern 4: Micrometer Observability

**What:** Timer per tool invocation, counter per tool name, gauge for cache hit ratio. `TimedAspect` bean enables `@Timed` AOP.

**When to use:** Every MCP tool handler method.

```java
// Source: https://docs.micrometer.io/micrometer/reference/concepts/timers.html
@Bean
public TimedAspect timedAspect(MeterRegistry registry) {
    return new TimedAspect(registry);
}
```

```java
// On tool implementations:
@Timed(value = "esmp.mcp.request.duration", extraTags = {"tool", "getMigrationContext"})
public MigrationContext getMigrationContext(String classFqn) { ... }
```

For manual metric recording in the assembler (to capture truncation/warning data):
```java
Counter.builder("esmp.mcp.tool.invocations")
    .tag("tool", toolName)
    .register(meterRegistry)
    .increment();
```

### Pattern 5: SSE Endpoint Configuration

**What:** The MCP server auto-registers `/sse` and `/mcp/message` endpoints with the existing DispatcherServlet. Default port is 8080 (same as app).

**When to use:** Provide this YAML in `application.yml`.

```yaml
spring:
  ai:
    mcp:
      server:
        name: esmp-mcp
        version: 1.0.0
        type: SYNC
        sse-endpoint: /mcp/sse          # Claude Code connects here
        sse-message-endpoint: /mcp/message
        capabilities:
          tool: true
          resource: false
          prompt: false
```

**Claude Code connection command (project-scoped .mcp.json):**
```json
{
  "mcpServers": {
    "esmp": {
      "type": "sse",
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
```

Or via CLI:
```bash
claude mcp add --transport sse esmp http://localhost:8080/mcp/sse --scope project
```

### Pattern 6: Graceful Degradation

**What:** Wrap each parallel `CompletableFuture.join()` in try-catch. Accumulate warnings. Compute `contextCompleteness` from successful service count.

```java
List<String> warnings = new ArrayList<>();
double completeness = 0.0;

try {
    cone = coneFuture.join();
    completeness += 0.3;
} catch (Exception e) {
    warnings.add("Graph service unavailable: dependency cone omitted");
    log.warn("Cone traversal failed for {}: {}", classFqn, e.getMessage());
}
// ... pattern repeated for each service
```

### Anti-Patterns to Avoid

- **WebFlux starter with Vaadin:** Causes `NoHandlerFoundException` on `/sse`. Always use `spring-ai-starter-mcp-server-webmvc`.
- **@Transactional on MigrationContextAssembler:** Pure read orchestrator with parallel CompletableFutures — matches RagService/SchedulingService pattern. No @Transactional.
- **Blocking tool methods on virtual thread pool:** Spring Boot virtual threads are enabled (`spring.threads.virtual.enabled: true`). CompletableFuture.supplyAsync() uses ForkJoin pool by default. For virtual thread compatibility, pass a custom executor.
- **Lazy initialization conflict:** Fixed in Spring AI 1.1.1. Since project uses 1.1.2, `spring.main.lazy-initialization=true` is safe. But do not set it — ESMP does not use lazy init.
- **Using @McpTool instead of @Tool:** The `@McpTool` annotation is in Spring AI 1.1.0-M1+ (milestone). In stable 1.1.2, use `@Tool` from `org.springframework.ai.tool.annotation`. Confirmed: `@McpTool` beans not registering was a known issue in early milestones (issue #4392).
- **WebFlux lazy init conflict:** Issue #4055 (MCP not working with `spring-boot-starter-web` + `webflux` + lazy init). This project does NOT use WebFlux, so irrelevant.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| MCP protocol (SSE handshake, tool schema, JSON-RPC) | Custom SSE controller + protocol impl | `spring-ai-starter-mcp-server-webmvc` | Protocol has 15+ spec details including JSON schema generation, tool list refresh notifications, error envelopes |
| JSON schema for tool parameters | Manual schema string | Spring AI auto-generation from `@Tool` + `@ToolParam` | Spring AI generates schema from Java types and JavaDoc |
| Cache TTL management | Manual `ConcurrentHashMap` with timestamps | Caffeine + Spring `@Cacheable` | Caffeine uses Window TinyLFU eviction; handles concurrent access, stats, size limits |
| Micrometer timer wiring | Manual `System.currentTimeMillis()` diff | `@Timed` + `TimedAspect` | AOP-based, Prometheus-compatible, tag-based, percentile tracking |
| Token counting | Tiktoken Java port | Character heuristic: `text.length() / 4` (Claude average) | Tiktoken has no official Java library; character heuristic at 4 chars/token is sufficient for budget control |

**Key insight:** The MCP protocol layer is 90% boilerplate that the starter handles. Focus implementation effort on MigrationContextAssembler logic and graceful degradation — those are the novel parts.

---

## Common Pitfalls

### Pitfall 1: Wrong MCP Starter (WebFlux vs WebMVC)
**What goes wrong:** `/sse` returns 404; MCP endpoints never exposed.
**Why it happens:** `spring-ai-starter-mcp-server-webflux` registers reactive handlers; Spring Boot prioritizes `DispatcherServlet` over `DispatcherHandler` when both are present, so reactive endpoints are invisible.
**How to avoid:** Use `spring-ai-starter-mcp-server-webmvc` exclusively. Confirmed working in Spring MVC apps alongside Vaadin (GitHub issue #3499 resolved).
**Warning signs:** 404 on GET `/mcp/sse` after startup.

### Pitfall 2: @McpTool vs @Tool Annotation Confusion
**What goes wrong:** Tools not registered in MCP server; Spring AI logs show 0 tools discovered.
**Why it happens:** `@McpTool` was introduced in 1.1.0-M1 (milestone). In stable 1.1.2, the `@Tool` annotation from `org.springframework.ai.tool.annotation` is the stable API. `@McpTool` in some docs references 1.1.3+ features.
**How to avoid:** Use `@Tool(description="...")` from `spring-ai-core`. Confirm by checking which annotation is on classpath at build time.
**Warning signs:** Tools list empty when Claude Code connects (`/mcp` shows 0 tools from esmp server).

### Pitfall 3: SSE Transport Deprecation Warning
**What goes wrong:** Claude Code shows SSE deprecation warning; future Claude Code versions may drop SSE support.
**Why it happens:** MCP spec 2025-03-26 deprecated SSE in favor of Streamable HTTP. Claude Code CLI docs mark SSE as deprecated.
**How to avoid:** For Phase 14, SSE is acceptable (single user, localhost). The CONTEXT.md locked this decision. `spring-ai-starter-mcp-server-webmvc` also supports `protocol: STREAMABLE` — upgrade path exists without code changes.
**Warning signs:** Claude Code logs `"SSE transport is deprecated"` at connection time.

### Pitfall 4: Cache Eviction on Incremental Reindex Missing
**What goes wrong:** MCP returns stale dependency cones after source files are updated and re-indexed.
**Why it happens:** `IncrementalIndexingService` updates Neo4j and Qdrant but MCP's Caffeine cache is unaware.
**How to avoid:** `IncrementalIndexingService` must call `CacheManager.getCache("dependencyCones").evict(fqn)` for each re-indexed class, and clear semantic query cache on full reindex.
**Warning signs:** `get_migration_context` returns different results than `GET /api/rag/context` for same input after re-index.

### Pitfall 5: Token Budget Estimation Inaccuracy
**What goes wrong:** Context chunks not truncated when they should be, causing large MCP responses that exceed Claude's context window.
**Why it happens:** No official Java tiktoken. Character-based heuristics are imprecise.
**How to avoid:** Use `text.length() / 4` as a conservative estimate (Claude averages ~4 chars/token for mixed code/English). Apply a safety factor: truncate at 90% of max-tokens budget.
**Warning signs:** MCP responses over 8000 "tokens" estimated but not truncated.

### Pitfall 6: CompletableFuture + Virtual Threads Interaction
**What goes wrong:** CompletableFuture.supplyAsync() may not use virtual threads from Spring's pool.
**Why it happens:** `supplyAsync()` defaults to `ForkJoinPool.commonPool()`, which is not virtual threads.
**How to avoid:** Either (a) inject `Executor` bean backed by virtual threads, or (b) accept ForkJoin pool for parallel IO — Neo4j + Qdrant calls block on IO anyway, ForkJoin handles this fine.
**Warning signs:** SLO breach on `get_migration_context` > 1.5s if Neo4j or Qdrant is slow.

---

## Code Examples

### Tool Registration (Full Pattern)

```java
// Source: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
// In a @Configuration class:
@Bean
public ToolCallbackProvider migrationTools(MigrationToolService toolService) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(toolService)
        .build();
}
```

### application.yml MCP Section

```yaml
spring:
  ai:
    mcp:
      server:
        name: esmp-mcp
        version: 1.0.0
        type: SYNC
        sse-endpoint: /mcp/sse
        sse-message-endpoint: /mcp/message
        capabilities:
          tool: true
          resource: false
          prompt: false
          completion: false

esmp:
  mcp:
    context:
      max-tokens: 8000
    cache:
      dependency-cone-ttl-minutes: 5
      domain-terms-ttl-minutes: 10
      semantic-query-ttl-minutes: 3
      max-size: 500
```

### McpConfig ConfigurationProperties

```java
// Source: follows RagWeightConfig pattern (com.esmp.rag.config)
@Component
@ConfigurationProperties(prefix = "esmp.mcp")
public class McpConfig {
    private ContextConfig context = new ContextConfig();
    private CacheConfig cache = new CacheConfig();

    public static class ContextConfig {
        private int maxTokens = 8000;
        // getters/setters
    }

    public static class CacheConfig {
        private int dependencyConeTtlMinutes = 5;
        private int domainTermsTtlMinutes = 10;
        private int semanticQueryTtlMinutes = 3;
        private int maxSize = 500;
        // getters/setters
    }
    // nested getters
}
```

### Structured Request Logging Pattern

```java
// In MigrationToolService or via AOP:
log.info("MCP_REQUEST requestId={} tool={} params={} latencyMs={} payloadSize={} truncated={} completeness={} warnings={}",
    requestId, toolName, params, latencyMs, payloadSize, truncated, completeness, warnings);
```

### Claude Code .mcp.json (Project-Scoped)

```json
{
  "mcpServers": {
    "esmp": {
      "type": "sse",
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
```

Place at project root as `.mcp.json`. Checked into version control. Team members will be prompted to approve on first use.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| SSE transport (MCP spec pre-2025-03) | Streamable HTTP (MCP spec 2025-03-26) | March 2025 | SSE still works; HTTP is preferred for new servers. Phase 14 uses SSE per locked decision. Upgrade path: change `protocol: SSE` → `protocol: STREAMABLE` in YAML. |
| Manual `ToolCallbackProvider` wiring | `@Tool` annotation + `MethodToolCallbackProvider` | Spring AI 1.1.x | Annotation-driven; less boilerplate |
| Separate MCP process (STDIO) | Embedded in Spring Boot (SSE) | Spring AI 1.0.x | Single process; shares DB connections and Spring beans |

**Deprecated/outdated:**
- `@McpTool` (milestone annotation): Use `@Tool` from stable Spring AI 1.1.2. `@McpTool` is in early milestone snapshots; may move to stable in 1.1.3+.
- STDIO transport for this use case: Works but requires separate process startup; SSE embedded in app is correct for ESMP.

---

## Open Questions

1. **`@Tool` vs `@McpTool` in Spring AI 1.1.2**
   - What we know: `@Tool` is documented in Spring AI 1.1 stable; `@McpTool` appeared in 1.1.0-M1 and is mentioned in newer docs (1.1.3+). Issue #4392 shows `@McpTool` beans not registering in 1.1.0-M1.
   - What's unclear: Whether `@McpTool` is on the classpath in exactly 1.1.2 GA.
   - Recommendation: Use `@Tool` annotation from `org.springframework.ai.tool.annotation`. If compilation fails, fall back to `MethodToolCallbackProvider` + `@Bean` approach which is confirmed working.

2. **SSE endpoint path conflict with Vaadin**
   - What we know: Default paths are `/sse` and `/mcp/message`. CONTEXT.md specifies `/mcp/sse`. The `sse-endpoint` property is configurable.
   - What's unclear: Whether Vaadin's internal Push endpoint or any existing REST controller claims `/mcp/sse`.
   - Recommendation: Configure `sse-endpoint: /mcp/sse` explicitly (not default `/sse`). Verify at startup that the endpoint is reachable.

3. **Caffeine dependency already on classpath?**
   - What we know: Spring Boot BOM includes Caffeine as an optional dependency. It is not in `build.gradle.kts` currently.
   - Recommendation: Explicitly add `implementation("com.github.ben-manes.caffeine:caffeine")` to avoid BOM-optional version issues.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test 3.5.11 + Testcontainers 1.20.4 |
| Config file | None (auto-configured via `@SpringBootTest`) |
| Quick run command | `./gradlew test --tests "com.esmp.mcp.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| ID | Behavior | Test Type | Automated Command | File Exists? |
|----|----------|-----------|-------------------|-------------|
| MCP-01 | MCP server starts and exposes `/mcp/sse` | integration | `./gradlew test --tests "*.McpServerStartupTest"` | Wave 0 |
| MCP-02 | `get_migration_context` returns unified context for known FQN | integration | `./gradlew test --tests "*.MigrationContextAssemblerTest"` | Wave 0 |
| MCP-03 | `search_knowledge` delegates to VectorSearchService | integration | `./gradlew test --tests "*.MigrationToolServiceIntegrationTest"` | Wave 0 |
| MCP-04 | `get_dependency_cone` returns nodes+edges for FQN | integration | `./gradlew test --tests "*.MigrationToolServiceIntegrationTest"` | Wave 0 |
| MCP-05 | `validate_system_health` returns 41-query report | integration | `./gradlew test --tests "*.MigrationToolServiceIntegrationTest"` | Wave 0 |
| MCP-06 | Caffeine cache hit on second call to same FQN | integration | `./gradlew test --tests "*.MigrationToolServiceIntegrationTest"` | Wave 0 |
| MCP-07 | Cache evicted after `IncrementalIndexingService` reindex | integration | `./gradlew test --tests "*.MigrationToolServiceIntegrationTest"` | Wave 0 |
| MCP-08 | Graceful degradation: Neo4j down → partial context + warnings | unit | `./gradlew test --tests "*.MigrationContextAssemblerTest"` | Wave 0 |
| SLO-MCP-01 | `get_migration_context` < 1.5s with pilot fixtures | integration | `./gradlew test --tests "*.McpSloTest"` | Wave 0 |
| SLO-MCP-02 | `search_knowledge` < 500ms | integration | `./gradlew test --tests "*.McpSloTest"` | Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.mcp.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/mcp/application/MigrationContextAssemblerTest.java` — covers MCP-02, MCP-08
- [ ] `src/test/java/com/esmp/mcp/tool/MigrationToolServiceIntegrationTest.java` — covers MCP-03, MCP-04, MCP-05, MCP-06, MCP-07
- [ ] `src/test/java/com/esmp/mcp/config/McpServerStartupTest.java` — covers MCP-01
- [ ] `src/test/java/com/esmp/mcp/config/McpSloTest.java` — covers SLO-MCP-01, SLO-MCP-02

---

## Sources

### Primary (HIGH confidence)
- [Spring AI MCP Server Boot Starter docs](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) — starter artifacts, `@Tool` registration, YAML properties, SYNC/ASYNC types
- [Spring AI STDIO and SSE Server Starter docs (1.1-SNAPSHOT)](https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/api/mcp/mcp-stdio-sse-server-boot-starter-docs.html) — SSE endpoint defaults (`/sse`, `/mcp/message`), `ToolCallbackProvider`, YAML config table
- [Claude Code MCP documentation](https://code.claude.com/docs/en/mcp) — `.mcp.json` format, `claude mcp add --transport sse`, SSE deprecation note, MAX_MCP_OUTPUT_TOKENS
- [RagService.java](../../src/main/java/com/esmp/rag/application/RagService.java) — CompletableFuture parallel pattern, Neo4jClient query pattern (direct project source)
- [application.yml](../../src/main/resources/application.yml) — existing `esmp.*` config structure to match

### Secondary (MEDIUM confidence)
- [GitHub issue #3499 — WebFlux vs WebMVC for SSE](https://github.com/spring-projects/spring-ai/issues/3499) — confirmed WebMVC starter works alongside Spring MVC apps; WebFlux breaks in mixed setup
- [GitHub issue #4055 — lazy initialization fix](https://github.com/spring-projects/spring-ai/issues/4055) — fixed in Spring AI 1.1.1 (project uses 1.1.2, not affected)
- [Spring Boot Caffeine Cache — Baeldung](https://www.baeldung.com/spring-boot-caffeine-cache) — `CaffeineCacheManager`, `@Cacheable`, `@CacheEvict` patterns
- [Cache Eviction in Spring Boot — Baeldung](https://www.baeldung.com/spring-boot-evict-cache) — programmatic `cacheManager.getCache(...).evict(key)` and `.clear()`
- [Micrometer Timers reference](https://docs.micrometer.io/micrometer/reference/concepts/timers.html) — `@Timed`, `TimedAspect` setup

### Tertiary (LOW confidence)
- [GitHub issue #4392 — @McpTool not registering in 1.1.0-M1](https://github.com/spring-projects/spring-ai/issues/4392) — Informs `@Tool` vs `@McpTool` decision; milestone-specific issue

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — confirmed artifact IDs from official Spring AI docs, GitHub issues resolve known conflicts
- Architecture: HIGH — patterns directly derived from existing project code (RagService, RagWeightConfig) and official docs
- Pitfalls: HIGH — WebFlux/WebMVC confusion verified via GitHub issue with resolution; `@Tool` vs `@McpTool` verified via issue tracker
- Test architecture: MEDIUM — test file structure follows established project pattern; exact assertions TBD during implementation

**Research date:** 2026-03-19
**Valid until:** 2026-04-19 (Spring AI 1.1.x is stable; Caffeine and Micrometer APIs are stable)
