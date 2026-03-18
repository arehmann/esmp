# Phase 11: RAG Pipeline - Research

**Researched:** 2026-03-18
**Domain:** GraphRAG retrieval pipeline — Neo4j graph expansion + Qdrant vector search + merge/re-rank
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Query Input & Resolution**
- Accept both class FQN/simple name and natural language queries
- Resolution strategy: Neo4j lookup first — try resolving input as FQN or simple class name in Neo4j. If found → graph+vector pipeline. If no match → treat as natural language, vector-only search with graph expansion on top 3 hits
- Ambiguous simple names (multiple matches): return a disambiguation response listing matching FQNs with module/stereotype info. User re-queries with the exact FQN
- Natural language fallback: expand graph cone around top 3 vector hits, merge all cone results + vector results into final response
- Accept optional filters: module, stereotype, limit — consistent with existing VectorSearchController API pattern

**Cone-Constrained Search**
- Filter Qdrant by cone FQNs — after getting cone nodes from Neo4j, pass their FQNs as a Qdrant payload filter (`classFqn IN [...]`). Server-side filtering leverages existing payload index on classFqn
- Cone depth: fixed at 10 hops (existing validated depth from Phase 4)
- No FQN cap: query all cone FQNs regardless of cone size. A 50-node cone produces ~150-300 chunks — well within Qdrant's filter capacity
- Embedding source: embed focal class's own chunk text for similarity search within the cone

**Merge & Ranking**
- Weighted linear combination: `finalScore = 0.4 * vectorSimilarity + 0.35 * graphProximity + 0.25 * enhancedRiskScore`
- Graph proximity derived from hop distance (1/hopCount normalization)
- Weights configurable via `application.yml` (`esmp.rag.weight.*`)
- No Vaadin-specific ranking boost — framework-agnostic scoring. Vaadin metadata included in response for downstream filtering only
- Default result limit: top 20, configurable via query param (max 100)

**Response Structure**
- Structured migration package optimized for AI orchestrator consumption:
  - `focalClass`: full detail — FQN, stereotype, risk scores, domain terms, code text
  - `contextChunks`: ranked list — each with code text, relationship path to focal class (type + hop count), score breakdown (vector/graph/risk components), risk scores, domain terms, vaadin metadata
  - `coneSummary`: aggregate stats — total nodes, vaadin7 count, avg enhanced risk, top domain terms (deduplicated), total unique business terms
- Code text: semantic chunk text by default (from Qdrant). Optional `includeFullSource=true` param fetches full `.java` source files from disk
- Each context result includes relationship path to focal class: e.g., "DEPENDS_ON (1 hop)", "EXTENDS > IMPLEMENTS (2 hops)"

### Claude's Discretion
- Internal service class structure and method decomposition
- Error handling for missing source files (includeFullSource mode)
- Exact graph proximity normalization formula
- Response serialization format details
- SLO optimization strategies (caching, parallel execution)

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| RAG-01 | System performs graph expansion from a focal class to retrieve related nodes | `GraphQueryService.findDependencyCone()` reusable as-is; needs a hop-distance variant for proximity scoring |
| RAG-02 | System performs embedding similarity search against Qdrant | `VectorSearchService.search()` needs a new overload accepting `List<String> fqnFilter`; `ConditionFactory.matchKeywords()` confirmed available in Qdrant client 1.13.0 |
| RAG-03 | System combines graph and vector results into ranked retrieval context | New `RagService.assemble()` with weighted linear combination; merge by classFqn; re-rank by finalScore |
| RAG-04 | User can query "what classes relate to X?" via REST and receive structured, ranked list | New `RagController` at `/api/rag/context`; disambig response type for ambiguous simple names |
| SLO-01 | Graph dependency cone query completes in under 200ms | Existing `findDependencyCone()` Cypher is a single OPTIONAL MATCH with variable-length path — baseline performance must be measured |
| SLO-02 | RAG context assembly completes in under 1.5 seconds for a 50-node cone | Parallel execution of graph expansion + embedding + Qdrant search; ~150-300 chunks from 50-node cone is within budget |
</phase_requirements>

## Summary

Phase 11 builds a retrieval layer on top of the existing graph (Neo4j) and vector (Qdrant) stores
that were constructed in Phases 3-10. The core pipeline is: resolve query → get dependency cone
from Neo4j → embed focal class text → search Qdrant filtered to cone FQNs → merge + re-rank →
return structured migration context package. All foundational components exist and are tested.

