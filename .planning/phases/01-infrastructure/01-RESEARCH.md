# Phase 1: Infrastructure - Research

**Researched:** 2026-03-04
**Domain:** Spring Boot 3.5 / Java 21 / Docker Compose / Gradle Kotlin DSL / Flyway / Testcontainers
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Package & module structure**
- Single Gradle module (not multi-module) — simpler for solo developer, can split later if needed
- Package-by-feature organization: `com.esmp.graph`, `com.esmp.extraction`, `com.esmp.vector`, `com.esmp.risk`, etc.
- Root package: `com.esmp`
- No pre-created `common` package — let shared code emerge organically when a second feature needs it

**Build system conventions**
- Gradle Kotlin DSL (`build.gradle.kts`)
- Gradle version catalog (`libs.versions.toml`) for centralized dependency versions
- Minimal code quality plugins: Spotless for formatting only — add linting/analysis later when there's code to analyze
- JUnit 5 + Testcontainers for testing — real Neo4j, Qdrant, MySQL in integration tests

**Docker service configuration**
- Single `docker-compose.yml` file (no profiles or multiple files) — use Spring profiles for app config
- Named Docker volumes for Neo4j, Qdrant, MySQL data persistence
- Spring Boot app runs OUTSIDE Docker (IDE/CLI with `./gradlew bootRun`) — only data stores + monitoring in Docker for faster dev loop
- Standard port mappings: Neo4j 7474/7687, Qdrant 6333/6334, MySQL 3306, Prometheus 9090, Grafana 3000

**Observability baseline**
- Spring Actuator health/info/metrics endpoints + Prometheus scraping — no Grafana dashboards yet
- Logging: both formats via Spring profile — structured JSON in `prod` profile, plain text in `dev` profile
- Custom Spring Actuator health indicators for Neo4j, Qdrant, and MySQL — startup fails fast if any store unreachable
- No distributed tracing in Phase 1 — single service, tracing adds value later

### Claude's Discretion

- Specific Neo4j, Qdrant, MySQL Docker image versions (use latest stable)
- Gradle wrapper version
- Spotless formatting rules (Google Java Style or similar)
- Flyway initial migration content (schema for migration job state tables)
- Docker Compose resource limits
- Spring Boot application.yml structure and defaults
- Exact Testcontainers configuration

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| INFRA-01 | Docker Compose setup with Neo4j, Qdrant, Spring Boot services, Prometheus, Grafana | Docker image versions, health check patterns, named volumes, port mappings all researched |
| INFRA-02 | Spring Boot 3.5 with Java 21 and virtual threads | `spring.threads.virtual.enabled=true` single-property enablement confirmed on Spring Boot 3.2+; 3.5.11 is latest stable |
| INFRA-03 | Professional-grade project structure following Spring Boot best practices | Gradle 9 + Kotlin DSL + version catalog pattern researched; package-by-feature confirmed as standard |
</phase_requirements>

---

## Summary

Spring Boot 3.5.11 (released February 2026) is the current stable version on the 3.5 train and is the correct target. Virtual threads are enabled with a single property (`spring.threads.virtual.enabled=true`) — no Java configuration class is required. The framework automatically wires Tomcat, `@Async`, and the application task executor to virtual thread pools. Spring Data Neo4j is bundled via the Spring Boot BOM (no explicit version needed); Qdrant requires the official `io.qdrant:java-client` or Spring AI's vector store adapter. Flyway integrates automatically when on the classpath with `spring.jpa.hibernate.ddl-auto=none` required to prevent JPA from interfering.

The critical Docker Compose pitfall is the Qdrant health check: the official Qdrant Docker image excludes `curl` and `wget` by design for security. The recommended working approach uses bash's `/dev/tcp` built-in. The Qdrant health check issue has been open since 2023 and was still unresolved as of September 2025. For Neo4j, `cypher-shell` is available in the image and works well. For MySQL, `mysqladmin ping` is the standard approach.

