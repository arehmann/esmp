# Requirements: ESMP

**Defined:** 2026-03-04
**Core Value:** Provide structural, semantic, and AI-powered understanding of a legacy Java enterprise codebase to enable safe, incremental Vaadin 7 → Vaadin 24 migration

## v1 Requirements

### Infrastructure

- [x] **INFRA-01**: Docker Compose setup with Neo4j, Qdrant, Spring Boot services, Prometheus, Grafana
- [x] **INFRA-02**: Spring Boot 3.5 with Java 21 and virtual threads
- [x] **INFRA-03**: Professional-grade project structure following Spring Boot best practices

### AST & Code Analysis

- [x] **AST-01**: System can parse Java/Vaadin 7 source code into structured AST using OpenRewrite LST
- [x] **AST-02**: System extracts class metadata, method signatures, field definitions, annotations, and imports
- [x] **AST-03**: System builds call graph edges between methods across classes
- [x] **AST-04**: System persists extracted nodes and relationships to Neo4j graph database

### Code Knowledge Graph

- [x] **CKG-01**: Graph stores Class, Method, Field, Annotation, Package, Module, UI View, Service, Repository, and DB Table nodes
- [x] **CKG-02**: Graph stores CALLS, EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE relationships
- [x] **CKG-03**: User can query the graph via structured API endpoints

### Graph Validation & Canonical Queries

- [x] **GVAL-01**: 20 canonical validation queries defined and passing against populated graph
- [x] **GVAL-02**: Dependency cone accuracy verified against manually confirmed architectural expectations
- [x] **GVAL-03**: No orphan nodes or duplicate structural nodes exist in graph
- [x] **GVAL-04**: Inheritance chains complete and transitive repository dependencies correctly resolved

### Domain Lexicon

- [x] **LEX-01**: System extracts business terms from class names, enums, DB schema, Javadoc, and comments
- [x] **LEX-02**: System stores terms with definition, synonyms, related classes, related tables, criticality, and migration sensitivity
- [x] **LEX-03**: System creates USES_TERM and DEFINES_RULE graph edges connecting terms to code
- [x] **LEX-04**: User can view and curate the domain lexicon

### Structural Risk Analysis

- [x] **RISK-01**: System computes cyclomatic complexity per class/method
- [x] **RISK-02**: System computes fan-in and fan-out metrics per class
- [x] **RISK-03**: System detects DB write operations per class
- [x] **RISK-04**: System produces composite structural risk score per class
- [x] **RISK-05**: User can view dependency heatmap sorted by structural risk score

### Domain-Aware Risk Analysis

- [x] **DRISK-01**: System computes domain criticality weight per class from associated business term criticality ratings
- [x] **DRISK-02**: System scores security sensitivity for classes handling authentication, authorization, or encryption
- [x] **DRISK-03**: System scores financial involvement for classes in payment, billing, or ledger operations
- [x] **DRISK-04**: System computes business rule density per class from DEFINES_RULE edge count and rule complexity
- [x] **DRISK-05**: System produces enhanced composite risk score combining structural risk, domain criticality, security sensitivity, financial involvement, and business rule density

### Smart Chunking & Vector Indexing

- [x] **VEC-01**: System chunks code by semantic unit (class, service method, validation block, UI block, business rule)
- [x] **VEC-02**: Each chunk is enriched with graph neighbors, domain terms, risk score (structural + domain-aware), and migration state
- [x] **VEC-03**: System indexes enriched chunks into Qdrant using open-source embedding model
- [x] **VEC-04**: System supports incremental re-indexing of changed files

### Golden Module Pilot

- [x] **GMP-01**: One bounded context selected and fully processed through chunking, domain enrichment, and vector indexing
- [x] **GMP-02**: RAG retrieval for pilot module returns contextually relevant results validated by senior engineers
- [x] **GMP-03**: Risk computation and migration recommendation for pilot module aligns with expert expectations

### Continuous Indexing

- [x] **CI-01**: CI hook re-extracts changed files on each build
- [x] **CI-02**: Graph nodes and edges update incrementally (not full rebuild)
- [x] **CI-03**: Vector embeddings update incrementally for changed chunks

### RAG Pipeline

- [x] **RAG-01**: System performs graph expansion from a focal class to retrieve related nodes
- [x] **RAG-02**: System performs embedding similarity search against Qdrant
- [x] **RAG-03**: System combines graph and vector results into ranked retrieval context
- [x] **RAG-04**: User can query "what classes/services relate to X?" and get structured results

### Governance Dashboard

- [x] **DASH-01**: Dashboard shows % Vaadin 7 APIs remaining per module
- [x] **DASH-02**: Dashboard shows dependency graph explorer (interactive)
- [x] **DASH-03**: Dashboard shows business concept graph visualization
- [x] **DASH-04**: Dashboard shows risk hotspot clusters
- [x] **DASH-05**: Dashboard shows lexicon coverage percentage
- [x] **DASH-06**: Dashboard shows migration progress heatmap

