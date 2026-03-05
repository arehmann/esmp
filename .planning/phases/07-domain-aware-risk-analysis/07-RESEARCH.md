# Phase 7: Domain-Aware Risk Analysis - Research

**Researched:** 2026-03-05
**Domain:** Neo4j Cypher graph traversal, keyword-based heuristic scoring, Spring @ConfigurationProperties extension, Spring Boot integration testing
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Phase boundary:** No new extraction visitors. This phase operates exclusively on existing graph data (USES_TERM, DEFINES_RULE edges, BusinessTermNode.criticality). All domain scoring is computed via Cypher SET queries in RiskService, mirroring Phase 6's pattern.

**Security detection heuristics:**
- Combined approach: keyword matching on class names + package paths + annotation detection
- Keyword list (starting point): Auth, Login, Security, Encrypt, Cipher, Credential, Token, Permission, Acl
- Annotation detection: @Secured, @PreAuthorize, @RolesAllowed and similar Spring Security annotations from ClassNode.annotations[]
- Package path matching: classes in security/auth/crypto packages inherit a base security score
- Keyword list hardcoded as static constant (matches STEREOTYPE_LABELS pattern in RiskService)
- Security sensitivity is a graduated score (0.0–1.0): annotation=0.5 base, name=0.3 base, both=0.8+, package boost applies on top

**Financial detection heuristics:**
- Same multi-signal pattern as security: name keywords + package paths + annotation matching
- Financial keywords (starting point): Payment, Invoice, Billing, Ledger, Account, Transaction, Currency, Tax
- Graduated score (0.0–1.0) matching the security approach
- Direct matches only — no graph traversal to find financial-adjacent classes
- USES_TERM enrichment: if a class USES_TERM a BusinessTerm with financial keywords, boost its financial involvement score

**Enhanced score composition:**
- Extend existing RiskWeightConfig with 4 new domain weights (domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity)
- Single formula with all 8 dimensions, weights sum to 1.0
- Default weight distribution: 60% structural / 40% domain
- Store as new `enhancedRiskScore` property on ClassNode alongside existing `structuralRiskScore`
- Extend GET /api/risk/heatmap with new domain score fields + `sortBy` parameter (structural vs enhanced)
- Also store individual domain scores (domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity) on ClassNode

**Criticality aggregation:**
- Max criticality: take the highest criticality of any linked BusinessTerm via USES_TERM
- Criticality levels: High=1.0, Low=0.0 (binary — no expansion)
- Classes with zero USES_TERM edges get domainCriticality=0.0
- Business rule density uses log-normalized count: log(1 + definesRuleCount), matching Phase 6 pattern

### Claude's Discretion
- Exact keyword lists for security and financial detection (use the CONTEXT.md terms as starting point, expand as needed)
- Exact match density weights for graduated scoring (the 0.3/0.5/0.8 values from CONTEXT.md)
- Score clamping/normalization approach
- Validation query design for the 3 new domain risk validation queries
- Neo4j index strategy for new properties

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| DRISK-01 | System computes domain criticality weight per class from associated business term criticality ratings | USES_TERM graph traversal + max(criticality) aggregation via Cypher pattern comprehension; BusinessTermNode.criticality is string "High"/"Low" — map to 1.0/0.0 |
| DRISK-02 | System scores security sensitivity for classes handling authentication, authorization, or encryption | Keyword matching on simpleName + annotations list + packageName via Cypher WHERE clauses; graduated 0.0–1.0 score SET on each JavaClass node |
| DRISK-03 | System scores financial involvement for classes in payment, billing, or ledger operations | Same multi-signal heuristic as DRISK-02 + USES_TERM boost for BusinessTerms with financial keywords |
| DRISK-04 | System computes business rule density per class from DEFINES_RULE edge count and rule complexity | DEFINES_RULE edge count via Cypher pattern comprehension; log(1 + count) normalization matching Phase 6 pattern |
| DRISK-05 | System produces enhanced composite risk score combining structural risk, domain criticality, security sensitivity, financial involvement, and business rule density | 8-dimension weighted formula in single Cypher SET query; structuralRiskScore is pre-computed input; new enhancedRiskScore stored alongside it |
</phase_requirements>

---

## Summary

Phase 7 extends the Phase 6 structural risk scoring infrastructure with four domain-specific dimensions. Unlike Phase 6 which required new AST visitors, Phase 7 operates entirely on data already in the graph: BusinessTermNode criticality ratings (via USES_TERM edges), class names and annotations for heuristic security/financial detection, and DEFINES_RULE edges for business rule density.

The implementation follows the exact same Cypher-SET pattern established in Phase 6: `computeAndPersistRiskScores()` in RiskService is extended with new private methods that run MATCH + SET queries to write domain scores directly to JavaClass nodes. No new Spring services, repositories, or extraction visitors are needed. The pipeline order is: structural scores already exist → domain scores extend them → enhanced composite formula combines all 8.