Structured logging (JSON in prod, plain text in dev) is a first-class Spring Boot 3.4+ feature via `logging.structured.format.console=ecs|logstash` — no Logstash encoder dependency needed. Spotless with Google Java Format is the correct minimal formatter for a greenfield Spring Boot project.

**Primary recommendation:** Use `spring-initializr`-style structure (Gradle 9.3.x + Kotlin DSL + `libs.versions.toml`) with Spring Boot 3.5.11 on Java 21. Enable virtual threads with one property. Wire Prometheus scraping via Actuator. Build custom health indicators for Neo4j (built-in exists), MySQL (built-in exists), and Qdrant (custom required). Use Testcontainers `@ServiceConnection` for integration tests.

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.11 | Application framework | Latest stable 3.5.x; released 2026-02-19 |
| Java | 21 (LTS) | Runtime | LTS release with virtual threads GA |
| Gradle | 9.3.1 | Build tool | Latest stable as of 2026-01-29; Spring Boot 3.5 added Gradle 9 support |
| Spring Data Neo4j | via Boot BOM (7.5.x) | Neo4j OGM + driver | BOM-managed; matches Spring Data 2025.1 train |
| Spring Data JPA | via Boot BOM | MySQL ORM / Flyway integration | BOM-managed |
| Flyway | via Boot BOM (~10.x) | MySQL schema migrations | Auto-configures with datasource on classpath |
| Micrometer Prometheus | via Boot BOM | Metrics export | Boot auto-configures `/actuator/prometheus` endpoint |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `io.qdrant:java-client` | 1.x latest | Qdrant REST/gRPC client | Custom health indicator + Phase 5+ vector ops |
| `spring-boot-starter-actuator` | via Boot BOM | Health, info, metrics endpoints | Always include |
| Testcontainers Neo4j module | 1.20.x | Integration tests with real Neo4j | All `@DataNeo4jTest` / `@SpringBootTest` tests |
| Testcontainers MySQL module | 1.20.x | Integration tests with real MySQL | All JPA/Flyway integration tests |
| `spring-boot-testcontainers` | via Boot BOM | `@ServiceConnection` support | Required for annotation-driven container wiring |
| Spotless (Gradle plugin) | 7.x latest | Code formatting enforcement | Build-time check + apply |
| google-java-format | 1.23.x | Java formatter backing Spotless | Industry-standard, IDE-compatible |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Spring Data Neo4j | Neo4j Java Driver directly | Driver gives more control over Cypher; SDN adds OGM which is useful in Phase 3+ but not Phase 1 — both are fine for Phase 1 skeleton |
| Flyway | Liquibase | Flyway is simpler for SQL-centric teams; Liquibase more powerful for XML/JSON changelogs — Flyway is the right choice here |
| Gradle 9 | Gradle 8.12 | Gradle 9 is stable and Spring Boot 3.5 explicitly supports it; no reason to stay on 8 |
| google-java-format | palantir-java-format | Both work with Spotless; google-java-format is more widely known |

**Installation:**
```bash
# Project skeleton — use Spring Initializr or manual bootstrap
# Core runtime dependencies in libs.versions.toml
```

---

## Architecture Patterns

### Recommended Project Structure

