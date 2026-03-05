# Phase 8: Smart Chunking and Vector Indexing - Research

**Researched:** 2026-03-05
**Domain:** Qdrant vector database, Spring AI ONNX embeddings, semantic code chunking
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Chunking strategy:**
- Method-level granularity: one chunk per method, plus one "class header" chunk per class (package, imports, fields, annotations, class javadoc)
- Class header is a separate chunk — method chunks reference it via classHeaderId, RAG can retrieve both when needed
- Methods only — inner classes are already separate ClassNodes in the graph (Phase 3), they get their own header + method chunks
- Chunking happens as a post-extraction step (separate ChunkingService), not during the visitor pass — enrichment data (neighbors, risk scores) only exists after the full extraction pipeline completes
- Follows the RiskService pattern: decoupled from extraction, can re-chunk without re-extracting

**Enrichment payload:**
- 1-hop graph neighbors: direct callers, callees, dependencies, implementors (FQN lists). Deeper expansion deferred to Phase 11 RAG
- Full risk breakdown: structuralRiskScore, enhancedRiskScore, domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity — all already on ClassNode
- Domain terms: list of {termId, displayName} pairs from USES_TERM edges — lightweight, full details fetchable from Neo4j
- Migration state: Vaadin 7 API detection based on existing stereotype labels (VaadinView, VaadinComponent, VaadinDataBinding from Phase 2) — `vaadin7Detected: true/false` + list of detected patterns
- Both risk scores included for downstream flexibility (RAG can filter by either)

**Incremental re-indexing:**
- Content hash detection: SHA-256 hash of each source file, stored in Qdrant payload per chunk
- On re-index, query Qdrant for existing hashes, compare against current file hashes, only re-chunk and re-embed files whose hash changed
- Changed file only — no cascade to neighbor chunks. Neighbor enrichment for other files stays stale until they're re-indexed. Phase 10 (Continuous Indexing) can add cascade logic
- Separate REST endpoints: `POST /api/vector/index` (full) and `POST /api/vector/reindex` (incremental) in `com.esmp.vector` package, decoupled from extraction trigger

**Qdrant collection design:**
- Single collection (`code_chunks`) for all chunk types — payload fields for filtered queries
- Deterministic UUID v5 point IDs from namespace + classFqn + methodSignature — enables idempotent upsert, matches the MERGE-by-business-key pattern in Neo4j
- Startup initialization: create collection with vector config and payload indexes at app startup (like Neo4jSchemaInitializer pattern) — fail fast if misconfigured
- Payload indexes: classFqn (keyword), module (keyword), stereotype (keyword), chunkType (keyword), enhancedRiskScore (float range)

### Claude's Discretion
- ONNX runtime configuration and model loading strategy
- Exact chunk text formatting (how method source + context is concatenated for embedding)
- Qdrant vector dimension and distance metric (determined by all-MiniLM-L6-v2 model: 384 dimensions, cosine)
- ChunkingService and VectorIndexingService internal design
- Vector-specific validation queries for a VectorValidationQueryRegistry
- Test strategy (integration tests with Testcontainers Qdrant)
- Batch size for Qdrant upsert operations
- Error handling for embedding failures on individual chunks

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| VEC-01 | System chunks code by semantic unit (class, service method, validation block, UI block, business rule) | Method-level chunking + class header chunk pattern; ChunkingService reads source via ClassNode.sourceFilePath |
| VEC-02 | Each chunk is enriched with graph neighbors, domain terms, risk score (structural + domain-aware), and migration state | All properties exist on ClassNode/MethodNode in Neo4j; 1-hop neighbor Cypher via Neo4jClient; USES_TERM edges for domain terms; extraLabels for Vaadin stereotypes |
| VEC-03 | System indexes enriched chunks into Qdrant using open-source embedding model | Spring AI TransformersEmbeddingModel (all-MiniLM-L6-v2, 384 dims, cosine); Qdrant Java client 1.13.0 upsert with UUID point IDs |
| VEC-04 | System supports incremental re-indexing of changed files | SHA-256 hash stored in Qdrant payload; scroll API to retrieve existing hashes; compare and upsert only changed files; dedicated POST /api/vector/reindex endpoint |
</phase_requirements>

---

## Summary

Phase 8 adds a semantic vector indexing layer on top of the existing graph and risk infrastructure. The work has three well-defined sub-concerns: (1) chunking Java source into semantic units using the OpenRewrite AST already available from Phase 2, (2) enriching each chunk with graph data already materialized in Neo4j from Phases 3-7, and (3) storing embeddings in Qdrant using the existing QdrantClient bean.

