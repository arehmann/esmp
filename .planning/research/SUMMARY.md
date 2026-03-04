# Project Research Summary

**Project:** Enterprise Semantic Modernization Platform (ESMP)
**Domain:** AI-assisted Java/Vaadin 7 → Vaadin 24 code modernization
**Researched:** 2026-03-04
**Confidence:** MEDIUM-HIGH

## Executive Summary

ESMP is an AI-assisted code modernization platform for migrating a large-scale (500k–1M LOC) Vaadin 7 Java codebase to Vaadin 24. Expert practitioners in this domain build such platforms as layered pipelines: AST-based structural extraction feeds a code knowledge graph and vector store; a GraphRAG hybrid retrieval layer assembles semantically rich context; an AI orchestration layer (backed by Claude) generates and validates migration patches; and a governance layer surfaces progress metrics and risk signals. The Salesforce and Aviator post-mortems converge on a clear industry consensus — human-in-the-loop review is non-negotiable, and deterministic guardrails must be semantic (not syntactic) to prevent silent regressions. OpenRewrite is the right engine for deterministic Java/Vaadin transformations but covers approximately 60% of Vaadin 7 patterns; Claude fills the gap for the remaining patterns, making the AI layer a first-class delivery mechanism, not a fallback.

The recommended stack is Spring Boot 3.5.11 / Java 21 as the ESMP runtime, Neo4j 5.x for the code knowledge graph, Qdrant for vector storage, Spring AI 1.1.2 for RAG orchestration, and the Anthropic Java SDK 2.15.0 for advanced Claude API operations. Local ONNX embeddings (all-MiniLM-L6-v2 via Spring AI Transformers) are strongly preferred over API-based embeddings at bulk indexing scale to control cost and latency. OpenRewrite runs as a Gradle plugin against the target brownfield project — not embedded in the ESMP runtime — to isolate classpath conflicts. Three databases serve distinct roles: Neo4j (code topology and domain lexicon), Qdrant (embedding vectors), and PostgreSQL (migration job state, audit trail, confidence ledger).

The key risks are: (1) knowledge graph staleness if incremental indexing is not built from the start; (2) LLM self-reported confidence being mistaken for a reliable merge gate; (3) scope creep across the platform's many ambitious components resulting in infrastructure without end-to-end migration output; (4) RAG retrieval returning wrong context if graph-first traversal is not implemented before vector-only similarity; and (5) semantic guardrail bypass if guardrails are implemented as text-diff or regex checks rather than AST-level invariant comparisons. Each risk has a clear prevention strategy tied to a specific roadmap phase.

---

## Key Findings

### Recommended Stack

The stack centers on the Spring Boot 3.5.11 ecosystem, which manages the majority of dependencies through its BOM (Spring Data Neo4j 8.0.3, Neo4j Java Driver 5.28.5, Testcontainers 1.20.x, Flyway 10.x). Spring AI 1.1.2 is imported as a second BOM and provides unified abstractions for Claude, Qdrant, and ONNX local embeddings — eliminating manual HTTP client coding for the three highest-complexity integrations. Java 21 is the correct LTS choice: Spring Boot 3.5.x fully supports it and virtual threads (`spring.threads.virtual.enabled=true`) provide concurrency for the CPU-bound AST extraction pipeline without Reactive complexity. Spring Boot 4.0 and Spring AI 2.0 milestones are explicitly excluded — they require pre-release JDK and break established autoconfiguration.

