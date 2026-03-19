# Phase 15: Docker Deployment & Enterprise Scale - Context

**Gathered:** 2026-03-19
**Status:** Ready for planning

<domain>
## Phase Boundary

Make ESMP deployable as a Docker image where the source codebase is supplied at runtime (via GitHub URL clone or volume mount), and ensure the full pipeline — extraction, graph building, vector indexing, RAG, MCP — performs acceptably on enterprise codebases of 4M+ LOC (~40K class files). This includes a Dockerfile, a production-ready all-in-one docker-compose.yml, runtime source access, parallel extraction, batched Neo4j persistence, and progress streaming.

</domain>

<decisions>
## Implementation Decisions

### Source Input Strategy
- Support both volume mount and JGit GitHub clone strategies, selectable via `esmp.source.strategy` enum config (VOLUME_MOUNT | GITHUB_URL)
- Volume mount: user bind-mounts source at `/mnt/source` (read-only)
- GitHub clone: JGit `Git.cloneRepository()` with HTTPS + PAT auth via `UsernamePasswordCredentialsProvider`
- Auth for private repos: PAT via `ESMP_SOURCE_GITHUB_TOKEN` environment variable only (no SSH key support)
- Clone directory persists across container restarts via a named Docker volume (avoids re-cloning large repos; `git pull` refreshes on startup)
- SourceAccessService resolves sourceRoot on application startup (ApplicationReadyEvent), not lazily on extraction trigger — app won't accept requests until source is validated/cloned

### Enterprise Scale (4M LOC / ~40K files)
- Target: 4M LOC / approximately 40,000 class files
- Parallel extraction: partition file list into batches processed by bounded ThreadPoolTaskExecutor using CompletableFuture
- Each partition gets its own ExtractionAccumulator instance — merged after all tasks complete (no shared mutable state)
- Batched Neo4j UNWIND MERGE: replace per-node Cypher loops with UNWIND parameter lists for Annotation/Package/Module/DBTable nodes (~2000 rows per batch)
- Both parallel extraction and batched UNWIND MERGE are in scope for this phase
- Parallel threshold and partition size configurable via `esmp.extraction.parallel-threshold` (default 500) and `esmp.extraction.partition-size` (default 200) application.yml properties

### Deployment Experience
- All-in-one docker-compose.yml: includes ESMP service alongside Neo4j, Qdrant, MySQL, Prometheus, Grafana
- Secrets managed via `.env` file (gitignored) with `.env.example` providing defaults — docker compose reads `.env` automatically
- Minimum recommended container memory: 4GB (75% MaxRAMPercentage = ~3GB heap)
- Dockerfile uses Vaadin production build (`bootJar` + `vaadinBuildFrontend` in build stage)
- Multi-stage Dockerfile: eclipse-temurin:21-jdk-jammy (build) → eclipse-temurin:21-jre-jammy (runtime)
- Layered JAR extraction for Docker cache efficiency
- Non-root user (`esmp`, uid 1000)

### Progress Visibility
- SSE streaming via Spring MVC SseEmitter on `/api/extraction/progress?jobId=X`
- Push events with (phase, filesProcessed, totalFiles) during extraction
- SSE timeout: 60 minutes (covers worst-case 4M LOC extraction)
- Extraction trigger endpoint (`POST /api/extraction/trigger`) returns 202 + jobId immediately (async model)
- Client connects to SSE endpoint for real-time progress
- No dashboard integration for extraction status in this phase — SSE is API-only

### Claude's Discretion
- Exact JVM flags beyond MaxRAMPercentage (GC tuning, heap dump path)
- ExtractionAccumulator.merge() implementation details
- SseEmitter event format and error handling
- HEALTHCHECK command and timing in Dockerfile
- Node.js installation strategy for Vaadin frontend build in Docker

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Existing Infrastructure
- `docker-compose.yml` — Current infrastructure services (Neo4j, Qdrant, MySQL, Prometheus, Grafana) with healthchecks and volume definitions
- `src/main/resources/application.yml` — All current Spring Boot config including hardcoded localhost URLs that must be overridable via env vars
- `build.gradle.kts` — Current dependencies, Vaadin plugin config, Gradle toolchain settings

### Extraction Pipeline
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` — Current sequential extraction loop that needs parallel path
- `src/main/java/com/esmp/extraction/visitor/ExtractionAccumulator.java` — HashMap-based accumulator that needs merge() method for parallel support
- `src/main/java/com/esmp/extraction/config/ExtractionConfig.java` — Existing source-root and classpath-file config properties

### Persistence Layer
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` — Current per-node Neo4j persistence that needs batched UNWIND MERGE
- `src/main/java/com/esmp/indexing/application/IncrementalIndexingService.java` — Incremental indexing also uses per-node writes

### Research
- `.planning/phases/15-docker-deployment-enterprise-scale/15-RESEARCH.md` — Complete technical research with Docker patterns, JGit cookbook, batched Cypher, parallel extraction patterns, and pitfall analysis

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ExtractionConfig` (`@ConfigurationProperties(prefix="esmp.extraction")`): Already has sourceRoot and classpathFile — extend for parallel config
- `docker-compose.yml`: All infrastructure services already defined with healthchecks — extend to add ESMP service
- `management.endpoints.web.exposure` in application.yml: Actuator health probes already enabled — use for Docker HEALTHCHECK
- `spring.threads.virtual.enabled: true`: Virtual threads already on — ThreadPoolTaskExecutor should complement, not replace

### Established Patterns
- `@ConfigurationProperties` with `@Component`: Used for RiskWeightConfig, RagWeightConfig, SchedulingWeightConfig, McpConfig — follow same pattern for SourceAccessConfig and extraction parallel config
- Neo4jClient raw Cypher: Used extensively for MERGE operations in ExtractionService, LinkingService — follow same pattern for UNWIND MERGE
- Testcontainers integration tests: Established pattern across all phases — use for SourceAccessService and parallel extraction tests

### Integration Points
- `ExtractionService.extract()`: Main extraction entry point — needs parallel path added
- `ExtractionAccumulator`: Needs `merge(ExtractionAccumulator other)` method
- `application.yml`: Needs env var overrides for all localhost URLs (SPRING_DATASOURCE_URL, SPRING_NEO4J_URI, QDRANT_HOST)
- `docker-compose.yml`: Add ESMP service with depends_on healthcheck conditions

</code_context>

<specifics>
## Specific Ideas

- SourceAccessService resolves on startup so the app is guaranteed ready when users hit API endpoints
- Named Docker volume for clone directory to avoid re-cloning large enterprise repos on container restart
- 4GB minimum memory recommendation — user prefers tighter allocation over generous headroom
- All-in-one compose preferred over split files — keep it simple, one `docker compose up` for everything

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 15-docker-deployment-enterprise-scale*
*Context gathered: 2026-03-19*
