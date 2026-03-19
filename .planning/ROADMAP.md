# Roadmap: Enterprise Semantic Modernization Platform (ESMP)

## Overview

ESMP is built as a strict dependency chain: infrastructure and tooling come first, then AST-based code extraction populates a Neo4j knowledge graph, graph validation ensures structural correctness before semantic layers are built, domain lexicon enrichment deepens semantic precision early to prevent downstream rework, risk analysis is split into structural and domain-aware stages for accuracy, smart chunking and vector indexing create the semantic retrieval layer, a golden module pilot validates the full pipeline on one bounded context before scaling, continuous indexing keeps the graph current as the legacy codebase evolves, GraphRAG pipelines assemble AI context, a governance dashboard surfaces migration progress, and finally risk-prioritized scheduling makes migration order data-driven. Each phase delivers a coherent capability that unblocks the next — no phase is infrastructure only.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Infrastructure** - Docker Compose environment with Neo4j, Qdrant, MySQL, and Spring Boot skeleton running (completed 2026-03-04)
- [x] **Phase 2: AST Extraction** - Parse Java/Vaadin 7 source into structured graph nodes using OpenRewrite LST (completed 2026-03-04)
- [x] **Phase 3: Code Knowledge Graph** - Full structural graph populated with all node types and relationship edges (gap closure in progress) (completed 2026-03-04)
- [x] **Phase 4: Graph Validation & Canonical Queries** - Structural graph correctness verified before building semantic layers (completed 2026-03-05)
- [x] **Phase 5: Domain Lexicon** - Business term extraction, curation, and graph edge integration — moved early to prevent downstream rework (completed 2026-03-05)
- [x] **Phase 6: Structural Risk Analysis** - Cyclomatic complexity, fan-in/out, DB write detection, and composite structural risk scoring (completed 2026-03-05)
- [x] **Phase 7: Domain-Aware Risk Analysis** - Domain criticality, security sensitivity, financial involvement, and enhanced composite scoring (completed 2026-03-05)
- [x] **Phase 8: Smart Chunking and Vector Indexing** - Semantic code chunks embedded and indexed in Qdrant with incremental re-indexing (completed 2026-03-06)
- [x] **Phase 9: Golden Module Pilot** - End-to-end validation of semantic pipeline on one bounded context before scaling (completed 2026-03-06)
- [x] **Phase 10: Continuous Indexing** - CI-triggered incremental graph and vector updates on changed files only (completed 2026-03-18)
- [x] **Phase 11: RAG Pipeline** - Multi-layer GraphRAG retrieval combining graph expansion and embedding similarity (completed 2026-03-18)
- [x] **Phase 12: Governance Dashboard** - Migration progress, risk clusters, dependency explorer, and lexicon coverage metrics (completed 2026-03-18)
- [x] **Phase 13: Risk-Prioritized Scheduling** - Data-driven module migration order recommendations (completed 2026-03-18)
- [ ] **Phase 14: MCP Server for AI-Powered Migration Context** - MCP protocol layer exposing all ESMP knowledge services as Claude Code tools via SSE transport

## Phase Details

### Phase 1: Infrastructure
**Goal**: Developer can run the full ESMP environment locally with all data stores healthy and a Spring Boot service ready to accept ingestion requests
**Depends on**: Nothing (first phase)
**Requirements**: INFRA-01, INFRA-02, INFRA-03
**Success Criteria** (what must be TRUE):
  1. `docker compose up` starts Neo4j, Qdrant, MySQL, Prometheus, Grafana, and the Spring Boot service with no errors
  2. Spring Boot service starts on Java 21 with virtual threads enabled and passes its health check endpoint
  3. Neo4j, Qdrant, and MySQL are each reachable from the Spring Boot service (connection verified at startup)
  4. Project compiles cleanly with professional-grade package structure, Flyway schema migrations applied to MySQL
  5. Spring Boot Actuator exposes health, info, and metrics endpoints
**Plans:** 2/2 plans complete

Plans:
- [x] 01-01-PLAN.md — Gradle project skeleton, Docker Compose environment, Spring Boot app with health indicators and config
- [x] 01-02-PLAN.md — Integration tests for health indicators and virtual threads, human verification of full environment

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
**Plans:** 3/3 plans complete

