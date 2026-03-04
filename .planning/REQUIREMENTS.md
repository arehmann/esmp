# Requirements: ESMP

**Defined:** 2026-03-04
**Core Value:** Provide structural, semantic, and AI-powered understanding of a legacy Java enterprise codebase to enable safe, incremental Vaadin 7 → Vaadin 24 migration

## v1 Requirements

### AST & Code Analysis

- [ ] **AST-01**: System can parse Java/Vaadin 7 source code into structured AST using OpenRewrite LST
- [ ] **AST-02**: System extracts class metadata, method signatures, field definitions, annotations, and imports
- [ ] **AST-03**: System builds call graph edges between methods across classes
- [ ] **AST-04**: System persists extracted nodes and relationships to Neo4j graph database

### Code Knowledge Graph

- [ ] **CKG-01**: Graph stores Class, Method, Field, Annotation, Package, Module, UI View, Service, Repository, and DB Table nodes
- [ ] **CKG-02**: Graph stores CALLS, EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE relationships
- [ ] **CKG-03**: User can query the graph via structured API endpoints

### Risk Analysis

- [ ] **RISK-01**: System computes cyclomatic complexity per class/method
- [ ] **RISK-02**: System computes fan-in and fan-out metrics per class
- [ ] **RISK-03**: System detects DB write operations per class
- [ ] **RISK-04**: System produces composite risk score per class
- [ ] **RISK-05**: User can view dependency heatmap sorted by risk score

### Smart Chunking & Vector Indexing

- [ ] **VEC-01**: System chunks code by semantic unit (class, service method, validation block, UI block, business rule)
- [ ] **VEC-02**: Each chunk is enriched with graph neighbors, domain terms, risk score, and migration state
- [ ] **VEC-03**: System indexes enriched chunks into Qdrant using open-source embedding model
- [ ] **VEC-04**: System supports incremental re-indexing of changed files

### RAG Pipeline

- [ ] **RAG-01**: System performs graph expansion from a focal class to retrieve related nodes
- [ ] **RAG-02**: System performs embedding similarity search against Qdrant
- [ ] **RAG-03**: System combines graph and vector results into ranked retrieval context
- [ ] **RAG-04**: User can query "what classes/services relate to X?" and get structured results

### Domain Lexicon

- [ ] **LEX-01**: System extracts business terms from class names, enums, DB schema, Javadoc, and comments
- [ ] **LEX-02**: System stores terms with definition, synonyms, related classes, related tables, criticality, and migration sensitivity
- [ ] **LEX-03**: System creates USES_TERM and DEFINES_RULE graph edges connecting terms to code
- [ ] **LEX-04**: User can view and curate the domain lexicon

### Governance Dashboard

- [ ] **DASH-01**: Dashboard shows % Vaadin 7 APIs remaining per module
- [ ] **DASH-02**: Dashboard shows dependency graph explorer (interactive)
- [ ] **DASH-03**: Dashboard shows business concept graph visualization
- [ ] **DASH-04**: Dashboard shows risk hotspot clusters
- [ ] **DASH-05**: Dashboard shows lexicon coverage percentage
- [ ] **DASH-06**: Dashboard shows migration progress heatmap

### Continuous Indexing

- [ ] **CI-01**: CI hook re-extracts changed files on each build
- [ ] **CI-02**: Graph nodes and edges update incrementally (not full rebuild)
- [ ] **CI-03**: Vector embeddings update incrementally for changed chunks

### Risk-Prioritized Scheduling

- [ ] **SCHED-01**: System recommends module migration order based on composite risk score
- [ ] **SCHED-02**: Recommendation accounts for dependency risk, change frequency, and complexity

### Infrastructure

- [ ] **INFRA-01**: Docker Compose setup with Neo4j, Qdrant, Spring Boot services, Prometheus, Grafana
- [ ] **INFRA-02**: Spring Boot 3.5 with Java 21 and virtual threads
- [ ] **INFRA-03**: Professional-grade project structure following Spring Boot best practices

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
| INFRA-01 | Phase 1 | Infrastructure | Pending |
| INFRA-02 | Phase 1 | Infrastructure | Pending |
| INFRA-03 | Phase 1 | Infrastructure | Pending |
| AST-01 | Phase 2 | AST Extraction | Pending |
| AST-02 | Phase 2 | AST Extraction | Pending |
| AST-03 | Phase 2 | AST Extraction | Pending |
| AST-04 | Phase 2 | AST Extraction | Pending |
| CKG-01 | Phase 3 | Code Knowledge Graph | Pending |
| CKG-02 | Phase 3 | Code Knowledge Graph | Pending |
| CKG-03 | Phase 3 | Code Knowledge Graph | Pending |
| RISK-01 | Phase 4 | Risk Analysis | Pending |
| RISK-02 | Phase 4 | Risk Analysis | Pending |
| RISK-03 | Phase 4 | Risk Analysis | Pending |
| RISK-04 | Phase 4 | Risk Analysis | Pending |
| RISK-05 | Phase 4 | Risk Analysis | Pending |
| VEC-01 | Phase 5 | Smart Chunking and Vector Indexing | Pending |
| VEC-02 | Phase 5 | Smart Chunking and Vector Indexing | Pending |
| VEC-03 | Phase 5 | Smart Chunking and Vector Indexing | Pending |
| VEC-04 | Phase 5 | Smart Chunking and Vector Indexing | Pending |
| RAG-01 | Phase 6 | RAG Pipeline | Pending |
| RAG-02 | Phase 6 | RAG Pipeline | Pending |
| RAG-03 | Phase 6 | RAG Pipeline | Pending |
| RAG-04 | Phase 6 | RAG Pipeline | Pending |
| LEX-01 | Phase 7 | Domain Lexicon | Pending |
| LEX-02 | Phase 7 | Domain Lexicon | Pending |
| LEX-03 | Phase 7 | Domain Lexicon | Pending |
| LEX-04 | Phase 7 | Domain Lexicon | Pending |
| DASH-01 | Phase 8 | Governance Dashboard | Pending |
| DASH-02 | Phase 8 | Governance Dashboard | Pending |
| DASH-03 | Phase 8 | Governance Dashboard | Pending |
| DASH-04 | Phase 8 | Governance Dashboard | Pending |
| DASH-05 | Phase 8 | Governance Dashboard | Pending |
| DASH-06 | Phase 8 | Governance Dashboard | Pending |
| CI-01 | Phase 9 | Continuous Indexing | Pending |
| CI-02 | Phase 9 | Continuous Indexing | Pending |
| CI-03 | Phase 9 | Continuous Indexing | Pending |
| SCHED-01 | Phase 10 | Risk-Prioritized Scheduling | Pending |
| SCHED-02 | Phase 10 | Risk-Prioritized Scheduling | Pending |

**Coverage:**
- v1 requirements: 37 total
- Mapped to phases: 37
- Unmapped: 0

---
*Requirements defined: 2026-03-04*
*Last updated: 2026-03-04 — traceability finalized after roadmap creation*
