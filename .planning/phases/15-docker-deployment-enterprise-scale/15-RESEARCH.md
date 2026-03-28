# Phase 15: Docker Deployment & Enterprise Scale - Research

**Researched:** 2026-03-19
**Domain:** Docker containerization, runtime Git cloning, enterprise-scale Java AST parsing, Neo4j bulk writes, JVM tuning
**Confidence:** HIGH (core deployment patterns), MEDIUM (enterprise scale optimizations)

---

## Summary

Phase 15 adds two major capabilities: (1) making ESMP deployable as a Docker image where the `sourceRoot` is supplied at runtime via a GitHub URL or a volume mount, rather than a hardcoded local path; (2) ensuring the full pipeline — extraction, graph building, vector indexing, RAG, MCP — performs acceptably on enterprise codebases of 4M+ LOC.

The existing application.yml already externalizes `esmp.extraction.source-root` and `esmp.extraction.classpath-file` via `ExtractionConfig`. Spring Boot relaxed binding maps `ESMP_EXTRACTION_SOURCE_ROOT` environment variables straight to those properties, so the configuration bridge already exists. What is missing is: a Dockerfile, a production-ready `docker-compose.yml` (Spring Boot service added), runtime Git clone support in the extraction path, progress streaming for long-running operations, and throughput improvements for the sequential file-by-file parsing loop.

For enterprise scale the bottleneck is the `ExtractionService` loop that calls seven visitors per `SourceFile` sequentially. At 4M LOC (roughly 20,000–40,000 class files) this loop takes tens of minutes. The fix is batch-partitioned parallel parsing using `CompletableFuture` on a bounded `ThreadPoolTaskExecutor`, combined with batched Neo4j UNWIND MERGE writes instead of one-by-one Cypher calls, and JVM heap sizing that respects container limits via `UseContainerSupport`.

**Primary recommendation:** Add a multi-stage Dockerfile (`eclipse-temurin:21-jdk-jammy` build → `eclipse-temurin:21-jre-jammy` runtime), add a `SourceAccessService` that handles GitHub clone and volume-mount strategies based on an enum config, expose progress via Spring MVC `SseEmitter`, and partition the extraction loop into configurable batches of 200 files processed by a bounded executor.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| eclipse-temurin | 21-jre-jammy (Docker base) | JRE-only runtime image | Official Adoptium builds; smaller than JDK; Java 21 LTS |
| eclipse-temurin | 21-jdk-jammy (build stage) | Gradle compilation in Docker | Same base, build-stage only |
| org.eclipse.jgit:org.eclipse.jgit | 7.6.0.202603022253-r | Programmatic Git clone within Spring service | Pure-Java, no git binary required, HTTPS+PAT auth via `UsernamePasswordCredentialsProvider` |
| Spring MVC SseEmitter | (Spring Boot 3.5.11 built-in) | Server-Sent Events for progress streaming | Already on classpath; no extra dep; works with virtual threads |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| spring-boot-starter-cache + Caffeine | already on classpath | Cache clone results and parsed file hashes | Avoid re-clone when source URL unchanged |
| Docker Compose `depends_on` with healthcheck | Docker Compose 2.x | Boot-ordering between Spring Boot service and Neo4j/Qdrant/MySQL | Required — app fails on startup if databases not ready |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| JGit | Shell `git clone` via `ProcessBuilder` | ProcessBuilder works but requires git binary in Docker image (+50MB), loses Java-native error handling |
| SseEmitter (MVC) | WebFlux Flux | WebFlux would require reactive refactor of entire service layer; MVC SseEmitter is simpler and already coherent with current stack |
| eclipse-temurin | amazoncorretto, openjdk | Temurin is community consensus for Docker; amazoncorretto is AWS-specific |

**Installation (new libraries only):**
```bash
# In build.gradle.kts dependencies block — add:
implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")
```

**Version verification:** JGit 7.6.0.202603022253-r confirmed as release in Maven Central `maven-metadata.xml` on 2026-03-19.