The embedding technology is resolved: Spring AI 1.1.2 ships `spring-ai-starter-model-transformers` which bundles the all-MiniLM-L6-v2 ONNX model as a classpath resource, provides Spring Boot auto-configuration via `TransformersEmbeddingModel`, and outputs 384-dimension float vectors. The Qdrant Java client 1.13.0 is already on the classpath. Idempotent upsert uses UUID v5 point IDs derived from `{namespace}:{classFqn}#{methodSignature}`. The incremental re-index uses Qdrant scroll to retrieve stored `contentHash` values and compares against current SHA-256 file hashes, re-embedding only changed files.

The existing `RiskService` pattern (post-extraction, separate service, Neo4jClient for Cypher, `@ConfigurationProperties` for weights) is the direct template for `ChunkingService` and `VectorIndexingService`. The `Neo4jSchemaInitializer` (ApplicationRunner, IF NOT EXISTS) is the direct template for `QdrantCollectionInitializer`. The existing Testcontainers integration test pattern (GenericContainer for Qdrant, DynamicPropertySource) is already proven in `RiskServiceIntegrationTest`.

**Primary recommendation:** Use `spring-ai-starter-model-transformers` for zero-config ONNX embedding (model bundled in JAR), implement ChunkingService as a Neo4jClient-driven service that reads source files and builds enriched chunk records, then pass to VectorIndexingService for batch upsert into the `code_chunks` Qdrant collection. No new Testcontainers dependencies are needed — Qdrant is already run as a `GenericContainer` in existing tests.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-ai-starter-model-transformers` | 1.1.2 (matches project Spring AI BOM) | ONNX embedding via TransformersEmbeddingModel | Bundles all-MiniLM-L6-v2 ONNX + tokenizer on classpath; Spring Boot auto-config; 384-dim output matches locked decision |
| `io.qdrant:client` | 1.13.0 (already in libs.versions.toml) | Qdrant gRPC client | Already declared; `QdrantClient` bean already configured in `QdrantConfig.java` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Java `MessageDigest` (SHA-1) | JDK 21 built-in | UUID v5 deterministic point ID generation | No extra dependency needed; use SHA-1 + version/variant bit manipulation per RFC 4122 |
| Java `MessageDigest` (SHA-256) | JDK 21 built-in | Content hash for incremental re-index detection | Already used in ClassNode.contentHash (established pattern) |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `spring-ai-starter-model-transformers` | Raw ONNX Runtime Java API | Spring AI auto-config eliminates setup boilerplate; model already bundled; alternative requires manual session/env lifecycle management |
| `spring-ai-starter-model-transformers` | LangChain4j `langchain4j-embeddings-all-minilm-l6-v2` | LangChain4j works but introduces a second AI framework alongside Spring AI 1.1.2 which is already a project dependency (STATE.md: Spring AI 1.1.2) |
| `GenericContainer` (existing) | `org.testcontainers:testcontainers-qdrant:2.0.2` | The dedicated Qdrant module exists but adds a new dependency; existing tests already use GenericContainer("qdrant/qdrant") pattern which works identically |

**Installation:**
```gradle
// Add to build.gradle.kts dependencies block
implementation("org.springframework.ai:spring-ai-starter-model-transformers")
```

Note: No version needed — managed by Spring AI BOM (already in `dependencyManagement` via Spring Boot 3.5.11 which aligns with Spring AI 1.1.2).

If the Spring AI BOM is not already pulling in `spring-ai-starter-model-transformers`, add explicit version:
```gradle
implementation("org.springframework.ai:spring-ai-starter-model-transformers:1.1.2")
```

---

## Architecture Patterns

### Recommended Project Structure
```
src/main/java/com/esmp/
└── vector/                          # Package reserved at project init (Phase 1)
    ├── api/
    │   ├── VectorIndexController.java    # POST /api/vector/index, POST /api/vector/reindex
    │   ├── IndexStatusResponse.java      # Response record: chunksIndexed, filesProcessed, durationMs
    │   └── ChunkSearchResponse.java      # Future: query results (Phase 11)
    ├── application/
    │   ├── ChunkingService.java          # Reads Neo4j + source files, produces CodeChunk records
    │   └── VectorIndexingService.java    # Embeds chunks, upserts to Qdrant, manages hashes
    ├── config/
    │   ├── QdrantCollectionInitializer.java  # ApplicationRunner: create collection + payload indexes
    │   └── VectorConfig.java                 # @ConfigurationProperties(prefix="esmp.vector")
    ├── model/
    │   └── CodeChunk.java               # Domain record: chunkId, chunkType, classFqn, methodId, text, enrichment
    └── validation/
        └── VectorValidationQueryRegistry.java  # 3 Qdrant-facing validation queries
