---
phase: 01-infrastructure
plan: 01
subsystem: infra
tags: [spring-boot, gradle, docker-compose, neo4j, qdrant, mysql, flyway, actuator, virtual-threads]

requires: []
provides:
  - Gradle 9.3.1 build system with Spring Boot 3.5.11 and Java 21 toolchain
  - Docker Compose environment with Neo4j, Qdrant, MySQL, Prometheus, Grafana and health checks
  - Spring Boot skeleton with QdrantClient bean, custom QdrantHealthIndicator, DataStoreStartupValidator
  - Flyway migration V1 creating migration_job and migration_audit tables in MySQL
  - Spring Actuator endpoints: health, info, metrics, prometheus with virtual threads enabled
affects:
  - all subsequent phases (build system, package structure, data store connections all established here)

tech-stack:
  added:
    - Spring Boot 3.5.11 (web, actuator, data-neo4j, data-jpa, test, testcontainers)
    - Gradle 9.3.1 with Kotlin DSL and version catalog
    - io.qdrant:client 1.13.0 with io.grpc:grpc-stub 1.65.1 (required for ListenableFuture on classpath)
    - Flyway (via Spring Boot BOM)
    - Micrometer Prometheus registry
    - Spotless 7.0.2 with google-java-format 1.23.0
    - Testcontainers 1.20.4 (junit-jupiter, neo4j, mysql)
    - Docker: neo4j:2026.01.4, qdrant/qdrant:latest, mysql:8.4, prom/prometheus:latest, grafana/grafana:latest
  patterns:
    - Package-by-feature under com.esmp root
    - Gradle version catalog (libs.versions.toml) for all dependency versions
    - Spring profile split: application-dev.yml (plain logs) and application-prod.yml (ECS JSON)
    - Custom HealthIndicator for Qdrant (no Spring Boot built-in)
    - ApplicationRunner fail-fast startup validation iterating all HealthIndicator beans
    - Flyway as sole schema manager (spring.jpa.hibernate.ddl-auto=none)

key-files:
  created:
    - build.gradle.kts
    - settings.gradle.kts
    - gradle/libs.versions.toml
    - gradle/wrapper/gradle-wrapper.properties
    - gradlew / gradlew.bat
    - gradle.properties
    - docker-compose.yml
    - prometheus.yml
    - .env
    - .gitignore
    - src/main/java/com/esmp/EsmpApplication.java
    - src/main/java/com/esmp/infrastructure/config/QdrantConfig.java
    - src/main/java/com/esmp/infrastructure/health/QdrantHealthIndicator.java
    - src/main/java/com/esmp/infrastructure/startup/DataStoreStartupValidator.java
    - src/main/resources/application.yml
    - src/main/resources/application-dev.yml
    - src/main/resources/application-prod.yml
    - src/main/resources/db/migration/V1__initial_schema.sql
  modified: []

key-decisions:
  - "Qdrant Java client artifact is io.qdrant:client (not io.qdrant:java-client as in RESEARCH.md)"
  - "grpc-stub must be added as explicit implementation dep to put ListenableFuture on compile classpath"
  - "Java 21 downloaded via Amazon Corretto zip to ~/java21 and pointed to via org.gradle.java.installations.paths"
  - "foojay-resolver plugin skipped due to IBM_SEMERU enum incompatibility with Gradle 9.3.1"
  - "Qdrant docker-compose health check uses bash /dev/tcp workaround (no curl/wget in image)"
  - "migration_audit FK column is job_id (correcting RESEARCH.md typo of migration_job_id)"

patterns-established:
  - "Pattern: All dependency versions in gradle/libs.versions.toml; reference with libs.* in build.gradle.kts"
  - "Pattern: Custom HealthIndicator beans auto-discovered by DataStoreStartupValidator via List<HealthIndicator> injection"
  - "Pattern: ApplicationRunner.run() throws IllegalStateException on DOWN health status for fail-fast startup"
  - "Pattern: Spring profiles for logging — dev gets plain text, prod gets ECS JSON via logging.structured.format.console"

requirements-completed: [INFRA-01, INFRA-02, INFRA-03]

duration: 9min
completed: 2026-03-04
---

