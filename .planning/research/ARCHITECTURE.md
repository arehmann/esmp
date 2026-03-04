# Architecture Research

**Domain:** Enterprise Code Modernization Platform (AI-assisted Java/Vaadin migration)
**Researched:** 2026-03-04
**Confidence:** MEDIUM-HIGH (core patterns verified via multiple sources; ESMP-specific combination is novel)

## Standard Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                       INGESTION LAYER                               │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐  │
│  │  AST Engine  │  │  Lexicon     │  │   Continuous Indexing     │  │
│  │ (OpenRewrite)│  │  Extractor   │  │   (CI Hook / Watcher)     │  │
│  └──────┬───────┘  └──────┬───────┘  └─────────────┬─────────────┘  │
│         │                 │                         │               │
├─────────┴─────────────────┴─────────────────────────┴───────────────┤
│                       KNOWLEDGE LAYER                               │
│  ┌──────────────────────────┐  ┌──────────────────────────────────┐  │
│  │  Code Knowledge Graph    │  │     Vector Store                 │  │
│  │  (Neo4j)                 │  │     (Qdrant)                     │  │
│  │  - Structural graph      │  │     - Enriched chunk embeddings  │  │
│  │  - Dependency edges      │  │     - Domain term embeddings     │  │
│  │  - Domain lexicon nodes  │  │     - Risk-metadata payloads     │  │
│  └──────────────────────────┘  └──────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────────────┤
│                       RETRIEVAL LAYER                               │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Multi-Layer Retrieval Pipeline                              │   │
│  │  graph expansion → lexicon match → embedding sim → risk sort │   │
│  └──────────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────────┤
│                   AI ORCHESTRATION LAYER                            │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Migration Orchestrator                                      │   │
│  │  trigger OpenRewrite → retrieve context → build prompt       │   │
│  │  → call Claude → validate output → open PR                   │   │
│  └──────────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────────┤
│                   GOVERNANCE & API LAYER                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐   │
│  │  REST API    │  │  Governance  │  │  Observability           │   │
│  │  (Spring MVC)│  │  Dashboard   │  │  (Prometheus / Grafana)  │   │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Typical Implementation |
|-----------|----------------|------------------------|
| AST Engine | Parse Java source into Lossless Semantic Trees; extract structural facts (classes, methods, fields, calls, dependencies) | OpenRewrite visitor pattern; triggered via Gradle plugin or programmatic API |
| Lexicon Extractor | Mine domain terms from enums, Javadoc, comments, DB schema column names; produce a curated Domain Lexicon | Custom OpenRewrite visitor + NLP normalization; domain expert curation loop |
| Continuous Indexer | Detect changed files on each CI build; re-extract and update graph nodes + vector embeddings incrementally | CI hook (Gradle task or GitHub Action) calling ingestion REST endpoint |
| Code Knowledge Graph | Store structural entities and relationships as a property graph; track risk scores, fan-in/out, cyclomatic complexity | Neo4j with named node labels (Class, Method, Field, Module) and relationship types (CALLS, EXTENDS, IMPORTS, USES_TERM, DEFINES_RULE) |
| Vector Store | Store embedding vectors for enriched code chunks (class-level, method-level, UI block, validation block, business rule); enable similarity search | Qdrant collection per chunk type; Spring AI QdrantVectorStore adapter; HNSW index |
| Multi-Layer Retrieval | Given a migration target, assemble relevant context: graph expansion (dependency cone) + lexicon match + embedding similarity + risk prioritization | Spring service composing Neo4j Cypher + Qdrant vector search; re-ranking by risk score |
| Migration Orchestrator | Coordinate the full migration workflow for a module: AST transform → context retrieval → prompt construction → Claude call → output validation → PR creation | Spring service with deterministic guardrails (no contract changes, no security annotation removal); behavioral diffing pre/post transform |
| Governance Dashboard | Expose migration KPIs: % legacy API removed, % UI migrated, lexicon coverage, risk clusters, AI confidence, regression counts | Spring MVC REST endpoints consumed by lightweight web UI; backed by Neo4j aggregation queries |
| Observability Stack | Track ingestion throughput, RAG latency, Claude token usage, migration success rate, regression detections | Prometheus metrics via Spring Boot Actuator; Grafana dashboards |

## Recommended Project Structure

