# Phase 9: Golden Module Pilot - Research

**Researched:** 2026-03-06
**Domain:** End-to-end pipeline validation, synthetic test fixtures, Qdrant vector search, pilot orchestration
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **Module selection**: Automated recommendation with manual override — Cypher query scores modules by Vaadin 7 stereotype count, risk score diversity, and class count. User can override via API parameter.
- **Synthetic test fixtures as pilot target**: Create a realistic multi-class test module with Vaadin 7 patterns (views, services, repos, entities, domain terms) — controlled, verifiable, exercises all pipeline stages.
- **Medium size (15-40 classes)**: Enough variety for meaningful graph relationships, risk distribution, and domain terms while still manually verifiable.
- **Both automated assertions + manual checklist**: Integration tests assert objective criteria; manual review for subjective quality.
- **Both markdown report + REST API**: `GET /api/pilot/validate/{module}` returns JSON; also generates a structured markdown report for human review.
- **Full node + edge coverage**: Verify all node types (ClassNode, MethodNode, FieldNode, AnnotationNode, PackageNode, BusinessTermNode) and all 9 relationship types.
- **Pass/fail per check, no composite score**: Each validation check reports pass/fail/warning — composite confidence score deferred.
- **Vector similarity + manual graph check**: Automated Qdrant embedding search validates returned chunks; manually verify graph neighbors appear in enrichment payloads.
- **New search endpoint**: `POST /api/vector/search` — accept text query, embed it, search Qdrant, return ranked chunks with full payloads. Phase 11 RAG will build on this.
- **Both precision + enrichment quality**: For known query classes, verify that top-K results are relevant AND chunk payloads contain correct callers/callees, domain terms, risk scores, Vaadin patterns.
- **3-5 test queries**: One per major class type (service, repo, view, entity, utility).
- **Fix critical, defer minor**: Critical pipeline correctness issues fixed immediately; cosmetic/optimization issues documented.
- **Lightweight golden regression tests**: A few key assertions (chunk count, enrichment fields present, search relevance) as permanent integration tests.
- **Pragmatic exit criteria**: All critical issues fixed + minor issues documented.
- **Lightweight migration readiness assessment**: Basic stats per module (Vaadin 7 class count, avg risk score, domain term coverage %) without full migration recommendations.

### Claude's Discretion
- Exact synthetic fixture design (class names, relationships, Vaadin 7 patterns)
- Module scoring Cypher query design and weight tuning
- Validation report structure and section organization
- Search endpoint response format and pagination
- Integration test strategy (Testcontainers vs in-memory)
- Which enrichment fields to include in golden regression assertions

### Deferred Ideas (OUT OF SCOPE)
- None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| GMP-01 | One bounded context selected and fully processed through chunking, domain enrichment, and vector indexing | Synthetic fixture module + PilotOrchestrationService calling existing pipeline services; `GET /api/pilot/recommend` + `GET /api/pilot/validate/{module}` |
| GMP-02 | RAG retrieval for pilot module returns contextually relevant results validated by senior engineers | `POST /api/vector/search` endpoint with EmbeddingModel + QdrantClient searchAsync; enrichment payload validation in integration tests |
| GMP-03 | Risk computation and migration recommendation for pilot module aligns with expert expectations | PilotValidationService checks risk score population, Vaadin 7 class stats, domain term coverage; markdown report summarizes findings |
</phase_requirements>

---

## Summary

Phase 9 is fundamentally an **integration and validation phase**, not a new pipeline phase. The entire Phases 2-8 pipeline already exists and works. This phase creates a controlled synthetic module (15-40 classes), runs the full pipeline against it, adds the one missing capability (`POST /api/vector/search`), and validates that every pipeline stage produces correct output.

The implementation pattern is well-established. Every new component follows the existing layered architecture: Controller -> Service -> Neo4jClient/QdrantClient. The `ValidationQueryRegistry` extensibility pattern from Phase 4 handles pilot-specific checks via a new `PilotValidationQueryRegistry`. A new `com.esmp.pilot` package hosts the orchestration and validation service.