The two engineering challenges are: (1) extending `GraphQueryService.findDependencyCone()` to
return per-node hop distances (currently only returns FQN + labels, no hop count), and (2)
extending `VectorSearchService` to accept a list of FQNs as a Qdrant filter. Both are small,
targeted changes to existing well-tested services. The `RagService` orchestrator and `RagController`
are the only net-new classes of substance.

**Primary recommendation:** Extend existing services with targeted additions (hop-distance cone
query, FQN-list Qdrant filter), build `RagService` as a pure orchestrator, and keep all new code
in `com.esmp.rag`.

## Standard Stack

### Core (all existing — no new dependencies required)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Data Neo4j / Neo4jClient | via Spring Boot 3.5.11 | Graph traversal, hop-distance Cypher | All complex Cypher in this codebase uses Neo4jClient directly |
| Qdrant Java Client | 1.13.0 | FQN-filtered vector search | Already in project; `ConditionFactory.matchKeywords()` confirmed for multi-value keyword filter |
| Spring AI EmbeddingModel | 1.1.2 | Embed focal class chunk text | Already configured, warmed up via EmbeddingWarmup at startup |
| Spring Boot `@ConfigurationProperties` | via Spring Boot 3.5.11 | RAG weight configuration | Established project pattern (RiskWeightConfig, VectorConfig) |
| JUnit 5 + Testcontainers | 1.20.4 | Integration tests with real Neo4j + Qdrant | Established project pattern (VectorSearchIntegrationTest) |

### No New Dependencies

No new library additions are required. The entire RAG pipeline can be built using:
- `Neo4jClient` for Cypher traversal
- `QdrantClient` with `ConditionFactory.matchKeywords()` for FQN-list filtering
- `EmbeddingModel.embed()` for query/focal-class embedding
- Java `CompletableFuture` for parallel graph + embed execution

**Confirmed via source inspection:** `ConditionFactory.matchKeywords(String field, List<String> keywords)`
is available in Qdrant client 1.13.0 (verified against GitHub v1.13.0 tag). This is the method
for passing a list of FQNs as a server-side `classFqn IN [...]` filter.

## Architecture Patterns

### Recommended Project Structure

```
src/main/java/com/esmp/rag/
├── api/
│   ├── RagController.java            # POST /api/rag/context
│   ├── RagRequest.java               # record: query, fqn, limit, module, stereotype, includeFullSource
│   ├── RagResponse.java              # record: focalClass, contextChunks, coneSummary, queryType
│   ├── FocalClassDetail.java         # record: fqn, stereotype, risk scores, domain terms, codeText
│   ├── ContextChunk.java             # record: classFqn, chunkType, codeText, relationshipPath, scoreBreakdown, risk, vaadin metadata
│   ├── ScoreBreakdown.java           # record: vectorScore, graphProximityScore, riskScore, finalScore
│   ├── ConeSummary.java              # record: totalNodes, vaadin7Count, avgEnhancedRisk, topDomainTerms, uniqueBusinessTermCount
│   └── DisambiguationResponse.java   # record: query, candidates (list of FQN + module + stereotype)
├── application/
│   └── RagService.java               # orchestrator: resolve → cone → embed → search → merge → rank
├── config/
│   └── RagWeightConfig.java          # @ConfigurationProperties(prefix="esmp.rag.weight")
└── validation/
    └── RagValidationQueryRegistry.java  # 3 validation queries
```

### Pattern 1: Hop-Distance Cone Query

The existing `findDependencyCone()` Cypher collects all reachable nodes but discards path length.
For RAG, hop distance is needed to compute graph proximity score. The fix is a new Cypher query
that also returns `min(length(path))` per node.

**What:** New Cypher query in `GraphQueryService` (or in `RagService` via Neo4jClient directly)
that returns `{fqn, labels, hopDistance}` for each reachable JavaClass node.

**When to use:** Always during RAG pipeline; original `findDependencyCone()` stays unchanged for
the existing Phase 4 `/api/graph/class/{fqn}/dependency-cone` endpoint.

**Key constraint:** Only JavaClass nodes are relevant for vector search (chunks only exist for
JavaClasses). JavaMethod, JavaField, DBTable, etc. in the cone are skipped for vector retrieval
but the focal class's hop distance is still used for graph proximity of their containing class.

