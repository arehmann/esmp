# Phase 6: Structural Risk Analysis - Research

**Researched:** 2026-03-05
**Domain:** OpenRewrite AST complexity analysis, Neo4j Cypher aggregation, Spring Boot REST API
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Complexity computation:**
- Compute cyclomatic complexity per method by counting branch points (if/else/for/while/switch/catch) in the AST
- Add a new `ComplexityVisitor` (OpenRewrite `JavaIsoVisitor`) that runs during the extraction pipeline alongside existing visitors
- Store per-method CC on `MethodNode` as a `cyclomaticComplexity` integer property
- Aggregate to class level: store both sum and max of method CCs on `ClassNode` (`complexitySum`, `complexityMax`)
- Computation happens during extraction (single source-parse pass), not as a separate post-extraction step

**Risk score storage:**
- Store risk metrics as properties directly on ClassNode — no separate RiskProfile node
- New ClassNode properties: `complexitySum`, `complexityMax`, `fanIn`, `fanOut`, `hasDbWrites`, `dbWriteCount`, `structuralRiskScore`
- New MethodNode property: `cyclomaticComplexity`
- Risk scores are fully recomputed on every re-extraction — no curated guard
- Composite score formula: weighted sum — `score = w1*complexity + w2*fanIn + w3*fanOut + w4*dbWriteFlag`
- Weights configurable via application.properties (e.g., `esmp.risk.weight.complexity=0.3`)

**Heatmap endpoint design:**
- REST endpoint only — `GET /api/risk/heatmap` returns JSON sorted by descending structural risk score
- Phase 12 (Governance Dashboard) builds the visual heatmap; Phase 6 provides the data API
- Filterable via query params: `?module=X&package=Y&stereotype=Service&limit=50` — follows LexiconController's filterable pattern
- Response includes full metric breakdown per class: fqn, complexitySum, complexityMax, fanIn, fanOut, hasDbWrites, dbWriteCount, structuralRiskScore, stereotype labels
- Per-class detail endpoint: `GET /api/risk/class/{fqn}` — full risk profile + method-level complexity breakdown + contributing factors. Reusable by Phase 7 and Phase 12

**DB write detection:**
- Extend existing JPA visitor (or add detection logic in the new ComplexityVisitor) — detect @Modifying, persist()/merge()/delete() calls, JPQL/SQL with INSERT/UPDATE/DELETE
- Store both binary flag (`hasDbWrites: true/false`) and quantified count (`dbWriteCount: N`) per class
- Binary flag feeds into composite risk score; count available in detail endpoint
- Flag any write type — no distinction between INSERT/UPDATE/DELETE for Phase 6
- Fan-in/fan-out computed from DEPENDS_ON edges (class-level): fanIn = count of classes that DEPENDS_ON this class, fanOut = count of classes this class DEPENDS_ON

### Claude's Discretion
- Exact ComplexityVisitor implementation (which AST nodes count as branch points)
- Default weight values for composite risk formula
- RiskController and RiskService internal design
- Risk-specific validation queries for RiskValidationQueryRegistry
- Normalization strategy for individual metrics before weighting (min-max, log scale, etc.)
- Test strategy (integration tests with Testcontainers)
- Whether fan-in/out computation happens during extraction or as a post-extraction Cypher aggregation

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| RISK-01 | System computes cyclomatic complexity per class/method | ComplexityVisitor using OpenRewrite J.If, J.WhileLoop, J.ForLoop, J.ForEachLoop, J.Switch, J.Case, J.Try.Catch AST nodes; per-method count stored on MethodNode; sum+max aggregated to ClassNode |
| RISK-02 | System computes fan-in and fan-out metrics per class | DEPENDS_ON edges already in graph from Phase 3 LinkingService; fan-in/out computed via post-extraction Cypher aggregation using Neo4jClient; no re-parsing needed |
| RISK-03 | System detects DB write operations per class | Detection in ComplexityVisitor (or extended JpaPatternVisitor): @Modifying annotation, EntityManager.persist/merge/remove calls, JPQL/native queries with INSERT/UPDATE/DELETE keywords; both hasDbWrites flag and dbWriteCount stored on ClassNode |
| RISK-04 | System produces composite structural risk score per class | Weighted sum formula applied post-extraction via Cypher SET; weights loaded from application.properties via @ConfigurationProperties; score stored on ClassNode.structuralRiskScore |
| RISK-05 | User can view dependency heatmap sorted by structural risk score | GET /api/risk/heatmap endpoint in RiskController; filterable by module/package/stereotype/limit; GET /api/risk/class/{fqn} for per-class detail with method breakdown |
</phase_requirements>

---

## Summary

