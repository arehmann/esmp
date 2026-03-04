# Stack Research

**Domain:** Enterprise Code Analysis and Modernization Platform (Java/Spring Boot)
**Researched:** 2026-03-04
**Confidence:** HIGH (core stack) / MEDIUM (version compatibility matrix)

---

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Java | 21 (LTS) | Runtime | LTS release, virtual threads (Project Loom), pattern matching. Spring Boot 3.5 requires Java 17+ but 21 is the current LTS and recommended for new projects. Spring Boot 4 requires Java 24+, so Java 21 keeps forward compatibility without forcing a pre-release JDK. |
| Spring Boot | 3.5.11 | Application framework | Current stable OSS release (Feb 2026). Spring Boot 4.0 is available but targets Spring Framework 7 / Jakarta EE 11 — not yet the standard for new enterprise projects. 3.5.x gives full virtual thread support, GraalVM native image, and Micrometer Observability built in. |
| Spring Data Neo4j | 8.0.3 (via Spring Boot BOM) | Object-graph mapping over Neo4j | Ships with Spring Boot BOM for 3.5.x. Provides @Node/@Relationship annotations, repository abstraction, and Cypher template. Eliminates manual driver session management for domain object persistence. Use for schema-mapped code graph entities; drop to raw driver for complex traversal queries. |
| Neo4j | 5.x (Community, Docker) | Code Knowledge Graph storage | Mature graph DB, Cypher DSL, great Java driver. Community edition sufficient for single-node local/Docker Compose. Spring Data Neo4j 8.0.3 targets Neo4j 5.23+ and Neo4j 2025.x. Provides relationship types (CALLS, DEPENDS_ON, USES_TERM, DEFINES_RULE) and efficient graph traversal. |
| Qdrant | 1.x (Docker) | Vector similarity search | Purpose-built vector DB with grpc + REST, cosine/dot/euclidean support, payload filtering. Docker-first. Spring AI 1.1.x ships a first-class `QdrantVectorStore` with auto-configuration. Outperforms Chroma and Weaviate for production-grade filtering + ANN search at this use case scale. |
| Spring AI | 1.1.2 | AI orchestration, RAG, embeddings | GA since May 2025. Unified abstraction for ChatClient, EmbeddingModel, VectorStore. Ships built-in Qdrant VectorStore, Anthropic chat model, and `TransformersEmbeddingModel` for ONNX local inference. Eliminates manual HTTP client coding against Claude and Qdrant APIs. |
| Anthropic Java SDK | 2.15.0 | Direct Claude API access | Official SDK from Anthropic (GA). Use alongside Spring AI's `AnthropicChatModel` — Spring AI for standard RAG/chat flows; raw SDK for batching, streaming with custom token management, or when Spring AI abstraction doesn't expose needed parameter. |
| OpenRewrite | 8.x (rewrite-maven-plugin 6.29+) | Deterministic AST-based Java/Vaadin transformations | The standard for automated Java migrations. Ships `rewrite-migrate-java` (3.28.0) with Java 8→21 recipes. Custom recipes can model Vaadin 7→24 API replacements. Produces lossless semantic trees — preserves formatting, comments, and whitespace. Used by Moderne at scale for enterprise migrations. |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| spring-ai-transformers (ONNX) | 1.1.2 (via Spring AI BOM) | Local embedding inference using `TransformersEmbeddingModel` | Always — use `all-MiniLM-L6-v2` (default) or `nomic-embed-text-v1.5` ONNX model. Downloads and caches model on first call. Avoids per-token embedding API costs at bulk indexing scale (500k–1M LOC). |
| JavaParser | 3.26+ | Supplemental AST introspection and symbol resolution | When you need fine-grained AST traversal beyond what OpenRewrite exposes (e.g., extracting method signatures for graph nodes, resolving type hierarchies). Lighter than Eclipse JDT for read-only analysis. Include `javaparser-symbol-solver-core` for type resolution. |
| Gradle Tooling API | 8.x | Programmatic Gradle build model extraction | Querying the multi-module Gradle project structure (projects, configurations, dependencies, source sets) without parsing build files. Required for the dependency heatmap and module topology graph. |
| Neo4j Java Driver | 5.28.5 (via Spring Boot BOM) | Raw Cypher execution for complex graph queries | For batch graph writes (bulk import of AST nodes), complex traversal queries that exceed Spring Data Neo4j's repository model, and reactive streaming. Spring Boot auto-configures the driver bean. |
| Micrometer + Prometheus | 1.14.x (via Spring Boot BOM) | Metrics instrumentation | Expose migration progress, indexing throughput, AI call latency, and graph query timing. Spring Boot Actuator auto-configures Micrometer; add `micrometer-registry-prometheus` for Prometheus scrape endpoint. |
| Flyway | 10.x (via Spring Boot BOM) | PostgreSQL schema versioning | For the ESMP's own relational metadata (migration job state, PR tracking, confidence scores). Simple SQL-based migration scripts — preferred over Liquibase for a single-schema solo project where rollback complexity is unnecessary. |
| PostgreSQL (Docker) | 16.x | Relational metadata storage | Job state, migration run history, PR tracking table, confidence score ledger. Use alongside Neo4j (graph) and Qdrant (vectors). Spring Boot `spring-boot-starter-data-jpa` + Flyway covers this. |
| Spring Boot Actuator | 3.5.11 (via Spring Boot BOM) | Health, metrics, info endpoints | Always include. Exposes `/actuator/health`, `/actuator/prometheus`, `/actuator/info` out of the box for Docker Compose health checks and Grafana dashboards. |
| Testcontainers | 1.20.x | Integration testing with real infrastructure | Spin up Neo4j, Qdrant, and PostgreSQL containers for integration tests without mocking. Spring Boot 3.3+ ships `@ServiceConnection` support for Testcontainers — eliminates manual property wiring. |
| Lombok | 1.18.x | Boilerplate reduction | @Builder, @Value, @Slf4j on domain entities and service classes. Keep use conservative — avoid @Data on Neo4j node entities (equality conflicts with OGM). |
| MapStruct | 1.6.x | DTO-to-domain mapping | Compile-time type-safe mappers between AST extraction DTOs, graph nodes, and API response models. Preferred over manual mapping or ModelMapper (reflection-based). |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Docker Compose | Orchestrate Neo4j, Qdrant, PostgreSQL, Prometheus, Grafana locally | Single `compose.yml` at project root. Spring Boot 3.5 has native Docker Compose support — auto-starts services on `./mvnw spring-boot:run`. Pin Neo4j to `neo4j:5.26-community`, Qdrant to `qdrant/qdrant:latest`, PostgreSQL to `postgres:16-alpine`. |
| Gradle (Wrapper) | Build tool for ESMP itself | Use Gradle for ESMP project (matches the target brownfield Gradle build). Avoids Maven/Gradle context-switching when writing Gradle Tooling API integration code. |
| GraalVM (optional) | Native image for CLI analysis tools | Only if CLI sub-tools (e.g., AST extractor as standalone jar) need fast startup. Not required for the main Spring Boot services. |
| Moderne CLI | Running OpenRewrite recipes at scale | `mod` CLI can run recipes against multi-module Gradle projects without embedding OpenRewrite into ESMP itself. Use for recipe development workflow. |

