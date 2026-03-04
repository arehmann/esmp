---
phase: 01-infrastructure
plan: 02
subsystem: infra
tags: [testcontainers, integration-testing, health-indicators, virtual-threads, qdrant, neo4j, mysql, junit5, mockito]

requires:
  - phase: 01-infrastructure-01
    provides: QdrantHealthIndicator, DataStoreStartupValidator, QdrantConfig, application.yml, docker-compose.yml
provides:
  - Integration tests proving all three health indicators (Neo4j, MySQL, Qdrant) report UP with real Testcontainers
  - Unit test proving DataStoreStartupValidator fail-fast behavior (throws IllegalStateException on DOWN)
  - VirtualThreadsTest proving spring.threads.virtual.enabled=true is active at startup
  - Flyway migration proof via JDBC query on migration_job table existence
  - Fixed QdrantConfig to use plaintext gRPC (useTls=false) - TLS caused UNAVAILABLE errors with local Qdrant
  - Fixed qdrant.port from 6333 (REST) to 6334 (gRPC) in application.yml
affects:
  - all subsequent phases relying on the test infrastructure pattern

tech-stack:
  added: []
  patterns:
    - Testcontainers with @DynamicPropertySource for Qdrant (no @ServiceConnection support)
    - Neo4j and MySQL containers use @DynamicPropertySource with explicit property overrides
    - DataStoreStartupValidatorTest as plain JUnit 5 unit test (no Spring context) for fail-fast logic
    - GenericContainer with waitingFor(Wait.forHttp("/healthz").forPort(6333)) for Qdrant readiness

key-files:
  created:
    - src/test/java/com/esmp/infrastructure/health/HealthIndicatorIntegrationTest.java
    - src/test/java/com/esmp/infrastructure/health/VirtualThreadsTest.java
    - src/test/java/com/esmp/infrastructure/startup/DataStoreStartupValidatorTest.java
  modified:
    - src/main/java/com/esmp/infrastructure/config/QdrantConfig.java
    - src/main/resources/application.yml
    - docker-compose.yml

key-decisions:
  - "Qdrant gRPC port is 6334 (not 6333) - 6333 is REST, 6334 is gRPC; application.yml corrected"
  - "QdrantGrpcClient must use useTls=false for local/Docker Qdrant - default TLS causes UNAVAILABLE gRPC error"
  - "VirtualThreads test uses @Value injection of spring.threads.virtual.enabled property (simpler than thread inspection)"
  - "DataStoreStartupValidatorTest uses named inner class NamedDownIndicator so getSimpleName() returns meaningful identifier"
  - "MySQL host port changed to 3307 to avoid conflict with local MySQL instance on developer machine"
  - "docker-compose.yml name: esmp added to group all containers under the ESMP project name"

patterns-established:
  - "Pattern: Use @DynamicPropertySource for containers without @ServiceConnection (e.g., Qdrant)"
  - "Pattern: Qdrant container exposes both 6333 (REST, for wait strategy) and 6334 (gRPC, for QdrantClient)"
  - "Pattern: Unit test DataStoreStartupValidator with lambda HealthIndicators and named inner classes for error message assertions"
  - "Pattern: Offset host ports when local dev tools conflict with Docker Compose (e.g., MySQL on 3307 not 3306)"

requirements-completed: [INFRA-01, INFRA-02, INFRA-03]

duration: ~45min (including human Docker verification)
completed: 2026-03-04
---

# Phase 1 Plan 2: Integration Tests Summary

**Testcontainers integration tests proving Neo4j/MySQL/Qdrant health indicators all UP, virtual threads enabled, Flyway migration applied, and fail-fast DataStoreStartupValidator unit-tested — plus human-verified Docker Compose end-to-end and MySQL port conflict fix**

## Performance

- **Duration:** ~45 min (including human Docker verification)
- **Started:** 2026-03-04T13:24:25Z
- **Completed:** 2026-03-04T14:30:00Z
- **Tasks:** 2 of 2 completed
- **Files modified:** 6

## Accomplishments

- Created `HealthIndicatorIntegrationTest` with 5 passing tests: actuator health returns 200 UP, Neo4j component UP, MySQL (db) component UP, Qdrant component UP, Flyway migration_job table exists
- Created `VirtualThreadsTest` with 3 passing tests: context loads, virtual threads property is true, actuator health returns 200
- Created `DataStoreStartupValidatorTest` with 3 passing unit tests: happy path (all UP), failure path (IllegalStateException when DOWN), exception message contains component name
- `./gradlew build` passes: 11 tests, 0 failures
- Human verified full Docker Compose environment: all 5 services healthy, Spring Boot starts connected to all stores, /actuator/health and /actuator/prometheus respond correctly, fail-fast aborts when MySQL stopped
- Resolved MySQL port conflict (3306 -> 3307) so Docker can start alongside local dev MySQL

## Task Commits

Each task was committed atomically:

1. **Task 1: Create integration tests for health indicators, virtual threads, and fail-fast startup** - `80670db` (test)
2. **Task 2: Verify full Docker Compose environment and Spring Boot startup** - `a96657f` (chore)

## Files Created/Modified

