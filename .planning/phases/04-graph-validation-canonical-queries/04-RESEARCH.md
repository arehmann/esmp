# Phase 4: Graph Validation & Canonical Queries - Research

**Researched:** 2026-03-05
**Domain:** Neo4j Cypher validation queries, Spring Boot REST, graph integrity patterns
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Validation query design:**
- Split ~10 structural integrity checks + ~10 architectural pattern checks
- Structural integrity: no orphan nodes, no dangling edges, uniqueness constraint compliance, relationship endpoint validity, inheritance chain completeness
- Architectural patterns: every @Service has DEPENDS_ON, every Repository has QUERIES, every UIView has BINDS_TO, etc.
- Queries organized in an extensible registry pattern — Phase 4 ships 20, future phases (e.g., Phase 5 Domain Lexicon) can add USES_TERM validation queries without modifying core
- Structural invariants are hard ERROR pass/fail; suspicious-but-not-broken conditions (0 QUERIES edges, very low Service count) are soft WARNING
- Report includes both counts AND specific failing entity details (e.g., "Orphan nodes: com.example.Foo, com.example.Bar") for actionability

**Ground truth source:**
- Validate against the real legacy codebase (not just synthetic fixtures)
- Manual verification: pick 1-2 well-understood modules, run extraction, then verify the graph output matches the developer's mental model of those modules' structure
- Module selection deferred to execution time — Phase 4 builds the validation framework; developer chooses the sample module when running it
- No formal architecture docs to compare against — developer's domain knowledge is the ground truth

**Failure handling:**
- Invocation: REST endpoint `GET /api/graph/validation` — runs all queries, returns JSON report with pass/fail/warn per query
- Validation is strictly read-only — report only, no auto-remediation
- Severity levels: ERROR (structurally broken — orphan nodes, missing edges) vs WARNING (suspicious — low counts, unexpected zeros)
- Report per query: name, severity, status (PASS/FAIL/WARN), count, details (list of specific failing entities)

**Dependency cone:**
- Cone = all nodes reachable from a focal class via ALL structural relationship types: DEPENDS_ON, EXTENDS, IMPLEMENTS, CALLS, BINDS_TO, QUERIES, MAPS_TO_TABLE (full transitive)
- Max depth: 10 hops (matches existing EXTENDS*1..10 and DEPENDS_ON*1..10 patterns)
- Exposed as new REST endpoint: `GET /api/graph/class/{fqn}/dependency-cone` — reusable by Phase 11 (RAG Pipeline) later
- Cone accuracy validated by comparing graph-derived cones against developer's mental model for the sample module

### Claude's Discretion
- Query format: Java constants vs external YAML — Claude picks based on existing codebase patterns
- Exact Cypher query implementations for each of the 20 canonical queries
- Validation report JSON schema design
- Cone query Cypher pattern (multi-relationship variable-length path)
- Whether to add graph summary/stats endpoint alongside validation
- Test strategy for validation queries (integration tests with Testcontainers)

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| GVAL-01 | 20 canonical validation queries defined and passing against populated graph | Cypher patterns for all 7 node types and 9 relationship types researched; registry pattern and report schema designed |
| GVAL-02 | Dependency cone accuracy verified against manually confirmed architectural expectations | Variable-length multi-relationship Cypher path pattern documented; SLO-01 (< 200ms) covered |
| GVAL-03 | No orphan nodes or duplicate structural nodes exist in graph | Orphan detection Cypher patterns and uniqueness constraint verification queries documented |
| GVAL-04 | Inheritance chains complete and transitive repository dependencies correctly resolved | Inheritance chain completeness query and QUERIES edge coverage query documented |
</phase_requirements>

---

## Summary

Phase 4 is entirely read-only: it adds a validation framework that runs Cypher queries against the existing code knowledge graph and reports structural integrity and architectural pattern compliance. The codebase already has all the infrastructure needed — `Neo4jClient`, `GraphQueryController`, Testcontainers integration test setup, and established response record patterns. The work is writing the 20 Cypher queries, structuring a registry, surfacing results via a new REST endpoint, and adding the dependency cone endpoint.

