# Feature Research

**Domain:** Enterprise Code Modernization / AI-Assisted Migration Platform (Vaadin 7 → Vaadin 24)
**Researched:** 2026-03-04
**Confidence:** MEDIUM-HIGH (core features HIGH via official docs; differentiator ordering MEDIUM via competitive analysis)

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features a migration engineer assumes exist. Missing these = platform is not usable as a migration tool.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| AST-based code extraction | Every modernization tool operates on parsed structure, not raw text — raw text matching produces incorrect transforms | HIGH | OpenRewrite LST is the right engine; Java parser + Vaadin-specific visitor rules required |
| Structural code graph (classes, methods, fields, dependencies) | Engineers need to understand what depends on what before touching anything | HIGH | Neo4j as graph DB; nodes for Class/Method/Field/Module, edges for CALLS/IMPORTS/EXTENDS/IMPLEMENTS |
| Dependency heatmap with risk scoring | Migration teams need to know which files are high-risk (high fan-in, many DB writes, complex conditionals) before scheduling work | MEDIUM | Cyclomatic complexity + fan-in/out + DB write detection are well-understood metrics |
| Module-level migration progress tracking | Without visibility into "how much is migrated", teams can't report to management or know when to accelerate | MEDIUM | Percentage of legacy Vaadin API removed per module; counts by class/method |
| Automated PR generation for migration changes | The whole point of the platform is producing validated, reviewable code changes — not just analysis | HIGH | OpenRewrite recipe output → Git branch → PR via GitHub/GitLab API |
| Human-in-the-loop review gate | Industry consensus: full automation without human sign-off is a known failure mode (hallucinated APIs, lost business rules) | LOW | Enforce PR review requirement; never auto-merge |
| Deterministic guardrails (no contract changes, no security annotation removal) | Without hard constraints, AI-generated code silently drops validation logic or security checks | HIGH | Rule engine pre-validation before PR opened; configurable blocklist of forbidden transforms |
| Basic regression test execution on generated code | Migrate-and-ship without running tests is reckless; the platform must verify generated code compiles and passes existing tests | MEDIUM | Trigger CI suite against generated branch before PR opens |
| Continuous re-indexing on code change | Legacy codebase continues active development — a stale graph produces wrong recommendations | MEDIUM | CI hook re-extracts changed files, updates graph + embeddings incrementally |
| Migration governance dashboard | Stakeholders and engineers need single-pane-of-glass: % migrated, risk clusters, AI confidence by module | MEDIUM | Web UI backed by Neo4j aggregation queries; Grafana-style layout acceptable |

### Differentiators (Competitive Advantage)