- `src/test/java/com/esmp/infrastructure/health/HealthIndicatorIntegrationTest.java` - @SpringBootTest integration test with 5 assertions against real containers
- `src/test/java/com/esmp/infrastructure/health/VirtualThreadsTest.java` - Tests context load, virtual threads property, health endpoint smoke test
- `src/test/java/com/esmp/infrastructure/startup/DataStoreStartupValidatorTest.java` - Unit test: UP passes, DOWN throws, message has component name
- `src/main/java/com/esmp/infrastructure/config/QdrantConfig.java` - Fixed: added `useTls=false` to QdrantGrpcClient.newBuilder call
- `src/main/resources/application.yml` - Fixed: qdrant.port changed from 6333 (REST) to 6334 (gRPC); datasource URL updated to port 3307
- `docker-compose.yml` - Added `name: esmp` project grouping; MySQL host port changed 3306 -> 3307 to avoid local dev conflict

## Decisions Made

- `QdrantGrpcClient.newBuilder(host, port, false)` — the `false` disables TLS; Qdrant Docker image uses plaintext gRPC (no TLS), the previous default caused `UNAVAILABLE: io exception` with TLS handshake error
- `qdrant.port: 6334` — Qdrant splits REST (6333) from gRPC (6334); both must be exposed in Testcontainers, REST port used for wait strategy, gRPC port used for QdrantClient connection
- MySQL host port 3307 is a permanent project convention to avoid conflict with local dev MySQL on 3306; all developers on this project must use port 3307 to connect to Docker MySQL

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed QdrantGrpcClient connecting with TLS to plain-gRPC Qdrant**
- **Found during:** Task 1 (running integration tests)
- **Issue:** `QdrantGrpcClient.newBuilder(host, port)` defaults to TLS; Qdrant Docker container uses plaintext gRPC — caused `UNAVAILABLE: io exception` with `SslHandler` in channel pipeline
- **Fix:** Changed to `QdrantGrpcClient.newBuilder(host, port, false)` (useTls=false)
- **Files modified:** `src/main/java/com/esmp/infrastructure/config/QdrantConfig.java`
- **Verification:** Integration tests pass after fix
- **Committed in:** 80670db (Task 1 commit)

**2. [Rule 1 - Bug] Fixed qdrant.port using REST port 6333 instead of gRPC port 6334**
- **Found during:** Task 1 (running integration tests, after TLS fix)
- **Issue:** `application.yml` had `qdrant.port: 6333` (REST endpoint) but `QdrantClient` uses gRPC which runs on port 6334 — caused `INTERNAL: http2 exception` when gRPC client connected to REST/HTTP1.1 endpoint
- **Fix:** Changed `qdrant.port` to 6334; updated both test `@DynamicPropertySource` registrations to map port 6334
- **Files modified:** `src/main/resources/application.yml`, both integration test files
- **Verification:** Integration tests pass after fix; `./gradlew build BUILD SUCCESSFUL`
- **Committed in:** 80670db (Task 1 commit)

---

**3. [Rule 1 - Bug] Fixed MySQL port conflict between Docker and local MySQL**
- **Found during:** Task 2 (Docker Compose human verification)
- **Issue:** docker-compose.yml mapped MySQL to host port 3306 but developer machine had local MySQL already bound to 3306 — Docker Compose failed to start MySQL container
- **Fix:** Changed MySQL port mapping to 3307:3306 in docker-compose.yml; updated application.yml datasource URL to jdbc:mysql://localhost:3307/esmp; added `name: esmp` to group containers
- **Files modified:** `docker-compose.yml`, `src/main/resources/application.yml`
- **Verification:** Human confirmed docker compose up starts MySQL successfully; Spring Boot connects to all stores and /actuator/health returns UP
- **Committed in:** a96657f (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (2 bugs in Qdrant configuration, 1 Docker port conflict)
**Impact on plan:** All three fixes required for the environment to work end-to-end. The Qdrant fixes enabled integration tests to pass; the MySQL port fix enabled Docker Compose to start alongside local dev tools.

## Issues Encountered

- Docker Desktop was not running initially — started it to allow Testcontainers to pull and run containers
- TLS error and then HTTP/2 protocol error both traced back to the same root cause: Qdrant gRPC port misconfiguration carried over from Plan 01
- MySQL port 3306 conflict with local dev MySQL required Docker port mapping change to 3307 — discovered during human verification of Docker Compose

## User Setup Required

None — Docker Desktop must be running to execute integration tests (`./gradlew test`).

Developer setup (after these changes):
1. `docker compose up -d` from project root (MySQL now accessible on host port 3307)
2. `./gradlew bootRun --args='--spring.profiles.active=dev'`
3. Health check: http://localhost:8080/actuator/health (expect all components UP)
4. Prometheus metrics: http://localhost:8080/actuator/prometheus
5. Grafana: http://localhost:3000 (admin / admin)

## Next Phase Readiness

- All 11 integration and unit tests pass with `./gradlew build BUILD SUCCESSFUL`
- Docker Compose environment human-verified end-to-end: all 5 services start healthy, Spring Boot connects to all stores, fail-fast validation aborts startup when a store is stopped
- Test infrastructure established: Testcontainers pattern with AbstractIntegrationTest documented for adding new containers in future phases
- Phase 1 is complete — all success criteria met
- Phase 2 (OpenRewrite audit) can begin with full confidence in the infrastructure foundation

---
*Phase: 01-infrastructure*
*Completed: 2026-03-04*
