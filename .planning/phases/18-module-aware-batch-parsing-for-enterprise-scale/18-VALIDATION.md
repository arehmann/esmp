---
phase: 18
slug: module-aware-batch-parsing-for-enterprise-scale
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-29
---

# Phase 18 -- Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers |
| **Config file** | `src/test/resources/application-test.yml` |
| **Quick run command** | `./gradlew test --tests "com.esmp.extraction.module.*" -x integrationTest` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30 seconds (unit), ~120 seconds (full) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.extraction.module.*" -x integrationTest`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 18-01-01 | 01 | 1 | MODULE-DETECT | unit | `./gradlew test --tests "ModuleDetectionServiceTest.testGradleModuleDetection"` | W0 | pending |
| 18-01-02 | 01 | 1 | MODULE-DETECT | unit | `./gradlew test --tests "ModuleDetectionServiceTest.testGradleDependencyGraph"` | W0 | pending |
| 18-01-03 | 01 | 1 | MODULE-DETECT | unit | `./gradlew test --tests "ModuleDetectionServiceTest.testMavenModuleDetection"` | W0 | pending |
| 18-01-04 | 01 | 1 | TOPO-SORT | unit | `./gradlew test --tests "ModuleGraphTest.testLinearChain"` | W0 | pending |
| 18-01-05 | 01 | 1 | FALLBACK | unit | `./gradlew test --tests "ModuleDetectionServiceTest.testMissingCompiledClasses"` | W0 | pending |
| 18-02-01 | 02 | 2 | EXTRACTION | integration | `./gradlew test --tests "ModuleAwareExtractionIntegrationTest.testSingleShotFallback"` | W0 | pending |
| 18-02-02 | 02 | 2 | EXTRACTION | integration | `./gradlew test --tests "ModuleAwareExtractionIntegrationTest.testModuleAwareDetectionAndExtraction"` | W0 | pending |
| 18-02-03 | 02 | 2 | SSE-PROGRESS | integration | `./gradlew test --tests "ModuleAwareExtractionIntegrationTest.testProgressEvents"` | W0 | pending |
| 18-02-04 | 02 | 2 | CROSS-MODULE | integration | `./gradlew test --tests "ModuleAwareExtractionIntegrationTest.testCrossModuleLinking"` | W0 | pending |

*Status: pending / green / red / flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/extraction/module/ModuleDetectionServiceTest.java` -- unit tests for Gradle/Maven parsing
- [ ] `src/test/java/com/esmp/extraction/module/ModuleGraphTest.java` -- topological sort + wave grouping
- [ ] `src/test/java/com/esmp/extraction/application/ModuleAwareExtractionIntegrationTest.java` -- full pipeline integration
- [ ] `src/test/resources/fixtures/modules/gradle-multi/settings.gradle` -- test fixture mimicking AdSuite structure
- [ ] `src/test/resources/fixtures/modules/maven-multi/pom.xml` -- Maven multi-module fixture

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Real AdSuite extraction (18K files, 4 modules) | SCALE | Requires full AdSuite project compiled | Point sourceRoot at `C:/frontoffice/migration/source/AdSuite`, verify 4 modules detected in correct wave order |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending