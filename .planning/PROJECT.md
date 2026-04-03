# Enterprise Semantic Modernization Platform (ESMP)

## What This Is

A platform that provides structural, semantic, and AI-powered understanding of a large brownfield enterprise Java codebase (multi-module Gradle, 500k–1M LOC) to enable safe, incremental migration from Vaadin 7 to Vaadin 24. It combines a Code Knowledge Graph, Domain Lexicon, context-aware RAG, and AI orchestration to produce validated migration pull requests — while coexisting with ongoing feature development.

## Core Value

The platform can analyze a legacy module, understand its structure, business context, and dependencies, then produce a validated migration PR from Vaadin 7 to Vaadin 24 with confidence scoring and regression safety.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Full structural Code Knowledge Graph (classes, methods, fields, relationships, dependencies)
- [ ] AST extraction engine using OpenRewrite for Java/Vaadin analysis
- [ ] Dependency heatmap with risk scoring (cyclomatic complexity, fan-in/out, DB writes)
- [ ] Domain Lexicon extraction from code, enums, DB schema, Javadoc, comments
- [ ] Domain term curation and graph integration (USES_TERM, DEFINES_RULE edges)
- [ ] Smart chunking strategy (by class, service method, validation block, UI block, business rule)
- [ ] Vector indexing of enriched code chunks using open-source embedding model
- [ ] Multi-layer retrieval pipeline (graph expansion → lexicon match → embedding similarity → risk prioritization)
- [ ] AI orchestration engine (trigger OpenRewrite → retrieve RAG context → build prompt → submit to Claude → validate → open PR)
- [ ] Deterministic guardrails (no service contract changes, no security annotation removal, no validation rule deletion)
- [ ] Behavioral diffing framework (service outputs, SQL queries, validation behavior comparison)
- [ ] Migration governance dashboard (% legacy API removed, % UI migrated, lexicon coverage, risk clusters, AI confidence, regressions)
- [ ] Continuous indexing via CI (re-extract changed files, update graph + embeddings on each build)
- [ ] Dependency graph explorer and business concept graph visualization

### Out of Scope

- Full automated rewrite without human validation — AI assists, humans approve
- Business rule inference without domain expert validation — lexicon requires curation
- Replacing CI/CD or core DevOps pipelines — ESMP is a parallel intelligence layer
- Mobile or desktop client — web dashboard only
- Real-time collaborative editing — single-user platform operated by migration engineer

## Context

- **Target system**: Brownfield enterprise Java application, multi-module Gradle build, 500k–1M LOC
- **Migration path**: Vaadin 7 → Vaadin 24
- **Team**: Solo developer with AI assistance (Claude)
- **Deployment**: Docker Compose (Neo4j, Qdrant, MySQL, Spring Boot services, Prometheus, Grafana)
- **AI strategy**: Claude for code understanding/generation, open-source embedding model (e.g., nomic-embed or all-MiniLM) for vector indexing
- **Adoption model**: Passive observation → developer assist → controlled migration → full transition
- **The legacy codebase continues active development** — ESMP must coexist without disruption

## Constraints

- **Tech stack**: Java + Spring Boot for ESMP services — matches target ecosystem
- **Graph DB**: Neo4j — mature graph database for code knowledge graph
- **Vector DB**: Qdrant — purpose-built for embedding similarity search
- **AST Engine**: OpenRewrite — deterministic, well-supported Java/Vaadin transformations
- **AI Provider**: Claude (Anthropic) for reasoning and code generation
- **Embeddings**: Open-source model (cost-effective for bulk indexing)
- **Deployment**: Docker Compose for local/dev environments
- **Solo constraint**: Architecture must be manageable by one developer with AI tools

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Java + Spring Boot for ESMP | Same ecosystem as target codebase, team expertise | — Pending |
| Claude + open-source embeddings | Claude for intelligence, lightweight model for cost-effective bulk vector indexing | — Pending |
| Docker Compose deployment | Quick local setup with Neo4j, Qdrant, and services | — Pending |
| Neo4j for code graph | Mature graph DB, Cypher query language, good Java drivers | — Pending |
| Qdrant for vector search | Purpose-built vector DB, good performance, Docker-friendly | — Pending |
| MySQL for relational metadata | Migration job state, audit trail, confidence ledger | — Pending |
| Brownfield parallel adoption | Non-disruptive: observe → assist → migrate → transition | — Pending |

---
*Last updated: 2026-04-03 — Phase 19 complete: Alfa* wrapper recipe book (150+ rules, 10 categories), deep transitive detection through Alfa* intermediaries, API/MCP surfaces updated*