```
esmp/
├── build.gradle.kts              # Single-module Gradle build
├── settings.gradle.kts           # Project name declaration
├── gradle/
│   └── libs.versions.toml        # Version catalog
├── gradlew / gradlew.bat         # Gradle wrapper
├── docker-compose.yml            # Neo4j, Qdrant, MySQL, Prometheus, Grafana
├── prometheus.yml                # Prometheus scrape config (bind-mounted)
├── src/
│   ├── main/
│   │   ├── java/com/esmp/
│   │   │   ├── EsmpApplication.java          # @SpringBootApplication
│   │   │   ├── graph/                        # Neo4j / code graph (Phase 3+)
│   │   │   ├── extraction/                   # AST extraction (Phase 2+)
│   │   │   ├── vector/                       # Qdrant / embeddings (Phase 5+)
│   │   │   ├── risk/                         # Risk analysis (Phase 4+)
│   │   │   ├── rag/                          # RAG pipeline (Phase 6+)
│   │   │   ├── lexicon/                      # Domain lexicon (Phase 7+)
│   │   │   └── infrastructure/
│   │   │       └── health/                   # Custom HealthIndicators (Phase 1)
│   │   └── resources/
│   │       ├── application.yml               # Base config
│   │       ├── application-dev.yml           # Dev profile (plain logging)
│   │       ├── application-prod.yml          # Prod profile (JSON logging)
│   │       └── db/migration/
│   │           └── V1__initial_schema.sql    # Flyway baseline
│   └── test/
│       └── java/com/esmp/
│           └── infrastructure/
│               └── health/                   # Health indicator tests
```

### Pattern 1: Virtual Threads Enablement

**What:** Single property activates virtual threads across all Spring-managed executor surfaces (Tomcat, `@Async`, `applicationTaskExecutor`).

**When to use:** Always for Java 21 + Spring Boot 3.2+. No custom executor beans needed unless overriding specific behavior.

**Example:**
```yaml
# application.yml
# Source: https://docs.spring.io/spring-boot/reference/features/spring-application.html
spring:
  threads:
    virtual:
      enabled: true
```

Note from Spring Boot 3.5 release notes: the auto-configured `TaskExecutor` bean was renamed from `taskExecutor` to `applicationTaskExecutor`. Do not inject by the old name.

---

### Pattern 2: Custom Actuator Health Indicator

**What:** Implement `HealthIndicator` as a Spring bean; Spring Boot auto-discovers and aggregates into `/actuator/health`.

**When to use:** For any service without a built-in Spring Boot health indicator (Qdrant requires this).

**Note:** Neo4j and MySQL/datasource health indicators are built into Spring Boot and auto-configure when the respective starters are present. Only Qdrant requires a custom implementation.

**Example (Qdrant):**
```java
// Source: Spring Boot Actuator docs + Qdrant Java client API
@Component
public class QdrantHealthIndicator implements HealthIndicator {

    private final QdrantClient qdrantClient;

    public QdrantHealthIndicator(QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    @Override
    public Health health() {
        try {
            // Qdrant /healthz returns 200 when ready
            // Use the Java client's health/readiness check
            qdrantClient.healthCheckAsync().get(3, TimeUnit.SECONDS);
            return Health.up().withDetail("qdrant", "reachable").build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("qdrant", "unreachable")
                .withException(e)
                .build();
        }
    }
}
```

---

### Pattern 3: Testcontainers with @ServiceConnection

**What:** `@ServiceConnection` on a `@Container` field eliminates manual `@DynamicPropertySource` wiring. Spring Boot creates appropriate `ConnectionDetails` beans automatically.

**When to use:** All integration tests needing real Neo4j or MySQL containers.

**Example:**
```java
// Source: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
@Testcontainers
@SpringBootTest
class InfrastructureHealthTest {

    @Container
    @ServiceConnection
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:2026.01.4");

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void allHealthIndicatorsAreUp() {
        // assertions against /actuator/health
    }
}
```

For Qdrant (no built-in `@ServiceConnection` support), use `@DynamicPropertySource`:
```java
@Container
static GenericContainer<?> qdrant =
    new GenericContainer<>("qdrant/qdrant:latest")
        .withExposedPorts(6333, 6334)
        .waitingFor(Wait.forHttp("/healthz").forPort(6333));

@DynamicPropertySource
static void qdrantProperties(DynamicPropertyRegistry registry) {
    registry.add("qdrant.host", qdrant::getHost);
    registry.add("qdrant.port", () -> qdrant.getMappedPort(6333));
}
```

---

### Pattern 4: Flyway Migration Naming Convention

