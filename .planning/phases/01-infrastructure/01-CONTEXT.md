# Phase 1: Infrastructure - Context

**Gathered:** 2026-03-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Docker Compose environment with Neo4j, Qdrant, PostgreSQL, Prometheus, Grafana, and a Spring Boot skeleton running on Java 21 with virtual threads. Developer can run the full ESMP environment locally with all data stores healthy and a Spring Boot service ready to accept ingestion requests. Flyway schema migrations applied to PostgreSQL. Spring Boot Actuator exposes health, info, and metrics endpoints.

</domain>

<decisions>
## Implementation Decisions

### Package & module structure
- Single Gradle module (not multi-module) — simpler for solo developer, can split later if needed
- Package-by-feature organization: `com.esmp.graph`, `com.esmp.extraction`, `com.esmp.vector`, `com.esmp.risk`, etc.
- Root package: `com.esmp`
- No pre-created `common` package — let shared code emerge organically when a second feature needs it

### Build system conventions
- Gradle Kotlin DSL (`build.gradle.kts`)
- Gradle version catalog (`libs.versions.toml`) for centralized dependency versions
- Minimal code quality plugins: Spotless for formatting only — add linting/analysis later when there's code to analyze
- JUnit 5 + Testcontainers for testing — real Neo4j, Qdrant, PostgreSQL in integration tests

### Docker service configuration
- Single `docker-compose.yml` file (no profiles or multiple files) — use Spring profiles for app config
- Named Docker volumes for Neo4j, Qdrant, PostgreSQL data persistence
- Spring Boot app runs OUTSIDE Docker (IDE/CLI with `./gradlew bootRun`) — only data stores + monitoring in Docker for faster dev loop
- Standard port mappings: Neo4j 7474/7687, Qdrant 6333/6334, PostgreSQL 5432, Prometheus 9090, Grafana 3000

### Observability baseline
- Spring Actuator health/info/metrics endpoints + Prometheus scraping — no Grafana dashboards yet (add when there's real data in Phase 2+)
- Logging: both formats via Spring profile — structured JSON in `prod` profile, plain text in `dev` profile
- Custom Spring Actuator health indicators for Neo4j, Qdrant, and PostgreSQL — startup fails fast if any store unreachable
- No distributed tracing in Phase 1 — single service, tracing adds value later

### Claude's Discretion
- Specific Neo4j, Qdrant, PostgreSQL Docker image versions (use latest stable)
- Gradle wrapper version
- Spotless formatting rules (Google Java Style or similar)
- Flyway initial migration content (schema for migration job state tables)
- Docker Compose resource limits
- Spring Boot application.yml structure and defaults
- Exact Testcontainers configuration

</decisions>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches. The project is greenfield with no existing code to integrate with.

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- None — greenfield project, no existing code

### Established Patterns
- None — Phase 1 establishes the patterns all subsequent phases will follow

### Integration Points
- This phase creates the foundation: all future phases plug into the package structure, build system, Docker environment, and health check patterns established here

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 01-infrastructure*
*Context gathered: 2026-03-04*