```

### Pattern 1: Startup Collection Initialization (QdrantCollectionInitializer)
**What:** ApplicationRunner that creates the `code_chunks` collection and payload indexes at startup if they don't already exist. Mirrors Neo4jSchemaInitializer exactly.
**When to use:** Ensures fail-fast behavior if Qdrant is misconfigured; idempotent on restarts.
**Example:**
```java
// Source: Mirrors Neo4jSchemaInitializer.java (com.esmp.extraction.config)
@Component
public class QdrantCollectionInitializer implements ApplicationRunner {

  private static final String COLLECTION = "code_chunks";
  private static final int VECTOR_DIM = 384;
  private final QdrantClient qdrantClient;

  @Override
  public void run(ApplicationArguments args) throws Exception {
    boolean exists = qdrantClient.collectionExistsAsync(COLLECTION).get(5, TimeUnit.SECONDS);
    if (!exists) {
      qdrantClient.createCollectionAsync(
          COLLECTION,
          VectorParams.newBuilder()
              .setDistance(Distance.Cosine)
              .setSize(VECTOR_DIM)
              .build()
      ).get(10, TimeUnit.SECONDS);

      // Create payload indexes for filtered queries
      createPayloadIndex("classFqn", PayloadSchemaType.Keyword);
      createPayloadIndex("module", PayloadSchemaType.Keyword);
      createPayloadIndex("stereotype", PayloadSchemaType.Keyword);
      createPayloadIndex("chunkType", PayloadSchemaType.Keyword);
      createPayloadIndex("enhancedRiskScore", PayloadSchemaType.Float);
    }
  }
}
```

### Pattern 2: UUID v5 Deterministic Point IDs
**What:** RFC 4122 UUID v5 (SHA-1 based) from a fixed namespace UUID + business key string, giving the same point ID for the same chunk across re-indexing runs. Enables idempotent upsert.
**When to use:** Every PointStruct creation in VectorIndexingService.
**Example:**
```java
// No external library needed — standard JDK MessageDigest
private static final UUID CHUNK_NAMESPACE =
    UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"); // DNS namespace per RFC 4122

public static UUID chunkId(String classFqn, String methodSignature) {
    String name = classFqn + "#" + methodSignature;
    try {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        // Prepend namespace bytes (big-endian)
        sha1.update(uuidToBytes(CHUNK_NAMESPACE));
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] digest = sha1.digest();
        // Set version 5 and variant bits per RFC 4122
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x50); // version = 5
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80); // variant = RFC 4122
        ByteBuffer bb = ByteBuffer.wrap(digest);
        return new UUID(bb.getLong(), bb.getLong());
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-1 not available", e);
    }
}
```

### Pattern 3: Qdrant Upsert with Enriched Payload
**What:** Build PointStruct with UUID id, float[] embedding, and enriched payload map. Use `upsertAsync` for idempotent write.
**When to use:** VectorIndexingService.indexChunks() and re-indexing.
**Example:**
```java
// Source: Qdrant Java client 1.13.0 (io.qdrant:client)
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

List<PointStruct> points = chunks.stream()
    .map(chunk -> {
        float[] embedding = embed(chunk.text());
        return PointStruct.newBuilder()
            .setId(id(chunk.pointId()))      // UUID from chunkId()
            .setVectors(vectors(embedding))
            .putPayload("classFqn",          value(chunk.classFqn()))
            .putPayload("chunkType",         value(chunk.chunkType()))
            .putPayload("methodId",          value(chunk.methodId()))
            .putPayload("module",            value(chunk.module()))
            .putPayload("stereotype",        value(chunk.stereotype()))
            .putPayload("contentHash",       value(chunk.contentHash()))
            .putPayload("classHeaderId",     value(chunk.classHeaderId()))
            .putPayload("structuralRiskScore", value(chunk.structuralRiskScore()))
            .putPayload("enhancedRiskScore", value(chunk.enhancedRiskScore()))
            .putPayload("domainCriticality", value(chunk.domainCriticality()))
            .putPayload("securitySensitivity", value(chunk.securitySensitivity()))
            .putPayload("financialInvolvement", value(chunk.financialInvolvement()))
            .putPayload("businessRuleDensity", value(chunk.businessRuleDensity()))
            .putPayload("vaadin7Detected",   value(chunk.vaadin7Detected()))
            .putPayload("callers",           value(String.join(",", chunk.callers())))
            .putPayload("callees",           value(String.join(",", chunk.callees())))
            .putPayload("domainTerms",       value(serializeTerms(chunk.domainTerms())))
            .build();
    })
    .toList();

