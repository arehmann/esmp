# Roadmap: Enterprise Semantic Modernization Platform (ESMP)

## Overview

ESMP is built as a strict dependency chain: infrastructure and tooling come first, then AST-based code extraction populates a Neo4j knowledge graph, risk scoring enables safe migration targeting, smart chunking and vector indexing create the semantic retrieval layer, GraphRAG pipelines assemble AI context, domain lexicon enrichment deepens semantic precision, a governance dashboard surfaces migration progress, continuous CI indexing keeps the graph current as the legacy codebase evolves, and finally risk-prioritized scheduling makes migration order data-driven. Each phase delivers a coherent capability that unblocks the next — no phase is infrastructure only.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Infrastructure** - Docker Compose environment with Neo4j, Qdrant, PostgreSQL, and Spring Boot skeleton running
- [ ] **Phase 2: AST Extraction** - Parse Java/Vaadin 7 source into structured graph nodes using OpenRewrite LST
- [ ] **Phase 3: Code Knowledge Graph** - Full structural graph populated with all node types and relationship edges
- [ ] **Phase 4: Risk Analysis** - Cyclomatic complexity, fan-in/out, DB write detection, and composite risk scoring per class
- [ ] **Phase 5: Smart Chunking and Vector Indexing** - Semantic code chunks embedded and indexed in Qdrant with incremental re-indexing
- [ ] **Phase 6: RAG Pipeline** - Multi-layer GraphRAG retrieval combining graph expansion and embedding similarity
- [ ] **Phase 7: Domain Lexicon** - Business term extraction from code and schema, curation UI, and graph edge integration
- [ ] **Phase 8: Governance Dashboard** - Migration progress, risk clusters, dependency explorer, and lexicon coverage metrics
- [ ] **Phase 9: Continuous Indexing** - CI-triggered incremental graph and vector updates on changed files only
- [ ] **Phase 10: Risk-Prioritized Scheduling** - Data-driven module migration order recommendations

## Phase Details

### Phase 1: Infrastructure
**Goal**: Developer can run the full ESMP environment locally with all data stores healthy and a Spring Boot service ready to accept ingestion requests
**Depends on**: Nothing (first phase)
**Requirements**: INFRA-01, INFRA-02, INFRA-03
**Success Criteria** (what must be TRUE):
  1. `docker compose up` starts Neo4j, Qdrant, PostgreSQL, Prometheus, Grafana, and the Spring Boot service with no errors
  2. Spring Boot service starts on Java 21 with virtual threads enabled and passes its health check endpoint
  3. Neo4j, Qdrant, and PostgreSQL are each reachable from the Spring Boot service (connection verified at startup)
  4. Project compiles cleanly with professional-grade package structure, Flyway schema migrations applied to PostgreSQL
  5. Spring Boot Actuator exposes health, info, and metrics endpoints
**Plans**: TBD

### Phase 2: AST Extraction
**Goal**: System can parse a Java/Vaadin 7 source module and persist structured AST entities to Neo4j
**Depends on**: Phase 1
**Requirements**: AST-01, AST-02, AST-03, AST-04
**Success Criteria** (what must be TRUE):
  1. Given a path to a Java source file, the system parses it into an OpenRewrite LST without error
  2. Class metadata (name, package, annotations, imports), method signatures, and field definitions are extracted and stored as Neo4j nodes
  3. Call graph edges between methods across classes are stored as CALLS relationships in Neo4j
  4. A Vaadin 7 view class is parsed and its Vaadin component usage is captured in the graph
  5. Re-running extraction on an unchanged file does not create duplicate nodes (idempotent)
**Plans**: TBD

### Phase 3: Code Knowledge Graph
**Goal**: Neo4j graph contains the full structural model of the codebase with all node types, relationship edges, and is queryable via API
**Depends on**: Phase 2
**Requirements**: CKG-01, CKG-02, CKG-03
**Success Criteria** (what must be TRUE):
  1. Graph stores all node types: Class, Method, Field, Annotation, Package, Module, UI View, Service, Repository, DB Table
  2. Graph stores all relationship types: CALLS, EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE
  3. User can call a REST API endpoint with a class name and receive its full structural context (methods, fields, dependencies, annotations)
  4. Graph query returns the complete inheritance chain for a given class
  5. API returns all Service classes that directly or transitively depend on a given Repository
**Plans**: TBD

### Phase 4: Risk Analysis
**Goal**: Every class in the graph has a composite risk score, and the developer can view a dependency heatmap sorted by migration risk
**Depends on**: Phase 3
**Requirements**: RISK-01, RISK-02, RISK-03, RISK-04, RISK-05
**Success Criteria** (what must be TRUE):
  1. Every class node in Neo4j has a cyclomatic complexity value computed from its methods
  2. Every class node has fan-in (classes that call it) and fan-out (classes it calls) counts
  3. Every class node is flagged for DB write operations (INSERT/UPDATE/DELETE) detected in its method bodies
  4. Every class node has a composite risk score combining complexity, fan-in, fan-out, and DB write presence
  5. User can call a REST endpoint and receive a list of classes sorted by descending risk score, usable as a migration-order heatmap
**Plans**: TBD