Plans:
- [x] 02-01-PLAN.md — OpenRewrite/Vaadin dependencies, Neo4j domain model entities, synthetic test fixtures, schema constraints
- [x] 02-02-PLAN.md — JavaSourceParser, ClassMetadataVisitor, CallGraphVisitor, VaadinPatternVisitor with unit tests
- [x] 02-03-PLAN.md — ExtractionService, REST endpoint, Neo4j persistence, integration tests, Vaadin audit report

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
**Plans:** 6/6 plans complete

Plans:
- [x] 03-01-PLAN.md — New Neo4j node entities, relationship properties, repositories, schema constraints, accumulator extensions
- [x] 03-02-PLAN.md — DependencyVisitor, JpaPatternVisitor, stereotype detection, mapper extensions, LinkingService, ExtractionService wiring
- [x] 03-03-PLAN.md — Graph query REST API (structural context, inheritance chain, transitive dependencies, search)
- [x] 03-04-PLAN.md — Gap closure: BINDS_TO edge detection in VaadinPatternVisitor and materialization in LinkingService
- [x] 03-05-PLAN.md — Gap closure: Stereotype label simple-name fallback and searchByName label hydration fix
- [x] 03-06-PLAN.md — Gap closure: HAS_ANNOTATION FQN mismatch, QUERIES repository-to-entity resolution, BINDS_TO simple-name fallback

### Phase 4: Graph Validation & Canonical Queries
**Goal**: Structural graph is verified correct before building semantic layers on top of it
**Depends on**: Phase 3
**Requirements**: GVAL-01, GVAL-02, GVAL-03, GVAL-04
**Success Criteria** (what must be TRUE):
  1. 20 canonical validation queries are defined and all pass against the populated graph
  2. Dependency cone accuracy is verified — graph-derived cones match manually verified architectural expectations
  3. No orphan nodes (unreachable from any module root) or duplicate structural nodes exist in the graph
  4. Inheritance chains are complete and transitive repository dependencies are correctly resolved
  5. Graph answers for a known sample module match senior engineer expectations
**Plans:** 2/2 plans complete

Plans:
- [x] 04-01-PLAN.md — Validation framework with 20 canonical Cypher queries, execution service, REST endpoint, and integration tests
- [x] 04-02-PLAN.md — Dependency cone REST endpoint with multi-relationship transitive traversal and integration tests

### Phase 5: Domain Lexicon
**Goal**: Business terms extracted from the codebase are stored in the graph with curated definitions, and developers can view and edit the lexicon
**Depends on**: Phase 4 (requires validated graph structure)
**Requirements**: LEX-01, LEX-02, LEX-03, LEX-04
**Optimization Note**: Moved earlier (was Phase 7) — domain terms influence graph relationships (USES_TERM), risk weighting, and retrieval ranking. Building the lexicon before risk analysis and chunking prevents re-enrichment and re-indexing rework.
**Success Criteria** (what must be TRUE):
  1. Business terms are automatically extracted from class names, enums, DB table/column names, Javadoc, and inline comments
  2. Each term in Neo4j stores: definition, synonyms, related classes, related tables, criticality, and migration sensitivity
  3. USES_TERM edges connect Code nodes to their relevant domain terms; DEFINES_RULE edges connect rule-implementing classes to their business rules
  4. Developer can open a lexicon UI, view all extracted terms, edit a term's definition and criticality, and save the change
  5. After curation, re-running extraction does not overwrite hand-edited term definitions
**Plans:** 3/3 plans complete

Plans:
- [x] 05-01-PLAN.md — BusinessTermNode model, LexiconVisitor for term extraction from class names/enums/Javadoc/DB schema, accumulator/mapper/service wiring
- [x] 05-02-PLAN.md — USES_TERM and DEFINES_RULE graph edges via LinkingService, LexiconService, LexiconController REST API, LexiconValidationQueryRegistry
- [x] 05-03-PLAN.md — Vaadin 24 Gradle setup, LexiconView grid UI with TermEditorDialog for inline curation

### Phase 6: Structural Risk Analysis
**Goal**: Every class in the graph has structural risk metrics and a composite structural risk score
**Depends on**: Phase 3
**Requirements**: RISK-01, RISK-02, RISK-03, RISK-04, RISK-05
**Optimization Note**: Split from original Phase 4 (Risk Analysis) — structural metrics are computed independently of domain terms, providing an early risk baseline.
**Success Criteria** (what must be TRUE):
  1. Every class node in Neo4j has a cyclomatic complexity value computed from its methods
  2. Every class node has fan-in (classes that call it) and fan-out (classes it calls) counts
  3. Every class node is flagged for DB write operations (INSERT/UPDATE/DELETE) detected in its method bodies
  4. Every class node has a composite structural risk score combining complexity, fan-in, fan-out, and DB write presence
  5. User can call a REST endpoint and receive a list of classes sorted by descending structural risk score