---

## Architecture Patterns

### Recommended Project Structure (new files only)
```
/
├── Dockerfile                         # Multi-stage build
├── docker-compose.full.yml            # Full stack including esmp service
├── src/main/java/com/esmp/
│   ├── source/
│   │   ├── config/
│   │   │   └── SourceAccessConfig.java   # @ConfigurationProperties prefix="esmp.source"
│   │   ├── application/
│   │   │   └── SourceAccessService.java  # clone / validate / resolve sourceRoot
│   │   └── api/
│   │       └── SourceAccessController.java  # GET /api/source/status
│   └── extraction/
│       └── application/
│           └── ExtractionProgressService.java  # SseEmitter progress push
```

### Pattern 1: Multi-Stage Dockerfile (layered JAR)

**What:** Two stages — Gradle build + JRE runtime. Layered JAR extraction separates dependencies from application code so Docker cache hits on dependency layer when only code changes.

**When to use:** All Docker deployments.

```dockerfile
# Source: Docker official Spring Boot guide (docker.com/blog/9-tips-for-containerizing-your-spring-boot-code)
# Stage 1 — Build
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY gradle/libs.versions.toml gradle/
RUN ./gradlew dependencies --no-daemon -q || true   # warm dependency cache layer
COPY src src
RUN ./gradlew bootJar --no-daemon -x test -x spotlessCheck

# Extract layered JAR
RUN mkdir -p build/dependency && \
    java -Djarmode=layertools -jar build/libs/*.jar extract --destination build/dependency

# Stage 2 — Runtime
FROM eclipse-temurin:21-jre-jammy AS runtime
RUN useradd -m -u 1000 esmp
WORKDIR /app

# Copy layers in change-frequency order (least-changed first)
COPY --from=builder /app/build/dependency/dependencies/ ./
COPY --from=builder /app/build/dependency/spring-boot-loader/ ./
COPY --from=builder /app/build/dependency/snapshot-dependencies/ ./
COPY --from=builder /app/build/dependency/application/ ./

# Install git for JGit SSH fallback (optional: only needed if SSH transport used)
# RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

USER esmp

# Container-aware JVM memory (Java 21 UseContainerSupport is ON by default)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 \
               -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
               -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### Pattern 2: Source Access Strategy

**What:** `SourceAccessService` accepts either a `GITHUB_URL` (JGit clone into container-local temp dir) or a `VOLUME_MOUNT` (user pre-mounts source at a known path). The strategy is determined by `esmp.source.strategy` env var, defaulting to `VOLUME_MOUNT`.

**When to use:** All extraction operations that receive a `sourceRoot`.

```java
// Source: JGit cookbook (github.com/centic9/jgit-cookbook)
// SourceAccessConfig.java
@ConfigurationProperties(prefix = "esmp.source")
@Component
public class SourceAccessConfig {
    private Strategy strategy = Strategy.VOLUME_MOUNT;
    private String volumeMountPath = "/mnt/source";
    private String githubUrl = "";         // e.g. https://github.com/org/repo
    private String githubToken = "";       // PAT; set via env ESMP_SOURCE_GITHUB_TOKEN
    private String cloneDirectory = "/tmp/esmp-source-clone";
    private String branch = "main";

    public enum Strategy { VOLUME_MOUNT, GITHUB_URL }
    // getters/setters ...
}

// SourceAccessService.java
@Service
public class SourceAccessService {
    public String resolveSourceRoot() {
        return switch (config.getStrategy()) {
            case GITHUB_URL -> cloneIfNeeded();
            case VOLUME_MOUNT -> config.getVolumeMountPath();
        };
    }