client.upsertAsync(COLLECTION, points).get(30, TimeUnit.SECONDS);
```

### Pattern 4: Incremental Re-Index Hash Retrieval via Scroll
**What:** Scroll all points in `code_chunks`, collecting `classFqn` + `contentHash` per point. Build a `Map<String, String>` of fqn → stored hash. Compare with current file hashes from Neo4j. Re-index only changed files.
**When to use:** `POST /api/vector/reindex` endpoint handler in VectorIndexingService.
**Example:**
```java
// Source: Qdrant Java client scroll API
import static io.qdrant.client.WithPayloadSelectorFactory.include;

Map<String, String> storedHashes = new HashMap<>();
String offset = null;
do {
    var builder = ScrollPoints.newBuilder()
        .setCollectionName(COLLECTION)
        .setLimit(500)
        .setWithPayload(include(List.of("classFqn", "contentHash")));
    if (offset != null) builder.setOffset(/* next page offset */);

    var result = client.scrollAsync(builder.build()).get(10, TimeUnit.SECONDS);
    result.getResultList().forEach(sp -> {
        String fqn = sp.getPayloadMap().get("classFqn").getStringValue();
        String hash = sp.getPayloadMap().get("contentHash").getStringValue();
        storedHashes.put(fqn, hash);
    });
    offset = result.hasNextPageOffset()
        ? result.getNextPageOffset().toString() : null;
} while (offset != null);
```

### Pattern 5: Spring AI TransformersEmbeddingModel
**What:** Spring AI auto-configures `TransformersEmbeddingModel` when `spring-ai-starter-model-transformers` is on classpath. Defaults to all-MiniLM-L6-v2 (384 dims) with model bundled in JAR. Supports batch embedding.
**When to use:** Inject `EmbeddingModel` into VectorIndexingService; call `embed(List<String>)` for batch embedding.
**Example:**
```java
// Source: Spring AI 1.1.2 docs (docs.spring.io/spring-ai/reference/api/embeddings/onnx.html)
@Service
public class VectorIndexingService {

    private final EmbeddingModel embeddingModel;  // injected by Spring AI auto-config
    private final QdrantClient qdrantClient;

    public void indexChunks(List<CodeChunk> chunks) {
        // Batch embed: embed(List<String>) returns List<float[]>
        List<String> texts = chunks.stream().map(CodeChunk::text).toList();
        List<float[]> embeddings = embeddingModel.embed(texts)
            .stream().map(doubles -> {
                float[] floats = new float[doubles.size()];
                for (int i = 0; i < doubles.size(); i++) floats[i] = doubles.get(i).floatValue();
                return floats;
            }).toList();
        // ... build PointStruct list and upsert
    }
}
```

Required `application.yml` properties (add to existing config):
```yaml
spring:
  ai:
    model:
      embedding: transformers
    embedding:
      transformer:
        tokenizer:
          options:
            padding: "true"   # Required to avoid "ragged array" errors on variable-length input
```

### Pattern 6: Existing Test Container Pattern (No New Dependencies)
**What:** Qdrant already runs as `GenericContainer` in existing integration tests. Re-use the exact same pattern for VectorIndexingServiceIntegrationTest.
**When to use:** All Phase 8 integration tests.
**Example:**
```java
// Source: RiskServiceIntegrationTest.java (existing pattern)
@Container
static GenericContainer<?> qdrant =
    new GenericContainer<>("qdrant/qdrant:latest")
        .withExposedPorts(6333, 6334)
        .waitingFor(Wait.forHttp("/healthz").forPort(6333));

