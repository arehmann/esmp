# Pitfalls Research

**Domain:** Enterprise Code Modernization Platform — AI-assisted migration, code knowledge graph, RAG-based code analysis
**Researched:** 2026-03-04
**Confidence:** HIGH (critical pitfalls) / MEDIUM (integration gotchas, performance traps)

---

## Critical Pitfalls

### Pitfall 1: LLM Confidence Scores Are Lies

**What goes wrong:**
Claude (or any LLM) returns a confidence score for generated migration code, the platform surfaces it as a reliability metric, and a developer merges a PR that quietly breaks business logic. The platform gave 85% confidence on code that was subtly wrong.

**Why it happens:**
LLMs are optimized for fluency, not factual accuracy. GPT-4 research found it assigns its highest confidence score to 87% of responses — including wrong ones. The model cannot reliably self-assess because it does not know what it does not know. Token-level probability does not predict answer-level correctness. When you ask the model to score its own output, you are asking it to grade its own homework.

**How to avoid:**
Never use LLM self-reported confidence scores as the primary signal for "safe to merge." Build external confidence signals instead: compilation success, test pass rate, behavioral diff results, static analysis clean, OpenRewrite guardrail pass, and zero security annotation removals. A composite score built from deterministic checks is trustworthy. A score Claude returns is not. Display LLM confidence as a secondary indicator only, clearly labeled as advisory.

**Warning signs:**
- The platform says "AI confidence: 92%" but no tests ran.
- Confidence scores correlate with PR merge rate instead of with post-merge defect rate.
- No external validation gates sit between AI generation and the "approve" button.

**Phase to address:**
AI Orchestration phase — before any PR is auto-opened. Confidence scoring architecture must be designed here, not bolted on later.

---

### Pitfall 2: OpenRewrite Covers ~60% of Vaadin 7 Patterns, Not 100%

**What goes wrong:**
The platform treats OpenRewrite as the complete migration engine. It handles what it can, leaving the rest silently unhandled. The migration "finishes" but the output code still has legacy Vaadin 7 APIs, custom component wiring, or event listener patterns that no recipe covers.

**Why it happens:**
OpenRewrite recipes are designed for correctness over completeness. For standard Spring and Java migrations, coverage is high. For Vaadin 7 → Vaadin 24, the API surface changed fundamentally (server-side rendering → Lit/Web Components, custom Java UI APIs → Vaadin Flow). No publicly documented OpenRewrite recipes exist specifically for Vaadin 7. The platform assumes recipe coverage before confirming it empirically.

**How to avoid:**
Before building the AI orchestration layer, do a recipe coverage audit: run OpenRewrite against a representative sample of Vaadin 7 code and categorize what transforms, what partially transforms, and what does not. Build a coverage gap registry. Design the AI layer explicitly to handle the gap patterns that OpenRewrite cannot — not as a fallback, but as a first-class planned responsibility.

**Warning signs:**
- No coverage audit has been done before Phase 1 completes.
- Migration pipeline is designed as "OpenRewrite then done" without an AI gap-fill stage.
- Custom Vaadin 7 components, AbstractField extensions, or legacy data binding patterns exist in the target codebase with no planned recipe coverage.

**Phase to address:**
OpenRewrite AST extraction phase — coverage audit must happen here, not during AI orchestration phase.

---

### Pitfall 3: The Knowledge Graph Goes Stale While the Codebase Keeps Evolving

**What goes wrong:**
The graph is built once against the codebase snapshot. The legacy codebase continues active development (bug fixes, new features). Six weeks later, the graph reflects a version of the code that no longer exists. AI recommendations reference deleted methods, wrong call chains, and outdated dependency paths. Migration PRs conflict with unrelated in-flight changes.

**Why it happens:**
Full re-indexing of 500k–1M LOC takes time and is treated as an expensive one-time operation. Incremental update systems are non-trivial to build — knowing which graph nodes to invalidate, re-extract, and re-embed when a file changes requires careful dependency tracking. The easier path is to skip it.

