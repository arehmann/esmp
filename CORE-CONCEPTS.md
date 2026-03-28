# ESMP Core Concepts

> A deep dive into how ESMP works, why each component exists, and how they combine to make Vaadin 7 to Vaadin 24 migration safe, fast, and data-driven.

---

## Table of Contents

- [The Problem: Why Migration Is Hard](#the-problem-why-migration-is-hard)
- [The Solution: Knowledge-Driven Migration](#the-solution-knowledge-driven-migration)
- [Core Concept 1: AST Extraction — Turning Code Into Knowledge](#core-concept-1-ast-extraction--turning-code-into-knowledge)
  - [Why OpenRewrite?](#why-openrewrite)
  - [The 8 Visitors](#the-8-visitors)
  - [What Gets Extracted](#what-gets-extracted)
  - [Benefits for Migration](#benefits-for-migration)
- [Core Concept 2: The Code Knowledge Graph](#core-concept-2-the-code-knowledge-graph)
  - [9 Node Types](#9-node-types)
  - [10 Edge Types](#10-edge-types)
  - [Why a Graph Database?](#why-a-graph-database)
  - [Graph Queries That Power Migration](#graph-queries-that-power-migration)
- [Core Concept 3: Multi-Dimensional Risk Scoring](#core-concept-3-multi-dimensional-risk-scoring)
  - [Layer 1: Structural Risk](#layer-1-structural-risk)
  - [Layer 2: Domain-Aware Risk](#layer-2-domain-aware-risk)
  - [How Risk Drives Migration Order](#how-risk-drives-migration-order)
- [Core Concept 4: Domain Lexicon — Business Terms From Code](#core-concept-4-domain-lexicon--business-terms-from-code)
  - [How Terms Are Extracted](#how-terms-are-extracted)
  - [Why Business Terms Matter for Migration](#why-business-terms-matter-for-migration)
- [Core Concept 5: Vector Embeddings — Semantic Code Understanding](#core-concept-5-vector-embeddings--semantic-code-understanding)
  - [Chunking Strategy](#chunking-strategy)
  - [Graph-Enriched Embeddings](#graph-enriched-embeddings)
  - [Semantic Search vs Keyword Search](#semantic-search-vs-keyword-search)
- [Core Concept 6: RAG Pipeline — AI Context Assembly](#core-concept-6-rag-pipeline--ai-context-assembly)
  - [The 3-Layer Retrieval Strategy](#the-3-layer-retrieval-strategy)
  - [Weighted Re-Ranking](#weighted-re-ranking)
  - [Why This Produces Better AI Output](#why-this-produces-better-ai-output)
- [Core Concept 7: Migration Scheduling — The Right Order](#core-concept-7-migration-scheduling--the-right-order)
  - [The 4-Dimension Scoring Model](#the-4-dimension-scoring-model)
  - [Wave Assignment via Topological Sort](#wave-assignment-via-topological-sort)
  - [Dynamic Risk Evolution](#dynamic-risk-evolution)
- [Core Concept 8: MCP Server — AI Assistant Integration](#core-concept-8-mcp-server--ai-assistant-integration)
  - [What Is MCP?](#what-is-mcp)
  - [The 10 Tools](#the-10-tools)
  - [How AI Assistants Use ESMP](#how-ai-assistants-use-esmp)
  - [The Migration Loop](#the-migration-loop)
- [Core Concept 9: Docker Deployment & Enterprise Scale](#core-concept-9-docker-deployment--enterprise-scale)
  - [One-Command Deployment](#one-command-deployment)
  - [Source Access Strategies](#source-access-strategies)
  - [Parallel Extraction](#parallel-extraction)
  - [SSE Progress Streaming](#sse-progress-streaming)
- [Core Concept 10: Migration Engine — Automated OpenRewrite Recipes](#core-concept-10-migration-engine--automated-openrewrite-recipes)
  - [The 3-Stage Pipeline](#the-3-stage-pipeline)
  - [Automation Classification](#automation-classification)
  - [Per-Class Migration Scores](#per-class-migration-scores)
  - [Safety Model](#safety-model)
- [Core Concept 11: Recipe Book & Transitive Detection](#core-concept-11-recipe-book--transitive-detection)
  - [External Recipe Book](#external-recipe-book)
  - [Transitive Detection](#transitive-detection)
  - [Enrichment Feedback Loop](#enrichment-feedback-loop)
  - [Coverage Metrics](#coverage-metrics)
  - [Recipe Book Management API](#recipe-book-management-api)
- [How Everything Connects](#how-everything-connects)
- [Without ESMP vs With ESMP](#without-esmp-vs-with-esmp)
- [Practical Guide: Migrating an Enterprise Vaadin 7 Project to Vaadin 24/25](#practical-guide-migrating-an-enterprise-vaadin-7-project-to-vaadin-2425)
  - [Phase 1: Deploy ESMP and Analyze Your Codebase](#phase-1-deploy-esmp-and-analyze-your-codebase)
  - [Phase 2: Plan the Migration](#phase-2-plan-the-migration)
  - [Phase 3: Migrate Module by Module](#phase-3-migrate-module-by-module)
  - [Phase 4: Wave by Wave — Systematic Rollout](#phase-4-wave-by-wave--systematic-rollout)
  - [Phase 5: Continuous Validation](#phase-5-continuous-validation)
  - [Vaadin 7 to Vaadin 24/25 Pattern Mapping Reference](#vaadin-7-to-vaadin-2425-pattern-mapping-reference)
  - [Tips for Success](#tips-for-success)

---

## The Problem: Why Migration Is Hard

Migrating a large enterprise codebase from Vaadin 7 to Vaadin 24 is one of the riskiest technical projects an organization can undertake. Here's why:

**The APIs are fundamentally different.** Vaadin 7 uses server-side component trees (`com.vaadin.ui.*`), `Navigator` for routing, `BeanItemContainer` for data binding, and `Property`/`Item` APIs. Vaadin 24 uses Flow (`com.vaadin.flow.component.*`), `@Route` annotations, `DataProvider`, and `Binder<T>`. There's no mechanical search-and-replace — every class needs to be rewritten with understanding of what it does.

**Classes are deeply entangled.** A single View class might depend on 3 services, 2 entities, 4 utility classes, and be navigated to from 5 other views. Change it without understanding all these connections and something else breaks silently.

**Business logic is buried in UI code.** In many Vaadin 7 codebases, business rules (discount calculations, validation logic, authorization checks) are woven directly into view classes rather than cleanly separated. Migration must preserve this logic exactly.

**Nobody knows the safe order.** Which module do you migrate first? The one with the fewest dependencies? The simplest? The one the team knows best? Without data, this is a guess — and a wrong guess means rework.

**The codebase is too large for one person to understand.** Enterprise codebases have hundreds or thousands of classes. No single developer has a mental model of all relationships, and reading every file manually takes months.

---

## The Solution: Knowledge-Driven Migration

ESMP's approach: **analyze everything first, then migrate with full knowledge.**

```
  Traditional Migration               ESMP-Driven Migration
  =====================               =====================

  1. Read a file                       1. Parse ALL files (AST extraction)
  2. Guess what depends on it          2. Build complete relationship graph
  3. Rewrite by hand                   3. Score risk for every class
  4. Hope nothing breaks               4. Schedule migration order by risk
  5. Find bugs in QA (weeks later)     5. Give AI full context per class
  6. Repeat for next file              6. Validate after every change
                                       7. Repeat with shrinking risk
```

The key insight: **migration is an information problem.** The code changes themselves are often straightforward once you understand what a class does, what depends on it, and what patterns it uses. ESMP solves the information problem so humans (or AI) can focus on the actual rewriting.

---

## Core Concept 1: AST Extraction — Turning Code Into Knowledge

### Why OpenRewrite?

ESMP uses [OpenRewrite](https://docs.openrewrite.org/) as its AST parsing engine. OpenRewrite produces a **Lossless Semantic Tree (LST)** — not just the structure of the code, but type-resolved information that preserves formatting, comments, and whitespace.

Why this matters:

| Parser | What It Knows | Limitation |
|--------|--------------|------------|
| **Regex/grep** | Text patterns | Can't distinguish `import com.vaadin.ui.Table` from a comment mentioning Table |
| **JavaParser** | Syntax structure | Doesn't resolve types — can't tell if `Table` is `com.vaadin.ui.Table` or `java.sql.Table` |
| **OpenRewrite LST** | Full type resolution | Knows that `Table` in this context is `com.vaadin.ui.Table` — a Vaadin 7 component |

Type resolution is critical for Vaadin detection. When the VaadinPatternVisitor sees `extends CustomComponent`, it needs to know this is `com.vaadin.ui.CustomComponent` (Vaadin 7) not some other `CustomComponent` in the codebase.

### The 8 Visitors

When ESMP parses a `.java` file, it runs 8 specialized visitors over the AST. Each visitor extracts a different dimension of understanding:

```
  YourJavaFile.java
       │
       ▼ OpenRewrite parses to lossless AST
       │
       ├── 1. ClassMetadataVisitor
       │      What: Classes, methods, fields, annotations, stereotypes
       │      Why:  The foundation — you need to know what exists before
       │            you can analyze relationships
       │
       ├── 2. CallGraphVisitor
       │      What: Method-to-method CALLS edges
       │      Why:  Know which methods invoke which — if you change
       │            calculateTotal(), who calls it?
       │
       ├── 3. DependencyVisitor
       │      What: Class-to-class DEPENDS_ON edges (imports, field types, etc.)
       │      Why:  Know which classes are coupled — changing OrderService
       │            affects every class that depends on it
       │
       ├── 4. VaadinPatternVisitor
       │      What: Vaadin 7 views, components, data bindings, BINDS_TO edges
       │      Why:  The migration target — know exactly which classes have
       │            Vaadin 7 patterns and what they bind to
       │
       ├── 5. JpaPatternVisitor
       │      What: @Query annotations, @Entity mappings, QUERIES/MAPS_TO_TABLE edges
       │      Why:  Database access patterns — these must be preserved exactly
       │            during migration (wrong query = data corruption)
       │
       ├── 6. ComplexityVisitor
       │      What: Cyclomatic complexity per method, DB write detection
       │      Why:  Risk signal — high complexity = harder to migrate safely,
       │            DB writes = extra risk if migration changes behavior
       │
       ├── 7. LexiconVisitor
       │      What: Business terms extracted from naming conventions
       │      Why:  Domain intelligence — know which code handles invoices,
       │            payments, authentication (high-impact areas)
       │
       └── 8. MigrationPatternVisitor
              What: Vaadin 7 type usages with automation classification
              Why:  Migration automation — catalogs every Vaadin 7 type,
                    maps to Vaadin 24 equivalents, classifies as YES/PARTIAL/NO
                    for OpenRewrite recipe generation (94-rule recipe book)
```

**Important design principle:** Each visitor is **stateless per file** and writes to an `ExtractionAccumulator` (an in-memory buffer). This means:
- Visitors can run in parallel (each batch gets its own visitor instances)
- No shared mutable state between files
- Results are merged after all files are processed

### What Gets Extracted

After running all 8 visitors across your codebase, ESMP has:

**9 Node Types in Neo4j:**

| Node | What It Represents | Example |
|------|--------------------|---------|
| `JavaClass` | A class, interface, or enum | `com.acme.billing.InvoiceService` |
| `MethodNode` | A method within a class | `calculateTotal(Order)` |
| `FieldNode` | A field within a class | `private final OrderRepository repo` |
| `AnnotationType` | An annotation used in code | `@Service`, `@Entity`, `@Query` |
| `JavaPackage` | A Java package | `com.acme.billing` |
| `Module` | A module (3rd package segment) | `billing` |
| `DBTable` | A database table (from `@Table`) | `invoices` |
| `BusinessTerm` | A domain concept from naming | `Invoice`, `Payment` |
| `MigrationAction` | A Vaadin 7 type migration action | `Table` -> `Grid` (YES automation) |

**10 Edge Types:**

| Edge | What It Means | Example |
|------|---------------|---------|
| `CALLS` | Method A calls Method B | `processOrder()` → `validate()` |
| `EXTENDS` | Class A extends Class B | `AdminUser` → `BaseUser` |
| `IMPLEMENTS` | Class implements Interface | `InvoiceService` → `Billable` |
| `DEPENDS_ON` | Class A uses Class B | `OrderService` → `OrderRepo` |
| `BINDS_TO` | Vaadin binding relationship | `OrderForm` → `Order` |
| `QUERIES` | JPA `@Query` reference | `UserRepo` → `User` |
| `MAPS_TO_TABLE` | ORM table mapping | `User` → `users` |
| `USES_TERM` | Class uses business term | `InvoiceService` → `Invoice` |
| `DEFINES_RULE` | Class defines business rule | `PriceCalculator` → `Price` |
| `HAS_MIGRATION_ACTION` | Class has a migration action | `OrderView` → `Table->Grid` |

### Benefits for Migration

**1. You know exactly WHAT needs migrating**

Without ESMP, you'd `grep` for `com.vaadin.ui` and get a list of files. With ESMP, the VaadinPatternVisitor tells you:
- Which classes are Vaadin 7 **views** (extend `UI`, `CustomComponent`, use `Navigator`)
- Which classes use Vaadin 7 **data binding** (`BeanItemContainer`, `Property`, `FieldGroup`)
- Which classes have Vaadin 7 **components** (`Table`, `DateField`, `ComboBox`)
- What each view **binds to** (BINDS_TO edges → which entity/DTO classes)

This means you know not just "file X has Vaadin code" but "file X is a form that binds to the Order entity using BeanFieldGroup — in Vaadin 24 this becomes `Binder<Order>`."

**2. You know what BREAKS if you change something**

The DependencyVisitor + CallGraphVisitor build a complete dependency graph. Before you touch `OrderView`, you can ask:
- **Who calls me?** (fan-in) → "3 other views navigate to OrderView"
- **Who do I call?** (fan-out) → "OrderService.findAll(), DateFormatter.format()"
- **What's my full cone?** → "18 classes are transitively reachable from OrderView"

Without this, you migrate `OrderView`, break `DashboardView` which navigates to it, and don't find out until runtime.

**3. You can prioritize what to migrate FIRST**

The ComplexityVisitor computes cyclomatic complexity per method. Combined with dependency counts and domain analysis, ESMP produces a **risk score (0.0–1.0)** for every class:

```
  Low risk (0.1):   ConfigHelper     → 2 methods, CC=2, no dependencies, no DB writes
  Medium risk (0.5): OrderView       → 8 methods, CC=8, 5 dependencies, data binding
  High risk (0.8):   PaymentService  → 15 methods, CC=15, DB writes, financial terms
```

You migrate ConfigHelper first (safe, quick win), PaymentService last (needs careful review).

**4. AI gets the context it needs to write correct code**

When you ask AI to "migrate OrderView to Vaadin 24", without ESMP it only sees the file you paste. With ESMP:

- The **RAG pipeline** uses the graph to find the 25 most relevant code chunks — not by keyword matching but by **graph proximity** (what's 1 hop away? 2 hops?)
- The AI sees that `OrderView` binds to `Order` entity, calls `OrderService`, and is navigated to from `DashboardView`
- The AI knows `OrderView` has CC=8 (moderate complexity) and uses `Table` + `BeanItemContainer` (specific Vaadin 7 patterns with known Vaadin 24 equivalents: `Grid` + `DataProvider`)

Without this context, AI hallucinates incorrect imports, misses callers that need updating, and produces code that compiles but breaks the application.

**5. Business terms protect what matters most**

A class named `InvoiceCalculator` contains the term "Invoice" — a **high-criticality** business concept. ESMP flags this automatically. When the scheduling algorithm runs, classes touching high-criticality terms get higher risk scores and are scheduled for later waves with more careful review.

Without this, a developer might casually refactor `InvoiceCalculator` during migration, accidentally changing discount rounding logic, and causing billing errors in production.

**6. The graph enables incremental validation**

After you migrate each class, ESMP re-indexes it and runs 47 validation queries:
- Are all DEPENDS_ON edges still valid? (no broken references)
- Do all CALLS edges resolve? (no missing methods)
- Are stereotypes consistent? (Repository classes still have @Repository)

This catches issues **immediately** — not days later during integration testing.

---

## Core Concept 2: The Code Knowledge Graph

### 9 Node Types

Every entity extracted from your codebase becomes a node in Neo4j. Together they form a complete structural model of your application.

```
  JavaClass ────────── "The classes in your code"
       │                  Every .java class, interface, enum
       │                  Properties: fqn, simpleName, packageName, stereotype,
       │                  isAbstract, isInterface, vaadin7Detected, riskScores...
       │
       ├── MethodNode ── "The methods within classes"
       │                  Signature, return type, parameters, cyclomaticComplexity
       │
       ├── FieldNode ─── "The fields within classes"
       │                  Name, type, annotations, access modifier
       │
       ├── AnnotationType "The annotations used in code"
       │                  @Service, @Repository, @Entity, @Query, @Route...
       │
       ├── JavaPackage ── "Package hierarchy"
       │                  com.acme.billing, com.acme.auth...
       │
       ├── Module ─────── "Logical modules (3rd package segment)"
       │                  billing, auth, common, ui...
       │
       ├── DBTable ────── "Database tables from @Table/@Entity"
       │                  invoices, users, orders...
       │
       ├── BusinessTerm ─ "Domain vocabulary from naming"
       │                  Invoice, Payment, Order, Account...
       │
       └── MigrationAction "Vaadin 7 → 24 type migration action"
                          sourceType, targetType, automation (YES/PARTIAL/NO)
```

### 10 Edge Types

Edges are where the real value lives. They encode **relationships** that are invisible when reading files in isolation.

```
  STRUCTURAL EDGES (from code structure)
  ──────────────────────────────────────
  EXTENDS        Class A extends Class B
  IMPLEMENTS     Class A implements Interface B
  DEPENDS_ON     Class A imports/uses Class B
  CALLS          Method A invokes Method B

  VAADIN-SPECIFIC EDGES (from VaadinPatternVisitor)
  ──────────────────────────────────────
  BINDS_TO       A Vaadin view/form binds to a data class
                 (OrderForm BINDS_TO Order)

  DATA ACCESS EDGES (from JpaPatternVisitor)
  ──────────────────────────────────────
  QUERIES        A repository's @Query references an entity
  MAPS_TO_TABLE  An @Entity maps to a database table

  DOMAIN EDGES (from LexiconVisitor + LinkingService)
  ──────────────────────────────────────
  USES_TERM      A class uses a business term
  DEFINES_RULE   A class defines a business rule
                 (Validator/Calculator/Policy/Strategy patterns)

  MIGRATION EDGES (from MigrationPatternVisitor)
  ──────────────────────────────────────
  HAS_MIGRATION_ACTION  A class has a Vaadin 7 type migration action
                        (OrderView HAS_MIGRATION_ACTION Table→Grid)
```

### Why a Graph Database?

Migration questions are inherently graph questions:

| Question | Graph Query | SQL Equivalent |
|----------|------------|----------------|
| "What depends on InvoiceService?" | 1-hop DEPENDS_ON traversal | 3 table joins |
| "What's the full impact of changing Order?" | 10-hop multi-edge traversal | Impossible without recursive CTEs |
| "Which modules are most coupled?" | Aggregate DEPENDS_ON across modules | Multiple subqueries |
| "Find all Vaadin views that bind to financial entities" | Pattern: `(:VaadinView)-[:BINDS_TO]->(:Entity)-[:USES_TERM]->(:BusinessTerm {criticality:'High'})` | 5+ table joins |

Neo4j answers these in milliseconds with simple Cypher queries. The same questions in a relational database would require complex recursive queries or be impossible without pre-computing all transitive closures.

### Graph Queries That Power Migration

**Dependency Cone** — "What does this class transitively depend on?"

```cypher
MATCH path = (c:JavaClass {fqn: $fqn})-[:DEPENDS_ON|EXTENDS|IMPLEMENTS|CALLS
    |BINDS_TO|QUERIES|MAPS_TO_TABLE*1..10]->(target)
RETURN DISTINCT target.fqn, labels(target), length(path) AS hops
```

This single query traverses ALL 7 relationship types up to 10 hops deep. It tells you: "If you change InvoiceService, here are ALL 45 classes that might be affected, sorted by how many hops away they are."

**Fan-In / Fan-Out** — "How coupled is this class?"

```cypher
// Fan-in: who depends on me?
MATCH (other)-[:DEPENDS_ON]->(c:JavaClass {fqn: $fqn}) RETURN count(other)

// Fan-out: who do I depend on?
MATCH (c:JavaClass {fqn: $fqn})-[:DEPENDS_ON]->(other) RETURN count(other)
```

High fan-in = many things break if migration goes wrong. High fan-out = many things must work correctly first.

---

## Core Concept 3: Multi-Dimensional Risk Scoring

### Layer 1: Structural Risk

Computed from code structure alone (no domain knowledge needed):

```
  structuralRiskScore = complexity * 0.4    (cyclomatic complexity)
                      + fanIn     * 0.2    (how many classes depend on this)
                      + fanOut    * 0.2    (how many classes this depends on)
                      + dbWrites  * 0.2    (does this class write to DB?)

  Range: 0.0 (trivial) to 1.0 (extremely risky)
```

**Why each dimension:**

- **Complexity (40%):** A method with CC=15 has 15 independent execution paths. Each path must be preserved during migration. Higher complexity = more ways to introduce bugs.

- **Fan-In (20%):** If 20 other classes depend on `OrderService`, a migration bug in OrderService breaks 20 classes. High fan-in means high blast radius.

- **Fan-Out (20%):** If `OrderView` depends on 10 other classes, all 10 must be compatible after migration. High fan-out means more integration points to verify.

- **DB Writes (20%):** Classes that write to the database are inherently riskier to migrate. A subtle change in behavior could corrupt data. This is a binary boost — the class either has DB writes (detected via `@Modifying`, `persist()`, `save()`, etc.) or it doesn't.

### Layer 2: Domain-Aware Risk

Adds business intelligence on top of structural risk:

```
  enhancedRiskScore = domainComplexity      * 0.24    (structural base, rescaled)
                    + domainFanIn           * 0.12
                    + domainFanOut          * 0.12
                    + domainDbWrites        * 0.12
                    + domainCriticality     * 0.10    (business term criticality)
                    + securitySensitivity   * 0.10    (auth/password/token patterns)
                    + financialInvolvement  * 0.10    (payment/invoice/billing)
                    + businessRuleDensity   * 0.10    (DEFINES_RULE count)

  Range: 0.0 to 1.0
```

**New dimensions beyond structural:**

- **Domain Criticality (10%):** Does this class use HIGH criticality business terms? If `InvoiceService` USES_TERM "Invoice" (criticality=High), it scores 1.0 here. This means business-critical code gets extra protection during migration.

- **Security Sensitivity (10%):** Does the class name contain "Auth", "Security", "Token"? Does it have `@PreAuthorize` annotations? Is it in a `security` package? Graduated scoring catches security-relevant code that must be migrated with extreme care.

- **Financial Involvement (10%):** Similar heuristics for financial code — "Payment", "Invoice", "Billing" patterns in names, packages, and linked business terms.

- **Business Rule Density (10%):** How many DEFINES_RULE edges does this class have? Classes that define business rules (Validators, Calculators, Policies) need the most careful migration because changing their behavior changes business outcomes.

### How Risk Drives Migration Order

```
  Without Risk Scoring               With Risk Scoring
  ====================               =================

  "Let's migrate billing first,      "Schedule says billing is Wave 3
   it's the module I know best"       (high risk). Start with utils (Wave 1)
                                      — 12 classes, avg risk 0.1, no DB writes.
  → billing has 45 classes,            Quick win, builds confidence."
    avg risk 0.72, DB writes,
    financial terms                   → First success in 2 days, not 2 weeks.
                                        Team learns the pattern on safe code.
  → 3 weeks in, 2 production           Then scale to riskier modules.
    bugs in invoice calculation
```

---

## Core Concept 4: Domain Lexicon — Business Terms From Code

### How Terms Are Extracted

The LexiconVisitor extracts business terms from three sources:

**1. CamelCase class names** (with stop-suffix filtering):

```
  InvoiceCalculatorService
  └───┬───┘└────┬────┘└──┬──┘
      │         │        └── "Service" (stop-suffix, filtered out)
      │         └── "Calculator" (kept)
      └── "Invoice" (kept)
```

28 stop-suffixes are filtered: Service, Controller, Repository, Impl, Helper, Utils, Factory, Builder, Adapter, Manager, Handler, Processor, Provider, Converter, Mapper, Resolver, Validator, Listener, Observer, Interceptor, Filter, Transformer, Formatter, Serializer, Deserializer, Wrapper, Decorator, Proxy.

**2. Enum types and constants:**

```java
  enum PaymentStatus { PENDING, PAID, REFUNDED, CANCELLED }
  // → Terms: "Payment", "Status"
  // → Constants also provide domain vocabulary
```

**3. Class-level Javadoc:**

```java
  /** Handles monthly invoice generation and distribution to customers */
  class InvoiceGenerator { ... }
  // → Additional context for term definitions
```

### Why Business Terms Matter for Migration

**Term criticality drives risk scoring.** When a class USES_TERM "Invoice" (criticality=High), its domain risk score increases. This means the scheduling algorithm pushes it to later waves where it gets more careful review.

**Terms identify business rule classes.** The LinkingService creates DEFINES_RULE edges for classes matching Validator/Rule/Policy/Constraint/Calculator/Strategy patterns. These classes contain business logic that must be preserved exactly — changing a rounding calculation in `InvoiceCalculator` could cause billing errors.

**Curated terms are protected.** When a domain expert marks a term as "curated" (updates the definition, sets criticality), re-running extraction will NOT overwrite those changes. The `MERGE` query uses `ON CREATE SET` / `ON MATCH SET` with a curated guard to protect human curation.

**Terms provide AI context.** When the MCP tool `getMigrationContext` is called, it includes all business terms linked to the focal class. This tells the AI: "This class is involved with Invoice, Payment, and Discount — these are high-criticality business concepts. Preserve all calculation logic exactly."

---

## Core Concept 5: Vector Embeddings — Semantic Code Understanding

### Chunking Strategy

Each class is split into multiple chunks for embedding:

```
  InvoiceService.java
       │
       ├── CLASS_HEADER chunk
       │     Class declaration + fields + annotations
       │     "public class InvoiceService { private final InvoiceRepository repo;
       │      private final PriceCalculator calc; ... }"
       │
       ├── METHOD chunk: calculateTotal()
       │     Full method body
       │     "public BigDecimal calculateTotal(Order order) { ... }"
       │
       ├── METHOD chunk: applyDiscount()
       │     "public BigDecimal applyDiscount(BigDecimal total, String code) { ... }"
       │
       └── METHOD chunk: validate()
             "public ValidationResult validate(Invoice invoice) { ... }"
```

Why split into chunks rather than embed whole files?
- Whole files exceed embedding model context limits
- Methods are the natural unit of migration — you migrate one method at a time
- Search results are more precise — "find the discount calculation" returns the specific method, not the entire 500-line class

### Graph-Enriched Embeddings

Each chunk is enriched with metadata from the knowledge graph before embedding:

```
  METHOD chunk: calculateTotal()
  ├── Code text: "public BigDecimal calculateTotal(Order order) { ... }"
  ├── callers: ["OrderView.buildLayout()", "BatchProcessor.run()"]
  ├── callees: ["repo.findById()", "calc.computeSubtotal()"]
  ├── dependencies: ["InvoiceRepository", "PriceCalculator", "Order"]
  ├── implementors: []
  ├── domainTerms: [{"Invoice", "High"}, {"Order", "High"}]
  ├── enhancedRiskScore: 0.72
  ├── vaadin7Detected: false
  ├── module: "billing"
  └── stereotype: "Service"
```

This enrichment means the vector embedding captures not just what the code says, but what it connects to and how important it is. A search for "payment calculation" will rank a method that USES_TERM "Payment" higher than one that just happens to contain the word "payment" in a comment.

### Semantic Search vs Keyword Search

| Traditional Search | ESMP Vector Search |
|-------------------|-------------------|
| `grep "calculate total"` finds exact text matches | `"compute invoice sum"` finds `calculateTotal()` by meaning |
| Can't understand synonyms | Understands that "compute" ≈ "calculate", "sum" ≈ "total" |
| No ranking beyond line number | Ranked by cosine similarity (0.0–1.0) |
| No context about relationships | Each result includes callers, callees, risk, domain terms |
| Returns lines of text | Returns structured chunks with metadata |

---

## Core Concept 6: RAG Pipeline — AI Context Assembly

### The 3-Layer Retrieval Strategy

When you ask "help me migrate InvoiceService", ESMP doesn't just search for similar code. It uses a 3-layer strategy:

```
  Query: "InvoiceService"
       │
       ▼
  Layer 1: RESOLVE focal class
       Is "InvoiceService" a fully-qualified name? → FQN match
       Is it a simple class name? → Simple name lookup (may disambiguate)
       Is it natural language? → Embed and vector search
       │
       ▼
  Layer 2: EXPAND dependency cone (Neo4j graph traversal)
       Starting from com.acme.billing.InvoiceService:
       - 1 hop: InvoiceRepository, PriceCalculator, Order
       - 2 hops: Invoice entity, DBTable "invoices"
       - ...up to 10 hops
       Result: 45 reachable classes with hop distances
       │
       ▼
  Layer 3: VECTOR SEARCH within cone
       Search ONLY chunks belonging to classes in the cone
       (not the entire codebase — focused retrieval)
       Result: 50 candidate chunks
       │
       ▼
  Layer 4: WEIGHTED RE-RANKING
       Re-rank candidates using 3 signals
       Return top-K chunks sorted by finalScore
```

### Weighted Re-Ranking

Each candidate chunk gets a composite score:

```
  finalScore = vectorSimilarity * 0.40    (how similar is the text?)
             + graphProximity   * 0.35    (how close in the dependency graph?)
             + riskScore        * 0.25    (how risky is this code?)
```

**Why this combination?**

- **Vector similarity (40%):** The code text should be semantically relevant to what you're migrating.
- **Graph proximity (35%):** Code that's 1 hop away in the graph is more relevant than code 8 hops away, even if the text is similar. If you're migrating InvoiceService, the InvoiceRepository (1 hop, DEPENDS_ON) is more important than some utility class that happens to mention invoices (8 hops, unrelated).
- **Risk score (25%):** Riskier code is more important to include in the context. If InvoiceCalculator (risk 0.78) and InvoiceDTO (risk 0.05) are both in the cone, the AI needs to see the calculator more — that's where migration bugs will happen.

### Why This Produces Better AI Output

```
  Without RAG                          With RAG
  ===========                          ========

  Human pastes InvoiceService.java     AI receives:
  AI sees: 1 file, 200 lines          - InvoiceService source code
                                       - 25 most relevant connected chunks
  AI's knowledge:                      - InvoiceRepository methods
  - "This class has some methods"      - Order entity fields
  - "It mentions Order somewhere"      - PriceCalculator logic
                                       - All Vaadin 7 patterns detected
  AI produces:                         - Business terms: Invoice (High), Order (High)
  - Generic Vaadin 24 code             - Risk breakdown: CC=12, financial=0.8
  - Missing OrderService integration
  - Wrong method signatures            AI produces:
  - Broken navigation                  - Correct Vaadin 24 code
                                       - Proper OrderService injection
                                       - Matching method signatures
                                       - Updated navigation with @Route
                                       - Warning about high financial involvement
```

---

## Core Concept 7: Migration Scheduling — The Right Order

### The 4-Dimension Scoring Model

```
  Module Score = risk       * 0.35   (avg enhanced risk of all classes in module)
               + dependency * 0.25   (how many other modules depend on this one)
               + frequency  * 0.20   (how often has this module changed in git?)
               + complexity * 0.20   (avg cyclomatic complexity, log-normalized)
```

**Risk (35%):** The primary signal. Modules with high average enhanced risk scores are scheduled for later waves.

**Dependency (25%):** If 5 other modules depend on `billing`, migrating billing incorrectly breaks 5 modules. High-dependency modules go later (after their dependents have been safely migrated).

**Frequency (20%):** Modules that change often in git (last 180 days) are more likely to have active development — migrating them introduces merge conflicts and coordination overhead. Stable modules are safer to migrate.

**Complexity (20%):** Higher average cyclomatic complexity means more execution paths, more edge cases, and more opportunities for migration bugs.

### Wave Assignment via Topological Sort

ESMP uses Kahn's algorithm (BFS topological sort) to assign modules to waves:

```
  Step 1: Build module dependency graph
  Step 2: Find modules with no incoming dependencies → Wave 1
  Step 3: Remove Wave 1 modules and their edges
  Step 4: Find new modules with no incoming dependencies → Wave 2
  Step 5: Repeat until all modules assigned
  Step 6: Circular dependencies → Final wave (SCC detection)
  Step 7: Within each wave, sort by module score (lowest first)

  Result:
  +--------+----------+-------+---------+
  | Wave 1 | Wave 2   | Wave 3| Wave 4  |
  | (safe) | (moderate)|(risky)|(cycles) |
  |        |          |       |         |
  | utils  | service  |billing| auth<>  |
  | config | data     |  ui   | payment |
  | common | report   |       |         |
  +--------+----------+-------+---------+

  Migrate left to right.
  Lower score within wave = migrate first.
```

**Key property:** Every module's dependencies are guaranteed to be in **earlier waves**. When you migrate `service` (Wave 2), `utils` and `config` (Wave 1) are already migrated and verified.

### Dynamic Risk Evolution

As you migrate modules, risk scores change:

```
  After migrating Wave 1 (utils, config, common):
  ─────────────────────────────────────────────────
  billing risk: 0.780 → 0.720   (dependency 'utils' is now safe)
  ui risk:      0.650 → 0.590   (dependency 'common' is now safe)

  After migrating Wave 2 (service, data):
  ─────────────────────────────────────────────────
  billing risk: 0.720 → 0.650   ('service' dependency now safe too)
  ui risk:      0.590 → 0.510   ('data' dependency now safe)

  Each wave makes subsequent waves SAFER.
```

This is why migration order matters: migrating dependencies first **reduces the risk** of migrating the things that depend on them. ESMP's schedule is designed to maximize this cascading risk reduction.

---

## Core Concept 8: MCP Server — AI Assistant Integration

### What Is MCP?

[Model Context Protocol (MCP)](https://modelcontextprotocol.io/) is a standard protocol for connecting AI assistants to external tools and data sources. Think of it as "USB for AI" — a universal plug that lets any AI assistant (Claude Code, Cursor, custom agents) connect to ESMP's knowledge services.

ESMP implements an MCP server using Spring AI's MCP Server WebMVC module with SSE (Server-Sent Events) transport at `http://localhost:8080/mcp/sse`.

### The 10 Tools

| Tool | Purpose | When to Use |
|------|---------|-------------|
| `getMigrationContext` | Full context assembly for a class | Before migrating any class — primary tool |
| `searchKnowledge` | Semantic vector search | Finding relevant code when you don't have a specific class FQN |
| `getDependencyCone` | Graph-based dependency traversal | Understanding what a class depends on |
| `getRiskAnalysis` | Risk heatmap or class detail | Planning which classes to migrate and in what order |
| `browseDomainTerms` | Business vocabulary search | Understanding business impact before migrating |
| `validateSystemHealth` | 47 integrity checks | Pre-migration health check, post-migration validation |
| `getMigrationPlan` | Per-class migration plan | Seeing which Vaadin 7 types are automatable vs manual |
| `applyMigrationRecipes` | Preview OpenRewrite diffs | Auto-migrating YES-classified type changes (preview mode, safe) |
| `getModuleMigrationSummary` | Module-level migration stats | Planning how much of a module can be auto-migrated |
| `getRecipeBookGaps` | Unmapped type gaps by usage | Identifying which Vaadin 7 types still need mapping rules |

### How AI Assistants Use ESMP

When Claude Code (or any MCP client) connects to ESMP, it gains access to the entire knowledge graph through natural language:

```
  Developer: "I need to migrate the PaymentProcessor class"
       │
       ▼
  Claude Code decides to call getMigrationContext("com.acme.billing.PaymentProcessor")
       │
       ▼
  ESMP MCP Server:
  ├── GraphQueryService:     dependency cone (32 reachable nodes)
  ├── RiskService:           risk detail (enhanced: 0.72, financial: 0.8)
  ├── LexiconService:        domain terms (Payment=High, Invoice=High)
  ├── Neo4j DEFINES_RULE:    business rules (PriceValidator, DiscountCalculator)
  └── RagService:            15 code chunks (re-ranked by graph proximity + risk)
       │
       ▼ All assembled into MigrationContext (token-budgeted, <8000 tokens)
       │
  Claude Code: "PaymentProcessor is high-risk (0.72) with financial involvement (0.8).
                It depends on PaymentGateway, OrderRepository, and InvoiceService.
                Business rules: PriceValidator and DiscountCalculator must be preserved.
                Here's the migrated Vaadin 24 code with all relationships maintained..."
```

**Key design decisions:**

- **Parallel assembly:** All 5 services run concurrently via `CompletableFuture.supplyAsync`. A single `getMigrationContext` call takes ~450ms, not 5 × 450ms.
- **Token budgeting:** AI context windows are limited. ESMP truncates code chunks first (then cone nodes) to stay within 8000 tokens (configurable). This prevents context overflow.
- **Graceful degradation:** If Qdrant is down, `getMigrationContext` still returns the dependency cone, risk analysis, and domain terms — just without code chunks. The `contextCompleteness` field (0.0–1.0) tells the AI how much data it received.
- **Caching:** Dependency cones are cached for 5 minutes, domain terms for 10 minutes. When incremental reindexing runs, caches are automatically evicted for affected classes.

### The Migration Loop

The MCP server enables a tight feedback loop:

```
  ┌─── 1. PLAN: getRiskAnalysis(heatmap) → pick lowest-risk class
  │
  ├─── 2. CONTEXT: getMigrationContext(classFqn) → full knowledge
  │
  ├─── 3. AUTO-MIGRATE: applyMigrationRecipes(classFqn) → OpenRewrite handles YES actions
  │
  ├─── 4. AI-MIGRATE: AI writes Vaadin 24 code for remaining PARTIAL/NO patterns
  │
  ├─── 5. REINDEX: POST /api/indexing/incremental → graph updated
  │
  ├─── 6. VALIDATE: validateSystemHealth() → 47 checks pass?
  │         │
  │         ├── YES → next class
  │         └── NO  → fix issues, re-validate
  │
  └─── 7. REPEAT for next class / module
```

Each iteration:
- The graph reflects the latest state (migrated classes are updated)
- Risk scores decrease as dependencies are migrated
- The schedule adapts (later waves become safer)
- Validation catches any broken relationships immediately

---

## Core Concept 9: Docker Deployment & Enterprise Scale

### One-Command Deployment

ESMP packages everything into a single `docker compose` command:

```
  docker compose -f docker-compose.full.yml up -d

  Starts 6 services:
  ┌──────────┐ ┌──────────┐ ┌──────────┐
  │   ESMP   │ │  Neo4j   │ │  Qdrant  │
  │  :8080   │ │  :7474   │ │  :6333   │
  │          │ │  :7687   │ │  :6334   │
  └──────────┘ └──────────┘ └──────────┘
  ┌──────────┐ ┌──────────┐ ┌──────────┐
  │  MySQL   │ │Prometheus│ │ Grafana  │
  │  :3307   │ │  :9090   │ │  :3000   │
  └──────────┘ └──────────┘ └──────────┘
```

The ESMP Docker image uses a multi-stage build:
- **Stage 1 (builder):** `eclipse-temurin:21-jdk` — compiles Java, builds Vaadin frontend, creates layered JAR
- **Stage 2 (runtime):** `eclipse-temurin:21-jre` — minimal runtime, non-root user, health check

The `docker-compose.full.yml` uses `condition: service_healthy` on all database dependencies, ensuring ESMP only starts after Neo4j, MySQL, and Qdrant are ready.

### Source Access Strategies

The Docker container needs access to the target codebase for analysis. ESMP provides two strategies:

**Volume Mount (default):** Bind-mount the codebase from the host into the container:

```
  Host: /path/to/legacy-app/src/main/java
       │
       │  bind mount (read-only)
       ▼
  Container: /mnt/source
       │
       └── SourceAccessService resolves this path on startup
```

**GitHub Clone:** JGit clones the repository into the container using a Personal Access Token:

```
  GitHub: https://github.com/org/legacy-app.git
       │
       │  JGit clone (HTTPS + PAT)
       ▼
  Container: /data/esmp-source-clone
       │
       └── SourceAccessService resolves this path on startup
           (pulls on restart if clone already exists,
            re-clones if remote URL changed)
```

The `SourceAccessService` resolves the source root on `ApplicationReadyEvent`, so by the time the app accepts requests, the source path is ready.

### Parallel Extraction

For enterprise-scale codebases (4M+ LOC, 40K+ files), sequential extraction is too slow. ESMP parallelizes:

```
  40,000 Java files
       │
       │  files > parallelThreshold (500)?
       │
       ├── NO: Sequential path (small codebases)
       │        Single thread, single accumulator
       │
       └── YES: Parallel path (enterprise codebases)
                │
                ├── Partition into 200 batches of 200 files
                │
                ├── Each batch gets:
                │   - Its own ExtractionAccumulator (no shared state)
                │   - Its own visitor instances (stateless per batch)
                │   - Runs on ThreadPoolTaskExecutor (4-N threads)
                │
                ├── All batches complete via CompletableFuture.allOf
                │
                └── Merge all accumulators:
                    - Maps: putAll / putIfAbsent (annotations)
                    - Lists: addAll (edges)
                    - Sets: addAll (stereotypes)
                    - Business terms: merge allSourceFqns
                    - Write data: sum counts
```

Persistence also parallelizes using **batched UNWIND MERGE Cypher** instead of per-node `saveAll()`:

```cypher
-- Before (per-node, slow):
-- 5000 individual Neo4j transactions for 5000 annotations

-- After (batched, fast):
UNWIND $rows AS row
MERGE (a:AnnotationType {fullyQualifiedName: row.fqn})
ON CREATE SET a.simpleName = row.simpleName
ON MATCH SET a.simpleName = row.simpleName
-- 3 transactions for 5000 annotations (2000-row batches)
```

### SSE Progress Streaming

Enterprise extractions take minutes. The extraction trigger returns immediately (HTTP 202 + jobId), and a separate SSE endpoint streams progress:

```
  Client                              ESMP
  ──────                              ────
  POST /api/extraction/trigger ──────▶ {"jobId": "abc", "status": "accepted"}
                                       │
  GET /api/extraction/progress ──────▶ SSE stream opens
       ?jobId=abc                      │
                                       ├── event: progress
                                       │   data: {"phase":"SCANNING", "filesProcessed":0, "totalFiles":40000}
                                       │
                                       ├── event: progress
                                       │   data: {"phase":"VISITING", "filesProcessed":5000, "totalFiles":40000}
                                       │
                                       ├── event: progress
                                       │   data: {"phase":"VISITING", "filesProcessed":20000, "totalFiles":40000}
                                       │
                                       ├── event: progress
                                       │   data: {"phase":"PERSISTING", "filesProcessed":40000, "totalFiles":40000}
                                       │
                                       └── event: done
                                           data: complete
```

Progress phases: `SCANNING` → `PARSING` → `VISITING` → `PERSISTING` → `LINKING`

---

## Core Concept 10: Migration Engine — Automated OpenRewrite Recipes

ESMP doesn't just analyze your codebase — it can automatically migrate the straightforward type changes using OpenRewrite recipes, leaving only the complex patterns for AI-assisted or manual migration.

### The 3-Stage Pipeline

```
  Stage 1: CATALOG (MigrationPatternVisitor)
  ───────────────────────────────────────────
  The 8th visitor scans every .java file for Vaadin 7 type references
  using a 94-rule external JSON recipe book (loaded from
  data/migration/vaadin-recipe-book.json). Each usage becomes a
  MigrationAction node. Custom overlay rules can extend the base set.

  com.vaadin.ui.Table            → Grid              (YES — direct rename)
  com.vaadin.ui.TextField        → TextField (Flow)  (YES — package move)
  com.vaadin.ui.DateField        → DatePicker        (YES — direct rename)
  com.vaadin.ui.CustomComponent  → Composite         (PARTIAL — API differs)
  com.vaadin.ui.Window           → Dialog             (NO — different paradigm)
  com.vaadin.navigator.View      → @Route             (NO — pattern change)

  Also handles:
  - PARTIAL rules: types that need manual API adjustment after rename
  - COMPLEX rules: types with no direct equivalent
  - JAVAX_PACKAGE rules: javax.* → jakarta.* package migrations


  Stage 2: CLASSIFY (per-class scores)
  ─────────────────────────────────────
  For each class with Vaadin 7 usage:

  migrationActionCount    = total type usages found
  automatableActionCount  = count where automation = YES
  automationScore         = automatableActionCount / migrationActionCount
  needsAiMigration        = true if any action is PARTIAL or NO

  These properties are stored on the ClassNode for scheduling and planning.


  Stage 3: EXECUTE (MigrationRecipeService)
  ──────────────────────────────────────────
  For each YES action, generates an OpenRewrite ChangeType recipe.
  For javax.* packages, generates ChangePackage recipes.

  Two execution modes:
  ┌──────────────────────────────────────────────────────┐
  │  preview()        Returns unified diff. No files     │
  │                   changed. Safe for exploration.     │
  │                                                      │
  │  applyAndWrite()  Writes transformed source to disk. │
  │                   Use after reviewing the preview.   │
  └──────────────────────────────────────────────────────┘
```

### Automation Classification

| Classification | Meaning | OpenRewrite Action | Example |
|---------------|---------|-------------------|---------|
| **YES** | Direct type/package rename | `ChangeType` recipe auto-applied | `Table` → `Grid` |
| **PARTIAL** | Type exists but API differs | Rename applied, manual API fix needed | `CustomComponent` → `Composite` |
| **NO** | No direct equivalent | Flagged for AI/manual migration | `Window` → `Dialog` |

### Per-Class Migration Scores

Every class gets four new properties after extraction:

```
  OrderFormView
  ├── migrationActionCount: 5      (5 Vaadin 7 types used)
  ├── automatableActionCount: 3    (3 can be auto-migrated)
  ├── automationScore: 0.60        (60% automatable)
  └── needsAiMigration: true       (2 actions need AI help)
```

These scores integrate with the scheduling algorithm — classes with high `automationScore` are cheaper to migrate and can be prioritized in earlier waves.

### Safety Model

The MCP tool `applyMigrationRecipes` calls `preview()` (returns diffs) rather than `applyAndWrite()` (modifies files). This means:

- **AI assistants** can safely explore migration plans without modifying source code
- **Developers** use the REST API's `/api/migration/apply/{fqn}` endpoint for explicit file writes
- **Module-level apply** (`/api/migration/apply-module`) requires explicit REST call — never triggered by MCP

This layered safety model ensures that automated tooling explores and suggests, while humans explicitly approve file modifications.

---

## Core Concept 11: Recipe Book & Transitive Detection

Phase 17 replaced the hardcoded static type maps with an external, curated recipe book and added transitive detection that finds classes inheriting from Vaadin 7 types through the graph.

### External Recipe Book

The recipe book is an external JSON file (`data/migration/vaadin-recipe-book.json`) containing 94 base rules. Each rule specifies:

```
{
  "id": "vaadin7-table-to-grid",
  "oldType": "com.vaadin.ui.Table",
  "newType": "com.vaadin.flow.component.grid.Grid",
  "automation": "YES",
  "category": "COMPONENT",
  "status": "MAPPED",
  "source": "BASE",
  "migrationSteps": ["Replace Table with Grid", "Convert ColumnGenerator to Renderer"]
}
```

**RecipeBookRegistry** loads rules at startup and serves as the in-memory lookup for MigrationPatternVisitor. A custom overlay file can be configured to extend or override base rules — custom rules take precedence when IDs collide.

### Transitive Detection

Many classes inherit from Vaadin 7 types without directly importing them. Transitive detection uses `EXTENDS*1..10` Cypher graph traversal to find these classes:

```
  Known Vaadin 7 Types (e.g., com.vaadin.ui.CustomComponent)
         │
         │ EXTENDS*1..10 traversal
         ▼
  ┌──────────────────────────────────┐
  │  MyBaseComponent extends         │
  │    CustomComponent               │  ← detected (depth 1)
  ├──────────────────────────────────┤
  │  SpecialWidget extends           │
  │    MyBaseComponent               │  ← detected (depth 2, transitive)
  └──────────────────────────────────┘
```

Each transitive class gets a **complexity profile** based on configurable weights (override-weight, own-calls-weight, binding-weight, component-weight):

| Profile | Score | Meaning |
|---------|-------|---------|
| **Pure wrapper** | Below threshold | Simple delegation, fully automatable |
| **AI-assisted** | At or above threshold | Moderate logic, needs AI help |
| **Complex** | Well above threshold | Significant custom behavior, manual migration |

### Enrichment Feedback Loop

After extraction completes, the recipe book is enriched automatically:

```
  Extraction runs
       │
       ▼
  MigrationPatternVisitor catalogs Vaadin 7 usages
       │
       ▼
  Recipe book usageCount updated for matched rules
       │
       ▼
  Unmapped types auto-added as NEEDS_MAPPING / DISCOVERED
       │
       ▼
  Gaps visible at /api/migration/recipe-book/gaps
       │
       ▼
  User or AI resolves gaps → adds custom rules
       │
       ▼
  Re-extract → gaps shrink, coverage improves
```

This creates a continuous improvement loop: each extraction surfaces new gaps, which are resolved and fed back into the recipe book.

### Coverage Metrics

`ModuleMigrationSummary` now includes coverage metrics:

- **coverageByType**: percentage of distinct Vaadin 7 types that have MAPPED rules
- **coverageByUsage**: percentage of actual type usages that are covered by MAPPED rules (weighted by frequency)
- **topGaps**: most-used types that still lack mapping, sorted by usage count

### Recipe Book Management API

Five endpoints at `/api/migration/recipe-book` manage the recipe book:

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/migration/recipe-book` | List all rules (filterable by category, status, automatable) |
| `GET` | `/api/migration/recipe-book/gaps` | NEEDS_MAPPING rules sorted by usage count |
| `PUT` | `/api/migration/recipe-book/rules/{id}` | Add or update a custom rule |
| `DELETE` | `/api/migration/recipe-book/rules/{id}` | Delete custom/discovered rule (base rules return 403) |
| `POST` | `/api/migration/recipe-book/reload` | Re-read recipe book from file |

The MCP tool `getRecipeBookGaps` (10th tool) exposes gap data to AI assistants, enabling them to identify unmapped types and suggest resolutions.

---

## How Everything Connects

Here's how all 11 core concepts flow together in a complete migration:

```
  ┌─────────────────────────────────────────────────────────────┐
  │                    YOUR LEGACY CODEBASE                      │
  │              (4M LOC, 40K Java files, Vaadin 7)             │
  └────────────────────────┬────────────────────────────────────┘
                           │
                    1. AST EXTRACTION
                    (8 visitors, parallel)
                           │
                           ▼
  ┌─────────────────────────────────────────────────────────────┐
  │                  CODE KNOWLEDGE GRAPH                        │
  │              (Neo4j: 9 node types, 10 edge types)           │
  └──────┬──────────┬──────────┬───────────┬────────────────────┘
         │          │          │           │
    2. GRAPH   3. RISK    4. LEXICON  5. VECTORS
    QUERIES    SCORING    EXTRACTION  (Qdrant, 384-dim)
         │          │          │           │
         └──────────┴──────────┴───────────┘
                           │
                    6. RAG PIPELINE
                    (graph + vectors + risk
                     → weighted re-ranking)
                           │
                    7. SCHEDULING
                    (4-dimension scoring
                     → wave assignment)
                           │
                    8. MCP SERVER
                    (10 tools for AI assistants)
                           │
                           ▼
  ┌─────────────────────────────────────────────────────────────┐
  │              AUTO + AI-ASSISTED MIGRATION                    │
  │                                                              │
  │   For each wave, for each module, for each class:           │
  │   1. getMigrationPlan → see what's automatable               │
  │   2. applyMigrationRecipes → auto-migrate YES actions        │
  │   3. getMigrationContext → full knowledge for remaining       │
  │   4. AI writes Vaadin 24 code for PARTIAL/NO patterns        │
  │   5. Incremental reindex → graph updated                    │
  │   6. validateSystemHealth → 47 checks                       │
  │   7. Risk scores decrease → next wave gets safer            │
  └─────────────────────────────────────────────────────────────┘
                           │
                    9. DOCKER DEPLOYMENT
                    (one-command setup,
                     enterprise-scale parallel extraction,
                     SSE progress monitoring)
                           │
                   10. MIGRATION ENGINE
                    (OpenRewrite recipes,
                     94-rule recipe book,
                     preview + apply modes)
                           │
                   11. RECIPE BOOK &
                    TRANSITIVE DETECTION
                    (external JSON rules,
                     EXTENDS traversal,
                     enrichment feedback loop)
```

---

## Without ESMP vs With ESMP

| Aspect | Without ESMP | With ESMP |
|--------|-------------|-----------|
| **Finding Vaadin 7 code** | `grep -r "com.vaadin.ui"` → 200 files, no structure | VaadinPatternVisitor → views, bindings, components categorized with what they bind to |
| **Understanding dependencies** | Read imports manually, hope you found them all | Dependency cone: ALL 45 transitively reachable classes in milliseconds |
| **Knowing what breaks** | Find out in QA, days later | Fan-in analysis before changing anything |
| **Migration order** | Gut feeling, team debate | Data-driven wave schedule based on risk, dependencies, complexity, change frequency |
| **AI context for migration** | Paste one file, hope AI guesses the rest | 25 most relevant code chunks with graph proximity, risk scores, business terms |
| **Business impact** | Read Javadoc (if it exists) | Automatic business term extraction with criticality scoring |
| **Type migrations** | Manual find-and-replace for each Vaadin 7 type | 94-rule recipe book with OpenRewrite auto-migration; transitive detection finds inherited Vaadin 7 types; enrichment loop surfaces gaps automatically |
| **Progress tracking** | Spreadsheet, manual counting | 47 automated validation queries after every change |
| **Risk visibility** | "It seems complex" | Quantified: CC=15, fan-in=20, financial=0.8, security=0.6 |
| **Team alignment** | Meetings to agree on approach | Dashboard showing risk heatmap, dependency graph, schedule |
| **Deployment** | Install Java, Gradle, Neo4j, Qdrant, MySQL manually | `docker compose -f docker-compose.full.yml up -d` |
| **Scale** | Works for 100 classes, breaks at 10K | Parallel extraction handles 40K+ files |

---

## Practical Guide: Migrating an Enterprise Vaadin 7 Project to Vaadin 24/25

This section walks through how to use ESMP end-to-end to migrate a real enterprise codebase. The steps are ordered — follow them sequentially.

### Prerequisites

- Docker and Docker Compose installed
- Your legacy Vaadin 7 project source code accessible (local path or GitHub repo)
- Claude Code (or another MCP-compatible AI assistant) installed
- A target Vaadin 24/25 project initialized (empty Spring Boot + Vaadin 24 starter, or a new branch of your existing project)

### Phase 1: Deploy ESMP and Analyze Your Codebase

#### Step 1.1: Start ESMP

```bash
git clone https://github.com/arehmann/esmp.git
cd esmp

# Configure source access
cp .env.example .env
```

Edit `.env` to point at your legacy codebase:

```bash
# Option A: Local source code
ESMP_SOURCE_STRATEGY=VOLUME_MOUNT
SOURCE_ROOT=/path/to/your/legacy-vaadin7-app

# Option B: GitHub repository
ESMP_SOURCE_STRATEGY=GITHUB_URL
ESMP_SOURCE_GITHUB_URL=https://github.com/your-org/legacy-app.git
ESMP_SOURCE_GITHUB_TOKEN=ghp_your_token_here
ESMP_SOURCE_BRANCH=main
```

Start everything:

```bash
docker compose -f docker-compose.full.yml up -d

# Wait for all services (check health)
docker compose -f docker-compose.full.yml ps
# All services should show "healthy" (~2 minutes for first build)
```

Verify ESMP is running:

```bash
curl http://localhost:8080/actuator/health
# → {"status":"UP"}

curl http://localhost:8080/api/source/status
# → {"strategy":"VOLUME_MOUNT","sourceRoot":"/mnt/source","resolved":true}
```

#### Step 1.2: Run Full Extraction

```bash
# Trigger extraction (returns immediately)
curl -X POST http://localhost:8080/api/extraction/trigger \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/mnt/source/src/main/java",
    "classpathFile": "/mnt/source/classpath.txt"
  }'
# → {"jobId": "abc-123", "status": "accepted"}

# Monitor progress (optional, useful for large codebases)
curl -N "http://localhost:8080/api/extraction/progress?jobId=abc-123"
```

> **Classpath file:** Generate one from your legacy project: `./gradlew dependencies --configuration runtimeClasspath | grep '\\---' | sed 's/.*--- //' | sort -u > classpath.txt`. This helps ESMP detect Vaadin 7 types accurately. If you don't have one, extraction still works but with reduced Vaadin detection accuracy.

#### Step 1.3: Build Vector Index

```bash
curl -X POST "http://localhost:8080/api/vector/index?sourceRoot=/mnt/source/src/main/java"
```

This embeds every class and method into 384-dimensional vectors for semantic search. Takes a few minutes for large codebases (first run downloads the ONNX model ~90MB).

#### Step 1.4: Validate the Knowledge Graph

```bash
curl http://localhost:8080/api/graph/validation | python -m json.tool
```

Check that the pass count is high. Common warnings:
- `ORPHAN_CLASSES`: Classes with no relationships (utility classes, typically fine)
- `VECTOR_INDEX_POPULATED`: Should be PASS after step 1.3

#### Step 1.5: Explore the Dashboard

Open **http://localhost:8080** in your browser.

What to look for:
- **Metric cards:** Total classes, Vaadin 7 views count, high-risk class count, business term count
- **Risk heatmap:** Which modules have the highest risk? These are your later waves
- **Dependency graph:** Click modules to explore internal dependencies. Look for tightly coupled clusters
- **Business concept graph:** Which business terms are most used? These are your high-impact areas

**Take notes on what you see.** This is your migration landscape.

### Phase 2: Plan the Migration

#### Step 2.1: Generate the Migration Schedule

Open **http://localhost:8080/schedule** and click **Generate Schedule**.

Or via API:

```bash
curl "http://localhost:8080/api/scheduling/recommend?sourceRoot=/mnt/source" | python -m json.tool
```

You'll get a wave-ordered migration plan:

```
  Wave 1 (Safest)          Wave 2 (Moderate)        Wave 3 (Risky)
  ├── utils (0.12)         ├── service (0.45)       ├── billing (0.78)
  ├── config (0.18)        ├── data (0.52)          └── ui (0.62)
  └── common (0.21)        └── report (0.38)

  Wave 4 (Circular Deps)
  ├── auth (0.85)
  └── payment (0.83)
```

**Migration rule:** Always migrate left to right, top to bottom. Dependencies are guaranteed to be in earlier waves.

#### Step 2.2: Validate Pilot Module

Pick the first module from Wave 1:

```bash
curl "http://localhost:8080/api/pilot/validate/utils"
```

All 5 checks should pass:
- `MODULE_HAS_CLASSES` ✓
- `VAADIN7_PRESENT` ✓ (or ✗ if no Vaadin code — still fine to start here)
- `GRAPH_COMPLETE` ✓
- `VECTOR_INDEXED` ✓
- `TERMS_EXTRACTED` ✓

#### Step 2.3: Curate Business Terms (Optional but Recommended)

Open **http://localhost:8080/lexicon** and review the high-criticality terms.

For each important term:
1. Update the definition to be precise
2. Set the correct criticality (High/Medium/Low)
3. Add synonyms if needed
4. Mark as curated (protects from extraction overwrite)

This improves risk scoring accuracy and gives AI better context about business impact.

### Phase 3: Migrate Module by Module

This is where the actual migration happens. The process is the same for every module.

#### Step 3.1: List Classes in the Module (Sorted by Risk)

```bash
# Get classes in the module, sorted by risk (lowest first)
curl "http://localhost:8080/api/risk/heatmap?module=utils&sortBy=enhanced&limit=100"
```

Start with the lowest-risk class. Low risk = simplest, fewest dependencies, safest to learn the pattern.

#### Step 3.2: For Each Class — Auto-Migrate Then AI-Migrate via MCP

Open Claude Code in your **target project** (the Vaadin 24/25 project where migrated code will live).

Make sure ESMP is running and `.mcp.json` is configured (Claude Code auto-discovers it).

**First, auto-migrate what OpenRewrite can handle:**

```
You: "I'm migrating com.acme.utils.DateFormatter from Vaadin 7 to Vaadin 24.
      First show me what can be auto-migrated."

Claude: [calls getMigrationPlan("com.acme.utils.DateFormatter")]

Claude: "DateFormatter has 2 migration actions:
         - com.vaadin.data.util.converter.Converter → Converter (Flow) — YES (automatable)
         - com.vaadin.ui.Label → Span — YES (automatable)
         automationScore: 1.0 — fully automatable!

         Let me preview the auto-migration..."

Claude: [calls applyMigrationRecipes("com.acme.utils.DateFormatter", "/path/to/src")]

Claude: "Here's the OpenRewrite diff — both imports updated automatically.
         Shall I apply this?"
```

**Then, for classes with PARTIAL/NO actions, get full AI context:**

```
You: "Now migrate com.acme.ui.OrderFormView — it has complex patterns."

Claude: [calls getMigrationPlan("com.acme.ui.OrderFormView")]

Claude: "OrderFormView has 5 migration actions:
         - Table → Grid (YES), TextField → TextField (YES), DateField → DatePicker (YES)
         - CustomComponent → Composite (PARTIAL — API differs)
         - Navigator.View → @Route (NO — pattern change)
         automationScore: 0.60 — 3 of 5 are automatable.

         Let me auto-migrate the 3 YES actions first, then get full
         context for the remaining patterns..."

Claude: [calls applyMigrationRecipes("com.acme.ui.OrderFormView", "/path/to/src")]
Claude: [calls getMigrationContext("com.acme.ui.OrderFormView")]

Claude: "Auto-migration applied 3 import changes. For the remaining patterns:
         - CustomComponent → Composite: change extends clause, wrap content in getContent()
         - Navigator.View → @Route: add @Route annotation, remove implements View
         Here's the complete migrated code: ..."
```

#### Step 3.3: Review and Apply the Migration

The AI produces Vaadin 24 code with full context. Review it:

- Does it preserve the business logic?
- Are all Vaadin 7 patterns correctly mapped to Vaadin 24?
  - `com.vaadin.ui.Table` → `com.vaadin.flow.component.grid.Grid`
  - `BeanItemContainer` → `DataProvider`
  - `Property` → `Binder`
  - `Navigator` → `@Route`
  - `com.vaadin.ui.CustomComponent` → `com.vaadin.flow.component.Composite`
- Does it handle all callers (fan-in)? The context includes who calls this class
- Are method signatures compatible with existing callers?

Apply the code to your target project.

#### Step 3.4: Re-Index and Validate

After writing the migrated code, tell ESMP about the change:

```bash
curl -X POST "http://localhost:8080/api/indexing/incremental" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/mnt/source/src/main/java",
    "changedFiles": ["com/acme/utils/DateFormatter.java"]
  }'
```

Then validate:

```bash
curl http://localhost:8080/api/graph/validation | python -m json.tool
```

Or via MCP:

```
You: "Validate the knowledge graph health after my changes"
Claude: [calls validateSystemHealth()]
Claude: "47/47 checks passing. No broken references."
```

**If validation fails:** The response tells you exactly what broke (e.g., "DANGLING_EDGE: OrderView still references old DateFormatter method signature"). Fix the issue before proceeding.

#### Step 3.5: Repeat for Each Class in the Module

Work through the module from lowest risk to highest risk. Each class you migrate:
1. Reduces the Vaadin 7 count
2. Updates the graph with new code structure
3. Makes subsequent classes easier (their dependencies are now migrated)

#### Step 3.6: Module Complete — Full Validation

After all classes in the module are migrated:

```bash
curl "http://localhost:8080/api/pilot/validate/utils"
```

All 5 checks should still pass. The `VAADIN7_PRESENT` check may now show fewer Vaadin 7 classes (or zero if the module is fully migrated).

### Phase 4: Wave by Wave — Systematic Rollout

Repeat Phase 3 for each module in each wave:

```
  Wave 1: utils → config → common
  Wave 2: service → data → report
  Wave 3: billing → ui
  Wave 4: auth → payment (these need the most care)
```

**Between waves:**

1. Run full validation: `curl http://localhost:8080/api/graph/validation`
2. Regenerate the schedule: `curl "http://localhost:8080/api/scheduling/recommend"` — risk scores should have decreased for remaining modules
3. Review the dashboard — the risk heatmap should be getting "greener"

### Phase 5: Continuous Validation

Throughout the migration, keep ESMP running and re-indexing:

```bash
# After any code change, update the graph
curl -X POST "http://localhost:8080/api/indexing/incremental" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/mnt/source/src/main/java",
    "changedFiles": ["com/acme/billing/InvoiceService.java"],
    "deletedFiles": ["com/acme/billing/OldInvoiceHelper.java"]
  }'
```

What to watch for:
- **Validation errors:** Broken DEPENDS_ON edges (class was renamed but callers still reference old name)
- **Risk score spikes:** A module's risk increases if you introduce new dependencies
- **Orphan nodes:** Classes that lost all their edges (might indicate a missed migration)

### Vaadin 7 to Vaadin 24/25 Pattern Mapping Reference

ESMP detects these Vaadin 7 patterns and provides context for migration:

| Vaadin 7 Pattern | Vaadin 24/25 Equivalent | ESMP Detection |
|-------------------|------------------------|----------------|
| `extends CustomComponent` | `extends Composite<Div>` | VaadinPatternVisitor |
| `extends UI` | `@Route` + `AppLayout` | VaadinPatternVisitor |
| `Navigator` | `@Route("path")` | VaadinPatternVisitor |
| `com.vaadin.ui.Table` | `Grid<T>` | VaadinPatternVisitor |
| `com.vaadin.ui.TextField` | `com.vaadin.flow.component.textfield.TextField` | VaadinPatternVisitor |
| `com.vaadin.ui.DateField` | `DatePicker` | VaadinPatternVisitor |
| `com.vaadin.ui.ComboBox` | `ComboBox<T>` (Flow) | VaadinPatternVisitor |
| `BeanItemContainer<T>` | `DataProvider.ofCollection(list)` | VaadinPatternVisitor |
| `Property<T>` / `Item` | `Binder<T>` | VaadinPatternVisitor |
| `FieldGroup` | `Binder<T>.forField()` | VaadinPatternVisitor |
| `Button.ClickListener` | `Button.addClickListener(e -> ...)` | VaadinPatternVisitor |
| `@PreAuthorize` / Security | Same (Spring Security unchanged) | ComplexityVisitor (security flag) |
| `@Entity` / `@Query` | Same (JPA unchanged) | JpaPatternVisitor |
| `@Repository` / `@Service` | Same (Spring unchanged) | ClassMetadataVisitor |

### Tips for Success

**Start small.** Don't try to migrate the riskiest module first. Use Wave 1 as your learning wave — build confidence on simple classes before tackling complex ones.

**Curate business terms early.** The 15 minutes you spend reviewing and curating the lexicon pays off in every subsequent `getMigrationContext` call — the AI gets better business context.

**Use the dashboard as a war room.** Open it on a shared screen during migration sprints. The risk heatmap shows progress visually — as modules turn green, the team sees momentum.

**Re-validate after every change.** Don't batch 10 classes and then validate. Validate after each class — it takes seconds and catches issues immediately when the change is fresh in your mind.

**Trust the schedule but apply judgment.** ESMP recommends Wave 1 first because it's data-driven. But if your team has deep expertise in a specific module, that knowledge is valuable too. Use the schedule as the default, override with team knowledge when justified.

**Keep the graph current.** If developers are making changes to the legacy codebase while migration is ongoing, run incremental reindexing regularly. Stale graph data leads to incorrect migration context.

**Use MCP for exploration, not just migration.** Even before you start migrating, use `searchKnowledge` and `getDependencyCone` to understand unfamiliar parts of the codebase. The graph is a powerful exploration tool.