@DynamicPropertySource
static void registerProperties(DynamicPropertyRegistry registry) {
    // ... (neo4j, mysql as usual)
    registry.add("qdrant.host", qdrant::getHost);
    registry.add("qdrant.port", () -> qdrant.getMappedPort(6334));
}
```

### Anti-Patterns to Avoid
- **Embedding inside ChunkingService:** Chunking (text assembly) and embedding (float vector generation) are separate concerns. ChunkingService produces `CodeChunk` records; VectorIndexingService calls the embedding model. This mirrors the Visitor → ExtractionService → LinkingService separation.
- **Rebuilding the collection on every index:** `POST /api/vector/index` should upsert (not delete+recreate). Only `POST /api/vector/reindex` needs hash comparison, but even the full index trigger must be idempotent — subsequent calls should update existing points, not fail.
- **Synchronous blocking during embedding of large codebases:** Embed in batches (recommended: 32–128 texts per batch) to avoid OOM. Virtual threads (enabled in application.yml) handle I/O concurrency but embedding is CPU-bound.
- **Storing raw List<> in Qdrant payload:** Qdrant payload values must be scalar or list-of-scalar. Encode domain term list as JSON string or comma-separated FQNs; decode on read. Do not attempt to store nested objects.
- **Reading source files from ClassNode.sourceFilePath during reindex without null check:** sourceFilePath may be null for classes without accessible source (e.g., framework classes extracted transitively). Guard with `if (sourceFilePath != null && Files.exists(Path.of(sourceFilePath)))`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| ONNX model loading + tokenization + mean pooling | Custom ONNX session management | `spring-ai-starter-model-transformers` | Handles ONNX Runtime session lifecycle, tokenizer padding/truncation, mean pooling, caching — all non-trivial |
| Semantic text embedding | Custom HTTP call to embedding API | `TransformersEmbeddingModel.embed()` | Bundled model; no network dependency; 14,200 sentences/sec; already decided in STATE.md |
| Vector similarity search | Custom cosine similarity in Java | Qdrant's built-in ANN search | Qdrant uses HNSW index; custom search won't scale |
| Batch payload retrieval for hash comparison | Custom point-by-ID fetch loop | Qdrant scroll API | Scroll is designed for full-collection iteration; per-ID fetch in a loop is O(n) round-trips |

**Key insight:** The embeddings + vector search problem is already solved by libraries. The actual complexity in this phase is the enrichment assembly (Cypher queries + source file reading) and the chunk text formatting.

---

## Common Pitfalls

### Pitfall 1: "Supplied array is ragged" from TransformersEmbeddingModel
**What goes wrong:** When embedding a batch of texts with different token lengths, the tokenizer returns arrays of different sizes, causing an exception.
**Why it happens:** Tokenizer padding is disabled by default.
**How to avoid:** Set `spring.ai.embedding.transformer.tokenizer.options.padding=true` in `application.yml`.
**Warning signs:** Exception on first `embed(List<String>)` call with multiple texts of different lengths.

### Pitfall 2: `createCollectionAsync` fails on restart (collection already exists)
**What goes wrong:** `QdrantCollectionInitializer` throws on startup after the first run because the collection already exists.
**Why it happens:** `createCollectionAsync` does not have an "IF NOT EXISTS" equivalent.
**How to avoid:** Always call `collectionExistsAsync(COLLECTION).get()` first; only call `createCollectionAsync` when it returns false.
**Warning signs:** Application fails to start after the first successful run.

### Pitfall 3: Spring AI BOM not pulling transformer starter
**What goes wrong:** `TransformersEmbeddingModel` bean not available; application fails to start.
**Why it happens:** Spring AI auto-configuration for transformers requires explicit dependency on `spring-ai-starter-model-transformers` — it is not transitively included by `spring-boot-starter-web` or the Spring AI BOM alone.
**How to avoid:** Add explicit `implementation("org.springframework.ai:spring-ai-starter-model-transformers")` to `build.gradle.kts`.
**Warning signs:** `NoSuchBeanDefinitionException` for `EmbeddingModel`.

### Pitfall 4: `ValueFactory.value()` overload for double vs. float
**What goes wrong:** Payload values with wrong type cause Qdrant filter mismatch or serialization errors (e.g., passing `double` where `float` expected).
**Why it happens:** Qdrant `ValueFactory.value()` has overloads for `String`, `long`, `double`, `boolean`. Risk scores are `double` in ClassNode — use `value(double)` overload.
**How to avoid:** Use `value((double) chunk.enhancedRiskScore())` for all risk score payload fields.
**Warning signs:** Payload index type mismatch errors when querying by float range.

### Pitfall 5: ClassNode.sourceFilePath null for synthesized or framework classes
**What goes wrong:** `Files.readString(Path.of(sourceFilePath))` throws NullPointerException or NoSuchFileException for classes extracted without source (e.g., Vaadin framework classes referenced transitively).
**Why it happens:** `sourceFilePath` is only set when the class is in the scanned source root.
**How to avoid:** Filter `MATCH (c:JavaClass) WHERE c.sourceFilePath IS NOT NULL` in the Neo4j query that loads classes for chunking.
**Warning signs:** NullPointerException or FileNotFoundException during full index run on a large codebase.

### Pitfall 6: Scroll pagination offset type mismatch
**What goes wrong:** `nextPageOffset` from Qdrant scroll is a `PointId` (protobuf), not a plain string or int. Passing wrong type to next scroll request causes an exception.
**Why it happens:** The Qdrant Java client uses protobuf types for all IDs.
**How to avoid:** Use `result.getNextPageOffset()` directly as the `setOffset()` parameter — do not `.toString()` it.
**Warning signs:** `IllegalArgumentException` or `InvalidProtocolBufferException` on the second scroll page.

### Pitfall 7: EmbeddingModel first-call latency (model download/cache)
**What goes wrong:** The first `embed()` call after application startup takes 30–60 seconds if the ONNX model is not in the cache directory.
**Why it happens:** `spring-ai-starter-model-transformers` bundles the model in the JAR but may still need to extract it to the cache on first run.
**How to avoid:** Configure `spring.ai.embedding.transformer.cache.directory` to a persistent path. Warm the model at startup with a single no-op embed call in `QdrantCollectionInitializer.run()` or a dedicated `@PostConstruct` bean.
**Warning signs:** First `POST /api/vector/index` request times out in production.

---

## Code Examples

Verified patterns from official sources:

### Create Qdrant Collection (idempotent)
```java
// Source: Qdrant Java client 1.13.0 + api.qdrant.tech collection-exists endpoint
boolean exists = qdrantClient.collectionExistsAsync("code_chunks").get(5, TimeUnit.SECONDS);
if (!exists) {
    qdrantClient.createCollectionAsync(
        "code_chunks",
        VectorParams.newBuilder()
            .setDistance(Distance.Cosine)
            .setSize(384)
            .build()
    ).get(10, TimeUnit.SECONDS);
}
```

### Create Payload Index
```java
// Source: qdrant.tech/documentation/concepts/indexing/
client.createPayloadIndexAsync(
    "code_chunks",
    "classFqn",
    PayloadSchemaType.Keyword,
    null, true, null, null
).get(5, TimeUnit.SECONDS);