    private String cloneIfNeeded() {
        Path cloneDir = Path.of(config.getCloneDirectory());
        if (Files.exists(cloneDir.resolve(".git"))) {
            // Already cloned — pull latest
            try (Git git = Git.open(cloneDir.toFile())) {
                git.pull().setCredentialsProvider(credentials()).call();
            }
        } else {
            Files.createDirectories(cloneDir);
            Git.cloneRepository()
                .setURI(config.getGithubUrl())
                .setDirectory(cloneDir.toFile())
                .setBranch(config.getBranch())
                .setCredentialsProvider(credentials())
                .call();
        }
        return cloneDir.toString();
    }

    private UsernamePasswordCredentialsProvider credentials() {
        // GitHub PAT: username is any non-empty string, password is the token
        return new UsernamePasswordCredentialsProvider("x-token", config.getGithubToken());
    }
}
```

### Pattern 3: Spring Boot Environment Variable Override

**What:** Spring Boot relaxed binding maps `ESMP_EXTRACTION_SOURCE_ROOT` → `esmp.extraction.source-root`. The `ExtractionConfig` bean already has this property. Setting the env var in `docker-compose.full.yml` or `docker run -e` is sufficient — no code change needed.

```yaml
# docker-compose.full.yml (esmp service section)
services:
  esmp:
    image: esmp:latest
    ports:
      - "8080:8080"
    environment:
      NEO4J_URI: bolt://neo4j:7687
      NEO4J_PASSWORD: ${NEO4J_PASSWORD:-esmp-local-password}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:-esmp-local-password}
      QDRANT_HOST: qdrant
      ESMP_SOURCE_STRATEGY: ${ESMP_SOURCE_STRATEGY:-VOLUME_MOUNT}
      ESMP_SOURCE_VOLUME_MOUNT_PATH: /mnt/source
      ESMP_SOURCE_GITHUB_URL: ${ESMP_SOURCE_GITHUB_URL:-}
      ESMP_SOURCE_GITHUB_TOKEN: ${ESMP_SOURCE_GITHUB_TOKEN:-}
    volumes:
      - ${SOURCE_ROOT:-./}:/mnt/source:ro    # user bind-mounts their codebase
    depends_on:
      neo4j:
        condition: service_healthy
      qdrant:
        condition: service_healthy
      mysql:
        condition: service_healthy
```

**Key:** `spring.datasource.url` uses `jdbc:mysql://localhost:3307/esmp` in dev. In Docker, it must be `jdbc:mysql://mysql:3306/esmp` (service name + internal port). Use env var `SPRING_DATASOURCE_URL` to override.

### Pattern 4: Batched Parallel Extraction (Enterprise Scale)

**What:** Replace the sequential `for (SourceFile sf : sourceFiles)` visitor loop with a partitioned executor pattern. Each partition of N files is visited in a separate task; accumulators are merged after all tasks complete.

**When to use:** When `sourceFiles.size() > 500` (configurable threshold).

```java
// Source: Spring Boot virtual threads + CompletableFuture pattern
// ExtractionService — add a parallel path for large codebases

@Value("${esmp.extraction.parallel-threshold:500}")
private int parallelThreshold;

@Value("${esmp.extraction.partition-size:200}")
private int partitionSize;

// In extract():
if (sourceFiles.size() > parallelThreshold) {
    accumulator = visitInParallel(sourceFiles);
} else {
    accumulator = visitSequentially(sourceFiles);
}

private ExtractionAccumulator visitInParallel(List<SourceFile> sourceFiles) {
    List<List<SourceFile>> partitions = partition(sourceFiles, partitionSize);
    List<CompletableFuture<ExtractionAccumulator>> futures = partitions.stream()
        .map(batch -> CompletableFuture.supplyAsync(
            () -> visitBatch(batch),
            extractionExecutor   // bounded ThreadPoolTaskExecutor, NOT commonPool
        ))
        .toList();
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(v -> futures.stream()
            .map(CompletableFuture::join)
            .reduce(new ExtractionAccumulator(), ExtractionAccumulator::merge))
        .join();
}
```