**Plans:** 2/2 plans complete

Plans:
- [x] 06-01-PLAN.md — ComplexityVisitor for cyclomatic complexity + DB write detection, accumulator/model/mapper extensions, ExtractionService pipeline wiring
- [x] 06-02-PLAN.md — RiskService (fan-in/out Cypher + composite score), RiskController REST API, RiskWeightConfig, RiskValidationQueryRegistry, integration tests

### Phase 7: Domain-Aware Risk Analysis
**Goal**: Risk scoring is enhanced with domain criticality, security sensitivity, and financial involvement for more accurate migration prioritization
**Depends on**: Phase 5 (Domain Lexicon) and Phase 6 (Structural Risk)
**Requirements**: DRISK-01, DRISK-02, DRISK-03, DRISK-04, DRISK-05
**Optimization Note**: Split from original Phase 4 — domain-aware risk requires the lexicon to exist first. Produces more accurate migration scheduling and avoids biased early prioritization.
**Success Criteria** (what must be TRUE):
  1. Every class has a domain criticality weight derived from its associated business terms' criticality ratings
  2. Classes handling authentication, authorization, or encryption are flagged with security sensitivity scores
  3. Classes involved in financial transactions (payment, billing, ledger) are flagged with financial involvement scores
  4. Business rule density is computed per class based on DEFINES_RULE edge count and rule complexity
  5. Enhanced composite risk score combines structural risk, domain criticality, security sensitivity, financial involvement, and business rule density
**Plans:** 2/2 plans complete

Plans:
- [x] 07-01-PLAN.md — Domain risk model extensions (ClassNode, RiskWeightConfig, application.yml) and 5 Cypher-based domain score computation methods in RiskService
- [x] 07-02-PLAN.md — API record extensions, sortBy heatmap parameter, DomainRiskValidationQueryRegistry, integration tests for all DRISK requirements

### Phase 8: Smart Chunking and Vector Indexing
**Goal**: Enriched semantic code chunks are indexed in Qdrant and incremental re-indexing updates only changed files
**Depends on**: Phase 7
**Requirements**: VEC-01, VEC-02, VEC-03, VEC-04
**Success Criteria** (what must be TRUE):
  1. A service class is chunked into distinct semantic units: one chunk per service method, one per validation block, one per UI binding block
  2. Each chunk payload includes graph neighbors (callers/callees), domain terms present, risk score (structural + domain-aware), and migration state
  3. Chunks are embedded using a local ONNX model and stored in Qdrant with their enriched payloads
  4. When a source file changes, only its affected chunks are re-embedded and updated in Qdrant (not a full collection rebuild)
  5. Qdrant collection is queryable by embedding similarity and returns chunks with their enrichment payloads
**Plans:** 2/2 plans complete

Plans:
- [x] 08-01-PLAN.md — Spring AI transformer dependency, CodeChunk domain model, QdrantCollectionInitializer, VectorConfig, ChunkIdGenerator, ChunkingService with Neo4j enrichment
- [x] 08-02-PLAN.md — VectorIndexingService (embed + upsert + incremental reindex), VectorIndexController REST API, VectorValidationQueryRegistry, integration tests

### Phase 9: Golden Module Pilot
**Goal**: Semantic pipeline is validated end-to-end on one bounded context before scaling to the full codebase
**Depends on**: Phase 8
**Requirements**: GMP-01, GMP-02, GMP-03
**Optimization Note**: Prevents scaling flawed enrichment logic. Select one bounded context and execute full chunking, domain enrichment, RAG retrieval, and risk computation. Validate results with senior engineers before proceeding.
**Success Criteria** (what must be TRUE):
  1. One bounded context (module) is selected and fully processed through chunking, domain enrichment, and vector indexing
  2. RAG retrieval for the pilot module returns contextually relevant results validated by senior engineers
  3. Risk computation and migration recommendation for the pilot module aligns with expert expectations
  4. Any pipeline issues discovered are documented and fixed before proceeding to full-scale execution
**Plans:** 2/2 plans complete