The most nuanced part is the graduated security/financial scoring heuristic. A keyword match on simpleName contributes 0.3, an annotation match contributes 0.5, both together contribute 0.8 before clamping. Package-path match adds 0.2 as a boost. USES_TERM matching for financial terms adds 0.2 as a boost. All scores clamp to [0.0, 1.0] using `min(1.0, score)` in Cypher. This produces a continuously graduated score rather than a binary flag, enabling the composite formula to apply configurable weights.

**Primary recommendation:** Extend RiskService with 4 new Cypher-based computation methods, extend RiskWeightConfig with 4 new domain weights, add 5 new properties to ClassNode, extend the two API response records, add a `sortBy` parameter to the heatmap endpoint, add 3 validation queries to a new DomainRiskValidationQueryRegistry, and add an index on enhancedRiskScore. Zero new Spring services required.

---

## Standard Stack

### Core (all already in project — no new dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Data Neo4j | 7.x (Spring Boot 3.5.11) | SDN entity mapping + @Version MERGE | Already in project — ClassNode entity |
| Neo4j Java Driver | 5.x | Neo4jClient Cypher execution | Already in project — all risk queries use this |
| Spring Boot ConfigurationProperties | 3.5.11 | RiskWeightConfig extension | Already in project — @ConfigurationProperties pattern established |
| JUnit 5 + Testcontainers | 5.x / 1.x | Integration test with real Neo4j | Already in project — RiskServiceIntegrationTest pattern |

**No new dependencies required.** This phase is a pure extension of Phase 6 infrastructure.

---

## Architecture Patterns

### Recommended Project Structure

No new files in new packages. All changes extend existing files or add sibling files:

```
src/main/java/com/esmp/
├── extraction/
│   ├── config/
│   │   └── RiskWeightConfig.java          # EXTEND: add 4 domain weights
│   └── model/
│       └── ClassNode.java                 # EXTEND: add 5 new properties
├── graph/
│   ├── api/
│   │   ├── RiskHeatmapEntry.java          # EXTEND: add domain score fields
│   │   ├── RiskDetailResponse.java        # EXTEND: add domain score fields
│   │   └── RiskController.java            # EXTEND: add sortBy parameter
│   ├── application/
│   │   └── RiskService.java               # EXTEND: add 5 new computation methods
│   └── validation/
│       └── DomainRiskValidationQueryRegistry.java   # NEW: 3 new validation queries
src/main/resources/
│   └── application.yml                    # EXTEND: add 4 domain weight defaults
src/main/java/com/esmp/extraction/config/
│   └── Neo4jSchemaInitializer.java        # EXTEND: add enhancedRiskScore index
src/test/java/com/esmp/graph/application/
│   └── DomainRiskServiceIntegrationTest.java  # NEW: integration tests
```

### Pattern 1: Cypher-Based Domain Score Computation

**What:** Each domain dimension is computed in its own private method in RiskService. Each method runs a single MATCH + SET Cypher query that writes a score property directly to all JavaClass nodes. Methods are called in sequence from `computeAndPersistRiskScores()`.

**When to use:** Always — this is the established project pattern. Never compute scores in Java loops.

**Example — Domain Criticality (DRISK-01):**
```java
// Source: Phase 6 RiskService pattern, adapted for USES_TERM traversal
private void computeDomainCriticality() {
    String cypher = """
        MATCH (c:JavaClass)
        OPTIONAL MATCH (c)-[:USES_TERM]->(t:BusinessTerm)
        WITH c,
             max(CASE WHEN t.criticality = 'High' THEN 1.0
                      WHEN t.criticality = 'Low'  THEN 0.0
                      ELSE 0.0 END) AS maxCrit
        SET c.domainCriticality = coalesce(maxCrit, 0.0)
        """;
    neo4jClient.query(cypher).run();
}
```

**Example — Business Rule Density (DRISK-04):**
```java
// Source: Phase 6 log normalization pattern, adapted for DEFINES_RULE
private void computeBusinessRuleDensity() {
    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             size([(c)-[:DEFINES_RULE]->(t) | t]) AS ruleCount
        SET c.businessRuleDensity = log(1.0 + ruleCount)
        """;
    neo4jClient.query(cypher).run();
}
```

**Example — Security Sensitivity (DRISK-02):**
```java
// Graduated score: keyword=0.3 base, annotation=0.5 base, both=0.8, package boost=+0.2
// Clamped to [0.0, 1.0] via min(1.0, ...)
private void computeSecuritySensitivity() {
    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             // Name keyword match (case-insensitive: tolower used on simpleName)
             CASE WHEN toLower(c.simpleName) =~ $namePattern THEN 1 ELSE 0 END AS nameHit,
             // Annotation match on stored annotations list
             CASE WHEN ANY(a IN c.annotations WHERE toLower(a) =~ $annotPattern) THEN 1 ELSE 0 END AS annotHit,
             // Package path match
             CASE WHEN toLower(coalesce(c.packageName, '')) =~ $pkgPattern THEN 1 ELSE 0 END AS pkgHit
        SET c.securitySensitivity = min(1.0,
             (nameHit * $nameWeight) +
             (annotHit * $annotWeight) +
             (CASE WHEN nameHit = 1 AND annotHit = 1 THEN $bothBonus ELSE 0.0 END) +
             (pkgHit * $pkgBoost)
        )
        """;
    neo4jClient.query(cypher)
        .bindAll(Map.of(
            "namePattern", buildSecurityNamePattern(),
            "annotPattern", buildSecurityAnnotPattern(),
            "pkgPattern", buildSecurityPkgPattern(),
            "nameWeight", 0.3,
            "annotWeight", 0.5,
            "bothBonus", 0.2,   // extra when both name AND annotation match
            "pkgBoost", 0.2))
        .run();
}
```