```java
// Source: GraphQueryService pattern (Neo4jClient, variable-length path)
String cypher = """
    MATCH (focal:JavaClass {fullyQualifiedName: $fqn})
    OPTIONAL MATCH path = (focal)-[:DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS|BINDS_TO|QUERIES|MAPS_TO_TABLE*1..10]->(reachable:JavaClass)
    WITH focal, reachable, CASE WHEN path IS NOT NULL THEN min(length(path)) ELSE null END AS hopDist
    WHERE reachable IS NOT NULL
    RETURN focal.fullyQualifiedName AS focalFqn,
           reachable.fullyQualifiedName AS fqn,
           [label IN labels(reachable) WHERE label <> 'JavaClass'] AS labels,
           hopDist
    """;
```

Note: This query uses `OPTIONAL MATCH ... path = ...` with `min(length(path))` scoped in the
WITH clause. This differs from the existing cone query which collects all node types. Restricting
to `:JavaClass` target nodes is intentional — only classes have vector chunks.

**IMPORTANT GOTCHA (from Phase 7 memory):** `min()` is an aggregation function in Cypher. When
used in a WITH clause alongside non-aggregated variables, all non-aggregated variables become
grouping keys. The correct pattern is to compute min(length(path)) in the same WITH as `path`
before collecting nodes:

```cypher
OPTIONAL MATCH path = (focal)-[...]->(reachable:JavaClass)
WITH focal, reachable, min(length(path)) AS hopDist
RETURN focal.fullyQualifiedName, reachable.fullyQualifiedName, hopDist, labels(reachable)
```

### Pattern 2: FQN-List Qdrant Filter

**What:** New method overload on `VectorSearchService` accepting `List<String> coneFqns` that
builds a `matchKeywords("classFqn", coneFqns)` filter and adds it to the Qdrant `SearchPoints`.

**When to use:** RAG pipeline cone-constrained search. Existing `search(SearchRequest)` is
unchanged for the Phase 9 `/api/vector/search` endpoint.

```java
// Source: ConditionFactory.matchKeywords() — Qdrant client 1.13.0
import static io.qdrant.client.ConditionFactory.matchKeywords;

// In VectorSearchService (new method):
public List<ChunkSearchResult> searchByCone(String queryText, List<String> coneFqns, int limit) {
    float[] queryVector = embeddingModel.embed(queryText);

    Filter.Builder filterBuilder = Filter.newBuilder()
        .addMust(matchKeywords("classFqn", coneFqns));  // server-side FQN IN [...] filter

    SearchPoints searchPoints = SearchPoints.newBuilder()
        .setCollectionName(vectorConfig.getCollectionName())
        .setLimit(limit)
        .setWithPayload(enable(true))
        .setFilter(filterBuilder.build())
        .addAllVector(...)  // float[] → repeated float
        .build();

    return qdrantClient.searchAsync(searchPoints).get(30, TimeUnit.SECONDS)
        .stream().map(this::mapToResult).collect(Collectors.toList());
}
```

**Note on float[] → repeated float:** The existing `VectorSearchService.search()` iterates
`for (float v : queryVector) { builder.addVector(v); }`. Use the same pattern for the new method.

### Pattern 3: Parallel Graph + Embed Execution

SLO-02 (1.5s total for 50-node cone) requires graph traversal and text embedding to run in
parallel since they are independent operations. The focal class chunk text must be fetched from
Qdrant first (or from the VectorIndexingService text cache), then embedding and graph expansion
can proceed concurrently.

**Approach:** Use `CompletableFuture.supplyAsync()` for the two independent legs:

```java
// In RagService.assemble():
CompletableFuture<Map<String, Integer>> coneFuture =
    CompletableFuture.supplyAsync(() -> graphQueryService.findDependencyConeWithHops(fqn));

CompletableFuture<float[]> embeddingFuture =
    CompletableFuture.supplyAsync(() -> embeddingModel.embed(focalChunkText));

// Block for both to complete before Qdrant search
Map<String, Integer> coneWithHops = coneFuture.join();
float[] queryVector = embeddingFuture.join();

// Then: Qdrant search (depends on both)
List<ChunkSearchResult> vectorResults = vectorSearchService.searchByCone(queryVector, coneFqns, limit * 3);
```

**Caveat:** Spring's `Neo4jClient` does not require a specific thread — it creates its own
connections from the driver pool. `EmbeddingModel.embed()` is CPU-bound (ONNX inference). Both
can safely run on separate executor threads. No Spring transaction context is required in the RAG
service (read-only graph queries + no DB writes).

### Pattern 4: Weighted Merge and Re-Rank