---

## Installation

```xml
<!-- Spring Boot BOM — controls all managed dependency versions -->
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.11</version>
</parent>

<!-- Spring AI BOM — import separately -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>1.1.2</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<!-- Core Spring -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-neo4j</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Spring AI: Anthropic + Qdrant + ONNX local embeddings -->
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-transformers</artifactId>
</dependency>

<!-- Anthropic SDK (direct, for advanced streaming/batching) -->
<dependency>
  <groupId>com.anthropic</groupId>
  <artifactId>anthropic-java</artifactId>
  <version>2.15.0</version>
</dependency>

<!-- Qdrant native Java client (for low-level operations) -->
<dependency>
  <groupId>io.qdrant</groupId>
  <artifactId>client</artifactId>
  <version>1.17.0</version>
</dependency>

<!-- AST Analysis -->
<dependency>
  <groupId>com.github.javaparser</groupId>
  <artifactId>javaparser-symbol-solver-core</artifactId>
  <version>3.26.2</version>
</dependency>
<dependency>
  <groupId>org.gradle</groupId>
  <artifactId>gradle-tooling-api</artifactId>
  <version>8.12</version>
</dependency>

<!-- OpenRewrite (run as Gradle/Maven plugin, not embedded in runtime) -->
<!-- Add to build plugin, not application dependencies -->

<!-- Database -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>

<!-- Observability -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <scope>runtime</scope>
</dependency>

<!-- Utilities -->
<dependency>
  <groupId>org.projectlombok</groupId>
  <artifactId>lombok</artifactId>
  <optional>true</optional>
</dependency>
<dependency>
  <groupId>org.mapstruct</groupId>
  <artifactId>mapstruct</artifactId>
  <version>1.6.3</version>
</dependency>

<!-- Testing -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>neo4j</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
```

