---
phase: 08-smart-chunking-vector-indexing
verified: 2026-03-05T20:00:00Z
status: passed
score: 9/9 must-haves verified
gaps: []
human_verification:
  - test: "Run POST /api/vector/index against a real Java source tree and confirm Qdrant is populated with non-trivial chunk texts"
    expected: "Qdrant collection code_chunks grows by (classes * 1) + (total methods) points; payloads contain real source snippets with [CLASS:] and [METHOD:] prefixes"
    why_human: "Integration tests use synthetic two-class source trees; production behaviour with a large real codebase (hundreds of classes, ONNX warmup latency, Qdrant upsert throughput) cannot be verified programmatically without running the app"
  - test: "Run POST /api/vector/reindex immediately after a full index with no changes and confirm response shows filesProcessed=0"
    expected: "Response: {filesProcessed:0, chunksIndexed:0, chunksSkipped:N, durationMs:M}"
    why_human: "Test 4 in the integration suite covers this, but end-to-end REST response encoding needs human confirmation against a live stack"
---

# Phase 8: Smart Chunking and Vector Indexing — Verification Report

**Phase Goal:** Enriched code chunks embedded and stored in Qdrant (Smart Chunking and Vector Indexing)
**Verified:** 2026-03-05T20:00:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Spring AI TransformersEmbeddingModel bean is auto-configured and available for injection | VERIFIED | `spring-ai-starter-model-transformers` declared in `build.gradle.kts` line 44; BOM imported at line 26; `application.yml` lines 7-12 configure tokenizer padding; `EmbeddingWarmup` injects `EmbeddingModel` via constructor — no `@Qualifier` needed, auto-configured by starter |
| 2 | QdrantCollectionInitializer creates code_chunks collection with 384-dim cosine vectors and 5 payload indexes on startup | VERIFIED | `QdrantCollectionInitializer` implements `ApplicationRunner`; calls `collectionExistsAsync`, then `createCollectionAsync` with `VectorParams(size=384, Distance.Cosine)`, then `createPayloadIndexAsync` for classFqn, module, stereotype, chunkType (Keyword) and enhancedRiskScore (Float); `QdrantCollectionInitializerTest` (3 Testcontainers tests) asserts collection exists, exactly 5 payload indexes present, dimension = 384 |
| 3 | ChunkingService produces one CLASS_HEADER chunk and N METHOD chunks for a class with N methods | VERIFIED | `ChunkingService.chunkClasses()` iterates class rows, builds one `CLASS_HEADER` chunk per class then one `METHOD` chunk per `methodId` from the enrichment query; `ChunkingServiceTest` Test 1 asserts a 3-method class produces exactly 4 chunks (1+3); `VectorIndexingServiceIntegrationTest` Test 6 asserts OrderService (2 methods) produces exactly 3 points in Qdrant |
| 4 | Each chunk carries enrichment: 1-hop graph neighbours, domain terms, full risk breakdown, vaadin7Detected flag | VERIFIED | `ChunkingService` performs two Neo4j queries per class: enrichment query (DEPENDS_ON callers/deps, IMPLEMENTS, USES_TERM, DECLARES_METHOD) and callees query (CALLS); all 6 risk dimensions copied from class row; vaadin7Detected set when labels intersect VAADIN7_LABELS set; `ChunkingServiceTest` Tests 5-8 verify each dimension individually; integration Test 3 confirms domainTerms payload present in Qdrant point |
| 5 | POST /api/vector/index embeds all chunks and upserts them to Qdrant code_chunks collection | VERIFIED | `VectorIndexController.index()` delegates to `VectorIndexingService.indexAll()`; service calls `chunkingService.chunkClasses()`, batches texts, calls `embeddingModel.embed(texts)` returning `List<float[]>`, builds `PointStruct` with `VectorsFactory.vectors(float[])` and 21-field payload, calls `qdrantClient.upsertAsync()`; returns `IndexStatusResponse`; integration Test 1 confirms Qdrant point count matches reported `chunksIndexed` |
| 6 | POST /api/vector/reindex only re-embeds files whose SHA-256 hash changed since last index | VERIFIED | `VectorIndexingService.reindex()` scrolls Qdrant with `WithPayloadSelectorFactory.include(["classFqn","contentHash"])`, builds `Map<fqn, hash>`, groups current chunks by class, skips classes with matching hash; integration Test 4 confirms `filesProcessed=0` when hashes unchanged; integration Test 5 confirms `filesProcessed=1` when one class's `contentHash` updated in Neo4j |
| 7 | Qdrant points have 384-dim float vectors and enriched payloads queryable by similarity | VERIFIED | `buildPointStruct()` uses `VectorsFactory.vectors(float[])` with 384-element arrays from all-MiniLM-L6-v2; collection configured with 384-dim cosine distance (verified by `QdrantCollectionInitializerTest`); integration Test 7 performs `qdrantClient.searchAsync()` with an embedded query vector and asserts non-empty scored results with classFqn payload |
| 8 | Re-running full index on unchanged files is idempotent (same point IDs, updated payloads) | VERIFIED | `ChunkIdGenerator.chunkId(fqn, methodSig)` produces deterministic UUID v5 via SHA-1 + DNS namespace; `VectorIndexingService` uses `PointIdFactory.id(chunk.pointId())` — Qdrant upsert updates existing points by ID; integration Test 2 calls `indexAll` twice and confirms point count does not increase |
| 9 | Validation queries check vector index population and consistency | VERIFIED | `VectorValidationQueryRegistry` extends `ValidationQueryRegistry` with 3 queries: VECTOR_INDEX_POPULATED (count JavaClass with non-null sourceFilePath), VECTOR_CHUNKS_HAVE_CONTENT_HASH (count missing contentHash — must be 0), VECTOR_SOURCE_FILES_ACCESSIBLE (informational count + sample); registered as `@Component`, auto-discovered by `ValidationService`'s `List<ValidationQueryRegistry>` |