**What:** Merge vector results (indexed by classFqn) with cone hop distances (indexed by classFqn),
then compute `finalScore = 0.4 * vectorSimilarity + 0.35 * graphProximity + 0.25 * enhancedRiskScore`.

**Graph proximity normalization:** `graphProximity = 1.0 / hopCount` (focal class itself = hop 0,
direct dependencies = hop 1, etc.). At hop 10: proximity = 0.1. At hop 1: proximity = 1.0.
Focal class if it appears in vector results: proximity = 1.0 (treat as hop 0 → distance 1 for
non-zero denominator, or set proximity = 1.0 explicitly for the focal class itself).

**Vector score normalization:** Qdrant cosine similarity scores are already in [0.0, 1.0].

**Risk score:** `enhancedRiskScore` is already in [0.0, 1.0] from Phase 7.

```java
// In RagService — merge and rank:
double graphProximity = coneWithHops.containsKey(classFqn)
    ? 1.0 / coneWithHops.get(classFqn)
    : 0.0;
double finalScore = weights.getVectorSimilarity() * vectorScore
    + weights.getGraphProximity() * graphProximity
    + weights.getRiskScore() * enhancedRiskScore;
```

Classes in the cone but not in vector results (no matching chunks) are omitted from `contextChunks`
but counted in `coneSummary.totalNodes`. Classes with vector hits but outside the cone are excluded
entirely (cone acts as hard boundary).

### Pattern 5: RagWeightConfig

**Follow the exact same pattern as `RiskWeightConfig`:**

```java
// Source: com.esmp.extraction.config.RiskWeightConfig pattern
@Component
@ConfigurationProperties(prefix = "esmp.rag.weight")
public class RagWeightConfig {
    private double vectorSimilarity = 0.40;
    private double graphProximity   = 0.35;
    private double riskScore        = 0.25;
    // getters + setters
}
```

application.yml addition:
```yaml
esmp:
  rag:
    weight:
      vector-similarity: 0.40
      graph-proximity: 0.35
      risk-score: 0.25
```

### Pattern 6: Query Resolution Logic

**Three resolution paths (in order):**

1. **Exact FQN match** — `MATCH (c:JavaClass {fullyQualifiedName: $input})` → full pipeline
2. **Simple name match** — `MATCH (c:JavaClass) WHERE toLower(c.simpleName) = toLower($input)`:
   - 0 matches → natural language fallback
   - 1 match → resolve to that FQN, full pipeline
   - 2+ matches → disambiguation response (return `DisambiguationResponse` with list of candidates)
3. **Natural language** — embed query text, search full Qdrant collection (no cone filter), take
   top 3 hits, expand cone around each hit's `classFqn`, merge cones + vector results

**Reuse `searchByName()`** from `GraphQueryService` for simple-name lookup — it returns
`SearchResponse.SearchEntry(fqn, simpleName, packageName, labels)` which contains all fields
needed for the disambiguation response.

### Anti-Patterns to Avoid

- **Embedding the query string for FQN-resolved queries:** When input resolves to a known class,
  embed the focal class's OWN chunk text (from Qdrant payload or re-read from Neo4j), not the
  raw query string. The point is "find things similar to this class", not "find things similar
  to the word CustomerOrderService".
- **Using `findDependencyCone()` for RAG:** The existing method returns all node types (Method,
  Field, DBTable, Annotation, Package, Module). Filter to `:JavaClass` only for vector FQN list.
  Non-class nodes do not have Qdrant chunks.
- **Skipping the focal class from vector results:** The focal class itself will appear in Qdrant
  results (it has chunks). Include it in `contextChunks` with hop distance = 0 / proximity = 1.0.
- **Passing null for `WithVectorsSelectorFactory`:** Known project pitfall (Phase 8 memory) —
  pass `WithVectorsSelectorFactory.enable(false)` not null to avoid NPE in certain Qdrant client
  methods. The `search()` path using `SearchPoints` does not hit this issue (it's `retrieveAsync`
  that requires it), but be aware.
- **Cypher string concatenation for sortBy or FQN parameters:** Never interpolate user-supplied
  values directly into Cypher strings. Use `.bind(value).to("param")` exclusively.
- **`@Transactional` on the RagService:** RagService does not write to Neo4j or JPA. The graph
  queries are read-only via Neo4jClient; no transaction annotation is needed (and none of the
  existing read-only services use it either).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Multi-value Qdrant payload filter | Custom Cypher post-filter or Java-side FQN filtering | `ConditionFactory.matchKeywords("classFqn", fqnList)` | Server-side indexed filter; existing `classFqn` payload index from Phase 8 QdrantCollectionInitializer |