**What:** Versioned migration files in `src/main/resources/db/migration/` with prefix `V{n}__{description}.sql`.

**When to use:** Always. Never use `spring.jpa.hibernate.ddl-auto=create` or `update` — these conflict with Flyway.

**Example:**
```sql
-- V1__initial_schema.sql
-- Source: standard Flyway convention
CREATE TABLE IF NOT EXISTS migration_job (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_key       VARCHAR(255) NOT NULL UNIQUE,
    status        VARCHAR(50)  NOT NULL,
    started_at    DATETIME     NOT NULL,
    completed_at  DATETIME,
    error_message TEXT,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS migration_audit (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT       NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    event_detail  TEXT,
    occurred_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (migration_job_id) REFERENCES migration_job(id)
);
```

**Required application config:**
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none     # CRITICAL: let Flyway own schema
  flyway:
    enabled: true
    locations: classpath:db/migration
```

---

### Pattern 5: Structured Logging Per Profile

**What:** Spring Boot 3.4+ native structured logging requires no additional dependencies. A single property activates JSON output.

**When to use:** Dev profile gets plain text; prod profile gets ECS JSON.

**Example:**
```yaml
# application-dev.yml
logging:
  level:
    root: INFO
    com.esmp: DEBUG
# No structured format — defaults to plain logback pattern

# application-prod.yml
logging:
  structured:
    format:
      console: ecs
    ecs:
      service:
        name: esmp
        version: ${spring.application.version:unknown}
        environment: production
```

---

### Pattern 6: libs.versions.toml Structure

**What:** Version catalog centralizes all dependency versions; prevents version drift across a growing project.

**Example:**
```toml
# gradle/libs.versions.toml
[versions]
spring-boot       = "3.5.11"
java              = "21"
testcontainers    = "1.20.4"
qdrant-client     = "1.13.0"
spotless          = "7.0.2"
google-java-format = "1.23.0"

[libraries]
spring-boot-starter-web      = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-starter-data-neo4j = { module = "org.springframework.boot:spring-boot-starter-data-neo4j" }
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-boot-starter-test     = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-boot-testcontainers   = { module = "org.springframework.boot:spring-boot-testcontainers" }
micrometer-prometheus        = { module = "io.micrometer:micrometer-registry-prometheus" }
flyway-core                  = { module = "org.flywaydb:flyway-core" }
flyway-mysql                 = { module = "org.flywaydb:flyway-mysql" }
mysql-connector              = { module = "com.mysql:mysql-connector-j" }
qdrant-client                = { module = "io.qdrant:java-client", version.ref = "qdrant-client" }
testcontainers-junit         = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
testcontainers-neo4j         = { module = "org.testcontainers:neo4j", version.ref = "testcontainers" }
testcontainers-mysql         = { module = "org.testcontainers:mysql", version.ref = "testcontainers" }

[plugins]
spring-boot          = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-mgmt = { id = "io.spring.dependency-management", version = "1.1.7" }
spotless             = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

---

### Anti-Patterns to Avoid

- **`spring.jpa.hibernate.ddl-auto=update` with Flyway present:** Hibernate will attempt to modify the schema independently, causing conflicts and unpredictable state. Always set to `none` or `validate`.
- **`depends_on` without `condition: service_healthy` in Docker Compose:** `depends_on` alone only waits for container start, not service readiness. Use `condition: service_healthy` for services that require readiness probes.
- **Running Spring Boot in Docker during Phase 1 dev loop:** The decision is to run Spring Boot outside Docker for faster iteration. Avoid adding the app to Docker Compose in Phase 1.
- **Hardcoding database passwords in `application.yml`:** Use environment variables (`${MYSQL_PASSWORD}`) and `.env` files for local secrets; never commit credentials.
- **Using `neo4j:latest` tag in production Docker Compose:** Tags can change unexpectedly. Pin to a specific version (`neo4j:2026.01.4`) and update deliberately.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| MySQL health check | Custom JDBC ping bean | Built-in `DataSourceHealthIndicator` (Spring Boot Actuator) | Auto-configured when `spring-boot-starter-actuator` + datasource on classpath |
| Neo4j health check | Custom Cypher query bean | Built-in `Neo4jHealthIndicator` (Spring Boot Actuator) | Auto-configured when `spring-boot-starter-data-neo4j` + actuator present |
| Schema versioning | Manual SQL applied at startup | Flyway | Handles checksums, ordering, history table, baseline, repair — not trivial to replicate |
| Prometheus metrics export | Custom metrics endpoint | `micrometer-registry-prometheus` + Actuator | Exposes `/actuator/prometheus` with JVM, Tomcat, and custom meters automatically |
| Integration test DB wiring | `@DynamicPropertySource` manual wiring for Neo4j/MySQL | `@ServiceConnection` on `@Container` | Auto-creates typed `ConnectionDetails` beans; less boilerplate, less error-prone |
| Test container lifecycle | Manual `@BeforeAll`/`@AfterAll` | `@Testcontainers` + `static @Container` | JUnit extension manages start/stop; static containers reused across test methods |

**Key insight:** Spring Boot's auto-configuration covers nearly all infrastructure health and metrics concerns. The only custom code needed is for Qdrant (no Spring Boot built-in) and any ESMP-specific business health checks added later.

---

## Common Pitfalls

### Pitfall 1: Qdrant Docker Health Check

**What goes wrong:** Docker Compose `healthcheck` using `curl` or `wget` fails silently because the official Qdrant image excludes these tools for security reasons. The compose `depends_on: condition: service_healthy` never resolves.

**Why it happens:** Qdrant intentionally strips common HTTP tools from its image. This is a known open issue (github.com/qdrant/qdrant/issues/4250, open since 2023, still unresolved as of September 2025).

**How to avoid:** Use bash's `/dev/tcp` built-in to probe the `/readyz` HTTP endpoint. Explicitly invoke `bash` (not the default `sh`/`dash`):
```yaml
healthcheck:
  test: ["CMD", "bash", "-c",
    "exec 3<>/dev/tcp/127.0.0.1/6333 && echo -e 'GET /readyz HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q 'HTTP/1.1 200' <&3"]
  interval: 10s
  timeout: 5s
  retries: 5
  start_period: 20s
```

**Warning signs:** `service "qdrant" didn't complete successfully` on `docker compose up` with `condition: service_healthy`.

---

### Pitfall 2: Flyway + Hibernate DDL Conflict

**What goes wrong:** `spring.jpa.hibernate.ddl-auto=create` or `update` causes Hibernate to create/modify tables that Flyway should own, resulting in schema drift between environments.

**Why it happens:** Spring Boot sets a non-`none` DDL mode when it detects an in-memory H2 datasource, but leaves the mode as configured otherwise. Developers often forget to explicitly set `none`.

**How to avoid:** Always explicitly set:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
```

**Warning signs:** `flyway_schema_history` table shows checksum mismatches, or schema inconsistencies between dev and CI environments.

---

### Pitfall 3: Actuator Endpoints Not Exposed

**What goes wrong:** `/actuator/health/liveness`, `/actuator/health/readiness`, and `/actuator/prometheus` return 404 even with `spring-boot-starter-actuator` on classpath.

**Why it happens:** Spring Boot defaults to only exposing `health` and `info` over HTTP (not `prometheus`, `metrics`, etc.). Prometheus scraping requires explicitly enabling exposure.

**How to avoid:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
```

**Warning signs:** Prometheus scrape config returns empty target or 404.

---

### Pitfall 4: Docker Compose Startup Order

**What goes wrong:** Spring Boot (running externally) starts before Neo4j/Qdrant/MySQL are ready, causing connection failures at startup if health indicators are set to fail-fast.

**Why it happens:** Docker Compose `depends_on` with `condition: service_healthy` only applies within the compose network. The externally-run Spring Boot process has no such dependency management.

**How to avoid:** Use `spring.datasource.hikari.connection-timeout` and Neo4j's driver retry policy to give stores time to become available. Alternatively, run `docker compose up` first, wait for all health checks to pass (`docker compose ps`), then start Spring Boot. Document this in a project README.

**Warning signs:** `ConnectionRefused` or `ServiceUnavailableException` during `./gradlew bootRun` immediately after `docker compose up`.

---

### Pitfall 5: Spring Boot 3.5 applicationTaskExecutor Bean Name

**What goes wrong:** Code injecting a `@Qualifier("taskExecutor")` bean fails with `NoSuchBeanDefinitionException`.

**Why it happens:** Spring Boot 3.5 renamed the auto-configured `TaskExecutor` bean from `taskExecutor` to `applicationTaskExecutor`.

**How to avoid:** Use `@Qualifier("applicationTaskExecutor")` when explicitly injecting the application task executor, or inject by type (`TaskExecutor`) without a qualifier.

---

### Pitfall 6: Gradle 9 Configuration Cache and Spring Boot Plugin

**What goes wrong:** Some Gradle 9 users report configuration cache incompatibilities with certain plugins during the initial 9.x adoption window.

**Why it happens:** Gradle 9 promotes configuration cache from experimental to recommended; some plugins haven't fully adopted it yet.

**How to avoid:** If encountering cache errors, disable configuration cache in `gradle.properties` initially:
```properties
org.gradle.configuration-cache=false
```
Re-enable and validate plugin compatibility once the core build is stable.

---

## Code Examples

Verified patterns from official sources:

### docker-compose.yml (Data Stores + Monitoring Only)

```yaml
# Source: Official Docker Hub images; health check patterns from community + official docs
services:

  neo4j:
    image: neo4j:2026.01.4
    ports:
      - "7474:7474"
      - "7687:7687"
    environment:
      NEO4J_AUTH: neo4j/esmp-local-password
    volumes:
      - neo4j_data:/data
    healthcheck:
      test: ["CMD-SHELL",
        "cypher-shell -u neo4j -p esmp-local-password 'RETURN 1' || exit 1"]
      interval: 10s
      timeout: 10s
      retries: 10
      start_period: 30s

  qdrant:
    image: qdrant/qdrant:latest
    ports:
      - "6333:6333"
      - "6334:6334"
    volumes:
      - qdrant_data:/qdrant/storage
    healthcheck:
      test: ["CMD", "bash", "-c",
        "exec 3<>/dev/tcp/127.0.0.1/6333 && echo -e 'GET /readyz HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q 'HTTP/1.1 200' <&3"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s

  mysql:
    image: mysql:8.4
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-esmp-local-root}
      MYSQL_DATABASE: esmp
      MYSQL_USER: esmp
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:-esmp-local-password}
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost",
        "-u", "esmp", "-p${MYSQL_PASSWORD:-esmp-local-password}"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus_data:/prometheus

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    volumes:
      - grafana_data:/var/lib/grafana
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD:-admin}

volumes:
  neo4j_data:
  qdrant_data:
  mysql_data:
  prometheus_data:
  grafana_data:
```

---

### prometheus.yml (Scrape Config)

```yaml
# Source: Prometheus docs + Spring Boot Actuator Prometheus docs
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'esmp'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8080']
```

Note: `host.docker.internal` resolves to the host machine from within Docker on Mac/Windows. On Linux, use the host's network IP or enable `--add-host=host.docker.internal:host-gateway` in the compose file.

---

### application.yml (Base Configuration)

