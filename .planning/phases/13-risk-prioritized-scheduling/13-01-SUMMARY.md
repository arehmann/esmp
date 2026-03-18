---
phase: 13-risk-prioritized-scheduling
plan: 01
subsystem: scheduling
tags: [neo4j, topological-sort, composite-scoring, git-frequency, testcontainers]

# Dependency graph
requires:
  - phase: 07-domain-aware-risk-analysis
    provides: enhancedRiskScore on ClassNode used as risk dimension
  - phase: 06-structural-risk-analysis
    provides: complexitySum, complexityMax on ClassNode used as complexity dimension
  - phase: 04-graph-validation-canonical-queries
    provides: ValidationQueryRegistry extensibility pattern
  - phase: 03-code-knowledge-graph
    provides: DEPENDS_ON edges for cross-module dependency graph and topological sort
provides:
  - SchedulingService with Kahn's BFS topological sort and 4-dimension composite scoring
  - GitFrequencyService using ProcessBuilder to parse git log per-module commit counts
  - SchedulingWeightConfig externalized weights (risk/dependency/frequency/complexity)
  - GET /api/scheduling/recommend REST endpoint returning ScheduleResponse with waves + flat ranking
  - 3 SchedulingValidationQueryRegistry queries (total: 41 validation queries in project)
  - 9 tests (3 unit + 6 integration) covering all SCHED-01 and SCHED-02 requirements
affects:
  - phase 13 plan 02 (SchedulingView UI will consume GET /api/scheduling/recommend)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Kahn's BFS topological sort with SCC cycle fallback for module wave assignment
    - 4-dimension normalized composite scoring (risk + dependency + frequency + complexity)
    - ProcessBuilder git log parsing with 30s timeout and graceful empty-map fallback
    - Static setUpDone guard for Testcontainers one-time setup in integration tests

key-files:
  created:
    - src/main/java/com/esmp/scheduling/config/SchedulingWeightConfig.java
    - src/main/java/com/esmp/scheduling/application/GitFrequencyService.java
    - src/main/java/com/esmp/scheduling/application/SchedulingService.java
    - src/main/java/com/esmp/scheduling/api/ModuleSchedule.java
    - src/main/java/com/esmp/scheduling/api/WaveGroup.java
    - src/main/java/com/esmp/scheduling/api/ScheduleResponse.java
    - src/main/java/com/esmp/scheduling/api/SchedulingController.java
    - src/main/java/com/esmp/scheduling/validation/SchedulingValidationQueryRegistry.java
    - src/test/java/com/esmp/scheduling/GitFrequencyServiceTest.java
    - src/test/java/com/esmp/scheduling/SchedulingServiceIntegrationTest.java
  modified:
    - src/main/resources/application.yml

key-decisions:
  - "SchedulingService is NOT @Transactional — pure read orchestrator like RagService and DashboardService"
  - "Module index corrected from parts[4] to parts[5] for path src/main/java/com/esmp/<module>/... (parts[4]=esmp, parts[5]=module)"
  - "Kahn's BFS assigns modules not reached by topo sort to cycleWave=maxWave+1 for circular dep fallback"
  - "Division-by-zero guard: maxDependentCount and maxCommitCount floored to 1 when all-zero"
  - "logMaxComplexity guard: normalizedComplexity = 0.0 when maxComplexity is zero (all log denominators)"
  - "ValidationSeverity INFO does not exist in the codebase — third scheduling query uses WARNING instead"

patterns-established:
  - "Scheduling scoring: normalizedRisk is used raw (already [0,1]); dependency/frequency use linear normalization; complexity uses log(1+x)/log(1+max) normalization"
  - "GitFrequencyService path parsing: parts[5] extracts module from src/main/java/com/esmp/<module>/<class>"

requirements-completed: [SCHED-01, SCHED-02]

# Metrics
duration: 30min
completed: 2026-03-19
---

# Phase 13 Plan 01: Risk-Prioritized Scheduling Backend Summary

**Kahn's BFS topological sort with 4-dimension composite scoring (risk/dependency/frequency/complexity) delivering GET /api/scheduling/recommend with wave grouping and git frequency analysis via ProcessBuilder**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-03-19T00:00:00Z
- **Completed:** 2026-03-19T00:05:00Z
- **Tasks:** 2
- **Files modified:** 11

## Accomplishments