| Parallel execution primitives | Thread pool management | `CompletableFuture.supplyAsync()` | Java 21, already used in project; graph + embed are naturally parallel |
| Configurable weights | Hardcoded constants | `@ConfigurationProperties` (RagWeightConfig) | Established project pattern; enables tuning without code changes |
| Graph traversal | APOC or custom BFS | Neo4j native variable-length path syntax `*1..10` | Project established pattern; no APOC dependency; Phase 4 confirmed this works |
| Text normalization for hop distances | Custom distance metrics | `1.0 / hopCount` linear normalization | Simple, interpretable, consistent with project's log-normalization philosophy |

**Key insight:** All three data sources (Neo4j graph, Qdrant vectors, risk scores embedded in
Qdrant payload) are already queryable with existing project infrastructure. The RAG service is a
pure orchestrator — it calls existing services and merges their results.

## Common Pitfalls

### Pitfall 1: Cypher `min()` as scalar vs. aggregation

**What goes wrong:** Writing `min(length(path))` in the same RETURN or WITH clause as
non-aggregated node variables causes Cypher to treat all other variables as implicit grouping keys.
This works correctly but must be structured carefully: compute `min(length(path))` in the WITH
clause directly after the OPTIONAL MATCH, before collecting or filtering.

**Why it happens:** Cypher aggregation in WITH forces grouping. Phase 7 hit this exact bug when
computing `min()` for security/financial heuristics.

**How to avoid:** Structure as:
```cypher
OPTIONAL MATCH path = (focal)-[...]->(reachable:JavaClass)
WITH reachable, min(length(path)) AS hopDist
WHERE reachable IS NOT NULL
RETURN reachable.fullyQualifiedName AS fqn, hopDist
```

**Warning signs:** Unexpected duplicate rows; hop distances always = 1; null results for multi-hop nodes.

### Pitfall 2: Empty cone producing Qdrant matchKeywords with empty list

**What goes wrong:** If the focal class is isolated (no outgoing edges), the cone FQN list is
empty. Passing an empty list to `matchKeywords("classFqn", List.of())` will either return zero
results or throw an error in Qdrant client.

**Why it happens:** Isolated classes exist legitimately (e.g., utility enums with no dependencies).

**How to avoid:** Guard with: if cone FQNs list is empty (after adding focal class), skip Qdrant
filter and fall back to returning only the focal class's own chunks. Always include the focal
class FQN in the cone FQN list even before graph expansion (the focal class is always its own
context).

**Warning signs:** Empty `contextChunks` for simple utility classes; NullPointerException in Qdrant gRPC layer.

### Pitfall 3: FQN path variable truncation in Spring MVC

**What goes wrong:** Spring MVC truncates path variables at the first `.` character. A GET request
to `/api/rag/context/com.example.MyClass` produces `fqn = "com"`.

**Why it happens:** Spring's default PathVariable handling treats `.` as an extension separator.

**How to avoid:** Use `:.+` regex suffix on all FQN path variables:
`@GetMapping("/context/{fqn:.+}")`.

**Confirmed project decision (Phase 3):** All graph endpoints already use this pattern
(`/class/{fqn:.+}/dependency-cone` etc.).

**Note:** The CONTEXT.md decision is to use `POST /api/rag/context` with a request body, which
avoids this issue entirely. Use POST with `RagRequest` record.

### Pitfall 4: Chunk text not stored in Qdrant payload

**What goes wrong:** Attempting to retrieve code text from Qdrant payload and finding it empty.

**Why it happens:** Phase 8 ChunkSearchResult javadoc explicitly states: "the embedding source
text is not stored as payload — only the vector embedding is persisted." Text was used to produce
the embedding but is not stored as a payload field.

**How to avoid:** For `includeFullSource=false` (default), generate a display-friendly summary
from available payload fields (classFqn, chunkType, methodId, stereotype, domainTerms, risk scores).
For `includeFullSource=true`, read `.java` source files from disk using the pattern in
`ChunkingService.readSourceFile()`.

Alternatively: fetch the `text` field from Neo4j via the class's `sourceFilePath` + method
position, or re-generate semantic chunk text on demand. The simplest approach: include available
Qdrant payload metadata as `codeText` for the default mode; add source file reading for
`includeFullSource=true`.

**Warning signs:** `contextChunks[].codeText` always empty in API responses.

### Pitfall 5: SLO-02 timing with sequential embedding + graph traversal