The key technical question in this phase is Cypher query design. Orphan detection, relationship completeness, and architectural invariant checking all have well-established Cypher patterns using `NOT (n)--()`, `OPTIONAL MATCH` with null checks, and label-based filtering. The multi-relationship cone query using `apoc.path.subgraphNodes` is the most complex piece — however since APOC may not be available in the Neo4j container used in tests, a native Cypher alternative using `*` variable-length path on a union of relationship types must be prepared. Neo4j native Cypher does not support OR across relationship types in a single `[r:A|B|C*]` with variable depth on all edges simultaneously, so the cone query will use `[r:DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS|BINDS_TO|QUERIES|MAPS_TO_TABLE*1..10]` — this IS supported natively and matches the existing codebase patterns.

**Primary recommendation:** Use Java string constants for the 20 Cypher queries (aligned with existing codebase pattern), register them in a `ValidationQueryRegistry` component, execute them via `Neo4jClient` in a new `ValidationService`, and expose results via a new `ValidationController`. The dependency cone follows the same `Neo4jClient` traversal pattern already used in `GraphQueryService`.

---

## Standard Stack

### Core (already on classpath — no new dependencies needed)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| spring-data-neo4j | 7.x (Spring Boot 3.5 BOM) | Neo4jClient for Cypher execution | Already used by GraphQueryService |
| neo4j-java-driver | 5.x (BOM managed) | Bolt protocol, Value/MapAccessor types | Already used in all graph queries |
| spring-boot-starter-web | 3.5.11 | REST controller for new endpoints | Already on classpath |
| testcontainers-neo4j | BOM managed | Integration test with real Neo4j | Already used in GraphQueryControllerIntegrationTest |
| JUnit 5 + AssertJ | BOM managed | Test assertions | Already used in test suite |

### No New Dependencies
All required libraries are already on the classpath. Phase 4 adds zero new `build.gradle.kts` entries.

---

## Architecture Patterns

### Recommended Package Structure

```
src/main/java/com/esmp/
├── graph/
│   ├── api/
│   │   ├── GraphQueryController.java          (existing — add cone endpoint here)
│   │   ├── ValidationController.java          (NEW — GET /api/graph/validation)
│   │   ├── ValidationReport.java              (NEW — top-level response record)
│   │   ├── ValidationQueryResult.java         (NEW — per-query result record)
│   │   └── DependencyConeResponse.java        (NEW — cone endpoint response)
│   ├── application/
│   │   ├── GraphQueryService.java             (existing — add findDependencyCone())
│   │   └── ValidationService.java             (NEW — runs all 20 queries)
│   └── validation/
│       ├── ValidationQueryRegistry.java       (NEW — holds all 20 query definitions)
│       ├── ValidationQuery.java               (NEW — record: name, cypher, severity, description)
│       └── ValidationSeverity.java            (NEW — enum: ERROR, WARNING)

src/test/java/com/esmp/graph/
│   ├── api/
│   │   ├── GraphQueryControllerIntegrationTest.java (existing)
│   │   └── ValidationControllerIntegrationTest.java (NEW)
│   └── validation/
│       └── ValidationServiceIntegrationTest.java    (NEW)
```

### Pattern 1: ValidationQuery Record (Immutable Definition)
**What:** Each of the 20 queries is defined as an immutable record with name, cypher, severity, and human-readable description.
**When to use:** Allows the registry to be iterable and extensible without modifying service logic.

```java
// com/esmp/graph/validation/ValidationQuery.java
package com.esmp.graph.validation;

/**
 * Immutable definition of a single graph validation query.
 *
 * @param name unique identifier (e.g., "ORPHAN_CLASSES")
 * @param description human-readable description for the report
 * @param cypher the Cypher query; must return columns: count AS count, details AS details
 * @param severity ERROR for structural breaks, WARNING for suspicious but non-fatal conditions
 */
public record ValidationQuery(
    String name,
    String description,
    String cypher,
    ValidationSeverity severity) {}
```

### Pattern 2: Registry as Spring Component
**What:** `ValidationQueryRegistry` is a `@Component` that initializes all 20 queries in its constructor and exposes them as an unmodifiable list.
**When to use:** Future phases add their own registry beans rather than modifying this class. The `ValidationService` accepts `List<ValidationQueryRegistry>` to aggregate all registered queries — extensible without touching core.