**Score:** 9/9 truths verified

---

### Required Artifacts

#### Plan 01 Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/vector/model/CodeChunk.java` | Domain record for enriched code chunk | VERIFIED | `public record CodeChunk(UUID pointId, ChunkType chunkType, String classFqn, String methodId, String classHeaderId, String text, String module, String stereotype, String contentHash, double structuralRiskScore, double enhancedRiskScore, double domainCriticality, double securitySensitivity, double financialInvolvement, double businessRuleDensity, boolean vaadin7Detected, List<String> vaadinPatterns, List<String> callers, List<String> callees, List<String> dependencies, List<String> implementors, List<DomainTermRef> domainTerms)` — 22 fields, 81 lines |
| `src/main/java/com/esmp/vector/config/QdrantCollectionInitializer.java` | Startup Qdrant collection + index creation | VERIFIED | `@Component implements ApplicationRunner`; `run()` calls `collectionExistsAsync`, `createCollectionAsync` (VectorParams 384/Cosine), and `createPayloadIndexAsync` ×5; 87 lines of substantive logic |
| `src/main/java/com/esmp/vector/application/ChunkingService.java` | Chunking + enrichment service reading Neo4j + source files | VERIFIED | `@Service class ChunkingService`; 422 lines; `chunkClasses()` executes 3 Cypher queries per class, reads source files, builds CLASS_HEADER and METHOD chunks with all enrichment fields |
| `src/main/java/com/esmp/vector/config/VectorConfig.java` | Configurable vector settings | VERIFIED | `@Component @ConfigurationProperties(prefix = "esmp.vector")` with collectionName, vectorDimension, batchSize fields and getters/setters |
| `src/main/java/com/esmp/vector/model/ChunkType.java` | Chunk type enum | VERIFIED | `public enum ChunkType { CLASS_HEADER, METHOD }` |
| `src/main/java/com/esmp/vector/model/DomainTermRef.java` | Domain term reference record | VERIFIED | `public record DomainTermRef(String termId, String displayName)` |
| `src/main/java/com/esmp/vector/util/ChunkIdGenerator.java` | Deterministic UUID v5 generator | VERIFIED | RFC 4122 UUID v5 via SHA-1 + DNS namespace `6ba7b810-9dad-11d1-80b4-00c04fd430c8`; `chunkId(classFqn, methodSig)` is the public entry point |
| `src/main/java/com/esmp/vector/application/EmbeddingWarmup.java` | ONNX model pre-loader | VERIFIED | `@Component` with `@EventListener(ApplicationReadyEvent.class)`; calls `embeddingModel.embed(List.of("warmup"))` with timing log |
| `src/test/java/com/esmp/vector/config/QdrantCollectionInitializerTest.java` | Integration test: collection + indexes | VERIFIED | 3 `@SpringBootTest(MOCK)` + Testcontainers tests: `collectionExistsAfterStartup`, `collectionHasFivePayloadIndexes`, `collectionHasCorrectVectorDimension` (asserts 384L) |
| `src/test/java/com/esmp/vector/application/ChunkingServiceTest.java` | Unit tests: 10 cases | VERIFIED | 10 tests with mocked Neo4jClient (RETURNS_DEEP_STUBS); covers chunk count, text format, classHeaderId linkage, risk scores, neighbors, domain terms, vaadin7 detection, null/missing file skipping |