**Note on Cypher regex vs. list patterns:** The `annotations` property is a `List<String>` stored as a Neo4j property array. Use `ANY(a IN c.annotations WHERE ...)` — this is the established pattern from Phase 4 validation queries (e.g., `ANY(label IN labels(c) WHERE label = 'Service')`).

**Note on `=~` regex in Cypher:** Neo4j regex uses `=~` operator. Pattern must be a full-match regex (anchored implicitly). To do case-insensitive contains: `toLower(c.simpleName) =~ '.*(auth|login|security|encrypt|cipher|credential|token|permission|acl).*'`. This is the correct Neo4j Cypher approach — verified against project's existing use of `=~` in LexiconValidationQueryRegistry (DEFINES_RULE_COVERAGE query uses `=~`).

### Pattern 2: Keyword Set as Static Java Constants (for pattern building)

**What:** Security and financial keyword lists are Java static constants, assembled into regex patterns before passing to Cypher as parameters. This avoids string interpolation in Cypher (uses parameters) while keeping keywords configurable in one place.

```java
// In RiskService:
private static final List<String> SECURITY_KEYWORDS = List.of(
    "auth", "login", "security", "encrypt", "cipher", "credential",
    "token", "permission", "acl", "oauth", "jwt", "password", "secret",
    "session", "principal", "role", "privilege");

private static final List<String> FINANCIAL_KEYWORDS = List.of(
    "payment", "invoice", "billing", "ledger", "account", "transaction",
    "currency", "tax", "price", "fee", "charge", "refund", "balance",
    "credit", "debit", "wallet", "payable", "receivable");

private static final List<String> SECURITY_PKG_KEYWORDS = List.of(
    "security", "auth", "crypto", "encryption", "authentication", "authorization");

private static final List<String> SECURITY_ANNOTATIONS = List.of(
    "secured", "preauthorize", "postauthorize", "rolesallowed",
    "permitall", "denyall", "withsecuritycontext");

private String buildSecurityNamePattern() {
    return ".*(" + String.join("|", SECURITY_KEYWORDS) + ").*";
}
```

**Why:** Using Cypher parameters (not string interpolation) is the project standard. Regex patterns are pre-built in Java and passed as `$param`. This avoids Cypher injection and keeps keyword lists version-controllable.

### Pattern 3: Enhanced Composite Score Formula

**What:** The enhanced composite score combines all 8 dimensions in a single Cypher SET. The structuralRiskScore is already computed — the enhanced formula takes it as a weighted component alongside the 4 new domain scores.

**Weight defaults (60% structural / 40% domain, all 8 weights sum to 1.0):**

| Dimension | Default Weight | Notes |
|-----------|---------------|-------|
| complexity (structural) | 0.24 | Was 0.4 in Phase 6; scaled by 0.6 |
| fanIn (structural) | 0.12 | Was 0.2; scaled by 0.6 |
| fanOut (structural) | 0.12 | Was 0.2; scaled by 0.6 |
| dbWrites (structural) | 0.12 | Was 0.2; scaled by 0.6 |
| domainCriticality | 0.10 | New; 40% / 4 domains |
| securitySensitivity | 0.10 | New |
| financialInvolvement | 0.10 | New |
| businessRuleDensity | 0.10 | New |

**Total: 0.24 + 0.12 + 0.12 + 0.12 + 0.10 + 0.10 + 0.10 + 0.10 = 1.00**

**Critical design note:** The structuralRiskScore is itself a weighted sum of log-normalized values — it is NOT bounded to [0,1]. The enhanced formula should NOT use structuralRiskScore as a black-box input, as that would double-weight the structural components unevenly. Instead, the enhanced formula recomputes all 8 dimensions from raw properties, using the new 8-weight distribution. This is the correct approach and mirrors how computeStructuralRiskScore() works.