Features that set ESMP apart from general-purpose tools like Vaadin Modernization Toolkit, Moderne.ai, or generic AI coding assistants.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Domain Lexicon extraction and curation | Captures business meaning embedded in code (enums, Javadoc, method names, DB column names) — competitors do structural analysis but not semantic/domain analysis | HIGH | Extract terms from Java symbols, DB schema, comments; build DEFINES_RULE / USES_TERM graph edges; expose curation UI for domain expert validation |
| Multi-layer RAG retrieval pipeline | Context-aware retrieval that combines graph traversal + lexicon match + embedding similarity + risk prioritization — far more precise than naive vector search | HIGH | Graph expansion from focal class → lexicon-enriched chunk reranking → embedding similarity fallback; produces ranked context for AI prompt |
| AI confidence scoring per migration | Every generated PR carries an explicit confidence score based on RAG retrieval quality, complexity metrics, and guardrail pass rate | MEDIUM | Aggregated from: retrieval score, cyclomatic complexity, guardrail checks passed/failed, behavioral diff delta |
| Behavioral diffing framework | Compares service output, SQL query shape, and validation behavior between legacy and migrated code — catches semantic regressions that unit tests miss | HIGH | Capture HTTP response snapshots, SQL query logs, validation error sets against known inputs; diff legacy vs migrated |
| Smart chunking strategy for code indexing | Chunks by semantic unit (service method, validation block, UI component, business rule) rather than fixed token windows — produces better RAG retrieval quality | MEDIUM | Custom chunking logic in indexer; Vaadin UI block = one chunk, Spring service method = one chunk |
| Risk-prioritized migration scheduling | Platform recommends which modules to migrate first vs last based on dependency risk, change frequency, and confidence score | MEDIUM | Sortable module list with composite risk score; accounts for fan-in, complexity, active development frequency |
| Business concept graph visualization | Engineers and architects can explore how domain concepts (Customer, Order, Invoice) relate across modules — not just technical dependencies | MEDIUM | Second graph layer on top of structural graph; nodes enriched with domain lexicon terms; interactive Neo4j Bloom or custom D3 visualization |
| Lexicon coverage metric | Measures what percentage of the codebase's domain concepts are captured and curated — a leading indicator of migration quality | LOW | Simple count of USES_TERM edges vs total classes; surfaced in governance dashboard |
| Coexistence with active development | Platform operates as a passive intelligence layer — never blocks developer commits, never requires codebase freeze | MEDIUM | CI integration read-only; ESMP does not sit in the critical path of the primary build |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem valuable but create more problems than they solve for this domain.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Full automated rewrite without human approval | "Ship faster — remove the review step" | Industry consensus: AI hallucinates APIs, silently drops validation rules, loses business logic edge cases; Salesforce's own post-mortems confirm human review is non-negotiable | Enforce required PR review; use confidence scoring to flag low-confidence PRs for deeper review |
| Real-time collaborative editing of migrated code | Feels like a natural IDE experience | Not the use case; the migration engineer is solo; adds OAuth, WebSocket, conflict resolution complexity for zero user benefit | Single-user web dashboard; export generated code to IDE for editing |
| Business rule inference without domain expert validation | "AI should figure out what rules mean automatically" | AI infers patterns, not intent; critical regulatory or compliance rules mis-inferred = production defect; must have human SME in the loop | Domain Lexicon curation UI where SMEs validate extracted terms and rules |
| Replacing or wrapping CI/CD pipelines | "Integrate ESMP into the main build pipeline as a gate" | ESMP must coexist without disruption; blocking the primary pipeline on migration analysis introduces risk and team friction | ESMP is a parallel layer; CI hook triggers re-indexing passively without blocking main pipeline |
| Mobile or tablet UI | Seems like a good idea for executive dashboards | Solo migration engineer works at a desktop; adds responsive design complexity with no real user benefit | Web dashboard optimized for desktop-first; responsive only if needed later |
| Automatic dependency version pinning | "Also manage our dependency upgrades" | Scope creep — ESMP knows about the code structure and migration, not general dependency management; overlap with Dependabot/Renovate which teams already have | Scope ESMP strictly to Vaadin 7 → 24 migration path; defer general dependency management to dedicated tools |
| Natural language query interface over the graph | "Chat with your codebase" | Sounds compelling, but adds LLM-over-graph complexity; the governance dashboard and visualizations already answer the key questions engineers need | Structured dashboard queries, Cypher-backed endpoints; add NL query only if structured views prove insufficient after validation |

---

## Feature Dependencies

```
AST Extraction Engine
    └──requires──> Structural Code Graph (nodes + edges in Neo4j)
                       └──requires──> Dependency Heatmap + Risk Scoring
                       └──requires──> Smart Chunking Strategy
                                          └──requires──> Vector Indexing (Qdrant)
                                                             └──requires──> Multi-layer RAG Pipeline
                                                                                └──requires──> AI Orchestration Engine
                                                                                                   └──requires──> Automated PR Generation
                                                                                                   └──requires──> Deterministic Guardrails
                                                                                                   └──requires──> AI Confidence Scoring

Domain Lexicon Extraction
    └──requires──> AST Extraction Engine (symbols, Javadoc, enums)
    └──enhances──> Multi-layer RAG Pipeline (lexicon match layer)
    └──enhances──> Business Concept Graph Visualization

Behavioral Diffing Framework
    └──requires──> Automated PR Generation (needs generated code to diff against)
    └──enhances──> AI Confidence Scoring (behavioral delta feeds score)

Continuous Re-indexing
    └──requires──> Structural Code Graph
    └──requires──> Vector Indexing
    └──enhances──> Migration Governance Dashboard (keeps metrics current)

Migration Governance Dashboard
    └──requires──> Structural Code Graph (aggregation queries)
    └──requires──> AI Confidence Scoring (per-module scores)
    └──enhances──> Risk-prioritized Migration Scheduling

Deterministic Guardrails ──conflicts──> Full Automated Rewrite (guardrails enforce human review gate)
```

### Dependency Notes