- SchedulingService: aggregates module metrics from Neo4j, builds cross-module dependency graph, runs Kahn's BFS topo sort assigning wave numbers, computes 4-dimension composite scores with log-normalization for complexity
- GitFrequencyService: executes `git log --name-only` via ProcessBuilder with 30s timeout, parses module names from file paths at parts[5], returns empty map gracefully when git is unavailable
- REST endpoint `GET /api/scheduling/recommend` returns ScheduleResponse with waves (WaveGroup list) and flatRanking ordered by waveNumber ASC then finalScore ASC
- 3 validation queries added to global registry (SCHEDULING_MODULES_EXIST, SCHEDULING_RISK_SCORES_POPULATED, SCHEDULING_DEPENDENCY_EDGES_EXIST)
- 9 tests pass: 3 unit (GitFrequencyServiceTest) + 6 integration (SchedulingServiceIntegrationTest)

## Task Commits

Each task was committed atomically:

1. **Task 1: Config, GitFrequencyService, API records, and unit tests** - `a6ad7ff` (feat)
2. **Task 2: SchedulingService, SchedulingController, validation registry, and integration tests** - `7f560af` (feat)

## Files Created/Modified

- `src/main/java/com/esmp/scheduling/config/SchedulingWeightConfig.java` - @ConfigurationProperties with risk/dependency/frequency/complexity weights and gitWindowDays
- `src/main/java/com/esmp/scheduling/application/GitFrequencyService.java` - ProcessBuilder git log parser with graceful fallback
- `src/main/java/com/esmp/scheduling/application/SchedulingService.java` - Neo4j aggregation + Kahn BFS topo sort + composite scoring orchestrator
- `src/main/java/com/esmp/scheduling/api/ModuleSchedule.java` - Per-module recommendation record (13 fields including rationale)
- `src/main/java/com/esmp/scheduling/api/WaveGroup.java` - Wave grouping record
- `src/main/java/com/esmp/scheduling/api/ScheduleResponse.java` - Full response record with waves + flatRanking
- `src/main/java/com/esmp/scheduling/api/SchedulingController.java` - GET /api/scheduling/recommend endpoint
- `src/main/java/com/esmp/scheduling/validation/SchedulingValidationQueryRegistry.java` - 3 scheduling validation queries
- `src/main/resources/application.yml` - Added esmp.scheduling.weight block and git-window-days: 180
- `src/test/java/com/esmp/scheduling/GitFrequencyServiceTest.java` - 3 unit tests (unavailable git, blank sourceRoot, parse git log)
- `src/test/java/com/esmp/scheduling/SchedulingServiceIntegrationTest.java` - 6 integration tests with Testcontainers (Neo4j + MySQL + Qdrant)

## Decisions Made

- SchedulingService is NOT @Transactional — follows RagService/DashboardService pattern as pure read orchestrator
- Module index at parts[5] not parts[4] for path `src/main/java/com/esmp/<module>/...` (see auto-fix below)
- Kahn's BFS assigns unprocessed modules (cycles) to maxWave+1 to avoid breaking the response
- Third validation query uses WARNING severity as INFO does not exist in ValidationSeverity enum

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed module extraction index: parts[4] → parts[5]**
- **Found during:** Task 1 (GitFrequencyServiceTest testParsesGitLogOutput)
- **Issue:** Plan specified `module = parts[4]` but for path `src/main/java/com/esmp/alpha/Foo.java`, parts[4]="esmp" and parts[5]="alpha". The service returned `{esmp=1}` instead of `{alpha=1}`.
- **Fix:** Changed index from 4 to 5 in `GitFrequencyService.computeModuleCommitCounts()`
- **Files modified:** `src/main/java/com/esmp/scheduling/application/GitFrequencyService.java`
- **Verification:** All 3 GitFrequencyServiceTest tests pass after fix
- **Committed in:** `a6ad7ff` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 Rule 1 bug)
**Impact on plan:** Essential correctness fix — without it, all modules would be bucketed under "esmp" instead of their real names. No scope creep.

## Issues Encountered

None beyond the auto-fixed bug above.

## Next Phase Readiness

- GET /api/scheduling/recommend fully functional and tested
- Phase 13 Plan 02 (SchedulingView Vaadin UI) can consume the endpoint directly
- Git frequency works when git is available; returns 0 contribution when unavailable

---
*Phase: 13-risk-prioritized-scheduling*
*Completed: 2026-03-19*