**How to avoid:**
Design incremental indexing from the start, not as a later optimization. The CI integration (git hook or build trigger) must re-extract only changed files, invalidate affected graph nodes, re-embed changed code chunks, and leave unaffected graph regions intact. GraphRAG's incremental update approach (introduced in v0.4.0) provides a model: update without rebuilding the entire graph. Track a file hash per node in Neo4j so stale detection is O(changed files), not O(codebase).

**Warning signs:**
- No file hash or last-indexed timestamp stored in Neo4j nodes.
- No CI trigger defined for graph re-indexing.
- "We'll add incremental updates later" appears in planning.
- Migration recommendations reference methods that are not in the current codebase.

**Phase to address:**
CI integration phase — incremental indexing must be part of the first CI pipeline build, not a post-launch concern.

---

### Pitfall 4: Behavioral Diffing Is Declared Complete Before It Is Useful

**What goes wrong:**
The platform ships a "behavioral diffing framework" that compares method outputs before and after migration. In practice, it is only tested on stateless utility methods. When applied to real Vaadin 7 UI code — which has side effects, session state, DB writes, event callbacks, and UI state mutations — the diff produces false positives on every run (timestamps, generated IDs, session tokens) and catches nothing meaningful.

**Why it happens:**
Differential testing for stateful, side-effecting code is fundamentally harder than for pure functions. The "looks done but isn't" trap: a working diff framework for pure functions is demonstrably running, but does not provide the validation guarantees needed for real migration scenarios. Normalization of non-deterministic outputs (timestamps, UUIDs, ordering) is typically skipped in first pass.

**How to avoid:**
Define behavioral diff scope concretely before building: what specific behaviors will be compared (SQL emitted, validation results, return values)? What is explicitly out of scope (UI rendering, session state)? Build normalization for non-deterministic outputs from day one. Test the diff framework on actual Vaadin 7 business logic methods, not toy examples. A diff framework is only as useful as the behaviors it can reliably compare.

**Warning signs:**
- Behavioral diff demo only shows pure function comparisons.
- No handling for timestamps, generated IDs, or ordering variability in outputs.
- Diff test suite uses only unit-test-sized examples, not realistic service methods.
- Framework reports 0 differences on a known-modified file.

**Phase to address:**
Behavioral diffing and guardrails phase — define scope and normalization before implementation begins.

---

### Pitfall 5: RAG Retrieval Retrieves the Wrong Context for Complex Migrations

**What goes wrong:**
The embedding similarity search finds "semantically similar" code chunks, but for a complex Vaadin 7 form component with multiple interacting validators, event listeners, and data source bindings, the most similar chunks are superficially similar but from unrelated contexts. The AI generates a migration based on wrong examples and misses the actual pattern to follow.

**Why it happens:**
Vector-only retrieval is semantic — it rewards textual/structural similarity without understanding usage context or call graph relationships. For code, what matters is: "what calls this?" "what does this depend on?" "what business rules constrain this?" — not "what code looks structurally similar?" Cosine similarity is not a proxy for relevant context for migration purposes.

**How to avoid:**
The retrieval pipeline must be graph-first, vector-second. Use the code knowledge graph to walk the call graph outward from the target component first (direct callers, dependencies, business rules attached). Only then use vector similarity to find migration examples for the specific pattern being transformed. Implement reranking that scores chunks by their graph distance from the migration target, not just their embedding similarity. Build hybrid retrieval (keyword + vector + graph expansion) rather than vector-only.

**Warning signs:**
- Retrieval pipeline is vector similarity only, with no graph traversal component.
- No reranking step between retrieval and context assembly.
- Context window filling strategy uses top-K by cosine score without any business context or graph proximity weighting.
- AI generates migrations that are syntactically plausible but miss domain-specific constraints visible in the graph.

**Phase to address:**
RAG and retrieval pipeline phase — graph-first retrieval architecture must be locked before building the AI orchestration layer.

---