- **AST Extraction requires Structural Code Graph:** You cannot build the graph without parsing the code into structured nodes/edges first. The graph is the output of AST extraction.
- **Smart Chunking requires Structural Code Graph:** Chunking by semantic unit (service method, UI block) requires knowing the structure; raw file-based chunking degrades RAG quality significantly.
- **Multi-layer RAG Pipeline requires Vector Indexing AND Structural Code Graph:** The multi-layer pipeline combines graph traversal (Neo4j) and embedding similarity (Qdrant); either alone produces weaker retrieval.
- **Domain Lexicon enhances RAG Pipeline:** Lexicon-enriched chunks rank higher in retrieval; the lexicon match layer is an optional but high-value addition to the pipeline.
- **Behavioral Diffing requires PR Generation:** The diff framework needs both legacy execution traces and generated code execution traces; it cannot operate without migrated code to compare.
- **Deterministic Guardrails conflicts with Full Automated Rewrite:** The guardrail layer explicitly enforces a human review gate; auto-merge bypasses the core safety mechanism.

---

## MVP Definition

### Launch With (v1)

Minimum viable product — enough to produce a first validated migration PR for a single module.

- [ ] AST extraction engine using OpenRewrite — without this, nothing else works
- [ ] Structural Code Graph (Neo4j: classes, methods, fields, CALLS/IMPORTS/EXTENDS edges) — the foundational data model
- [ ] Dependency heatmap + risk scoring (cyclomatic complexity, fan-in/out) — needed to choose the right module to migrate first
- [ ] Smart chunking + vector indexing (Qdrant with open-source embeddings) — required for RAG retrieval
- [ ] Basic RAG pipeline (graph expansion + embedding similarity) — required for AI context building
- [ ] AI orchestration engine (trigger OpenRewrite → retrieve RAG context → build prompt → submit to Claude → validate → open PR) — core value delivery
- [ ] Deterministic guardrails (no contract changes, no security annotation removal, no validation deletion) — non-negotiable safety gate
- [ ] Automated PR generation — the tangible output engineers review
- [ ] Basic migration governance dashboard (% legacy API removed, modules migrated, AI confidence per module) — needed for tracking progress

### Add After Validation (v1.x)

Features to add once core pipeline produces correct, reviewable PRs.

- [ ] Domain Lexicon extraction and curation UI — add when RAG precision proves insufficient without semantic enrichment
- [ ] Behavioral diffing framework — add when migration volume justifies automated behavioral regression checking
- [ ] Continuous re-indexing via CI — add once codebase churn causes graph staleness to produce incorrect recommendations
- [ ] Risk-prioritized migration scheduling — add once enough modules are analyzed to make scheduling decisions data-driven
- [ ] Lexicon coverage metric in dashboard — add alongside Domain Lexicon feature

### Future Consideration (v2+)

Features to defer until the core migration pipeline is proven and adoption grows.

- [ ] Business concept graph visualization — powerful for architects, but not needed for first migration PRs
- [ ] Multi-layer RAG with full lexicon match layer — defer the lexicon match layer until the lexicon is sufficiently curated to add signal rather than noise
- [ ] Advanced confidence scoring model — v1 uses simple heuristics; a trained model requires historical migration data to calibrate

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| AST Extraction Engine | HIGH | HIGH | P1 |
| Structural Code Graph | HIGH | HIGH | P1 |
| Deterministic Guardrails | HIGH | MEDIUM | P1 |
| AI Orchestration Engine | HIGH | HIGH | P1 |
| Automated PR Generation | HIGH | MEDIUM | P1 |
| Smart Chunking + Vector Indexing | HIGH | MEDIUM | P1 |
| Basic RAG Pipeline | HIGH | HIGH | P1 |
| Dependency Heatmap + Risk Scoring | HIGH | MEDIUM | P1 |
| Migration Governance Dashboard | HIGH | MEDIUM | P1 |
| Continuous Re-indexing via CI | HIGH | MEDIUM | P2 |
| Domain Lexicon Extraction | HIGH | HIGH | P2 |
| Domain Lexicon Curation UI | MEDIUM | MEDIUM | P2 |
| Behavioral Diffing Framework | HIGH | HIGH | P2 |
| AI Confidence Scoring | MEDIUM | MEDIUM | P2 |
| Risk-prioritized Migration Scheduling | MEDIUM | LOW | P2 |
| Lexicon Coverage Metric | MEDIUM | LOW | P2 |
| Business Concept Graph Visualization | MEDIUM | MEDIUM | P3 |
| Multi-layer RAG (lexicon match layer) | HIGH | MEDIUM | P3 |

**Priority key:**
- P1: Must have for launch — platform is not usable without these
- P2: Should have — adds correctness, safety, or significant user efficiency
- P3: Nice to have — competitive polish, defer until core is validated

---