```yaml
# Source: Spring Boot docs + researched patterns
spring:
  application:
    name: esmp
  threads:
    virtual:
      enabled: true          # Enables virtual threads on Java 21
  datasource:
    url: jdbc:mysql://localhost:3306/esmp
    username: esmp
    password: ${MYSQL_PASSWORD:esmp-local-password}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      connection-timeout: 30000
      maximum-pool-size: 20
  jpa:
    hibernate:
      ddl-auto: none         # CRITICAL: Flyway owns schema
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: ${NEO4J_PASSWORD:esmp-local-password}

management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true

qdrant:
  host: localhost
  port: 6333

server:
  port: 8080
```

---

### application-dev.yml (Dev Profile)

```yaml
# Plain text logging — no structured format property needed
logging:
  level:
    root: INFO
    com.esmp: DEBUG
```

---

### application-prod.yml (Prod Profile)

```yaml
# Source: https://docs.spring.io/spring-boot/reference/features/logging.html
logging:
  structured:
    format:
      console: ecs
    ecs:
      service:
        name: esmp
        environment: production
  level:
    root: WARN
    com.esmp: INFO
```

---

### build.gradle.kts (Skeleton)

```kotlin
// Source: Spring Initializr pattern + libs.versions.toml catalog
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
    alias(libs.plugins.spotless)
    java
}

group = "com.esmp"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.neo4j)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.micrometer.prometheus)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    implementation(libs.mysql.connector)
    implementation(libs.qdrant.client)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.testcontainers.mysql)
}

spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get())
        removeUnusedImports()
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@Async` + custom `ThreadPoolTaskExecutor` | `spring.threads.virtual.enabled: true` | Spring Boot 3.2 (Nov 2023) | Eliminates thread pool tuning for I/O-bound services |
| `@DynamicPropertySource` for test containers | `@ServiceConnection` on `@Container` | Spring Boot 3.1 (May 2023) | Cleaner, type-safe test wiring; less boilerplate |
| Logstash Logback Encoder dependency for JSON logs | `logging.structured.format.console: ecs` | Spring Boot 3.4 (Nov 2024) | Zero-dependency JSON structured logging |
| Gradle Groovy DSL | Gradle Kotlin DSL (`build.gradle.kts`) | Mainstream since Gradle 7.x | Type safety, IDE completion, refactoring support |
| `neo4j:4.x` / `neo4j:5.x` tags | `neo4j:2026.01.4` (date-versioned) | ~2025 | Neo4j adopted calendar-based versioning |

**Deprecated/outdated:**
- `spring.jpa.hibernate.ddl-auto=create/update`: Use `none` with Flyway in any production-like environment
- `taskExecutor` bean name: Renamed to `applicationTaskExecutor` in Spring Boot 3.5
- Groovy DSL (`build.gradle`): Still works but Kotlin DSL is the official recommendation for new projects
- Logstash Logback Encoder for JSON: Replaced by native `logging.structured.format.console` in Boot 3.4+

---

## Open Questions

1. **Qdrant Java client version for health check**
   - What we know: `io.qdrant:java-client` official client provides `healthCheckAsync()`. Latest version is ~1.13.x as of early 2026.
   - What's unclear: Exact API of `healthCheckAsync()` method — whether it throws checked exceptions or returns a `CompletableFuture<HealthCheckReply>`. Needs verification against the library's published Javadoc before coding the health indicator.
   - Recommendation: Check `https://github.com/qdrant/java-client` releases for the latest stable version and confirm the health check API signature before implementing `QdrantHealthIndicator`.

2. **Neo4j 2026.x version compatibility with Spring Data Neo4j 7.5.x**
   - What we know: Spring Data Neo4j 7.5.x is the 2025 release train version. Neo4j 2026.01.4 is the latest community image.
   - What's unclear: Whether the Bolt protocol version in SDN 7.5.x supports the 2026.x Neo4j server. Neo4j maintains strong backward compatibility, so this is likely fine.
   - Recommendation: Verify by running an integration test with the exact versions chosen. If there are Bolt protocol warnings, downgrade the Neo4j Docker image to the latest 5.x LTS.