---

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| Spring AI 1.1.2 | LangChain4j | LangChain4j is more mature for complex agentic chains and has more community examples. Use if Spring AI's ChatClient/Advisor API proves too limiting for the multi-step orchestration pipeline. Both support Anthropic + Qdrant. |
| Spring AI `QdrantVectorStore` | io.qdrant:client (raw) | Raw Qdrant client when you need multi-vector fields, named vectors, or payload filters that Spring AI's VectorStore abstraction doesn't expose. Use both: Spring AI for standard RAG retrieval, raw client for collection management and advanced payloads. |
| Spring Data Neo4j (OGM) | Neo4j Java Driver (raw only) | Raw driver only when the project's graph schema is too dynamic for @Node/@Relationship annotation mapping (e.g., when graph structure is generated at runtime). For ESMP's well-defined AST schema, SDN is the right level. |
| JavaParser | Eclipse JDT (JDT Core) | Eclipse JDT for full IDE-quality semantic analysis (refactoring, incremental compilation). JDT is heavier and requires Eclipse runtime context. Prefer if JavaParser's symbol solver proves insufficient for resolving generic types or cross-module references in the 500k LOC codebase. |
| Flyway | Liquibase | Liquibase for multi-environment schema management requiring XML/YAML changelogs, rollback DDL generation, and database-agnostic migrations. ESMP is single-environment (Docker Compose), so Flyway's SQL simplicity wins. |
| PostgreSQL | H2 (in-memory) | H2 only for unit testing. Never for persistent metadata. ESMP's migration job state must survive service restarts. |
| `TransformersEmbeddingModel` (ONNX local) | OpenAI text-embedding-3-small | OpenAI embeddings for higher dimensional quality (1536d) if retrieval precision degrades on the local model. Cost: bulk re-indexing 500k LOC at ~$0.02/1M tokens is feasible but adds API dependency. Start local, switch if quality warrants. |

---

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Spring AI 2.0.0-Mx milestones | 2.0.0-M2 (Jan 2026) targets Spring Boot 4.0 / Spring Framework 7.0 — both are cutting-edge non-LTS. Milestone APIs break between releases. Do not build production tooling on milestone software. | Spring AI 1.1.2 (GA, stable API) |
| Spring Boot 4.0.x | Released but not yet the enterprise standard. Requires Java 24+, Jakarta EE 11, and breaks many Spring Boot 3.x autoconfigurations. "Latest" doesn't mean "standard" for a solo enterprise project. | Spring Boot 3.5.11 |
| Embedded Neo4j (neo4j-community embedded) | The embedded Neo4j library is no longer maintained as a standard deployment model. The Neo4j team directs all users to use the Docker/standalone server. Community embedded JAR is removed from active support. | Neo4j 5.x Docker container |
| Pinecone / Weaviate / Chroma | Pinecone is cloud-only (violates local Docker constraint). Chroma lacks robust Java client. Weaviate has heavier operational footprint than Qdrant. ESMP's constraint explicitly lists Qdrant. | Qdrant (Docker) |
| OpenNLP / Stanford CoreNLP for code understanding | General NLP libraries are inappropriate for code semantic analysis — they don't understand Java syntax, AST structures, or Vaadin component semantics. They add complexity without benefit. | OpenRewrite (AST), JavaParser (symbol resolution), Spring AI + Claude (semantic reasoning) |
| Spring Data REST / HATEOAS | Adds hypermedia link overhead to a tool that doesn't need REST discoverability. ESMP dashboard is an internal tool, not a public API. Increases complexity without consumer benefit. | Plain Spring MVC @RestController |
| Swagger/OpenAPI codegen for internal APIs | ESMP is a single-developer internal platform. Codegen adds generated code churn. Use SpringDoc OpenAPI for documentation only if team grows. | SpringDoc `springdoc-openapi-starter-webmvc-ui` for docs only |
| Reactive WebFlux (as primary) | The AST extraction and graph write pipeline is inherently sequential and CPU-bound — the Reactive programming model adds complexity without throughput benefit here. Spring MVC with virtual threads (Java 21) gives equivalent concurrency. | Spring MVC + `spring.threads.virtual.enabled=true` |

---

## Stack Patterns by Variant

**For AST extraction pipeline (batch, CPU-bound):**
- Use Spring Batch or simple `@Scheduled` + `@Async` with virtual thread executor
- Write Neo4j nodes via batch Neo4j driver transactions (not Spring Data Neo4j repositories) for throughput
- Use `TransformersEmbeddingModel` with a dedicated thread pool so ONNX inference doesn't block web threads

**For RAG retrieval (latency-sensitive):**
- Use Spring AI `ChatClient` with `QuestionAnswerAdvisor` for standard RAG flows
- Use `QdrantVectorStore.similaritySearch()` with metadata filters for graph-constrained retrieval
- Cache domain lexicon terms in-memory (ConcurrentHashMap) to avoid repeated Qdrant round-trips for lexicon match