```java
// Existing codebase pattern: Neo4jClient + Java string constants for Cypher
// Source: GraphQueryService.java, established pattern throughout codebase

@Component
public class ValidationQueryRegistry {

    private final List<ValidationQuery> queries;

    public ValidationQueryRegistry() {
        this.queries = List.of(
            // Structural integrity queries (ERROR severity)
            new ValidationQuery(
                "ORPHAN_CLASS_NODES",
                "JavaClass nodes not reachable from any JavaPackage or JavaModule",
                """
                MATCH (c:JavaClass)
                WHERE NOT (:JavaPackage)-[:CONTAINS_CLASS]->(c)
                  AND NOT (:JavaModule)-[:CONTAINS_PACKAGE]->(:JavaPackage)-[:CONTAINS_CLASS]->(c)
                RETURN count(c) AS count,
                       collect(c.fullyQualifiedName)[0..20] AS details
                """,
                ValidationSeverity.WARNING),
            // ... all 20 queries
        );
    }

    public List<ValidationQuery> getQueries() {
        return queries;
    }
}
```

### Pattern 3: ValidationService Execution
**What:** `ValidationService` iterates over all queries, executes each via `Neo4jClient`, maps results to `ValidationQueryResult`, and returns a `ValidationReport`.
**When to use:** Always — this is the single execution path for validation.

```java
// Source: GraphQueryService.java — Neo4jClient .query().fetch().all() pattern
@Service
public class ValidationService {

    private final Neo4jClient neo4jClient;
    private final ValidationQueryRegistry registry;

    // ... constructor

    public ValidationReport runAllValidations() {
        List<ValidationQueryResult> results = new ArrayList<>();
        for (ValidationQuery query : registry.getQueries()) {
            ValidationQueryResult result = executeQuery(query);
            results.add(result);
        }
        long errorCount = results.stream()
            .filter(r -> r.status() == ValidationStatus.FAIL).count();
        long warnCount = results.stream()
            .filter(r -> r.status() == ValidationStatus.WARN).count();
        return new ValidationReport(
            Instant.now().toString(), results, errorCount, warnCount);
    }

    private ValidationQueryResult executeQuery(ValidationQuery query) {
        Collection<Map<String, Object>> rows = neo4jClient
            .query(query.cypher())
            .fetch()
            .all();
        // Extract count and details from result
        // Determine PASS/FAIL/WARN based on count and severity
        ...
    }
}
```

### Pattern 4: Dependency Cone Query
**What:** Variable-length path on all 7 structural relationship types, up to 10 hops, returning all reachable node FQNs and their labels.
**When to use:** The `GET /api/graph/class/{fqn}/dependency-cone` endpoint.

```cypher
// Source: established pattern from GraphQueryService.java EXTENDS*1..10 and DEPENDS_ON*1..10
// Neo4j native Cypher — no APOC required

MATCH (focal:JavaClass {fullyQualifiedName: $fqn})
OPTIONAL MATCH (focal)-[:DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS|BINDS_TO|QUERIES|MAPS_TO_TABLE*1..10]->(reachable)
WITH focal, collect(DISTINCT reachable) AS reachableNodes
RETURN focal.fullyQualifiedName AS focalFqn,
       [n IN reachableNodes | {
           fqn: CASE
                    WHEN n:JavaClass THEN n.fullyQualifiedName
                    WHEN n:JavaMethod THEN n.methodId
                    WHEN n:JavaField THEN n.fieldId
                    WHEN n:JavaAnnotation THEN n.fullyQualifiedName
                    WHEN n:JavaPackage THEN n.packageName
                    WHEN n:JavaModule THEN n.moduleName
                    WHEN n:DBTable THEN n.tableName
                    ELSE 'unknown'
                END,
           labels: labels(n)
       }] AS coneNodes,
       size(reachableNodes) AS coneSize
```

**CRITICAL NOTE on multi-relationship variable-length paths:** Neo4j 5.x supports `[r:TYPE_A|TYPE_B|TYPE_C*1..N]` natively. This is the same syntax already used in `GraphQueryService` (e.g., `[:DEPENDS_ON*1..10]`, `[:EXTENDS*1..10]`). The union syntax `|` between relationship types works with variable-length. This is verified by the existing codebase's use of `DEPENDS_ON|IMPLEMENTS*1..3` in `LinkingService`. Confidence: HIGH.

### Pattern 5: Response Record Design
**What:** Plain Java records for JSON serialization via Jackson (auto-configured by Spring Boot).
**When to use:** Follows existing pattern in `com.esmp.graph.api` — `ClassStructureResponse`, `InheritanceChainResponse`, etc.