**Critical:** `ExtractionAccumulator` must be thread-safe (concurrent maps) or each task must use its own instance with a merge step. The merge approach (each task gets its own accumulator, merged after completion) is safer and avoids ConcurrentModificationException.

### Pattern 5: Batched Neo4j UNWIND MERGE (Enterprise Scale)

**What:** Replace one-by-one `neo4jClient.query().run()` loops for AnnotationNode/PackageNode/ModuleNode/DBTableNode with batched UNWIND MERGE Cypher that processes all nodes in a single transaction per batch.

**When to use:** Node lists with more than 100 entries (all enterprise-scale extractions).

```java
// Source: Neo4j performance guide (neo4j-guide.com/neo4j-slow-import-data-performance)
// Replaces per-node loop in ExtractionService and IncrementalIndexingService

// Example for PackageNode (apply same pattern to Annotation/Module/DBTable)
String cypher =
    "UNWIND $rows AS row "
    + "MERGE (p:JavaPackage {packageName: row.name}) "
    + "ON CREATE SET p.simpleName = row.simpleName, p.moduleName = row.moduleName "
    + "ON MATCH SET  p.simpleName = row.simpleName, p.moduleName = row.moduleName";

List<Map<String, Object>> rows = packageNodes.stream()
    .map(n -> Map.of(
        "name", n.getPackageName() != null ? n.getPackageName() : "",
        "simpleName", n.getSimpleName() != null ? n.getSimpleName() : "",
        "moduleName",  n.getModuleName() != null ? n.getModuleName() : ""))
    .toList();

// Batch to stay within Neo4j transaction memory limits (~2000 rows per batch)
for (List<Map<String, Object>> batch : partition(rows, 2000)) {
    neo4jClient.query(cypher).bind(batch).to("rows").run();
}
```

### Pattern 6: Progress Streaming via SseEmitter

**What:** `ExtractionProgressService` holds a per-request `SseEmitter` and pushes progress events as files are processed. The extraction endpoint returns the emitter; clients poll via EventSource.

**When to use:** `/api/extraction/trigger` and `/api/vector/index` for large codebases.

```java
// Source: Baeldung Server-Sent Events in Spring (baeldung.com/spring-server-sent-events)
@GetMapping(value = "/api/extraction/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamProgress(@RequestParam String jobId) {
    SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 min timeout
    progressService.register(jobId, emitter);
    return emitter;
}

// During extraction:
progressService.send(jobId, new ProgressEvent("PARSING", filesProcessed, totalFiles));
```

### Anti-Patterns to Avoid

- **Hardcoded localhost URLs in docker-compose.full.yml:** Service names must be used (`neo4j:7687`, `mysql:3306`, `qdrant:6334`) not `localhost`.
- **UseContainerSupport disabled:** In Java 21, container support is ON by default, but `-Xmx` flags override it. Never combine `-Xmx` with `MaxRAMPercentage` — they conflict.
- **Fat-JAR single-layer COPY:** Always use layered extraction; a 150MB fat JAR copied as a single layer makes every code change rebuild a 150MB layer.
- **Cloning into image at build time:** Git clone at build time burns credentials into the image layer and the URL is fixed. Clone at runtime via `SourceAccessService` instead.
- **Running as root in the container:** Security violation; always create and switch to `USER esmp`.
- **Parallel parsing with shared `ExtractionAccumulator`:** Shared mutable state across threads causes data corruption. Each `CompletableFuture` task must use its own accumulator instance.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Git clone with auth | Shell ProcessBuilder + credential management | JGit `Git.cloneRepository()` with `UsernamePasswordCredentialsProvider` | Handles redirects, TLS, protocol negotiation, PAT auth; no git binary needed in image |
| Container memory detection | Custom heap calculation | `-XX:UseContainerSupport` (Java 21 default) + `-XX:MaxRAMPercentage` | JVM reads cgroup memory limits; custom calc will be wrong |
| Layered JAR extraction | Custom JAR splitter | `java -Djarmode=layertools -jar app.jar extract` | Spring Boot built-in; Docker-layer-cache-aware split |
| Progress polling REST endpoint | Polling GET /status | Spring MVC `SseEmitter` | Push-based; no polling overhead; 1 line in controller |
| Batched Cypher execution | Streaming individual MERGE calls | Cypher UNWIND with parameter list | Single network round-trip per batch; 10-20x faster at scale |