The biggest design decision is the synthetic fixture module: it must be complex enough to trigger all code paths (Vaadin views, services, repositories, entities, domain term generation via LexiconVisitor) while staying small enough to manually verify (~20 classes is the sweet spot). Source files must be written to disk so that `ChunkingService.chunkClasses()` can read them — this is the primary coupling between the synthetic module and the existing pipeline.

**Primary recommendation:** Create a `com.example.pilot` package with ~20 synthetic Java source files that serve as both the pilot module fixture AND the golden regression test substrate. The new `POST /api/vector/search` endpoint is the most architecturally important new code; all other components are orchestration wrappers around existing services.

## Standard Stack

### Core (Existing — All Already in Classpath)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring AI TransformersEmbeddingModel | 1.1.2 | Embed query text for vector search | Already autowired as `EmbeddingModel` bean |
| Qdrant Java Client | Latest (grpc) | `searchAsync()` for similarity search | Already configured as `QdrantClient` bean |
| Spring Data Neo4j / Neo4jClient | Spring Boot 3.5.x | Pilot module scoring Cypher queries | Established pattern across all services |
| Testcontainers (Neo4j + MySQL + Qdrant) | Latest | Integration test infrastructure | Used in VectorIndexingServiceIntegrationTest |
| JUnit 5 / AssertJ | Spring Boot 3.5.x | Test assertions | Project-wide standard |

### New Components This Phase
| Component | Package | Purpose |
|-----------|---------|---------|
| PilotController | `com.esmp.pilot.api` | `GET /api/pilot/recommend`, `GET /api/pilot/validate/{module}` |
| PilotService | `com.esmp.pilot.application` | Module recommendation Cypher, pilot validation orchestration, markdown report generation |
| PilotValidationQueryRegistry | `com.esmp.pilot.validation` | Extends `ValidationQueryRegistry` with pilot-specific checks |
| VectorSearchController | `com.esmp.vector.api` | `POST /api/vector/search` |
| VectorSearchService | `com.esmp.vector.application` | Embeds query, calls Qdrant searchAsync, maps results |
| SearchRequest / SearchResponse | `com.esmp.vector.api` | Request/response records |
| ModuleRecommendation | `com.esmp.pilot.api` | Response record for `GET /api/pilot/recommend` |
| PilotValidationReport | `com.esmp.pilot.api` | Response record combining ValidationReport + pilot-specific metrics |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `java.nio.file` | JDK 21 | Write synthetic fixture `.java` files to temp dirs in tests | Already used in VectorIndexingServiceIntegrationTest |
| Jackson ObjectMapper | Spring Boot default | Serialize/deserialize Qdrant payload JSON (domain terms already use hand-rolled JSON) | Only if search response needs nested objects |

## Architecture Patterns

### Recommended Project Structure
```
com.esmp/
├── pilot/
│   ├── api/
│   │   ├── PilotController.java          # GET /api/pilot/recommend, GET /api/pilot/validate/{module}
│   │   ├── ModuleRecommendation.java     # record: moduleName, score, rationale, vaadin7Count, classCount
│   │   └── PilotValidationReport.java   # record: ValidationReport + pilot metrics + markdownReport
│   ├── application/
│   │   └── PilotService.java            # recommendation Cypher, orchestration, markdown generation
│   └── validation/
│       └── PilotValidationQueryRegistry.java  # extends ValidationQueryRegistry, pilot-specific checks
└── vector/
    └── api/
        ├── VectorSearchController.java  # POST /api/vector/search
        ├── SearchRequest.java           # record: query, limit, filter fields
        └── SearchResponse.java          # record: List<ChunkSearchResult>
                                         # ChunkSearchResult: score, classFqn, chunkType, methodId, text, payload...
```

### Pattern 1: Pilot Validation via Existing ValidationService
The `ValidationService` already accepts `List<ValidationQueryRegistry>` via Spring injection. Adding a `PilotValidationQueryRegistry` bean automatically includes its queries in `GET /api/graph/validation`. Pilot-specific checks should go here.

**What:** Add `PilotValidationQueryRegistry extends ValidationQueryRegistry` with 3-5 pilot-specific Cypher checks.
**When to use:** For objective, count-based assertions (e.g., "pilot module has at least N chunks in Qdrant").

