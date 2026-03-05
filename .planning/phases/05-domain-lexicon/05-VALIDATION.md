---
phase: 5
slug: domain-lexicon
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-05
---

# Phase 5 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + AssertJ + Testcontainers (existing project stack) |
| **Config file** | `build.gradle.kts` — `tasks.withType<Test> { useJUnitPlatform() }` |
| **Quick run command** | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 05-01-01 | 01 | 1 | LEX-01 | unit | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` | ❌ W0 | ⬜ pending |
| 05-01-02 | 01 | 1 | LEX-01 | unit | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` | ❌ W0 | ⬜ pending |
| 05-01-03 | 01 | 1 | LEX-01 | unit | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` | ❌ W0 | ⬜ pending |
| 05-01-04 | 01 | 1 | LEX-01 | unit | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` | ❌ W0 | ⬜ pending |
| 05-02-01 | 02 | 1 | LEX-02 | integration | `./gradlew test --tests "com.esmp.extraction.application.LexiconIntegrationTest"` | ❌ W0 | ⬜ pending |
| 05-02-02 | 02 | 1 | LEX-02 | integration | `./gradlew test --tests "com.esmp.extraction.application.LexiconIntegrationTest"` | ❌ W0 | ⬜ pending |
| 05-03-01 | 03 | 2 | LEX-03 | integration | `./gradlew test --tests "com.esmp.extraction.application.LexiconIntegrationTest"` | ❌ W0 | ⬜ pending |
| 05-03-02 | 03 | 2 | LEX-03 | integration | `./gradlew test --tests "com.esmp.extraction.application.LexiconIntegrationTest"` | ❌ W0 | ⬜ pending |
| 05-04-01 | 04 | 3 | LEX-04 | integration | `./gradlew test --tests "com.esmp.graph.api.LexiconControllerTest"` | ❌ W0 | ⬜ pending |
| 05-04-02 | 04 | 3 | LEX-04 | integration | `./gradlew test --tests "com.esmp.graph.api.LexiconControllerTest"` | ❌ W0 | ⬜ pending |
| 05-04-03 | 04 | 3 | LEX-04 | manual | Browser: `http://localhost:8080/lexicon` | manual-only | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/extraction/visitor/LexiconVisitorTest.java` — stubs for LEX-01 (all extraction scenarios)
- [ ] `src/test/java/com/esmp/extraction/application/LexiconIntegrationTest.java` — stubs for LEX-02, LEX-03 (Testcontainers Neo4j)
- [ ] `src/test/java/com/esmp/graph/api/LexiconControllerTest.java` — stubs for LEX-04 REST API (Testcontainers Neo4j + MockMvc)
- [ ] `src/test/resources/fixtures/lexicon/` — Java fixture files (SampleInvoiceService.java, PaymentStatusEnum.java)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Vaadin LexiconView loads without error | LEX-04 | Vaadin UI rendering requires browser | 1. Start app 2. Navigate to `http://localhost:8080/lexicon` 3. Verify grid loads with terms |
| Grid sorting/filtering works | LEX-04 | UI interaction | Click column headers, type in search field |
| Inline edit saves correctly | LEX-04 | UI interaction | Double-click term, edit definition, save, verify persistence |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