```java
private void computeEnhancedRiskScore() {
    String cypher = """
        MATCH (c:JavaClass)
        SET c.enhancedRiskScore = (
            $wComplexity  * log(1.0 + coalesce(c.complexitySum, 0)) +
            $wFanIn       * log(1.0 + coalesce(c.fanIn, 0)) +
            $wFanOut      * log(1.0 + coalesce(c.fanOut, 0)) +
            $wDbWrites    * CASE WHEN coalesce(c.hasDbWrites, false) THEN 1.0 ELSE 0.0 END +
            $wDomainCrit  * coalesce(c.domainCriticality, 0.0) +
            $wSecurity    * coalesce(c.securitySensitivity, 0.0) +
            $wFinancial   * coalesce(c.financialInvolvement, 0.0) +
            $wRuleDensity * coalesce(c.businessRuleDensity, 0.0)
        )
        """;
    neo4jClient.query(cypher)
        .bindAll(Map.of(
            "wComplexity",  riskWeightConfig.getDomainComplexity(),
            "wFanIn",       riskWeightConfig.getDomainFanIn(),
            "wFanOut",      riskWeightConfig.getDomainFanOut(),
            "wDbWrites",    riskWeightConfig.getDomainDbWrites(),
            "wDomainCrit",  riskWeightConfig.getDomainCriticality(),
            "wSecurity",    riskWeightConfig.getSecuritySensitivity(),
            "wFinancial",   riskWeightConfig.getFinancialInvolvement(),
            "wRuleDensity", riskWeightConfig.getBusinessRuleDensity()))
        .run();
}
```

**application.yml extension:**
```yaml
esmp:
  risk:
    weight:
      # Structural weights (Phase 6 — unchanged, still used for structuralRiskScore)
      complexity: 0.4
      fan-in: 0.2
      fan-out: 0.2
      db-writes: 0.2
      # Domain-enhanced weights (Phase 7 — used for enhancedRiskScore, all 8 sum to 1.0)
      domain-complexity: 0.24
      domain-fan-in: 0.12
      domain-fan-out: 0.12
      domain-db-writes: 0.12
      domain-criticality: 0.10
      security-sensitivity: 0.10
      financial-involvement: 0.10
      business-rule-density: 0.10
```

**Important:** The Phase 6 structural weights (complexity=0.4 etc.) are PRESERVED unchanged. They continue to drive `structuralRiskScore`. The new "domain-" prefixed variants drive `enhancedRiskScore`. This avoids breaking Phase 6 behavior.

### Pattern 4: RiskWeightConfig Extension

Add 4 new fields with getters/setters matching existing pattern. Spring relaxed binding maps `domain-criticality` YAML key to `domainCriticality` field:

```java
// New fields in RiskWeightConfig:
private double domainComplexity  = 0.24;
private double domainFanIn       = 0.12;
private double domainFanOut      = 0.12;
private double domainDbWrites    = 0.12;
private double domainCriticality = 0.10;
private double securitySensitivity   = 0.10;
private double financialInvolvement  = 0.10;
private double businessRuleDensity   = 0.10;
```

### Pattern 5: API Record Extension

Java records are immutable — adding fields requires replacing the record declaration. Both `RiskHeatmapEntry` and `RiskDetailResponse` must be updated. All call sites (mappers in RiskService) update to pass the new fields:

```java
// RiskHeatmapEntry new fields (append to end of record components):
double domainCriticality,
double securitySensitivity,
double financialInvolvement,
double businessRuleDensity,
double enhancedRiskScore
```

**sortBy parameter in RiskController:**
```java
@GetMapping("/heatmap")
public ResponseEntity<List<RiskHeatmapEntry>> getHeatmap(
    @RequestParam(required = false) String module,
    @RequestParam(required = false) String packageName,
    @RequestParam(required = false) String stereotype,
    @RequestParam(defaultValue = "50") int limit,
    @RequestParam(defaultValue = "enhanced") String sortBy) {  // "structural" | "enhanced"
```

The `sortBy` parameter maps to `ORDER BY c.structuralRiskScore DESC` or `ORDER BY c.enhancedRiskScore DESC` in the Cypher query. Since this is string-interpolated into the query (property name), validate that `sortBy` is one of two known values and use a ternary in Java — do not pass as a parameter (Cypher cannot parameterize ORDER BY property names).

```java
// Safe: validate in Java, interpolate known constant
String orderByProp = "enhanced".equals(sortBy) ? "enhancedRiskScore" : "structuralRiskScore";
// Then use in query string: "ORDER BY c." + orderByProp + " DESC"
```

### Pattern 6: DomainRiskValidationQueryRegistry

New `@Component` extending `ValidationQueryRegistry` with protected constructor — identical pattern to `RiskValidationQueryRegistry` and `LexiconValidationQueryRegistry`:

```java
@Component
public class DomainRiskValidationQueryRegistry extends ValidationQueryRegistry {
  public DomainRiskValidationQueryRegistry() {
    super(List.of(
        // 1. DOMAIN_SCORES_POPULATED (ERROR)
        // JavaClass nodes where enhancedRiskScore IS NULL after full extraction
        new ValidationQuery("DOMAIN_SCORES_POPULATED", "...",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.enhancedRiskScore IS NULL
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.ERROR),

        // 2. HIGH_DOMAIN_RISK_NO_BUSINESS_TERMS (WARNING)
        // Classes with domainCriticality > 0 but no USES_TERM edges (score from stale data)
        new ValidationQuery("HIGH_DOMAIN_RISK_NO_BUSINESS_TERMS", "...",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.domainCriticality > 0.0
              AND NOT (c)-[:USES_TERM]->()
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING),

        // 3. SECURITY_FINANCIAL_COVERAGE (INFO/WARNING)
        // Total classes with non-zero security or financial scores (sanity check)
        new ValidationQuery("SECURITY_FINANCIAL_FLAGGED", "...",
            """
            OPTIONAL MATCH (c:JavaClass)
            WHERE c.securitySensitivity > 0.0 OR c.financialInvolvement > 0.0
            RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
            """,
            ValidationSeverity.WARNING)
    ));
  }
}
```

