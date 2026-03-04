---
phase: 2
slug: ast-extraction
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-04
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (via `spring-boot-starter-test`, already configured) |
| **Config file** | `build.gradle.kts` — `tasks.withType<Test> { useJUnitPlatform() }` already present |
| **Quick run command** | `./gradlew test --tests "com.esmp.extraction.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30 seconds (includes Testcontainers Neo4j startup) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.extraction.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 02-01-01 | 01 | 0 | AST-01 | unit | `./gradlew test --tests "com.esmp.extraction.parser.JavaSourceParserTest"` | ❌ W0 | ⬜ pending |
| 02-01-02 | 01 | 0 | AST-02 | unit | `./gradlew test --tests "com.esmp.extraction.visitor.ClassMetadataVisitorTest"` | ❌ W0 | ⬜ pending |
| 02-01-03 | 01 | 0 | AST-03 | unit | `./gradlew test --tests "com.esmp.extraction.visitor.CallGraphVisitorTest"` | ❌ W0 | ⬜ pending |
| 02-01-04 | 01 | 0 | AST-04 | integration | `./gradlew test --tests "com.esmp.extraction.ExtractionIntegrationTest"` | ❌ W0 | ⬜ pending |
| 02-02-01 | 02 | 1 | AST-01 | unit | `./gradlew test --tests "com.esmp.extraction.parser.JavaSourceParserTest"` | ❌ W0 | ⬜ pending |
| 02-02-02 | 02 | 1 | AST-02 | unit | `./gradlew test --tests "com.esmp.extraction.visitor.ClassMetadataVisitorTest"` | ❌ W0 | ⬜ pending |
| 02-03-01 | 03 | 2 | AST-03 | unit | `./gradlew test --tests "com.esmp.extraction.visitor.CallGraphVisitorTest"` | ❌ W0 | ⬜ pending |
| 02-04-01 | 04 | 3 | AST-04 | integration | `./gradlew test --tests "com.esmp.extraction.ExtractionIntegrationTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/extraction/parser/JavaSourceParserTest.java` — stubs for AST-01
- [ ] `src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java` — stubs for AST-02
- [ ] `src/test/java/com/esmp/extraction/visitor/CallGraphVisitorTest.java` — stubs for AST-03
- [ ] `src/test/java/com/esmp/extraction/ExtractionIntegrationTest.java` — stubs for AST-04 (Testcontainers Neo4j)
- [ ] `src/test/resources/fixtures/` — synthetic Java source fixtures (Vaadin UI class, View, service, repo, entity, data-bound form)
- [ ] `build.gradle.kts` additions: `rewrite-java:8.74.3`, `rewrite-java-21:8.74.3`, `vaadin-server:7.7.48` (testImplementation)
- [ ] `libs.versions.toml` additions: `openrewrite = "8.74.3"`, `vaadin-server = "7.7.48"`

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Vaadin 7 pattern audit against real legacy module | AST-01, AST-04 | Requires access to actual legacy codebase | Run extraction against real module, compare graph output against manually verified expectations, document gaps in audit report |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