### Phase 5: Smart Chunking and Vector Indexing
**Goal**: Enriched semantic code chunks are indexed in Qdrant and incremental re-indexing updates only changed files
**Depends on**: Phase 4
**Requirements**: VEC-01, VEC-02, VEC-03, VEC-04
**Success Criteria** (what must be TRUE):
  1. A service class is chunked into distinct semantic units: one chunk per service method, one per validation block, one per UI binding block
  2. Each chunk payload includes graph neighbors (callers/callees), domain terms present, risk score, and migration state
  3. Chunks are embedded using a local ONNX model and stored in Qdrant with their enriched payloads
  4. When a source file changes, only its affected chunks are re-embedded and updated in Qdrant (not a full collection rebuild)
  5. Qdrant collection is queryable by embedding similarity and returns chunks with their enrichment payloads
**Plans**: TBD

### Phase 6: RAG Pipeline
**Goal**: Developer can query "what classes/services relate to X?" and receive a ranked, graph-aware retrieval result that assembles correct migration context
**Depends on**: Phase 5
**Requirements**: RAG-01, RAG-02, RAG-03, RAG-04
**Success Criteria** (what must be TRUE):
  1. Given a focal class, the system traverses the Neo4j graph to retrieve its dependency cone (directly and transitively related nodes)
  2. The system performs embedding similarity search in Qdrant constrained to the dependency cone (not the full collection)
  3. Graph expansion results and vector similarity results are merged and re-ranked by risk score into a single retrieval context
  4. Developer queries "what classes relate to CustomerOrderService?" via REST and receives a structured, ranked list with relationship context
  5. Retrieval context for a known Vaadin 7 view correctly includes its backing service, repository, and domain entities
**Plans**: TBD

### Phase 7: Domain Lexicon
**Goal**: Business terms extracted from the codebase are stored in the graph with curated definitions, and developers can view and edit the lexicon
**Depends on**: Phase 6
**Requirements**: LEX-01, LEX-02, LEX-03, LEX-04
**Success Criteria** (what must be TRUE):
  1. Business terms are automatically extracted from class names, enums, DB table/column names, Javadoc, and inline comments
  2. Each term in Neo4j stores: definition, synonyms, related classes, related tables, criticality, and migration sensitivity
  3. USES_TERM edges connect Code nodes to their relevant domain terms; DEFINES_RULE edges connect rule-implementing classes to their business rules
  4. Developer can open a lexicon UI, view all extracted terms, edit a term's definition and criticality, and save the change
  5. After curation, re-running extraction does not overwrite hand-edited term definitions
**Plans**: TBD

### Phase 8: Governance Dashboard
**Goal**: Developer can see the current state of the migration — what is done, what is risky, and what still uses Vaadin 7 APIs — in a single dashboard
**Depends on**: Phase 7
**Requirements**: DASH-01, DASH-02, DASH-03, DASH-04, DASH-05, DASH-06
**Success Criteria** (what must be TRUE):
  1. Dashboard shows the percentage of Vaadin 7 API usages remaining, broken down by module
  2. Dashboard provides an interactive dependency graph explorer where clicking a node reveals its callers, callees, and risk score
  3. Dashboard shows the business concept graph with domain terms linked to their implementing classes
  4. Dashboard highlights risk hotspot clusters (groups of high-risk, highly-coupled classes)
  5. Dashboard shows lexicon coverage percentage (terms with curated definitions vs total extracted terms)
  6. Dashboard shows a migration progress heatmap across all modules with color encoding for migration state
**Plans**: TBD

### Phase 9: Continuous Indexing
**Goal**: As the legacy codebase undergoes active development, the knowledge graph and vector store stay current without requiring manual re-runs
**Depends on**: Phase 8
**Requirements**: CI-01, CI-02, CI-03
**Success Criteria** (what must be TRUE):
  1. A CI build hook (Gradle task or webhook endpoint) triggers extraction when source files change
  2. Only files whose hash has changed since last extraction are re-processed (not a full rebuild)
  3. Neo4j graph nodes and edges for changed files are updated or created incrementally without touching unaffected nodes
  4. Qdrant embeddings for changed file chunks are updated; embeddings for unchanged files remain untouched
  5. A full re-index of a sample 100-class module completes in under 5 minutes; incremental update of 5 changed files completes in under 30 seconds
**Plans**: TBD

### Phase 10: Risk-Prioritized Scheduling
**Goal**: System recommends a migration order for modules that accounts for dependency risk, change frequency, and complexity — so the developer starts with the safest targets
**Depends on**: Phase 9
**Requirements**: SCHED-01, SCHED-02
**Success Criteria** (what must be TRUE):
  1. Developer calls a REST endpoint and receives an ordered list of modules recommended for migration, from lowest-risk to highest-risk
  2. Recommendation score incorporates composite risk score, number of dependents, recent change frequency from git history, and cyclomatic complexity distribution
  3. Developer can view the rationale for each module's position in the recommendation list (which factors dominate its score)
  4. Re-running the recommendation after ingesting new modules produces an updated ordered list reflecting the new dependency landscape
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Infrastructure | 0/TBD | Not started | - |
| 2. AST Extraction | 0/TBD | Not started | - |
| 3. Code Knowledge Graph | 0/TBD | Not started | - |
| 4. Risk Analysis | 0/TBD | Not started | - |
| 5. Smart Chunking and Vector Indexing | 0/TBD | Not started | - |
| 6. RAG Pipeline | 0/TBD | Not started | - |
| 7. Domain Lexicon | 0/TBD | Not started | - |
| 8. Governance Dashboard | 0/TBD | Not started | - |
| 9. Continuous Indexing | 0/TBD | Not started | - |
| 10. Risk-Prioritized Scheduling | 0/TBD | Not started | - |