### Pitfall 6: Scope Creep Fragments Delivery for a Solo Developer

**What goes wrong:**
The platform is extremely ambitious: graph extraction, domain lexicon, RAG pipeline, AI orchestration, behavioral diffing, CI integration, governance dashboard, dependency heatmap — all for one developer. Each component is 80% built but nothing is end-to-end functional. After six months, there is impressive infrastructure but zero migration PRs produced.

**Why it happens:**
Each component is genuinely interesting and architecturally important. A solo developer moves laterally across the system, solving each hard problem in turn. Without a forcing function that requires end-to-end flow, the integration layer is perpetually "next." The governance dashboard is built before the AI can produce a migration. The dependency heatmap is refined before the graph extraction is verified correct.

**How to avoid:**
Structure phases as vertical slices: each phase must produce a working end-to-end migration for a constrained case, even if narrow. Phase 1 produces one migration PR for one simple Vaadin component. Phase 2 handles the next complexity tier. No infrastructure phase without a working output at the end. Define "done" as "produced a validated migration PR" not "built the service."

**Warning signs:**
- A phase is entirely infrastructure with no migration output.
- More than two services are being built simultaneously.
- The governance dashboard is prioritized before the migration engine produces any migrations.
- "We'll integrate everything at the end" appears in planning.

**Phase to address:**
All phases — vertical slice constraint must be enforced in roadmap from Phase 1 onward.

---

### Pitfall 7: Deterministic Guardrails Are Bypassed Under AI Pressure

**What goes wrong:**
Claude produces migration code that removes a `@Secured` annotation "because the new Vaadin 24 component doesn't need it in the same place" — and the guardrail that was supposed to block security annotation removal fails to fire because the annotation was moved, not deleted. The PR is merged. A security regression ships.

**Why it happens:**
Guardrails are typically implemented as simple AST checks: "does this annotation still exist in the output?" But AI-generated code can restructure, inline, or reorganize code in ways that satisfy the check syntactically while violating the semantic intent. "No security annotation removal" is actually "no net decrease in authorization coverage" — a much harder check.

**How to avoid:**
Define guardrails in terms of semantic invariants, not syntactic patterns. Use OpenRewrite's AST for pre/post comparison, not text diff. For security annotations: build an authorization surface extractor that maps every endpoint/method to its required roles before and after migration; block any PR where any endpoint loses coverage. For validation rules: extract all validation constraints pre-migration; block any PR where a constraint disappears. Guardrails must never be bypassed even when AI confidence is high.

**Warning signs:**
- Guardrails are implemented as regex or text-diff checks.
- "Guardrail override" exists as a developer option with no audit trail.
- Guardrail test suite only checks annotation presence, not authorization equivalence.
- No test that generates intentionally wrong AI output and verifies the guardrail blocks it.

**Phase to address:**
Guardrails and validation phase — guardrail architecture must be based on semantic invariants from day one.

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Full graph rebuild instead of incremental indexing | Simpler initial implementation | Graph is hours-stale during active development; AI recommendations diverge from current code | Only in Phase 1 proof-of-concept; must be replaced before CI integration |
| Vector-only retrieval (no graph traversal) | Faster to build, simpler pipeline | Wrong context for complex components; AI misses business constraints | Never in production migration pipeline; acceptable for early demo only |
| LLM self-reported confidence as the primary gate | Zero external infrastructure needed | Merging broken migrations confidently; trust collapse after first production incident | Never — always require at least compilation + test pass as external gate |
| Single OpenRewrite run without coverage gap tracking | Simple pipeline to implement | 40% of Vaadin 7 patterns silently untransformed; "migration complete" with remaining legacy code | Never — coverage audit must precede any recipe-based migration |
| Monolithic Spring Boot application for ESMP | Simpler deployment, fewer services | Graph extraction, RAG, and AI orchestration have very different scaling needs; tight coupling limits iteration | Acceptable in Phase 1; plan for service extraction if needed |
| Guardrails as text/regex checks | Fast to write | AI can satisfy syntactic check while violating semantic intent | Never for security annotation or validation rule guardrails |
| Static embedding snapshot without re-indexing | Fast setup | Embeddings drift from code over time; retrieval quality degrades silently | Acceptable for initial feasibility testing only |