**Key insight:** JGit is the standard Java library for Git operations — it has been the embedded Git implementation in Eclipse, Jenkins, and Gerrit for over a decade. The `org.eclipse.jgit` artifact is 100% pure Java with no native dependency requirements.

---

## Common Pitfalls

### Pitfall 1: `localhost` in Docker service-to-service URLs

**What goes wrong:** `application.yml` has `spring.datasource.url: jdbc:mysql://localhost:3307/esmp`. Inside Docker, `localhost` is the container itself, not the MySQL service. The app fails to connect at startup.

**Why it happens:** Dev config hard-codes `localhost` because the developer runs services directly. Docker networking uses service names.

**How to avoid:** In `docker-compose.full.yml`, override connection URLs via environment variables:
```yaml
SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/esmp
SPRING_NEO4J_URI: bolt://neo4j:7687
QDRANT_HOST: qdrant
```
Note: the `mysql` service exposes port 3306 internally (mapped to host 3307), so inside Docker use `3306`, not `3307`.

**Warning signs:** `Connection refused to localhost:3307` in startup logs.

### Pitfall 2: JVM heap exceeds container memory limit → OOM kill

**What goes wrong:** Default JVM heap is 25% of host RAM (e.g., 8GB on a 32GB host). Container is limited to 4GB. JVM allocates 8GB, cgroup OOM-kills the container.

**Why it happens:** Pre-Java 11 behavior leaked into assumptions. `UseContainerSupport` is enabled by default in Java 11+ but `MaxRAMPercentage` defaults to only 25%.

**How to avoid:** Set `MaxRAMPercentage=75.0`. For extraction at enterprise scale (4M LOC), allocate at least 6GB container memory: `docker run -m 6g`.

**Warning signs:** Container exits with code 137 (OOM killed), no Java stack trace.

### Pitfall 3: OpenRewrite `JavaTypeCache` not shared across parallel partitions

**What goes wrong:** Parallel visitor tasks each create their own `JavaTypeCache`. This doubles or triples memory usage because type metadata is not deduplicated across batches. At 4M LOC, each cache instance can exceed 500MB.

**Why it happens:** `JavaSourceParser` creates `new JavaTypeCache()` per parse call. When parallelizing, multiple simultaneous parse calls each allocate a cache.

**How to avoid:** Share a single `JavaTypeCache` instance across all parser calls within one extraction run. Pass the cache as a constructor argument to `JavaSourceParser.parse()`. Important: `JavaTypeCache` is in `org.openrewrite.java.internal` (internal package) — access it via `JavaParser.Builder.typeCache()` as already done in the project.

**Warning signs:** Heap usage grows proportionally with partition count rather than plateauing.

### Pitfall 4: `ExtractionAccumulator` merge conflicts in parallel extraction

**What goes wrong:** If two partitions process classes with the same annotation or package, the merge logic must deduplicate. A naive `putAll()` map merge will keep only one entry, losing data.

**Why it happens:** Annotations and packages are shared across many classes. Parallel partitions each see only a subset of the total class set but may encounter the same package/annotation FQN.

**How to avoid:** Implement `ExtractionAccumulator.merge(ExtractionAccumulator other)` that uses `putIfAbsent()` or merges lists correctly for call edges and enrichment data. Classes are keyed by FQN so direct `putAll()` is safe (no collisions expected); annotation/package maps also key by FQN so `putAll()` is safe too.

**Warning signs:** Missing annotation nodes or duplicate edge entries in graph after parallel extraction.

### Pitfall 5: Git clone directory not cleaned between runs