Plans:
- [x] 09-01-PLAN.md — Synthetic pilot fixtures (20 classes), PilotService (recommendation + validation), VectorSearchService, PilotValidationQueryRegistry, response records
- [x] 09-02-PLAN.md — PilotController + VectorSearchController REST endpoints, full pipeline integration tests, golden regression tests

### Phase 10: Continuous Indexing
**Goal**: As the legacy codebase undergoes active development, the knowledge graph and vector store stay current without requiring manual re-runs
**Depends on**: Phase 9
**Requirements**: CI-01, CI-02, CI-03, SLO-03, SLO-04
**Optimization Note**: Moved earlier (was Phase 9) — prevents graph and vector drift during development. Keeps graph and vector store aligned with ongoing development, avoiding large re-sync operations.
**Success Criteria** (what must be TRUE):
  1. A CI build hook (Gradle task or webhook endpoint) triggers extraction when source files change
  2. Only files whose hash has changed since last extraction are re-processed (not a full rebuild)
  3. Neo4j graph nodes and edges for changed files are updated or created incrementally without touching unaffected nodes
  4. Qdrant embeddings for changed file chunks are updated; embeddings for unchanged files remain untouched
  5. Incremental update of 5 changed files completes in under 30 seconds; full re-index of 100-class module completes in under 5 minutes
**Plans:** 2/2 plans complete

Plans:
- [x] 10-01-PLAN.md — FileHashUtil, IncrementalIndexingService orchestrator, request/response records, ChunkingService FQN-filtered overload
- [x] 10-02-PLAN.md — IndexingController REST endpoint, integration tests with synthetic fixtures, SLO verification

### Phase 11: RAG Pipeline
**Goal**: Developer can query "what classes/services relate to X?" and receive a ranked, graph-aware retrieval result that assembles correct migration context
**Depends on**: Phase 10
**Requirements**: RAG-01, RAG-02, RAG-03, RAG-04, SLO-01, SLO-02
**Success Criteria** (what must be TRUE):
  1. Given a focal class, the system traverses the Neo4j graph to retrieve its dependency cone (directly and transitively related nodes)
  2. The system performs embedding similarity search in Qdrant constrained to the dependency cone (not the full collection)
  3. Graph expansion results and vector similarity results are merged and re-ranked by risk score into a single retrieval context
  4. Developer queries "what classes relate to CustomerOrderService?" via REST and receives a structured, ranked list with relationship context
  5. Retrieval context for a known Vaadin 7 view correctly includes its backing service, repository, and domain entities
  6. Graph dependency cone query completes in under 200ms; RAG context assembly completes in under 1.5 seconds for a 50-node cone
**Plans:** 2/2 plans complete

Plans:
- [x] 11-01-PLAN.md — RAG API records (RagRequest, RagResponse, FocalClassDetail, ContextChunk, ScoreBreakdown, ConeSummary, DisambiguationResponse), RagWeightConfig, VectorSearchService cone-constrained search extension
- [x] 11-02-PLAN.md — RagService orchestrator (resolve, cone, embed, search, merge, rank), RagController REST endpoint, RagValidationQueryRegistry, integration tests

### Phase 12: Governance Dashboard
**Goal**: Developer can see the current state of the migration — what is done, what is risky, and what still uses Vaadin 7 APIs — in a single dashboard
**Depends on**: Phase 11
**Requirements**: DASH-01, DASH-02, DASH-03, DASH-04, DASH-05, DASH-06
**Success Criteria** (what must be TRUE):
  1. Dashboard shows the percentage of Vaadin 7 API usages remaining, broken down by module
  2. Dashboard provides an interactive dependency graph explorer where clicking a node reveals its callers, callees, and risk score
  3. Dashboard shows the business concept graph with domain terms linked to their implementing classes
  4. Dashboard highlights risk hotspot clusters (groups of high-risk, highly-coupled classes)
  5. Dashboard shows lexicon coverage percentage (terms with curated definitions vs total extracted terms)
  6. Dashboard shows a migration progress heatmap across all modules with color encoding for migration state
**Plans:** 3/3 plans complete

Plans:
- [x] 12-01-PLAN.md — DashboardService with 6 Neo4j Cypher aggregation queries, DTO records, integration tests
- [x] 12-02-PLAN.md — MainLayout AppLayout shell, CytoscapeGraph Java/JS wrapper component, LexiconView routing update
- [x] 12-03-PLAN.md — DashboardView with metric cards, heatmap, risk clusters, dependency graph explorer, business concept graph

