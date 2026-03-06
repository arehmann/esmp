---
phase: 09-golden-module-pilot
plan: 01
subsystem: api
tags: [qdrant, neo4j, spring-ai, embeddings, validation, pilot, vaadin7, synthetic-fixtures]

# Dependency graph
requires:
  - phase: 08-smart-chunking-vector-indexing
    provides: VectorIndexingService, ChunkingService, EmbeddingModel bean, Qdrant code_chunks collection, CodeChunk record
  - phase: 04-graph-validation-canonical-queries
    provides: ValidationService, ValidationQueryRegistry extensibility pattern, ValidationReport record
  - phase: 07-domain-aware-risk-analysis
    provides: enhancedRiskScore on ClassNode, RiskService
  - phase: 05-domain-lexicon
    provides: BusinessTermNode, USES_TERM edges, LexiconValidationQueryRegistry
provides:
  - 20 synthetic pilot fixture Java source files in com.esmp.pilot package covering all pipeline code paths
  - PilotService with recommendModules() Cypher scoring and validateModule() orchestration
  - VectorSearchService with EmbeddingModel + Qdrant searchAsync similarity search
  - PilotValidationQueryRegistry extending ValidationQueryRegistry with 3 pilot-specific violation queries
  - 6 response records: ModuleRecommendation, PilotCheck, PilotValidationReport, SearchRequest, ChunkSearchResult, SearchResponse
affects: [09-02, 10-rest-api-controllers, 11-rag-pipeline, 12-migration-diff]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "PilotValidationQueryRegistry extends ValidationQueryRegistry with hardcoded 'pilot' static violation Cypher"
    - "Module-scoped checks use Neo4jClient .bind(moduleName).to('module') parameterization (not registry)"
    - "Qdrant scroll-based chunk count via ScrollPoints with module payload filter (no countAsync)"
    - "VectorSearchService maps ScoredPoint.getPayloadMap() to ChunkSearchResult via type-safe helpers"

key-files:
  created:
    - src/main/java/com/esmp/pilot/application/PilotService.java
    - src/main/java/com/esmp/vector/application/VectorSearchService.java
    - src/main/java/com/esmp/pilot/validation/PilotValidationQueryRegistry.java
    - src/main/java/com/esmp/pilot/api/ModuleRecommendation.java
    - src/main/java/com/esmp/pilot/api/PilotCheck.java
    - src/main/java/com/esmp/pilot/api/PilotValidationReport.java
    - src/main/java/com/esmp/vector/api/SearchRequest.java
    - src/main/java/com/esmp/vector/api/ChunkSearchResult.java
    - src/main/java/com/esmp/vector/api/SearchResponse.java
    - src/test/resources/fixtures/pilot/ (20 synthetic Java source files)
  modified: []

key-decisions:
  - "Synthetic pilot fixtures use package com.esmp.pilot so ChunkingService.deriveModule() returns 'pilot'"
  - "Module-scoped validation checks live in PilotService (parameterized) not PilotValidationQueryRegistry (static Cypher)"
  - "Qdrant module chunk count uses scroll-based pagination — countAsync not used in this codebase"
  - "PilotValidationReport.chunkCount is long (not int) to align with scroll-count return type"
  - "VectorSearchService throws RuntimeException on Qdrant failure (not checked exception) — consistent with service layer pattern"

patterns-established:
  - "Pilot fixture package com.esmp.pilot: ensures correct module derivation by ChunkingService"
  - "Pilot module validation: global ValidationService.runAllValidations() + parameterized Neo4jClient for module-scoped metrics"

requirements-completed: [GMP-01, GMP-02, GMP-03]

# Metrics
duration: 6min
completed: 2026-03-06
---

# Phase 9 Plan 01: Pilot Service Infrastructure Summary

**PilotService with module recommendation Cypher + validation orchestration, VectorSearchService with EmbeddingModel similarity search, PilotValidationQueryRegistry with 3 violation queries, 6 response records, and 20 synthetic pilot fixtures covering all pipeline code paths**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-06T09:16:49Z
- **Completed:** 2026-03-06T09:23:00Z
- **Tasks:** 2
- **Files modified:** 29

## Accomplishments

- 20 synthetic Java source files created in `src/test/resources/fixtures/pilot/` using `package com.esmp.pilot` — covering Service(4), Repository(3), Entity(3), VaadinView(3), VaadinDataBinding(2), rule/calc(2), enum(3) stereotypes; include Javadoc on 6 classes and branching logic for varied CC values
- PilotService implements `recommendModules()` (module scoring Cypher with Vaadin density 0.4, risk diversity 0.3, size 0.3 weights) and `validateModule(String)` (5-step orchestration with markdown report generation)
- VectorSearchService implements `search(SearchRequest)` embedding via EmbeddingModel, Qdrant searchAsync with optional module/stereotype/chunkType filters, ScoredPoint payload mapping
- PilotValidationQueryRegistry adds 3 static violation queries (PILOT_MODULE_CLASS_COUNT, PILOT_VAADIN7_NODES_PRESENT, PILOT_BUSINESS_TERMS_EXTRACTED) automatically picked up by ValidationService

