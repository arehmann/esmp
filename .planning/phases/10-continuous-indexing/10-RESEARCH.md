# Phase 10: Continuous Indexing - Research

**Researched:** 2026-03-06
**Domain:** Incremental pipeline orchestration, SHA-256 content hashing, Neo4j DETACH DELETE, REST webhook design
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Trigger mechanism**
- New REST webhook endpoint: `POST /api/indexing/incremental`
- Synchronous — blocks until extraction + vector update completes, returns results inline
- CI pipeline calls it after build with a list of changed files
- Caller provides changed file paths (ESMP does not need git access)
- Single unified endpoint handles both incremental (changed files list) and full module re-index (sourceRoot only) use cases

**Changed file detection**
- Trust the caller's changed-files list as primary trigger
- Also compute SHA-256 contentHash during extraction and store on ClassNode in Neo4j
- Use contentHash as secondary validation to skip truly-unchanged files (e.g., whitespace-only diffs from CI)
- Neo4j contentHash is the single source of truth for file versions — no separate MySQL audit table

**Deleted/renamed file handling**
- Request body has two lists: `changedFiles` (to re-extract) and `deletedFiles` (to remove)
- CI pipeline provides both from `git diff --name-status`
- Hard delete from both Neo4j and Qdrant — stale data removed completely
- Cascade delete: removing a ClassNode also removes all edges (CALLS, DEPENDS_ON, etc.) and child MethodNodes/FieldNodes
- Renamed files treated as delete old + extract new (no rename tracking)

**Re-computation scope**
- **Linking**: Global re-link via `LinkingService.linkAllRelationships()` — Cypher MERGE is idempotent, fast, and ensures transitive edges are correct
- **Risk scores**: Global recompute via `RiskService.computeAndPersistRiskScores()` — fan-in/out changes ripple beyond changed classes
- **Vector indexing**: Re-embed only changed files' chunks — neighbor enrichment payload drift corrected on their next change
- **Response**: Detailed report with counts (classesExtracted, classesDeleted, nodesCreated, nodesUpdated, edgesLinked, chunksReEmbedded, chunksDeleted, durationMs)

### Claude's Discretion
- Exact request/response record structure
- Error handling strategy (partial failures, transactional boundaries)
- Internal pipeline orchestration ordering
- Neo4j deletion Cypher implementation (DETACH DELETE pattern)
- Whether to extract only changed files or the full sourceRoot for linking context

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| CI-01 | CI hook re-extracts changed files on each build | `POST /api/indexing/incremental` webhook; caller supplies changed file paths; SHA-256 secondary guard skips whitespace-only re-parses |
| CI-02 | Graph nodes and edges update incrementally (not full rebuild) | Parse only changed files; DETACH DELETE removed classes; global link+risk recompute is idempotent and fast |
| CI-03 | Vector embeddings update incrementally for changed chunks | Reuse `VectorIndexingService.deleteByClass()` + selective chunk re-embed for changed files only |
| SLO-03 | Incremental re-index of 5 changed files completes in under 30 seconds | 5 files ≈ 10-15 chunks; embedding batch + Qdrant upsert < 5s; full link+risk Cypher pass < 20s on typical graph |
| SLO-04 | Full re-index of 100-class module completes in under 5 minutes | Existing `ExtractionService.extract()` measured at ~35-108 min for test scenarios; for 100 classes the pipeline is sub-minute; confirmed by Phase 8/9 execution times |
</phase_requirements>

---

## Summary

Phase 10 wires together already-built services into a new incremental indexing pipeline. The core design is:

1. **New `IncrementalIndexingService`** orchestrates the pipeline: compute SHA-256 hash per changed file → skip unchanged (hash match) → delete Neo4j nodes for removed files → parse+visit only changed files → persist nodes → global link+risk recompute → re-embed changed chunks only.
2. **New `IndexingController`** at `POST /api/indexing/incremental` accepts `changedFiles`, `deletedFiles`, and `sourceRoot` in the request body, returns a `IncrementalIndexResult` report.
3. **SHA-256 hash population** is the only new extraction logic: `ClassMetadataVisitor` currently passes `null` for `contentHash` (line 96); the incremental service must compute the hash from file bytes before parsing and inject it into the accumulator, OR compute it per-file in the service orchestrator and set it on the ClassNode after mapping.