**Core technologies:**
- Java 21 (LTS): Runtime — virtual threads, pattern matching, Spring Boot 3.5 fully supported
- Spring Boot 3.5.11: Application framework — BOM manages all managed dependencies; native Docker Compose support
- Spring AI 1.1.2: AI orchestration, RAG, embeddings — unified ChatClient, VectorStore, EmbeddingModel abstractions
- Anthropic Java SDK 2.15.0: Direct Claude API — streaming, batching, token management beyond Spring AI abstractions
- Neo4j 5.x (Docker): Code knowledge graph — Cypher DSL, graph traversal, property graph model for AST entities
- Spring Data Neo4j 8.0.3: OGM — `@Node`/`@Relationship` annotations for schema-mapped entities; raw `Neo4jClient` for complex traversal queries
- Qdrant 1.x (Docker): Vector store — gRPC + REST, payload filtering, HNSW ANN; `QdrantVectorStore` via Spring AI
- Spring AI Transformers / ONNX: Local embeddings — `all-MiniLM-L6-v2` or `nomic-embed-text-v1.5`; avoids API cost at bulk scale
- OpenRewrite 8.x: AST-based Java/Vaadin transformations — runs as Gradle plugin on target codebase, not embedded in ESMP
- JavaParser 3.26+: Supplemental AST introspection — symbol resolution, type hierarchy for fine-grained graph node extraction
- Gradle Tooling API 8.x: Build model extraction — multi-module project structure, dependency configurations without build file parsing
- PostgreSQL 16.x (Docker): Relational metadata — migration job state, audit trail, confidence score ledger; Flyway for schema versioning
- Testcontainers 1.20.x: Integration testing — `@ServiceConnection` eliminates manual property wiring for Neo4j, Qdrant, PostgreSQL

**Key compatibility constraint:** Lombok must appear before MapStruct in `annotationProcessorPaths` or compilation fails. OpenRewrite runs only as a Gradle/Maven plugin against the target project — never as a runtime dependency of ESMP.

### Expected Features

The feature dependency chain is strict: AST Extraction → Structural Code Graph → Smart Chunking + Vector Indexing → Multi-layer RAG Pipeline → AI Orchestration → PR Generation. Guardrails are not optional at any point in this chain. Domain Lexicon extraction and Behavioral Diffing are high-value P2 additions that require the core pipeline to be operational first.

**Must have (table stakes — v1):**
- AST extraction engine (OpenRewrite LST visitors) — without this, nothing else works
- Structural code graph (Neo4j: Class/Method/Field nodes, CALLS/IMPORTS/EXTENDS/IMPLEMENTS edges) — foundational data model
- Dependency heatmap + risk scoring (cyclomatic complexity, fan-in/out) — required to select safe migration targets
- Smart chunking + vector indexing (Qdrant, ONNX embeddings) — semantic chunking by service method / UI block / validation block
- Basic GraphRAG retrieval pipeline (graph expansion + embedding similarity) — required for AI context assembly
- AI orchestration engine (OpenRewrite dry-run → RAG context → Claude prompt → validate → PR) — core value delivery
- Deterministic guardrails (no contract changes, no security annotation removal, no validation deletion) — non-negotiable safety gate
- Automated PR generation (branch + commit + PR via GitHub/GitLab API) — the tangible output engineers review
- Migration governance dashboard (% legacy API removed, modules migrated, AI confidence per module) — stakeholder visibility

**Should have (v1.x — add after core pipeline validated):**
- Domain Lexicon extraction and curation UI — adds semantic enrichment to RAG; required when retrieval precision proves insufficient
- Behavioral diffing framework — SQL output, service return values, validation behavior comparison; must handle non-deterministic output normalization
- Continuous re-indexing via CI hook — incremental indexing of changed files only; must be designed from Phase 2, not bolted on later
- Risk-prioritized migration scheduling — composite risk score to recommend module order
- Lexicon coverage metric in dashboard

**Defer (v2+):**
- Business concept graph visualization — architect-facing; not needed for first migration PRs
- Multi-layer RAG with full lexicon match layer — defer lexicon match layer until lexicon is sufficiently curated
- Advanced AI confidence scoring model — requires historical migration data to calibrate; v1 uses heuristics