```java
// Source: existing pattern from LexiconValidationQueryRegistry.java
@Component
public class PilotValidationQueryRegistry extends ValidationQueryRegistry {
    public PilotValidationQueryRegistry() {
        super(List.of(
            new ValidationQuery(
                "PILOT_MODULE_HAS_CHUNKS",
                "Pilot module classes have vector chunks in Neo4j contentHash property",
                """
                MATCH (c:JavaClass)
                WHERE c.module = $pilotModule AND c.contentHash IS NOT NULL
                RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
                """,
                ValidationSeverity.WARNING),
            // ... more checks
        ));
    }
}
```

Note: `ValidationQueryRegistry` checks are static Cypher — if pilot module name needs to be dynamic, use `PilotService` for those checks instead of the registry.

### Pattern 2: Module Recommendation Cypher
Score each module by Vaadin 7 stereotype presence, risk diversity, and class count. Use `toFloat()` for safe arithmetic on mixed integer/float properties.

```cypher
// Module scoring query (run in PilotService via Neo4jClient)
MATCH (c:JavaClass)
WHERE c.module IS NOT NULL AND c.module <> ''
WITH c.module AS module,
     count(c) AS classCount,
     sum(CASE WHEN ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding']) THEN 1 ELSE 0 END) AS vaadin7Count,
     avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgRisk,
     stDev(coalesce(c.enhancedRiskScore, 0.0)) AS riskDiversity
WHERE classCount >= 5
WITH module, classCount, vaadin7Count, avgRisk, riskDiversity,
     (0.4 * toFloat(vaadin7Count) / classCount
    + 0.3 * riskDiversity
    + 0.3 * toFloat(CASE WHEN classCount >= 15 AND classCount <= 40 THEN 1 ELSE 0 END)) AS score
RETURN module, classCount, vaadin7Count, avgRisk, riskDiversity, score
ORDER BY score DESC
LIMIT 5
```