### Pattern 7: Pipeline Ordering in ExtractionService

`computeAndPersistRiskScores()` already called after `linkAllRelationships()`. Domain risk computation extends this same method — no pipeline ordering change needed. The internal call sequence becomes:

```java
public void computeAndPersistRiskScores() {
    // Phase 6 structural (unchanged)
    computeFanInOut();
    computeStructuralRiskScore();
    // Phase 7 domain (new)
    computeDomainCriticality();       // DRISK-01
    computeSecuritySensitivity();     // DRISK-02
    computeFinancialInvolvement();    // DRISK-03
    computeBusinessRuleDensity();     // DRISK-04
    computeEnhancedRiskScore();       // DRISK-05 (runs last — depends on all others)
}
```

### Anti-Patterns to Avoid

- **Java loop iteration over nodes:** Never fetch all JavaClass nodes into Java and compute scores in a loop. The Phase 6 pattern uses a single Cypher SET that updates all nodes atomically. Domain scoring follows the same rule.
- **Regex injection into Cypher string:** Security and financial keyword patterns must be passed as `$param` parameters, never string-interpolated into Cypher. Build the regex string in Java, pass as parameter.
- **ORDER BY parameterization:** Cypher does not support parameterized property names in ORDER BY. Validate `sortBy` in Java and use string interpolation only with a known safe constant (ternary between two hardcoded strings).
- **Modifying structuralRiskScore:** Phase 6 output must remain stable. Only write to the new properties (domainCriticality, securitySensitivity, financialInvolvement, businessRuleDensity, enhancedRiskScore).
- **Records with positional breaking:** When extending Java record types, adding to the END of the component list is the only safe approach — all callers pass fields positionally and must be updated.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Keyword matching | Custom string scanner in Java | Cypher `=~` with regex parameter | Single query updates all nodes; avoids N+1 fetches |
| Weight configuration | Hardcoded constants | RiskWeightConfig @ConfigurationProperties | Already established; Spring relaxed binding handles YAML key formatting |
| Graph traversal for term counting | Java traversal | Cypher `size([(c)-[:DEFINES_RULE]->(t) \| t])` pattern comprehension | Established in Phase 6 for fan-in/out; works identically for edge counting |
| Validation query discovery | Explicit wiring | @Component + List<ValidationQueryRegistry> injection | ValidationService auto-aggregates all registered beans — Phase 4 design |
| Test infrastructure | New test setup | Copy RiskServiceIntegrationTest pattern | Testcontainers setup (Neo4j + MySQL + Qdrant) already established |

---

## Common Pitfalls

### Pitfall 1: Cypher Regex Is Full-Match (Not Contains)

**What goes wrong:** `c.simpleName =~ 'auth'` does NOT match "AuthService". Neo4j `=~` is anchored — the entire string must match the pattern.

**Why it happens:** Developers coming from Java `String.matches()` (same behavior) or grep forget the anchoring. The existing codebase already uses `=~` correctly in DEFINES_RULE_COVERAGE: `'.*(Validator|Rule|Policy|Constraint|Calculator|Strategy).*'` — note the `.*` prefix and suffix.

**How to avoid:** Always wrap keyword patterns with `.*`: `'.*(' + keywords.join('|') + ').*'`. Test with `toLower()` to handle case: `toLower(c.simpleName) =~ '.*(auth|login|...).*'`.

**Warning signs:** Security/financial scores are all 0.0 even for classes named "AuthService" or "PaymentProcessor".

### Pitfall 2: annotations Property Is a List — Use ANY()

**What goes wrong:** `c.annotations =~ '.*Secured.*'` fails with a type error — annotations is a `List<String>`, not a String.

**Why it happens:** The annotations property on JavaClass stores FQNs as a list. Direct `=~` on a list is a type error in Cypher.

**How to avoid:** Use `ANY(a IN c.annotations WHERE toLower(a) =~ '.*secured.*')`. This is identical to how Phase 4 validation uses `ANY(label IN labels(c) WHERE label = 'Service')` — same pattern, different collection. Verified: ClassNode.annotations is `List<String>` stored as `@Property("annotations")`.

**Warning signs:** Cypher runtime error about type mismatch on the `annotations` property, or unexpected NPEs when annotations is null — use `coalesce(c.annotations, [])` defensively.

### Pitfall 3: USES_TERM Edges May Not Exist for All Classes

**What goes wrong:** `MATCH (c)-[:USES_TERM]->(t)` returns no rows for classes with zero USES_TERM edges, making the aggregation return null instead of 0.0.