client.createPayloadIndexAsync(
    "code_chunks",
    "enhancedRiskScore",
    PayloadSchemaType.Float,
    null, true, null, null
).get(5, TimeUnit.SECONDS);
```

### Upsert Points with UUID IDs
```java
// Source: Qdrant Java client README (github.com/qdrant/java-client)
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

PointStruct point = PointStruct.newBuilder()
    .setId(id(UUID.fromString("5c56c793-69f3-4fbf-87e6-c4bf54c28c26")))
    .setVectors(vectors(embeddingFloatArray))
    .putPayload("classFqn", value("com.example.PaymentService"))
    .putPayload("chunkType", value("METHOD"))
    .putPayload("enhancedRiskScore", value(0.87))
    .build();

client.upsertAsync("code_chunks", List.of(point)).get(10, TimeUnit.SECONDS);
```

### Scroll Points (for hash retrieval)
```java
// Source: Qdrant API reference scroll-points, Java client
import static io.qdrant.client.WithPayloadSelectorFactory.include;

var result = client.scrollAsync(
    ScrollPoints.newBuilder()
        .setCollectionName("code_chunks")
        .setLimit(500)
        .setWithPayload(include(List.of("classFqn", "contentHash")))
        .build()
).get(10, TimeUnit.SECONDS);

result.getResultList().forEach(sp -> {
    String fqn = sp.getPayloadMap().get("classFqn").getStringValue();
    String hash = sp.getPayloadMap().get("contentHash").getStringValue();
    // ...
});

// For next page: use result.getNextPageOffset() directly
```

### TransformersEmbeddingModel Batch Embed
```java
// Source: Spring AI 1.1.2 docs (docs.spring.io/spring-ai/reference/api/embeddings/onnx.html)
@Autowired
EmbeddingModel embeddingModel;  // TransformersEmbeddingModel auto-configured