## Task Commits

Each task was committed atomically:

1. **Task 1: Synthetic pilot fixtures, response records, and PilotValidationQueryRegistry** - `4930fe4` (feat)
2. **Task 2: PilotService and VectorSearchService implementation** - `7a9b930` (feat)

**Plan metadata:** (created in final commit)

## Files Created/Modified

- `src/test/resources/fixtures/pilot/InvoiceService.java` — Service with if/else branching (CC>1), InvoiceRepository injection
- `src/test/resources/fixtures/pilot/PaymentService.java` — Service with try/catch, PaymentRepository + InvoiceService injection, financial name heuristics
- `src/test/resources/fixtures/pilot/CustomerService.java` — Service with high fan-out (3 DEPENDS_ON edges)
- `src/test/resources/fixtures/pilot/AuditService.java` — Service with security method names (logSecurityEvent, verifyAuthentication)
- `src/test/resources/fixtures/pilot/InvoiceRepository.java` — @Query SELECT triggers QUERIES edge
- `src/test/resources/fixtures/pilot/CustomerRepository.java` — JpaRepository with findByRole, findByEmail
- `src/test/resources/fixtures/pilot/PaymentRepository.java` — @Modifying @Query UPDATE triggers DB write detection
- `src/test/resources/fixtures/pilot/InvoiceEntity.java` — @Entity @Table(name="invoice") triggers MAPS_TO_TABLE
- `src/test/resources/fixtures/pilot/CustomerEntity.java` — @Entity @Table(name="customer")
- `src/test/resources/fixtures/pilot/PaymentEntity.java` — @Entity @Table(name="payment") with amount/currency
- `src/test/resources/fixtures/pilot/InvoiceView.java` — implements com.vaadin.navigator.View, @SpringView(name="invoice")
- `src/test/resources/fixtures/pilot/CustomerView.java` — Vaadin 7 view for customer management
- `src/test/resources/fixtures/pilot/PaymentView.java` — Vaadin 7 view with ComboBox status filter
- `src/test/resources/fixtures/pilot/InvoiceForm.java` — BeanFieldGroup<InvoiceEntity> triggers BINDS_TO
- `src/test/resources/fixtures/pilot/CustomerForm.java` — BeanFieldGroup<CustomerEntity> triggers BINDS_TO
- `src/test/resources/fixtures/pilot/InvoiceValidator.java` — Validator pattern, 6-branch status transition method (DEFINES_RULE)
- `src/test/resources/fixtures/pilot/PaymentCalculator.java` — switch/case CC, financial heuristics (DEFINES_RULE)
- `src/test/resources/fixtures/pilot/InvoiceStatusEnum.java` — 5 constants, triggers domain term extraction
- `src/test/resources/fixtures/pilot/PaymentStatusEnum.java` — 4 constants
- `src/test/resources/fixtures/pilot/CustomerRole.java` — 4 constants (ADMIN, USER, MANAGER, AUDITOR)
- `src/main/java/com/esmp/pilot/api/ModuleRecommendation.java` — response record with 7 fields
- `src/main/java/com/esmp/pilot/api/PilotCheck.java` — response record (name, status, detail)
- `src/main/java/com/esmp/pilot/api/PilotValidationReport.java` — imports ValidationReport directly (no circular dep)
- `src/main/java/com/esmp/vector/api/SearchRequest.java` — request record with query + 4 optional filters
- `src/main/java/com/esmp/vector/api/ChunkSearchResult.java` — 13-field result record
- `src/main/java/com/esmp/vector/api/SearchResponse.java` — wraps List<ChunkSearchResult>
- `src/main/java/com/esmp/pilot/validation/PilotValidationQueryRegistry.java` — @Component extending ValidationQueryRegistry
- `src/main/java/com/esmp/pilot/application/PilotService.java` — 300+ line orchestration service
- `src/main/java/com/esmp/vector/application/VectorSearchService.java` — 150+ line search service

## Decisions Made

- Synthetic pilot fixtures use `package com.esmp.pilot` — critical for `ChunkingService.deriveModule()` to return "pilot" (RESEARCH pitfall #2)
- Module-scoped validation checks (e.g., "has >= 15 classes") implemented in PilotService with `.bind()` parameters, NOT in PilotValidationQueryRegistry — registry only holds static violation Cypher (RESEARCH pitfall #4)
- `countAsync` not used for Qdrant module chunk count — scroll-based pagination with Filter instead, consistent with existing codebase pattern
- `PilotValidationReport.chunkCount` is `long` not `int` — scroll count returns long, avoids narrowing conversion
- VectorSearchService wraps Qdrant exceptions in RuntimeException for consistent service layer error propagation

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- PilotService and VectorSearchService are complete service-layer implementations
- Plan 02 will add REST controllers: `GET /api/pilot/recommend`, `GET /api/pilot/validate/{module}`, `POST /api/vector/search`
- Synthetic fixtures are ready for pipeline integration test in plan 02

---
*Phase: 09-golden-module-pilot*
*Completed: 2026-03-06*
