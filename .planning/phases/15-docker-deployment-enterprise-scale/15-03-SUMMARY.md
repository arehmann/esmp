---
phase: 15-docker-deployment-enterprise-scale
plan: 03
subsystem: api
tags: [sse, async, spring-mvc, extraction, progress-streaming, docker, verification]

# Dependency graph
requires:
  - phase: 15-02
    provides: parallel extraction with batched UNWIND MERGE persistence and extractionExecutor thread pool
  - phase: 15-01
    provides: SourceAccessService for resolved sourceRoot; Dockerfile and docker-compose.full.yml for Docker verification
provides:
  - ExtractionProgressService with ConcurrentHashMap<String, SseEmitter> lifecycle management
  - Async POST /api/extraction/trigger returning 202 Accepted with jobId
  - GET /api/extraction/progress?jobId=X SSE endpoint (60-min timeout)
  - ProgressEvent record (phase, filesProcessed, totalFiles)
  - Per-file progress callbacks in both sequential and parallel extraction paths
  - Human-verified Docker stack: image builds, all 6 services start, SSE streams end-to-end
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
  - "SSE race condition for small codebases (extraction completes before client connects) accepted — negligible for enterprise-scale target"

patterns-established:
  - "Async endpoint pattern: generate UUID jobId, fire CompletableFuture.runAsync, return 202 immediately"
  - "Progress registry pattern: ConcurrentHashMap<jobId, SseEmitter> with self-cleaning callbacks"

requirements-completed: [SCALE-03]

# Metrics
duration: 25min
completed: 2026-03-28
---

# Phase 15 Plan 03: SSE Progress Streaming for Async Extraction Summary

**Async extraction trigger (202 + jobId) with real-time SSE progress streaming via ExtractionProgressService and ConcurrentHashMap<String, SseEmitter>, verified end-to-end in Docker with all 6 services running**

## Performance

- **Duration:** ~25 min (including human checkpoint)
- **Started:** 2026-03-20T07:20:38Z
- **Completed:** 2026-03-28
- **Tasks:** 2 (1 auto + 1 human-verify checkpoint approved)
- **Files modified:** 4

## Accomplishments
- ExtractionProgressService manages per-job SseEmitter lifecycle with ConcurrentHashMap and self-cleaning callbacks
- POST /api/extraction/trigger now returns 202 Accepted with jobId immediately; extraction runs on extractionExecutor thread pool
- GET /api/extraction/progress?jobId=X streams SSE events (progress/done/error) during extraction lifecycle
- ExtractionService.extract() extended with optional jobId parameter, emitting SCANNING, PARSING, VISITING, PERSISTING, LINKING events
- Per-file VISITING progress in both sequential loop and parallel AtomicInteger counter paths
- Docker stack human-verified: image builds with Vaadin frontend compilation, all 6 services start via docker-compose.full.yml, POST /api/extraction/trigger returns 202 with jobId, SSE events stream correctly

## Task Commits

Each task was committed atomically:

1. **Task 1: ExtractionProgressService, async trigger, SSE endpoint, unit tests** - `ff1da5b` (feat)
2. **Task 2: Docker stack human verification** - N/A (human checkpoint — approved by user)

**Plan metadata:** (committed with docs update)

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
- SSE race condition for small codebases (extraction completes before client connects to the SSE endpoint, DONE event missed) accepted as not a concern — enterprise-scale extractions take minutes, making the race window negligible for the target deployment

## Deviations from Plan

None — plan executed exactly as written. The SSE race condition for small codebases was observed during Docker verification and explicitly accepted by the user.

## Issues Encountered

- SSE race condition observed during Docker verification for small codebases: extraction completes too quickly for client to connect. Accepted as not a concern for the enterprise-scale target (40K+ file extractions take several minutes, providing ample time for client to connect).

## User Setup Required

None - the Docker deployment stack is self-contained. To deploy:
1. Copy `.env.example` to `.env` and set credentials
2. Set `SOURCE_ROOT` to the path of the codebase to analyze
3. Run `docker compose -f docker-compose.full.yml up -d`
4. POST to `/api/extraction/trigger` to start async extraction (returns 202 + jobId)
5. Stream progress from `GET /api/extraction/progress?jobId=<id>`

## Next Phase Readiness

- Phase 15 (Docker Deployment & Enterprise Scale) is fully complete across all 3 plans
- Full Docker deployment stack operational: Dockerfile, docker-compose.full.yml, SourceAccessService, parallel extraction with UNWIND MERGE, SSE progress streaming
- The ESMP platform is ready for production containerized deployment targeting enterprise-scale Java codebases (40K+ files)
- All prior phases (1-14) functionality preserved and accessible via the containerized deployment

---
*Phase: 15-docker-deployment-enterprise-scale*
*Completed: 2026-03-28*