**What goes wrong:** Sequential execution — graph traversal (50-100ms) then embedding (200-400ms
for ONNX) then Qdrant search (50-100ms) totals ~400-600ms under ideal conditions but can exceed
1.5s under load or cold JVM.

**Why it happens:** ONNX inference on all-MiniLM-L6-v2 takes 200-400ms for a single text embed
on first call after warmup; graph traversal is a separate round trip to Neo4j.

**How to avoid:** Parallelize graph traversal and embedding using `CompletableFuture.supplyAsync()`.
EmbeddingWarmup ensures model is loaded at startup (Phase 8 pattern). The warm embed call should
be ~50-150ms; graph traversal ~20-100ms for 50 nodes. Parallel execution leaves budget for the
Qdrant search call.

**Warning signs:** SLO-02 integration test fails intermittently; consistent > 1.0s times even
for small cones.

### Pitfall 6: Natural language fallback cone merging

**What goes wrong:** Taking the top 3 vector hits and expanding cones around each produces
potentially overlapping FQN sets. Merging must deduplicate FQNs and take the minimum hop distance
across cones when a class appears in multiple cones.

**Why it happens:** Cone expansion from 3 different focal points will naturally overlap for closely
related classes.

**How to avoid:** Use a `Map<String, Integer>` (FQN → min hop distance) during cone merge. For
the natural language fallback, the "virtual focal class" is undefined; set all hop distances = 1
(all cone members treated as equally proximate) to avoid skewing the graph proximity score.

## Code Examples

### FQN-List Qdrant Filter (confirmed API)

```java
// Source: ConditionFactory.matchKeywords() — Qdrant client 1.13.0, verified against GitHub tag
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

// Build filter from cone FQN list
Filter coneFilter = Filter.newBuilder()
    .addMust(matchKeywords("classFqn", coneFqns))  // coneFqns: List<String>
    .build();

SearchPoints searchPoints = SearchPoints.newBuilder()
    .setCollectionName(vectorConfig.getCollectionName())
    .setLimit(limit)
    .setWithPayload(enable(true))
    .setFilter(coneFilter)
    .build();

// Add float[] vector
for (float v : queryVector) {
    searchPoints = searchPoints.toBuilder().addVector(v).build();
}
// Or more efficiently:
SearchPoints.Builder builder = SearchPoints.newBuilder()
    .setCollectionName(vectorConfig.getCollectionName())
    .setLimit(limit)
    .setWithPayload(enable(true))
    .setFilter(coneFilter);
for (float v : queryVector) { builder.addVector(v); }
SearchPoints built = builder.build();
```

### Hop-Distance Cypher

```java
// Source: Neo4jClient pattern from GraphQueryService (project canonical)
// Only targets JavaClass nodes — other node types have no Qdrant chunks
String cypher = """
    MATCH (focal:JavaClass {fullyQualifiedName: $fqn})
    OPTIONAL MATCH path = (focal)-[:DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS|BINDS_TO|QUERIES|MAPS_TO_TABLE*1..10]->(reachable:JavaClass)
    WITH focal, reachable, min(length(path)) AS hopDist
    WHERE reachable IS NOT NULL
    RETURN focal.fullyQualifiedName AS focalFqn,
           reachable.fullyQualifiedName AS fqn,
           [label IN labels(reachable) WHERE label <> 'JavaClass'] AS labels,
           hopDist
    """;

Collection<Map<String, Object>> rows = neo4jClient
    .query(cypher)
    .bind(fqn).to("fqn")
    .fetch()
    .all();

// Build FQN → hopDistance map
Map<String, Integer> fqnToHop = new HashMap<>();
for (Map<String, Object> row : rows) {
    String nodeFqn = (String) row.get("fqn");
    Long hop = (Long) row.get("hopDist");
    if (nodeFqn != null && hop != null) {
        fqnToHop.put(nodeFqn, hop.intValue());
    }
}
// Always add focal class at hop 0
fqnToHop.put(fqn, 0);
```

### Weighted Score Computation

```java
// finalScore = 0.4 * vectorSimilarity + 0.35 * graphProximity + 0.25 * enhancedRiskScore
// graphProximity = 1.0 / max(hopCount, 1) to avoid division by zero for focal class at hop 0
private double computeFinalScore(
        float vectorSimilarity,
        int hopCount,
        double enhancedRiskScore,
        RagWeightConfig weights) {
    double proximity = 1.0 / Math.max(hopCount, 1);
    return weights.getVectorSimilarity() * vectorSimilarity
        + weights.getGraphProximity() * proximity
        + weights.getRiskScore() * enhancedRiskScore;
}
```