**Why it happens:** MATCH requires at least one match. Classes that have no USES_TERM edges produce no rows, so `max(...)` aggregation returns null.

**How to avoid:** Use `OPTIONAL MATCH` + `coalesce(maxCrit, 0.0)`. This is the same pattern Phase 6 uses for `coalesce(c.fanIn, 0)`. Confirmed: the established computeFanInOut() already handles this via pattern comprehension `size([...])` which returns 0 for no matches — use the same approach for DEFINES_RULE counting.

**Warning signs:** domainCriticality is NULL for some classes in the graph after computation runs.

### Pitfall 4: Enhanced Score Formula Must Recompute Raw Dimensions, Not Use structuralRiskScore

**What goes wrong:** Using `structuralRiskScore` as an input to the enhanced formula results in triple-weighted structural components — structuralRiskScore already sums all 4 structural terms, and then multiplying by a weight multiplies them all again.

**Why it happens:** It seems natural to combine two pre-computed scores: `enhancedRiskScore = 0.6 * structuralRiskScore + 0.4 * domainScore`. But structuralRiskScore itself is a log-normalized sum — its scale is not bounded like the domain scores. The domain scores (0.0–1.0 range) and structural score (unbounded log sum) are not on the same scale.

**How to avoid:** Recompute all 8 dimensions from raw node properties in the enhanced formula Cypher query, using the new 8-weight set. The structural weights (domainComplexity, domainFanIn, etc.) are simply the old structural weights scaled to the 60% target.

**Warning signs:** Classes with high structural risk but zero domain flags have enhancedRiskScore dramatically larger than expected, while domain-critical but simple classes have unexpectedly low enhanced scores.

### Pitfall 5: Java Record Immutability — No Partial Extension

**What goes wrong:** Attempting to add a field to an existing Java record via subclassing or extension fails — records are final.

**Why it happens:** Java records are implicitly final and cannot be extended. Adding new components requires updating the record declaration and all call sites.

**How to avoid:** Update both `RiskHeatmapEntry` and `RiskDetailResponse` record declarations by appending 5 new fields. Update the two mapper methods in RiskService (`mapNodeToHeatmapEntry`, `mapNodeToDetailResponse`) to read the new properties from the Neo4j node and pass them. Update integration tests that construct or assert these records.

**Warning signs:** Compilation errors at mapper call sites after record update.

### Pitfall 6: packageName vs. sourceFilePath for Package Detection

**What goes wrong:** Using `c.sourceFilePath =~ '.*security.*'` catches test files and build artifacts. Using `c.packageName` is the correct signal.

**Why it happens:** Package-level security signals (classes in `com.example.security.*`) are better detected via `c.packageName` than `c.sourceFilePath`, which includes filesystem paths that may contain misleading segments.

**How to avoid:** Use `toLower(coalesce(c.packageName, '')) =~ '.*(security|auth|crypto|...).*'` for package-based detection. Already established: LinkingService uses `c.sourceFilePath CONTAINS '/src/test/'` specifically for test exclusion — a different purpose.

---

## Code Examples

### Domain Criticality via USES_TERM (DRISK-01)

```java
// Source: Phase 6 computeFanInOut pattern + USES_TERM graph structure from Phase 5
private void computeDomainCriticality() {
    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             size([(c)-[:USES_TERM]->(t:BusinessTerm) WHERE t.criticality = 'High' | t]) AS highCount
        SET c.domainCriticality = CASE WHEN highCount > 0 THEN 1.0 ELSE 0.0 END
        """;
    neo4jClient.query(cypher).run();
}
```

### Security Sensitivity Computation (DRISK-02)

```java
// Source: established Cypher =~ pattern + ANY() list check from Phase 4 validation
private void computeSecuritySensitivity() {
    String namePattern  = buildPattern(SECURITY_NAME_KEYWORDS);
    String annotPattern = buildPattern(SECURITY_ANNOTATION_KEYWORDS);
    String pkgPattern   = buildPattern(SECURITY_PKG_KEYWORDS);

    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             CASE WHEN toLower(coalesce(c.simpleName, '')) =~ $namePattern THEN 1 ELSE 0 END AS nameHit,
             CASE WHEN ANY(a IN coalesce(c.annotations, [])
                          WHERE toLower(a) =~ $annotPattern) THEN 1 ELSE 0 END AS annotHit,
             CASE WHEN toLower(coalesce(c.packageName, '')) =~ $pkgPattern THEN 1 ELSE 0 END AS pkgHit
        SET c.securitySensitivity = min(1.0,
             toFloat(nameHit) * $nameWeight +
             toFloat(annotHit) * $annotWeight +
             CASE WHEN nameHit = 1 AND annotHit = 1 THEN $bothBonus ELSE 0.0 END +
             toFloat(pkgHit) * $pkgBoost
        )
        """;
    neo4jClient.query(cypher)
        .bindAll(Map.of(
            "namePattern", namePattern,
            "annotPattern", annotPattern,
            "pkgPattern", pkgPattern,
            "nameWeight", NAME_HIT_WEIGHT,
            "annotWeight", ANNOT_HIT_WEIGHT,
            "bothBonus", BOTH_HIT_BONUS,
            "pkgBoost", PKG_HIT_BOOST))
        .run();
}

private static String buildPattern(List<String> keywords) {
    return ".*(" + String.join("|", keywords) + ").*";
}
```

