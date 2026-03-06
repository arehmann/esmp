---
phase: 08-smart-chunking-vector-indexing
plan: 01
subsystem: vector
tags: [spring-ai, qdrant, onnx, all-MiniLM-L6-v2, neo4j, chunking, embeddings]

# Dependency graph
requires:
  - phase: 07-domain-aware-risk-analysis
    provides: enhancedRiskScore, domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity on ClassNode
  - phase: 05-domain-lexicon
    provides: BusinessTerm nodes + USES_TERM edges consumed by ChunkingService
  - phase: 03-code-knowledge-graph
    provides: DEPENDS_ON, IMPLEMENTS, CALLS, DECLARES_METHOD edges for 1-hop neighbor enrichment
  - phase: 01-infrastructure
    provides: QdrantClient bean, Docker Compose Qdrant container at port 6334

provides:
  - Spring AI TransformersEmbeddingModel auto-configured (all-MiniLM-L6-v2, 384-dim)
  - CodeChunk domain record with full enrichment payload
  - code_chunks Qdrant collection with 384-dim cosine vectors and 5 payload indexes at startup
  - ChunkingService producing CLASS_HEADER + METHOD chunks from Neo4j + source files
  - VectorConfig @ConfigurationProperties for collection-name, vector-dimension, batch-size
  - ChunkIdGenerator for deterministic UUID v5 point IDs (idempotent upserts)
  - QdrantCollectionInitializer ApplicationRunner for startup schema creation
  - EmbeddingWarmup ApplicationReadyEvent listener for ONNX model pre-loading

affects:
  - 08-smart-chunking-vector-indexing/02 (VectorIndexingService builds on CodeChunk + QdrantClient)

# Tech tracking
tech-stack:
  added:
    - spring-ai 1.1.2 (spring-ai-bom + spring-ai-starter-model-transformers)
    - ONNX runtime 1.20.0 (transitive via spring-ai-transformers)
    - all-MiniLM-L6-v2 ONNX model (auto-downloaded by Spring AI)
  patterns:
    - ApplicationRunner for startup Qdrant collection + payload index creation (mirrors Neo4jSchemaInitializer)
    - ApplicationReadyEvent listener for ONNX model warmup
    - RFC 4122 UUID v5 (SHA-1 + DNS namespace) for deterministic point IDs
    - RETURNS_DEEP_STUBS Mockito pattern for fluent Neo4jClient query chain mocking
    - CLASS_HEADER + METHOD dual-chunk pattern per class

key-files:
  created:
    - src/main/java/com/esmp/vector/model/CodeChunk.java
    - src/main/java/com/esmp/vector/model/ChunkType.java
    - src/main/java/com/esmp/vector/model/DomainTermRef.java
    - src/main/java/com/esmp/vector/config/VectorConfig.java
    - src/main/java/com/esmp/vector/config/QdrantCollectionInitializer.java
    - src/main/java/com/esmp/vector/util/ChunkIdGenerator.java
    - src/main/java/com/esmp/vector/application/ChunkingService.java
    - src/main/java/com/esmp/vector/application/EmbeddingWarmup.java
    - src/test/java/com/esmp/vector/config/QdrantCollectionInitializerTest.java
    - src/test/java/com/esmp/vector/application/ChunkingServiceTest.java
  modified:
    - gradle/libs.versions.toml (added spring-ai 1.1.2 version + 2 library entries)
    - build.gradle.kts (added spring-ai BOM import + starter-model-transformers dependency)
    - src/main/resources/application.yml (spring.ai.embedding.transformer config + esmp.vector config)

key-decisions:
  - "Spring AI 1.1.2 spring-ai-starter-model-transformers pulls ONNX runtime and all-MiniLM-L6-v2 automatically — no manual model download needed"
  - "QdrantCollectionInitializer uses RETURNS_DEEP_STUBS in Testcontainers integration test (WebEnvironment.MOCK required — WebEnvironment.NONE fails with Vaadin SpringBootAutoConfiguration)"
  - "ChunkingService separates enrichment query (5 OPTIONAL MATCHes) from callees query — both use .bind(fqn).to('fqn').fetch().one(); Mockito RETURNS_DEEP_STUBS thenReturn chaining provides first/second sequential responses"
  - "UUID v5 uses DNS namespace 6ba7b810-9dad-11d1-80b4-00c04fd430c8 with classFqn#methodSignature as name — guarantees stable idempotent point IDs across re-indexing runs"
  - "PayloadSchemaType.Float used for enhancedRiskScore index (not Integer) — Qdrant Float type supports range queries needed for risk-filtered similarity search"

patterns-established:
  - "Chunk text format: [CLASS: SimpleName] [PACKAGE: pkg] for headers; [CLASS: SimpleName] [METHOD: signature] for methods"
  - "1-hop enrichment is class-level (shared across header + all method chunks from same class) — avoids N+1 queries per method"
  - "Source extraction uses balanced-brace scanning for method bodies — simple but effective for well-formed Java source"

requirements-completed:
  - VEC-01
  - VEC-02
  - VEC-03

# Metrics
duration: 15min
completed: 2026-03-05
---