List<String> texts = List.of("method body text 1", "method body text 2");
List<List<Double>> embeddings = embeddingModel.embed(texts);
// embeddings.get(0) is a 384-element list for texts.get(0)
```

### Neo4j 1-hop Neighbor Query (for enrichment)
```java
// Source: com.esmp.graph.application (Neo4jClient pattern established in Phase 3-7)
// Pattern: Neo4jClient.query(cypher).bind(param).fetch().all()
String cypher = """
    MATCH (c:JavaClass {fullyQualifiedName: $fqn})
    OPTIONAL MATCH (caller:JavaClass)-[:DEPENDS_ON]->(c)
    OPTIONAL MATCH (c)-[:DEPENDS_ON]->(dep:JavaClass)
    OPTIONAL MATCH (c)-[:USES_TERM]->(t:BusinessTerm)
    RETURN
        collect(DISTINCT caller.fullyQualifiedName) AS callers,
        collect(DISTINCT dep.fullyQualifiedName) AS dependencies,
        collect(DISTINCT {termId: t.termId, displayName: t.displayName}) AS terms
    """;

neo4jClient.query(cypher)
    .bind(classFqn).to("fqn")
    .fetch()
    .one()
    .ifPresent(row -> { /* build enrichment */ });
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual ONNX session management | Spring AI `TransformersEmbeddingModel` | Spring AI 1.0+ (2024) | Auto-config handles lifecycle, tokenization, mean pooling |
| Numeric integer Qdrant point IDs | UUID point IDs | Qdrant 1.9+ (2024) | Enables UUID v5 deterministic IDs without collision risk |
| Full reindex on every change | Hash-based incremental reindex | Established pattern (2023+) | SLO-03/04: 5 files in <30s, 100 classes in <5 min |
| Separate tokenizer JARs | Bundled tokenizer in spring-ai-transformers | Spring AI 1.0+ | No HuggingFace network call needed at runtime |

**Deprecated/outdated:**
- `spring-ai-transformers-spring-boot-starter`: Old artifact name before Spring AI 1.0 GA rename. Use `spring-ai-starter-model-transformers` (confirmed in Spring AI 1.1.2 docs).

---

## Open Questions

1. **Spring AI BOM version alignment**
   - What we know: Spring Boot 3.5.11 is the project version; Spring AI 1.1.2 is listed in STATE.md as the AI runtime. The `dependencyManagement` block currently imports only the Vaadin BOM.
   - What's unclear: Whether the Spring AI BOM is already in `dependencyManagement`, or whether `spring-ai-starter-model-transformers` needs an explicit version pin to 1.1.2.
   - Recommendation: Wave 0 task should check `build.gradle.kts` and add `mavenBom("org.springframework.ai:spring-ai-bom:1.1.2")` to `dependencyManagement` if missing; then add `spring-ai-starter-model-transformers` without version. If BOM already present, just add the dependency.

2. **Chunk text format for embedding quality**
   - What we know: The model embeds the `text` field of each CodeChunk. Context says Claude's Discretion on exact formatting.
   - What's unclear: Whether including method signature + class context in chunk text improves RAG recall (Phase 11 concern).
   - Recommendation: Use `[CLASS: {simpleName}] [METHOD: {signature}]\n{methodBodySource}` for method chunks; `[CLASS: {simpleName}] [PACKAGE: {packageName}]\n{classJavadoc}\n{fieldSignatures}` for header chunks. Prefix with class/method label so embeddings cluster by semantic type.

3. **ONNX model warm-up timing**
   - What we know: First embed call may be slow (30–60s) on cold start.
   - What's unclear: Whether Spring AI 1.1.2 extracts model from JAR to cache synchronously at startup or lazily on first call.
   - Recommendation: Add a `@EventListener(ApplicationReadyEvent.class)` warm-up in `VectorIndexingService` that calls `embeddingModel.embed(List.of("warmup"))` to pre-load the model before handling REST requests.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (junit-jupiter) via spring-boot-starter-test |