3. **Prometheus scrape target on Linux Docker hosts**
   - What we know: `host.docker.internal` resolves correctly on Mac and Windows. On Linux, it requires `extra_hosts: ["host.docker.internal:host-gateway"]` in the Prometheus compose service.
   - What's unclear: Whether the CI environment (if any) is Linux-based and requires this configuration.
   - Recommendation: Add the `extra_hosts` entry to the Prometheus service in `docker-compose.yml` as a cross-platform default.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (via `spring-boot-starter-test`) + Testcontainers |
| Config file | None — no config file needed; JUnit 5 is auto-detected by Spring Boot Test |
| Quick run command | `./gradlew test --tests "*health*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| INFRA-01 | Neo4j, Qdrant, MySQL reachable from Spring Boot at startup (health indicators UP) | Integration | `./gradlew test --tests "*HealthIndicatorIntegrationTest*"` | Wave 0 |
| INFRA-02 | Spring Boot starts on Java 21 with virtual threads enabled; health check endpoint responds | Integration + Smoke | `./gradlew test --tests "*VirtualThreadsTest*"` | Wave 0 |
| INFRA-03 | Flyway migration applies without error; project compiles cleanly | Integration + Build | `./gradlew build` | Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "*health*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/com/esmp/infrastructure/health/HealthIndicatorIntegrationTest.java` — covers INFRA-01 (all three health indicators UP)
- [ ] `src/test/java/com/esmp/infrastructure/health/VirtualThreadsTest.java` — covers INFRA-02 (virtual threads active, health endpoint reachable)
- [ ] Testcontainers + `spring-boot-testcontainers` in `libs.versions.toml` and `build.gradle.kts` test dependencies — required before any integration tests run

---

## Sources

### Primary (HIGH confidence)

- Spring Boot 3.5.11 release blog — https://spring.io/blog/2026/02/19/spring-boot-3-5-11-available-now/
- Spring Boot 3.5 Release Notes (GitHub wiki) — virtual threads rename, breaking changes
- Spring Boot Logging reference docs — `logging.structured.format.console` property and format options
- Spring Boot Testcontainers docs — `@ServiceConnection` pattern and `Neo4jContainer` / `MySQLContainer` usage
- Neo4jHealthIndicator Spring Boot 3.5.3 API docs — confirms built-in health indicator auto-configuration
- Gradle releases page — 9.3.1 confirmed stable as of 2026-01-29
- Neo4j Docker Hub — `neo4j:2026.01.4` confirmed latest community tag
- Qdrant docs (qdrant.tech/documentation/guides/monitoring) — `/healthz`, `/livez`, `/readyz` endpoint confirmation

### Secondary (MEDIUM confidence)

- Qdrant GitHub issue #4250 — health check status confirmed open as of September 2025; `/dev/tcp` bash workaround documented by community
- Spring Boot Gradle best practices (erichaag.dev) — version catalog structure validated against official Gradle docs
- Spotless plugin README (diffplug/spotless) — `googleJavaFormat()` configuration syntax

### Tertiary (LOW confidence)

- Qdrant Java client API surface for `healthCheckAsync()` — based on GitHub README and SDK patterns; needs direct verification at implementation time
- Neo4j 2026.x / Spring Data Neo4j 7.5.x Bolt protocol compatibility — assumed compatible based on Neo4j backward compatibility history; verify with integration test

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — versions confirmed via official release blogs and Docker Hub tags
- Architecture: HIGH — patterns verified against Spring Boot official docs and release notes
- Pitfalls: HIGH (Qdrant/Flyway/Actuator) / MEDIUM (Gradle 9 cache) — Qdrant issue verified against open GitHub ticket
- Validation architecture: HIGH — JUnit 5 + Testcontainers patterns confirmed against Spring Boot docs

**Research date:** 2026-03-04
**Valid until:** 2026-04-04 (stable stack; Spring Boot 3.5 patch releases won't affect patterns)