# Phase 8 Plan 01: Smart Chunking Foundation Summary

**Spring AI TransformersEmbeddingModel with all-MiniLM-L6-v2 ONNX, CodeChunk record with 22-field enrichment payload, and ChunkingService producing CLASS_HEADER + N METHOD chunks enriched from Neo4j graph neighbors, domain terms, and risk scores**

## Performance

- **Duration:** 15 min
- **Started:** 2026-03-05T17:36:08Z
- **Completed:** 2026-03-05T17:51:00Z
- **Tasks:** 2
- **Files modified:** 13

## Accomplishments

- Spring AI 1.1.2 BOM + `spring-ai-starter-model-transformers` added; ONNX runtime 1.20.0 pulled transitively with all-MiniLM-L6-v2 auto-downloaded
- `code_chunks` Qdrant collection created at startup (384-dim cosine distance) with 5 payload indexes (classFqn, module, stereotype, chunkType, enhancedRiskScore) — verified by 3-test integration suite with Testcontainers Qdrant
- ChunkingService reads Neo4j for all JavaClass nodes with source paths, fetches 1-hop neighbors (DEPENDS_ON, IMPLEMENTS, CALLS, USES_TERM, DECLARES_METHOD) and produces enriched CLASS_HEADER + METHOD chunks — 10 unit tests green

## Task Commits

Each task was committed atomically:

1. **Task 1: Spring AI dependency, domain model, Qdrant initializer, VectorConfig** - `5d0c6fc` (feat)
2. **Task 2: ChunkingService with Neo4j enrichment and source file reading** - `6918e95` (feat)

## Files Created/Modified

- `gradle/libs.versions.toml` - Added spring-ai 1.1.2 version, spring-ai-bom and spring-ai-starter-transformers library entries
- `build.gradle.kts` - Added Spring AI BOM to dependencyManagement, spring-ai-starter-transformers to dependencies
- `src/main/resources/application.yml` - Added spring.ai.embedding.transformer tokenizer config + esmp.vector section
- `src/main/java/com/esmp/vector/model/ChunkType.java` - Enum: CLASS_HEADER, METHOD
- `src/main/java/com/esmp/vector/model/DomainTermRef.java` - Record: termId, displayName for USES_TERM references
- `src/main/java/com/esmp/vector/model/CodeChunk.java` - 22-field record with UUID pointId, enrichment payload, risk scores, Vaadin state, graph neighbors
- `src/main/java/com/esmp/vector/config/VectorConfig.java` - @ConfigurationProperties(prefix=esmp.vector): collectionName, vectorDimension, batchSize
- `src/main/java/com/esmp/vector/config/QdrantCollectionInitializer.java` - ApplicationRunner: creates code_chunks collection + 5 payload indexes idempotently at startup
- `src/main/java/com/esmp/vector/util/ChunkIdGenerator.java` - Static UUID v5 generator using SHA-1 + DNS namespace
- `src/main/java/com/esmp/vector/application/ChunkingService.java` - @Service: Neo4j → source files → enriched List<CodeChunk>
- `src/main/java/com/esmp/vector/application/EmbeddingWarmup.java` - ApplicationReadyEvent: pre-loads ONNX model to avoid cold-start
- `src/test/java/com/esmp/vector/config/QdrantCollectionInitializerTest.java` - 3 Testcontainers integration tests verifying collection + indexes + vector dimension
- `src/test/java/com/esmp/vector/application/ChunkingServiceTest.java` - 10 unit tests covering chunk count, text format, classHeaderId linkage, risk scores, neighbors, domain terms, vaadin detection, null/missing file skipping

## Decisions Made

- Spring AI 1.1.2 BOM imported alongside Vaadin BOM in `dependencyManagement` — both BOMs coexist without conflict
- `WebEnvironment.MOCK` required for QdrantCollectionInitializerTest — `NONE` fails because Vaadin's `SpringBootAutoConfiguration` injects `WebApplicationContext`
- Mockito `RETURNS_DEEP_STUBS` used to mock Neo4jClient fluent chain; `thenReturn(...).thenReturn(...)` chaining provides sequential responses for enrichment vs callees queries
- PayloadSchemaType.Float chosen for enhancedRiskScore index to support range queries (not Integer)
- UUID v5 with DNS namespace chosen for deterministic point IDs matching Neo4j MERGE-by-business-key pattern

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- `WebEnvironment.NONE` caused Vaadin context startup failure in integration test (auto-fixed: switched to `WebEnvironment.MOCK`, consistent with all other integration tests in the codebase)
- `Map.of()` limited to 10 key-value pairs — replaced with `HashMap` for 12-field class row map in test helper (auto-fixed during compilation)

## Next Phase Readiness

- ChunkingService ready to feed into Plan 02 VectorIndexingService for embedding + Qdrant upsert
- EmbeddingModel bean auto-configured; EmbeddingWarmup ensures ONNX model loaded before first indexing call
- code_chunks collection and payload indexes present at startup — VectorIndexingService can upsert immediately
- No blockers

---
*Phase: 08-smart-chunking-vector-indexing*
*Completed: 2026-03-05*