### Financial Involvement with USES_TERM Boost (DRISK-03)

```java
// Combines heuristic multi-signal + graph traversal boost for USES_TERM BusinessTerms
private void computeFinancialInvolvement() {
    String namePattern  = buildPattern(FINANCIAL_NAME_KEYWORDS);
    String annotPattern = buildPattern(FINANCIAL_ANNOTATION_KEYWORDS);
    String pkgPattern   = buildPattern(FINANCIAL_PKG_KEYWORDS);
    String termPattern  = buildPattern(FINANCIAL_TERM_KEYWORDS);

    String cypher = """
        MATCH (c:JavaClass)
        WITH c,
             CASE WHEN toLower(coalesce(c.simpleName, '')) =~ $namePattern THEN 1 ELSE 0 END AS nameHit,
             CASE WHEN ANY(a IN coalesce(c.annotations, [])
                          WHERE toLower(a) =~ $annotPattern) THEN 1 ELSE 0 END AS annotHit,
             CASE WHEN toLower(coalesce(c.packageName, '')) =~ $pkgPattern THEN 1 ELSE 0 END AS pkgHit,
             CASE WHEN EXISTS {
               MATCH (c)-[:USES_TERM]->(t:BusinessTerm)
               WHERE toLower(coalesce(t.termId, '')) =~ $termPattern
                  OR toLower(coalesce(t.displayName, '')) =~ $termPattern
             } THEN 1 ELSE 0 END AS termHit
        SET c.financialInvolvement = min(1.0,
             toFloat(nameHit) * $nameWeight +
             toFloat(annotHit) * $annotWeight +
             CASE WHEN nameHit = 1 AND annotHit = 1 THEN $bothBonus ELSE 0.0 END +
             toFloat(pkgHit) * $pkgBoost +
             toFloat(termHit) * $termBoost
        )
        """;
    neo4jClient.query(cypher)
        .bindAll(Map.of(
            "namePattern", namePattern,
            "annotPattern", annotPattern,
            "pkgPattern", pkgPattern,
            "termPattern", termPattern,
            "nameWeight", NAME_HIT_WEIGHT,
            "annotWeight", ANNOT_HIT_WEIGHT,
            "bothBonus", BOTH_HIT_BONUS,
            "pkgBoost", PKG_HIT_BOOST,
            "termBoost", TERM_HIT_BOOST))
        .run();
}
```

### ClassNode New Properties

```java
// Append to ClassNode.java (// ---------- Phase 7: domain risk metrics ----------)
private double domainCriticality;
private double securitySensitivity;
private double financialInvolvement;
private double businessRuleDensity;
private double enhancedRiskScore;
// + corresponding getters and setters
```

### Neo4j Index for Enhanced Score