| Config file | Configured in `build.gradle.kts`: `tasks.withType<Test> { useJUnitPlatform() }` |
| Quick run command | `./gradlew test --tests "com.esmp.vector.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements to Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| VEC-01 | ChunkingService produces one header + N method chunks for a class | Unit | `./gradlew test --tests "com.esmp.vector.application.ChunkingServiceTest"` | Wave 0 |
| VEC-01 | Class header chunk contains package, imports, annotations, javadoc | Unit | `./gradlew test --tests "com.esmp.vector.application.ChunkingServiceTest"` | Wave 0 |
| VEC-02 | Enrichment includes callers, callees, domain terms, risk scores, vaadin7Detected | Integration | `./gradlew test --tests "com.esmp.vector.application.VectorIndexingServiceIntegrationTest"` | Wave 0 |
| VEC-03 | Chunks are embedded (384-dim float vectors) and upserted to Qdrant code_chunks collection | Integration | `./gradlew test --tests "com.esmp.vector.application.VectorIndexingServiceIntegrationTest"` | Wave 0 |
| VEC-03 | QdrantCollectionInitializer creates collection + 5 payload indexes at startup | Integration | `./gradlew test --tests "com.esmp.vector.config.QdrantCollectionInitializerTest"` | Wave 0 |
| VEC-04 | Reindex skips files whose hash matches stored hash | Integration | `./gradlew test --tests "com.esmp.vector.application.VectorIndexingServiceIntegrationTest"` | Wave 0 |
| VEC-04 | Reindex re-embeds files whose hash changed | Integration | `./gradlew test --tests "com.esmp.vector.application.VectorIndexingServiceIntegrationTest"` | Wave 0 |
| VEC-04 | POST /api/vector/reindex returns count of re-indexed files | Integration | `./gradlew test --tests "com.esmp.vector.api.VectorIndexControllerIntegrationTest"` | Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.vector.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/vector/application/ChunkingServiceTest.java` — covers VEC-01 (unit test with fixture classes)
- [ ] `src/test/java/com/esmp/vector/application/VectorIndexingServiceIntegrationTest.java` — covers VEC-02, VEC-03, VEC-04 (with Neo4j + Qdrant Testcontainers)
- [ ] `src/test/java/com/esmp/vector/config/QdrantCollectionInitializerTest.java` — covers VEC-03 startup init
- [ ] `src/test/java/com/esmp/vector/api/VectorIndexControllerIntegrationTest.java` — covers VEC-04 REST endpoint

---

## Sources

### Primary (HIGH confidence)
- [Spring AI 1.1.2 GitHub docs — TransformersEmbeddingModel](https://github.com/spring-projects/spring-ai/blob/v1.1.2/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/embeddings/onnx.adoc) — dependency, properties, tokenizer padding requirement
- [Spring AI official reference — ONNX embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html) — model defaults (384 dims, all-MiniLM-L6-v2), configuration properties
- [Qdrant Java client README](https://github.com/qdrant/java-client) — upsert, search, scroll API with Java examples
- [Qdrant collection-exists API reference](https://api.qdrant.tech/api-reference/collections/collection-exists) — `collectionExistsAsync()` return type and usage
- [Qdrant indexing documentation](https://qdrant.tech/documentation/concepts/indexing/) — `createPayloadIndexAsync()` signature with PayloadSchemaType
- [Qdrant scroll-points API reference](https://api.qdrant.tech/api-reference/points/scroll-points) — scroll with payload selection, pagination
- `com.esmp.extraction.config.Neo4jSchemaInitializer` (existing codebase) — ApplicationRunner pattern for startup init
- `com.esmp.graph.application.RiskService` (existing codebase) — Neo4jClient pattern, post-extraction service structure
- `com.esmp.graph.application.RiskServiceIntegrationTest` (existing codebase) — GenericContainer Qdrant test pattern

### Secondary (MEDIUM confidence)
- [Qdrant Testcontainers for Java](https://java.testcontainers.org/modules/qdrant/) — dedicated module exists (`org.testcontainers:testcontainers-qdrant:2.0.2`) but existing GenericContainer pattern is equivalent and avoids new dependency
- [UUID5 Java implementation reference](https://github.com/rootsdev/polygenea/blob/master/java/src/org/rootsdev/polygenea/UUID5.java) — SHA-1 + version/variant bit setting approach (no external lib needed)

### Tertiary (LOW confidence)
- WebSearch results on batch size limits for Qdrant upsert — no official limit documented; recommend 32–128 based on community patterns (not verified against official docs)

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Spring AI 1.1.2 verified via official docs; Qdrant 1.13.0 already in project; no new unknowns
- Architecture: HIGH — all patterns are direct replicas of existing codebase patterns (RiskService, Neo4jSchemaInitializer, RiskServiceIntegrationTest)
- Pitfalls: HIGH for tokenizer padding, collection exists, scroll pagination (all verified); MEDIUM for BOM alignment (open question)
- Test strategy: HIGH — existing GenericContainer Qdrant pattern already works in RiskServiceIntegrationTest

**Research date:** 2026-03-05
**Valid until:** 2026-04-05 (Spring AI 1.x minor releases monthly; Qdrant client stable)