```java
// ValidationQueryResult.java
public record ValidationQueryResult(
    String name,
    String description,
    ValidationSeverity severity,
    ValidationStatus status,    // PASS, FAIL, WARN
    long count,
    List<String> details        // specific failing entity IDs (capped at 20 for readability)
) {}

// ValidationReport.java
public record ValidationReport(
    String generatedAt,         // ISO-8601 timestamp
    List<ValidationQueryResult> results,
    long errorCount,
    long warnCount
) {}

// DependencyConeResponse.java
public record DependencyConeResponse(
    String focalFqn,
    List<ConeNode> coneNodes,
    int coneSize
) {
    public record ConeNode(String fqn, List<String> labels) {}
}
```

### Anti-Patterns to Avoid
- **SDN repository for validation queries:** SDN's `@Query` annotation and derived queries cannot map variable-length paths or `collect()` aggregations cleanly. Use `Neo4jClient` directly for all validation queries — same as `GraphQueryService`.
- **String concatenation in Cypher:** Always use `.bind(value).to("paramName")` — even for validation queries that appear to have no parameters. The cone endpoint takes an FQN parameter.
- **Modifying the graph during validation:** Validation must be strictly read-only. No `MERGE`, `CREATE`, or `DELETE` statements in any validation query.
- **Unbounded `details` list:** Cap returned failing entity lists at 20 entries (`collect(n.fqn)[0..20]`) to prevent huge response payloads for severely broken graphs.

---

## The 20 Canonical Validation Queries

### Structural Integrity Checks (10 queries, ERROR severity unless noted)

| # | Query Name | What It Checks | Expected Result | Severity |
|---|-----------|----------------|-----------------|----------|
| 1 | ORPHAN_CLASS_NODES | JavaClass nodes with no CONTAINS_CLASS incoming edge | count = 0 | WARNING |
| 2 | DANGLING_METHOD_NODES | JavaMethod nodes with no DECLARES_METHOD incoming edge | count = 0 | ERROR |
| 3 | DANGLING_FIELD_NODES | JavaField nodes with no DECLARES_FIELD incoming edge | count = 0 | ERROR |
| 4 | DANGLING_ANNOTATION_NODES | JavaAnnotation nodes with no HAS_ANNOTATION incoming edge | count = 0 | WARNING |
| 5 | DUPLICATE_CLASS_FQNS | JavaClass nodes with non-unique fullyQualifiedName (constraint check) | count = 0 | ERROR |
| 6 | EXTENDS_CHAIN_DANGLING | Classes with superClass property set but no EXTENDS edge (missing parent in graph) | count = 0 | WARNING |
| 7 | IMPLEMENTS_MISSING | Classes with implementedInterfaces containing FQNs that exist in graph but no IMPLEMENTS edge | count = 0 | WARNING |
| 8 | MAPS_TO_TABLE_ORPHAN_TABLE | DBTable nodes with no MAPS_TO_TABLE incoming edge | count = 0 | WARNING |
| 9 | QUERIES_EDGE_INTEGRITY | QUERIES edges where source is not a JavaMethod or target is not a DBTable | count = 0 | ERROR |
| 10 | BINDS_TO_EDGE_INTEGRITY | BINDS_TO edges where source or target node does not exist | count = 0 | ERROR |

### Architectural Pattern Checks (10 queries, WARNING severity unless noted)