# Phase 1 Plan 1: Infrastructure Foundation Summary

**Spring Boot 3.5.11 skeleton with Gradle 9.3.1/Kotlin DSL, Docker Compose for Neo4j/Qdrant/MySQL/Prometheus/Grafana, custom QdrantHealthIndicator, fail-fast ApplicationRunner startup validator, and Flyway migration V1**

## Performance

- **Duration:** 9 min
- **Started:** 2026-03-04T13:12:07Z
- **Completed:** 2026-03-04T13:22:01Z
- **Tasks:** 2
- **Files modified:** 18

## Accomplishments

- Gradle 9.3.1 project with Spring Boot 3.5.11, Java 21 toolchain, version catalog, Spotless formatting compiles cleanly
- Docker Compose defines all 5 services (Neo4j, Qdrant, MySQL, Prometheus, Grafana) with proper health checks including Qdrant /dev/tcp workaround
- Spring Boot skeleton with custom QdrantHealthIndicator and DataStoreStartupValidator that fails fast if any data store is DOWN at startup
- Flyway migration V1 creates migration_job and migration_audit tables; JPA ddl-auto=none prevents schema conflicts

## Task Commits

Each task was committed atomically:

1. **Task 1: Gradle project skeleton and Docker Compose environment** - `d585482` (feat)
2. **Task 2: Spring Boot application with health indicators and configuration** - `074b71c` (feat)

## Files Created/Modified

- `build.gradle.kts` - Spring Boot 3.5.11 Gradle build with all dependencies and Spotless config
- `gradle/libs.versions.toml` - Centralized version catalog for all dependencies and plugins
- `settings.gradle.kts` - Project name declaration
- `gradlew` / `gradlew.bat` - Gradle 9.3.1 wrapper scripts
- `gradle/wrapper/gradle-wrapper.jar` - Gradle wrapper bootstrap JAR
- `gradle.properties` - JVM args and Java 21 toolchain path
- `docker-compose.yml` - Neo4j 2026.01.4, Qdrant, MySQL 8.4, Prometheus, Grafana with health checks
- `prometheus.yml` - Scrape config targeting host.docker.internal:8080/actuator/prometheus
- `.env` - Local dev defaults (no real secrets)
- `.gitignore` - Standard Gradle/IDE/Java exclusions
- `src/main/java/com/esmp/EsmpApplication.java` - @SpringBootApplication entry point
- `src/main/java/com/esmp/infrastructure/config/QdrantConfig.java` - QdrantClient bean via QdrantGrpcClient.newBuilder
- `src/main/java/com/esmp/infrastructure/health/QdrantHealthIndicator.java` - Custom HealthIndicator with 3s timeout
- `src/main/java/com/esmp/infrastructure/startup/DataStoreStartupValidator.java` - ApplicationRunner fail-fast validator
- `src/main/resources/application.yml` - Virtual threads, datasources, Flyway, Actuator exposure
- `src/main/resources/application-dev.yml` - DEBUG logging for com.esmp
- `src/main/resources/application-prod.yml` - ECS structured JSON logging
- `src/main/resources/db/migration/V1__initial_schema.sql` - migration_job and migration_audit tables

## Decisions Made