---

## Integration Gotchas

Common mistakes when connecting to external services.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Neo4j (Docker Compose) | Treating it like a relational DB — no query profiling, missing indexes on high-cardinality properties | Add indexes on `(:Class {fqn})`, `(:Method {signature})` before loading 500k LOC; use `EXPLAIN` on all Cypher queries before going to production scale |
| Qdrant | Loading all embeddings with default HNSW settings; skipping quantization | Use scalar quantization from the start to manage memory; tune `m` and `ef_construct` based on collection size, not defaults |
| OpenRewrite (Maven/Gradle plugin) | Running recipes against the full multi-module build without understanding LST build time | Profile LST construction time on a sample module first; expect 5-15 minutes for large multi-module builds; cache LSTs across runs |
| Claude API | No retry logic, no rate limit handling, no cost tracking | Implement exponential backoff; track token usage per migration attempt; set hard budget limits per PR generation to avoid runaway costs |
| Claude API | Sending the entire file as context | Chunk at business logic boundaries (service method, validation block, UI component); stay well under context window; pass graph-derived context, not raw file content |
| Qdrant + embedding model | Changing embedding model after initial indexing | Changing embedding model invalidates all vectors; must re-embed entire collection; treat embedding model as a locked dependency until explicit re-indexing event |
| Docker Compose service startup | Using `depends_on` and assuming services are ready | `depends_on` only controls start order, not readiness; use health checks on Neo4j and Qdrant; Spring Boot 3.1+ Docker Compose support handles readiness if configured correctly |
| Git hook / CI trigger | Triggering full graph rebuild on every commit | Track file hashes; only re-extract and re-embed files that changed; only invalidate graph nodes connected to changed files |

---

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Eager full graph load into memory for every analysis | First analysis: fast. After 500k LOC loaded: Neo4j OOM or 30-second query times | Use pagination, lazy graph traversal, and targeted Cypher queries; never load the full graph into application memory | At roughly 50k–100k nodes with naive query patterns |
| Embedding all code at method level without chunking strategy | Vector collection grows to millions of tiny vectors; retrieval returns irrelevant noise | Define chunking at meaningful boundaries: class, service method, validation block, UI component — not arbitrary line counts | At 200k+ LOC equivalent chunks |
| Synchronous OpenRewrite LST construction in API request path | Timeout on first analysis request against any non-trivial module | Decouple LST construction from request path; run as background job; cache LST per module keyed by file hash | First multi-module analysis attempt |
| Storing all migration history and RAG context in Neo4j | Graph query time grows with audit trail size | Separate hot (current code graph) from cold (migration history) storage; Neo4j for current code topology, relational DB or append-only log for history | After 50+ migration cycles against the same codebase |
| Re-generating embeddings during every CI run regardless of changes | CI build time grows unbounded as codebase grows | File-hash-gated incremental embedding: only re-embed files whose content hash changed since last indexing | After first month of active development against the target codebase |

---

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| Storing target codebase (500k–1M LOC) in Qdrant/Neo4j without access controls | Proprietary business logic and trade secrets in infrastructure with default credentials | Use Neo4j Enterprise auth or bolt+tls; Qdrant API key authentication; Docker Compose secrets, not environment variables in compose file |
| Sending raw source code to Claude API without scrubbing | IP leakage of proprietary enterprise code to third-party AI provider | Establish data handling policy first; consider whether code can be sent externally; if not, use a local/on-prem model instead of Claude API |
| Generated migration PRs bypass normal code review because "AI validated it" | Silent regression or security hole merged without human review | Enforce that all AI-generated PRs go through normal PR review process; the platform assists reviewers, it does not replace them |
| No audit trail for AI-generated code | Cannot trace which migrations were AI-generated vs human-authored; no rollback path | Store migration run metadata (timestamp, model version, recipe used, confidence scores, guardrail results) in a durable log, linked to the PR |
| Guardrail bypass for "emergency" migrations | One-off bypass becomes the default pattern; guardrails erode | No bypass path in the architecture; if guardrails block a migration, the migration must be fixed, not the guardrail |

