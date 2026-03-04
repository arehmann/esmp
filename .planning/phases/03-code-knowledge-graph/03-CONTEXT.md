# Phase 3: Code Knowledge Graph - Context

**Gathered:** 2026-03-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Neo4j graph contains the full structural model of the codebase with all node types (Class, Method, Field, Annotation, Package, Module, UI View, Service, Repository, DB Table), all relationship edges (CALLS, EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE), and is queryable via REST API endpoints. Phase 2's extraction pipeline is extended with new visitors and a post-extraction linking pass. A new `com.esmp.graph` package provides query endpoints.

</domain>

<decisions>
## Implementation Decisions

### Node type modeling
- Stereotype types (Service, Repository, UIView) use `@DynamicLabels` on ClassNode — same pattern as existing VaadinView/VaadinComponent/VaadinDataBinding labels
- Annotation becomes a first-class `AnnotationNode` in Neo4j with `HAS_ANNOTATION` edges from Class/Method/Field — enables queries like "find all classes with @Transactional"
- Package and Module both become first-class Neo4j nodes (`PackageNode`, `ModuleNode`) with hierarchy: Module → CONTAINS_PACKAGE → Package → CONTAINS_CLASS → Class
- Module = Gradle subproject or source root directory
- DB Table becomes a first-class `DBTableNode` with table name and schema properties, linked via `MAPS_TO_TABLE` edges from entity classes

### Relationship materialization
- EXTENDS and IMPLEMENTS: Post-extraction linking pass — after all classes are parsed and persisted, a separate linking step creates Neo4j relationship edges between ClassNode pairs using the existing `superClass` and `implementedInterfaces` string properties
- DEPENDS_ON: Derived from field injection (`@Autowired`, `@Inject` fields) and constructor parameter types — a class DEPENDS_ON another if it has a field typed as that class or takes it as a constructor argument
- BINDS_TO: Specifically for Vaadin 7 data binding connections — links a Vaadin UI View/Form to the entity/DTO it binds to (via BeanFieldGroup, FieldGroup, etc.)
- Relationships carry metadata properties: CALLS already has sourceFile+lineNumber; DEPENDS_ON carries injection type (constructor, field, setter); EXTENDS/IMPLEMENTS carry resolution confidence

### DB Table detection
- Annotation-based detection only: @Table, @Entity for MAPS_TO_TABLE; @Query annotations and Spring Data derived query method names (findByX) for QUERIES
- Target codebase is primarily JPA/Spring Data — annotation-based detection covers the dominant patterns
- Table name resolution: when @Entity exists but no @Table, derive table name using JPA default naming convention (e.g., CustomerOrder → customer_order)
- QUERIES edges are Method → DBTable (not Class → DBTable) for precise method-level impact analysis

### Query API design
- New package `com.esmp.graph` with dedicated REST controller — separation of concerns: extraction populates, graph queries
- Complex queries (inheritance chains, transitive dependencies) use custom Cypher via `@Query` annotations on repository methods — SDN derived queries cannot express variable-length path patterns
- Response format: flat JSON with nested arrays (className, methods, fields, dependencies, annotations, inheritanceChain) — simple, frontend-friendly, testable
- Primary lookup by fully qualified name (exact match); also support simple name search endpoint (GET /api/graph/search?name=X) returning a list of matches

### Claude's Discretion
- Exact Cypher query patterns for transitive dependency traversal
- AnnotationNode property schema (retention policy, target types, etc.)
- PackageNode/ModuleNode property design
- Neo4j index strategy for query performance
- Error handling for unresolved types in linking pass
- Whether to add new visitors or extend existing ones
- Batch size for post-extraction linking

</decisions>

<specifics>
## Specific Ideas

- The linking pass should be idempotent like extraction — re-running should not create duplicate edges
- Module detection should work with both multi-module Gradle builds (each subproject = module) and single-module projects (source root = module)
- The 3 required API capabilities map to success criteria: (1) structural context for a class, (2) inheritance chain, (3) service→repository transitive dependencies

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ClassNode` with `@DynamicLabels extraLabels`: Pattern for adding stereotype labels (Service, Repository, UIView) without new node types
- `ExtractionAccumulator`: Central data collector — extend with new data types for annotations, packages, modules, DB tables
- `AccumulatorToModelMapper`: Maps accumulator data to Neo4j entities — extend for new node types
- `ClassMetadataVisitor`: Already extracts class metadata, annotations, superClass, implementedInterfaces — extend or create parallel visitors for new data
- `CallGraphVisitor`: Pattern for relationship extraction — reference for building DEPENDS_ON, QUERIES visitors
- `VaadinPatternVisitor`: Pattern for detecting framework-specific patterns — reference for DB/JPA pattern detection
- `ClassNodeRepository` with `@Query` Cypher: Pattern for custom Neo4j queries
- `Neo4jTransactionConfig`: Dual transaction manager already configured
- `Neo4jSchemaInitializer`: Existing schema constraint setup — extend for new node types

### Established Patterns
- Business-key `@Id` with `@Version` for idempotent MERGE semantics on all node types
- Visitor pattern: each concern gets its own visitor class traversing OpenRewrite LST
- Pipeline: scan → parse → visit → map → persist (ExtractionService orchestrates)
- REST: controller → service → repository layering
- Testcontainers Neo4j for integration tests

### Integration Points
- ExtractionService.extract(): Must be extended to run new visitors and the post-extraction linking pass
- ClassNodeRepository: Extend or create sibling repositories for new node types (AnnotationNode, PackageNode, ModuleNode, DBTableNode)
- New `/api/graph/` endpoints in `com.esmp.graph` package, separate from `/api/extraction/`
- Existing `/api/extraction/trigger` stays unchanged — Phase 3 extends what happens after extraction

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 03-code-knowledge-graph*
*Context gathered: 2026-03-04*
