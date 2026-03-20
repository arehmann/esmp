---
phase: 15-docker-deployment-enterprise-scale
plan: 03
subsystem: api
tags: [sse, async, spring-mvc, extraction, progress-streaming]

# Dependency graph
requires:
  - phase: 15-02
    provides: parallel extraction with batched UNWIND MERGE persistence
  - phase: 15-01
    provides: SourceAccessService for resolved sourceRoot
provides:
  - ExtractionProgressService with ConcurrentHashMap<String, SseEmitter> lifecycle management
  - Async POST /api/extraction/trigger returning 202 Accepted with jobId
  - GET /api/extraction/progress?jobId=X SSE endpoint (60-min timeout)
  - ProgressEvent record (phase, filesProcessed, totalFiles)
  - Per-file progress callbacks in both sequential and parallel extraction paths
affects: [mcp, rag, vector, indexing]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Async fire-and-forget via CompletableFuture.runAsync on extractionExecutor"
    - "SSE streaming via Spring SseEmitter with ConcurrentHashMap registry"
    - "Optional jobId overload pattern for backward-compatible progress injection"

key-files:
  created:
    - src/main/java/com/esmp/extraction/application/ExtractionProgressService.java
    - src/test/java/com/esmp/extraction/application/ExtractionProgressServiceTest.java
  modified:
    - src/main/java/com/esmp/extraction/api/ExtractionController.java
    - src/main/java/com/esmp/extraction/application/ExtractionService.java

key-decisions:
  - "ExtractionProgressService uses ConcurrentHashMap for thread-safe emitter registry; emitter cleanup on completion/timeout/error"
  - "extract() overload accepts nullable jobId — null means no progress streaming (backward compat)"
  - "sendProgress() helper is a no-op when jobId is null or blank — zero cost on synchronous path"
  - "POST /api/extraction/trigger falls back to SourceAccessService.getResolvedSourceRoot() when no sourceRoot in body"
  - "60-minute SSE emitter timeout covers enterprise-scale 40K-file extractions"

patterns-established:
  - "Async endpoint pattern: generate UUID jobId, fire CompletableFuture.runAsync, return 202 immediately"
  - "Progress registry pattern: ConcurrentHashMap<jobId, SseEmitter> with self-cleaning callbacks"

requirements-completed: [SCALE-03]

# Metrics
duration: 3min
completed: 2026-03-20
---

# Phase 15 Plan 03: SSE Progress Streaming for Async Extraction Summary

**Async extraction trigger (202 + jobId) with real-time SSE progress streaming via ExtractionProgressService and ConcurrentHashMap<String, SseEmitter>**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-20T07:20:38Z
- **Completed:** 2026-03-20T07:23:30Z
- **Tasks:** 1 complete (checkpoint at Task 2 — awaiting human Docker verification)
- **Files modified:** 4

## Accomplishments
- ExtractionProgressService manages per-job SseEmitter lifecycle with ConcurrentHashMap and self-cleaning callbacks
- POST /api/extraction/trigger now returns 202 Accepted with jobId immediately; extraction runs on extractionExecutor thread pool
- GET /api/extraction/progress?jobId=X streams SSE events (progress/done/error) during extraction lifecycle
- ExtractionService.extract() extended with optional jobId parameter, emitting SCANNING, PARSING, VISITING, PERSISTING, LINKING events
- Per-file VISITING progress in both sequential loop and parallel AtomicInteger counter paths

## Task Commits

Each task was committed atomically:

1. **Task 1: ExtractionProgressService, async trigger, SSE endpoint, unit tests** - `ff1da5b` (feat)

**Plan metadata:** (pending — checkpoint in progress)

## Files Created/Modified
- `src/main/java/com/esmp/extraction/application/ExtractionProgressService.java` - ConcurrentHashMap<String, SseEmitter> registry with register/send/complete/error methods and ProgressEvent record
- `src/main/java/com/esmp/extraction/api/ExtractionController.java` - Async POST /trigger (202+jobId) and GET /progress SSE endpoint
- `src/main/java/com/esmp/extraction/application/ExtractionService.java` - extract(sourceRoot, classpathFile, jobId) overload with sendProgress() callbacks in both sequential and parallel paths
- `src/test/java/com/esmp/extraction/application/ExtractionProgressServiceTest.java` - 7 unit tests covering register/send/complete/error lifecycle and graceful no-ops

## Decisions Made
- ExtractionProgressService uses ConcurrentHashMap for thread-safe emitter registry; emitter cleanup on completion/timeout/error
- extract() overload accepts nullable jobId — null means no progress streaming (backward compat with existing synchronous callers)
- sendProgress() helper is a no-op when jobId is null or blank — zero overhead on synchronous path
- POST /api/extraction/trigger falls back to SourceAccessService.getResolvedSourceRoot() when no sourceRoot in body
- 60-minute SSE emitter timeout covers enterprise-scale 40K-file extractions

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Task 1 (all automated code work) complete and committed.
- Task 2 is a human-verify checkpoint awaiting Docker stack verification.
- Once approved: Docker image build, all-services docker-compose.full.yml startup, and SSE progress streaming end-to-end in the containerized environment.

---
*Phase: 15-docker-deployment-enterprise-scale*
*Completed: 2026-03-20 (partial — checkpoint at Task 2)*
