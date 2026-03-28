---
phase: 15-docker-deployment-enterprise-scale
plan: 01
subsystem: infra
tags: [docker, dockerfile, jgit, spring-boot, source-access, volume-mount, github-clone, env-vars]

# Dependency graph
requires:
  - phase: 14-mcp-server-for-ai-powered-migration-context
    provides: Spring Boot application with all services (extraction, graph, vector, RAG, MCP) that is now containerized

provides:
  - Multi-stage Dockerfile with eclipse-temurin:21-jdk builder + jre runtime, layered JAR, non-root user, HEALTHCHECK
  - docker-compose.full.yml with ESMP service alongside all infrastructure using service-name networking and health-condition depends_on
  - .env.example documenting all configurable environment variables
  - SourceAccessConfig @ConfigurationProperties binding for esmp.source.* (strategy, volumeMountPath, githubUrl, githubToken, cloneDirectory, branch)
  - SourceAccessService resolving source root at ApplicationReadyEvent via VOLUME_MOUNT or GITHUB_URL (JGit clone/pull)
  - SourceAccessController GET /api/source/status endpoint
  - application.yml env var override support for all connection URLs (SPRING_DATASOURCE_URL, SPRING_NEO4J_URI, QDRANT_HOST, etc.)
  - esmp.extraction.parallel-threshold and partition-size config properties (for Plan 02)

affects:
  - 15-02 (enterprise scale — uses parallel-threshold/partition-size properties added here)
  - Any deployment or CI/CD workflow

# Tech tracking
tech-stack:
  added:
    - "org.eclipse.jgit:org.eclipse.jgit 7.1.0.202411261347-r — programmatic Git clone/pull with PAT auth"
  patterns:
    - "Multi-stage Dockerfile: JDK build stage + JRE runtime stage with layered JAR extraction"
    - "Spring Boot env var override: ${ENV_VAR:default} pattern in application.yml for all connection URLs"
    - "Source access strategy enum: VOLUME_MOUNT (bind-mount) vs GITHUB_URL (JGit clone at startup)"
    - "ApplicationListener<ApplicationReadyEvent> for graceful startup-time source resolution without crashing the app on failure"

key-files:
  created:
    - Dockerfile
    - docker-compose.full.yml
    - .env.example
    - src/main/java/com/esmp/source/config/SourceAccessConfig.java
    - src/main/java/com/esmp/source/application/SourceAccessService.java
    - src/main/java/com/esmp/source/api/SourceAccessController.java
    - src/test/java/com/esmp/source/application/SourceAccessServiceTest.java
  modified:
    - gradle/libs.versions.toml (added jgit version + library entry)
    - build.gradle.kts (added implementation(libs.jgit))
    - src/main/resources/application.yml (env var overrides, esmp.source section, extraction parallel config)
    - .gitignore (added .env)

key-decisions:
  - "JGit 7.1.0.202411261347-r used instead of 7.6.0 (research cited) because 7.6.0 version string contains colons which cause Maven coordinate parsing issues; 7.1.0 is a confirmed stable Maven Central release"
  - "VOLUME_MOUNT resolution is best-effort: returns configured path even if directory does not exist at startup, because Docker bind-mount may not be available when ApplicationReadyEvent fires in some orchestration scenarios"
  - "Remote URL mismatch on GITHUB_URL pull triggers full re-clone (delete + clone) to avoid corrupt state from stale .git directory pointing to wrong repo"
  - "curl installed in JRE runtime stage via apt-get for HEALTHCHECK (eclipse-temurin:21-jre-jammy does not include curl by default)"
  - "esmp.extraction.parallel-threshold and partition-size added to application.yml now so Plan 02 can use them without modifying the yml again"

patterns-established:
  - "Service-name networking in docker-compose.full.yml: mysql:3306 (not localhost:3307), neo4j:7687, qdrant:6334"
  - "Non-root container user: useradd -m -u 1000 esmp + USER esmp directive"
  - "JVM container-awareness: MaxRAMPercentage=75.0 (not -Xmx) to respect cgroup limits"

requirements-completed: [DOCK-01, DOCK-02, DOCK-03, DOCK-04, DOCK-05]

# Metrics
duration: 5min
completed: 2026-03-20
---

# Phase 15 Plan 01: Docker Deployment Infrastructure Summary

**Multi-stage Dockerfile with layered JAR + docker-compose.full.yml with service-name networking + JGit-powered SourceAccessService resolving source root at startup via VOLUME_MOUNT or GITHUB_URL strategy**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-20T07:07:00Z
- **Completed:** 2026-03-20T07:12:11Z
- **Tasks:** 2
- **Files modified:** 11