**Anti-features to avoid:**
- Full automated rewrite without human approval (industry consensus: always require PR review)
- Natural language query interface over the graph (structured dashboard answers the key questions; NL adds LLM-over-graph complexity)
- Replacing or wrapping CI/CD pipelines (ESMP must coexist passively; never block the primary build)
- Reactive WebFlux as primary (Spring MVC + virtual threads is the right model for CPU-bound AST pipeline)

### Architecture Approach

ESMP follows a strict five-layer architecture: Ingestion Layer (AST Engine + Lexicon Extractor + CI Indexer) → Knowledge Layer (Neo4j graph + Qdrant vectors) → Retrieval Layer (hybrid GraphRAG pipeline) → AI Orchestration Layer (guardrails + behavioral diff + PR creation) → Governance Layer (REST API + dashboard + observability). Each layer has single-direction dependencies: ingestion writes to knowledge; retrieval only reads from knowledge; orchestration depends on retrieval but never on ingestion; governance is read-only. This separation prevents the most common anti-pattern (coupling ingestion and query paths in the same service), which causes resource contention between write-heavy long-running extraction and latency-sensitive retrieval.

**Major components:**
1. AST Engine (OpenRewrite visitors) — parses Java source into Lossless Semantic Trees; extracts structural facts (classes, methods, fields, call edges, Vaadin component usage)
2. Code Knowledge Graph (Neo4j) — stores structural entities, dependency edges, risk scores, and domain lexicon nodes; relationship types: CALLS, EXTENDS, IMPORTS, USES_TERM, DEFINES_RULE
3. Vector Store (Qdrant) — stores enriched code chunk embeddings with risk metadata payloads; chunked by semantic unit (service method, UI block, validation block)
4. Multi-Layer Retrieval Pipeline — graph expansion (dependency cone traversal) → lexicon match → embedding similarity constrained to cone → re-rank by risk score
5. Migration Orchestrator — full workflow: OpenRewrite dry-run → context retrieval → prompt assembly → Claude call → guardrail validation → behavioral diff → PR creation
6. Deterministic Guardrails — semantic invariant checks (authorization surface, validation rule coverage, service contract) using AST comparison, not text-diff
7. Behavioral Diff Engine — captures SQL output, service return values, validation behavior; normalizes timestamps and generated IDs; compares legacy vs migrated
8. Governance Dashboard (Spring MVC REST + web UI) — read-only KPI aggregation from Neo4j; migration progress, risk clusters, AI confidence histogram

**Key pattern:** GraphRAG hybrid retrieval (graph expansion first, vector similarity second, risk re-ranking third) is validated to achieve 15%+ precision improvement over vector-only retrieval for code migration tasks. Pure vector similarity retrieval is an anti-pattern for this domain.

### Critical Pitfalls

1. **LLM confidence scores are not reliable merge gates** — Build external composite confidence from: compilation success + test pass rate + guardrail checks + behavioral diff result. Never allow LLM self-reported confidence to be the primary gate for merge approval. Violating this causes silent behavioral regressions with high reported confidence.

2. **OpenRewrite covers ~60% of Vaadin 7 patterns** — Conduct a recipe coverage audit before building the AI orchestration layer. Categorize: patterns covered by existing recipes, patterns partially covered, patterns with no recipe coverage. Design the AI layer as a first-class planned delivery mechanism for coverage gaps, not as a fallback. Custom Vaadin 7 recipe authoring will be required.

3. **Knowledge graph goes stale without incremental indexing** — Design incremental indexing (file-hash-gated, CI-triggered) from Phase 2 of construction, not as a post-launch optimization. Full re-index of 500k–1M LOC takes 30–90 minutes; this blocks migration jobs. Store a file hash on every Neo4j node for O(changed files) stale detection.

4. **Scope creep produces infrastructure without end-to-end migration output** — Enforce a vertical slice constraint at every phase: each phase must produce at least one validated migration PR, even if narrow in scope. No phase is "infrastructure only." The governance dashboard and dependency heatmap must not be prioritized over the migration engine's end-to-end capability.