**For AI orchestration (Claude calls):**
- Use Spring AI `AnthropicChatModel` for standard generation
- Use Anthropic Java SDK 2.15.0 directly for streaming responses, token counting, and cache-control headers (Spring AI 1.1 adds prompt caching support for Anthropic)
- Always set `max_tokens` and timeouts — Claude calls are the highest-latency operation in the pipeline

**For OpenRewrite recipe execution:**
- Run OpenRewrite as a Gradle plugin (`id("org.openrewrite.rewrite")`) applied to the target brownfield project — not embedded in ESMP runtime JAR
- ESMP triggers recipe execution via `ProcessBuilder` + Gradle Tooling API, captures stdout/stderr, and parses the resulting diff
- This keeps ESMP clean of transitive OpenRewrite classpath conflicts with the target codebase

---

## Version Compatibility

| Package | Compatible With | Notes |
|---------|-----------------|-------|
| Spring Boot 3.5.11 | Spring AI 1.1.2 | Spring AI 1.1.x explicitly targets Spring Boot 3.x. Spring AI 2.x targets Spring Boot 4.x — do not mix. |
| Spring Boot 3.5.11 | Spring Data Neo4j 8.0.3 | SDN 8.0.x is the current release managed by Spring Data 2025.x BOM, which ships with Spring Boot 3.5. |
| Spring Boot 3.5.11 | Neo4j Java Driver 5.28.5 | Spring Boot BOM pins neo4j-java-driver; as of Spring Boot 3.5.x, driver 5.28.x is the managed version. |
| Spring AI 1.1.2 | Qdrant Java client 1.17.0 | Spring AI's QdrantVectorStore wraps the official io.qdrant:client. Both can coexist in the same classpath. |
| Java 21 | Spring Boot 3.5.11 | Fully supported. Enable virtual threads with `spring.threads.virtual.enabled=true`. |
| OpenRewrite 8.x | Java 21 target codebase | OpenRewrite supports Java 8–25 as target source level. Runs on JDK 17+ at build time. |
| JavaParser 3.26.x | Java 21 source files | JavaParser supports parsing Java 21 syntax including sealed classes, records, pattern matching. |
| MapStruct 1.6.x | Lombok 1.18.x | Both use annotation processors. Declare in `annotationProcessorPaths` in Maven: Lombok first, then MapStruct. Reverse order causes compilation failures. |
| Testcontainers 1.20.x | Spring Boot 3.5 `@ServiceConnection` | `@ServiceConnection` removes all manual property wiring for Neo4j, Qdrant, and PostgreSQL test containers. Requires testcontainers-bom managed by Spring Boot parent BOM. |

---

## Sources

- [Spring Boot releases — spring.io](https://spring.io/blog/2026/02/19/spring-boot-3-5-11-available-now/) — Spring Boot 3.5.11 confirmed as current stable (Feb 2026). HIGH confidence.
- [Spring AI 1.1 GA announcement](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/) — GA since Nov 2025, 1.1.2 patch Dec 2025. HIGH confidence.
- [Spring Data Neo4j getting-started docs](https://docs.spring.io/spring-data/neo4j/reference/getting-started.html) — SDN 8.0.3, targets Neo4j 5.23+. HIGH confidence.
- [Qdrant Java client releases](https://github.com/qdrant/java-client/releases) — io.qdrant:client 1.17.0, Dec 2025. HIGH confidence.
- [Anthropic Java SDK releases](https://github.com/anthropics/anthropic-sdk-java/releases) — v2.15.0 released Feb 2026, includes Claude Sonnet 4.6. HIGH confidence.
- [Spring AI ONNX embeddings docs](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html) — TransformersEmbeddingModel confirmed in Spring AI 1.0.3 and 1.1.x. HIGH confidence.
- [OpenRewrite changelog 8.61.1](https://docs.openrewrite.org/changelog/8-61-1-Release) — 8.61.1 released Aug 2025, rewrite-maven-plugin 6.29+. MEDIUM confidence (exact plugin version number from secondary source).
- [XDEV Vaadin + OpenRewrite modernization article](https://xdev.software/en/news/detail/efficient-modernization-future-proof-upgrades-for-java-and-vaadin-projects) — Custom OpenRewrite recipes for Vaadin exist; XDEV has published Vaadin-specific migration recipes. MEDIUM confidence (third-party).
- [Vaadin version upgrade recipes forum](https://vaadin.com/forum/t/version-upgrade-recipes/166344) — Community confirmation that Vaadin 7→24 OpenRewrite recipes are in progress, not fully published. LOW confidence (forum source). Implication: custom recipe authoring will be required for Vaadin 7 component API.

---

*Stack research for: Enterprise Semantic Modernization Platform (ESMP)*
*Researched: 2026-03-04*
