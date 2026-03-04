# Phase 2: AST Extraction - Context

**Gathered:** 2026-03-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Parse Java/Vaadin 7 source code into structured AST using OpenRewrite LST, extract class metadata, method signatures, field definitions, annotations, and imports, build call graph edges between methods across classes, and persist all extracted nodes and relationships to Neo4j. Includes a Vaadin 7 pattern audit to address the LOW confidence blocker on OpenRewrite coverage.

</domain>

<decisions>
## Implementation Decisions

### Source input strategy
- Config property (`esmp.target.source-root`) as default source path, REST endpoint can override per request
- REST trigger only: POST /api/extraction/trigger — developer explicitly kicks off extraction
- Full recursive scan by default, optional module filter parameter to narrow scope
- Synchronous request — blocks until extraction finishes, returns summary

### OpenRewrite usage model
- Embed rewrite-java parser library directly in ESMP as a dependency
- The Phase 1 classpath isolation decision ("OpenRewrite as Gradle plugin on target codebase only") applies to recipe EXECUTION (Vaadin migration transforms), not to AST PARSING
- Type-attributed parsing — provide target module's compiled classpath for fully resolved types and accurate call graphs
- Classpath provided via pre-exported classpath file: target project runs a Gradle task (e.g. `./gradlew exportClasspath`) that writes classpath to a file, ESMP reads it

### Vaadin 7 capture depth
- Target codebase uses mix of everything: UI/Views, data binding, custom components, custom themes/widgetsets, server push
- Include explicit Vaadin 7 pattern audit report: after extraction, produce report showing what patterns were found and what OpenRewrite could/couldn't parse
- Add secondary Neo4j labels for Vaadin-specific nodes: :VaadinView, :VaadinComponent, :VaadinDataBinding — enables easy querying of all Vaadin artifacts
- Capture Vaadin component trees: extract parent-child layout hierarchy (UI → VerticalLayout → HorizontalLayout → Button) stored as CONTAINS_COMPONENT edges

### Sample module strategy
- Synthetic test fixtures for automated tests: 5-10 representative Java files covering Vaadin UI class, View with Navigator, service with injected repos, repository, entity, data-bound form
- Real Vaadin 7 as test-only dependency — fixtures compile against real Vaadin 7 classes for accurate type resolution
- Manual validation against real legacy module as documented Phase 2 task: run extraction, compare graph output against manually verified expectations, document gaps
- Both synthetic and real validation — synthetic for repeatable CI, real for confidence

### Claude's Discretion
- Neo4j node property schema (which properties on Class, Method, Field nodes)
- Exact OpenRewrite rewrite-java parser configuration
- Idempotency implementation strategy (hash-based, version-based, etc.)
- Error handling for unparseable files
- Extraction summary response format
- Gradle export classpath task design for target project

</decisions>

<specifics>
## Specific Ideas

- The Vaadin 7 audit report should directly address the STATE.md blocker: "OpenRewrite Vaadin 7 recipe coverage is LOW confidence — hands-on audit required in Phase 2"
- Component tree extraction captures the layout hierarchy statically from source code (new() calls and addComponent() chains), not from runtime

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- `com.esmp.infrastructure.config.QdrantConfig`: Pattern for external service configuration — similar config class needed for OpenRewrite parser settings
- `com.esmp.infrastructure.health.QdrantHealthIndicator`: Pattern for health checks — can add Neo4j extraction health indicator
- `com.esmp.infrastructure.startup.DataStoreStartupValidator`: Pattern for startup validation — extraction service can validate target source path exists at startup
- Spring Data Neo4j already on classpath (spring-boot-starter-data-neo4j) — use for entity persistence

### Established Patterns
- Package-by-feature: extraction code goes in `com.esmp.extraction`
- Gradle Kotlin DSL with version catalog for dependency management
- JUnit 5 + Testcontainers for integration tests
- Spotless for code formatting (Google Java Style)
- Spring profiles for environment-specific config

### Integration Points
- Neo4j: Spring Data Neo4j for persisting extracted AST nodes and relationships
- REST API: Spring Web for extraction trigger endpoint
- Application config: application.yml for target source root property
- Testcontainers Neo4j already configured for integration tests

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 02-ast-extraction*
*Context gathered: 2026-03-04*