### Phase 13: Risk-Prioritized Scheduling
**Goal**: System recommends a migration order for modules that accounts for dependency risk, change frequency, and complexity — so the developer starts with the safest targets
**Depends on**: Phase 12
**Requirements**: SCHED-01, SCHED-02
**Success Criteria** (what must be TRUE):
  1. Developer calls a REST endpoint and receives an ordered list of modules recommended for migration, from lowest-risk to highest-risk
  2. Recommendation score incorporates composite risk score (structural + domain-aware), number of dependents, recent change frequency from git history, and cyclomatic complexity distribution
  3. Developer can view the rationale for each module's position in the recommendation list (which factors dominate its score)
  4. Re-running the recommendation after ingesting new modules produces an updated ordered list reflecting the new dependency landscape
**Plans:** 2/2 plans complete

Plans:
- [x] 13-01-PLAN.md — SchedulingWeightConfig, GitFrequencyService, API records, SchedulingService (Neo4j aggregation + topological sort + composite scoring), SchedulingController, SchedulingValidationQueryRegistry, integration tests
- [x] 13-02-PLAN.md — ScheduleView Vaadin UI (wave lanes + sortable table + CytoscapeGraph drill-down), MainLayout sidebar update, human verification

### Phase 14: MCP Server for AI-Powered Migration Context
**Goal**: Claude Code can connect to ESMP via MCP protocol and use 6 tools to retrieve structured, domain-aware migration context during Vaadin 7 to Vaadin 24 migration work
**Depends on**: Phase 13
**Requirements**: MCP-01, MCP-02, MCP-03, MCP-04, MCP-05, MCP-06, MCP-07, MCP-08, SLO-MCP-01, SLO-MCP-02
**Success Criteria** (what must be TRUE):
  1. MCP server starts and exposes `/mcp/sse` endpoint alongside existing Vaadin UI and REST APIs
  2. `get_migration_context` returns unified context (dependency cone, risk, domain terms, business rules, code chunks) for a given class FQN
  3. `search_knowledge` returns ranked code chunks from semantic vector search with optional module/stereotype filters
  4. `get_dependency_cone` returns graph nodes and edges for a class FQN
  5. `get_risk_analysis` returns risk heatmap or class-level detail with per-method complexity
  6. `browse_domain_terms` returns lexicon terms filtered by search text or criticality
  7. `validate_system_health` runs all 41 validation queries and returns pass/fail report
  8. Graceful degradation returns partial context with warnings when a downstream service fails
  9. Caffeine cache is hit on repeated calls and evicted after incremental reindex
  10. `get_migration_context` completes in under 1.5s; `search_knowledge` completes in under 500ms
**Plans:** 2 plans

Plans:
- [ ] 14-01-PLAN.md — Dependencies (MCP WebMVC starter, Caffeine), config (McpConfig, McpCacheConfig, McpObservabilityConfig), response records (MigrationContext, AssemblerWarning), MigrationContextAssembler with parallel async assembly and token budgeting
- [ ] 14-02-PLAN.md — MigrationToolService (6 @Tool methods), McpToolRegistration, McpCacheEvictionService, IncrementalIndexingService cache eviction wiring, .mcp.json, integration tests, human verification

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10 -> 11 -> 12 -> 13 -> 14

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Infrastructure | 2/2 | Complete | 2026-03-04 |
| 2. AST Extraction | 3/3 | Complete | 2026-03-04 |
| 3. Code Knowledge Graph | 6/6 | Complete | 2026-03-04 |
| 4. Graph Validation & Canonical Queries | 2/2 | Complete | 2026-03-05 |
| 5. Domain Lexicon | 3/3 | Complete | 2026-03-05 |
| 6. Structural Risk Analysis | 2/2 | Complete | 2026-03-05 |
| 7. Domain-Aware Risk Analysis | 2/2 | Complete | 2026-03-05 |
| 8. Smart Chunking and Vector Indexing | 2/2 | Complete | 2026-03-06 |
| 9. Golden Module Pilot | 2/2 | Complete   | 2026-03-06 |
| 10. Continuous Indexing | 2/2 | Complete    | 2026-03-18 |
| 11. RAG Pipeline | 2/2 | Complete    | 2026-03-18 |
| 12. Governance Dashboard | 3/3 | Complete    | 2026-03-18 |
| 13. Risk-Prioritized Scheduling | 2/2 | Complete    | 2026-03-18 |
| 14. MCP Server | 0/2 | Planning    | - |
