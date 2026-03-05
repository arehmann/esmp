# Phase 5: Domain Lexicon - Context

**Gathered:** 2026-03-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Business terms are automatically extracted from the codebase (class names, enums, DB schema, Javadoc) and stored in Neo4j with curated definitions, synonyms, criticality, and migration sensitivity. USES_TERM and DEFINES_RULE graph edges connect terms to code. A Vaadin 24 lexicon UI lets developers view all extracted terms, edit definitions and criticality, and save changes. Hand-edited term definitions survive re-extraction runs.

</domain>

<decisions>
## Implementation Decisions

### Term extraction rules
- Split camelCase/PascalCase class names into **individual words only** (no compound terms) — 'InvoicePaymentService' → 'Invoice', 'Payment'
- **Strip technical suffixes** before extraction — Service, Repository, Controller, Impl, Abstract, Base, DTO, Entity are stop-words
- **Enum type name + individual constants** both become terms — 'PaymentStatus' enum → 'Payment Status', 'Active', 'Pending Approval'
- **Javadoc class-level only** for term/definition extraction — method-level and inline comments are too noisy
- DB table and column names are split on underscores and treated as term sources

### Curation & re-extraction
- **Never overwrite hand-edited definitions** — mark edited terms with a 'curated' boolean flag; re-extraction skips curated terms entirely
- **Editable fields:** definition, criticality, and synonyms — other fields (source, relationships, usage count) are auto-derived
- **Auto-seed definitions from Javadoc** — use class-level Javadoc as initial definition text; terms without Javadoc get blank definitions
- **Auto-add new terms on re-extraction**, flag them as 'new/uncurated' status — user can filter to see only new terms for review

### Lexicon UI approach
- **Vaadin 24** — Java-only UI framework matching the Spring Boot stack; sets the pattern for Phase 12 (Governance Dashboard)
- **Searchable, sortable, filterable data grid** — columns: term, definition, criticality, source type, usage count, curated status
- **No bulk operations for now** — single-term editing only; keep the UI simple for v1
- **Usage count in grid, detail on click** — grid shows count (e.g., '12 classes'); clicking expands to list of related FQNs

### Term metadata & scoring
- **3-level criticality: Low / Medium / High** — simple, decisive, feeds into Phase 7 risk weighting
- **Heuristic seeding for criticality** — keyword patterns auto-assign: financial terms (payment, invoice, billing) → High; auth/security terms → High; generic utility terms → Low; user can override
- **3-level migration sensitivity: None / Moderate / Critical** — matches criticality pattern; 'None' = safe to auto-migrate, 'Critical' = needs domain expert review
- **Pattern-based business rule detection** — classes with names containing 'Validator', 'Rule', 'Policy', 'Constraint', 'Calculator', 'Strategy' are DEFINES_RULE candidates; also classes with validation annotations

### Claude's Discretion
- Exact technical suffix stop-word list (beyond the core set listed above)
- Vaadin 24 view implementation details (routing, layout components, form binding)
- Neo4j schema design for BusinessTermNode (properties, constraints, indexes)
- USES_TERM edge creation heuristics (how aggressively to link terms to code)
- Validation query definitions for LexiconValidationQueryRegistry
- Grid filtering/sorting implementation details
- Definition auto-seeding logic when Javadoc is ambiguous or multi-sentence

</decisions>

<specifics>
## Specific Ideas

- The lexicon grid should feel like a professional data management tool — think spreadsheet, not blog. Migration engineers need to quickly scan and curate hundreds of terms.
- Phase 4's extensible ValidationQueryRegistry was designed to accept Phase 5's USES_TERM validation queries — create a LexiconValidationQueryRegistry as a new @Component.
- The 'curated' flag is the key to LEX-04 compliance — it's the mechanism that prevents re-extraction from overwriting hand-edited definitions.
- Heuristic criticality seeding saves significant curation time — most enterprise codebases have predictable domain patterns (finance, auth, user management).

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- **ExtractionAccumulator** (`visitor/ExtractionAccumulator.java`): Proven multi-visitor data collection pattern; extend with `Map<String, BusinessTermData>` and `addBusinessTerm()` method
- **Visitor pattern** (5 existing `JavaIsoVisitor<ExtractionAccumulator>` implementations): Template ready for new `LexiconVisitor`
- **AccumulatorToModelMapper** (`application/AccumulatorToModelMapper.java`): Add `mapToBusinessTermNodes()` method following existing patterns
- **LinkingService** (`application/LinkingService.java`): Idempotent Cypher MERGE patterns for USES_TERM and DEFINES_RULE edge creation
- **Neo4jSchemaInitializer** (`config/Neo4jSchemaInitializer.java`): Add uniqueness constraint for BusinessTermNode
- **ValidationQueryRegistry** (`graph/validation/ValidationQueryRegistry.java`): Extensible @Component pattern — create LexiconValidationQueryRegistry

### Established Patterns
- Neo4jClient `.query(cypher).bind(param).to("name")` for all complex Cypher queries
- Response record classes in `com.esmp.graph.api` package
- `@GetMapping` with `{fqn:.+}` regex suffix for path variables with dots
- Testcontainers Neo4j for integration tests
- Controller → Service → Repository/Neo4jClient layering
- `@DynamicLabels` for stereotype labels on nodes
- `putIfAbsent` deduplication in ExtractionAccumulator

### Integration Points
- Add `LexiconVisitor` to `ExtractionService.extract()` visitor sequence
- Create `BusinessTermNode` in `extraction/model/` following ClassNode patterns (@Id, @Version, business key)
- Create `BusinessTermNodeRepository` in `extraction/persistence/`
- Add `linkBusinessTermUsages()` and `linkBusinessRules()` to LinkingService
- New `LexiconController` under `/api/lexicon/` for REST API
- New Vaadin view class for lexicon UI (first Vaadin view in the project)
- Add BusinessTermNode constraint to Neo4jSchemaInitializer

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 05-domain-lexicon*
*Context gathered: 2026-03-05*