---

## UX Pitfalls

Common user experience mistakes in this domain.

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Dashboard shows "migration progress %" without defining what 100% means | Developer cannot tell if they are 80% done or 8% done | Define migration completeness explicitly: % of Vaadin 7 API call sites replaced, % of legacy components removed; track absolute numbers, not relative |
| AI confidence displayed as a single number without context | Developer merges a "95% confident" migration that breaks tests because confidence referred to syntactic correctness, not behavioral equivalence | Decompose confidence: compilation %, test pass %, guardrail status, behavioral diff status — four separate indicators, not one aggregate |
| Dependency heatmap displays all dependencies at the same visual weight | High-risk components (high fan-in, DB writes, complex validators) invisible among low-risk noise | Apply risk scoring to graph visualization; surface top 20 highest-risk components at entry point; let developer drill down from risk, not topology |
| Migration recommendations reference code elements the developer cannot locate | Developer abandons recommendation and migrates manually | Always link graph node references to source file + line number; never surface a method/class reference without a navigable code link |

---

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **AST extraction:** LST builds and recipes run — verify that recipe output covers all identified Vaadin 7 API patterns in the target codebase, not just framework-standard patterns
- [ ] **Code Knowledge Graph:** Neo4j has nodes and edges — verify that call graph traversal from a target component reaches all actual runtime callers, not just compile-time direct callers
- [ ] **Vector indexing:** Qdrant has vectors and similarity search works — verify that retrieval on real Vaadin 7 migration queries returns context from the correct module, not from unrelated code with superficial similarity
- [ ] **Behavioral diffing:** Framework runs and reports results — verify that normalization handles timestamps, generated IDs, and session tokens; verify that it detects a known behavioral change injected deliberately
- [ ] **Guardrails:** Guardrail checks pass — verify by injecting a migration that removes a `@Secured` annotation and confirming the PR is blocked
- [ ] **CI indexing:** Git hook triggers re-indexing — verify that modifying a single file results in only affected graph nodes being updated, not a full rebuild
- [ ] **AI orchestration:** Claude generates migration code — verify that a generated PR for a non-trivial Vaadin 7 component compiles, passes existing tests, and has no security annotation removals
- [ ] **Governance dashboard:** Metrics display — verify that the "% legacy API removed" metric counts from the same baseline before and after a known migration, not from an arbitrary starting point

---

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Knowledge graph is deeply stale (weeks behind codebase) | MEDIUM | Trigger full rebuild off-hours; implement file hash tracking before next development cycle; stale period: accept manual migration only |
| Embedding model changed after initial indexing | HIGH | Schedule full re-embedding run (expect hours for 500k LOC); pause AI migration feature during re-indexing; validate retrieval quality post-re-embed before re-enabling |
| Security guardrail bypass resulted in merged regression | HIGH | Revert PR immediately; audit all AI-generated PRs merged since last guardrail review; harden guardrail to semantic invariant check; add regression test for the exact bypass pattern |
| OpenRewrite coverage gap discovered late (major pattern untransformed) | MEDIUM | Enumerate gap patterns; write custom recipe or assign to AI-only path with explicit flagging; do not re-run previous migrations — rerun against affected modules only |
| LLM confidence score caused premature merge of broken migration | MEDIUM | Remove LLM confidence from merge gate immediately; add compilation + test pass as hard gates; audit last N AI-generated PRs for similar issues |
| Scope creep has produced infrastructure without end-to-end migration output | HIGH | Stop all horizontal infrastructure work; identify narrowest vertical slice that can produce one migration PR; ship that slice before resuming infrastructure investment |

