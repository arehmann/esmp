---
phase: 16-openrewrite-recipe-based-migration-engine
plan: 03
subsystem: api
tags: [spring-mvc, mcp, openrewrite, vaadin-migration, rest-api, testcontainers]

# Dependency graph
requires:
  - phase: 16-02
    provides: MigrationRecipeService with generatePlan/preview/applyAndWrite/applyModule/getModuleSummary
  - phase: 14-mcp-server-for-ai-powered-migration-context
    provides: MigrationToolService pattern with @Tool, @Timed, MeterRegistry counter instrumentation

provides:
  - REST API with 5 migration endpoints at /api/migration/*
  - MigrationController (plan, summary, preview, apply, apply-module endpoints)
  - MigrationRequest and ModuleMigrationRequest request records
  - 3 new MCP tools in MigrationToolService (getMigrationPlan, applyMigrationRecipes, getModuleMigrationSummary)
  - MigrationControllerIntegrationTest (7 tests)
  - MigrationMcpToolIntegrationTest (4 tests)

affects: [mcp-client-usage, phase-17-if-any, dashboard-migration-tab]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - MCP tool uses preview() not applyAndWrite() to return diff-only without disk write
    - {fqn:.+} regex suffix on all FQN path variables prevents Spring MVC dot-truncation
    - @Tool methods call dedicated service methods; MeterRegistry counter + @Timed per tool

key-files:
  created:
    - src/main/java/com/esmp/migration/api/MigrationController.java
    - src/main/java/com/esmp/migration/api/MigrationRequest.java
    - src/main/java/com/esmp/migration/api/ModuleMigrationRequest.java
    - src/test/java/com/esmp/migration/api/MigrationControllerIntegrationTest.java
    - src/test/java/com/esmp/mcp/tool/MigrationMcpToolIntegrationTest.java
  modified:
    - src/main/java/com/esmp/mcp/tool/MigrationToolService.java
    - src/main/java/com/esmp/mcp/config/McpToolRegistration.java

key-decisions:
  - "applyMigrationRecipes MCP tool calls preview() not applyAndWrite() — Claude handles all filesystem writes via its own tools; ESMP never writes to target codebase through MCP (CONTEXT.md decision honored)"
  - "getPlan returns 200 with empty plan (0 actions) for unknown classes rather than 404 — generatePlan always returns a valid record; 404 would require explicit null return which MigrationRecipeService does not produce"

patterns-established:
  - "MCP migration tools: @Tool + @Timed + meterRegistry.counter pattern mirrors existing 6 tools"
  - "MigrationToolService constructor injection: MigrationRecipeService added as 7th injected dependency before MeterRegistry"

requirements-completed: [MIG-05, MIG-06]

# Metrics
duration: 10min
completed: 2026-03-28
---

# Phase 16 Plan 03: REST API and MCP Migration Tools Summary

**5 REST endpoints at /api/migration/* and 3 new MCP tools (getMigrationPlan, applyMigrationRecipes, getModuleMigrationSummary) exposing the OpenRewrite migration engine to both HTTP and Claude Code**

## Performance

- **Duration:** 10 min
- **Started:** 2026-03-28T14:27:48Z
- **Completed:** 2026-03-28T14:38:03Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments

- Created MigrationController with 5 endpoints (plan, summary, preview, apply, apply-module) using {fqn:.+} regex suffix for dot-safe path variables
- Extended MigrationToolService with 3 new @Tool methods; applyMigrationRecipes calls preview() not applyAndWrite() per the CONTEXT.md design decision that Claude handles all filesystem writes
- Added 11 integration tests (7 controller + 4 MCP tool) verifying HTTP status codes, response structure, disk-write safety, and graceful handling of unknown classes

## Task Commits

Each task was committed atomically:

1. **Task 1: MigrationController REST API and request records** - `0b64a23` (feat)
2. **Task 2: MCP migration tools** - `fc24d34` (feat)

**Plan metadata:** (docs commit below)

## Files Created/Modified

- `src/main/java/com/esmp/migration/api/MigrationController.java` — 5 REST endpoints for migration planning and execution
- `src/main/java/com/esmp/migration/api/MigrationRequest.java` — optional source root override record
- `src/main/java/com/esmp/migration/api/ModuleMigrationRequest.java` — module batch request record
- `src/main/java/com/esmp/mcp/tool/MigrationToolService.java` — extended with MigrationRecipeService injection and 3 new @Tool methods; Javadoc updated to "9 tools"
- `src/main/java/com/esmp/mcp/config/McpToolRegistration.java` — Javadoc updated to mention 9 tools
- `src/test/java/com/esmp/migration/api/MigrationControllerIntegrationTest.java` — 7 MockMvc tests
- `src/test/java/com/esmp/mcp/tool/MigrationMcpToolIntegrationTest.java` — 4 tool tests including disk-write safety assertion

## Decisions Made

- `applyMigrationRecipes` MCP tool calls `preview()` not `applyAndWrite()` — honoring the CONTEXT.md design decision that MCP tools return diff + modified source text only; Claude Code handles all filesystem writes via its own tools
- `getPlan` returns HTTP 200 with a plan record (totalActions=0) for unknown classes rather than HTTP 404, because `generatePlan()` always returns a non-null record; a class with no migration actions is a valid state

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- `vaadinPrepareFrontend` fails when Gradle daemon runs Java 17 (known issue from Phase 12). Resolved by passing `-Dorg.gradle.java.home="C:/Users/aziz.rehman/java21/jdk21.0.10_7"` to all Gradle test invocations.

## Next Phase Readiness

- Phase 16 complete: OpenRewrite migration engine fully exposed via REST API and MCP
- MCP server now exposes 9 tools total (6 existing + 3 migration tools)
- REST API ready for dashboard integration or direct CLI usage

---
*Phase: 16-openrewrite-recipe-based-migration-engine*
*Completed: 2026-03-28*

## Self-Check: PASSED

- MigrationController.java: FOUND
- MigrationRequest.java: FOUND
- ModuleMigrationRequest.java: FOUND
- MigrationControllerIntegrationTest.java: FOUND
- MigrationMcpToolIntegrationTest.java: FOUND
- Commit 0b64a23 (Task 1): FOUND
- Commit fc24d34 (Task 2): FOUND