### Query Resolution: Simple Name Lookup

```java
// Reuse GraphQueryService.searchByName() — returns SearchResponse with SearchEntry list
// which already uses Neo4jClient + labels(c) Cypher (avoids @DynamicLabels SDN bug)
SearchResponse nameSearch = graphQueryService.searchByName(input);
List<SearchResponse.SearchEntry> matches = nameSearch.results();

if (matches.isEmpty()) {
    // Natural language fallback
} else if (matches.size() == 1) {
    String resolvedFqn = matches.get(0).fqn();
    // Proceed with full pipeline
} else {
    // Return DisambiguationResponse
    return RagResponse.disambiguation(input, matches.stream()
        .map(e -> new DisambiguationCandidate(e.fqn(), e.simpleName(), e.packageName(), e.labels()))
        .collect(Collectors.toList()));
}
```

### Controller POST Pattern

```java
// Source: VectorSearchController pattern (existing)
// Use POST with request body to avoid FQN path variable truncation issue
@RestController
@RequestMapping("/api/rag")
public class RagController {

    @PostMapping("/context")
    public ResponseEntity<?> getContext(@RequestBody RagRequest request) {
        if (request == null || (request.query() == null && request.fqn() == null)) {
            return ResponseEntity.badRequest().build();
        }
        RagResponse response = ragService.assemble(request);
        if (response.isDisambiguation()) {
            return ResponseEntity.ok(response.disambiguation());
        }
        return ResponseEntity.ok(response);
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Vector-only RAG (search full collection) | Graph-constrained RAG (search only cone FQNs) | Phase 11 design decision | Precision improvement: results are structurally related, not just textually similar |
| Cosine similarity ranking | Weighted re-rank (vector + graph proximity + risk) | Phase 11 design decision | Migration-relevant ordering: high-risk, tightly-coupled classes surface first |
| `matchKeyword` (single value) | `matchKeywords` (list of values) | Available in Qdrant client 1.13.0 | Enables cone-constrained search without post-filtering |

## Open Questions

1. **Focal class chunk text source for embedding**
   - What we know: Qdrant does NOT store chunk text as payload (Phase 8 finding). The focal class
     FQN and source path are in Neo4j.
   - What's unclear: Should the RAG service read source text from disk (via `sourceFilePath` from
     Neo4j), or re-generate the chunk text via ChunkingService logic for a single class?
   - Recommendation: Query Neo4j for `sourceFilePath` of the focal class, read the file from disk
     (same as `includeFullSource` path), use the full file content as the embedding text. This is
     a single-file read and avoids re-running chunking logic. Fall back to simpleName + stereotype
     as embedding text if source file is inaccessible.

2. **Relationship path string construction**
   - What we know: CONTEXT.md requires "DEPENDS_ON (1 hop)", "EXTENDS > IMPLEMENTS (2 hops)"
     format in each `ContextChunk.relationshipPath`.
   - What's unclear: The hop-distance Cypher above returns minimum hop count, but not the
     relationship type sequence along the shortest path. Neo4j can return relationship types in
     a path but variable-length patterns with multiple relationship types make path introspection
     complex.
   - Recommendation (Claude's discretion): Use `"(N hop[s])"` format with hop count and the
     dominant relationship type from the first hop (query `(focal)-[r]->(direct)` for 1-hop
     classes). For 2+ hop classes, use `"multi-hop (N hops)"`. This is simpler and sufficient
     for the AI orchestrator use case. If exact path is needed, consider a separate targeted query
     for direct (1-hop) neighbors.

3. **ConeSummary top domain terms deduplication**
   - What we know: Domain terms in Qdrant are stored as compact JSON strings per chunk
     (Phase 8: "domain terms serialized as compact JSON string to avoid nested-object Qdrant
     payload limitation").
   - What's unclear: Deduplication and frequency counting requires deserializing JSON from each
     chunk's `domainTerms` payload field.
   - Recommendation: Parse `domainTerms` JSON array (each element is `{termId, displayName}`)
     using Jackson `ObjectMapper` or `JsonParser`. Aggregate by termId, count occurrences, take
     top N by count for `coneSummary.topDomainTerms`.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Testcontainers 1.20.4 + AssertJ |
| Config file | none — auto-configured via `@SpringBootTest` |
| Quick run command | `./gradlew test --tests "com.esmp.rag.*" -x vaadin` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| RAG-01 | Focal class cone contains direct and transitive JavaClass nodes with hop distances | integration | `./gradlew test --tests "com.esmp.rag.*RagServiceIntegrationTest*"` | Wave 0 |
| RAG-02 | Qdrant search filtered to cone FQNs returns only cone-member chunks | integration | `./gradlew test --tests "com.esmp.rag.*RagServiceIntegrationTest*"` | Wave 0 |
| RAG-03 | Merged results ordered by finalScore descending; highest-risk/closest classes rank first | integration | `./gradlew test --tests "com.esmp.rag.*RagServiceIntegrationTest*"` | Wave 0 |
| RAG-04 | POST /api/rag/context returns 200 with focalClass + contextChunks + coneSummary | integration | `./gradlew test --tests "com.esmp.rag.*RagControllerIntegrationTest*"` | Wave 0 |
| SLO-01 | findDependencyConeWithHops() for a 50-node cone completes in < 200ms | integration | `./gradlew test --tests "*RagServiceIntegrationTest*slo01*"` | Wave 0 |
| SLO-02 | Full RAG assembly for 50-node cone completes in < 1500ms | integration | `./gradlew test --tests "*RagServiceIntegrationTest*slo02*"` | Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "com.esmp.rag.*" -x vaadin`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/com/esmp/rag/application/RagServiceIntegrationTest.java` — covers RAG-01, RAG-02, RAG-03, SLO-01, SLO-02
- [ ] `src/test/java/com/esmp/rag/api/RagControllerIntegrationTest.java` — covers RAG-04 (disambiguation, full pipeline, 404 for unknown FQN)
- [ ] Shared test fixtures: reuse existing `src/test/resources/fixtures/pilot/` Java files (20 fixture classes) + Testcontainers setup pattern from `VectorSearchIntegrationTest`
- [ ] SLO test fixtures: reuse incremental fixture stubs from `src/test/resources/fixtures/incremental/` (97 bulk stubs) for a 50-node cone; add DEPENDS_ON edges via Neo4j client setup in test

## Sources

### Primary (HIGH confidence)

- Source inspection: `src/main/java/com/esmp/graph/application/GraphQueryService.java` — confirmed `findDependencyCone()` returns ConeNode(fqn, labels) with no hop distance
- Source inspection: `src/main/java/com/esmp/vector/application/VectorSearchService.java` — confirmed current filter API supports module/stereotype/chunkType only; no FQN list filter
- Source inspection: `src/main/java/com/esmp/vector/api/ChunkSearchResult.java` — confirmed text is NOT a field; `domainTerms` is compact JSON string
- Source inspection: `gradle/libs.versions.toml` — confirmed `qdrant-client = "1.13.0"`
- GitHub: `qdrant/java-client` v1.13.0 `ConditionFactory.java` — confirmed `matchKeywords(String field, List<String> keywords)` method exists
- Source inspection: `src/main/java/com/esmp/extraction/config/RiskWeightConfig.java` — `@ConfigurationProperties` pattern for RagWeightConfig

### Secondary (MEDIUM confidence)

- Project memory (MEMORY.md) — Phase 7: Cypher `min()` aggregation gotcha (CASE WHEN clamping pattern)
- Project memory (MEMORY.md) — Phase 8: `retrieveAsync` NPE with null WithVectorsSelector; domain terms as compact JSON string
- Project memory (MEMORY.md) — Phase 3: FQN path variable truncation, `:.+` regex fix, Neo4jClient for variable-length Cypher
- Project memory (MEMORY.md) — Phase 9: static `setUpDone` pattern for integration test ordering with Testcontainers

### Tertiary (LOW confidence)

- None

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — all libraries are already in the project with verified versions
- Architecture: HIGH — all patterns are verified against existing source code in this codebase
- `matchKeywords` API: HIGH — verified against GitHub source of Qdrant Java client 1.13.0 tag
- Hop-distance Cypher: HIGH — pattern derived directly from existing `findDependencyCone()` Cypher with confirmed `min(length(path))` Neo4j 5.x compatibility (Phase 6 uses same pattern in `findServiceDependents`)
- Pitfalls: HIGH — all critical pitfalls sourced from project memory of actual bugs hit in Phases 3-10
- SLO feasibility: MEDIUM — parallel execution budget analysis is theoretical; actual timing depends on hardware and JVM state

**Research date:** 2026-03-18
**Valid until:** 2026-04-18 (stable stack, no fast-moving dependencies)