- Qdrant Java client artifact ID is `io.qdrant:client` not `io.qdrant:java-client` — RESEARCH.md had wrong artifact name
- `grpc-stub` added as explicit `implementation` dependency to make `ListenableFuture` available on compile classpath (Qdrant client's healthCheckAsync returns ListenableFuture but grpc-stub is runtime-only in the Qdrant POM)
- foojay-resolver Gradle plugin skipped entirely due to `IBM_SEMERU` NoSuchFieldError with Gradle 9.3.1 — used `org.gradle.java.installations.paths` in gradle.properties pointing to a locally downloaded Amazon Corretto 21 instead
- Java 21 (Amazon Corretto 21.0.10) downloaded from AWS and extracted to `~/java21` to provide the required toolchain

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed wrong Qdrant Maven artifact name**
- **Found during:** Task 1 (Gradle project skeleton)
- **Issue:** RESEARCH.md specified `io.qdrant:java-client` but the actual Maven Central artifact is `io.qdrant:client` — dependency resolution failed with "could not find io.qdrant:java-client:1.13.0"
- **Fix:** Updated `gradle/libs.versions.toml` to use `module = "io.qdrant:client"`
- **Files modified:** `gradle/libs.versions.toml`
- **Verification:** Gradle dependency resolution succeeded after fix
- **Committed in:** d585482 (Task 1 commit)

**2. [Rule 3 - Blocking] Added grpc-stub to expose ListenableFuture on compile classpath**
- **Found during:** Task 2 (Spring Boot application)
- **Issue:** `QdrantHealthIndicator.health()` calls `healthCheckAsync().get(3, TimeUnit.SECONDS)` but `ListenableFuture` (from Guava via grpc-stub) is not on the compile classpath — Qdrant client marks grpc-stub as runtime-only
- **Fix:** Added `io.grpc:grpc-stub:1.65.1` as explicit `implementation` dependency in `build.gradle.kts` and `libs.versions.toml`
- **Files modified:** `build.gradle.kts`, `gradle/libs.versions.toml`
- **Verification:** `./gradlew compileJava` succeeds
- **Committed in:** d585482 (Task 1 commit)

**3. [Rule 3 - Blocking] Downloaded Java 21 and bypassed foojay plugin**
- **Found during:** Task 2 (Spring Boot application — first compilation attempt)
- **Issue:** `org.gradle.toolchains.foojay-resolver-convention` versions 0.8.0 and 0.9.0 both fail with `java.lang.NoSuchFieldError: IBM_SEMERU` on Gradle 9.3.1 — no compatible foojay version exists for this Gradle release
- **Fix:** Removed foojay plugin from `settings.gradle.kts`, downloaded Amazon Corretto 21 zip, extracted to `~/java21`, set `org.gradle.java.installations.paths` in `gradle.properties` to point to the extracted JDK
- **Files modified:** `settings.gradle.kts`, `gradle.properties`
- **Verification:** `./gradlew compileJava` resolves Java 21 toolchain and compiles successfully
- **Committed in:** d585482 (Task 1 commit)

**4. [Rule 1 - Bug] Fixed FK column name in V1__initial_schema.sql**
- **Found during:** Task 2 (Flyway migration)
- **Issue:** RESEARCH.md Pattern 4 had `FOREIGN KEY (migration_job_id)` but the column is defined as `job_id` — would cause Flyway migration failure at runtime
- **Fix:** Changed FK reference to `FOREIGN KEY (job_id)` matching the column definition
- **Files modified:** `src/main/resources/db/migration/V1__initial_schema.sql`
- **Verification:** SQL is syntactically correct with matching column name
- **Committed in:** 074b71c (Task 2 commit)

---

**Total deviations:** 4 auto-fixed (2 blocking artifact/dependency, 1 blocking toolchain, 1 bug in SQL)
**Impact on plan:** All auto-fixes were required for the project to compile. No scope creep — every fix was directly caused by the current tasks.

## Issues Encountered

- Java 21 not pre-installed on the development machine (only Java 17 via Corretto). Downloaded Corretto 21 manually and configured Gradle toolchain path — took ~2 minutes additional setup but unblocked compilation.
- foojay-resolver plugin incompatible with Gradle 9.3.1 due to `IBM_SEMERU` enum removal. Bypassed by using `org.gradle.java.installations.paths` instead.

## User Setup Required

None — no external service configuration required beyond Docker Desktop being installed to run `docker compose up`.

Developer setup steps:
1. Run `docker compose up -d` in project root to start all data stores
2. Run `./gradlew bootRun --args='--spring.profiles.active=dev'` to start the application

## Next Phase Readiness

- Build system fully operational — `./gradlew compileJava` succeeds on Java 21
- Spring Boot skeleton ready to accept new features in any package under `com.esmp`
- Docker Compose environment ready for integration testing
- Health check pattern established — new services should add a custom HealthIndicator bean
- Fail-fast validator automatically includes any new HealthIndicator beans without code changes
- Blocker from STATE.md still applies: OpenRewrite Vaadin 7 recipe coverage needs hands-on audit in Phase 2

---
*Phase: 01-infrastructure*
*Completed: 2026-03-04*