#### Plan 02 Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/esmp/vector/application/VectorIndexingService.java` | Embedding + Qdrant upsert + incremental reindex | VERIFIED | `@Service class VectorIndexingService`; 347 lines; `indexAll()`, `reindex()`, `deleteByClass()` methods fully implemented; `embedAndUpsert()` calls `embeddingModel.embed()` and `qdrantClient.upsertAsync()`; `scrollStoredHashes()` paginates via `scrollAsync` |
| `src/main/java/com/esmp/vector/api/VectorIndexController.java` | REST endpoints POST /api/vector/index and /reindex | VERIFIED | `@RestController @RequestMapping("/api/vector")`; `POST /index` calls `indexAll`, `POST /reindex` calls `reindex`; both return `ResponseEntity<IndexStatusResponse>` or 500 on error |
| `src/main/java/com/esmp/vector/api/IndexStatusResponse.java` | Response record | VERIFIED | `public record IndexStatusResponse(int filesProcessed, int chunksIndexed, int chunksSkipped, long durationMs)` |
| `src/main/java/com/esmp/vector/validation/VectorValidationQueryRegistry.java` | Vector validation queries | VERIFIED | `@Component extends ValidationQueryRegistry`; 3 queries in constructor; VECTOR_INDEX_POPULATED (WARNING), VECTOR_CHUNKS_HAVE_CONTENT_HASH (ERROR), VECTOR_SOURCE_FILES_ACCESSIBLE (WARNING) |
| `src/test/java/com/esmp/vector/application/VectorIndexingServiceIntegrationTest.java` | Integration tests: 7 cases | VERIFIED | 543 lines; `@SpringBootTest(MOCK)` + 3 Testcontainers (Neo4j, MySQL, Qdrant); 7 tests covering all VEC-01..VEC-04 scenarios |

---

### Key Link Verification

#### Plan 01 Key Links

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `QdrantCollectionInitializer` | `QdrantClient` bean | constructor injection | VERIFIED | `private final QdrantClient qdrantClient` injected in constructor; `qdrantClient.collectionExistsAsync(collection)` called at line 47 |
| `ChunkingService` | `Neo4jClient` | 1-hop neighbor Cypher queries | VERIFIED | `neo4jClient.query(cypher).fetch().all()` at line 185; `neo4jClient.query(cypher).bind(fqn).to("fqn").fetch().one()` at lines 201 and 213 |
| `ChunkingService` | `CodeChunk` record | produces `List<CodeChunk>` | VERIFIED | Method signature `public List<CodeChunk> chunkClasses(String sourceRoot)`; `new CodeChunk(...)` constructed at lines 143-148 (header) and 157-163 (method) |