```
esmp/
├── ingestion/                    # Ingestion layer — parses and indexes the legacy codebase
│   ├── ast/                      # OpenRewrite visitors, LST parsing, entity extraction
│   ├── lexicon/                  # Domain term extraction, normalization, curation API
│   └── indexer/                  # Incremental re-indexing, CI integration endpoint
│
├── knowledge/                    # Knowledge layer — storage and schema
│   ├── graph/                    # Neo4j repository, Cypher queries, graph schema definitions
│   └── vectors/                  # Qdrant collections, embedding service, chunk strategy
│
├── retrieval/                    # Retrieval layer — hybrid GraphRAG pipeline
│   ├── graphexpander/            # Cypher-based dependency cone traversal
│   ├── lexiconmatcher/           # Lexicon-aware token expansion
│   └── pipeline/                 # Orchestrates retrieval stages and re-ranks by risk
│
├── orchestration/                # AI orchestration layer
│   ├── migrator/                 # Migration workflow: transform → retrieve → prompt → generate → validate
│   ├── guardrails/               # Deterministic safety checks (contract, security, validation)
│   ├── diffing/                  # Behavioral diff engine (service output, SQL, validation behavior)
│   └── scm/                      # PR creation, branch management (GitHub/GitLab API)
│
├── governance/                   # Governance & API layer
│   ├── api/                      # REST controllers (Spring MVC)
│   ├── dashboard/                # KPI aggregation, lexicon coverage, risk cluster queries
│   └── metrics/                  # Prometheus custom metrics, health endpoints
│
├── shared/                       # Cross-cutting concerns
│   ├── model/                    # Shared domain model (CodeEntity, MigrationResult, etc.)
│   ├── config/                   # Spring configuration, Docker Compose wiring
│   └── security/                 # API authentication (if needed)
│
└── docker/                       # Docker Compose and infrastructure definitions
    ├── docker-compose.yml
    ├── neo4j/
    ├── qdrant/
    └── grafana/
```

### Structure Rationale