Phase 6 adds structural risk metrics to every JavaClass node already in the Neo4j graph. The implementation has three distinct computation stages: (1) AST-time complexity counting via a new `ComplexityVisitor` that runs alongside existing visitors in the `ExtractionService` pipeline, (2) post-extraction Cypher aggregation to compute fan-in/fan-out from existing DEPENDS_ON edges and write the weighted composite score back to each ClassNode, and (3) a REST API layer (`RiskController` + `RiskService`) that exposes heatmap and per-class detail endpoints following the LexiconController filterable pattern.

The critical technical insight is that DEPENDS_ON edges are already materialized in the graph from Phase 3's `LinkingService`, so fan-in/out require only Cypher COUNT queries — no re-parsing of source files. This makes the fan-in/out computation purely a Cypher operation and avoids the complexity of tracking dependency direction in the accumulator.

The DB write detection pattern extends the existing `JpaPatternVisitor` approach: detect `@Modifying`, `EntityManager.persist/merge/remove/delete` method calls, and JPQL/SQL string literals containing `INSERT`, `UPDATE`, or `DELETE` keywords. The accumulator receives a new per-class write count, and the mapper populates the new ClassNode properties. All risk metric properties are fully overwritten on re-extraction (no curated guard, unlike lexicon terms).

**Primary recommendation:** Implement as three plan files: (1) ComplexityVisitor + accumulator extension + model changes, (2) fan-in/out Cypher aggregation + composite score computation + Neo4j schema updates, (3) RiskController + RiskService + RiskValidationQueryRegistry.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| openrewrite-java | already on classpath | AST traversal for complexity counting | Project already uses for all visitor extraction |
| spring-data-neo4j | already on classpath | ClassNode/MethodNode schema evolution | Already used for all node persistence |
| Neo4jClient | already on classpath | Cypher queries for fan-in/out and risk score SET | Established pattern for complex Cypher in this project |
| spring-boot-starter-web | already on classpath | REST endpoints for heatmap | Already used for all controllers |
| Testcontainers Neo4j | already testImplementation | Integration tests | Established pattern: LexiconIntegrationTest, LinkingServiceIntegrationTest |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| @ConfigurationProperties | Spring Boot built-in | Weight configuration binding | For esmp.risk.weight.* properties |
| AssertJ | already testImplementation via spring-boot-starter-test | Test assertions | All unit and integration tests |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Cypher aggregation for fan-in/out | Accumulator-based counting during extraction | Cypher is simpler (DEPENDS_ON already in graph); accumulator would require tracking all edges again |
| Inline score computation in Java | Cypher SET for score | Cypher keeps logic close to data; Java requires fetching all nodes first |

**Installation:** No new dependencies required. All libraries are already in `build.gradle.kts`.

---

## Architecture Patterns

### Recommended Project Structure

New files for Phase 6 (all follow existing package conventions):

```
src/main/java/com/esmp/
├── extraction/
│   ├── model/
│   │   ├── ClassNode.java               # ADD: complexitySum, complexityMax, fanIn, fanOut,
│   │   │                                #      hasDbWrites, dbWriteCount, structuralRiskScore
│   │   └── MethodNode.java              # ADD: cyclomaticComplexity
│   ├── visitor/
│   │   ├── ComplexityVisitor.java       # NEW: JavaIsoVisitor for CC counting + DB write detection
│   │   └── ExtractionAccumulator.java  # ADD: complexity and DB write data maps
│   ├── application/
│   │   ├── ExtractionService.java      # ADD: ComplexityVisitor to pipeline; add risk score step
│   │   └── AccumulatorToModelMapper.java # ADD: map complexity/write data to ClassNode/MethodNode
│   └── config/
│       └── Neo4jSchemaInitializer.java  # ADD: index on structuralRiskScore
└── graph/
    ├── api/
    │   ├── RiskController.java          # NEW: GET /api/risk/heatmap, GET /api/risk/class/{fqn}
    │   ├── RiskHeatmapEntry.java        # NEW: response record for heatmap list
    │   └── RiskDetailResponse.java      # NEW: response record for per-class detail
    ├── application/
    │   └── RiskService.java             # NEW: fan-in/out computation + heatmap query logic
    └── validation/
        └── RiskValidationQueryRegistry.java # NEW: extends ValidationQueryRegistry
```

### Pattern 1: ComplexityVisitor — OpenRewrite JavaIsoVisitor for branch counting

**What:** A `JavaIsoVisitor<ExtractionAccumulator>` that visits method bodies and counts branch points to compute cyclomatic complexity. Cyclomatic complexity = 1 (baseline) + number of decision points per method.

**When to use:** Runs during the extraction pipeline per source file, immediately after `ClassMetadataVisitor` populates method records.

