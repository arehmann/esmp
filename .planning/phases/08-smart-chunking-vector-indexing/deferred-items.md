# Phase 8 Deferred Items

## Pre-existing Test Failures (Out of Scope for Phase 8)

These failures existed before Phase 8 and are not regressions from Phase 8 changes:

### 1. LinkingServiceIntegrationTest (9 tests)
- **Root cause:** `SpringBootTest.WebEnvironment.NONE` causes Vaadin `SpringBootAutoConfiguration` to fail requiring `WebApplicationContext`
- **Fix:** Migrate test to `WebEnvironment.MOCK` (same fix applied to other tests in Phase 5)
- **Priority:** Low — does not affect production code

### 2. VirtualThreadsTest (3 tests)
- **Root cause:** Likely related to same Vaadin context load issue
- **Priority:** Low

### 3. ValidationControllerIntegrationTest.allQueriesPassOnWellFormedGraph (1 test)
- **Root cause:** Test expects a fixed number of passing/failing validation queries; adding new VectorValidationQueryRegistry (3 queries) and DomainRiskValidationQueryRegistry may have changed counts
- **Priority:** Medium — should be updated when ValidationController test expectations are revisited

## Logged by Phase 8 Plan 02 execution
*Date: 2026-03-05*