### Risk-Prioritized Scheduling

- [x] **SCHED-01**: System recommends module migration order based on composite risk score (structural + domain-aware)
- [x] **SCHED-02**: Recommendation accounts for dependency risk, change frequency, and complexity

### Performance SLOs (Cross-Cutting)

- [x] **SLO-01**: Graph dependency cone query completes in under 200ms
- [x] **SLO-02**: RAG context assembly completes in under 1.5 seconds for a 50-node cone
- [x] **SLO-03**: Incremental re-index of 5 changed files completes in under 30 seconds
- [x] **SLO-04**: Full re-index of 100-class module completes in under 5 minutes

### Migration Engine

- [x] **MIG-01**: MigrationPatternVisitor catalogs every Vaadin 7 type usage per class with source-target mapping and automatable/partial/no classification
- [x] **MIG-02**: ClassNode stores migrationActionCount, automatableActionCount, automationScore, and needsAiMigration properties
- [x] **MIG-03**: MigrationRecipeService generates composite OpenRewrite recipes from automatable actions and produces preview diffs
- [x] **MIG-04**: MigrationRecipeService applies recipes and writes modified source with correct imports and formatting
- [x] **MIG-05**: REST API exposes migration plan, preview, apply, and batch-apply-module endpoints
- [x] **MIG-06**: MCP tools (getMigrationPlan, applyMigrationRecipes, getModuleMigrationSummary) callable from Claude Code

### Recipe Book & Transitive Detection

- [x] **RB-01**: Migration rules stored in external JSON recipe book (not hardcoded Java maps) — loaded at startup, supports base rules + user custom overlay
- [x] **RB-02**: Comprehensive initial recipe book covering all known Vaadin 7 → 24 component/data/server types plus javax → jakarta mappings (80+ rules)
- [x] **RB-03**: Extraction-driven enrichment — after each extraction, recipe book updated with per-rule usage counts, discovered unmapped types (NEEDS_MAPPING), and codebase-specific statistics
- [x] **RB-04**: Transitive detection via EXTENDS graph traversal — custom widgets inheriting from Vaadin 7 types discovered, classified as pure wrapper (no overrides) or complex, assigned inherited automation level
- [x] **RB-05**: REST API for recipe book management (view rules, view gaps, add/update custom rules) and updated MCP tools that surface transitive actions and coverage scores
- [x] **RB-06**: AI-optimized output — getMigrationPlan returns enrichment context (usageCount, pureWrapper flag, vaadinAncestor, migration steps) so Claude Code can make accurate migration decisions without additional queries

## v2 Requirements

### AI Orchestration

- **ORCH-01**: AI orchestration engine triggers OpenRewrite, retrieves RAG context, builds prompt, submits to Claude, validates output
- **ORCH-02**: System generates automated pull requests with migrated code
- **ORCH-03**: Deterministic guardrails block contract changes, security annotation removal, validation deletion
- **ORCH-04**: AI confidence scoring per migration (composite: compilation, tests, guardrails, behavioral diff)

### Behavioral Diffing

- **DIFF-01**: System captures and compares service outputs pre/post migration
- **DIFF-02**: System captures and compares SQL queries pre/post migration
- **DIFF-03**: System captures and compares validation behavior pre/post migration

### Advanced Features

- **ADV-01**: Multi-layer RAG with full lexicon match scoring layer
- **ADV-02**: Natural language query interface over the graph
- **ADV-03**: Advanced trained confidence scoring model

## Out of Scope

| Feature | Reason |
|---------|--------|
| Full automated rewrite without human validation | Industry consensus: AI hallucination risk too high for enterprise code |
| Business rule inference without domain expert validation | Critical rules require human SME curation |
| Replacing CI/CD pipelines | ESMP is a parallel intelligence layer, not a build system |
| Mobile/tablet UI | Solo migration engineer works at desktop |
| Automatic dependency version management | Overlaps with Dependabot/Renovate; not ESMP's concern |
| Real-time collaborative editing | Single-user platform; adds OAuth/WebSocket complexity for no benefit |

## Traceability