5. **Deterministic guardrails bypassed by semantic restructuring** — Implement guardrails as AST-level semantic invariant checks, not text-diff or regex pattern matching. The correct check for security annotations is not "does this annotation still exist?" but "does every endpoint retain equivalent authorization coverage?" Use OpenRewrite's LST for pre/post comparison. No bypass path in the architecture.

6. **RAG retrieval returns wrong context for complex components** — Retrieval pipeline must be graph-first (dependency cone traversal from Neo4j) before vector similarity. Vector-only retrieval rewards textual similarity, not structural coupling or business constraint relevance. Re-ranking by graph distance from migration target is required.

7. **Behavioral diffing appears done before it handles stateful code** — Define scope concretely before building: what behaviors are compared (SQL emitted, validation results, return values) and what is explicitly out of scope (UI rendering, session state). Build non-deterministic output normalization (timestamps, UUIDs, ordering) from day one. Test against real Vaadin 7 business logic methods, not toy examples.

---

## Implications for Roadmap

Based on the component build-order constraints from ARCHITECTURE.md and the vertical slice discipline from PITFALLS.md, a 6-phase structure is recommended:

### Phase 1: Foundation and AST Extraction
**Rationale:** All subsequent phases depend on the code knowledge graph. Nothing can be built without first parsing the codebase into structured entities. This phase also front-loads the OpenRewrite coverage audit — the most critical risk to identify early. A monolithic Spring Boot application is acceptable here; service extraction can happen later.
**Delivers:** Running Spring Boot application with Docker Compose (Neo4j, Qdrant, PostgreSQL); OpenRewrite LST extraction for a representative sample module; Neo4j populated with Class/Method/Field nodes and CALLS/IMPORTS/EXTENDS edges; coverage audit document for Vaadin 7 API patterns (covered / partial / gap).
**Features from FEATURES.md:** AST extraction engine, structural code graph (partial)
**Avoids:** Text-based parsing (anti-pattern 2 from ARCHITECTURE.md), missing coverage audit (Pitfall 2), full graph rebuild assumption without file hash tracking (Pitfall 3 prevention design)
**Research flag:** Needs deeper research — OpenRewrite Vaadin 7 recipe availability and custom recipe authoring are LOW confidence (forum sources only). Research-phase recommended.

### Phase 2: Knowledge Graph + Vector Indexing
**Rationale:** The knowledge graph and vector store must be fully populated before retrieval can be built. Smart chunking strategy (semantic unit boundaries) must be locked before any embeddings are written — changing the embedding model or chunking strategy after initial indexing requires full re-indexing. Incremental indexing design must happen here, not later.
**Delivers:** Full Neo4j graph for a sample module (dependency edges, risk scores, cyclomatic complexity); Qdrant collection with enriched code chunk embeddings (ONNX local model); file-hash-gated incremental indexing endpoint (`POST /ingest/incremental`); dependency heatmap with risk scoring.
**Features from FEATURES.md:** Structural code graph (complete), smart chunking + vector indexing, dependency heatmap + risk scoring, continuous re-indexing (design and initial implementation)
**Avoids:** Embedding model lock-in after initial index (Integration Gotcha), full rebuild on every CI run (Performance Trap), vector-only retrieval assumption (Pitfall 5 prevention)
**Research flag:** Standard patterns — Spring Data Neo4j, Qdrant integration via Spring AI are HIGH confidence. No research-phase needed.