#### Plan 02 Key Links

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `VectorIndexingService` | `EmbeddingModel` | Spring AI injection | VERIFIED | `private final EmbeddingModel embeddingModel` injected via constructor; `embeddingModel.embed(texts)` called in `embedAndUpsert()` at line 208 |
| `VectorIndexingService` | `QdrantClient` | upsertAsync | VERIFIED | `qdrantClient.upsertAsync(vectorConfig.getCollectionName(), points).get(30, SECONDS)` at line 217 |
| `VectorIndexingService` | `ChunkingService` | produces chunks for embedding | VERIFIED | `chunkingService.chunkClasses(sourceRoot)` called in both `indexAll()` (line 82) and `reindex()` (line 125) |
| `VectorIndexController` | `VectorIndexingService` | REST trigger | VERIFIED | `vectorIndexingService.indexAll(sourceRoot)` at line 49; `vectorIndexingService.reindex(sourceRoot)` at line 66 |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| VEC-01 | Plan 01 + Plan 02 | System chunks code by semantic unit (class, service method, validation block, UI block, business rule) | SATISFIED | `ChunkingService` produces CLASS_HEADER (one per class) and METHOD (one per declared method) chunks; verified by ChunkingServiceTest Test 1 (3-method class → 4 chunks) and VectorIndexingServiceIntegrationTest Test 6 (2-method class → 3 Qdrant points) |
| VEC-02 | Plan 01 | Each chunk is enriched with graph neighbours, domain terms, risk score (structural + domain-aware), and migration state | SATISFIED | `CodeChunk` record carries callers, callees, dependencies, implementors (1-hop graph), domainTerms (USES_TERM), structuralRiskScore, enhancedRiskScore, domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity (risk), and vaadin7Detected (migration state); ChunkingServiceTest Tests 5-8 verify each category; integration Test 3 confirms domainTerms present in Qdrant payload |
| VEC-03 | Plan 01 + Plan 02 | System indexes enriched chunks into Qdrant using open-source embedding model | SATISFIED | Spring AI `spring-ai-starter-model-transformers` adds all-MiniLM-L6-v2 ONNX model; `VectorIndexingService.embedAndUpsert()` calls `embeddingModel.embed()` and `qdrantClient.upsertAsync()`; integration Tests 1, 2, 7 confirm points in Qdrant with correct 384-dim collection config and similarity search returning results |
| VEC-04 | Plan 02 | System supports incremental re-indexing of changed files | SATISFIED | `VectorIndexingService.reindex()` scrolls Qdrant for stored `contentHash` values per class, compares against current Neo4j hashes, re-embeds only classes with changed hash; integration Test 4 confirms zero files re-processed when unchanged; integration Test 5 confirms exactly 1 file re-processed after hash update |

All 4 VEC requirements: SATISFIED

No orphaned requirements detected. REQUIREMENTS.md maps VEC-01 through VEC-04 exclusively to Phase 8, and both plans together claim all four.

---

### Anti-Patterns Found

Scanned all 12 production files and 3 test files created in this phase.

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| None | — | — | — |

No TODO/FIXME/placeholder comments found. No empty implementations (`return null`, `return {}`, `return []`) in production code. No stub handlers. `EmbeddingWarmup.warmUp()` catches exceptions with a `log.warn` and continues — this is intentional non-fatal handling documented in the method Javadoc.

**Noted deviation (non-blocking):** The SUMMARY for Plan 02 lists pre-existing test failures in `LinkingServiceIntegrationTest` (9 tests), `VirtualThreadsTest` (3 tests), and `ValidationControllerIntegrationTest` (1 test) caused by a Vaadin `WebEnvironment.NONE` regression from Phase 5. These are confirmed pre-existing and logged in `deferred-items.md`. They do not affect Phase 8 goal achievement.

---

### Human Verification Required

#### 1. Full index against a real Java source tree

**Test:** Run `POST /api/vector/index?sourceRoot=/path/to/real/src` against a running stack (Docker Compose with Neo4j, Qdrant, Spring Boot) after extracting real project AST.
**Expected:** Qdrant `code_chunks` collection grows by at least (number of classes with source paths) points. Point payloads contain real Java source snippets with `[CLASS:]` and `[METHOD:]` prefixes. No OOM or ONNX model download errors in the logs.
**Why human:** Integration tests use two synthetic 10-line Java files. Production behaviour with hundreds of classes, ONNX cold-start (model auto-download on first run), and Qdrant network throughput cannot be verified programmatically without executing the application.

#### 2. Incremental reindex REST response format

**Test:** After a full index, immediately call `POST /api/vector/reindex?sourceRoot=...` and inspect the HTTP 200 JSON response body.
**Expected:** `{"filesProcessed":0,"chunksIndexed":0,"chunksSkipped":N,"durationMs":M}` with `N > 0`.
**Why human:** The integration test asserts `IndexStatusResponse` field values in Java. The REST layer serialisation (Jackson record → JSON field names, camelCase vs snake_case) has not been separately verified against the actual HTTP response.

---

### Gaps Summary

No gaps. All 9 observable truths verified, all 12 production artifacts substantive and wired, all 4 key links in Plan 01 and Plan 02 confirmed connected. All VEC-01 through VEC-04 requirements are satisfied with implementation evidence at multiple levels (code, tests, commits).

The phase delivers its stated goal: enriched code chunks embedded via all-MiniLM-L6-v2 and stored in Qdrant's `code_chunks` collection, with full REST API for indexing and incremental reindex.

---

_Verified: 2026-03-05T20:00:00Z_
_Verifier: Claude (gsd-verifier)_