## Competitor Feature Analysis

| Feature | Vaadin Modernization Toolkit | Moderne.ai | ESMP (Our Approach) |
|---------|------------------------------|------------|---------------------|
| Vaadin-specific migration | Yes — purpose-built for Vaadin 7/8 → 24 | No — general Java modernization | Yes — Vaadin 7 → 24 is the explicit migration path |
| AST-based transformation | Java-to-Java transpiler, rule-based | OpenRewrite LST (lossless semantic tree) | OpenRewrite recipes + custom Vaadin visitor rules |
| Automation percentage | Up to 85% of UI code automated | Varies by recipe | Target: high automation with human-in-the-loop gating |
| Code analysis before commit | Free Analyzer plugin, dependency detection | Impact analysis, data tables across repos | Dependency heatmap + risk scoring per module |
| Multi-repo / multi-module support | Single-project focused | Multi-repo at scale (thousands of repos) | Single large multi-module Gradle project |
| Governance dashboard | Migration reports, effort estimates | DevCenter dashboard, metrics by team/BU | Migration dashboard: % migrated, risk clusters, AI confidence |
| Business rule / domain extraction | None (structural only) | None (structural only) | Domain Lexicon extraction + curation (differentiator) |
| Semantic / RAG-based context | None | None — deterministic recipes only | Multi-layer RAG pipeline with graph + lexicon + embeddings |
| AI confidence scoring | None | None | Per-module confidence score based on retrieval + guardrails |
| Behavioral regression diffing | None | None | Behavioral diffing framework (service output, SQL, validation) |
| Coexistence with active development | No CI integration noted | CI integration via Moderne CLI | Passive CI hook — never blocks primary build |
| Deployment model | SaaS / IDE plugin | SaaS + on-prem (DX Edition) | Docker Compose — local/dev, self-hosted |

---

## Sources

- [Vaadin Modernization Toolkit](https://vaadin.com/modernization-toolkit) — official feature list and automation capabilities (HIGH confidence)
- [Overview of OpenRewrite and Moderne](https://www.moderne.ai/blog/overview-of-openrewrite-and-moderne) — Moderne vs OpenRewrite feature comparison (HIGH confidence)
- [Moderne: The Agent Tools Company](https://www.moderne.ai) — enterprise feature set, DevCenter, multi-repo analysis (HIGH confidence)
- [Moderne raises $30M — TechCrunch](https://techcrunch.com/2025/02/11/moderne-raises-30m-to-solve-technical-debt-across-complex-codebases/) — product positioning and enterprise use (MEDIUM confidence)
- [How AI-Driven Refactoring Cut Legacy Code Migration to Just 4 Months — Salesforce Engineering](https://engineering.salesforce.com/how-ai-driven-refactoring-cut-a-2-year-legacy-code-migration-to-4-months/) — real-world enterprise migration with human-in-the-loop requirement (HIGH confidence)
- [Deterministic Guardrails for AI-Generated Code](https://tech.hub.ms/2025-11-18-Deterministic-Guardrails-for-AI-Generated-Code-Why-Observability-and-Smarter-Linters-Matter.html) — guardrail patterns, hybrid deterministic + LLM approach (MEDIUM confidence)
- [Using AI for Business Rule Extraction from Legacy Systems — ArgonDigital](https://argondigital.com/blog/general/using-ai-for-business-rule-extraction-from-legacy-systems/) — domain/lexicon extraction pattern (MEDIUM confidence)
- [Solving the Nasty Code Migration Problem with Assisted AI Agents — Aviator](https://www.aviator.co/blog/solving-the-nasty-code-migration-problem-with-assisted-ai-agents/) — anti-patterns for full automation (MEDIUM confidence)
- [Automating migration from Vaadin 8 to Vaadin 23/24 — Vaadin Webinar](https://pages.vaadin.com/webinar-v8-upgrade-automation) — Vaadin-specific migration automation scope (HIGH confidence)
- [Enterprise RAG: How to Build a RAG System — Azumo](https://azumo.com/artificial-intelligence/ai-insights/build-enterprise-rag-system) — enterprise RAG feature expectations (MEDIUM confidence)
- [Building a Graph-Based Code Analysis Engine — CodePrism](https://rustic-ai.github.io/codeprism/blog/graph-based-code-analysis-engine/) — code graph architecture patterns (MEDIUM confidence)

---
*Feature research for: Enterprise Semantic Modernization Platform (ESMP) — Vaadin 7 → Vaadin 24*
*Researched: 2026-03-04*