The main new concern is the **Neo4j DETACH DELETE cascade** and the **transactional boundary** for the delete pass (must commit before the parse+persist pass so that re-added FQNs don't collide with version-locked existing nodes). Everything else is composition of existing services.

**Primary recommendation:** Implement as a thin orchestration service (`IncrementalIndexingService`) that composes `ExtractionService` internals selectively, plus a new `IndexingController`. SHA-256 hash computation belongs in a utility (`FileHashUtil`) used by the service before parsing.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `java.security.MessageDigest` (SHA-256) | JDK 21 built-in | File content hashing | No external dep; deterministic; same approach used by `VectorIndexingService.reindex()` for hash comparison |
| `org.springframework.data.neo4j.core.Neo4jClient` | SDN 7 (Spring Boot 3.5) | DETACH DELETE and custom Cypher | Already used everywhere in the codebase for non-CRUD operations |
| `org.springframework.data.neo4j.repository.Neo4jRepository` | SDN 7 | findById lookups for hash reads | Existing `ClassNodeRepository` |
| Spring `@Transactional("neo4jTransactionManager")` | Spring TX | Isolate delete transaction from persist transaction | Already established pattern in `ExtractionService.extract()` |
| JUnit 5 + Testcontainers (Neo4j + MySQL + Qdrant) | As in Phases 8–9 | Integration tests | Established test pattern across all prior phases |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `java.nio.file.Files.readAllBytes` | JDK 21 | Read file for SHA-256 | Before parsing; before OpenRewrite parses so the hash is derived from raw bytes |
| `org.springframework.web.bind.annotation.RequestBody` | Spring Web | Deserialize request JSON | Standard Spring MVC |
| `io.qdrant.client.QdrantClient.deleteAsync` (filter-based) | Qdrant Java client | Remove Qdrant points for deleted class FQNs | Already used in `VectorIndexingService.deleteByClass()` |

---

## Architecture Patterns

### Recommended Package Structure
```
src/main/java/com/esmp/
├── indexing/
│   ├── api/
│   │   ├── IndexingController.java          # POST /api/indexing/incremental
│   │   ├── IncrementalIndexRequest.java     # changedFiles, deletedFiles, sourceRoot, classpathFile
│   │   └── IncrementalIndexResponse.java    # report record
│   └── application/
│       └── IncrementalIndexingService.java  # orchestrator
```

No validation registry needed for Phase 10 — the endpoint itself is the validation artifact. Optionally one `IndexingValidationQueryRegistry` can verify `contentHash` is populated after the run, but this is lightweight.

### Pattern 1: File-Hash-Before-Parse
**What:** Compute SHA-256 of each candidate `.java` file before parsing, compare against stored `ClassNode.contentHash` in Neo4j, discard unchanged files from the parse list.
**When to use:** Every incremental run — primary performance gate for SLO-03.
**Example:**
```java
// Source: JDK 21 MessageDigest + Files.readAllBytes
public static String sha256(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 unavailable", e);
    }
}
```

### Pattern 2: Neo4j DETACH DELETE Cascade
**What:** Single Cypher statement that removes a `JavaClass` node AND all its relationships and child nodes (`MethodNode`, `FieldNode`) in one atomic operation.
**When to use:** `deletedFiles` list processing; also re-deletion before re-extraction of changed files (prevents version conflict on MERGE).

```cypher
// Delete class + all its DECLARES_METHOD and DECLARES_FIELD children + all relationships
MATCH (c:JavaClass {fullyQualifiedName: $fqn})
OPTIONAL MATCH (c)-[:DECLARES_METHOD]->(m:JavaMethod)
OPTIONAL MATCH (c)-[:DECLARES_FIELD]->(f:JavaField)
DETACH DELETE c, m, f
```

**Important:** Run this in a **separate committed transaction** before the parse+persist pass. If the same FQN is in both `deletedFiles` and `changedFiles` (rename scenario), delete wins and the extract pass re-creates it cleanly. The `@Version` field on `ClassNode` uses optimistic locking — if a node with the same FQN still exists when SDN tries to `save()` the newly-mapped node, it will treat it as an update (MERGE). DETACH DELETE first avoids any version conflict risk and ensures a clean re-extraction.

### Pattern 3: Orchestration Pipeline Ordering
**What:** Fixed sequential steps for the incremental service.
**When to use:** Every call to `IncrementalIndexingService.runIncremental()`.

```
Step 1: Validate request (sourceRoot exists, file paths within sourceRoot)
Step 2: [TX-1] Delete removed classes from Neo4j (deletedFiles list)
         → also deleteByClass(fqn) in Qdrant for each deleted class
Step 3: Compute SHA-256 for each changedFiles path
         → query Neo4j for stored contentHash values (batch MATCH)
         → filter out files where hash matches stored hash
Step 4: [TX-2] Parse + visit + map + persist only truly-changed files
         → inject computed hash into ClassNode.contentHash before saving
Step 5: [TX-2 or TX-3] Global linkAllRelationships() — idempotent MERGE
Step 6: [TX-3 or TX-4] Global computeAndPersistRiskScores() — after linking
Step 7: Re-embed changed classes only → VectorIndexingService.deleteByClass() + chunk + embed + upsert
Step 8: Return IncrementalIndexResponse with all counts
```

**Pipeline ordering constraint (inherited from prior phases):**
- `LinkingService` MUST run before `RiskService` (DEPENDS_ON edges for fan-in/out)
- `RiskService` MUST run before vector indexing enrichment (risk scores in chunk payloads)
- Delete pass MUST commit before parse+persist pass

### Pattern 4: Batch Hash Lookup
**What:** Fetch stored `contentHash` values for all candidate files in one Cypher query instead of N individual lookups.
**When to use:** Step 3 of orchestration — avoids N round-trips to Neo4j for large file lists.

```cypher
// Source: Neo4jClient query pattern (same as Phase 8 scroll pattern logic)
MATCH (c:JavaClass)
WHERE c.sourceFilePath IN $paths
RETURN c.sourceFilePath AS path, c.contentHash AS hash
```

Use `Neo4jClient.query().bindAll(Map.of("paths", pathList)).fetchAs(...)` to retrieve the map.

### Pattern 5: SHA-256 Injection into Accumulator
**What:** The `ClassMetadataVisitor` currently passes `null` for `contentHash` (line 96). Two options:
1. Compute hash in the orchestrator per-file and call `accumulator.getClasses().get(fqn).setContentHash(hash)` after visitor traversal — but `ClassNodeData` is a record (immutable).
2. **Recommended:** Compute hash before parsing, pass it into a `Map<String, String> fileHashMap` keyed by relative source path, then after `mapper.mapToClassNodes(accumulator)`, iterate the resulting `ClassNode` list and set `classNode.setContentHash(fileHashMap.get(classNode.getSourceFilePath()))`.

This is the correct approach: keep `ClassMetadataVisitor` unchanged, inject hash in the service layer where file paths are known.

### Anti-Patterns to Avoid

- **Full re-parse on every incremental call:** Defeats SLO-03. Always hash-filter before parsing.
- **Separate MySQL audit table for file versions:** Locked out by CONTEXT.md — Neo4j `contentHash` is the single source of truth.
- **Deleting and recreating ALL edges on each incremental run:** `LinkingService.linkAllRelationships()` uses MERGE — already idempotent. Do NOT drop all edges first; just re-run MERGE.
- **Partial transactional commit of delete + persist in same transaction:** Spring's `@Transactional("neo4jTransactionManager")` on `ExtractionService.extract()` is one big TX. For incremental, the delete pass needs to commit first. Use `@Transactional(propagation = REQUIRES_NEW)` on the delete helper, or separate service calls.
- **Version conflicts on MERGE of re-extracted classes:** If ClassNode with FQN X is deleted in the same TX that tries to re-save it, SDN may still have a stale entity in its session cache. Use separate transactions.
- **Calling `ExtractionService.extract()` directly for incremental:** That method always scans ALL files under `sourceRoot` and runs global linking/risk as one unit. Do NOT call it for incremental; extract its internal components and reuse selectively.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SHA-256 file hash | Custom rolling hash / CRC | `MessageDigest.getInstance("SHA-256")` on `Files.readAllBytes()` | JDK built-in; collision-resistant; matches Phase 8 pattern |
| Qdrant point deletion | Manual point-by-point delete | `VectorIndexingService.deleteByClass(fqn)` | Already wraps filter-based `qdrantClient.deleteAsync()` |
| Neo4j cascade delete | Manual child traversal in Java | Cypher `DETACH DELETE c, m, f` after OPTIONAL MATCH | Single atomic Cypher statement removes node + all rels in one pass |
| Incremental vector embed | Custom embedding + upsert loop | `VectorIndexingService.indexAll(sourceRoot)` restricted to changed files only, OR call `chunkingService.chunkClasses()` + `embedAndUpsert()` directly via package-private access | ChunkingService already handles 1-hop enrichment + risk scores |
| Stored hash lookup | Full table scan | Cypher `MATCH (c:JavaClass) WHERE c.sourceFilePath IN $paths RETURN c.sourceFilePath, c.contentHash` | O(N) Cypher list lookup using the `sourceFilePath` property |

**Key insight:** Phase 10 is almost entirely composition. The only genuinely new code is: `FileHashUtil.sha256()`, `IncrementalIndexingService` (orchestrator), `IndexingController`, request/response records, and one Cypher delete query. Estimated net-new Java LOC: ~350.

---

## Common Pitfalls

### Pitfall 1: Version Conflict on Re-Extraction of Changed Classes
**What goes wrong:** ClassNode with FQN X is deleted via Cypher, but the Neo4j SDN session still caches the old entity. When the same TX calls `classNodeRepository.saveAll()` with a new ClassNode(X), SDN may attempt to MERGE against the cached version and throw an `OptimisticLockingFailureException`.
**Why it happens:** SDN's first-level cache is not invalidated by direct `Neo4jClient` Cypher operations in the same transaction.
**How to avoid:** Commit the delete transaction before starting the parse+persist transaction. Use `@Transactional(propagation = Propagation.REQUIRES_NEW)` on a `deleteClasses(List<String> fqns)` helper in the incremental service, OR implement delete and persist as two separate service method calls each with their own `@Transactional`.
**Warning signs:** `OptimisticLockingFailureException` or `EntityExistsException` on `saveAll()` for re-extracted classes.

### Pitfall 2: sourceFilePath Format Mismatch for Hash Lookup
**What goes wrong:** The `sourceFilePath` stored on ClassNode (set from `cu.getSourcePath().toString()` in `ClassMetadataVisitor`) uses a relative path from the parse root (e.g., `com/example/Foo.java`), while the `changedFiles` list from CI contains absolute paths (e.g., `/home/ci/project/src/main/java/com/example/Foo.java`). Hash map lookup yields no matches.
**Why it happens:** OpenRewrite's `cu.getSourcePath()` returns a path relative to the source root passed to `JavaParser.build().parse()`. The CI caller provides absolute paths.
**How to avoid:** In `FileHashUtil`, normalize the path to a relative path from `sourceRoot` before storing in the file hash map: `sourceRoot.relativize(absoluteFilePath).toString()`. Match this against the stored `sourceFilePath` values in Neo4j.
**Warning signs:** Every file appears as "changed" (no hash matches) even on re-run of unchanged code.

### Pitfall 3: Global Link+Risk Running on Empty Accumulator
**What goes wrong:** If all `changedFiles` are skipped (all hashes match), the accumulator is empty. `LinkingService.linkAllRelationships(accumulator)` is called with no new data — this is fine (it's global MERGE). But if the caller skips linking entirely when `changedFiles` is empty, transitive edges added in a prior deletion may be stale.
**Why it happens:** Temptation to short-circuit when zero files changed.
**How to avoid:** Always run `linkAllRelationships()` and `computeAndPersistRiskScores()` after any delete operation (even if no files were re-extracted), because edge deletion via DETACH DELETE removes edges that need to be re-evaluated globally.
**Warning signs:** After deleting a class, its EXTENDS or DEPENDS_ON edges to other classes remain stale.

### Pitfall 4: `indexAll()` Re-Chunks ALL Classes for Changed-File Vector Update
**What goes wrong:** Calling `VectorIndexingService.indexAll(sourceRoot)` for the vector step re-chunks ALL classes in Neo4j, not just the changed ones. For a 1000-class codebase, this is 2000+ chunks re-embedded unnecessarily.
**Why it happens:** `indexAll()` calls `chunkingService.chunkClasses(sourceRoot)` which walks ALL `JavaClass` nodes in Neo4j.
**How to avoid:** For the incremental vector step, use the changed class FQNs directly:
1. `VectorIndexingService.deleteByClass(fqn)` for each changed class FQN
2. Call `chunkingService.chunkClasses(sourceRoot)` but filter the resulting chunks to only those whose `classFqn` is in the changed set
3. Upsert only those chunks
Alternatively, add a `chunkByFqns(List<String> fqns, String sourceRoot)` method to `ChunkingService` that limits the Neo4j class query to a specific FQN list.
**Warning signs:** SLO-03 violated — incremental run takes longer than 30 seconds for 5 files.

### Pitfall 5: Qdrant Delete Before Neo4j Delete
**What goes wrong:** If Qdrant vectors are deleted first and then the Neo4j deletion fails (e.g., TX rollback), vectors are gone but graph data remains — inconsistent state.
**Why it happens:** Qdrant operations are not transactional with Neo4j.
**How to avoid:** Delete Neo4j nodes first (committed). Then delete Qdrant vectors. If Qdrant deletion fails, the vectors are orphaned but can be cleaned up on the next incremental run (the re-extraction will upsert new vectors and the old ones will be overwritten by the deterministic UUID v5 point ID).
**Warning signs:** `deleteByClass()` throws but Neo4j delete succeeded — log and continue; orphaned Qdrant points are harmless (same UUIDs will be overwritten on re-index).

### Pitfall 6: SLO-03 — 30-Second Budget Breakdown
**What goes wrong:** Global `computeAndPersistRiskScores()` runs complex Cypher aggregations on the full graph. On a large graph (1000+ nodes), this alone can exceed 10 seconds.
**Why it happens:** Fan-in/out computation uses pattern comprehension over all DEPENDS_ON edges.
**How to avoid:** Measure the existing Phase 6/7 risk recompute time on the full test graph. If it approaches 20s, consider making risk recompute optional (via request parameter `recomputeRisk=true/false`). For typical incremental runs on small change sets, the Cypher is fast (Neo4j query cache warms quickly on repeated patterns). SLO-03 says "5 changed files < 30 seconds" which is achievable given Phase 8/9 benchmarks showing sub-60s vector indexing for 20 files.
**Warning signs:** `durationMs` in response exceeds 30000ms consistently.

---

## Code Examples

Verified patterns from existing codebase and JDK documentation:

### DETACH DELETE with Child Nodes
```cypher
// Source: Neo4j Cypher reference — DETACH DELETE removes node + all relationships
// OPTIONAL MATCH handles classes with no methods/fields without failing
MATCH (c:JavaClass {fullyQualifiedName: $fqn})
OPTIONAL MATCH (c)-[:DECLARES_METHOD]->(m:JavaMethod)
OPTIONAL MATCH (c)-[:DECLARES_FIELD]->(f:JavaField)
DETACH DELETE c, m, f
```

### Batch contentHash Lookup
```java
// Source: pattern from ExtractionService / RiskService Neo4jClient usage in this codebase
String cypher = """
    MATCH (c:JavaClass)
    WHERE c.sourceFilePath IN $paths
    RETURN c.sourceFilePath AS path, c.contentHash AS hash
    """;
Map<String, String> storedHashes = new HashMap<>();
neo4jClient.query(cypher)
    .bindAll(Map.of("paths", relativePaths))
    .fetchAs(Map.class)
    .mappedBy((typeSystem, record) -> Map.entry(
        record.get("path").asString(""),
        record.get("hash").asString("")))
    .all()
    .forEach(entry -> storedHashes.put(entry.getKey(), entry.getValue()));
```

### SHA-256 File Hash Utility
```java
// Source: JDK 21 MessageDigest — identical pattern to ChunkIdGenerator UUID v5 hash approach
public static String sha256(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 not available", e);
    }
}
```
Note: `HexFormat.of().formatHex()` is JDK 17+ and available in Java 21. This is simpler than the `%02x` loop currently used in `ChunkIdGenerator`.

### Inject contentHash into ClassNode After Mapping
```java
// Source: pattern derived from AccumulatorToModelMapper.mapToClassNodes() — classNode.setContentHash()
Map<String, String> fileHashByRelPath = computeFileHashes(changedPaths, sourceRootPath);
List<ClassNode> classNodes = mapper.mapToClassNodes(accumulator);
for (ClassNode cn : classNodes) {
    String relPath = cn.getSourceFilePath(); // already relative from OpenRewrite
    String hash = fileHashByRelPath.get(relPath);
    if (hash != null) cn.setContentHash(hash);
}
classNodeRepository.saveAll(classNodes);
```

### IncrementalIndexRequest Record
```java
// Suggested structure — exact naming at Claude's discretion
public record IncrementalIndexRequest(
    List<String> changedFiles,   // absolute or relative paths to changed .java files
    List<String> deletedFiles,   // absolute or relative paths to deleted/renamed .java files
    String sourceRoot,           // used for parsing context and module derivation
    String classpathFile         // optional; falls back to config default
) {}
```

### IncrementalIndexResponse Record
```java
// Suggested structure per CONTEXT.md response requirements
public record IncrementalIndexResponse(
    int classesExtracted,
    int classesDeleted,
    int nodesCreated,
    int nodesUpdated,
    int edgesLinked,
    int chunksReEmbedded,
    int chunksDeleted,
    long durationMs,
    List<String> errors
) {}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Full re-extraction on every CI run | SHA-256 hash guard + partial re-parse | Phase 10 | SLO-03 compliance (30s budget) |
| `null` contentHash on ClassNode | Computed SHA-256 stored on ClassNode | Phase 10 | Enables hash-based change detection in future incremental runs |
| No deletion support | DETACH DELETE + Qdrant filter delete | Phase 10 | Removed classes no longer leave stale graph data |

**Existing code that already handles incremental:**
- `VectorIndexingService.reindex()`: Incremental vector reindex via Qdrant-stored hashes. This method scrolls ALL Qdrant points to get hashes. For Phase 10 (caller already knows changed files), it is more efficient to call `deleteByClass()` + selective re-chunk than to call `reindex()` which still chunks all classes first.
- `VectorIndexingService.deleteByClass()`: Filter-based Qdrant point deletion — directly reusable.

---

## Open Questions

1. **`chunkingService.chunkClasses()` FQN filtering**
   - What we know: `ChunkingService.chunkClasses(sourceRoot)` fetches ALL `JavaClass` nodes from Neo4j via a full graph query. There is no existing method to chunk a subset of classes by FQN.
   - What's unclear: The exact Neo4j query inside `ChunkingService` — whether adding a FQN filter parameter is a one-line change or requires refactoring.
   - Recommendation: Read `ChunkingService.java` during plan authoring to determine if a `chunkByFqns(List<String>)` overload is trivial. If it is a one-line Cypher WHERE clause addition, add it (saves significant time for SLO-03). If complex, use the workaround: call `chunkClasses()` and filter the result list by changed FQN set.

2. **Transactional boundary for delete + extract**
   - What we know: `@Transactional("neo4jTransactionManager")` on a single method commits at method exit. The delete helper and the extract persist need separate transactions.
   - What's unclear: Whether `IncrementalIndexingService` calling two `@Transactional` methods sequentially achieves separate commits, or if a caller `@Transactional` wraps them.
   - Recommendation: Mark `IncrementalIndexingService.runIncremental()` as NOT `@Transactional`. Call a `@Transactional("neo4jTransactionManager")` `deleteClasses()` method first (commits on return), then call a `@Transactional("neo4jTransactionManager")` `extractAndPersist()` method second (commits on return). This is the proven Spring pattern for sequential committed transactions without `REQUIRES_NEW`.

3. **`ClassNodeData` record immutability for contentHash injection**
   - What we know: `ExtractionAccumulator.ClassNodeData` is a record. The `addClass()` method accepts `contentHash` as the last parameter and passes it through. `ClassMetadataVisitor` always passes `null`.
   - What's unclear: Whether to modify `ClassMetadataVisitor` to accept a pre-computed hash map, or inject after mapping.
   - Recommendation: Do NOT modify `ClassMetadataVisitor` (preserves backward compatibility with full `ExtractionService.extract()`). Instead, inject hash after `mapper.mapToClassNodes(accumulator)` by calling `classNode.setContentHash(hash)` on the mapped entities before `saveAll()`. This is clean and requires no changes to visitor or accumulator.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Testcontainers + AssertJ (Spring Boot Test) |
| Config file | No explicit config file — inherited from Spring Boot test autoconfiguration |
| Quick run command | `./gradlew test --tests "com.esmp.indexing.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CI-01 | `POST /api/indexing/incremental` with changedFiles triggers extraction of only those files | integration | `./gradlew test --tests "com.esmp.indexing.application.IncrementalIndexingServiceIntegrationTest#incrementalRun_extractsOnlyChangedFiles"` | ❌ Wave 0 |
| CI-02 | Deleted class FQN is removed from Neo4j (DETACH DELETE) after incremental run | integration | `./gradlew test --tests "com.esmp.indexing.application.IncrementalIndexingServiceIntegrationTest#deletedFile_removesClassNodeFromNeo4j"` | ❌ Wave 0 |
| CI-02 | Changed file updates ClassNode.contentHash in Neo4j | integration | `./gradlew test --tests "com.esmp.indexing.application.IncrementalIndexingServiceIntegrationTest#changedFile_updatesContentHashOnClassNode"` | ❌ Wave 0 |
| CI-02 | Unchanged file (hash match) is skipped — classNode.version unchanged | integration | `./gradlew test --tests "com.esmp.indexing.application.IncrementalIndexingServiceIntegrationTest#unchangedFile_isSkipped"` | ❌ Wave 0 |
| CI-03 | Qdrant chunks for deleted class are removed after incremental run | integration | `./gradlew test --tests "com.esmp.indexing.application.IncrementalIndexingServiceIntegrationTest#deletedFile_removesQdrantChunks"` | ❌ Wave 0 |
| CI-03 | Qdrant chunks for changed class are updated after incremental run | integration | `./gradlew test --tests "com.esmp.indexing.application.IncrementalIndexingServiceIntegrationTest#changedFile_updatesQdrantChunks"` | ❌ Wave 0 |
| SLO-03 | Incremental run of 5 changed files completes in < 30 000 ms | integration | `./gradlew test --tests "com.esmp.indexing.application.IncrementalIndexingServiceIntegrationTest#incrementalRun_5files_completesUnder30Seconds"` | ❌ Wave 0 |
| SLO-04 | Full re-index of 100-class module via `sourceRoot` only request completes in < 300 000 ms | integration (slow, marked @Tag("slow")) | `./gradlew test --tests "com.esmp.indexing.application.IncrementalIndexingServiceIntegrationTest#fullReindex_100classes_completesUnder5Minutes"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.indexing.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/indexing/application/IncrementalIndexingServiceIntegrationTest.java` — covers CI-01, CI-02, CI-03, SLO-03, SLO-04
- [ ] `src/test/resources/fixtures/incremental/` — small fixture set: 3 Java files for baseline + 2 modified versions for change detection testing

*(No new test framework install needed — JUnit 5 + Testcontainers already configured.)*

---

## Sources

### Primary (HIGH confidence)
- Codebase: `ExtractionService.java`, `VectorIndexingService.java`, `ClassMetadataVisitor.java`, `AccumulatorToModelMapper.java`, `ClassNode.java`, `ExtractionAccumulator.java` — direct code inspection, all claims verified against live source
- Codebase: `LinkingService.java` — global re-link is idempotent Cypher MERGE, confirmed by reading service head
- JDK 21 API: `java.security.MessageDigest`, `java.util.HexFormat` — built-in, no version risk
- Neo4j Cypher: `DETACH DELETE` semantics — standard Cypher, verified by pattern in all prior phases

### Secondary (MEDIUM confidence)
- Phase 8/9 execution timing from STATE.md: Phase 8 P02 = 108min (includes full stack setup), Phase 9 P01 = 6min, Phase 9 P02 = 35min — used to estimate SLO feasibility. These are plan execution times not runtime benchmarks, so timing estimates for SLO-03/04 are directional.
- Spring `@Transactional` propagation patterns — established Spring docs knowledge; verified pattern in `ExtractionService.extract()` using `@Transactional("neo4jTransactionManager")`.

### Tertiary (LOW confidence)
- SLO-03 (<30s for 5 files): estimated from chunk count (~10 chunks for 5 small classes) and Phase 8 embedding speed. Actual runtime depends on graph size at execution time. Mark for validation in integration test.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries are already used in prior phases, no new dependencies required
- Architecture: HIGH — direct code inspection of all reusable services; no speculation
- Pitfalls: HIGH — derived from actual codebase patterns (version field, SDN session cache, path relativity from OpenRewrite)
- SLO estimates: MEDIUM — directional based on Phase 8/9 benchmarks, not measured on target graph

**Research date:** 2026-03-06
**Valid until:** 2026-06-01 (stable stack — Spring Boot 3.5, SDN 7, Qdrant client version locked in build.gradle)