| Requirement | Phase | Phase Name | Status |
|-------------|-------|------------|--------|
| INFRA-01 | Phase 1 | Infrastructure | Complete |
| INFRA-02 | Phase 1 | Infrastructure | Complete |
| INFRA-03 | Phase 1 | Infrastructure | Complete |
| AST-01 | Phase 2 | AST Extraction | Pending |
| AST-02 | Phase 2 | AST Extraction | Pending |
| AST-03 | Phase 2 | AST Extraction | Pending |
| AST-04 | Phase 2 | AST Extraction | Pending |
| CKG-01 | Phase 3 | Code Knowledge Graph | Pending |
| CKG-02 | Phase 3 | Code Knowledge Graph | Pending |
| CKG-03 | Phase 3 | Code Knowledge Graph | Pending |
| GVAL-01 | Phase 4 | Graph Validation & Canonical Queries | Pending |
| GVAL-02 | Phase 4 | Graph Validation & Canonical Queries | Pending |
| GVAL-03 | Phase 4 | Graph Validation & Canonical Queries | Pending |
| GVAL-04 | Phase 4 | Graph Validation & Canonical Queries | Pending |
| LEX-01 | Phase 5 | Domain Lexicon | Pending |
| LEX-02 | Phase 5 | Domain Lexicon | Pending |
| LEX-03 | Phase 5 | Domain Lexicon | Pending |
| LEX-04 | Phase 5 | Domain Lexicon | Pending |
| RISK-01 | Phase 6 | Structural Risk Analysis | Pending |
| RISK-02 | Phase 6 | Structural Risk Analysis | Pending |
| RISK-03 | Phase 6 | Structural Risk Analysis | Pending |
| RISK-04 | Phase 6 | Structural Risk Analysis | Pending |
| RISK-05 | Phase 6 | Structural Risk Analysis | Pending |
| DRISK-01 | Phase 7 | Domain-Aware Risk Analysis | Pending |
| DRISK-02 | Phase 7 | Domain-Aware Risk Analysis | Pending |
| DRISK-03 | Phase 7 | Domain-Aware Risk Analysis | Pending |
| DRISK-04 | Phase 7 | Domain-Aware Risk Analysis | Pending |
| DRISK-05 | Phase 7 | Domain-Aware Risk Analysis | Pending |
| VEC-01 | Phase 8 | Smart Chunking and Vector Indexing | Pending |
| VEC-02 | Phase 8 | Smart Chunking and Vector Indexing | Pending |
| VEC-03 | Phase 8 | Smart Chunking and Vector Indexing | Pending |
| VEC-04 | Phase 8 | Smart Chunking and Vector Indexing | Pending |
| GMP-01 | Phase 9 | Golden Module Pilot | Pending |
| GMP-02 | Phase 9 | Golden Module Pilot | Pending |
| GMP-03 | Phase 9 | Golden Module Pilot | Pending |
| CI-01 | Phase 10 | Continuous Indexing | Pending |
| CI-02 | Phase 10 | Continuous Indexing | Pending |
| CI-03 | Phase 10 | Continuous Indexing | Pending |
| SLO-03 | Phase 10 | Continuous Indexing | Pending |
| SLO-04 | Phase 10 | Continuous Indexing | Pending |
| RAG-01 | Phase 11 | RAG Pipeline | Pending |
| RAG-02 | Phase 11 | RAG Pipeline | Pending |
| RAG-03 | Phase 11 | RAG Pipeline | Pending |
| RAG-04 | Phase 11 | RAG Pipeline | Pending |
| SLO-01 | Phase 11 | RAG Pipeline | Pending |
| SLO-02 | Phase 11 | RAG Pipeline | Pending |
| DASH-01 | Phase 12 | Governance Dashboard | Pending |
| DASH-02 | Phase 12 | Governance Dashboard | Pending |
| DASH-03 | Phase 12 | Governance Dashboard | Pending |
| DASH-04 | Phase 12 | Governance Dashboard | Pending |
| DASH-05 | Phase 12 | Governance Dashboard | Pending |
| DASH-06 | Phase 12 | Governance Dashboard | Pending |
| SCHED-01 | Phase 13 | Risk-Prioritized Scheduling | Pending |
| SCHED-02 | Phase 13 | Risk-Prioritized Scheduling | Pending |

| MIG-01 | Phase 16 | OpenRewrite Recipe-Based Migration Engine | Pending |
| MIG-02 | Phase 16 | OpenRewrite Recipe-Based Migration Engine | Pending |
| MIG-03 | Phase 16 | OpenRewrite Recipe-Based Migration Engine | Pending |
| MIG-04 | Phase 16 | OpenRewrite Recipe-Based Migration Engine | Pending |
| MIG-05 | Phase 16 | OpenRewrite Recipe-Based Migration Engine | Pending |
| MIG-06 | Phase 16 | OpenRewrite Recipe-Based Migration Engine | Pending |

**Coverage:**
- v1 requirements: 54 total (was 48 — added 6 for Phase 16 migration engine)
- Mapped to phases: 54
- Unmapped: 0

---
*Requirements defined: 2026-03-04*
*Last updated: 2026-03-04 — roadmap optimized: reordered phases, added graph validation/golden module pilot, split risk analysis, added SLOs*