**What goes wrong:** Second extraction run finds `.git` exists, calls `git pull`, but the working directory has stale content. If the clone directory is the same as a previous partial clone, the pull may fail or return wrong content.

**Why it happens:** JGit `Git.open()` succeeds even on a corrupt or partial clone.

**How to avoid:** Check that the remote URL matches before pulling; delete and re-clone if it differs. Also ensure the clone directory is in a Docker volume or tmpfs that is isolated per container lifecycle, not a bind-mounted host path.

**Warning signs:** `ExtractionService` finds 0 Java files after a second run.

### Pitfall 6: Vaadin production build required in Docker

**What goes wrong:** `vaadinPrepareFrontend` and `vaadinBuildFrontend` tasks must run in the Gradle build stage of the Dockerfile. If they are skipped, the Vaadin UI serves a blank page. Also: Vaadin requires the daemon to run with Java 21 (not 17) due to `org.reflections:0.10.2` NPE.

**Why it happens:** Vaadin's frontend bundle compilation is a Gradle task — it does not happen at runtime.

**How to avoid:** In the Dockerfile build stage, ensure the Gradle toolchain resolves to Java 21. The `JAVA_HOME` inside the `eclipse-temurin:21-jdk-jammy` image is correct. Remove the `--no-daemon` limitation only if the daemon memory doesn't cause OOM in a build container.

**Warning signs:** Vaadin UI loads but shows blank content; `vaadinBuildFrontend` not in Gradle build log.

---

## Code Examples

### Dockerfile (complete)