### Phase 3: GraphRAG Retrieval Pipeline
**Rationale:** Retrieval must be validated as producing correct context before the AI orchestration layer is built on top of it. The retrieval pipeline is the most technically novel component: graph expansion + lexicon match + embedding similarity + risk re-ranking. Errors here propagate silently into AI output quality. This phase must end with a retrieval quality evaluation against real Vaadin 7 migration queries.
**Delivers:** Multi-layer retrieval pipeline (graph expansion → embedding similarity constrained to dependency cone → risk re-ranking); retrieval quality evaluation confirming graph-derived context appears in results for real target components; basic Domain Lexicon extraction (from enums, Javadoc, comments).
**Features from FEATURES.md:** Basic RAG pipeline, domain lexicon extraction (initial), smart chunking validation
**Avoids:** Vector-only retrieval (Pitfall 5), raw file dumps to Claude (Anti-Pattern 4 from ARCHITECTURE.md)
**Research flag:** Standard patterns for hybrid GraphRAG retrieval — HIGH confidence from Neo4j and Spring AI documentation. No research-phase needed.

### Phase 4: AI Orchestration + Guardrails
**Rationale:** With a validated retrieval pipeline, the AI orchestration layer can be built. Guardrails must be designed as semantic invariant checks (not text-diff) from day one — the architecture of the guardrail system is extremely difficult to change after it is integrated. This phase ends with the first end-to-end migration PR for a simple, low-risk Vaadin 7 component.
**Delivers:** Migration Orchestrator (OpenRewrite dry-run → RAG context → Claude prompt → validate → PR); deterministic guardrails (contract check, security annotation surface comparison, validation rule coverage); automated PR generation via GitHub/GitLab API; first validated migration PR for a simple Vaadin 7 component.
**Features from FEATURES.md:** AI orchestration engine, deterministic guardrails, automated PR generation, human-in-the-loop review gate
**Avoids:** LLM confidence as primary merge gate (Pitfall 1), syntactic guardrail bypass (Pitfall 7), scope creep (Pitfall 6 — must produce a working PR at phase end)
**Research flag:** Needs deeper research — Claude API prompt engineering for migration code generation, structured output parsing, and Anthropic SDK streaming patterns may benefit from a research-phase. Guardrail semantic invariant design for Vaadin security annotations is novel.

### Phase 5: Behavioral Diffing + Confidence Scoring
**Rationale:** Behavioral diffing validates the safety of migrations that pass syntactic guardrails. It must be built after the PR generation pipeline exists (there is nothing to diff without generated code). Confidence scoring must be composite and externally derived — this phase finalizes the confidence model and surfaces it correctly in the dashboard.
**Delivers:** Behavioral diff engine (SQL output comparison, service return value comparison, validation exception path comparison) with non-deterministic output normalization; composite confidence score (compilation + test pass + guardrail status + behavioral diff status — four separate indicators); updated governance dashboard surfacing decomposed confidence.
**Features from FEATURES.md:** Behavioral diffing framework, AI confidence scoring, governance dashboard (enhanced)
**Avoids:** False positive behavioral diffs from unhandled timestamps/UUIDs (Pitfall 4), single LLM confidence number in dashboard UX (UX Pitfall)
**Research flag:** Standard patterns for behavioral diffing scope and normalization. No research-phase needed, but scope must be defined before implementation begins (per PITFALLS.md guidance).

### Phase 6: Governance, CI Integration, and Domain Enrichment
**Rationale:** The final phase hardens the platform for ongoing operation: CI hook ensures the knowledge graph stays current as the legacy codebase continues active development; domain lexicon curation UI enables SME validation; governance dashboard reaches its full capability. Risk-prioritized migration scheduling becomes useful only once enough modules are analyzed to make scheduling decisions data-driven.
**Delivers:** Continuous re-indexing CI hook (changed files only, file-hash-gated); Domain Lexicon curation UI; complete governance dashboard (% migrated, risk clusters, lexicon coverage metric, AI confidence histogram); risk-prioritized migration scheduling; observability stack (Prometheus/Grafana).
**Features from FEATURES.md:** Continuous re-indexing, domain lexicon curation UI, lexicon coverage metric, risk-prioritized migration scheduling, governance dashboard (complete), observability
**Avoids:** Knowledge graph staleness in production (Pitfall 3), coexistence failure with active development (anti-feature), scope creep by deferring governance until core is working (Pitfall 6)
**Research flag:** Standard patterns — Prometheus/Grafana, Spring Boot Actuator, CI hook integration are HIGH confidence. No research-phase needed.