| # | Query Name | What It Checks | Expected Result | Severity |
|---|-----------|----------------|-----------------|----------|
| 11 | SERVICE_HAS_DEPENDENCIES | Service-labeled classes with zero DEPENDS_ON outgoing edges | count = 0 | WARNING |
| 12 | REPOSITORY_HAS_QUERIES | Repository-labeled classes whose methods have zero QUERIES outgoing edges | count = 0 | WARNING |
| 13 | UI_VIEW_HAS_BINDS_TO | VaadinView-labeled classes with zero BINDS_TO outgoing edges | count = 0 | WARNING |
| 14 | ENTITY_MAPS_TO_TABLE | Classes with @Entity annotation but no MAPS_TO_TABLE outgoing edge | count = 0 | WARNING |
| 15 | INHERITANCE_CHAIN_COMPLETENESS | Classes whose superClass FQN exists in graph but EXTENDS edge is missing | count = 0 | ERROR |
| 16 | TRANSITIVE_REPO_DEPENDENCY | Service → DEPENDS_ON*1..10 → Repository path exists for each service | count > 0 or no services | WARNING |
| 17 | NO_ISOLATED_MODULES | JavaModule nodes with no CONTAINS_PACKAGE outgoing edges | count = 0 | WARNING |
| 18 | NO_EMPTY_PACKAGES | JavaPackage nodes with no CONTAINS_CLASS outgoing edges | count = 0 | WARNING |
| 19 | ANNOTATION_COVERAGE | JavaClass nodes with empty annotations list and no HAS_ANNOTATION edge (fully unannotated classes) | informational count | WARNING |
| 20 | CALLS_EDGE_COVERAGE | Total CALLS edges in graph (sanity check — should be > 0 after extraction) | count > 0 | WARNING |

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON serialization of response records | Custom serializer | Jackson (auto-configured Spring Boot) | Records serialize cleanly via Jackson; already proven in 4 existing endpoints |
| Cypher result iteration | Custom result mapper | `neo4jClient.query().fetch().all()` + Map iteration | Pattern already established in GraphQueryService |
| Neo4j connection management | Connection pooling code | Spring Boot Neo4j auto-configuration | Already handles bolt protocol, connection pool, retry |
| Test container lifecycle | Manual Docker management | `@Testcontainers` + `@Container` static fields | Already in GraphQueryControllerIntegrationTest |
| Relationship type union in Cypher | Multiple separate queries | `[:TYPE_A|TYPE_B*1..N]` native syntax | Neo4j 5 supports this natively |

**Key insight:** This phase is 95% Cypher query authoring and response record design. All Java infrastructure already exists.

---

## Common Pitfalls

### Pitfall 1: Cypher `collect()` Returns Null Instead of Empty List
**What goes wrong:** When `OPTIONAL MATCH` finds no results, `collect()` returns `[]` (empty list) not null — BUT if the outer `MATCH` finds nothing, the row itself is absent. Validation queries must always return exactly one row.
**Why it happens:** Aggregation functions like `count()` and `collect()` collapse rows. If the MATCH clause finds nothing, there are no rows to aggregate — the query returns zero rows, not a row with count=0.
**How to avoid:** Use `OPTIONAL MATCH` for the "failing" part, always have a root node in the main `MATCH`, or use `RETURN coalesce(count(n), 0)`. Pattern:
```cypher
// CORRECT — always returns one row even when nothing fails
MATCH (c:JavaClass)
WHERE NOT EXISTS { (c)<-[:DECLARES_METHOD]-() }  -- wrong, Methods don't point to classes
// Better pattern for orphan detection:
OPTIONAL MATCH (m:JavaMethod) WHERE NOT (m)<-[:DECLARES_METHOD]-()
RETURN count(m) AS count, collect(m.methodId)[0..20] AS details
```
**Warning signs:** Query returns 0 rows total (as opposed to 1 row with count=0).

### Pitfall 2: Variable-Length Path Performance on Large Graphs
**What goes wrong:** `*1..10` variable-length paths on multi-relationship types can be slow on large graphs (tens of thousands of nodes).
**Why it happens:** The dependency cone with 7 relationship types explores an exponentially large search space without indexes on relationship types.
**How to avoid:** The SLO is 200ms (SLO-01). Add a Neo4j index on `JavaClass.fullyQualifiedName` (already enforced via uniqueness constraint — uniqueness constraints imply B-tree indexes in Neo4j 5). The cone query starts from a single focal node with a property equality match, which hits the index. Depth limit of 10 is already in place. Monitor with `PROFILE` if needed.
**Warning signs:** Cone endpoint takes > 200ms on the test graph.

### Pitfall 3: Direction Matters in Relationship Queries
**What goes wrong:** Using undirected `-[r:DEPENDS_ON]-` when the model stores directed `(class)-[DEPENDS_ON]->(dep)` relationships gives wrong counts.
**Why it happens:** Easy to omit `->` in Cypher while writing quickly.
**How to avoid:** Always verify relationship direction against `LinkingService.java` which is the canonical source. All relationships are outgoing from the class:
- `(c)-[:EXTENDS]->(parent)` — outgoing
- `(c)-[:IMPLEMENTS]->(iface)` — outgoing
- `(c)-[:DEPENDS_ON]->(dep)` — outgoing
- `(c)-[:MAPS_TO_TABLE]->(table)` — outgoing
- `(m)-[:QUERIES]->(table)` — outgoing (MethodNode to DBTable)
- `(view)-[:BINDS_TO]->(entity)` — outgoing
- `(pkg)-[:CONTAINS_CLASS]->(c)` — incoming to class
- `(mod)-[:CONTAINS_PACKAGE]->(pkg)` — incoming to package