**Weights (Claude's discretion):** Vaadin 7 density (0.4) weighted highest since pilot must validate Vaadin pattern detection; risk diversity (0.3) ensures variety; size appropriateness (0.3) enforces 15-40 class constraint.

### Pattern 3: POST /api/vector/search
Embed the query string using the existing `EmbeddingModel` bean, then call `qdrantClient.searchAsync()`. This is the same pattern already used in `VectorIndexingServiceIntegrationTest.similaritySearch_returnsRelevantChunks()`.

```java
// Source: VectorIndexingServiceIntegrationTest.java line 341-368 (verified in codebase)
float[] queryVector = embeddingModel.embed(request.query());

SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
    .setCollectionName(vectorConfig.getCollectionName())
    .setLimit(request.limit() != null ? request.limit() : 10)
    .setWithPayload(WithPayloadSelectorFactory.enable(true));

// Optional: filter by module
if (request.module() != null) {
    searchBuilder.setFilter(Filter.newBuilder()
        .addMust(matchKeyword("module", request.module()))
        .build());
}

for (float v : queryVector) {
    searchBuilder.addVector(v);
}

List<ScoredPoint> results = qdrantClient.searchAsync(searchBuilder.build())
    .get(30, TimeUnit.SECONDS);
```

**Critical import:** `WithPayloadSelectorFactory.enable(true)` and `WithVectorsSelectorFactory.enable(false)` — the `null` variant causes NPE (documented in STATE.md Phase 8 learnings).

### Pattern 4: Synthetic Fixture Module Design
The synthetic pilot module must be written as actual `.java` source files AND corresponding Neo4j nodes + relationships. The `ChunkingService` reads files from disk via `sourceFilePath` stored in Neo4j.

**Fixture class design for `com.example.pilot` package (~20 classes):**

| Class Name | Stereotype | Purpose |
|------------|-----------|---------|
| `InvoiceService` | Service | Business logic, DEFINES_RULE pattern |
| `PaymentService` | Service | Financial domain, USES_TERM edges |
| `CustomerService` | Service | High fan-out, multiple dependencies |
| `InvoiceRepository` | Repository | QUERIES edge to DBTable |
| `CustomerRepository` | Repository | JPA repository |
| `PaymentRepository` | Repository | DB write operations |
| `InvoiceEntity` | Entity | MAPS_TO_TABLE, @Entity |
| `CustomerEntity` | Entity | MAPS_TO_TABLE |
| `PaymentEntity` | Entity | Financial involvement |
| `InvoiceView` | VaadinView | VaadinView label, BINDS_TO |
| `CustomerView` | VaadinView | @SpringView, View interface |
| `PaymentView` | VaadinView | Vaadin data binding |
| `InvoiceForm` | VaadinDataBinding | BeanFieldGroup binding |
| `CustomerForm` | VaadinDataBinding | Form entity binding |
| `InvoiceValidator` | (plain) | DEFINES_RULE detection |
| `PaymentCalculator` | (plain) | Financial heuristic |
| `InvoiceStatusEnum` | (enum) | Domain term extraction |
| `PaymentStatusEnum` | (enum) | Domain term extraction |
| `CustomerRole` | (enum) | Domain term extraction |
| `AuditService` | Service | Security sensitivity heuristics |

This gives: 3 services, 3 repositories, 3 entities, 3 Vaadin views, 2 Vaadin forms, 2 rule/calc classes, 3 enums, 1 security service = 20 classes, covering all stereotype labels and heuristic paths.

### Pattern 5: Pilot Orchestration in PilotService
The pilot orchestration sequence is:
1. Verify pilot module classes exist in Neo4j (query by module name)
2. Run all 32+ existing validation queries via `ValidationService.runAllValidations()`
3. Query Qdrant for chunk count scoped to pilot module
4. Run 3-5 pilot-specific validation checks
5. Generate markdown report sections
6. Return `PilotValidationReport` record

**No extraction trigger needed** — the pilot module is pre-loaded during test setup or before calling the validate endpoint. The `POST /api/vector/index` endpoint already handles chunking + embedding.

### Anti-Patterns to Avoid
- **Embedding the extraction pipeline in PilotService**: Do not call `ExtractionService.extract()` from the pilot service — it runs OpenRewrite on real source files. The pilot uses pre-persisted Neo4j nodes from synthetic fixtures.
- **Rewriting ValidationQuery to support parameters**: The existing `ValidationQuery` record is immutable and Cypher is static. If module-name-scoped checks are needed, implement them in `PilotService` directly using `Neo4jClient` with `.bind()` — do not modify the registry pattern.
- **Using null in Qdrant API calls**: Always pass `WithVectorsSelectorFactory.enable(false)` not `null` to `retrieveAsync` (STATE.md Phase 8 known issue).
- **Qdrant `getVector().getDataCount()` for dimension check**: Returns 0 for single-vector collections; use `getCollectionInfoAsync()` instead (STATE.md Phase 8 known issue).
- **Mixing pilot module into existing test fixtures**: The pilot synthetic fixtures are separate from the existing `src/test/resources/fixtures/` directory. Existing visitor tests use `maxDepth(1)` to avoid contamination.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Text embedding | Custom embedding logic | `EmbeddingModel.embed(List<String>)` — already Spring AI bean | Returns `List<float[]>` directly (verified in VectorIndexingService) |
| Vector similarity search | Custom cosine similarity | `QdrantClient.searchAsync(SearchPoints)` | Already handles HNSW indexing, filtering, scoring |
| Validation query aggregation | Manual query runner | `ValidationService.runAllValidations()` — inject as dependency | Already handles all 32 registries, pass/fail logic, CALLS_EDGE_COVERAGE inversion |
| Module filtering in Qdrant | Custom post-filter | Qdrant payload filter via `Filter.newBuilder().addMust(matchKeyword("module", ...))` | Server-side filtering at search time |
| Markdown report | External template engine | String builder in `PilotService` | No dependency needed for simple structured report |
| UUID point ID generation | Random UUID | `ChunkIdGenerator.chunkId(fqn, methodSig)` | Deterministic UUID v5 — already used for idempotent upserts |

**Key insight:** This phase is 80% orchestration and 20% new code. The hardest part is constructing the synthetic fixture module carefully enough that all pipeline code paths are exercised.

## Common Pitfalls

### Pitfall 1: ChunkingService reads from disk — synthetic files must exist on disk
**What goes wrong:** Creating Neo4j nodes for synthetic fixtures without writing corresponding `.java` files to the configured `sourceFilePath` paths. `ChunkingService.chunkClasses()` skips classes where `Files.exists(path)` is false.
**Why it happens:** The pipeline decouples graph data from source files; Neo4j stores `sourceFilePath` as a relative or absolute string. If it doesn't resolve to an existing file, the class is silently skipped.
**How to avoid:** In integration tests, use `@TempDir` and write all synthetic `.java` files before calling `VectorIndexingService.indexAll()`. Store absolute tempDir paths as `sourceFilePath` in Neo4j nodes (or store relative paths + pass tempDir as `sourceRoot`).
**Warning signs:** `indexAll()` returns `filesProcessed = 0` when Neo4j has classes.

### Pitfall 2: Module name derivation in ChunkingService
**What goes wrong:** Synthetic classes in `com.example.pilot` will produce `module = "example"` from `ChunkingService.deriveModule()`, not "pilot". The `deriveModule()` method strips `com.esmp.` prefix specifically — for other packages it returns the first segment after the second dot.
**Why it happens:** `deriveModule()` is hardcoded: `if (packageName.startsWith("com.esmp."))` returns segment after prefix; otherwise returns first segment via `packageName.substring(prefix.length())` — but for `com.example.pilot`, `remainder = "example.pilot"` and the method returns `"example"`.
**How to avoid:** Use `com.esmp.pilot` as the package name for synthetic fixtures so `deriveModule()` returns `"pilot"`. This aligns with the existing `com.esmp.*` package structure. Module name in Neo4j nodes should also be set to `"pilot"`.
**Warning signs:** Qdrant points have `module = "example"` instead of `"pilot"`.

### Pitfall 3: LexiconVisitor stop-suffix filtering may exclude pilot class names
**What goes wrong:** If synthetic class names end in stop-suffixes (e.g., "Service", "Repository", "Validator"), the LexiconVisitor will strip these suffixes before extracting terms. This is intentional behavior but must be accounted for when designing expected domain term outputs.
**Why it happens:** `LexiconVisitor` uses `STOP_SUFFIXES` to normalize class names. "InvoiceService" → term "Invoice" extracted.
**How to avoid:** Design pilot class names so that the extracted terms are predictable (e.g., "InvoiceService" → "Invoice", "PaymentCalculator" → "Payment", "InvoiceStatusEnum" → "Invoice", "Status"). Validate these exact terms in regression tests.
**Warning signs:** BusinessTermNode count in Neo4j is lower or higher than expected for the pilot module.

### Pitfall 4: Static ValidationQuery Cypher cannot accept module-name parameters
**What goes wrong:** `ValidationQuery.cypher()` is a static string — no parameter binding is possible in the registry pattern. Attempting to write `WHERE c.module = $pilotModule` in a `ValidationQuery` will fail because `ValidationService.executeQuery()` calls `.query(query.cypher()).fetch().all()` without binding.
**Why it happens:** The registry pattern was designed for global graph checks, not scoped checks.
**How to avoid:** For module-scoped checks (e.g., "pilot module has at least 15 chunks"), implement them directly in `PilotService` using `Neo4jClient.query(...).bind(moduleName).to("module").fetch().all()`. The `PilotValidationReport` should combine results from `ValidationService` (global) + `PilotService` (module-scoped).
**Warning signs:** Cypher parameter not found exception at runtime.

### Pitfall 5: Qdrant search requires vectorized query before calling searchAsync
**What goes wrong:** Passing text directly to Qdrant search — Qdrant expects a float vector, not a string.
**Why it happens:** Easy to misread the API; the `searchAsync` method signature accepts `SearchPoints` protobuf.
**How to avoid:** Always call `embeddingModel.embed(queryText)` first (returns `float[]`), then add each element via `searchBuilder.addVector(v)`. See `VectorIndexingServiceIntegrationTest.similaritySearch_returnsRelevantChunks()` for the verified pattern.
**Warning signs:** gRPC exception or empty results from `searchAsync`.

### Pitfall 6: RiskService must run AFTER LinkingService — pilot validation order matters
**What goes wrong:** Calling `computeAndPersistRiskScores()` before `linkAllRelationships()` results in zero fan-in/fan-out for all classes (no DEPENDS_ON edges yet).
**Why it happens:** Fan-in/out computed via Cypher pattern matching on DEPENDS_ON edges. If linking hasn't run, no edges exist.
**How to avoid:** In the pilot integration test setup, run in this order: persist nodes → `LinkingService.linkAllRelationships()` → `RiskService.computeAndPersistRiskScores()` → `VectorIndexingService.indexAll()`. This mirrors the `ExtractionService` pipeline ordering documented in STATE.md.
**Warning signs:** All `enhancedRiskScore` values are 0.0 or identical.

### Pitfall 7: Vaadin 7 stereotype labels applied by VaadinPatternVisitor require classpath
**What goes wrong:** Synthetic fixture classes that import Vaadin 7 types (e.g., `com.vaadin.navigator.View`) will not receive `VaadinView` dynamic labels unless VaadinPatternVisitor resolves those types during extraction.
**Why it happens:** `VaadinPatternVisitor` uses `getType().isAssignableTo()` which requires Vaadin 7 JARs on the parse classpath. Without them, type resolution fails and no Vaadin labels are applied (degrades gracefully — documented in STATE.md Phase 2).
**How to avoid:** For synthetic pilot fixtures that need Vaadin labels, set the `extraLabels` directly via Cypher during Neo4j node creation in tests (`:JavaClass:VaadinView`), rather than relying on extraction. This is what `VectorIndexingServiceIntegrationTest` does — it creates `CREATE (c:JavaClass:Service {...})` directly.
**Warning signs:** `vaadin7Detected = false` in all Qdrant payloads for view classes.

## Code Examples

Verified patterns from existing codebase:

### Qdrant Search (from VectorIndexingServiceIntegrationTest.java lines 341-368)
```java
// Source: src/test/java/com/esmp/vector/application/VectorIndexingServiceIntegrationTest.java
float[] queryVector = embeddingModel.embed("order processing service method");

SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
    .setCollectionName(vectorConfig.getCollectionName())
    .setLimit(5)
    .setWithPayload(WithPayloadSelectorFactory.enable(true));
for (float v : queryVector) {
    searchBuilder.addVector(v);
}

List<ScoredPoint> results = qdrantClient.searchAsync(searchBuilder.build())
    .get(30, TimeUnit.SECONDS);
```

### ValidationQueryRegistry Extension (from LexiconValidationQueryRegistry pattern)
```java
// Source: src/main/java/com/esmp/graph/validation/LexiconValidationQueryRegistry.java (pattern)
@Component
public class PilotValidationQueryRegistry extends ValidationQueryRegistry {
    public PilotValidationQueryRegistry() {
        super(List.of(
            new ValidationQuery(
                "PILOT_VAADIN7_NODES_PRESENT",
                "Pilot module has at least one VaadinView/VaadinComponent/VaadinDataBinding class",
                """
                MATCH (c:JavaClass)
                WHERE ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])
                  AND c.module = 'pilot'
                RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
                """,
                ValidationSeverity.WARNING)
            // Additional pilot checks...
        ));
    }
}
```

**Note:** For checks that need the inverted logic (count > 0 = PASS), you must either add special-case handling to `ValidationService.determineStatus()` or handle them in `PilotService` directly (recommended to avoid modifying the core service).

### Neo4j Node Creation for Synthetic Fixture (from VectorIndexingServiceIntegrationTest pattern)
```java
// Source: src/test/java/com/esmp/vector/application/VectorIndexingServiceIntegrationTest.java
neo4jClient.query("""
    CREATE (c:JavaClass:Service {
        fullyQualifiedName: $fqn,
        simpleName: 'InvoiceService',
        packageName: 'com.esmp.pilot',
        module: 'pilot',
        sourceFilePath: $path,
        contentHash: 'hash-invoice-service-v1',
        structuralRiskScore: 0.7,
        enhancedRiskScore: 0.8,
        domainCriticality: 0.6,
        securitySensitivity: 0.1,
        financialInvolvement: 0.9,
        businessRuleDensity: 0.4
    })
    CREATE (m1:JavaMethod {
        methodId: $methodId,
        simpleName: 'createInvoice',
        declaringClass: $fqn,
        cyclomaticComplexity: 4
    })
    CREATE (c)-[:DECLARES_METHOD]->(m1)
    """)
    .bindAll(Map.of("fqn", FQN, "path", "InvoiceService.java", "methodId", FQN + "#createInvoice(Invoice)"))
    .run();
```

### Module Scoring Cypher (new for pilot)
```cypher
// Source: new for Phase 9 — PilotService.recommendModule()
MATCH (c:JavaClass)
WHERE c.module IS NOT NULL AND c.module <> ''
WITH c.module AS module,
     count(c) AS classCount,
     sum(CASE WHEN ANY(l IN labels(c) WHERE l IN ['VaadinView','VaadinComponent','VaadinDataBinding'])
              THEN 1 ELSE 0 END) AS vaadin7Count,
     avg(coalesce(c.enhancedRiskScore, 0.0)) AS avgRisk,
     stDev(coalesce(c.enhancedRiskScore, 0.0)) AS riskDiversity
WHERE classCount >= 5
RETURN module, classCount, vaadin7Count, avgRisk, riskDiversity,
       (0.4 * toFloat(vaadin7Count) / classCount
      + 0.3 * riskDiversity
      + 0.3 * toFloat(CASE WHEN classCount >= 15 AND classCount <= 40 THEN 1.0 ELSE 0.0 END)) AS score
ORDER BY score DESC
LIMIT 5
```

### Pilot Validation Report Structure (new record)
```java
// New record in com.esmp.pilot.api
public record PilotValidationReport(
    String generatedAt,
    String pilotModule,
    // Global graph validation (all 32+ queries)
    ValidationReport graphValidation,
    // Module-specific metrics
    int classCount,
    int vaadin7ClassCount,
    int chunkCount,
    double avgEnhancedRiskScore,
    int businessTermCount,
    double domainTermCoveragePercent,
    // Pilot-specific pass/fail checks
    List<PilotCheck> pilotChecks,
    // Human-readable markdown
    String markdownReport) {}

public record PilotCheck(String name, String status, String detail) {}
```

### Search Response Records (new)
```java
// com.esmp.vector.api
public record SearchRequest(
    String query,
    Integer limit,
    String module,
    String stereotype,
    String chunkType) {}

public record ChunkSearchResult(
    float score,
    String classFqn,
    String chunkType,
    String methodId,
    String module,
    String stereotype,
    double structuralRiskScore,
    double enhancedRiskScore,
    boolean vaadin7Detected,
    String callers,
    String callees,
    String dependencies,
    String domainTerms) {}

public record SearchResponse(
    List<ChunkSearchResult> results,
    int totalReturned,
    String query) {}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual pipeline validation | `ValidationService` + `ValidationQueryRegistry` extensibility | Phase 4 | Pilot adds its registry without modifying core |
| Global risk heatmap only | Module-scoped risk queries via `?module=` param | Phase 6 | `GET /api/risk/heatmap?module=pilot` already works |
| No vector search API | `POST /api/vector/search` (this phase) | Phase 9 | Foundation for Phase 11 RAG pipeline |
| Batch-only indexing | Incremental reindex via hash comparison | Phase 8 | Already handles pilot module updates |

**Deprecated/outdated:**
- Nothing from previous phases is deprecated by Phase 9.

## Open Questions

1. **Where to store synthetic fixture `.java` files for the integration test**
   - What we know: `ChunkingService` reads from `sourceFilePath` property in Neo4j; tests use `@TempDir` for dynamic paths
   - What's unclear: Whether the pilot integration test should use `@TempDir` (ephemeral, correct for CI) or a fixed `src/test/resources/fixtures/pilot/` directory (easier to inspect manually)
   - Recommendation: Use `@TempDir` in tests (consistent with VectorIndexingServiceIntegrationTest pattern); also create a static `src/test/resources/fixtures/pilot/` directory for manual inspection — these are the same source files, just referenced differently

2. **PilotValidationQueryRegistry inverted-logic checks**
   - What we know: `ValidationService.determineStatus()` only special-cases `CALLS_EDGE_COVERAGE` by name for inverted logic
   - What's unclear: Whether pilot checks like "pilot module chunk count > 0" need inverted logic (they do — count > 0 should be PASS)
   - Recommendation: Implement module-scoped inverted checks in `PilotService` directly rather than extending the registry pattern. The registry pattern is clean for violation queries (count = 0 means pass). Keep `PilotValidationQueryRegistry` for violation queries only.

3. **Markdown report generation — file system persistence vs. response field**
   - What we know: CONTEXT.md says "generates a structured markdown report for human review"
   - What's unclear: Whether the report should be written to a file path or returned in the JSON response body
   - Recommendation: Return as a field in `PilotValidationReport` (the `markdownReport` string field). This avoids file system coupling. The engineer can copy/paste or pipe to a file. Phase 13 may add a proper reporting endpoint.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Testcontainers + AssertJ (Spring Boot 3.5.x) |
| Config file | No separate config — `@Testcontainers` + `@DynamicPropertySource` pattern |
| Quick run command | `./gradlew test --tests "com.esmp.pilot.*" --tests "com.esmp.vector.api.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements to Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| GMP-01 | Pilot module fully processed through chunking + vector indexing | integration | `./gradlew test --tests "com.esmp.pilot.application.PilotServiceIntegrationTest"` | Wave 0 |
| GMP-01 | Module recommendation endpoint returns ranked modules | integration | `./gradlew test --tests "com.esmp.pilot.api.PilotControllerIntegrationTest"` | Wave 0 |
| GMP-02 | POST /api/vector/search returns relevant results for pilot queries | integration | `./gradlew test --tests "com.esmp.vector.api.VectorSearchIntegrationTest"` | Wave 0 |
| GMP-02 | Search results have correct enrichment payload fields | integration | same as above | Wave 0 |
| GMP-03 | Pilot validation report includes risk scores + migration stats | integration | `./gradlew test --tests "com.esmp.pilot.application.PilotServiceIntegrationTest"` | Wave 0 |
| GMP-03 | All 32 existing validation queries pass on pilot module data | integration | same as above (calls ValidationService) | Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.pilot.*" --tests "com.esmp.vector.api.VectorSearch*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/pilot/application/PilotServiceIntegrationTest.java` — covers GMP-01, GMP-03
- [ ] `src/test/java/com/esmp/pilot/api/PilotControllerIntegrationTest.java` — covers GMP-01 (recommend endpoint)
- [ ] `src/test/java/com/esmp/vector/api/VectorSearchIntegrationTest.java` — covers GMP-02
- [ ] `src/test/resources/fixtures/pilot/` — synthetic fixture `.java` source files for manual review

## Sources

### Primary (HIGH confidence)
- Codebase direct inspection — `VectorIndexingService.java`, `ChunkingService.java`, `ValidationService.java`, `ValidationQueryRegistry.java`, `VectorIndexController.java`, `RiskController.java`
- `VectorIndexingServiceIntegrationTest.java` — verified Qdrant search pattern, Testcontainers setup, Neo4j node creation pattern, `WithVectorsSelectorFactory.enable(false)` requirement
- `STATE.md` accumulated decisions — Phase 8 Spring AI `embed()` returns `List<float[]>`, NPE with null in `retrieveAsync`, `WithVectorsSelectorFactory.enable(false)` requirement

### Secondary (MEDIUM confidence)
- Phase 9 CONTEXT.md — user decisions, locked choices, discretion areas
- REQUIREMENTS.md — GMP-01, GMP-02, GMP-03 requirement definitions

### Tertiary (LOW confidence)
- None — all findings verified directly from codebase

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in classpath, patterns extracted directly from code
- Architecture: HIGH — all patterns are direct extensions of established project patterns with exact code references
- Pitfalls: HIGH — derived from STATE.md accumulated decisions + codebase reading (especially ChunkingService file path handling)
- Validation architecture: HIGH — Testcontainers setup copied from VectorIndexingServiceIntegrationTest

**Research date:** 2026-03-06
**Valid until:** 2026-04-06 (stable stack — no fast-moving dependencies)