### Phase Ordering Rationale

- **Data layer before retrieval, retrieval before AI:** The hard dependency chain from FEATURES.md (AST → Graph → Vectors → RAG → Orchestration → PR) directly maps to Phases 1–4. Skipping or parallelizing these phases breaks downstream components.
- **Vertical slice at every phase end:** PITFALLS.md is emphatic — no phase should be "infrastructure only." Each phase through Phase 4 must produce a working migration output, even if narrow in scope. This is the most important constraint for a solo developer.
- **Guardrails before behavioral diffing:** Semantic guardrails catch the class of regressions that prevent safe deployment; behavioral diffing catches the subtler class that guardrails miss. Both are required but guardrails come first.
- **Coverage audit in Phase 1:** The OpenRewrite Vaadin 7 coverage gap (Pitfall 2) is the highest-risk unknown. Discovering a major coverage gap in Phase 4 is catastrophic; discovering it in Phase 1 allows the AI layer design to account for it.
- **Incremental indexing in Phase 2:** PITFALLS.md is explicit — "incremental indexing must be designed from the start." Building it in Phase 6 means months of stale-graph operation during active migration work.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 1 (OpenRewrite + Vaadin 7):** Recipe coverage for Vaadin 7 APIs is LOW confidence (forum sources). Custom recipe authoring scope is unknown. Research-phase strongly recommended before committing to Phase 1 scope.
- **Phase 4 (AI Orchestration, Claude prompt design):** Prompt engineering for structured migration output, confidence score extraction from Claude responses, and Anthropic SDK streaming integration may benefit from research-phase. The semantic guardrail design for authorization surface comparison is novel with no established reference implementation.

Phases with standard, well-documented patterns (research-phase not needed):
- **Phase 2 (Neo4j + Qdrant):** Spring Data Neo4j and Spring AI Qdrant integration are HIGH confidence via official documentation.
- **Phase 3 (GraphRAG retrieval):** Neo4j graph traversal + Qdrant similarity search combination is well-documented in Neo4j's own GraphRAG materials.
- **Phase 5 (behavioral diffing):** Differential testing patterns are established; scope definition is the only novel work.
- **Phase 6 (CI + governance + observability):** All standard Spring Boot / Prometheus / Grafana patterns, HIGH confidence.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Core technologies (Spring Boot 3.5.11, Spring AI 1.1.2, Neo4j 5.x, Qdrant, Anthropic SDK 2.15.0) verified from official release notes and documentation. Version compatibility matrix is HIGH confidence. One exception: OpenRewrite plugin version is MEDIUM (secondary source). |
| Features | MEDIUM-HIGH | Table stakes features and dependency ordering are HIGH confidence via official Vaadin, Moderne, and OpenRewrite documentation plus Salesforce engineering post-mortem. Differentiator feature value proposition is MEDIUM confidence via competitive analysis. |
| Architecture | MEDIUM-HIGH | Five-layer architecture and GraphRAG hybrid retrieval pattern are verified via Neo4j documentation, ACM survey, and multiple practitioner sources. The specific ESMP combination of all layers is novel — no reference implementation exists to validate end-to-end. |
| Pitfalls | HIGH (critical) / MEDIUM (integration) | Critical pitfalls (LLM confidence, OpenRewrite coverage gaps, guardrail bypass) are HIGH confidence — each is backed by empirical research, post-mortems, or direct library documentation. Integration gotchas and performance traps are MEDIUM confidence from practitioner experience sources. |

**Overall confidence:** MEDIUM-HIGH

### Gaps to Address