```java
// In Neo4jSchemaInitializer.run():
createConstraint(
    "java_class_enhanced_risk_score",
    "CREATE INDEX java_class_enhanced_risk_score IF NOT EXISTS"
        + " FOR (n:JavaClass) ON (n.enhancedRiskScore)");
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Binary security flag (boolean) | Graduated score (0.0–1.0) | Phase 7 decision | Enables proportional weight in composite formula |
| Structural risk only | Enhanced composite (structural + domain) | Phase 7 | More accurate migration prioritization for domain-critical classes |
| Single ORDER BY structuralRiskScore | sortBy parameter (structural vs enhanced) | Phase 7 | User can compare both views |

**No deprecated patterns:** The structuralRiskScore from Phase 6 is preserved unchanged. Phase 7 adds alongside, not replacing.

---

## Open Questions

1. **Medium criticality BusinessTerms**
   - What we know: BusinessTermNode.criticality stores "High" or "Low" (as per Phase 5 implementation)
   - What's unclear: If human curators add "Medium" criticality via the lexicon UI, the binary mapping (High=1.0, Low=0.0) would silently treat Medium as 0.0
   - Recommendation: Map "Medium" to 0.5 in the CASE expression for future-proofing: `CASE WHEN t.criticality = 'High' THEN 1.0 WHEN t.criticality = 'Medium' THEN 0.5 ELSE 0.0 END`

2. **Score stability on re-extraction**
   - What we know: Domain scores (security/financial) are based on class names and annotations which rarely change; structuralRiskScore is already re-computed on each extraction
   - What's unclear: If a class gets a new annotation between extractions, does the domain score update correctly?
   - Recommendation: All SET queries run unconditionally (no IF NOT EXISTS guard) — this is the correct approach, same as structuralRiskScore. Each extraction overwrites all scores.

3. **USES_TERM coverage for the target codebase**
   - What we know: USES_TERM edges exist for classes that are primary sources of business terms or DEPENDS_ON primary sources
   - What's unclear: Actual coverage rate — how many classes in a real legacy codebase will have USES_TERM edges?
   - Recommendation: The validation query DOMAIN_SCORES_POPULATED will reveal this. Classes with 0 USES_TERM edges will have domainCriticality=0.0 which is correct and expected — not all classes have domain-term relationships.

---

## Validation Architecture

> nyquist_validation is true in .planning/config.json — section included.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Testcontainers + Spring Boot Test |
| Config file | None — configured via @SpringBootTest + @Testcontainers annotations |
| Quick run command | `./gradlew test --tests "com.esmp.graph.application.DomainRiskServiceIntegrationTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| DRISK-01 | Domain criticality = 1.0 for class with High BusinessTerm via USES_TERM | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.domainCriticality_*"` | Wave 0 |
| DRISK-01 | Domain criticality = 0.0 for class with no USES_TERM edges | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.domainCriticality_zeroForNoTerms"` | Wave 0 |
| DRISK-01 | Domain criticality = 0.0 for class with only Low BusinessTerms | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.domainCriticality_zeroForLowTerms"` | Wave 0 |
| DRISK-02 | Security sensitivity > 0 for class named "AuthService" | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.securitySensitivity_nameHit"` | Wave 0 |
| DRISK-02 | Security sensitivity > 0 for class with @Secured annotation | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.securitySensitivity_annotHit"` | Wave 0 |
| DRISK-02 | Security sensitivity = 0 for plain utility class | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.securitySensitivity_zeroForPlain"` | Wave 0 |
| DRISK-03 | Financial involvement > 0 for class named "PaymentService" | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.financialInvolvement_nameHit"` | Wave 0 |
| DRISK-03 | Financial involvement boosted by financial USES_TERM edge | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.financialInvolvement_termBoost"` | Wave 0 |
| DRISK-04 | Business rule density = log(1 + count) of DEFINES_RULE edges | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.businessRuleDensity_logNormalized"` | Wave 0 |
| DRISK-04 | Business rule density = 0 for class with no DEFINES_RULE edges | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.businessRuleDensity_zeroForNoRules"` | Wave 0 |
| DRISK-05 | Enhanced risk score > structural risk for domain-critical class | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.enhancedScore_higherForDomainCritical"` | Wave 0 |
| DRISK-05 | Enhanced risk score computed for all JavaClass nodes (none NULL) | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.enhancedScore_notNullForAllClasses"` | Wave 0 |
| DRISK-05 | GET /api/risk/heatmap?sortBy=enhanced sorts by enhancedRiskScore | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.heatmap_sortByEnhanced"` | Wave 0 |
| DRISK-05 | GET /api/risk/heatmap response includes all domain score fields | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.heatmap_includesDomainFields"` | Wave 0 |
| DRISK-05 | GET /api/risk/class/{fqn} includes domain breakdown | integration | `./gradlew test --tests "*.DomainRiskServiceIntegrationTest.classDetail_includesDomainBreakdown"` | Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "com.esmp.graph.application.DomainRiskServiceIntegrationTest"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/com/esmp/graph/application/DomainRiskServiceIntegrationTest.java` — covers all DRISK-01 through DRISK-05 test cases listed above
- [ ] No additional framework install required — Testcontainers + JUnit 5 already present

---

## Sources

### Primary (HIGH confidence)

- Existing codebase — `RiskService.java`, `RiskWeightConfig.java`, `ClassNode.java`, `LinkingService.java`, `LexiconValidationQueryRegistry.java`, `RiskValidationQueryRegistry.java`, `Neo4jSchemaInitializer.java`, `ExtractionService.java` — direct code inspection, all patterns verified
- `BusinessTermNode.java` — confirms criticality is a String ("High"/"Low"), not an enum
- `RiskServiceIntegrationTest.java` — confirms Testcontainers + @SpringBootTest test pattern for this codebase
- `ValidationQueryRegistry.java` — confirms protected constructor pattern for extensibility

### Secondary (MEDIUM confidence)

- Neo4j Cypher documentation: `=~` is a full-match regex operator; `ANY(x IN list WHERE ...)` is the idiomatic list predicate — consistent with how existing codebase uses these patterns (DEFINES_RULE_COVERAGE query, SERVICE_HAS_DEPENDENCIES query)
- Java records are final and cannot be extended — any new fields require updating the record declaration and all construction sites

### Tertiary (LOW confidence)

- Weight defaults (0.24/0.12/0.12/0.12 for structural, 0.10 each for domain) — calculated from the 60%/40% user decision, mathematically sound but may need empirical tuning against real codebase data

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; all patterns verified against existing code
- Architecture: HIGH — all patterns are direct extensions of Phase 6 patterns, verified against working implementations
- Pitfalls: HIGH — identified from direct code inspection of existing patterns and established Cypher behavior
- Weight defaults: MEDIUM — mathematically correct per user constraint (60/40 split), but empirical tuning may be needed

**Research date:** 2026-03-05
**Valid until:** 2026-06-05 (stable domain — Neo4j Cypher patterns, Spring ConfigurationProperties, no fast-moving APIs)