### Pitfall 4: `collect()` in Cypher Returns `Value` Not `List<String>` via `fetchAs()`
**What goes wrong:** When using `fetchAs(MyRecord.class).mappedBy()`, the `record.get("details")` Value is a Cypher list, not a Java `List<String>`.
**Why it happens:** Neo4j driver returns list values as `Value` objects that must be explicitly converted with `.asList(Value::asString)`.
**How to avoid:** Use the established `toStringList(Value value)` helper already in `GraphQueryService`:
```java
// COPY this pattern from GraphQueryService:
private List<String> toStringList(Value value) {
    if (value == null || value.isNull()) return new ArrayList<>();
    try { return value.asList(Value::asString); }
    catch (Exception e) { return new ArrayList<>(); }
}
```
Use `neo4jClient.query().fetch().all()` (returns `Collection<Map<String, Object>>`) for validation queries since the map-based access is simpler when dealing with mixed count + list return shapes.

### Pitfall 5: Neo4j 2026.x Label Syntax
**What goes wrong:** The test container uses `neo4j:2026.01.4` (as seen in `GraphQueryControllerIntegrationTest`). Label existence checks must use the correct syntax.
**Why it happens:** Neo4j has changed syntax across versions. `ANY(label IN labels(n) WHERE label = 'X')` is stable across Neo4j 4.x and 5.x.
**How to avoid:** Use `n:LabelName` shorthand or `ANY(label IN labels(n) WHERE label = 'Service')` — both work in Neo4j 5.x / 2026.x. Avoid deprecated `HAS` clause.

---

## Code Examples

### Orphan Node Detection (Structural Integrity)
```cypher
-- Dangling JavaMethod nodes (no DECLARES_METHOD incoming edge)
-- Source: established Neo4j pattern for orphan detection
OPTIONAL MATCH (m:JavaMethod)
WHERE NOT ()-[:DECLARES_METHOD]->(m)
RETURN count(m) AS count,
       collect(m.methodId)[0..20] AS details
```

### Architectural Pattern Check (Service has Dependencies)
```cypher
-- Services with zero DEPENDS_ON edges (suspicious — every service should inject something)
-- Source: established pattern from GraphQueryService service-dependents query
OPTIONAL MATCH (c:JavaClass)
WHERE ANY(label IN labels(c) WHERE label = 'Service')
  AND NOT (c)-[:DEPENDS_ON]->()
RETURN count(c) AS count,
       collect(c.fullyQualifiedName)[0..20] AS details
```

### Dependency Cone (Multi-Relationship Variable-Length Path)
```cypher
-- Full dependency cone from focal class, all relationship types, up to 10 hops
-- Source: established pattern from GraphQueryService EXTENDS*1..10 and DEPENDS_ON*1..10
MATCH (focal:JavaClass {fullyQualifiedName: $fqn})
OPTIONAL MATCH (focal)-[:DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS|BINDS_TO|QUERIES|MAPS_TO_TABLE*1..10]->(reachable)
WITH focal, collect(DISTINCT reachable) AS reachableNodes
RETURN focal.fullyQualifiedName AS focalFqn,
       [n IN reachableNodes |
           CASE
               WHEN n:JavaClass THEN {fqn: n.fullyQualifiedName, labels: labels(n)}
               WHEN n:JavaMethod THEN {fqn: n.methodId, labels: labels(n)}
               WHEN n:JavaField THEN {fqn: n.fieldId, labels: labels(n)}
               WHEN n:JavaAnnotation THEN {fqn: n.fullyQualifiedName, labels: labels(n)}
               WHEN n:JavaPackage THEN {fqn: n.packageName, labels: labels(n)}
               WHEN n:JavaModule THEN {fqn: n.moduleName, labels: labels(n)}
               WHEN n:DBTable THEN {fqn: n.tableName, labels: labels(n)}
               ELSE {fqn: 'unknown', labels: labels(n)}
           END
       ] AS coneNodes,
       size(reachableNodes) AS coneSize
```