**Branch point nodes in OpenRewrite J. tree (HIGH confidence — based on OpenRewrite's public AST API):**

| OpenRewrite AST Node | Java Construct | CC Contribution |
|---------------------|----------------|----------------|
| `J.If` | `if` statement | +1 |
| `J.If.Else` | `else if` branch | +1 |
| `J.Ternary` | `? :` expression | +1 |
| `J.WhileLoop` | `while` loop | +1 |
| `J.DoWhileLoop` | `do-while` loop | +1 |
| `J.ForLoop` | `for` loop | +1 |
| `J.ForEachLoop` | `for-each` loop | +1 |
| `J.Switch` | `switch` statement | 0 (cases counted) |
| `J.Case` | `case` label in switch | +1 |
| `J.Try` catch clauses | `catch` blocks | +1 per catch |

**Cursor tracking:** Use `getCursor().firstEnclosing(J.MethodDeclaration.class)` to determine which method the branch belongs to — exactly as `CallGraphVisitor` does for method invocations. Track complexity counts in a `Map<String, Integer>` keyed by methodId.

**Example structure:**
```java
// Source: OpenRewrite JavaIsoVisitor API (verified from existing visitor patterns in project)
public class ComplexityVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  // Accumulate per-method branch count during traversal; flush on MethodDeclaration exit
  private final Map<String, Integer> methodComplexity = new HashMap<>();

  @Override
  public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, ExtractionAccumulator acc) {
    J.MethodDeclaration result = super.visitMethodDeclaration(md, acc);
    // After recursion, commit the count for this method
    if (md.getMethodType() != null) {
      String methodId = buildMethodId(md);
      int cc = methodComplexity.getOrDefault(methodId, 0) + 1; // +1 baseline
      acc.addMethodComplexity(methodId, md.getMethodType().getDeclaringType().getFullyQualifiedName(), cc);
      methodComplexity.remove(methodId); // clean up
    }
    return result;
  }

  @Override
  public J.If visitIf(J.If ifStatement, ExtractionAccumulator acc) {
    incrementForEnclosingMethod(); // +1 for the if
    return super.visitIf(ifStatement, acc);
  }
  // ... similar for WhileLoop, ForLoop, ForEachLoop, Case, Try catch clauses, Ternary
}
```

**Important:** The visitor must call `super.visit*()` on every override to recurse into nested structures (same pattern as all existing visitors).

### Pattern 2: ExtractionAccumulator Extension — Complexity and DB Write Data

**What:** Add new data maps and mutation methods to `ExtractionAccumulator` for Phase 6 data.

**New inner record and mutable class:**
```java
// Source: follows existing ExtractionAccumulator record patterns

// --- Phase 6: complexity and DB write data ---
private final Map<String, MethodComplexityData> methodComplexities = new HashMap<>();
private final Map<String, ClassWriteData> classWriteData = new HashMap<>();

public void addMethodComplexity(String methodId, String declaringClassFqn, int cyclomaticComplexity) {
    methodComplexities.put(methodId, new MethodComplexityData(methodId, declaringClassFqn, cyclomaticComplexity));
}

public void recordDbWrite(String classFqn) {
    classWriteData.compute(classFqn, (k, v) -> v == null ? new ClassWriteData(k, 1) : new ClassWriteData(k, v.writeCount() + 1));
}

// Inner records
public record MethodComplexityData(String methodId, String declaringClassFqn, int cyclomaticComplexity) {}
public record ClassWriteData(String classFqn, int writeCount) {}
```

**Note:** `ClassWriteData` can use a record if write count is computed as final (all invocations counted before building the record). Alternatively use mutable class like `BusinessTermData` if incremental accumulation is needed.

### Pattern 3: DB Write Detection — Extending JpaPatternVisitor Approach

**What:** Detect DB write operations in `ComplexityVisitor.visitMethodDeclaration` or as a separate `DbWriteVisitor`. Reuses the annotation resolution pattern from `JpaPatternVisitor`.

**Detection signals (in order of reliability):**
1. `@Modifying` annotation on a repository method (annotation FQN: `org.springframework.data.jpa.repository.Modifying`)
2. `EntityManager.persist()`, `.merge()`, `.remove()`, `.flush()` method invocations (via `visitMethodInvocation`, check `mi.getSimpleName()` + declaring type)
3. `@Query` annotation with JPQL/SQL string containing `INSERT`, `UPDATE`, `DELETE` (check annotation attribute value)
4. Spring Data derived method names with write semantics: `save`, `saveAll`, `delete`, `deleteAll`, `deleteBy*` prefixes

**Detection in the accumulator:** Record at the class level (aggregate). The class that declares the method with the write operation is flagged.

### Pattern 4: Fan-In/Out Computation via Cypher

**What:** Post-extraction Cypher queries that COUNT DEPENDS_ON edges in both directions per class, then SET the results on ClassNode properties. Runs in `ExtractionService.extract()` after the persist phase.

**Cypher for fan-in/out (one batch query):**
```cypher
// Source: Cypher aggregation pattern used throughout this project (Neo4jClient)
MATCH (c:JavaClass)
WITH c,
     size([(other)-[:DEPENDS_ON]->(c) | other]) AS fanIn,
     size([(c)-[:DEPENDS_ON]->(other) | other]) AS fanOut
SET c.fanIn = fanIn, c.fanOut = fanOut
```

This single query handles all classes in one pass. Pattern list comprehensions (`[(a)-[r]->(b) | b]`) are idiomatic Cypher for counting relationship cardinality without GROUP BY.

**Confidence:** HIGH — DEPENDS_ON edges confirmed in graph from Phase 3 `LinkingService`. Cypher pattern comprehensions verified as standard Neo4j feature.

### Pattern 5: Composite Risk Score Computation via Cypher

**What:** After fan-in/out are set, compute the weighted composite score per class using a single Cypher SET query, with weights injected as parameters.

```cypher
// Source: follows Neo4jClient .bind(param) pattern established in project
MATCH (c:JavaClass)
SET c.structuralRiskScore = (
    $w1 * coalesce(c.complexitySum, 0) +
    $w2 * coalesce(c.fanIn, 0) +
    $w3 * coalesce(c.fanOut, 0) +
    $w4 * CASE WHEN c.hasDbWrites THEN 1.0 ELSE 0.0 END
)
```

Parameters `$w1`, `$w2`, `$w3`, `$w4` are bound from application.properties via the weight config bean.

### Pattern 6: RiskController — Filterable Heatmap (follows LexiconController)

**What:** `@RestController` at `/api/risk/` with two endpoints, following the exact same filterable pattern as `LexiconController`.

```java
// Source: modeled on LexiconController (graph/api/LexiconController.java in this project)
@RestController
@RequestMapping("/api/risk")
public class RiskController {

  @GetMapping("/heatmap")
  public ResponseEntity<List<RiskHeatmapEntry>> getHeatmap(
      @RequestParam(required = false) String module,
      @RequestParam(required = false) String packageName,
      @RequestParam(required = false) String stereotype,
      @RequestParam(defaultValue = "50") int limit) {
    return ResponseEntity.ok(riskService.getHeatmap(module, packageName, stereotype, limit));
  }

  @GetMapping("/class/{fqn:.+}")
  public ResponseEntity<RiskDetailResponse> getClassDetail(@PathVariable String fqn) {
    return riskService.getClassDetail(fqn)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
```

**Note:** `{fqn:.+}` regex suffix is mandatory — established pattern from Phase 3 to prevent Spring MVC dot-truncation on FQN path variables.

### Pattern 7: RiskValidationQueryRegistry

**What:** Extends `ValidationQueryRegistry` (same pattern as `LexiconValidationQueryRegistry`) and is auto-discovered by `ValidationService`.

**Recommended validation queries:**
1. `RISK_SCORES_POPULATED` (ERROR) — JavaClass nodes where `structuralRiskScore IS NULL`. Count > 0 means extraction ran without Phase 6 ComplexityVisitor.
2. `FAN_IN_OUT_POPULATED` (ERROR) — JavaClass nodes where `fanIn IS NULL OR fanOut IS NULL`. Same signal.
3. `HIGH_COMPLEXITY_COVERAGE` (WARNING) — Classes with `complexitySum > 20` that have no DEPENDS_ON edges (might be misidentified utility classes).

### Anti-Patterns to Avoid

- **Separate RiskProfile node:** Decided against — properties directly on ClassNode. Do not create a separate node type.
- **Curated guard for risk scores:** Risk scores are recomputed every extraction. Do NOT use the `ON MATCH SET ... CASE WHEN curated` pattern from business terms.
- **Re-parsing source for fan-in/out:** DEPENDS_ON edges are already in the graph. Do not add dependency tracking to the accumulator — use Cypher.
- **Visitor state leaking between source files:** ComplexityVisitor uses `methodComplexity` as a transient map per source file traversal. The map must be cleared between source files or scoped per-visit. Since the visitor is instantiated once and reused across all source files (like existing visitors in `ExtractionService`), the map must be cleared at the start of each `visitMethodDeclaration` or use a local counter strategy instead of instance state.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Neo4j property reads from driver | Manual `record.get("prop").asXxx()` with null checks | `node.get("prop").asXxx(defaultValue)` (already established in LexiconService) | Neo4j driver handles missing properties with default value overload |
| Cypher parameter binding | String concatenation | `neo4jClient.query(cypher).bindAll(Map.of(...))` | SQL injection risk; established project pattern |
| Controller response mapping | Manual field-by-field copy in controller | Mapping helper in RiskService | Follows LexiconService.mapNodeToResponse() pattern |
| Configurable weights | Hardcoded floats | `@ConfigurationProperties("esmp.risk.weight")` binding class | application.properties already used for extraction config (ExtractionConfig pattern) |

**Key insight:** The project has established clean patterns for every concern in this phase. Follow LexiconController + LexiconService for the REST layer. Follow JpaPatternVisitor for the detection visitor. Follow LexiconValidationQueryRegistry for validation.

---

## Common Pitfalls

### Pitfall 1: Visitor Instance State Between Source Files
**What goes wrong:** `ComplexityVisitor` maintains a `Map<String, Integer> methodComplexity` as instance state. Since `ExtractionService` creates one visitor instance and calls `.visit(sourceFile, acc)` for each of N source files in a loop, state from file N leaks into file N+1.
**Why it happens:** The existing visitors (`CallGraphVisitor`, `ClassMetadataVisitor`, etc.) are stateless — they don't accumulate per-visit state. ComplexityVisitor is the first visitor with intra-visit transient state.
**How to avoid:** Use a local counter within `visitMethodDeclaration` itself rather than instance state. Track branch count by starting a counter at the method level and passing it through the cursor/accumulator. Alternatively, clear the map at the start of `visitCompilationUnit`. See `CallGraphVisitor.visitMethodInvocation` — it uses `getCursor().firstEnclosing(J.MethodDeclaration.class)` to find context without storing state.
**Warning signs:** Complexity counts for methods in file 2+ are inflated when tested against multiple files.

### Pitfall 2: MethodId Key Mismatch Between Visitors
**What goes wrong:** ComplexityVisitor builds a methodId that doesn't match the key used by `ClassMetadataVisitor.buildMethodId()`, so accumulator lookups fail silently (no error, but no CC data on the node).
**Why it happens:** `buildMethodId()` in `ClassMetadataVisitor` is a `static` package-visible method — it can be called directly. The risk is building the ID differently (e.g., using raw type names vs. FQN).
**How to avoid:** Call `ClassMetadataVisitor.buildMethodId(declaringClass, simpleName, paramTypes)` directly in `ComplexityVisitor`. This static method is already accessible within the same package.
**Warning signs:** `complexitySum` is 0 for all classes even though ComplexityVisitor ran.

### Pitfall 3: ClassNode Schema Evolution and SDN saveAll()
**What goes wrong:** Adding new primitive properties (`complexitySum`, `complexityMax`, `fanIn`, `fanOut`, `hasDbWrites`, `dbWriteCount`, `structuralRiskScore`) to ClassNode can cause issues if existing Neo4j nodes have these properties absent (null) while the Java model expects primitives.
**Why it happens:** Spring Data Neo4j maps Java `int` → Neo4j integer. If a stored ClassNode has no `complexitySum` property, SDN returns 0 for `int` fields (Java default). This is safe. However `boolean hasDbWrites` maps to null-absent property in Neo4j, which SDN reads as `false` for Java `boolean`. Safe but worth confirming.
**How to avoid:** Use Java primitives (`int`, `boolean`, `double`) not boxed types (`Integer`, `Boolean`, `Double`) for risk metrics — they have safe defaults (0, false, 0.0). No schema migration needed; Neo4j is schema-free.
**Warning signs:** NullPointerExceptions in mapper when setting boxed Integer properties to values from accumulator that haven't been populated yet.

### Pitfall 4: Cypher Fan-In/Out Running Before DEPENDS_ON Edges Exist
**What goes wrong:** If the fan-in/out Cypher runs before `linkingService.linkAllRelationships(accumulator)` creates DEPENDS_ON edges, all fan-in/out counts will be 0.
**Why it happens:** Order of operations in `ExtractionService.extract()`: persist nodes → run linking → then run risk scoring. If risk scoring runs before linking, DEPENDS_ON edges aren't in the graph yet.
**How to avoid:** Risk score computation (fan-in/out Cypher + composite score Cypher) MUST run after `linkingService.linkAllRelationships(accumulator)`. Add a `RiskService.computeAndPersistRiskScores()` call at the end of `ExtractionService.extract()`, after the linking step.
**Warning signs:** All `fanIn` and `fanOut` values are 0 even for well-connected classes.

### Pitfall 5: @Modifying Detection Annotation FQN
**What goes wrong:** `@Modifying` annotation FQN is `org.springframework.data.jpa.repository.Modifying`. When OpenRewrite type resolution fails (no Spring Data JAR on parse classpath), the annotation resolves to simple name `"Modifying"` — the JPA visitor pattern already handles this with simple-name fallback, but ComplexityVisitor must do the same.
**Why it happens:** Parse classpath may not include Spring Data JARs during extraction of legacy source code.
**How to avoid:** Use the same `resolveAnnotationFqn()` / simple-name fallback switch established in `JpaPatternVisitor` and `ClassMetadataVisitor`. Add `"Modifying" -> "org.springframework.data.jpa.repository.Modifying"` to the switch.
**Warning signs:** `hasDbWrites` is false for all Repository classes that use `@Modifying`.

### Pitfall 6: Heatmap Query Performance on Large Graphs
**What goes wrong:** `GET /api/risk/heatmap` with no filters does `MATCH (c:JavaClass) RETURN c ORDER BY c.structuralRiskScore DESC LIMIT 50` — returns all JavaClass nodes, which may be slow without an index.
**Why it happens:** Neo4j does not auto-index non-unique properties. `structuralRiskScore` is a computed numeric value that needs a range index for ORDER BY performance.
**How to avoid:** Add a range index on `JavaClass.structuralRiskScore` in `Neo4jSchemaInitializer`:
```cypher
CREATE INDEX java_class_risk_score IF NOT EXISTS FOR (n:JavaClass) ON (n.structuralRiskScore)
```
Neo4j 5.x range indexes support ORDER BY pushdown.
**Warning signs:** Heatmap endpoint is slow (> 200ms) on graphs with > 500 class nodes.

---

## Code Examples

Verified patterns from existing project code:

### Neo4jClient Cypher with parameter binding (established in LexiconService)
```java
// Source: LexiconService.findByFilters() — graph/application/LexiconService.java
Collection<RiskHeatmapEntry> results = neo4jClient.query(cypher)
    .bindAll(params)
    .fetchAs(RiskHeatmapEntry.class)
    .mappedBy((typeSystem, record) -> mapNodeToHeatmapEntry(record.get("c").asNode()))
    .all();
```

### Neo4j driver node property reading (established in LexiconService.mapNodeToResponse)
```java
// Source: LexiconService.mapNodeToResponse() — uses asXxx(defaultValue) pattern
node.get("complexitySum").asInt(0)        // safe: returns 0 if absent
node.get("hasDbWrites").asBoolean(false)  // safe: returns false if absent
node.get("structuralRiskScore").asDouble(0.0) // safe: returns 0.0 if absent
node.get("fullyQualifiedName").asString("") // safe: returns "" if absent
```

### Weight configuration with @ConfigurationProperties (follows ExtractionConfig pattern)
```java
// Source: modeled on extraction/config/ExtractionConfig.java
@ConfigurationProperties(prefix = "esmp.risk.weight")
@Component
public class RiskWeightConfig {
  private double complexity = 0.4;  // default: complexity is most important
  private double fanIn     = 0.2;  // many callers = high impact
  private double fanOut    = 0.2;  // many dependencies = fragile
  private double dbWrites  = 0.2;  // DB writes = migration risk

  // standard getters/setters
}
```

application.yml addition:
```yaml
esmp:
  risk:
    weight:
      complexity: 0.4
      fan-in: 0.2
      fan-out: 0.2
      db-writes: 0.2
```

### RiskValidationQueryRegistry (follows LexiconValidationQueryRegistry)
```java
// Source: modeled on graph/validation/LexiconValidationQueryRegistry.java
@Component
public class RiskValidationQueryRegistry extends ValidationQueryRegistry {
  public RiskValidationQueryRegistry() {
    super(List.of(
        new ValidationQuery(
            "RISK_SCORES_POPULATED",
            "JavaClass nodes with no structuralRiskScore (extraction ran without Phase 6)",
            """
            OPTIONAL MATCH (c:JavaClass) WHERE c.structuralRiskScore IS NULL
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.ERROR),
        new ValidationQuery(
            "FAN_IN_OUT_POPULATED",
            "JavaClass nodes with null fanIn or fanOut (risk metrics not computed)",
            """
            OPTIONAL MATCH (c:JavaClass) WHERE c.fanIn IS NULL OR c.fanOut IS NULL
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.ERROR)
    ));
  }
}
```

### Method complexity accumulation in ExtractionAccumulator (new section)
```java
// Source: follows existing ExtractionAccumulator section pattern

// --- Phase 6: risk metrics ---
private final Map<String, MethodComplexityData> methodComplexities = new HashMap<>();
private final Map<String, ClassWriteData> classWriteData = new HashMap<>();

public void addMethodComplexity(String methodId, String declaringClassFqn, int cyclomaticComplexity) {
    methodComplexities.put(methodId, new MethodComplexityData(methodId, declaringClassFqn, cyclomaticComplexity));
}

public void incrementClassDbWrites(String classFqn) {
    classWriteData.merge(classFqn, new ClassWriteData(classFqn, 1),
        (existing, newVal) -> new ClassWriteData(classFqn, existing.writeCount() + 1));
}

public Map<String, MethodComplexityData> getMethodComplexities() {
    return Collections.unmodifiableMap(methodComplexities);
}

public Map<String, ClassWriteData> getClassWriteData() {
    return Collections.unmodifiableMap(classWriteData);
}

// Inner records
public record MethodComplexityData(String methodId, String declaringClassFqn, int cyclomaticComplexity) {}
public record ClassWriteData(String classFqn, int writeCount) {}
```

**Note:** `ClassWriteData` can be a record if we use `Map.merge()` (shown above) or a mutable class if incremental. The `merge()` pattern shown is cleaner.

### AccumulatorToModelMapper — mapping complexity to ClassNode/MethodNode
```java
// Source: follows mapToClassNodes() pattern in AccumulatorToModelMapper.java

// In mapToClassNodes(), after creating each MethodNode:
ExtractionAccumulator.MethodComplexityData cc = acc.getMethodComplexities().get(mData.methodId());
if (cc != null) {
    methodNode.setCyclomaticComplexity(cc.cyclomaticComplexity());
}

// After building classNode:
// Compute complexitySum and complexityMax from this class's methods
List<MethodNode> classMethods = methodsByClass.getOrDefault(cData.fqn(), List.of());
int complexitySum = classMethods.stream().mapToInt(MethodNode::getCyclomaticComplexity).sum();
int complexityMax = classMethods.stream().mapToInt(MethodNode::getCyclomaticComplexity).max().orElse(0);
classNode.setComplexitySum(complexitySum);
classNode.setComplexityMax(complexityMax);

// DB writes
ExtractionAccumulator.ClassWriteData writeData = acc.getClassWriteData().get(cData.fqn());
classNode.setHasDbWrites(writeData != null && writeData.writeCount() > 0);
classNode.setDbWriteCount(writeData != null ? writeData.writeCount() : 0);
// structuralRiskScore = 0 initially; computed via Cypher after fan-in/out are set
classNode.setStructuralRiskScore(0.0);
```

### ExtractionService — adding risk score step (order matters)
```java
// Source: follows extract() method structure in ExtractionService.java
// ADD ComplexityVisitor to visitor list:
ComplexityVisitor complexityVisitor = new ComplexityVisitor();

// In the per-source-file loop (after existing visitors):
complexityVisitor.visit(sourceFile, accumulator);

// After persist + linking, ADD risk computation step:
LinkingService.LinkingResult linkingResult = linkingService.linkAllRelationships(accumulator);
// NEW: compute fan-in/out and composite risk scores (must run AFTER linking creates DEPENDS_ON)
riskService.computeAndPersistRiskScores(riskWeightConfig);
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Separate RiskProfile node | Properties directly on ClassNode | Phase 6 design decision | Simpler queries; no JOIN needed for heatmap |
| Compute CC post-extraction via separate step | Compute CC during AST visit (single parse pass) | Phase 6 design decision | No re-parsing; uses already-computed LSTs |
| Track fan-in/out in accumulator | Compute from existing DEPENDS_ON edges in graph | Phase 6 design decision | DEPENDS_ON already correct from Phase 3; no accumulator complexity |

**Deprecated/outdated for this phase:**
- None — Phase 6 is entirely new functionality.

---

## Open Questions

1. **Visitor statefulness: How to safely scope complexity counting per method without instance-level state leaking between files?**
   - What we know: All existing visitors are stateless (no instance fields modified during traversal). ComplexityVisitor is the first visitor that needs to associate branch counts with specific methods.
   - What's unclear: Whether a local counter approach (reset per `visitMethodDeclaration` entry) works cleanly with OpenRewrite's visitor recursion model (nested methods, anonymous classes).
   - Recommendation: Use a `Deque<Integer>` as a cursor stack — push 0 on `visitMethodDeclaration` enter, increment on each branch node visit, pop and commit on `visitMethodDeclaration` exit (called by `super.visitMethodDeclaration`). This is safe for nested methods/lambdas.

2. **Should DB write detection be in ComplexityVisitor or a separate DbWriteVisitor?**
   - What we know: CONTEXT.md says "Extend existing JPA visitor (or add detection logic in the new ComplexityVisitor)". Both are valid.
   - What's unclear: Whether combining detection in ComplexityVisitor makes it too large/complex to test.
   - Recommendation: Add DB write detection to `ComplexityVisitor` as a secondary concern — it visits `visitMethodDeclaration` and `visitMethodInvocation` which are already being traversed. Adding a separate visitor adds a file and another loop iteration per source file. Keep together, separate test cases.

3. **Normalization of composite score: raw weighted sum vs. normalized metrics**
   - What we know: CONTEXT.md marks normalization strategy as Claude's discretion.
   - What's unclear: Without normalization, `complexitySum` (0-500+) dominates `fanIn` (0-20 typically), making weights irrelevant.
   - Recommendation: Use log normalization in the Cypher formula: `log(1 + complexitySum)` and `log(1 + fanIn)` to reduce scale differences. This is computable in Cypher using the `log()` function. Simple to implement, reversible, and does not require knowing max values upfront. Document the formula in code comments.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Testcontainers (Neo4j + MySQL + Qdrant) |
| Config file | `build.gradle.kts` — `tasks.withType<Test> { useJUnitPlatform() }` |
| Quick run command | `./gradlew test --tests "*ComplexityVisitorTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements -> Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| RISK-01 | ComplexityVisitor counts if/else/for/while/switch/catch correctly | unit | `./gradlew test --tests "*ComplexityVisitorTest"` | No — Wave 0 |
| RISK-01 | Per-method CC stored on MethodNode; sum/max on ClassNode | unit | `./gradlew test --tests "*ComplexityVisitorTest"` | No — Wave 0 |
| RISK-02 | fanIn/fanOut set correctly after Cypher aggregation | integration | `./gradlew test --tests "*RiskServiceIntegrationTest"` | No — Wave 0 |
| RISK-03 | DB write detection: @Modifying, persist/merge/remove calls | unit | `./gradlew test --tests "*ComplexityVisitorTest"` | No — Wave 0 |
| RISK-04 | Composite score formula applied with configurable weights | integration | `./gradlew test --tests "*RiskServiceIntegrationTest"` | No — Wave 0 |
| RISK-05 | GET /api/risk/heatmap returns sorted list with metrics | integration | `./gradlew test --tests "*RiskControllerIntegrationTest"` | No — Wave 0 |
| RISK-05 | GET /api/risk/class/{fqn} returns detail with method breakdown | integration | `./gradlew test --tests "*RiskControllerIntegrationTest"` | No — Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "*ComplexityVisitorTest"` (unit tests only, ~10s)
- **Per wave merge:** `./gradlew test` (full suite including Testcontainers integration tests, ~3-5 min)
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/com/esmp/extraction/visitor/ComplexityVisitorTest.java` — covers RISK-01, RISK-03 (unit: inline Java source fixtures, no Spring context)
- [ ] `src/test/java/com/esmp/graph/application/RiskServiceIntegrationTest.java` — covers RISK-02, RISK-04 (Testcontainers: full @SpringBootTest with Neo4j + MySQL + Qdrant containers)
- [ ] `src/test/java/com/esmp/graph/api/RiskControllerIntegrationTest.java` — covers RISK-05 (Testcontainers: full @SpringBootTest)

*(All three integration tests follow the established `LexiconIntegrationTest` pattern with Neo4j + MySQL + Qdrant containers. ComplexityVisitorTest follows JpaPatternVisitorTest pattern with inline fixtures.)*

---

## Sources

### Primary (HIGH confidence)
- Existing project source code (read directly) — `ClassMetadataVisitor.java`, `JpaPatternVisitor.java`, `CallGraphVisitor.java`, `ExtractionAccumulator.java`, `LexiconService.java`, `LexiconController.java`, `ValidationQueryRegistry.java`, `LexiconValidationQueryRegistry.java`, `ExtractionService.java`, `AccumulatorToModelMapper.java`, `Neo4jSchemaInitializer.java`
- Project `build.gradle.kts` — verified library versions and existing dependencies
- Project `application.yml` — verified configuration structure for adding risk weights

### Secondary (MEDIUM confidence)
- OpenRewrite Java AST node types (`J.If`, `J.WhileLoop`, etc.) — inferred from OpenRewrite's public JavaIsoVisitor API; consistent with standard OpenRewrite documentation patterns
- Neo4j Cypher pattern comprehensions for fan-in/out — standard Cypher 5 feature; used throughout project's existing Cypher queries

### Tertiary (LOW confidence)
- Default weight values (0.4/0.2/0.2/0.2) — heuristic recommendation; no empirical basis from the specific codebase
- Log normalization recommendation — common practice in software metrics but not validated against this codebase's actual value distributions

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in project; no new dependencies
- Architecture: HIGH — all patterns verified against existing project code
- Pitfalls: HIGH — most derived from reading actual project code decisions and known issues in STATE.md
- Test strategy: HIGH — follows established Testcontainers pattern from LexiconIntegrationTest

**Research date:** 2026-03-05
**Valid until:** 2026-04-05 (stable: no fast-moving dependencies; OpenRewrite and Neo4j APIs change slowly)