```dockerfile
# Source: Docker official Spring Boot guide + project's Java 21 + Vaadin + Gradle stack
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Dependency warm-up (separate layer for caching)
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle/libs.versions.toml ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

# Full build with Vaadin frontend
COPY src src
RUN ./gradlew bootJar vaadinBuildFrontend --no-daemon -x test -x spotlessCheck

# Extract layered JAR
RUN mkdir -p build/dependency && \
    java -Djarmode=layertools -jar build/libs/esmp-*.jar extract --destination build/dependency

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN useradd -m -u 1000 esmp
WORKDIR /app

COPY --from=builder /app/build/dependency/dependencies/ ./
COPY --from=builder /app/build/dependency/spring-boot-loader/ ./
COPY --from=builder /app/build/dependency/snapshot-dependencies/ ./
COPY --from=builder /app/build/dependency/application/ ./

USER esmp

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 \
               -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
               -XX:+ExitOnOutOfMemoryError \
               -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### JGit Dependency (build.gradle.kts)

```kotlin
// Source: Maven Central (central.sonatype.com/artifact/org.eclipse.jgit/org.eclipse.jgit)
// Verified release: 7.6.0.202603022253-r on 2026-03-19
implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")
```

### Spring Boot Property Mapping in Docker

```yaml
# application.yml — all localhost values become configurable via env
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3307/esmp}
  neo4j:
    uri: ${SPRING_NEO4J_URI:bolt://localhost:7687}
qdrant:
  host: ${QDRANT_HOST:localhost}
esmp:
  source:
    strategy: ${ESMP_SOURCE_STRATEGY:VOLUME_MOUNT}
    volume-mount-path: ${ESMP_SOURCE_VOLUME_MOUNT_PATH:/mnt/source}
    github-url: ${ESMP_SOURCE_GITHUB_URL:}
    github-token: ${ESMP_SOURCE_GITHUB_TOKEN:}
    clone-directory: ${ESMP_SOURCE_CLONE_DIRECTORY:/tmp/esmp-source-clone}
    branch: ${ESMP_SOURCE_BRANCH:main}
  extraction:
    parallel-threshold: ${ESMP_EXTRACTION_PARALLEL_THRESHOLD:500}
    partition-size: ${ESMP_EXTRACTION_PARTITION_SIZE:200}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Fat JAR single-layer COPY | Layered JAR extraction in multi-stage build | Spring Boot 2.3+ | Rebuild only app layer on code change; -60% image size |
| `-Xmx` fixed heap | `UseContainerSupport` + `MaxRAMPercentage` | Java 11+ (default ON) | JVM respects cgroup limits; no OOM kills from oversized heap |
| Root user in container | Non-root `useradd` + `USER` directive | Docker security best practices (2022+) | Prevents privilege escalation if container is compromised |
| Sequential visitor loop | Partitioned parallel with bounded executor | Java 21 virtual threads | Scales to 40K files without proportional time increase |
| Per-node Cypher MERGE loop | `UNWIND` batch MERGE per transaction | Neo4j performance guidance | 10-20x faster for 10K+ node batches |
| Shell `git clone` in entrypoint | JGit `Git.cloneRepository()` | JGit 5+ / no native dep | No git binary in image; Java exception handling |

**Deprecated/outdated:**
- `org.springframework.boot.loader.JarLauncher`: Spring Boot 3.2+ uses `org.springframework.boot.loader.launch.JarLauncher` (note `launch` sub-package). Older class still works as alias but logs a deprecation warning.
- `-Xms`/`-Xmx` combined with `UseContainerSupport`: Using both produces confusing results; use percentage flags exclusively in containers.

---

## Open Questions

1. **OpenRewrite `JavaTypeCache` thread-safety in parallel parsing**
   - What we know: `JavaTypeCache` is in `org.openrewrite.java.internal`; the class is not documented as thread-safe.
   - What's unclear: Whether sharing one instance across parallel `JavaParser.build()` calls is safe or causes corruption.
   - Recommendation: Start with one `JavaParser` per partition (each with its own `JavaTypeCache`). If heap pressure is a problem, investigate sharing the cache behind a read-write lock. Use separate-accumulators-merge approach to avoid concurrency in accumulator state.

2. **Vaadin frontend build in CI/CD Docker pipeline**
   - What we know: Vaadin `vaadinBuildFrontend` requires access to npm and the node binary; `eclipse-temurin:21-jdk-jammy` does not include them by default.
   - What's unclear: Whether the Vaadin Gradle plugin auto-downloads Node or expects it on PATH.
   - Recommendation: In Wave 1, add `RUN apt-get install -y nodejs npm` to the build stage. Alternatively, use a `node:20-bullseye` base for the build stage and install JDK on top. Verify by running `vaadinBuildFrontend` in isolation.

3. **Clone directory persistence between container restarts**
   - What we know: `/tmp/esmp-source-clone` is ephemeral within the container filesystem.
   - What's unclear: Whether users want cloned source to persist across restarts (to avoid re-cloning on every start) or always re-clone for freshness.
   - Recommendation: Mount `/tmp/esmp-source-clone` as a named Docker volume so clones persist; add a `git pull` refresh on startup rather than a full clone.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test 3.5.11 + Testcontainers |
| Config file | No standalone config — `@SpringBootTest` in each test class |
| Quick run command | `./gradlew test --tests "*.SourceAccessServiceTest" --no-daemon` |
| Full suite command | `./gradlew test --no-daemon` |

### Phase Requirements → Test Map

| ID | Behavior | Test Type | Automated Command | File Exists? |
|----|----------|-----------|-------------------|--------------|
| DOCK-01 | Dockerfile builds successfully | smoke (Docker CLI) | `docker build -t esmp-test .` | Wave 0 |
| DOCK-02 | Spring Boot service starts in Docker and health check passes | integration | `docker compose -f docker-compose.full.yml up -d && curl /actuator/health` | Wave 0 |
| DOCK-03 | `VOLUME_MOUNT` strategy resolves sourceRoot correctly | unit | `./gradlew test --tests "*.SourceAccessServiceTest#testVolumeMountStrategy"` | Wave 0 |
| DOCK-04 | `GITHUB_URL` strategy clones repository and sets sourceRoot | integration | `./gradlew test --tests "*.SourceAccessServiceTest#testGithubUrlStrategy"` | Wave 0 |
| DOCK-05 | Service-to-service Docker networking connects to Neo4j/MySQL/Qdrant | integration | Docker Compose smoke test | Wave 0 |
| SCALE-01 | Parallel extraction produces same node count as sequential for 200+ files | integration | `./gradlew test --tests "*.ParallelExtractionTest"` | Wave 0 |
| SCALE-02 | Batched UNWIND MERGE produces same graph as per-node MERGE | integration | `./gradlew test --tests "*.BatchedPersistenceTest"` | Wave 0 |
| SCALE-03 | SseEmitter progress endpoint emits events during extraction | unit | `./gradlew test --tests "*.ExtractionProgressServiceTest"` | Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "*.SourceAccessServiceTest" --no-daemon`
- **Per wave merge:** `./gradlew test --no-daemon`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/source/application/SourceAccessServiceTest.java` — covers DOCK-03, DOCK-04
- [ ] `src/test/java/com/esmp/extraction/application/ParallelExtractionTest.java` — covers SCALE-01
- [ ] `src/test/java/com/esmp/extraction/application/BatchedPersistenceTest.java` — covers SCALE-02
- [ ] `src/test/java/com/esmp/extraction/application/ExtractionProgressServiceTest.java` — covers SCALE-03
- [ ] Docker smoke tests (shell-level, not JUnit) — covers DOCK-01, DOCK-02, DOCK-05

---

## Sources

### Primary (HIGH confidence)
- Docker official blog — `docker.com/blog/9-tips-for-containerizing-your-spring-boot-code/` — JVM memory flags, layered JAR, non-root user, health check patterns
- Docker multi-stage builds docs — `docs.docker.com/get-started/docker-concepts/building-images/multi-stage-builds/` — build stage patterns
- Spring Boot Externalized Configuration docs — `docs.spring.io/spring-boot/reference/features/external-config.html` — environment variable override, relaxed binding
- Maven Central JGit metadata — `repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit/maven-metadata.xml` — confirmed 7.6.0 as release on 2026-03-19
- Baeldung Server-Sent Events — `baeldung.com/spring-server-sent-events` — SseEmitter pattern

### Secondary (MEDIUM confidence)
- Neo4j performance guide — `neo4j-guide.com/neo4j-slow-import-data-performance/` — UNWIND MERGE batch pattern, batch size guidance (2000 rows)
- JGit cookbook — `github.com/centic9/jgit-cookbook/blob/master/src/main/java/org/dstadler/jgit/porcelain/CloneRemoteRepositoryWithAuthentication.java` — PAT auth pattern
- Medium: Docker multi-stage Gradle build — `medium.com/@cat.edelveis/a-guide-to-docker-multi-stage-builds-for-spring-boot` — Gradle-specific extraction steps
- TheServerSide: Parallel streams with virtual threads — `theserverside.com/tip/How-to-use-parallel-streams-in-Java-with-virtual-threads` — virtual thread executor guidance

### Tertiary (LOW confidence)
- OpenRewrite large file performance issue — `github.com/spring-projects/sts4/issues/825` — evidence of parsing slowness at scale; no official memory optimization docs found
- WebSearch finding: APOC periodic execution for Neo4j — only relevant if APOC is added to Neo4j image; current setup does not include APOC

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — JGit version confirmed from Maven Central; Docker base images verified current; Spring Boot env override is documented behavior
- Architecture: HIGH for Docker/config patterns; MEDIUM for parallel extraction (ExtractionAccumulator thread-safety needs empirical validation)
- Pitfalls: HIGH for Docker networking and JVM memory (multiple official sources); MEDIUM for parallel accumulator merge (derived from code analysis, not external validation)

**Research date:** 2026-03-19
**Valid until:** 2026-06-19 (stable Spring Boot / Docker / JGit ecosystem; JGit version should be re-verified at plan time)