### EXTENDS Chain Completeness Check
```cypher
-- Classes with superClass property set but no EXTENDS edge in the graph
-- (indicates parent class not extracted — external dependency or extraction gap)
-- Source: LinkingService.linkInheritanceRelationships() — complementary validation
OPTIONAL MATCH (c:JavaClass)
WHERE c.superClass IS NOT NULL
  AND c.superClass <> ''
  AND NOT (c)-[:EXTENDS]->()
RETURN count(c) AS count,
       collect(c.fullyQualifiedName + ' -> ' + c.superClass)[0..20] AS details
```

### Validation Query Cypher Protocol (PASS/FAIL/WARN Logic)
```java
// Source: established Neo4jClient pattern from GraphQueryService
// Use fetch().all() for consistent access to count + details columns

private ValidationQueryResult executeQuery(ValidationQuery query) {
    Collection<Map<String, Object>> rows = neo4jClient
        .query(query.cypher())
        .fetch()
        .all();

    // Each validation query returns exactly 1 row with 'count' and 'details'
    long count = 0L;
    List<String> details = new ArrayList<>();

    if (!rows.isEmpty()) {
        Map<String, Object> row = rows.iterator().next();
        Object countObj = row.get("count");
        if (countObj instanceof Long l) count = l;

        Object detailsObj = row.get("details");
        if (detailsObj instanceof List<?> list) {
            details = list.stream()
                .filter(d -> d instanceof String)
                .map(d -> (String) d)
                .collect(Collectors.toList());
        }
    }

    // Determine status: count == 0 means PASS (no failing entities)
    ValidationStatus status;
    if (count == 0) {
        status = ValidationStatus.PASS;
    } else if (query.severity() == ValidationSeverity.WARNING) {
        status = ValidationStatus.WARN;
    } else {
        status = ValidationStatus.FAIL;
    }

    return new ValidationQueryResult(
        query.name(), query.description(), query.severity(), status, count, details);
}
```

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + AssertJ (Spring Boot Test BOM) |
| Config file | `build.gradle.kts` — `tasks.withType<Test> { useJUnitPlatform() }` |
| Quick run command | `./gradlew test --tests "com.esmp.graph.validation.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements to Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| GVAL-01 | 20 validation queries registered and all return PASS on a well-formed test graph | Integration | `./gradlew test --tests "com.esmp.graph.api.ValidationControllerIntegrationTest"` | ❌ Wave 0 |
| GVAL-01 | Each individual query correctly identifies the specific failure it targets (injected bad data) | Integration | `./gradlew test --tests "com.esmp.graph.validation.ValidationServiceIntegrationTest"` | ❌ Wave 0 |
| GVAL-02 | Dependency cone endpoint returns correct node set for a known test class | Integration | `./gradlew test --tests "com.esmp.graph.api.ValidationControllerIntegrationTest#testDependencyCone*"` | ❌ Wave 0 |
| GVAL-03 | Orphan node and duplicate node queries detect planted violations | Integration | `./gradlew test --tests "com.esmp.graph.validation.ValidationServiceIntegrationTest#testOrphan*"` | ❌ Wave 0 |
| GVAL-04 | Inheritance chain completeness query flags classes with broken EXTENDS edges | Integration | `./gradlew test --tests "com.esmp.graph.validation.ValidationServiceIntegrationTest#testInheritanceChain*"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.graph.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/graph/api/ValidationControllerIntegrationTest.java` — covers GVAL-01 (endpoint) and GVAL-02 (cone)
- [ ] `src/test/java/com/esmp/graph/validation/ValidationServiceIntegrationTest.java` — covers GVAL-01 (20 queries), GVAL-03 (orphans), GVAL-04 (inheritance)
- [ ] No new framework install needed — Testcontainers, JUnit 5, AssertJ already in `build.gradle.kts`

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| APOC `apoc.path.subgraphNodes` for cone traversal | Native `[:A|B|C*1..N]` Cypher | Neo4j 4+ | No APOC plugin required; works in any Neo4j container including test containers |
| `@Query` on SDN repository for complex Cypher | `Neo4jClient` directly | Project Phase 2 decision | Avoids SDN path mapping limitations; already established in this codebase |
| External validation frameworks (testcontainers assertions) | Cypher-based assertions returned as JSON | Project decision | Validation lives in the running service, not in test code — can be invoked by operator post-deployment |

**Deprecated/outdated:**
- SDN repository `@Query` for variable-length paths: Cannot map `collect()` or path objects — use `Neo4jClient` (project-established pattern)
- Neo4j 3.x `HAS` keyword for label checks: Replaced by `n:LabelName` and `ANY(label IN labels(n) WHERE ...)` — Neo4j 5.x (used in project)

---

## Open Questions

1. **Whether `CALLS` edges are populated in the current graph**
   - What we know: `CallGraphVisitor` exists and produces CALLS edges per the Phase 2/3 implementation. `CALLS` is listed as a valid relationship type in the CKG-02 requirement.
   - What's unclear: The density of CALLS edges depends on how well OpenRewrite resolved inter-class method calls on the legacy codebase. The validation query #20 (CALLS_EDGE_COVERAGE) will surface this.
   - Recommendation: Make query #20 a WARNING with informational intent, not a hard FAIL. Log the count for diagnostic purposes.

2. **Cone query performance on large legacy codebase**
   - What we know: SLO-01 requires < 200ms. The uniqueness constraint on `fullyQualifiedName` implies a B-tree index. Test graphs in integration tests are small (5-10 nodes).
   - What's unclear: Real legacy codebase cone size. A tightly-coupled legacy service class could have a cone of 100+ nodes.
   - Recommendation: Add `LIMIT` as an optional query parameter to the cone endpoint (`?maxDepth=N`, default 10) and log cone size + query time. Do not block phase on performance until measured against real data.

3. **ValidationQueryResult `details` list type**
   - What we know: Some queries return FQNs (JavaClass), some return `methodId` (JavaMethod), some return composite strings. The column name `details` must be a `List<String>`.
   - What's unclear: Best string format for relationship-spanning errors (e.g., "com.example.Foo extends unknown.Bar").
   - Recommendation: Use concatenated strings in Cypher (`c.fullyQualifiedName + ' -> ' + c.superClass`) for composite details. Type is always `List<String>`.

---

## Sources

### Primary (HIGH confidence)
- `src/main/java/com/esmp/graph/application/GraphQueryService.java` — Neo4jClient pattern, variable-length path queries, `fetch().all()` usage, `toStringList()` helper
- `src/main/java/com/esmp/extraction/application/LinkingService.java` — canonical relationship directions for all 9 relationship types
- `src/main/java/com/esmp/extraction/config/Neo4jSchemaInitializer.java` — all 7 node types and their ID properties
- `src/test/java/com/esmp/graph/api/GraphQueryControllerIntegrationTest.java` — Testcontainers setup, test graph population pattern, assertion style
- `src/main/java/com/esmp/graph/api/GraphQueryController.java` — REST controller pattern, `{fqn:.+}` regex path variable
- `.planning/phases/04-graph-validation-canonical-queries/04-CONTEXT.md` — locked decisions and constraints

### Secondary (MEDIUM confidence)
- Neo4j 5.x documentation: Variable-length path with relationship type union `[:A|B|C*1..N]` is supported natively — verified against existing `DEPENDS_ON|IMPLEMENTS*1..3` usage in `LinkingService.linkQueryMethods()`
- Spring Boot 3.5 / Spring Data Neo4j 7.x: `Neo4jClient` `.fetch().all()` returns `Collection<Map<String, Object>>` — verified against `GraphQueryService.findServiceDependents()` and `findInheritanceChain()`

### Tertiary (LOW confidence)
- None — all critical claims are grounded in the project's own source code

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; everything verified in existing source
- Architecture: HIGH — patterns directly mirrored from `GraphQueryService` and existing controller/response structure
- 20 Cypher queries (structural): HIGH — based on exact graph schema from `Neo4jSchemaInitializer` and `LinkingService`
- 20 Cypher queries (architectural): MEDIUM — correctness of "every Service has DEPENDS_ON" depends on extraction completeness; queries correctly detect violations but real-world thresholds are unknown until run against actual data
- Cone query: HIGH — `[:A|B*1..N]` syntax verified against existing project usage
- Pitfalls: HIGH — all sourced from existing codebase comments and established project decisions

**Research date:** 2026-03-05
**Valid until:** 2026-04-05 (stable domain — Neo4j Cypher and Spring Boot patterns are stable)
