---
phase: 14
slug: mcp-server-for-ai-powered-migration-context
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-19
---

# Phase 14 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test 3.5.11 + Testcontainers 1.20.4 |
| **Config file** | None (auto-configured via `@SpringBootTest`) |
| **Quick run command** | `./gradlew test --tests "com.esmp.mcp.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.esmp.mcp.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 14-01-01 | 01 | 1 | MCP-01 | integration | `./gradlew test --tests "*.McpServerStartupTest"` | ❌ W0 | ⬜ pending |
| 14-01-02 | 01 | 1 | MCP-02 | integration | `./gradlew test --tests "*.MigrationContextAssemblerTest"` | ❌ W0 | ⬜ pending |
| 14-01-03 | 01 | 1 | MCP-03 | unit | `./gradlew test --tests "*.MigrationToolServiceTest"` | ❌ W0 | ⬜ pending |
| 14-01-04 | 01 | 1 | MCP-04 | unit | `./gradlew test --tests "*.MigrationToolServiceTest"` | ❌ W0 | ⬜ pending |
| 14-01-05 | 01 | 1 | MCP-05 | unit | `./gradlew test --tests "*.MigrationToolServiceTest"` | ❌ W0 | ⬜ pending |
| 14-02-01 | 02 | 2 | MCP-06 | unit | `./gradlew test --tests "*.McpCacheTest"` | ❌ W0 | ⬜ pending |
| 14-02-02 | 02 | 2 | MCP-07 | integration | `./gradlew test --tests "*.McpCacheEvictionTest"` | ❌ W0 | ⬜ pending |
| 14-03-01 | 03 | 2 | MCP-08 | unit | `./gradlew test --tests "*.MigrationContextAssemblerTest"` | ❌ W0 | ⬜ pending |
| 14-04-01 | 04 | 3 | SLO-MCP-01 | integration | `./gradlew test --tests "*.McpSloTest"` | ❌ W0 | ⬜ pending |
| 14-04-02 | 04 | 3 | SLO-MCP-02 | integration | `./gradlew test --tests "*.McpSloTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/esmp/mcp/application/MigrationContextAssemblerTest.java` — stubs for MCP-02, MCP-08
- [ ] `src/test/java/com/esmp/mcp/tool/MigrationToolServiceTest.java` — stubs for MCP-03, MCP-04, MCP-05
- [ ] `src/test/java/com/esmp/mcp/config/McpCacheTest.java` — stubs for MCP-06
- [ ] `src/test/java/com/esmp/mcp/config/McpServerStartupTest.java` — stubs for MCP-01
- [ ] `src/test/java/com/esmp/mcp/config/McpSloTest.java` — stubs for SLO-MCP-01, SLO-MCP-02
- [ ] `src/test/java/com/esmp/mcp/config/McpCacheEvictionTest.java` — stubs for MCP-07

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Claude Code connects to MCP server and lists tools | MCP-01 | Requires Claude Code CLI | Run `claude mcp add esmp-local --transport sse http://localhost:8080/mcp/sse`, then verify tools appear in Claude Code |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