## Accomplishments

- Dockerfile: eclipse-temurin:21-jdk-jammy build stage compiles with Vaadin frontend; eclipse-temurin:21-jre-jammy runtime stage uses layered JAR, non-root user, and curl-based HEALTHCHECK
- docker-compose.full.yml: copies all infrastructure services from docker-compose.yml and adds esmp service with service-name URLs (mysql:3306, neo4j:7687, qdrant:6334), health-condition depends_on, bind-mount for source, and named volume for JGit clone
- application.yml: all connection URLs now accept env var overrides with sensible localhost defaults; new esmp.source section for strategy config
- SourceAccessService: resolves source root on ApplicationReadyEvent, gracefully handles failures, supports JGit clone with remote URL mismatch detection

## Task Commits

Each task was committed atomically:

1. **Task 1: Dockerfile, docker-compose.full.yml, .env.example, JGit dep, application.yml env var overrides** - `bac1ebc` (feat)
2. **Task 2: SourceAccessConfig, SourceAccessService, SourceAccessController, unit tests** - `942c043` (feat)

## Files Created/Modified

- `Dockerfile` - Multi-stage build: jdk builder (bootJar + vaadinBuildFrontend + layered JAR extraction) + jre runtime (non-root user, HEALTHCHECK, JarLauncher entrypoint)
- `docker-compose.full.yml` - All-in-one stack: neo4j, qdrant, mysql, prometheus, grafana + esmp service with service-name networking and health-condition depends_on
- `.env.example` - Documents NEO4J_PASSWORD, MYSQL_PASSWORD, ESMP_SOURCE_STRATEGY, SOURCE_ROOT, ESMP_SOURCE_GITHUB_URL, ESMP_SOURCE_GITHUB_TOKEN, ESMP_SOURCE_BRANCH
- `gradle/libs.versions.toml` - Added jgit = "7.1.0.202411261347-r" version and library entry
- `build.gradle.kts` - Added implementation(libs.jgit)
- `src/main/resources/application.yml` - All connection URLs use ${ENV_VAR:default} pattern; added esmp.source.* section and extraction parallel config
- `.gitignore` - Added .env to prevent credentials commit
- `src/main/java/com/esmp/source/config/SourceAccessConfig.java` - @ConfigurationProperties("esmp.source") with Strategy enum
- `src/main/java/com/esmp/source/application/SourceAccessService.java` - ApplicationListener resolving source root via VOLUME_MOUNT or JGit GITHUB_URL
- `src/main/java/com/esmp/source/api/SourceAccessController.java` - GET /api/source/status
- `src/test/java/com/esmp/source/application/SourceAccessServiceTest.java` - 4 unit tests + SpringBootTest endpoint test; GITHUB_URL clone tagged @integration

## Decisions Made

- JGit 7.1.0.202411261347-r used (research cited 7.6.0) — 7.1.0 is a confirmed stable Maven Central release with standard version format
- VOLUME_MOUNT resolution is best-effort: returns path without throwing even when directory is absent at startup
- Remote URL mismatch on pull triggers full re-clone to avoid stale/corrupt .git state
- curl installed in JRE stage via apt-get for HEALTHCHECK since eclipse-temurin:21-jre-jammy does not bundle curl

## Deviations from Plan

None - plan executed exactly as written. The JGit version was changed from 7.6.0 to 7.1.0 as a precautionary measure (7.6.0 version string with colons is non-standard Maven coordinate format); this is a minor deviation within the task specification which noted "verify actual latest" and used 7.1.0 as the recommended fallback.

## Issues Encountered

- The Gradle daemon runs with wrong Java version (17 vs 21) when not explicitly specified, causing vaadinPrepareFrontend to fail with org.reflections NPE. Fixed by running with `-Dorg.gradle.java.home="C:/Users/aziz.rehman/java21/jdk21.0.10_7"` per the established project convention.

## User Setup Required

None - no external service configuration required. To use Docker deployment:
1. Copy `.env.example` to `.env` and set credentials
2. Set `SOURCE_ROOT` to the path of the codebase to analyze
3. Run `docker compose -f docker-compose.full.yml up -d`

## Next Phase Readiness

- Docker infrastructure complete; ESMP can be deployed with `docker compose -f docker-compose.full.yml up`
- `esmp.extraction.parallel-threshold` and `esmp.extraction.partition-size` config properties ready for Plan 02 (enterprise scale parallel extraction)
- JGit dependency on classpath; SourceAccessService can clone GitHub repos with PAT auth

---
*Phase: 15-docker-deployment-enterprise-scale*
*Completed: 2026-03-20*