---

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| LLM confidence scores unreliable | AI Orchestration phase | At phase end: zero PRs mergeable based on LLM confidence alone; external gates (compile + test + guardrail) are mandatory |
| OpenRewrite coverage gaps | AST Extraction / OpenRewrite phase | At phase end: coverage audit document listing all Vaadin 7 API patterns, their recipe status (covered / partial / gap), and planned handling for gaps |
| Knowledge graph staleness | CI Integration phase | At phase end: modifying one file triggers incremental re-indexing within CI build time; no full rebuild required for single-file changes |
| Behavioral diffing false positives | Guardrails and Validation phase | At phase end: diff framework correctly normalizes timestamps and UUIDs; deliberately injected behavioral change is detected; pure-function-only scope is explicitly documented |
| RAG retrieval wrong context | RAG Pipeline phase | At phase end: retrieval for a target Vaadin component includes graph-derived context (callers, dependencies, business rules) and is not vector-only |
| Scope creep / no end-to-end output | Every phase | At every phase end: at least one migration PR produced (even if narrow); no phase is "infrastructure only" |
| Deterministic guardrails bypassed | Guardrails and Validation phase | At phase end: injection test confirms `@Secured` removal is blocked; no bypass path exists in architecture |

---

## Sources

- [Why LLMs Fail at Confidence Scoring — 1up.ai](https://1up.ai/blog/why-llms-suck-at-confidence-scoring/)
- [LLMs are Overconfident: Evaluating Confidence Interval Calibration — arXiv](https://arxiv.org/html/2510.26995)
- [OpenRewrite FAQ: Completeness vs. Correctness — docs.openrewrite.org](https://docs.openrewrite.org/reference/faq)
- [Overview of OpenRewrite and Moderne for Code Modernization — moderne.ai](https://www.moderne.ai/blog/overview-of-openrewrite-and-moderne)
- [Solving the Nasty Code Migration Problem with Assisted AI Agents — Aviator](https://www.aviator.co/blog/solving-the-nasty-code-migration-problem-with-assisted-ai-agents/)
- [Codebase Knowledge Graph: Code Analysis with Graphs — Neo4j Blog](https://neo4j.com/blog/developer/codebase-knowledge-graph/)
- [Building a Temporal Infrastructure Knowledge Graph at Scale — Medium](https://medium.com/@roxane.fischer_50383/building-a-temporal-infrastructure-knowledge-graph-a-year-of-wrestling-with-neo4j-at-scale-949e989c98a2)
- [Advanced RAG Techniques for High-Performance LLM Applications — Neo4j](https://neo4j.com/blog/genai/advanced-rag-techniques/)
- [Vector Search in Production — Qdrant](https://qdrant.tech/articles/vector-search-production/)
- [Why Legacy Modernization Fails in 2026 — Medium](https://medium.com/@hashbyt/why-legacy-modernization-fails-2026-value-driven-enterprise-modernization-strategy-5ab69b6ce3e2)
- [Automated Behavioral Regression Testing — ResearchGate](https://www.researchgate.net/publication/220720078_Automated_Behavioral_Regression_Testing)
- [7 Challenges with Brownfield Codebases — Utkrusht](https://utkrusht.ai/blog/challenges-with-brownfield-development-codebases)
- [Vaadin 7 Extended Maintenance Fact Sheet — vaadin.com](https://vaadin.com/support/vaadin-7-extended-maintenance)
- [How to Increment Knowledge Graphs — Milvus AI Quick Reference](https://milvus.io/ai-quick-reference/how-do-you-keep-a-knowledge-graph-updated)
- [IBM Reimagining Brownfield Application Modernization](https://www.ibm.com/think/insights/reimagining-brownfield-application-modernization)

---
*Pitfalls research for: Enterprise Semantic Modernization Platform (ESMP) — Neo4j, Qdrant, OpenRewrite, Claude AI, Spring Boot, Vaadin 7 to Vaadin 24*
*Researched: 2026-03-04*