- **ingestion/**: Isolated from query path. Changes to how source is parsed do not ripple into retrieval or orchestration.
- **knowledge/**: Pure storage layer. Both graph and vector stores expose repository interfaces; orchestration never calls DB drivers directly.
- **retrieval/**: Single-responsibility: assemble context. Does not trigger transformations or call Claude.
- **orchestration/**: The only layer that calls the AI provider and creates PRs. Depends on retrieval, never on ingestion.
- **governance/**: Read-only view over knowledge stores. Can be disabled without affecting migration capability.
- **shared/model/**: Single definition of `CodeEntity`, `MigrationJob`, `LexiconTerm` prevents drift between layers.

## Architectural Patterns

### Pattern 1: Lossless Semantic Tree (LST) as Source of Truth

**What:** OpenRewrite builds an LST — a richer-than-AST representation that preserves whitespace and full type information. All structural facts written to Neo4j originate from LST visitors, not string parsing.
**When to use:** Any time you need to extract facts from Java source that survive round-trip (extract → store → emit patch).
**Trade-offs:** Higher parsing cost than regex/string; requires OpenRewrite Gradle plugin on the legacy build. Reward is 100% syntactically correct output patches.

**Example:**
```java
// OpenRewrite visitor extracting method call edges
public class CallGraphVisitor extends JavaIsoVisitor<ExecutionContext> {
    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        MethodMatcher matcher = new MethodMatcher("*..* *(..)");
        if (matcher.matches(method)) {
            ctx.getMessage("callEdges", new ArrayList<CallEdge>())
               .add(new CallEdge(currentClass, method.getMethodType()));
        }
        return super.visitMethodInvocation(method, ctx);
    }
}
```

### Pattern 2: GraphRAG Hybrid Retrieval (Graph Expansion + Vector Similarity)

**What:** For a migration target class, first traverse the dependency cone in Neo4j (one-hop CALLS/IMPORTS/EXTENDS expansion) to find structurally related nodes. Then use the expanded node set to re-rank embedding similarity results from Qdrant. Risk score is the final re-ranking key.
**When to use:** Any retrieval request that must respect code structure (callers, callees, inheritance). Pure vector similarity misses structural coupling.
**Trade-offs:** Two-store round-trip adds latency (~50-150ms). Reward is 15%+ precision improvement over vector-only retrieval for code tasks (validated in recent GraphRAG benchmarks).

**Example:**
```java
// Hybrid retrieval pipeline
public List<CodeChunk> retrieve(String targetClass, int k) {
    // Step 1: graph expansion
    List<String> dependencyCone = graphRepository.expandDependencies(targetClass, depth: 2);

    // Step 2: vector similarity constrained to cone
    List<ScoredChunk> candidates = vectorStore.similaritySearch(
        SearchRequest.query(targetClass)
                     .withFilterExpression("class_name IN " + dependencyCone)
                     .withTopK(k * 3));

    // Step 3: re-rank by risk score from graph
    return candidates.stream()
        .sorted(Comparator.comparingDouble(c -> graphRepository.getRiskScore(c.className())))
        .limit(k)
        .collect(toList());
}
```

### Pattern 3: Deterministic Guardrails as Pre/Post Conditions

**What:** Before emitting any migration patch, a guardrail service checks: (a) no public service method signatures changed, (b) no `@Secured`/`@PreAuthorize` annotations removed, (c) no `@NotNull`/`@Valid`/bean validation annotations deleted, (d) no SQL-issuing paths removed. After patch, a behavioral diff engine compares service outputs and SQL plans.
**When to use:** Every migration job, without exception. Guardrails are not optional.
**Trade-offs:** Adds validation overhead per migration job. Eliminates the class of regressions that would make automated migration unsafe to deploy.

### Pattern 4: Event-Driven Incremental Indexing

**What:** CI system calls a `/ingest/incremental` endpoint with a list of changed file paths after each build. The indexer re-parses only changed files, diffs node state in Neo4j, and upserts embeddings in Qdrant. Full re-index is reserved for schema changes.
**When to use:** Always, once initial full index is established. Full re-index on a 500k-1M LOC codebase takes hours; incremental runs in minutes.
**Trade-offs:** Requires change detection logic and idempotent upsert semantics in both stores.

## Data Flow

### Ingestion Flow (Initial Full Index)

```
Legacy Codebase (Gradle multi-module)
    |
    v
OpenRewrite Gradle Plugin
    |-- LST Visitor (class/method/field/call extraction)
    |-- Lexicon Visitor (enum/Javadoc/comment term extraction)
    v
Ingestion Service (Spring Boot)
    |-- Graph Writer --> Neo4j (nodes + CALLS/EXTENDS/IMPORTS edges)
    |-- Embedding Service --> Open-source model (nomic-embed / all-MiniLM)
    |-- Vector Writer --> Qdrant (enriched chunks with risk metadata)
    v
Index Complete + CI trigger registered
```

### Incremental Indexing Flow (Post-CI)

```
CI Build completes (changed file list)
    |
    v
POST /ingest/incremental {changedFiles: [...]}
    |
    v
Ingestion Service
    |-- Re-parse changed files via OpenRewrite
    |-- Diff graph nodes (add/update/remove)
    |-- Re-embed changed chunks
    |-- Upsert Neo4j nodes
    |-- Upsert Qdrant points
    v
Index updated (minutes, not hours)
```

### Migration Orchestration Flow

```
Engineer selects module for migration
    |
    v
Migration Orchestrator
    |
    +-- (1) Trigger OpenRewrite Vaadin7->24 recipes (dry-run first)
    |       --> Produces candidate LST patch
    |
    +-- (2) Multi-Layer Retrieval
    |       --> Graph: expand dependency cone of target module
    |       --> Lexicon: match domain terms in scope
    |       --> Qdrant: similarity search constrained to cone
    |       --> Re-rank by risk score
    |       --> Returns: top-K enriched context chunks
    |
    +-- (3) Prompt Assembly
    |       --> System prompt: migration rules, Vaadin 24 API contracts
    |       --> Context: retrieved chunks + domain lexicon terms
    |       --> Task: "validate/improve this candidate patch"
    |
    +-- (4) Claude API Call
    |       --> Model: claude-opus-4-6 (reasoning) or sonnet (speed)
    |       --> Response: validated or improved patch + confidence score
    |
    +-- (5) Guardrail Validation
    |       --> Contract check (no signature changes)
    |       --> Security annotation check
    |       --> Validation rule check
    |       --> If any fail: REJECT, log, surface to engineer
    |
    +-- (6) Behavioral Diff
    |       --> Compile patched module (sandbox)
    |       --> Compare service outputs against baseline
    |       --> Compare SQL plans
    |       --> If regression detected: REJECT with diff report
    |
    +-- (7) PR Creation (on pass)
            --> Branch: migrate/module-name-timestamp
            --> Commit: patch + confidence report
            --> PR: description with lexicon coverage, risk score, AI confidence
```

### Query / Dashboard Flow

```
Dashboard User
    |
    v
REST API (Spring MVC)
    |
    +-- GET /graph/dependencies/{module}   --> Neo4j: dependency subgraph
    +-- GET /graph/risks                   --> Neo4j: risk-scored heatmap
    +-- GET /lexicon/coverage              --> Neo4j: USES_TERM coverage stats
    +-- GET /migration/status              --> Neo4j: migration job status aggregate
    +-- GET /migration/confidence          --> Neo4j: AI confidence histogram
    v
Dashboard renders KPIs
```

## Component Build Order (Dependencies)

The components have hard dependencies that dictate the build sequence:

```
Phase 1 — Foundation
  Neo4j + Qdrant containers  (no code dependencies)
  Spring Boot skeleton + Docker Compose wiring
      |
      v
Phase 2 — Ingestion
  OpenRewrite LST extraction (classes, methods, calls)
  Graph Writer (Neo4j node/edge upsert)
      |
      v
Phase 3 — Domain Enrichment
  Lexicon Extractor (requires graph nodes to attach USES_TERM/DEFINES_RULE edges)
  Embedding Service (requires enriched code entities to chunk and index)
  Vector Writer (Qdrant upsert; requires embeddings)
      |
      v
Phase 4 — Retrieval
  Multi-Layer Retrieval Pipeline (requires graph + vectors both populated)
  Risk scoring queries (requires dependency edges + cyclomatic complexity in graph)
      |
      v
Phase 5 — AI Orchestration
  Guardrails engine (can be developed without AI, as pure rule checks)
  Behavioral Diff framework (compile + compare; independent of retrieval)
  Migration Orchestrator (requires retrieval + guardrails + diff + Claude API)
      |
      v
Phase 6 — Governance
  Governance Dashboard (reads from graph + orchestration job table; no write path)
  Continuous Indexing CI hook (requires ingestion pipeline to be stable)
  Observability stack (Prometheus/Grafana; can be layered on at any phase)
```

**Rationale:** Each phase can be validated independently. The graph is queryable after Phase 2 even without embeddings. RAG retrieval is testable after Phase 3 before any AI call is made. Guardrails can be unit-tested with synthetic patches before the full orchestrator is wired up.

## Anti-Patterns

### Anti-Pattern 1: Mixing Ingestion and Query Paths in the Same Service

**What people do:** Build one "CodeService" that both parses source files and answers retrieval queries.
**Why it's wrong:** Ingestion is write-heavy, long-running, and I/O bound. Retrieval is read-heavy, latency-sensitive, and CPU bound. Coupling them causes resource contention and makes it impossible to scale or restart one without the other.
**Do this instead:** Separate Spring `@Service` beans with explicit boundaries. Ingestion writes to Neo4j/Qdrant. Retrieval only reads. Communicate via shared storage, not in-process calls.

### Anti-Pattern 2: Using Text-Based Search Instead of LST for Extraction

**What people do:** Parse Java files with regex or string splitting to extract class/method names and call edges.
**Why it's wrong:** Regex breaks on generics, annotations, multi-line expressions, comments. Produces incorrect edges that corrupt the knowledge graph and cause false-positive or false-negative migration patches.
**Do this instead:** Use OpenRewrite LST visitors for all structural extraction. The LST includes full type resolution — critical for Vaadin component identification.

### Anti-Pattern 3: Full Re-Index on Every CI Build

**What people do:** Re-parse and re-embed the entire codebase on every commit to keep the index fresh.
**Why it's wrong:** A 500k-1M LOC codebase with nomic-embed will take 30-90 minutes for full re-indexing. This blocks migration jobs and defeats continuous indexing value.
**Do this instead:** Implement incremental indexing from day one. Accept changed-file lists from CI. Use idempotent upsert semantics (node/point ID = stable hash of file path + entity name).

### Anti-Pattern 4: Calling Claude with Raw Source Files as Context

**What people do:** Dump entire Java files into the Claude prompt as context.
**Why it's wrong:** 500-2000 line Java files exceed useful context density. Claude spends attention on boilerplate. Costs are high. Precision is lower than targeted retrieval.
**Do this instead:** Use the retrieval pipeline to assemble a focused 8-15 chunk context: directly related methods, callers, domain terms, relevant migration examples. Smart chunking (by method, validation block, UI block) is essential.

### Anti-Pattern 5: Skipping Behavioral Diffing and Relying Only on Compilation

**What people do:** Accept a migration patch if it compiles without errors.
**Why it's wrong:** Vaadin 7→24 migration can produce code that compiles but changes runtime behavior — different event lifecycles, lazy vs. eager binding, validation timing differences.
**Do this instead:** Run behavioral diff against a baseline before promoting any patch to a PR. At minimum: compare service-layer return values with the same input fixtures, compare emitted SQL, compare validation exception paths.

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| Claude API (Anthropic) | HTTP REST via Spring `WebClient`; structured prompt/response | Use `claude-opus-4-6` for reasoning-heavy migration tasks; `claude-sonnet-4-6` for speed-sensitive validation passes. Implement retry with exponential backoff. |
| GitHub / GitLab | REST API for branch creation, commit, PR open | Use personal access token in Docker Compose secrets. Abstract behind `ScmPort` interface so VCS provider is swappable. |
| Vaadin legacy build | Gradle plugin invocation (OpenRewrite) | OpenRewrite recipes run as a Gradle task. ESMP triggers via `./gradlew rewrite:run` subprocess or Gradle Tooling API. |
| Open-source embedding model | Local HTTP (Ollama) or Java library (ONNX Runtime) | nomic-embed-text or all-MiniLM-L6-v2. Prefer local for cost control and latency predictability on bulk indexing. |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| Ingestion Service <-> Neo4j | Spring Data Neo4j repositories (SDN 7+) | Use `@Node` / `@Relationship` annotations. Define explicit Cypher for complex aggregations — don't rely on SDN query derivation for graph traversal. |
| Ingestion Service <-> Qdrant | Spring AI `QdrantVectorStore` | Auto-configured via `spring-ai-starter-vector-store-qdrant`. Initialize schema on first run. |
| Retrieval Pipeline <-> Neo4j | Direct `Neo4jClient` for Cypher traversal queries | Graph expansion queries are too complex for SDN derived finders. Use `Neo4jClient.query(...).fetch()`. |
| Retrieval Pipeline <-> Qdrant | Spring AI `VectorStore.similaritySearch()` with metadata filter | Filter by `class_name IN [...]` to constrain similarity search to graph-expanded cone. |
| Orchestrator <-> Retrieval | Direct Spring service injection | No async needed for MVP. Consider `CompletableFuture` for parallel graph + vector fetch if latency is a concern. |
| Orchestrator <-> Claude | Spring `WebClient` with streaming support | Stream response for long generations. Parse structured output (JSON mode) for confidence scores and patch boundaries. |
| Dashboard API <-> Knowledge stores | Read-only Spring Data Neo4j + custom Cypher | Dashboard never writes. Expose KPI endpoints; no direct Qdrant queries needed for dashboard (metadata stored in Neo4j too). |

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| Solo dev, 1 legacy codebase | Single Docker Compose stack. All services on one host. Neo4j Community, Qdrant single-node. Suitable for 500k-1M LOC. |
| Team (2-5 devs), multiple repos | Extract ingestion as a separate Spring Boot service with its own port. Add a job queue (Spring Batch or simple DB-backed queue) for migration jobs. |
| Enterprise, 10+ repos | Neo4j Enterprise (clustering), Qdrant distributed. Separate Docker Compose stacks per repo or adopt Kubernetes. Add a proper message broker (Kafka) for ingestion events. |

### Scaling Priorities

1. **First bottleneck:** Embedding generation during bulk ingestion. Fix: batch embedding calls, async processing, or Ollama GPU acceleration.
2. **Second bottleneck:** Neo4j complex traversal queries under concurrent migration jobs. Fix: add composite indexes on `(:Class {fqn})` and `(:Method {signature})`, cap concurrent migration workers.

## Sources

- Neo4j Codebase Knowledge Graph blog: https://neo4j.com/blog/developer/codebase-knowledge-graph/
- OpenRewrite official docs (LST, visitor pattern, recipe architecture): https://docs.openrewrite.org/
- GraphRAG hybrid retrieval (Neo4j VectorCypherRetriever): https://neo4j.com/blog/developer/graph-traversal-graphrag-python-package/
- GraphRAG survey — ACM: https://dl.acm.org/doi/10.1145/3777378
- Practical GraphRAG at enterprise scale (legacy code migration +15% over vector-only): https://arxiv.org/abs/2507.03226
- Spring AI Qdrant integration: https://docs.spring.io/spring-ai/reference/api/vectordbs/qdrant.html
- Vaadin 7/8 → 24 migration tooling (MTK Analyzer, Dragonfly Transpiler): https://vaadin.com/blog/how-we-guide-our-enterprise-customers-throughout-their-modernization-journey
- Agentic AI safety guardrails architecture: https://dextralabs.com/blog/agentic-ai-safety-playbook-guardrails-permissions-auditability/
- OpenRewrite large-scale refactoring patterns: https://blog.cronn.de/en/java/2025/10/23/openrewrite-for-refactoring.html

---
*Architecture research for: Enterprise Semantic Modernization Platform (ESMP)*
*Researched: 2026-03-04*