- **OpenRewrite Vaadin 7 recipe coverage:** The exact set of Vaadin 7 API patterns covered by existing OpenRewrite recipes is unknown. The forum source indicates Vaadin 7→24 recipes are "in progress, not fully published." A hands-on coverage audit against a sample of the target codebase is the only reliable way to resolve this. Address in Phase 1 planning / research-phase.
- **Behavioral diffing scope for Vaadin 7 UI code:** Vaadin 7's server-side UI component model has stateful, side-effecting rendering behavior that makes behavioral diffing significantly harder than for pure service methods. The exact scope of what can be reliably compared (SQL output, validation results) vs what cannot (UI state, session mutations) needs to be defined empirically. Address during Phase 5 scope definition.
- **Claude API cost model at migration scale:** Token usage per migration attempt (prompt + context + response) is uncharacterized. At scale across a large module set, API costs may be significant. Budget limits and cost tracking must be implemented before production-scale migration runs. Address during Phase 4 planning.
- **Vaadin 7 custom component patterns in target codebase:** AbstractField extensions, custom data binding implementations, and legacy event listener patterns may have no recipe coverage and require novel AI prompt strategies. Discovering the scope of these patterns is part of the Phase 1 coverage audit.

---

## Sources

### Primary (HIGH confidence)
- Spring Boot 3.5.11 release notes (spring.io, Feb 2026) — version confirmation, virtual thread support
- Spring AI 1.1 GA announcement (spring.io, Nov 2025) — Qdrant VectorStore, AnthropicChatModel, ONNX embeddings
- Spring Data Neo4j docs (docs.spring.io) — SDN 8.0.3, Neo4j 5.23+ targeting, `@Node`/`@Relationship` annotations
- Anthropic Java SDK releases (GitHub, Feb 2026) — SDK 2.15.0, Claude Sonnet 4.6
- Qdrant Java client releases (GitHub, Dec 2025) — io.qdrant:client 1.17.0
- Vaadin Modernization Toolkit (vaadin.com) — official Vaadin 7→24 migration feature set and automation capabilities
- Salesforce Engineering: AI-Driven Refactoring post-mortem — human-in-the-loop requirement, 4-month migration case study
- OpenRewrite official docs — LST visitor pattern, recipe architecture, FAQ on completeness vs correctness
- Neo4j GraphRAG documentation — hybrid graph + vector retrieval, VectorCypherRetriever patterns
- GraphRAG survey (ACM, 2025) — hybrid retrieval precision improvement benchmarks
- Spring AI ONNX embeddings docs — TransformersEmbeddingModel, local inference

### Secondary (MEDIUM confidence)
- Moderne.ai blog + product site — enterprise feature set, DevCenter, multi-repo analysis
- Aviator blog: Code Migration with Assisted AI Agents — anti-patterns for full automation
- ArgonDigital blog: AI for Business Rule Extraction — domain lexicon extraction pattern
- OpenRewrite + Moderne overview (Moderne blog) — Moderne vs OpenRewrite comparison
- Deterministic Guardrails article (tech.hub.ms) — guardrail patterns, hybrid deterministic + LLM approach
- Neo4j Codebase Knowledge Graph blog — graph schema patterns for code analysis
- Practical GraphRAG at enterprise scale (arXiv 2025) — +15% precision improvement over vector-only for code tasks
- XDEV: Vaadin + OpenRewrite modernization — custom Vaadin recipes exist but scope is limited
- 1up.ai + arXiv: LLM confidence score calibration research — empirical basis for Pitfall 1

### Tertiary (LOW confidence)
- Vaadin forum: version upgrade recipes thread — community confirmation that Vaadin 7→24 OpenRewrite recipes are "in progress, not fully published." Implication: significant custom recipe authoring will be required. Needs empirical validation in Phase 1.

---
*Research completed: 2026-03-04*
*Ready for roadmap: yes*
