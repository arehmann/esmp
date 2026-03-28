# ESMP - Enterprise Source Migration Platform

> **Intelligent, risk-aware Vaadin 7 to Vaadin 24 migration planning powered by graph analysis, AI embeddings, and domain-aware scoring.**

ESMP analyzes your legacy Java/Vaadin codebase, builds a knowledge graph of every class, method, and relationship, scores migration risk across multiple dimensions, and recommends the safest order to migrate module by module.

---

## Table of Contents

- [What is ESMP?](#what-is-esmp)
- [Architecture Overview](#architecture-overview)
- [Quick Start](#quick-start)
- [Step-by-Step Setup Guide](#step-by-step-setup-guide)
- [Using the Dashboard](#using-the-dashboard)
- [Modules in Detail](#modules-in-detail)
  - [1. Code Extraction](#1-code-extraction)
  - [2. Code Knowledge Graph](#2-code-knowledge-graph)
  - [3. Graph Validation](#3-graph-validation)
  - [4. Domain Lexicon](#4-domain-lexicon)
  - [5. Risk Analysis](#5-risk-analysis)
  - [6. Vector Indexing & Semantic Search](#6-vector-indexing--semantic-search)
  - [7. Pilot Module Selection](#7-pilot-module-selection)
  - [8. RAG Context Assembly](#8-rag-context-assembly)
  - [9. Incremental Indexing](#9-incremental-indexing)
  - [10. Migration Scheduling](#10-migration-scheduling)
  - [11. MCP Server](#11-mcp-server)
  - [12. Docker Deployment & Source Access](#12-docker-deployment--source-access)
- [REST API Reference](#rest-api-reference)
- [Vaadin Dashboard Views](#vaadin-dashboard-views)
- [Configuration Reference](#configuration-reference)
- [Running Tests](#running-tests)
- [Monitoring](#monitoring)
- [Troubleshooting](#troubleshooting)
- [v1: Manual AI-Assisted Migration](#v1-manual-ai-assisted-migration)
  - [The Big Picture](#the-big-picture)
  - [Phase 1: Preparation](#phase-1-preparation--analyze--index--schedule)
  - [Phase 2: Pilot Migration](#phase-2-pilot-migration--one-module-end-to-end)
  - [Phase 3: Wave Execution](#phase-3-wave-execution--systematic-rollout)
  - [Phase 4: Continuous Validation](#phase-4-continuous-validation--keep-everything-green)
  - [Prompt Engineering for Migration](#prompt-engineering-for-migration)
  - [Automation Scripts](#automation-scripts)
- [v2: Autonomous AI Orchestration Engine (Roadmap)](#v2-autonomous-ai-orchestration-engine-roadmap)
  - [What Changes from v1 to v2](#what-changes-from-v1-to-v2)
  - [Architecture: The Orchestration Loop](#architecture-the-orchestration-loop)
  - [ORCH-01: AI Orchestration Engine](#orch-01-ai-orchestration-engine)
  - [ORCH-02: Automated Pull Request Generation](#orch-02-automated-pull-request-generation)
  - [ORCH-03: Deterministic Guardrails](#orch-03-deterministic-guardrails)
  - [ORCH-04: AI Confidence Scoring](#orch-04-ai-confidence-scoring)
  - [Behavioral Diffing Framework](#behavioral-diffing-framework)
  - [Advanced Features](#advanced-features)
  - [What v2 Will NOT Do](#what-v2-will-not-do)

---

## What is ESMP?

Migrating a large enterprise codebase from Vaadin 7 to Vaadin 24 is risky. Classes are entangled, business rules are buried in UI code, and nobody knows which module to migrate first without breaking everything else.

ESMP solves this by:

```
  Your Legacy Codebase
         |
         v
  +----------------------------------------------+
  |           ESMP Analysis Pipeline              |
  |                                               |
  |  1. Parse every .java file (AST extraction)   |
  |  2. Build a graph of ALL relationships        |
  |  3. Score risk: complexity, dependencies,     |
  |     domain criticality, security, financial   |
  |  4. Extract business terms from code          |
  |  5. Embed code into vectors for AI search     |
  |  6. Recommend safest migration order          |
  +----------------------------------------------+
         |
         v
  +----------------------------------------------+
  |           Governance Dashboard                |
  |                                               |
  |  - Visual risk heatmap per module             |
  |  - Interactive dependency graph               |
  |  - Business term curation                     |
  |  - Wave-based migration schedule              |
  |  - AI-powered context retrieval (RAG)         |
  +----------------------------------------------+
```

### Who is this for?

- **Tech Leads** planning a Vaadin migration strategy
- **Architects** who need to understand module dependencies before making changes
- **Developers** who want AI-assisted context about unfamiliar code areas
- **Project Managers** who need a data-driven migration schedule

---

## Architecture Overview

ESMP is a Spring Boot application backed by three specialized databases:

```
                        +------------------+
                        |   Browser (UI)   |
                        |  Vaadin 24 SPA   |
                        +--------+---------+
                                 |
                        +--------+---------+
                        |   Spring Boot    |
                        |   Application    |
                        |   (Java 21)      |
                        +--+-----+------+--+
                           |     |      |
              +------------+     |      +------------+
              |                  |                    |
     +--------v-------+ +-------v--------+ +---------v--------+
     |     Neo4j      | |     Qdrant     | |      MySQL       |
     |  Graph Database | | Vector Database| | Relational Store |
     |                | |                | |                  |
     | - 8 node types | | - 384-dim      | | - Migration jobs |
     | - 9 edge types | |   embeddings   | | - Audit trail    |
     | - 41 validation| | - Cosine       | | - Flyway managed |
     |   queries      | |   similarity   | |                  |
     +----------------+ +----------------+ +------------------+

     +----------------+ +----------------+
     |   Prometheus   | |    Grafana     |
     |   (Metrics)    | |  (Dashboards)  |
     +----------------+ +----------------+
```

### Technology Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Language** | Java 21 (virtual threads) | Performance + modern features |
| **Framework** | Spring Boot 3.5.11 | Application backbone |
| **UI** | Vaadin 24.9.12 | Rich web dashboard |
| **Graph DB** | Neo4j 2026.01.4 | Code relationships + queries |
| **Vector DB** | Qdrant 1.13.0 | Semantic code search |
| **Embeddings** | Spring AI + all-MiniLM-L6-v2 | 384-dim ONNX embeddings |
| **AST Parser** | OpenRewrite 8.74.3 | Java source code analysis |
| **Relational DB** | MySQL 8.4 | Job state + audit trail |
| **Monitoring** | Prometheus + Grafana | Metrics + dashboards |
| **Build** | Gradle 9.3 (Kotlin DSL) | Build + dependency management |
| **Deployment** | Docker (multi-stage) | Containerized deployment |
| **Git Access** | JGit 7.1.0 | Runtime GitHub clone for source access |
| **Testing** | JUnit 5 + Testcontainers | Unit + integration tests |

---

## Quick Start

### Option A: Full Docker Deployment (Recommended)

Everything in one command — no Java or Gradle required on your machine:

```bash
# 1. Clone the repo
git clone https://github.com/arehmann/esmp.git
cd esmp

# 2. Copy and customize environment variables
cp .env.example .env
# Edit .env to set SOURCE_ROOT to your legacy codebase path

# 3. Start everything (ESMP + Neo4j + Qdrant + MySQL + Prometheus + Grafana)
docker compose -f docker-compose.full.yml up -d

# 4. Wait for all services (~2 minutes for first build)
docker compose -f docker-compose.full.yml ps   # All should show "healthy"

# 5. Open the dashboard
# http://localhost:8080
```

**Source access strategies** (set in `.env`):
- **Volume mount** (default): Bind-mount your codebase into the container via `SOURCE_ROOT=./path/to/project`
- **GitHub clone**: Set `ESMP_SOURCE_STRATEGY=GITHUB_URL` with `ESMP_SOURCE_GITHUB_URL` and `ESMP_SOURCE_GITHUB_TOKEN`

### Option B: Local Development (Java 21 Required)

```bash
# 1. Clone the repo
git clone https://github.com/arehmann/esmp.git
cd esmp

# 2. Start databases only
docker compose up -d

# 3. Wait for all services to be healthy (~30 seconds)
docker compose ps   # All should show "healthy"

# 4. Build and run (requires Java 21)
./gradlew clean bootRun -Dorg.gradle.java.home="/path/to/java21"

# 5. Open the dashboard
# http://localhost:8080
```

Then jump to [Using the Dashboard](#using-the-dashboard) to start analyzing your codebase.

---

## Step-by-Step Setup Guide

> **Using Docker full-stack?** If you chose [Option A: Full Docker Deployment](#option-a-full-docker-deployment-recommended) in Quick Start, skip to [Step 4: Analyze Your Codebase](#step-4-analyze-your-codebase) — Docker handles everything else.

### Prerequisites

For local development (Option B):

| Requirement | Version | How to check |
|-------------|---------|-------------|
| **Java JDK** | 21+ | `java -version` |
| **Docker** | 20+ | `docker --version` |
| **Docker Compose** | 2.0+ | `docker compose version` |
| **Git** | 2.30+ | `git --version` |
| **Disk Space** | ~5 GB | For Docker images + data |

For Docker-only deployment (Option A), only Docker and Docker Compose are required.

### Step 1: Clone the Repository

```bash
git clone https://github.com/arehmann/esmp.git
cd esmp
```

### Step 2: Start Docker Services

ESMP requires three databases and two monitoring tools. Docker Compose handles all of them:

```bash
docker compose up -d
```

This starts:

```
  Docker Compose Services
  ========================

  +----------+     +----------+     +----------+
  |  Neo4j   |     |  Qdrant  |     |  MySQL   |
  |  :7474   |     |  :6333   |     |  :3307   |
  |  :7687   |     |  :6334   |     |          |
  +----------+     +----------+     +----------+
       Graph         Vectors        Relational
       Storage       Storage        Storage

  +----------+     +----------+
  |Prometheus|     | Grafana  |
  |  :9090   |     |  :3000   |
  +----------+     +----------+
      Metrics       Dashboards
```

**Wait for all services to be healthy** (about 30 seconds):

```bash
docker compose ps
```

You should see all services with status `healthy`:

```
NAME    IMAGE                    STATUS
neo4j   neo4j:2026.01.4         Up (healthy)
qdrant  qdrant/qdrant:latest     Up (healthy)
mysql   mysql:8.4                Up (healthy)
```

**Default credentials** (override with environment variables):

| Service | Username | Password | Env Variable |
|---------|----------|----------|-------------|
| Neo4j | `neo4j` | `esmp-local-password` | `NEO4J_PASSWORD` |
| MySQL | `esmp` | `esmp-local-password` | `MYSQL_PASSWORD` |
| Grafana | `admin` | `admin` | `GRAFANA_PASSWORD` |

### Step 3: Build and Run ESMP

```bash
# Build and start the application
./gradlew clean bootRun -Dorg.gradle.java.home="/path/to/java21"
```

> **Important:** The Gradle daemon must run with Java 21. If your default `JAVA_HOME` points to an older version, use the `-Dorg.gradle.java.home` flag pointing to your Java 21 installation.

The application starts on **http://localhost:8080**.

You'll see in the console:

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
  ...
Started EsmpApplication using Java 21 with PID ...
```

### Step 4: Analyze Your Codebase

Now trigger extraction on your Java source code:

```bash
# Point ESMP at your legacy codebase (returns immediately with a jobId)
curl -X POST "http://localhost:8080/api/extraction/trigger" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/absolute/path/to/your/java/src/main/java",
    "classpathFile": "/path/to/classpath.txt"
  }'
# Response: {"jobId": "abc-123", "status": "accepted"}
```

> **Note:** If you're using the Docker full-stack deployment, the source root is resolved automatically from your configured source access strategy. You can trigger without a body: `curl -X POST http://localhost:8080/api/extraction/trigger`

> **What is classpathFile?** A text file with one JAR path per line. It helps ESMP detect Vaadin types accurately. If you don't have one, omit it - extraction still works but with reduced Vaadin detection accuracy.

**Monitoring large extractions:**

```bash
# Stream real-time progress (for codebases with 500+ files)
curl -N "http://localhost:8080/api/extraction/progress?jobId=abc-123"
```

**Generating a classpath file from Gradle:**

```bash
# In your legacy project directory:
./gradlew dependencies --configuration runtimeClasspath \
  | grep '\\---' | sed 's/.*--- //' | sort -u > classpath.txt
```

When extraction completes, the result includes:

```json
{
  "classCount": 342,
  "methodCount": 2891,
  "fieldCount": 1205,
  "callEdgeCount": 4567,
  "vaadinViewCount": 28,
  "vaadinComponentCount": 45,
  "businessTermCount": 156,
  "durationMs": 12400
}
```

### Step 5: Build the Vector Index

After extraction, embed all code into vectors for semantic search:

```bash
curl -X POST "http://localhost:8080/api/vector/index?sourceRoot=/absolute/path/to/your/java/src/main/java"
```

This creates 384-dimensional embeddings for every class and method, enabling AI-powered code search.

### Step 6: Open the Dashboard

Navigate to **http://localhost:8080** in your browser. You'll see the governance dashboard with all your analyzed data.

---

## Using the Dashboard

ESMP provides three main views accessible via the sidebar:

```
  +--------------------------------------------------+
  |  ESMP                                             |
  +--------+-----------------------------------------+
  |        |                                         |
  | Side   |         Main Content Area               |
  | Nav    |                                         |
  |        |                                         |
  | [Dash] |   (Changes based on selected view)      |
  | [Lex]  |                                         |
  | [Sched]|                                         |
  |        |                                         |
  +--------+-----------------------------------------+
```

### Dashboard View (Home Page — `/`)

The default landing page shows a bird's-eye view of your entire codebase:

```
  +-----------------------------------------------------------+
  |                    GOVERNANCE DASHBOARD                     |
  +-----------------------------------------------------------+
  |                                                            |
  |  +------------+ +------------+ +------------+ +----------+ |
  |  | Total      | | Vaadin 7   | | High Risk  | | Business | |
  |  | Classes    | | Views      | | Classes    | | Terms    | |
  |  |    342     | |     28     | |     45     | |   156    | |
  |  +------------+ +------------+ +------------+ +----------+ |
  |                                                            |
  |  RISK HEATMAP                    RISK CLUSTERS             |
  |  +---------------------+    +------------------------+    |
  |  | Module | Risk | CC  |    |    (Interactive graph   |    |
  |  |--------|------|-----|    |     showing clusters    |    |
  |  | auth   | 0.82 | 34 |    |     of high-risk        |    |
  |  | billing| 0.71 | 28 |    |     classes connected    |    |
  |  | ui     | 0.45 | 12 |    |     by dependencies)     |    |
  |  +---------------------+    +------------------------+    |
  |                                                            |
  |  DEPENDENCY GRAPH              BUSINESS CONCEPTS           |
  |  +---------------------+    +------------------------+    |
  |  |  (Cytoscape.js       |    |  (Interactive term     |    |
  |  |   interactive graph  |    |   relationship graph   |    |
  |  |   - click modules    |    |   showing which terms  |    |
  |  |   - see connections) |    |   are used where)      |    |
  |  +---------------------+    +------------------------+    |
  +-----------------------------------------------------------+
```

**What you can do here:**

1. **Metric Cards** - See counts at a glance (classes, views, risk levels, terms)
2. **Risk Heatmap** - Sortable table showing which modules are riskiest
3. **Risk Clusters** - Interactive Cytoscape.js graph showing risk clusters
4. **Dependency Explorer** - Click any module to drill down and see its internal class dependencies
5. **Business Concept Graph** - Visualize which business terms are connected to which code

### Lexicon View (`/lexicon`)

Manage the automatically extracted business terms:

```
  +-----------------------------------------------------------+
  |                    BUSINESS LEXICON                         |
  +-----------------------------------------------------------+
  |                                                            |
  |  Filter: [________] Criticality: [All v] Curated: [All v] |
  |                                                            |
  |  +-------+------------+-----------+--------+-------+-----+|
  |  | Term  | Definition | Criticality| Source | Usage | Edit||
  |  |-------|------------|-----------|--------|-------|-----||
  |  |Account| Financial..| High      | Class  |  23   | [e] ||
  |  |Order  | Purchase...| High      | Enum   |  18   | [e] ||
  |  |Widget | UI comp... | Low       | Class  |   5   | [e] ||
  |  +-------+------------+-----------+--------+-------+-----+|
  |                                                            |
  |  Click any row to see which classes use this term          |
  +-----------------------------------------------------------+
```

**What you can do here:**

1. **Filter and search** business terms by name, criticality, or curation status
2. **Click a term** to see all classes that reference it (with usage counts)
3. **Edit terms** - update definitions, set criticality levels, add synonyms
4. **Curate** - mark terms as curated to protect them from re-extraction overwrite

### Schedule View (`/schedule`)

Plan your migration order based on risk and dependencies:

```
  +-----------------------------------------------------------+
  |                 MIGRATION SCHEDULE                         |
  +-----------------------------------------------------------+
  |                                                            |
  |  [Generate Schedule]     [Wave View] [Table View]          |
  |                                                            |
  |  WAVE 1 (Migrate First - Safest)                          |
  |  +----------+ +----------+ +----------+                   |
  |  |  utils   | |  config  | |  common  |                   |
  |  | Score:   | | Score:   | | Score:   |                   |
  |  |  0.120   | |  0.185   | |  0.210   |                   |
  |  | 12 cls   | |  8 cls   | | 15 cls   |                   |
  |  | (green)  | | (green)  | | (green)  |                   |
  |  +----------+ +----------+ +----------+                   |
  |                                                            |
  |  WAVE 2 (Moderate Risk)                                   |
  |  +----------+ +----------+                                |
  |  |  service | |  data    |                                |
  |  | Score:   | | Score:   |                                |
  |  |  0.445   | |  0.520   |                                |
  |  | 34 cls   | | 22 cls   |                                |
  |  | (amber)  | | (amber)  |                                |
  |  +----------+ +----------+                                |
  |                                                            |
  |  WAVE 3 (Migrate Last - Highest Risk)                     |
  |  +----------+                                             |
  |  |  billing |                                             |
  |  | Score:   |                                             |
  |  |  0.780   |                                             |
  |  | 45 cls   |                                             |
  |  |  (red)   |                                             |
  |  +----------+                                             |
  |                                                            |
  |  Click any module card for drill-down:                    |
  |  +------------------------------------------------------+ |
  |  | Module: billing (Wave 3)                              | |
  |  | Risk: 0.273 | Deps: 0.195 | Freq: 0.156 | CC: 0.156 | |
  |  |                                                        | |
  |  |  (Cytoscape graph showing billing's dependencies       | |
  |  |   color-coded by wave:                                 | |
  |  |   GREEN = earlier wave, BLUE = same wave,              | |
  |  |   RED = later wave)                                    | |
  |  +------------------------------------------------------+ |
  +-----------------------------------------------------------+
```

**What you can do here:**

1. **Generate Schedule** - Click to compute the optimal migration order
2. **Wave View** - See modules grouped into migration waves (color-coded by risk)
3. **Table View** - Toggle to a sortable grid with all metrics
4. **Drill Down** - Click any module to see its score breakdown and dependency graph
5. **Understand the score** - Each module is scored on 4 dimensions:
   - **Risk (35%)** - How risky is this module? (enhanced risk score from domain analysis)
   - **Dependency (25%)** - How many other modules depend on this one?
   - **Frequency (20%)** - How often has this module changed recently? (git history)
   - **Complexity (20%)** - How complex is the code? (cyclomatic complexity)

---

## Modules in Detail

### 1. Code Extraction

**What it does:** Parses every `.java` file in your codebase using OpenRewrite's AST engine and extracts structured metadata.

```
  Your Java Source Files
         |
         v
  +----------------------------------+
  |      OpenRewrite AST Parser      |
  |  (Lossless Java syntax tree)     |
  +----------------------------------+
         |
         v
  +----------------------------------+
  |        Seven Visitors            |
  |                                  |
  |  ClassMetadata  --> classes,     |
  |                     fields,      |
  |                     annotations  |
  |                                  |
  |  CallGraph      --> method-to-   |
  |                     method calls |
  |                                  |
  |  Dependency     --> class-to-    |
  |                     class deps   |
  |                                  |
  |  Vaadin Pattern --> Vaadin 7     |
  |                     views,       |
  |                     bindings     |
  |                                  |
  |  JPA Pattern    --> @Query,      |
  |                     @Entity,     |
  |                     table maps   |
  |                                  |
  |  Complexity     --> cyclomatic   |
  |                     complexity   |
  |                     per method   |
  |                                  |
  |  Lexicon        --> business     |
  |                     terms from   |
  |                     naming       |
  +----------------------------------+
         |
         v
  +----------------------------------+
  |         Neo4j Graph DB           |
  |  8 node types, 9 edge types      |
  +----------------------------------+
```

**How to use it:**

```bash
# Trigger extraction (returns 202 Accepted with jobId immediately)
curl -X POST "http://localhost:8080/api/extraction/trigger" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/path/to/your/src/main/java",
    "classpathFile": "/path/to/classpath.txt"
  }'
# Response: {"jobId": "abc-123", "status": "accepted"}

# Monitor progress via SSE (optional — useful for large codebases)
curl -N "http://localhost:8080/api/extraction/progress?jobId=abc-123"
# Streams: {"phase":"VISITING","filesProcessed":150,"totalFiles":500}
```

**Enterprise-scale support:** For codebases with 500+ files, extraction automatically parallelizes across multiple threads. Files are partitioned into batches (default 200), each processed by its own visitor instances. Results are merged and persisted using batched UNWIND MERGE Cypher for maximum throughput. Configure via `esmp.extraction.parallel-threshold` and `esmp.extraction.partition-size`.

**What gets extracted:**

| Node Type | What it represents | Example |
|-----------|-------------------|---------|
| `JavaClass` | A Java class/interface/enum | `com.acme.billing.InvoiceService` |
| `MethodNode` | A method within a class | `calculateTotal(Order)` |
| `FieldNode` | A field within a class | `private final OrderRepository repo` |
| `AnnotationNode` | An annotation on a class | `@Service`, `@Entity` |
| `PackageNode` | A Java package | `com.acme.billing` |
| `ModuleNode` | A module (3rd package segment) | `billing` |
| `DBTableNode` | A database table (from `@Table`) | `invoices` |
| `BusinessTerm` | A domain concept from naming | `Invoice`, `Payment` |

| Edge Type | What it means | Example |
|-----------|--------------|---------|
| `CALLS` | Method A calls Method B | `processOrder()` -> `validate()` |
| `EXTENDS` | Class A extends Class B | `AdminUser` -> `BaseUser` |
| `IMPLEMENTS` | Class implements Interface | `InvoiceService` -> `Billable` |
| `DEPENDS_ON` | Class A uses Class B | `OrderService` -> `OrderRepo` |
| `BINDS_TO` | Vaadin binding relationship | `OrderForm` -> `Order` |
| `QUERIES` | JPA `@Query` reference | `UserRepo` -> `User` |
| `MAPS_TO_TABLE` | ORM table mapping | `User` -> `users` |
| `USES_TERM` | Class uses business term | `InvoiceService` -> `Invoice` |
| `DEFINES_RULE` | Class defines business rule | `PriceCalculator` -> `Price` |

---

### 2. Code Knowledge Graph

**What it does:** After extraction, the LinkingService materializes all cross-class relationships in Neo4j using Cypher MERGE queries, creating a complete navigable graph of your codebase.

```
                    EXTENDS
  AdminUser  ─────────────────>  BaseUser
       |                            |
       | DEPENDS_ON                 | DEPENDS_ON
       v                            v
  UserRepository  <──────────  UserService
       |              DEPENDS_ON     |
       | QUERIES                     | CALLS
       v                            v
   users (table)              sendEmail()
```

**How to explore it:**

```bash
# Get structure of a specific class
curl "http://localhost:8080/api/graph/class/com.acme.billing.InvoiceService"

# See the inheritance chain
curl "http://localhost:8080/api/graph/class/com.acme.billing.InvoiceService/inheritance"

# See everything this class can reach (dependency cone)
curl "http://localhost:8080/api/graph/class/com.acme.billing.InvoiceService/dependency-cone"

# Search for classes by name
curl "http://localhost:8080/api/graph/search?name=Invoice"
```

You can also explore the graph visually in the Neo4j Browser at **http://localhost:7474**.

---

### 3. Graph Validation

**What it does:** Runs 41 automated quality checks against your code knowledge graph to ensure data integrity and highlight architectural issues.

```
  Validation Categories
  =====================

  STRUCTURAL INTEGRITY (10 queries)
  - Orphan nodes (classes with no edges)
  - Dangling references
  - Duplicate class names
  - Edge integrity (source/target exist)

  ARCHITECTURAL PATTERNS (10 queries)
  - Service → Repository dependencies
  - Repository → Entity queries
  - View → Binding patterns
  - Inheritance chain depth
  - Annotation coverage

  LEXICON (3 queries)
  - Orphan business terms
  - DEFINES_RULE coverage
  - USES_TERM edge integrity

  RISK (6 queries)
  - Risk scores populated
  - Fan-in/out computed
  - Domain scores populated
  - High-risk-no-dependencies check

  VECTOR (3 queries)
  - Vector index populated
  - Content hashes present
  - Source files accessible

  PILOT (3 queries)
  - Module class counts
  - Vaadin 7 nodes detected
  - Business terms extracted

  RAG (3 queries)
  - Cone query functional
  - Vector index aligned
  - Risk scores available

  SCHEDULING (3 queries)
  - Modules exist
  - Risk scores populated
  - Dependency edges exist
```

**How to use it:**

```bash
# Run all 41 validation queries
curl "http://localhost:8080/api/graph/validation"
```

Response shows pass/fail for each query with details:

```json
{
  "generatedAt": "2025-03-19T10:30:00Z",
  "passCount": 38,
  "warnCount": 2,
  "errorCount": 1,
  "results": [
    {
      "name": "ORPHAN_CLASSES",
      "severity": "WARNING",
      "status": "WARN",
      "count": 3,
      "details": ["com.acme.util.Helper", "com.acme.legacy.Old", "..."]
    }
  ]
}
```

---

### 4. Domain Lexicon

**What it does:** Automatically extracts business terms from your code's naming conventions, then lets you curate them to build a shared domain vocabulary.

```
  Source Code Analysis
  ====================

  class InvoiceCalculator {     -->  Terms: "Invoice", "Calculator"
      enum PaymentStatus {      -->  Terms: "Payment", "Status"
          PENDING, PAID, ...
      }
      /** Computes total */     -->  (Javadoc also scanned)
  }

  Extraction Rules:
  - CamelCase splitting (InvoiceService -> "Invoice")
  - Enum types and constants
  - Class-level Javadoc
  - 28 stop-suffixes filtered (Service, Controller, Impl, ...)
  - 12 stop-words filtered (get, set, is, ...)
```

**How to use it:**

```bash
# List all extracted business terms
curl "http://localhost:8080/api/lexicon/"

# Filter by criticality
curl "http://localhost:8080/api/lexicon/?criticality=High"

# Get term details (which classes use it)
curl "http://localhost:8080/api/lexicon/invoice"

# Curate a term (update definition, set criticality)
curl -X PUT "http://localhost:8080/api/lexicon/invoice" \
  -H "Content-Type: application/json" \
  -d '{
    "definition": "A financial document sent to customers for payment",
    "criticality": "High",
    "synonyms": ["bill", "receipt"]
  }'
```

> **Curated terms are protected** - once you mark a term as curated, re-running extraction won't overwrite your changes.

---

### 5. Risk Analysis

**What it does:** Computes a multi-dimensional risk score for every class, helping you understand which code is safest to migrate and which needs extra care.

ESMP computes risk in two layers:

```
  LAYER 1: Structural Risk (Phase 6)
  ===================================

  +-------------------+
  | Cyclomatic        |  How many branches (if/else/for/while/catch)?
  | Complexity (0.4)  |  Higher = harder to migrate safely
  +-------------------+
  | Fan-In (0.2)      |  How many classes depend on this one?
  |                   |  Higher = more things break if migration fails
  +-------------------+
  | Fan-Out (0.2)     |  How many classes does this one depend on?
  |                   |  Higher = more things need to work first
  +-------------------+
  | DB Writes (0.2)   |  Does this class write to the database?
  |                   |  Yes = extra migration risk
  +-------------------+
          |
          v
  structuralRiskScore (0.0 - 1.0)


  LAYER 2: Domain-Aware Risk (Phase 7)
  =====================================

  Takes structural risk and adds domain intelligence:

  +-------------------+
  | Domain            |  Uses HIGH criticality business terms?
  | Criticality (0.10)|  Score = 1.0 for High, 0.5 for Medium
  +-------------------+
  | Security          |  Contains auth/password/token/encrypt patterns?
  | Sensitivity (0.10)|  Checks names, annotations, and packages
  +-------------------+
  | Financial         |  Involves payment/invoice/billing logic?
  | Involvement (0.10)|  Heuristic + USES_TERM financial term boost
  +-------------------+
  | Business Rule     |  Defines validation/calculation rules?
  | Density (0.10)    |  log(1 + DEFINES_RULE count)
  +-------------------+
          |
          v
  enhancedRiskScore (0.0 - 1.0)
```

**How to use it:**

```bash
# Get risk heatmap (top 50 riskiest classes)
curl "http://localhost:8080/api/risk/heatmap?limit=50"

# Filter by module
curl "http://localhost:8080/api/risk/heatmap?module=billing"

# Sort by structural risk instead of enhanced risk
curl "http://localhost:8080/api/risk/heatmap?sortBy=structural"

# Get detailed risk breakdown for a specific class
curl "http://localhost:8080/api/risk/class/com.acme.billing.InvoiceCalculator"
```

The detail endpoint returns per-method complexity:

```json
{
  "fqn": "com.acme.billing.InvoiceCalculator",
  "enhancedRiskScore": 0.78,
  "structuralRiskScore": 0.65,
  "domainCriticality": 0.9,
  "securitySensitivity": 0.0,
  "financialInvolvement": 0.8,
  "methods": [
    { "simpleName": "calculateTotal", "cyclomaticComplexity": 12 },
    { "simpleName": "applyDiscount", "cyclomaticComplexity": 8 },
    { "simpleName": "validate", "cyclomaticComplexity": 15 }
  ]
}
```

---

### 6. Vector Indexing & Semantic Search

**What it does:** Embeds every class and method into a 384-dimensional vector space using the all-MiniLM-L6-v2 model, enabling semantic (meaning-based) code search.

```
  Chunking Strategy
  =================

  Each class produces multiple chunks:

  InvoiceService.java
       |
       +-> CLASS_HEADER chunk (class declaration + fields + annotations)
       |
       +-> METHOD chunk: calculateTotal()
       |
       +-> METHOD chunk: applyDiscount()
       |
       +-> METHOD chunk: validate()

  Each chunk is enriched with graph context:
  - Callers (who calls this method?)
  - Callees (what does this method call?)
  - Dependencies (what classes are needed?)
  - Domain terms (what business concepts?)
  - Risk scores (how risky is this code?)
  - Vaadin 7 detection flag
```

**How to use it:**

```bash
# Build the full vector index (first time)
curl -X POST "http://localhost:8080/api/vector/index?sourceRoot=/path/to/src"

# Incremental reindex (after code changes - only re-embeds changed files)
curl -X POST "http://localhost:8080/api/vector/reindex?sourceRoot=/path/to/src"

# Semantic search - find code by meaning, not just keywords
curl -X POST "http://localhost:8080/api/vector/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "calculate invoice totals with tax",
    "limit": 10,
    "module": "billing"
  }'
```

**What makes this different from `grep`?**

| Traditional Search | ESMP Vector Search |
|-------------------|-------------------|
| Matches exact keywords | Matches meaning/intent |
| `grep "calculate total"` finds `calculateTotal` | "compute invoice sum" finds `calculateTotal` |
| No context about relationships | Each result includes callers, callees, risk |
| No ranking by relevance | Ranked by cosine similarity |

---

### 7. Pilot Module Selection

**What it does:** Recommends which module to migrate first as a pilot, based on Vaadin 7 density, risk diversity, and manageable size.

```
  Pilot Scoring Formula
  =====================

  score = vaadin7_density * 0.4    (more Vaadin 7 code = better pilot)
        + risk_diversity  * 0.3    (mix of risk levels = more learning)
        + size_score      * 0.3    (5-30 classes = manageable scope)
```

**How to use it:**

```bash
# Get top 5 pilot module recommendations
curl "http://localhost:8080/api/pilot/recommend"

# Validate a specific module for pilot readiness
curl "http://localhost:8080/api/pilot/validate/billing"
```

The validation report includes 5 checks:

| Check | What it verifies |
|-------|-----------------|
| `MODULE_HAS_CLASSES` | Module has sufficient classes |
| `VAADIN7_PRESENT` | Module contains Vaadin 7 code |
| `GRAPH_COMPLETE` | All relationships are indexed |
| `VECTOR_INDEXED` | Code is searchable by AI |
| `TERMS_EXTRACTED` | Business terms are identified |

---

### 8. RAG Context Assembly

**What it does:** Retrieves the most relevant code context for any migration question using a multi-layer retrieval strategy combining graph traversal and vector similarity.

```
  RAG Pipeline (3-Layer Retrieval)
  ================================

  Query: "How does InvoiceService work?"
         |
         v
  +----------------------------------+
  | 1. RESOLVE focal class           |
  |    - FQN match?                  |
  |    - Simple name match?          |
  |    - Natural language search?    |
  |    - Disambiguation if ambiguous |
  +----------------------------------+
         |
         v
  +----------------------------------+
  | 2. EXPAND dependency cone        |
  |    Traverse up to 10 hops via:   |
  |    DEPENDS_ON, EXTENDS,          |
  |    IMPLEMENTS, CALLS, BINDS_TO,  |
  |    QUERIES, MAPS_TO_TABLE        |
  |                                  |
  |    Result: all reachable classes  |
  |    with hop distance             |
  +----------------------------------+
         |
         v
  +----------------------------------+
  | 3. VECTOR SEARCH within cone     |
  |    Search only chunks belonging  |
  |    to classes in the cone        |
  +----------------------------------+
         |
         v
  +----------------------------------+
  | 4. WEIGHTED RE-RANKING           |
  |                                  |
  |  finalScore =                    |
  |    vectorSimilarity * 0.40       |
  |  + graphProximity   * 0.35       |
  |  + riskScore        * 0.25       |
  |                                  |
  |  Closer in graph + more similar  |
  |  + higher risk = ranked higher   |
  +----------------------------------+
         |
         v
  Top-K context chunks with scores
```

**How to use it:**

```bash
# Get migration context by fully qualified name
curl -X POST "http://localhost:8080/api/rag/context" \
  -H "Content-Type: application/json" \
  -d '{
    "fqn": "com.acme.billing.InvoiceService",
    "limit": 20
  }'

# Get context by natural language query
curl -X POST "http://localhost:8080/api/rag/context" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "invoice calculation logic",
    "limit": 15,
    "module": "billing"
  }'
```

The response includes rich context for each code chunk:

```json
{
  "queryType": "FQN",
  "focalClass": {
    "fqn": "com.acme.billing.InvoiceService",
    "enhancedRiskScore": 0.78,
    "vaadin7Detected": true
  },
  "contextChunks": [
    {
      "classFqn": "com.acme.billing.InvoiceCalculator",
      "chunkType": "METHOD",
      "methodId": "calculateTotal",
      "scores": {
        "vectorScore": 0.89,
        "graphProximityScore": 0.95,
        "riskScore": 0.78,
        "finalScore": 0.87
      }
    }
  ],
  "coneSummary": {
    "totalNodes": 45,
    "vaadin7Count": 8,
    "avgEnhancedRisk": 0.52
  }
}
```

---

### 9. Incremental Indexing

**What it does:** Keeps ESMP in sync with ongoing development. When files change, only the affected classes are re-extracted, re-linked, re-scored, and re-embedded.

```
  Incremental Pipeline (8 Steps)
  ==============================

  Changed: OrderService.java, PaymentService.java
  Deleted: OldHelper.java

         |
         v
  1. VALIDATE inputs
  2. DELETE removed classes from Neo4j + Qdrant
  3. HASH-FILTER (skip files unchanged since last run)
  4. PARSE + PERSIST changed files (new transaction)
  5. LINK relationships for changed classes
  6. RECOMPUTE risk scores
  7. RE-EMBED only changed classes into Qdrant
  8. RETURN response with counts
```

**How to use it:**

```bash
# Incremental update (specific files changed)
curl -X POST "http://localhost:8080/api/indexing/incremental" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/path/to/src/main/java",
    "changedFiles": [
      "com/acme/billing/OrderService.java",
      "com/acme/billing/PaymentService.java"
    ],
    "deletedFiles": [
      "com/acme/util/OldHelper.java"
    ]
  }'

# Full re-index (scans all .java files)
curl -X POST "http://localhost:8080/api/indexing/incremental" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/path/to/src/main/java"
  }'
```

---

### 10. Migration Scheduling

**What it does:** Computes the optimal module migration order using topological sorting (respecting dependencies) and 4-dimension composite scoring.

```
  Scheduling Algorithm
  ====================

  Step 1: Aggregate module metrics from Neo4j
  Step 2: Count cross-module dependents
  Step 3: Build dependency graph edges
  Step 4: Measure git change frequency (last 180 days)
  Step 5: Topological sort (Kahn's algorithm)
           - Modules with no dependencies = Wave 1
           - Their dependents = Wave 2
           - And so on...
           - Circular dependencies = Final wave
  Step 6: Score each module:

  finalScore = risk       * 0.35   (avg enhanced risk)
             + dependency * 0.25   (normalized dependent count)
             + frequency  * 0.20   (normalized commit count)
             + complexity * 0.20   (log-normalized avg CC)

  Step 7: Sort within each wave by score (lowest first)

  Result:
  +--------+----------+-------+---------+
  | Wave 1 | Wave 2   | Wave 3| Wave 4  |
  | (safe) | (moderate)|(risky)|(cycles) |
  |        |          |       |         |
  | utils  | service  |billing| auth<>  |
  | config | data     |  ui   | payment |
  | common | report   |       |         |
  +--------+----------+-------+---------+

  Migrate left to right.
  Lower score within wave = migrate first.
```

**How to use it:**

```bash
# Get the recommended migration schedule
curl "http://localhost:8080/api/scheduling/recommend"

# With git frequency analysis (pass your source root)
curl "http://localhost:8080/api/scheduling/recommend?sourceRoot=/path/to/project"
```

> **Note:** If git is not available at the sourceRoot path, the frequency dimension defaults to zero and the schedule is still generated using the other three dimensions.

---

### 11. MCP Server

**What it does:** Exposes all ESMP knowledge services as [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) tools via SSE transport, so AI assistants like Claude Code can query migration context, search code, explore dependencies, assess risk, browse domain terms, and validate system health — all through a single connection.

```
  AI Assistant (Claude Code, Cursor, etc.)
         │
         │ MCP SSE (Server-Sent Events)
         │ http://localhost:8080/mcp/sse
         │
         ▼
  ┌─────────────────────────────┐
  │   MCP Server (Spring AI)    │
  │                             │
  │  6 Tools:                   │
  │  ┌───────────────────────┐  │
  │  │ getMigrationContext   │──┼──▶ MigrationContextAssembler (5 services in parallel)
  │  │ searchKnowledge       │──┼──▶ VectorSearchService (semantic code search)
  │  │ getDependencyCone     │──┼──▶ GraphQueryService (graph traversal)
  │  │ getRiskAnalysis       │──┼──▶ RiskService (structural + domain risk)
  │  │ browseDomainTerms     │──┼──▶ LexiconService (business vocabulary)
  │  │ validateSystemHealth  │──┼──▶ ValidationService (41 integrity checks)
  │  └───────────────────────┘  │
  │                             │
  │  Caffeine Cache (3 caches)  │
  │  Micrometer Metrics         │
  └─────────────────────────────┘
```

#### Setup: Connecting Claude Code to ESMP

**Step 1:** Start ESMP (either via Docker or locally):

```bash
# Docker (recommended)
docker compose -f docker-compose.full.yml up -d

# Or locally
docker compose up -d && ./gradlew bootRun -Dorg.gradle.java.home="/path/to/java21"
```

**Step 2:** The `.mcp.json` file at the project root auto-configures the connection:

```json
{
  "mcpServers": {
    "esmp": {
      "type": "sse",
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
```

**Step 3:** Open Claude Code in the ESMP project directory. It automatically discovers the MCP server and all 6 tools. Verify by asking:

```
You: "What tools do you have from ESMP?"
Claude: I have 6 ESMP tools available:
        - getMigrationContext
        - searchKnowledge
        - getDependencyCone
        - getRiskAnalysis
        - browseDomainTerms
        - validateSystemHealth
```

> **For other MCP clients** (Cursor, custom agents): point your MCP client's SSE transport at `http://localhost:8080/mcp/sse`. The server conforms to the standard MCP SSE protocol.

#### Tool Reference

##### 1. `getMigrationContext` — Primary context tool

The most important tool. Assembles comprehensive migration context for a class by calling 5 services in parallel.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `classFqn` | String | Yes | Fully-qualified class name (e.g. `com.acme.billing.InvoiceService`) |

**What it returns:**

```json
{
  "classFqn": "com.acme.billing.InvoiceService",
  "dependencyCone": {
    "fqn": "com.acme.billing.InvoiceService",
    "nodes": [
      {"fqn": "com.acme.billing.InvoiceRepository", "labels": ["JavaClass", "Repository"]},
      {"fqn": "com.acme.billing.Invoice", "labels": ["JavaClass", "Entity"]},
      {"fqn": "com.acme.common.BaseService", "labels": ["JavaClass", "Service"]}
    ]
  },
  "riskAnalysis": {
    "fqn": "com.acme.billing.InvoiceService",
    "enhancedRiskScore": 0.72,
    "structuralRiskScore": 0.58,
    "domainCriticality": 0.9,
    "securitySensitivity": 0.0,
    "financialInvolvement": 0.8,
    "businessRuleDensity": 0.3,
    "methods": [
      {"simpleName": "calculateTotal", "cyclomaticComplexity": 12}
    ]
  },
  "domainTerms": [
    {"termId": "invoice", "displayName": "Invoice", "criticality": "High", "usageCount": 23}
  ],
  "businessRules": ["PriceCalculator", "DiscountValidator"],
  "codeChunks": [
    {
      "classFqn": "com.acme.billing.InvoiceRepository",
      "chunkType": "METHOD",
      "methodId": "findByCustomerId",
      "text": "public List<Invoice> findByCustomerId(Long id) { ... }",
      "scores": {"vectorScore": 0.89, "graphProximityScore": 0.95, "riskScore": 0.3, "finalScore": 0.74}
    }
  ],
  "truncated": false,
  "truncatedItems": 0,
  "contextCompleteness": 1.0,
  "warnings": [],
  "durationMs": 450
}
```

**Key fields:**
- `contextCompleteness` (0.0–1.0): How many of the 5 services returned data. 1.0 = all services contributed. Below 1.0 means some services failed (check `warnings`).
- `truncated`: true if code chunks were dropped to fit the token budget (default 8000 tokens).
- `warnings`: Lists any services that failed with reason. Migration still works with partial context.

##### 2. `searchKnowledge` — Semantic code search

Find relevant code by meaning, not just keywords. Uses vector embeddings (all-MiniLM-L6-v2, 384-dim).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | String | Yes | Natural language query (e.g. `"payment processing logic"`) |
| `module` | String | No | Filter by module (e.g. `"billing"`) |
| `stereotype` | String | No | Filter by stereotype (`"Service"`, `"Repository"`, `"Entity"`, etc.) |
| `topK` | int | No | Max results (default 10) |

**Example response:**

```json
{
  "results": [
    {
      "classFqn": "com.acme.billing.PaymentProcessor",
      "chunkType": "METHOD",
      "methodId": "processPayment",
      "text": "public PaymentResult processPayment(Order order, PaymentMethod method) { ... }",
      "score": 0.91,
      "module": "billing",
      "stereotype": "Service",
      "enhancedRiskScore": 0.65
    }
  ],
  "totalResults": 8,
  "durationMs": 120
}
```

##### 3. `getDependencyCone` — Dependency graph exploration

Traverses all 7 relationship types (DEPENDS_ON, EXTENDS, IMPLEMENTS, CALLS, BINDS_TO, QUERIES, MAPS_TO_TABLE) up to 10 hops.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `classFqn` | String | Yes | Fully-qualified class name |
| `maxDepth` | int | No | Traversal depth (default 10) |

**Example response:**

```json
{
  "fqn": "com.acme.billing.InvoiceService",
  "nodes": [
    {"fqn": "com.acme.billing.InvoiceRepository", "labels": ["JavaClass", "Repository"]},
    {"fqn": "com.acme.billing.Invoice", "labels": ["JavaClass", "Entity"]},
    {"fqn": "invoices", "labels": ["DBTable"]}
  ]
}
```

##### 4. `getRiskAnalysis` — Risk assessment

Two modes: class detail (when `classFqn` provided) or heatmap (when `classFqn` is empty/null).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `classFqn` | String | No | Class FQN for detail mode; empty/null for heatmap |
| `module` | String | No | Module filter (heatmap mode only) |
| `sortBy` | String | No | `"structural"` or `"enhanced"` (default) |
| `limit` | int | No | Max heatmap entries (default 20) |

**Detail mode** returns per-method complexity breakdown, all 6 risk dimensions (structural, domain criticality, security sensitivity, financial involvement, business rule density, enhanced composite).

**Heatmap mode** returns a ranked list of the riskiest classes across the codebase.

##### 5. `browseDomainTerms` — Business vocabulary

Browse the automatically extracted domain lexicon.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `search` | String | No | Case-insensitive text search on term names |
| `criticality` | String | No | Filter: `"High"`, `"Medium"`, or `"Low"` |

**Example response:**

```json
[
  {
    "termId": "invoice",
    "displayName": "Invoice",
    "definition": "A financial document sent to customers for payment",
    "criticality": "High",
    "usageCount": 23,
    "relatedClasses": ["com.acme.billing.InvoiceService", "com.acme.billing.Invoice"]
  }
]
```

##### 6. `validateSystemHealth` — Integrity checks

Runs all 41 validation queries. No parameters.

**Example response:**

```json
{
  "generatedAt": "2026-03-28T10:30:00Z",
  "passCount": 39,
  "warnCount": 1,
  "errorCount": 1,
  "results": [
    {"name": "ORPHAN_CLASSES", "severity": "WARNING", "status": "WARN", "count": 3},
    {"name": "VECTOR_INDEX_POPULATED", "severity": "ERROR", "status": "PASS", "count": 1250}
  ]
}
```

#### Practical Usage Workflows

##### Workflow 1: Migrate a class with full AI assistance

```
You: "I need to migrate com.acme.billing.InvoiceService from Vaadin 7 to Vaadin 24.
      Get the full migration context first."

Claude: [calls getMigrationContext("com.acme.billing.InvoiceService")]
        → Receives dependency cone (18 nodes), risk analysis (enhanced: 0.72, financial: 0.8),
          3 domain terms (Invoice, Payment, Order), 2 business rules, 12 code chunks

Claude: "Here's what I found about InvoiceService:
         - HIGH financial involvement (0.8) — handle payment logic carefully
         - 18 transitive dependencies — InvoiceRepository, Invoice entity, ...
         - Key business rules: PriceCalculator, DiscountValidator
         - Vaadin 7 patterns detected: uses com.vaadin.ui.Table

         Here's the migrated Vaadin 24 code: ..."
```

##### Workflow 2: Explore unfamiliar code areas

```
You: "What classes handle authentication in this codebase?"

Claude: [calls searchKnowledge("authentication login security", null, null, 15)]
        → Returns 15 ranked code chunks with auth-related classes

Claude: "Found 8 classes related to authentication:
         1. AuthenticationService (risk: 0.85 — high security sensitivity)
         2. LoginView (Vaadin 7 view — needs migration)
         3. SessionManager ..."

You: "What does AuthenticationService depend on?"

Claude: [calls getDependencyCone("com.acme.auth.AuthenticationService", 5)]
        → Returns dependency tree

Claude: "AuthenticationService depends on:
         - UserRepository (1 hop)
         - User entity (2 hops)
         - users table (3 hops)
         - SessionManager (1 hop)
         - PasswordEncoder (1 hop)"
```

##### Workflow 3: Pre-migration health check

```
You: "Before I start migrating, is the knowledge graph healthy?"

Claude: [calls validateSystemHealth()]
        → Returns 41 query results

Claude: "System health: 39 pass, 1 warning, 1 error
         - WARNING: 3 orphan classes (no relationships) — likely utility classes
         - ERROR: Vector index not populated — run extraction first

         Recommendation: Run POST /api/extraction/trigger followed by
         POST /api/vector/index before starting migration."
```

##### Workflow 4: Understand business impact before migrating

```
You: "What business terms are HIGH criticality? I want to migrate those last."

Claude: [calls browseDomainTerms(null, "High")]
        → Returns all high-criticality terms

Claude: "12 high-criticality business terms found:
         - Invoice (23 usages) — used by InvoiceService, InvoiceCalculator, ...
         - Payment (18 usages) — PaymentProcessor, PaymentGateway, ...
         - Account (15 usages) — AccountService, AccountBalance, ...

         These modules have the highest business impact.
         Recommend migrating them in later waves with extra review."
```

##### Workflow 5: Risk-driven migration planning

```
You: "Show me the riskiest classes in the billing module"

Claude: [calls getRiskAnalysis(null, "billing", "enhanced", 10)]
        → Returns top 10 riskiest billing classes

Claude: "Top 3 riskiest classes in billing:
         1. InvoiceCalculator (0.78) — CC:15, financial:0.8, 3 business rules
         2. PaymentProcessor (0.72) — CC:12, security:0.6, DB writes
         3. BillingScheduler (0.65) — CC:8, 5 dependencies

         Start with low-risk classes first (OrderDTO, BillingConfig) and
         save these for last."

You: "Now give me the full context for BillingConfig so I can migrate it"

Claude: [calls getMigrationContext("com.acme.billing.BillingConfig")]
        → Assembles context for the low-risk class

Claude: "BillingConfig has risk 0.12 (safe to migrate):
         - No Vaadin 7 patterns (pure configuration)
         - 2 dependents: InvoiceService, PaymentProcessor
         - No business terms

         Here's the migrated code: ..."
```

#### Infrastructure Details

- **Token budgeting**: `getMigrationContext` truncates code chunks first (then cone nodes) to stay within configurable token limit (default 8000). Configure via `esmp.mcp.context.max-tokens`.
- **Graceful degradation**: If any of the 5 parallel services fail, returns partial context with warnings and reduced `contextCompleteness` score. Migration can proceed with partial data.
- **Caffeine caching**: Three named caches with configurable TTLs:
  - `dependencyCones` (5 min) — cone traversals are expensive
  - `domainTermsByClass` (10 min) — terms change infrequently
  - `semanticQueries` (3 min) — embedding results
  - All caches auto-evict when incremental reindexing runs
- **Observability**: Every tool call is instrumented with `@Timed` Micrometer metrics and structured logging. Query Prometheus for `esmp.mcp.request.duration` and `esmp.mcp.tool.invocations`.

---

### 12. Docker Deployment & Source Access

**What it does:** Packages ESMP as a single Docker image with all dependencies, enabling one-command deployment. Includes runtime source access so the container can analyze external codebases via volume mount or GitHub clone.

```
  Deployment Options
  ==================

  Option A: Volume Mount (default)           Option B: GitHub Clone
  ================================           ======================

  Host Machine                               GitHub Repository
  +-------------------+                      +-------------------+
  | /path/to/project  |                      | github.com/org/   |
  |   src/main/java/  |                      |   repo.git        |
  +--------+----------+                      +--------+----------+
           |                                          |
     bind mount (:ro)                          JGit clone (PAT)
           |                                          |
           v                                          v
  +--------------------------------------------------+
  |              Docker Container (ESMP)              |
  |                                                    |
  |  SourceAccessService                              |
  |  - Resolves source root on startup                |
  |  - VOLUME_MOUNT: reads /mnt/source               |
  |  - GITHUB_URL: clones to /data/esmp-source-clone |
  |                                                    |
  |  GET /api/source/status                           |
  |  -> {"strategy":"VOLUME_MOUNT","resolved":true}   |
  +--------------------------------------------------+
           |            |           |
  +--------+--+ +------+----+ +----+------+
  |   Neo4j   | |  Qdrant   | |   MySQL   |
  +--------+--+ +------+----+ +----+------+
  |Prometheus | |  Grafana  |
  +-----------+ +-----------+
```

**How to deploy:**

```bash
# 1. Copy environment template
cp .env.example .env

# 2. Configure source access (edit .env)
# For volume mount:
ESMP_SOURCE_STRATEGY=VOLUME_MOUNT
SOURCE_ROOT=/path/to/your/codebase

# For GitHub clone:
ESMP_SOURCE_STRATEGY=GITHUB_URL
ESMP_SOURCE_GITHUB_URL=https://github.com/org/repo.git
ESMP_SOURCE_GITHUB_TOKEN=ghp_xxxxx
ESMP_SOURCE_BRANCH=main

# 3. Start everything
docker compose -f docker-compose.full.yml up -d

# 4. Check status
curl http://localhost:8080/api/source/status
curl http://localhost:8080/actuator/health
```

**Docker image details:**
- Multi-stage build: `eclipse-temurin:21-jdk` (build) → `eclipse-temurin:21-jre` (runtime)
- Vaadin frontend compiled in build stage (`vaadinBuildFrontend`)
- Layered JAR extraction for optimal Docker layer caching
- Non-root `esmp` user (UID 1000)
- HEALTHCHECK via `/actuator/health/liveness` (30s interval, 120s start period)
- JVM tuned: 75% max RAM, G1GC, OOM heap dump

**Parallel extraction for enterprise scale:**

For codebases with 500+ files (configurable), extraction automatically parallelizes:

```
  Sequential (< threshold)          Parallel (>= threshold)
  ==========================       ===========================

  File 1 → visit → accumulator     Partition 1 → visit → acc1 \
  File 2 → visit → accumulator     Partition 2 → visit → acc2  |→ merge → persist
  File 3 → visit → accumulator     Partition 3 → visit → acc3 /
  ...                                (each partition: own visitors, own accumulator)
```

- Configurable via `esmp.extraction.parallel-threshold` (default 500) and `esmp.extraction.partition-size` (default 200)
- Per-partition visitor instances — no shared mutable state
- Annotation/Package/Module/DBTable/BusinessTerm persisted via batched UNWIND MERGE Cypher (2000-row batches)
- Bounded `ThreadPoolTaskExecutor` (core=4, max=available processors, CallerRunsPolicy backpressure)

**SSE progress streaming:**

For long-running extractions, monitor progress in real time:

```bash
# Terminal 1: trigger extraction (returns immediately with jobId)
curl -X POST http://localhost:8080/api/extraction/trigger \
  -H "Content-Type: application/json" \
  -d '{"sourceRoot": "/path/to/src"}'
# → {"jobId": "abc-123", "status": "accepted"}

# Terminal 2: stream progress events
curl -N "http://localhost:8080/api/extraction/progress?jobId=abc-123"
# → event: progress
# → data: {"phase":"SCANNING","filesProcessed":0,"totalFiles":4200}
# → event: progress
# → data: {"phase":"VISITING","filesProcessed":500,"totalFiles":4200}
# → ...
# → event: done
# → data: complete
```

Progress phases: `SCANNING` → `PARSING` → `VISITING` → `PERSISTING` → `LINKING`

---

## REST API Reference

Complete list of all 22 endpoints:

### Extraction

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/extraction/trigger` | Async extraction — returns 202 with `jobId` |
| `GET` | `/api/extraction/progress?jobId=X` | SSE stream of extraction progress events |

### Graph Queries

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/graph/class/{fqn}` | Class structure (methods, fields, annotations) |
| `GET` | `/api/graph/class/{fqn}/inheritance` | Inheritance chain (up to 10 hops) |
| `GET` | `/api/graph/class/{fqn}/dependency-cone` | All transitively reachable nodes |
| `GET` | `/api/graph/repository/{fqn}/service-dependents` | Services depending on a repository |
| `GET` | `/api/graph/search?name=...` | Search classes by name |
| `GET` | `/api/graph/validation` | Run all 41 validation queries |

### Domain & Risk

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/lexicon/` | List business terms (filterable) |
| `GET` | `/api/lexicon/{termId}` | Term detail with related classes |
| `PUT` | `/api/lexicon/{termId}` | Curate a business term |
| `GET` | `/api/risk/heatmap` | Risk heatmap (filterable, sortable) |
| `GET` | `/api/risk/class/{fqn}` | Per-class risk detail with method CC |

### Vector & AI

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/vector/index` | Full vector indexing |
| `POST` | `/api/vector/reindex` | Incremental vector reindex |
| `POST` | `/api/vector/search` | Semantic code search |
| `POST` | `/api/rag/context` | RAG context assembly |

### Operations

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/indexing/incremental` | Incremental or full re-indexing |
| `GET` | `/api/pilot/recommend` | Pilot module recommendations |
| `GET` | `/api/pilot/validate/{module}` | Module pilot readiness check |
| `GET` | `/api/scheduling/recommend` | Migration wave schedule |

### Source Access

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/source/status` | Source access strategy and resolution status |

### MCP (AI Assistant Integration)

| Transport | Endpoint | Description |
|-----------|----------|-------------|
| `SSE` | `/mcp/sse` | MCP server — exposes 6 tools (getMigrationContext, searchKnowledge, getDependencyCone, getRiskAnalysis, browseDomainTerms, validateSystemHealth) |

---

## Vaadin Dashboard Views

| URL | View | What it shows |
|-----|------|---------------|
| `http://localhost:8080/` | Dashboard | Metrics, risk heatmap, dependency graphs, business concepts |
| `http://localhost:8080/lexicon` | Lexicon | Business term curation with usage tracking |
| `http://localhost:8080/schedule` | Schedule | Migration wave planning with drill-down |

---

## Configuration Reference

All configuration is in `src/main/resources/application.yml`. Every setting supports environment variable overrides via `${ENV_VAR:default}` syntax, making Docker deployment configurable without rebuilding.

### Database Connections

```yaml
spring:
  neo4j:
    uri: ${SPRING_NEO4J_URI:bolt://localhost:7687}
    authentication:
      username: ${SPRING_NEO4J_AUTHENTICATION_USERNAME:neo4j}
      password: ${SPRING_NEO4J_AUTHENTICATION_PASSWORD:esmp-local-password}
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3307/esmp}
    username: ${SPRING_DATASOURCE_USERNAME:esmp}
    password: ${SPRING_DATASOURCE_PASSWORD:esmp-local-password}

qdrant:
  host: ${QDRANT_HOST:localhost}
  port: ${QDRANT_PORT:6334}
```

### Source Access (Docker Deployment)

```yaml
esmp:
  source:
    strategy: ${ESMP_SOURCE_STRATEGY:VOLUME_MOUNT}    # VOLUME_MOUNT or GITHUB_URL
    volume-mount-path: ${ESMP_SOURCE_VOLUME_MOUNT_PATH:/mnt/source}
    github-url: ${ESMP_SOURCE_GITHUB_URL:}
    github-token: ${ESMP_SOURCE_GITHUB_TOKEN:}
    clone-directory: ${ESMP_SOURCE_CLONE_DIRECTORY:/tmp/esmp-source-clone}
    branch: ${ESMP_SOURCE_BRANCH:main}
```

### Parallel Extraction (Enterprise Scale)

```yaml
esmp:
  extraction:
    parallel-threshold: ${ESMP_EXTRACTION_PARALLEL_THRESHOLD:500}  # Files above this → parallel
    partition-size: ${ESMP_EXTRACTION_PARTITION_SIZE:200}           # Files per partition
```

### Risk Scoring Weights

```yaml
esmp:
  risk:
    weight:
      # Structural risk weights (sum to 1.0)
      complexity: 0.4
      fan-in: 0.2
      fan-out: 0.2
      db-writes: 0.2
```

### RAG Pipeline Weights

```yaml
esmp:
  rag:
    weight:
      vector-similarity: 0.40   # How similar is the code text?
      graph-proximity: 0.35     # How close in the dependency graph?
      risk-score: 0.25          # How risky is this code?
```

### Scheduling Weights

```yaml
esmp:
  scheduling:
    weight:
      risk: 0.35
      dependency: 0.25
      frequency: 0.20
      complexity: 0.20
    git-window-days: 180        # How far back to look at git history
```

### MCP Server Settings

```yaml
esmp:
  mcp:
    context:
      max-tokens: 8000            # Token budget for getMigrationContext
    cache:
      dependency-cone-ttl-minutes: 5
      domain-terms-ttl-minutes: 10
      semantic-query-ttl-minutes: 3
      max-size: 500               # Max entries per cache
```

### Vector Index Settings

```yaml
esmp:
  vector:
    collection-name: code_chunks
    vector-dimension: 384       # Must match embedding model
    batch-size: 64              # Chunks per Qdrant upsert batch
```

---

## Running Tests

ESMP has comprehensive test coverage with 31 test suites:

```bash
# Run all tests (skip Vaadin frontend compilation)
./gradlew test -x vaadinPrepareFrontend \
  -Dorg.gradle.java.home="/path/to/java21"

# Run tests for a specific module
./gradlew test --tests "com.esmp.scheduling.*" -x vaadinPrepareFrontend

# Run only unit tests (fast, no Docker needed)
./gradlew test --tests "com.esmp.extraction.visitor.*" -x vaadinPrepareFrontend

# Run integration tests (requires Docker for Testcontainers)
./gradlew test --tests "com.esmp.*IntegrationTest" -x vaadinPrepareFrontend
```

> **Note:** Integration tests use Testcontainers which automatically starts Neo4j, MySQL, and Qdrant in Docker. Make sure Docker is running.

### Test Coverage Summary

| Module | Unit Tests | Integration Tests |
|--------|-----------|-------------------|
| Extraction (visitors) | 7 suites | 3 suites |
| Extraction (parallel + batched) | - | 2 suites (4 tests) |
| Extraction (progress SSE) | 1 suite (7 tests) | - |
| Graph (queries, validation) | - | 3 suites |
| Risk (structural + domain) | - | 2 suites (24 tests) |
| Vector (chunking, indexing) | 2 suites (13 tests) | 2 suites (12 tests) |
| Pilot | - | 2 suites (13 tests) |
| RAG | - | 2 suites (14 tests) |
| Incremental Indexing | - | 1 suite (8 tests) |
| Scheduling | 1 suite (3 tests) | 1 suite (6 tests) |
| Dashboard | - | 1 suite (7 tests) |
| MCP Server | 2 suites (5 tests) | 2 suites (11 tests) |
| Source Access | 1 suite (3 tests) | - |
| Infrastructure | - | 2 suites |

---

## Monitoring

### Prometheus (http://localhost:9090)

ESMP exposes Spring Boot Actuator metrics at `/actuator/prometheus`. Prometheus scrapes these automatically.

Useful queries:

```promql
# JVM memory usage
jvm_memory_used_bytes{area="heap"}

# HTTP request rate
rate(http_server_requests_seconds_count[5m])

# Request latency (95th percentile)
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
```

### Grafana (http://localhost:3000)

Login with `admin` / `admin` (or your configured password).

Add Prometheus as a data source:
1. Go to Configuration > Data Sources > Add data source
2. Select Prometheus
3. URL: `http://prometheus:9090`
4. Click "Save & Test"

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

Returns status of all datastores (Neo4j, MySQL, Qdrant):

```json
{
  "status": "UP",
  "components": {
    "neo4j": { "status": "UP" },
    "db": { "status": "UP" },
    "qdrant": { "status": "UP" }
  }
}
```

---

## Troubleshooting

### Build fails with `vaadinPrepareFrontend` NPE

```
Cannot invoke "java.lang.Class.isInterface()" because the return value of
"org.reflections.Reflections.forClass(String, java.lang.ClassLoader[])" is null
```

**Cause:** The Gradle daemon is running Java 17 but the project compiles to Java 21 bytecode. Vaadin's classpath scanner can't load the classes.

**Fix:** Always specify Java 21 for the Gradle JVM:

```bash
./gradlew clean bootRun -Dorg.gradle.java.home="/path/to/java21"
```

Also clean cached artifacts:

```bash
rm -rf build/ node_modules/ src/main/frontend/generated/
```

### Docker full-stack won't start (port 8080 in use)

If `docker compose -f docker-compose.full.yml up` fails with a port conflict on 8080:

```bash
# Find what's using port 8080
netstat -ano | grep ":8080"
# On Windows: taskkill /PID <pid> /F
# On Linux: kill -9 <pid>
```

Common culprits: a running Gradle bootRun or a Spring Boot test process.

### Docker services won't start

**Check Docker is running:**

```bash
docker info
```

**Check port conflicts:**

```bash
# These ports must be free:
# 8080 (ESMP), 7474, 7687 (Neo4j), 6333, 6334 (Qdrant), 3307 (MySQL),
# 9090 (Prometheus), 3000 (Grafana)
netstat -an | grep -E "8080|7474|7687|6333|6334|3307|9090|3000"
```

**Reset all data and restart:**

```bash
docker compose down -v   # Removes all volumes (data loss!)
docker compose up -d
```

### Extraction returns 0 classes

**Check your sourceRoot path:**
- Must be an absolute path
- Must point to the `src/main/java` directory (or wherever your `.java` files are)
- Path must exist and be readable

```bash
# Verify the path has Java files
find /your/path -name "*.java" | head -5
```

### Vector indexing is slow

First-time indexing downloads the ONNX embedding model (~90 MB). Subsequent runs use the cached model.

For large codebases (1000+ classes), the batch size can be tuned:

```yaml
esmp:
  vector:
    batch-size: 128  # Default is 64, increase for faster throughput
```

### Application starts but dashboard is blank

The Vaadin frontend needs to be compiled on first run. This happens automatically but can take 30-60 seconds. Check the console for:

```
Frontend compiled successfully
```

If it fails, clean and rebuild:

```bash
rm -rf build/ node_modules/ src/main/frontend/generated/
./gradlew clean bootRun -Dorg.gradle.java.home="/path/to/java21"
```

---

## Typical Workflow

Here's a complete workflow from start to finish:

```
  Step 1: Setup                    Step 2: Analyze
  cp .env.example .env             POST /api/extraction/trigger
  docker compose -f                  (returns jobId, runs async)
    docker-compose.full.yml        POST /api/vector/index
    up -d

       |                                |
       v                                v

  Step 3: Explore                  Step 4: Plan
  Open http://localhost:8080       GET /api/scheduling/recommend
  Review risk heatmap              Review wave assignment
  Explore dependencies             Pick pilot module
  Curate business terms            GET /api/pilot/validate/{module}

       |                                |
       v                                v

  Step 5: Migrate                  Step 6: Keep Updated
  POST /api/rag/context            POST /api/indexing/incremental
  (Get AI context for migration)   (After each code change)
  Write new Vaadin 24 code         Re-check risk scores
  Repeat for each class            Monitor validation health
```

---

## v1: Manual AI-Assisted Migration

> **Available now.** You drive the migration, ESMP provides the intelligence. You use ESMP's APIs to get rich, graph-aware context for each class, feed it to any AI tool (Claude, GPT, Copilot), review the output, and re-index. ESMP validates every step.

> **Looking for the fully automated version?** See [v2: Autonomous AI Orchestration Engine](#v2-autonomous-ai-orchestration-engine-roadmap) below — where ESMP itself calls Claude, generates code, validates it, and opens PRs automatically.

### The Big Picture

```
  +=======================================================================+
  |                   ESMP AS MIGRATION ORCHESTRATOR                       |
  +=======================================================================+
  |                                                                        |
  |   TRADITIONAL MIGRATION              ESMP-ORCHESTRATED MIGRATION       |
  |   =====================              ============================      |
  |                                                                        |
  |   Developer reads old code           ESMP analyzes entire codebase     |
  |        |                                    |                          |
  |   Developer guesses what             ESMP builds knowledge graph       |
  |   depends on what                    of ALL relationships              |
  |        |                                    |                          |
  |   Developer picks a random           ESMP recommends safest module     |
  |   class to migrate                   to migrate first (by wave)        |
  |        |                                    |                          |
  |   Developer manually reads           ESMP assembles rich context:      |
  |   all related classes                - dependency cone (10 hops)       |
  |        |                             - risk scores                     |
  |   Developer writes Vaadin 24         - business terms                  |
  |   code from scratch                  - callers/callees                 |
  |        |                             - Vaadin 7 patterns detected      |
  |   Developer hopes nothing                   |                          |
  |   broke                              AI writes Vaadin 24 code with     |
  |        |                             FULL understanding of context      |
  |   Developer manually tests                  |                          |
  |   everything                         ESMP re-indexes, validates,       |
  |                                      and confirms nothing broke        |
  |                                                                        |
  |   Result: Slow, risky,              Result: Fast, safe,               |
  |   error-prone                        data-driven                       |
  +=======================================================================+
```

The workflow is a loop:

```
  +---> SCHEDULE (which module next?)
  |            |
  |            v
  |     RETRIEVE CONTEXT (RAG for each class)
  |            |
  |            v
  |     AI MIGRATES (with full dependency + risk context)
  |            |
  |            v
  |     RE-INDEX (update graph with new code)
  |            |
  |            v
  |     VALIDATE (run 41 checks — anything broken?)
  |            |
  |     NO ----+----> YES (fix before continuing)
  |            |
  +--- NEXT CLASS / MODULE
```

---

### Phase 1: Preparation — Analyze, Index, Schedule

Before any migration, run the full analysis pipeline. This only needs to be done once (incremental updates keep it fresh afterward).

#### Step 1.1: Extract your codebase

```bash
# Point ESMP at your legacy Vaadin 7 project
curl -X POST "http://localhost:8080/api/extraction/trigger" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/path/to/legacy-app/src/main/java",
    "classpathFile": "/path/to/legacy-app/classpath.txt"
  }'
```

**What happens behind the scenes:**

```
  Your 500+ Java files
         |
         v
  7 AST visitors extract:
  - 8 node types (classes, methods, fields, annotations, ...)
  - 9 relationship types (CALLS, EXTENDS, DEPENDS_ON, ...)
  - Business terms from naming conventions
  - Cyclomatic complexity per method
  - Vaadin 7 patterns (views, components, data bindings)
  - JPA queries and table mappings
         |
         v
  Neo4j graph: thousands of nodes and edges
  representing your ENTIRE codebase structure
```

#### Step 1.2: Build vector embeddings

```bash
curl -X POST "http://localhost:8080/api/vector/index?sourceRoot=/path/to/legacy-app/src/main/java"
```

This creates searchable embeddings for every class and method, enriched with graph context (callers, dependencies, risk scores, business terms).

#### Step 1.3: Get your migration schedule

```bash
curl "http://localhost:8080/api/scheduling/recommend?sourceRoot=/path/to/legacy-app" | python -m json.tool
```

Or open **http://localhost:8080/schedule** and click **Generate Schedule**.

You now have a wave-ordered migration plan:

```
  YOUR MIGRATION ROADMAP
  ======================

  Wave 1 (Safest - Migrate First)     Wave 2 (Moderate Risk)
  +--------+ +--------+ +--------+    +--------+ +--------+
  | utils  | | config | | common |    | service| | report |
  | 0.120  | | 0.185  | | 0.210  |    | 0.445  | | 0.380  |
  +--------+ +--------+ +--------+    +--------+ +--------+

  Wave 3 (Higher Risk)                 Wave 4 (Circular Deps)
  +--------+ +--------+               +--------+ +--------+
  |  ui    | |billing |               |  auth  | |payment |
  | 0.620  | | 0.780  |               | 0.850  | | 0.830  |
  +--------+ +--------+               +--------+ +--------+

  Start from Wave 1, left to right.
  Each module's dependencies are guaranteed
  to be in earlier waves.
```

#### Step 1.4: Validate pilot module readiness

```bash
# Pick the first module from Wave 1
curl "http://localhost:8080/api/pilot/validate/utils"
```

Confirm all 5 checks pass before starting migration.

---

### Phase 2: Pilot Migration — One Module End-to-End

Now you migrate your first module using AI. This establishes the pattern you'll repeat for every module.

#### Step 2.1: List all classes in the module

```bash
# Get all classes in the target module
curl "http://localhost:8080/api/graph/search?name=utils" | python -m json.tool
```

Or use the Dashboard's dependency explorer to drill into the module.

#### Step 2.2: For each class — Get AI migration context

This is the key step. For every class you want to migrate, ask ESMP to assemble the full context an AI needs:

```bash
# Get rich migration context for a specific class
curl -X POST "http://localhost:8080/api/rag/context" \
  -H "Content-Type: application/json" \
  -d '{
    "fqn": "com.acme.utils.DateFormatter",
    "limit": 25,
    "includeFullSource": true
  }'
```

**What the AI receives (not just the class — everything it needs):**

```
  RAG Context Package for: DateFormatter
  =======================================

  FOCAL CLASS
  -----------
  - Full source code of DateFormatter
  - Risk score: 0.12 (low - safe to migrate)
  - Vaadin 7 detected: yes (uses com.vaadin.ui.Label)
  - Business terms: ["Date", "Format"]

  25 CONTEXT CHUNKS (ranked by relevance)
  ----------------------------------------
  Each chunk includes:

  1. DateUtils.formatEuropean()        score: 0.92
     - Called BY DateFormatter          (graph proximity: 1 hop)
     - Also Vaadin 7                    (risk: 0.08)
     - Code: public String formatEuropean(Date d) { ... }

  2. DateConverter.convertToModel()     score: 0.87
     - Implements Converter<String,Date> (Vaadin 7 interface!)
     - Called by DateFormatter           (graph proximity: 1 hop)
     - Code: @Override public Date convertToModel(...) { ... }

  3. OrderForm.buildDateField()         score: 0.81
     - USES DateFormatter               (graph proximity: 2 hops)
     - Vaadin 7 Form with DateField     (needs migration)
     - Code: DateField df = new DateField(); ...

  ... 22 more chunks, each with:
      - Source code
      - Relationship to focal class
      - Risk score
      - Vaadin 7 detection
      - Business terms

  CONE SUMMARY
  ------------
  - 18 total reachable nodes
  - 4 contain Vaadin 7 code
  - Average risk: 0.15 (low)
  - Key business terms: Date, Format, Order, Invoice
```

#### Step 2.3: Feed context to AI and migrate

Now you take the RAG context and feed it to your AI tool (Claude, GPT, Copilot, etc.). Here's how:

**Option A: Manual prompt (copy-paste)**

```
You are migrating a Java/Vaadin 7 application to Vaadin 24.

Here is the class to migrate:
[paste focal class source code]

Here is the full dependency context from our code knowledge graph:
[paste the RAG response JSON or formatted context]

Key information:
- This class has risk score 0.12 (low risk)
- It uses Vaadin 7 DateField which maps to Vaadin 24 DatePicker
- It has 4 callers that will also need updating
- Business terms involved: Date, Format

Please:
1. Rewrite this class using Vaadin 24 APIs
2. Preserve all business logic exactly
3. Note any callers that will need updating
4. Flag any Vaadin 7 patterns that have no direct Vaadin 24 equivalent
```

**Option B: Scripted pipeline (recommended for scale)**

```bash
#!/bin/bash
# migrate-class.sh — Migrate a single class with AI context

FQN="$1"
SOURCE_ROOT="/path/to/legacy-app/src/main/java"

# 1. Get full RAG context from ESMP
CONTEXT=$(curl -s -X POST "http://localhost:8080/api/rag/context" \
  -H "Content-Type: application/json" \
  -d "{\"fqn\": \"$FQN\", \"limit\": 25, \"includeFullSource\": true}")

# 2. Get risk detail
RISK=$(curl -s "http://localhost:8080/api/risk/class/$FQN")

# 3. Get inheritance chain
INHERITANCE=$(curl -s "http://localhost:8080/api/graph/class/$FQN/inheritance")

# 4. Combine into AI prompt
cat <<PROMPT
Migrate this Vaadin 7 class to Vaadin 24.

## Class: $FQN

## Risk Assessment:
$RISK

## Inheritance Chain:
$INHERITANCE

## Full Dependency Context (from code knowledge graph):
$CONTEXT

## Instructions:
1. Rewrite using Vaadin 24 Flow APIs
2. Replace deprecated Vaadin 7 patterns:
   - com.vaadin.ui.* -> com.vaadin.flow.component.*
   - Navigator -> @Route annotations
   - BeanItemContainer -> DataProvider
   - Property/Item -> Binder
3. Preserve all business logic
4. List all classes that call this one (they may need updates)
PROMPT
```

```bash
# Usage:
./migrate-class.sh com.acme.utils.DateFormatter | claude --prompt -
# or pipe to any AI CLI tool
```

**Option C: Claude Code integration (most powerful)**

If you're using Claude Code (this CLI tool), you can build a migration workflow:

```bash
# In your project directory, create a migration helper script:

# 1. Get the schedule
curl -s "http://localhost:8080/api/scheduling/recommend" > /tmp/schedule.json

# 2. For each module in wave order, for each class:
#    Ask Claude Code to migrate with full ESMP context

# Example Claude Code prompt:
# "Migrate com.acme.utils.DateFormatter from Vaadin 7 to Vaadin 24.
#  Use the ESMP RAG API at localhost:8080 to get full context first:
#  POST /api/rag/context with fqn=com.acme.utils.DateFormatter"
```

#### Step 2.4: After migration — Re-index and validate

After you've written the new Vaadin 24 code for a class:

```bash
# Tell ESMP about the changed files
curl -X POST "http://localhost:8080/api/indexing/incremental" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/path/to/legacy-app/src/main/java",
    "changedFiles": [
      "com/acme/utils/DateFormatter.java"
    ]
  }'

# Run validation to check nothing broke
curl "http://localhost:8080/api/graph/validation" | python -m json.tool
```

```
  After Re-indexing DateFormatter
  ===============================

  BEFORE                          AFTER
  ------                          -----
  Vaadin 7 views: 28              Vaadin 7 views: 27  (-1!)
  Risk score: 0.12                Risk score: 0.05    (improved!)
  Validation: 41/41 pass          Validation: 41/41 pass (still green!)

  The graph automatically updated:
  - DateFormatter node now has Vaadin 24 patterns
  - Risk scores recomputed
  - Vector embeddings refreshed
  - Dependent classes' context updated
```

---

### Phase 3: Wave Execution — Systematic Rollout

After the pilot module succeeds, scale up to the full migration following the wave schedule.

```
  WAVE-BY-WAVE MIGRATION PROCESS
  ================================

  For each wave (1, 2, 3, ...):
  |
  +---> For each module in this wave (sorted by score):
  |     |
  |     +---> For each class in this module:
  |     |     |
  |     |     1. GET RAG context:
  |     |        POST /api/rag/context {fqn: "...", limit: 25}
  |     |     |
  |     |     2. AI migrates with context
  |     |     |
  |     |     3. Re-index changed file:
  |     |        POST /api/indexing/incremental
  |     |     |
  |     |     4. Spot-check validation:
  |     |        GET /api/graph/validation
  |     |
  |     +---> Module complete!
  |           Validate module:
  |           GET /api/pilot/validate/{module}
  |
  +---> Wave complete!
        Full validation + update schedule:
        GET /api/graph/validation
        GET /api/scheduling/recommend
        (risk scores shift as you migrate)
```

**Key insight:** As you migrate modules in earlier waves, the risk scores for later waves **automatically update** because:
- Dependency counts change (migrated modules are "safe" now)
- Vaadin 7 detection counts drop
- The knowledge graph reflects the new code structure

```
  RISK EVOLUTION OVER TIME
  ========================

  Wave 1 migrated:
  billing risk: 0.780 --> 0.720  (its dependency 'utils' is now safe)

  Wave 2 migrated:
  billing risk: 0.720 --> 0.650  ('service' dependency now safe too)

  Wave 3 migrated:
  billing risk: 0.650 --> 0.580  (getting safer to migrate!)
```

#### Handling class-by-class migration within a module

For each module, here's the recommended class migration order:

```bash
# 1. Get all classes in the module sorted by risk (lowest first)
curl "http://localhost:8080/api/risk/heatmap?module=utils&sortBy=enhanced&limit=100"

# 2. Start with the lowest-risk class
# 3. For each class:

# Get migration context
curl -X POST "http://localhost:8080/api/rag/context" \
  -H "Content-Type: application/json" \
  -d '{"fqn": "com.acme.utils.StringHelper", "limit": 20}'

# Feed to AI, get migrated code, write it

# Re-index
curl -X POST "http://localhost:8080/api/indexing/incremental" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/path/to/src/main/java",
    "changedFiles": ["com/acme/utils/StringHelper.java"]
  }'

# Quick validation check
curl "http://localhost:8080/api/graph/validation" | \
  python -c "import sys,json; r=json.load(sys.stdin); print(f'Pass: {r[\"passCount\"]}, Warn: {r[\"warnCount\"]}, Error: {r[\"errorCount\"]}')"
```

---

### Phase 4: Continuous Validation — Keep Everything Green

Throughout the migration, ESMP acts as your safety net:

```
  CONTINUOUS SAFETY NET
  =====================

  +----------------------------------------------------------+
  |                    41 Validation Queries                   |
  |                                                           |
  |  STRUCTURAL    |  Are all edges still valid?              |
  |  INTEGRITY     |  Any orphan nodes created?               |
  |  (10 queries)  |  Any dangling references?                |
  |                                                           |
  |  ARCHITECTURE  |  Services still depend on repositories?  |
  |  PATTERNS      |  Views still bind to models?             |
  |  (10 queries)  |  Inheritance chains intact?              |
  |                                                           |
  |  DOMAIN        |  Business terms still connected?         |
  |  (3 queries)   |  Business rules still defined?           |
  |                                                           |
  |  RISK          |  Risk scores still computed?             |
  |  (6 queries)   |  No high-risk orphans?                   |
  |                                                           |
  |  VECTOR        |  Embeddings in sync with code?           |
  |  (3 queries)   |  Content hashes current?                 |
  |                                                           |
  |  RAG           |  Context retrieval working?              |
  |  (3 queries)   |  Vector index aligned with graph?        |
  |                                                           |
  |  SCHEDULING    |  Module structure intact?                |
  |  (3 queries)   |  Dependency edges still valid?           |
  |                                                           |
  |  PILOT         |  Module metrics up to date?              |
  |  (3 queries)   |                                          |
  +----------------------------------------------------------+
         |
         |  Run after every module migration:
         |  curl http://localhost:8080/api/graph/validation
         |
         v
  +----------------------------------------------------------+
  |  ALL PASS?                                                |
  |                                                           |
  |  YES --> Continue to next module                          |
  |                                                           |
  |  NO  --> STOP. Fix the issue before continuing.           |
  |          The validation details tell you exactly           |
  |          what broke and which classes are involved.        |
  +----------------------------------------------------------+
```

#### Monitoring migration progress

Track your migration progress through the dashboard:

```
  DASHBOARD SHOWS REAL-TIME MIGRATION STATUS
  ===========================================

  +-------------------+-------------------+-------------------+
  |   Total Classes   | Vaadin 7 Remaining|  Validation Health|
  |       342         |    28 --> 15 --> 0 |     41/41 pass   |
  +-------------------+-------------------+-------------------+

  Risk heatmap shows modules getting "greener" over time:

  Before migration:     After Wave 1:       After Wave 2:
  +------+-------+     +------+-------+    +------+-------+
  |Module| Risk  |     |Module| Risk  |    |Module| Risk  |
  |------|-------|     |------|-------|    |------|-------|
  |utils | 0.12  |     |utils | 0.02  |    |utils | 0.02  |
  |config| 0.18  |     |config| 0.05  |    |config| 0.05  |
  |svc   | 0.45  |     |svc   | 0.38  |    |svc   | 0.12  |
  |bill  | 0.78  |     |bill  | 0.72  |    |bill  | 0.58  |
  +------+-------+     +------+-------+    +------+-------+
   (mostly red)         (getting better)    (mostly green!)
```

---

### Prompt Engineering for Migration

Here are battle-tested prompts for different migration scenarios:

#### Simple class migration (no Vaadin UI)

```
I'm migrating a Java application. Here is a service class and its full
dependency context from our code knowledge graph (ESMP RAG output):

[paste RAG context JSON]

This class has:
- Risk score: {enhancedRiskScore} ({low/moderate/high})
- {dependentCount} classes depend on it
- Business terms: {topDomainTerms}

Rewrite this class for modern Spring Boot 3.x / Java 21:
1. Keep all business logic identical
2. Use modern Java features where appropriate (records, pattern matching)
3. List any API changes that callers will need to adapt to
```

#### Vaadin 7 View migration

```
I'm migrating a Vaadin 7 view to Vaadin 24 Flow. Here is the class and
its full dependency context from ESMP:

[paste RAG context JSON]

Key Vaadin 7 to 24 mappings:
- com.vaadin.ui.VerticalLayout -> com.vaadin.flow.component.orderedlayout.VerticalLayout
- com.vaadin.ui.Button -> com.vaadin.flow.component.button.Button
- com.vaadin.ui.Grid (v7) -> com.vaadin.flow.component.grid.Grid (v24)
- com.vaadin.navigator.View -> @Route annotation
- BeanItemContainer -> ListDataProvider or DataProvider
- Property.ValueChangeListener -> HasValue.ValueChangeListener
- UI.getCurrent().getNavigator().navigateTo() -> UI.getCurrent().navigate()

The context shows these related classes that bind to this view:
[list classes with BINDS_TO relationships from context]

Please:
1. Rewrite as Vaadin 24 Flow view with @Route
2. Convert all Vaadin 7 components to their Flow equivalents
3. Replace Property/Item model with Binder
4. Show which data binding patterns changed
5. Flag any Vaadin 7 features with no direct equivalent
```

#### Complex class with circular dependencies

```
I'm migrating a class that's part of a circular dependency cycle.
ESMP has placed it in the final migration wave because of this cycle.

Here is the class and its context:
[paste RAG context JSON]

The circular dependency involves these classes:
- {class A} depends on {class B}
- {class B} depends on {class A}

Strategy: We need to break this cycle as part of the migration.
Please:
1. Identify the interface boundary that should break the cycle
2. Extract the shared contract into an interface
3. Rewrite both classes to depend on the interface, not each other
4. Migrate to Vaadin 24 at the same time
```

---

### Automation Scripts

#### Full module migration script

```bash
#!/bin/bash
# migrate-module.sh — Migrate an entire module class by class
#
# Usage: ./migrate-module.sh <module-name> <source-root>
#
# Prerequisites: ESMP running at localhost:8080, jq installed

MODULE="$1"
SOURCE_ROOT="$2"
ESMP="http://localhost:8080"

echo "========================================="
echo " ESMP Module Migration: $MODULE"
echo "========================================="

# 1. Validate module readiness
echo ""
echo "[Step 1] Validating module readiness..."
VALIDATION=$(curl -s "$ESMP/api/pilot/validate/$MODULE")
CHECKS_FAILED=$(echo "$VALIDATION" | jq '[.pilotChecks[] | select(.status == "FAIL")] | length')

if [ "$CHECKS_FAILED" -gt 0 ]; then
  echo "FAILED: $CHECKS_FAILED pilot checks failed. Fix before migrating."
  echo "$VALIDATION" | jq '.pilotChecks[] | select(.status == "FAIL")'
  exit 1
fi
echo "All pilot checks passed."

# 2. Get classes sorted by risk (safest first)
echo ""
echo "[Step 2] Getting classes sorted by risk (lowest first)..."
CLASSES=$(curl -s "$ESMP/api/risk/heatmap?module=$MODULE&limit=500&sortBy=enhanced" \
  | jq -r '.[].fqn')
CLASS_COUNT=$(echo "$CLASSES" | wc -l)
echo "Found $CLASS_COUNT classes to migrate."

# 3. Migrate each class
COUNTER=0
echo "$CLASSES" | while read -r FQN; do
  COUNTER=$((COUNTER + 1))
  echo ""
  echo "========================================="
  echo " [$COUNTER/$CLASS_COUNT] $FQN"
  echo "========================================="

  # Get RAG context
  echo "  Fetching RAG context..."
  CONTEXT=$(curl -s -X POST "$ESMP/api/rag/context" \
    -H "Content-Type: application/json" \
    -d "{\"fqn\": \"$FQN\", \"limit\": 20, \"includeFullSource\": true}")

  # Save context to temp file for AI consumption
  echo "$CONTEXT" > "/tmp/esmp-context-$COUNTER.json"
  echo "  Context saved to /tmp/esmp-context-$COUNTER.json"
  echo "  Cone size: $(echo "$CONTEXT" | jq '.coneSummary.totalNodes') nodes"
  echo "  Vaadin 7: $(echo "$CONTEXT" | jq '.focalClass.vaadin7Detected')"
  echo "  Risk: $(echo "$CONTEXT" | jq '.focalClass.enhancedRiskScore')"

  # >>> YOUR AI MIGRATION STEP HERE <<<
  # Feed /tmp/esmp-context-$COUNTER.json to your AI tool
  # Write the migrated code back to the source file
  echo ""
  echo "  >>> Migrate this class now. Press Enter when done..."
  read -r

  # Re-index the changed file
  FILE_PATH=$(echo "$FQN" | tr '.' '/')".java"
  echo "  Re-indexing $FILE_PATH..."
  curl -s -X POST "$ESMP/api/indexing/incremental" \
    -H "Content-Type: application/json" \
    -d "{\"sourceRoot\": \"$SOURCE_ROOT\", \"changedFiles\": [\"$FILE_PATH\"]}" > /dev/null

  # Quick validation
  VAL=$(curl -s "$ESMP/api/graph/validation")
  ERRORS=$(echo "$VAL" | jq '.errorCount')
  if [ "$ERRORS" -gt 0 ]; then
    echo "  WARNING: $ERRORS validation errors detected!"
    echo "$VAL" | jq '.results[] | select(.status == "FAIL") | {name, count, details}'
    echo "  Fix before continuing. Press Enter to proceed anyway..."
    read -r
  else
    echo "  Validation: ALL PASS"
  fi
done

echo ""
echo "========================================="
echo " Module $MODULE migration complete!"
echo "========================================="
echo ""
echo "Run full validation: curl $ESMP/api/graph/validation"
echo "Check updated schedule: curl $ESMP/api/scheduling/recommend"
```

#### Migration progress tracker

```bash
#!/bin/bash
# migration-status.sh — Track overall migration progress
#
# Usage: ./migration-status.sh

ESMP="http://localhost:8080"

echo "========================================="
echo " ESMP Migration Progress"
echo "========================================="

# Get schedule for wave info
SCHEDULE=$(curl -s "$ESMP/api/scheduling/recommend")
TOTAL_MODULES=$(echo "$SCHEDULE" | jq '.flatRanking | length')
TOTAL_WAVES=$(echo "$SCHEDULE" | jq '.waves | length')

# Get risk heatmap for Vaadin 7 counts
HEATMAP=$(curl -s "$ESMP/api/risk/heatmap?limit=1000")
TOTAL_CLASSES=$(echo "$HEATMAP" | jq 'length')

# Get validation health
VALIDATION=$(curl -s "$ESMP/api/graph/validation")
PASS=$(echo "$VALIDATION" | jq '.passCount')
WARN=$(echo "$VALIDATION" | jq '.warnCount')
ERROR=$(echo "$VALIDATION" | jq '.errorCount')

echo ""
echo "Modules: $TOTAL_MODULES across $TOTAL_WAVES waves"
echo "Classes: $TOTAL_CLASSES total"
echo "Validation: $PASS pass, $WARN warn, $ERROR errors"
echo ""
echo "Risk Distribution:"
echo "  Low  (< 0.3): $(echo "$HEATMAP" | jq '[.[] | select(.enhancedRiskScore < 0.3)] | length') classes"
echo "  Med  (0.3-0.6): $(echo "$HEATMAP" | jq '[.[] | select(.enhancedRiskScore >= 0.3 and .enhancedRiskScore < 0.6)] | length') classes"
echo "  High (>= 0.6): $(echo "$HEATMAP" | jq '[.[] | select(.enhancedRiskScore >= 0.6)] | length') classes"
echo ""
echo "Wave Schedule:"
echo "$SCHEDULE" | jq -r '.waves[] | "  Wave \(.waveNumber): \(.modules | length) modules — \([.modules[].module] | join(", "))"'
```

---

### End-to-End Example: Migrating a Real Class

Here's a concrete example of migrating `OrderFormView` (a Vaadin 7 view) using ESMP:

```
  STEP 1: Check the schedule
  ===========================
  $ curl -s localhost:8080/api/scheduling/recommend | jq '.flatRanking[] | select(.module == "order")'

  {
    "module": "order",
    "waveNumber": 2,
    "finalScore": 0.445,
    "rationale": "Avg risk 0.42 (moderate), 3 dependents, 28 commits/6mo, avg CC 8.2 — moderate priority"
  }

  Wave 2 = safe to start after Wave 1 is done. OK.


  STEP 2: Get RAG context for OrderFormView
  ==========================================
  $ curl -s -X POST localhost:8080/api/rag/context \
    -d '{"fqn": "com.acme.order.OrderFormView", "limit": 20}' | jq '.coneSummary'

  {
    "totalNodes": 32,
    "vaadin7Count": 5,           <-- 5 Vaadin 7 classes in the dependency cone
    "avgEnhancedRisk": 0.38,
    "topDomainTerms": ["Order", "Customer", "Product", "Price"]
  }

  Context includes 20 ranked chunks:
  - OrderService.createOrder()           (1 hop, score 0.94)
  - OrderValidator.validate()            (1 hop, score 0.91)
  - CustomerSelector (Vaadin 7 combo)    (2 hops, score 0.85)
  - ProductGrid (Vaadin 7 grid)          (2 hops, score 0.82)
  - PriceCalculator.calculate()          (3 hops, score 0.78)
  ...


  STEP 3: AI migrates with full context
  ======================================
  AI sees:
  - OrderFormView extends com.vaadin.navigator.View (Vaadin 7!)
  - Uses BeanFieldGroup<Order> for data binding
  - Calls OrderService, OrderValidator
  - Contains CustomerSelector and ProductGrid components
  - Business terms: Order, Customer, Product, Price
  - Risk: 0.42 (moderate)

  AI produces:
  - @Route("order-form") public class OrderFormView extends VerticalLayout
  - Binder<Order> replaces BeanFieldGroup
  - Grid<Product> replaces old ProductGrid
  - ComboBox<Customer> replaces old CustomerSelector
  - All business logic preserved exactly


  STEP 4: Re-index and validate
  ==============================
  $ curl -s -X POST localhost:8080/api/indexing/incremental \
    -d '{"sourceRoot": "/path/to/src", "changedFiles": ["com/acme/order/OrderFormView.java"]}'

  {"classesExtracted": 1, "chunksReEmbedded": 4, "errors": []}

  $ curl -s localhost:8080/api/graph/validation | jq '{passCount, warnCount, errorCount}'

  {"passCount": 41, "warnCount": 0, "errorCount": 0}

  All green! Move to the next class.
```

---

## v2: Autonomous AI Orchestration Engine (Roadmap)

> **Coming next.** v2 transforms ESMP from a tool you use *with* AI into a tool that *is* the AI migration engine. Instead of you copying context to Claude and pasting code back, ESMP itself orchestrates the entire migration pipeline end-to-end — from code analysis to validated pull request.

### What Changes from v1 to v2

```
  +=====================================================================+
  |                      v1 vs v2 COMPARISON                             |
  +=====================================================================+
  |                                                                      |
  |   v1 (CURRENT)                      v2 (ROADMAP)                    |
  |   ============                      ============                    |
  |                                                                      |
  |   You call ESMP APIs               ESMP orchestrates everything     |
  |        |                                   |                         |
  |   You copy RAG context             ESMP builds prompts internally   |
  |   to Claude/GPT                    and calls Claude API directly    |
  |        |                                   |                         |
  |   You review AI output             Guardrails auto-validate output  |
  |   manually                         (contracts, security, rules)     |
  |        |                                   |                         |
  |   You paste migrated code          ESMP writes code to files        |
  |   into files                       automatically                    |
  |        |                                   |                         |
  |   You run re-index +               ESMP re-indexes, validates,      |
  |   validation manually              runs behavioral diffs            |
  |        |                                   |                         |
  |   You create PRs                   ESMP opens PRs with confidence   |
  |   yourself                         scores and diff reports          |
  |                                                                      |
  |   HUMAN-IN-THE-LOOP               HUMAN-ON-THE-LOOP                |
  |   (you drive)                      (you approve)                    |
  +=====================================================================+
```

```
  THE v2 PROMISE
  ==============

  Developer says: "Migrate the billing module"

  ESMP:
  1. Gets migration schedule (billing is Wave 3)
  2. For each class in billing (sorted by risk):
     a. Retrieves full RAG context (dependency cone + vectors)
     b. Runs OpenRewrite deterministic transforms first
     c. Builds a rich prompt with context + Vaadin 7→24 mappings
     d. Calls Claude API with the prompt
     e. Validates output:
        - Does it compile?
        - Do tests pass?
        - Did any service contracts change? (BLOCKED if yes)
        - Did any security annotations get removed? (BLOCKED if yes)
        - Did any validation rules disappear? (BLOCKED if yes)
     f. Captures behavioral diffs (service outputs, SQL, validations)
     g. Computes confidence score (0-100)
     h. Writes migrated code to file
     i. Re-indexes into knowledge graph
  3. Opens a PR with:
     - All migrated files
     - Confidence score per class
     - Behavioral diff report
     - Guardrail check results
     - Classes that need human review (low confidence)

  Developer reviews ONE PR instead of migrating 45 classes manually.
```

---

### Architecture: The Orchestration Loop

```
  +------------------------------------------------------------------+
  |                   v2 ORCHESTRATION ENGINE                         |
  +------------------------------------------------------------------+
  |                                                                    |
  |  +-----------+     +------------+     +-----------+               |
  |  | Schedule  |---->| OpenRewrite|---->|    RAG    |               |
  |  | (Wave +   |     | (Determin- |     | (Context  |               |
  |  |  class    |     |  istic     |     |  assembly |               |
  |  |  order)   |     |  transforms|     |  25 chunks|               |
  |  +-----------+     |  first)    |     |  + risk)  |               |
  |                    +------------+     +-----------+               |
  |                                              |                     |
  |                                              v                     |
  |                                    +------------------+            |
  |                                    | Prompt Builder   |            |
  |                                    | (class + context |            |
  |                                    |  + Vaadin maps   |            |
  |                                    |  + guardrail     |            |
  |                                    |  instructions)   |            |
  |                                    +------------------+            |
  |                                              |                     |
  |                                              v                     |
  |                                    +------------------+            |
  |                                    |  Claude API      |            |
  |                                    |  (Anthropic SDK) |            |
  |                                    +------------------+            |
  |                                              |                     |
  |                                              v                     |
  |  +------------+    +-------------+   +----------------+            |
  |  | Behavioral |<---| Guardrails  |<--| Output         |            |
  |  | Diff       |    | (Contract,  |   | Validator      |            |
  |  | (pre/post  |    |  Security,  |   | (Compile +     |            |
  |  |  compare)  |    |  Validation)|   |  Test + Parse) |            |
  |  +------------+    +-------------+   +----------------+            |
  |        |                  |                  |                      |
  |        v                  v                  v                      |
  |  +--------------------------------------------------+             |
  |  |              Confidence Scorer                     |             |
  |  |  compilation: PASS (25pts)                        |             |
  |  |  tests: PASS (25pts)                              |             |
  |  |  guardrails: PASS (25pts)                         |             |
  |  |  behavioral diff: MINOR (20pts)                   |             |
  |  |  TOTAL: 95/100 — AUTO-APPROVE                     |             |
  |  +--------------------------------------------------+             |
  |        |                                                           |
  |        v                                                           |
  |  +--------------------------------------------------+             |
  |  |              PR Generator                          |             |
  |  |  - Migrated files                                 |             |
  |  |  - Confidence per class                           |             |
  |  |  - Behavioral diff report                         |             |
  |  |  - Guardrail results                              |             |
  |  |  - Human review flags                             |             |
  |  +--------------------------------------------------+             |
  +------------------------------------------------------------------+
```

---

### ORCH-01: AI Orchestration Engine

The core engine that ties everything together. It replaces the manual "copy context → paste to AI → paste code back" loop with a single automated pipeline.

```
  ORCHESTRATION ENGINE PIPELINE
  =============================

  Input:  Module name + source root
  Output: Migrated code + PR + confidence report

  For each class (ordered by risk, lowest first):

  ┌─────────────────────────────────────────────────┐
  │ 1. DETERMINISTIC TRANSFORMS (OpenRewrite)       │
  │    - Import rewrites (com.vaadin.ui → flow)     │
  │    - Annotation migrations (@Route)             │
  │    - Known API renames (mechanical changes)     │
  │    These are SAFE — no AI needed.               │
  └──────────────────────┬──────────────────────────┘
                         │
  ┌──────────────────────▼──────────────────────────┐
  │ 2. RAG CONTEXT RETRIEVAL                        │
  │    - Dependency cone (10 hops, all 9 edge types)│
  │    - Vector search within cone (top 25 chunks)  │
  │    - Risk scores + business terms               │
  │    - Vaadin 7 pattern detection flags           │
  │    - Callers/callees for impact analysis        │
  └──────────────────────┬──────────────────────────┘
                         │
  ┌──────────────────────▼──────────────────────────┐
  │ 3. PROMPT CONSTRUCTION                          │
  │    - Class source code                          │
  │    - RAG context (25 ranked chunks)             │
  │    - Vaadin 7 → 24 API mapping table            │
  │    - Guardrail instructions:                    │
  │      "Do NOT change method signatures"          │
  │      "Do NOT remove @Secured annotations"       │
  │      "Do NOT remove Bean Validation rules"      │
  │    - Business term context from lexicon         │
  └──────────────────────┬──────────────────────────┘
                         │
  ┌──────────────────────▼──────────────────────────┐
  │ 4. CLAUDE API CALL                              │
  │    - Anthropic SDK (Java)                       │
  │    - Model: Claude Sonnet/Opus                  │
  │    - Returns: migrated Java source code         │
  └──────────────────────┬──────────────────────────┘
                         │
  ┌──────────────────────▼──────────────────────────┐
  │ 5. OUTPUT VALIDATION                            │
  │    - Parse the returned code (valid Java?)      │
  │    - Compile check (javac)                      │
  │    - Run existing tests                         │
  │    - Guardrail enforcement (see ORCH-03)        │
  │    - Behavioral diff (see DIFF-01/02/03)        │
  └──────────────────────┬──────────────────────────┘
                         │
  ┌──────────────────────▼──────────────────────────┐
  │ 6. CONFIDENCE SCORING (see ORCH-04)             │
  │    Score ≥ 90 → auto-commit                     │
  │    Score 70-89 → commit with review flag        │
  │    Score < 70 → skip, flag for human migration  │
  └─────────────────────────────────────────────────┘
```

**How you'll use it (v2 API — planned):**

```bash
# Migrate an entire module autonomously
curl -X POST "http://localhost:8080/api/orchestration/migrate" \
  -H "Content-Type: application/json" \
  -d '{
    "module": "billing",
    "sourceRoot": "/path/to/src/main/java",
    "dryRun": false,
    "confidenceThreshold": 80,
    "createPR": true
  }'

# Check migration status
curl "http://localhost:8080/api/orchestration/status/billing"

# Get confidence report
curl "http://localhost:8080/api/orchestration/report/billing"
```

---

### ORCH-02: Automated Pull Request Generation

After the orchestration engine migrates a module, it automatically creates a GitHub PR with a structured report.

```
  GENERATED PR STRUCTURE
  ======================

  PR Title: "feat: migrate billing module (Wave 3) — 42/45 classes, avg confidence 91%"

  PR Body:
  ┌────────────────────────────────────────────────────────────┐
  │ ## Migration Report: billing                               │
  │                                                            │
  │ **Module:** billing (Wave 3 of 4)                          │
  │ **Classes Migrated:** 42/45                                │
  │ **Average Confidence:** 91%                                │
  │ **Duration:** 4m 32s                                       │
  │                                                            │
  │ ### Confidence Breakdown                                   │
  │                                                            │
  │ | Class                    | Confidence | Status          | │
  │ |--------------------------|-----------|-----------------|  │
  │ | BillingConfig            |    98%    | Auto-approved   |  │
  │ | InvoiceRepository        |    96%    | Auto-approved   |  │
  │ | InvoiceService           |    92%    | Auto-approved   |  │
  │ | PaymentProcessor         |    85%    | Review needed   |  │
  │ | InvoiceFormView          |    78%    | Review needed   |  │
  │ | TaxCalculatorView        |    --     | Skipped (< 70)  |  │
  │ | RefundWizard             |    --     | Skipped (< 70)  |  │
  │ | BillingDashboard         |    --     | Skipped (< 70)  |  │
  │                                                            │
  │ ### Guardrail Results                                      │
  │                                                            │
  │ | Check                    | Status                       │
  │ |--------------------------|------------------------------|│
  │ | Service contracts        | PASS — no signatures changed │
  │ | Security annotations     | PASS — all @Secured kept     │
  │ | Validation rules         | PASS — all @Valid kept        │
  │ | Compilation              | PASS — all classes compile    │
  │ | Test suite               | PASS — 128/128 tests pass    │
  │                                                            │
  │ ### Behavioral Diff Summary                                │
  │                                                            │
  │ | Dimension          | Changes |                           │
  │ |--------------------|---------|                           │
  │ | Service outputs    | 0 diffs |                           │
  │ | SQL queries        | 2 minor | (ORDER BY added)          │
  │ | Validation         | 0 diffs |                           │
  │                                                            │
  │ ### Classes Needing Human Migration                        │
  │                                                            │
  │ These 3 classes scored below the confidence threshold:     │
  │ - `TaxCalculatorView` — complex custom Vaadin 7 widget    │
  │ - `RefundWizard` — multi-step wizard with no V24 equiv    │
  │ - `BillingDashboard` — heavy custom JS interop            │
  │                                                            │
  │ Use v1 manual migration for these:                         │
  │ `curl -X POST localhost:8080/api/rag/context -d {...}`     │
  └────────────────────────────────────────────────────────────┘
```

---

### ORCH-03: Deterministic Guardrails

Guardrails are non-negotiable rules that **automatically block** migrations that violate them. No AI confidence score can override a guardrail failure.

```
  GUARDRAIL ENFORCEMENT
  =====================

  ┌─────────────────────────────────────────────────────────┐
  │                  THREE GUARDRAILS                        │
  │                                                          │
  │  1. SERVICE CONTRACT PRESERVATION                        │
  │     ─────────────────────────────                        │
  │     Compare: method signatures before vs after           │
  │                                                          │
  │     BLOCKED if:                                          │
  │     - Public method signature changed                    │
  │     - Return type changed                                │
  │     - Parameter types changed                            │
  │     - Method removed                                     │
  │                                                          │
  │     WHY: Other modules depend on these contracts.        │
  │     Changing them silently breaks everything.            │
  │                                                          │
  │  ┌─────────────────────────────────────────────┐         │
  │  │  BEFORE                  AFTER              │         │
  │  │  List<Invoice>           List<Invoice>      │ PASS    │
  │  │    getInvoices(long)       getInvoices(long)│         │
  │  │                                             │         │
  │  │  Invoice                 Optional<Invoice>  │ BLOCKED │
  │  │    findById(long)          findById(long)   │ Return  │
  │  │                                  ^^^        │ type    │
  │  │                                  changed!   │ changed │
  │  └─────────────────────────────────────────────┘         │
  │                                                          │
  │  2. SECURITY ANNOTATION PRESERVATION                     │
  │     ───────────────────────────────                      │
  │     BLOCKED if ANY security annotation removed:          │
  │     @Secured, @RolesAllowed, @PreAuthorize,             │
  │     @PostAuthorize, @WithMockUser                        │
  │                                                          │
  │     WHY: Removing security = creating vulnerabilities.   │
  │     AI models sometimes "simplify" by removing these.    │
  │                                                          │
  │  3. VALIDATION RULE PRESERVATION                         │
  │     ─────────────────────────────                        │
  │     BLOCKED if ANY validation removed:                   │
  │     @Valid, @NotNull, @NotBlank, @Size, @Min, @Max,     │
  │     @Pattern, @Email, custom validators                  │
  │                                                          │
  │     WHY: Validation rules ARE business rules.            │
  │     Removing @NotNull on a payment amount = data         │
  │     corruption.                                          │
  └─────────────────────────────────────────────────────────┘

  Guardrail Failure Flow:
  =======================

  AI output fails guardrail
         |
         v
  Migration BLOCKED for this class
         |
         v
  Class added to "needs human review" list in PR
         |
         v
  Developer uses v1 manual migration for this class
  (with full RAG context still available)
```

---

### ORCH-04: AI Confidence Scoring

Every migrated class gets a composite confidence score (0-100) that determines whether it's auto-approved, flagged for review, or skipped.

```
  CONFIDENCE SCORING MODEL
  ========================

  ┌──────────────────────────────────────────────────┐
  │                                                    │
  │  DIMENSION              WEIGHT    SCORING          │
  │  ─────────              ──────    ───────          │
  │                                                    │
  │  Compilation            25 pts    PASS = 25        │
  │                                   FAIL = 0         │
  │                                                    │
  │  Test Suite             25 pts    All pass = 25    │
  │                                   Some fail = 0-15 │
  │                                   (proportional)   │
  │                                                    │
  │  Guardrails             25 pts    All pass = 25    │
  │                                   Any fail = 0     │
  │                                   (binary)         │
  │                                                    │
  │  Behavioral Diff        25 pts    No diffs = 25    │
  │                                   Minor = 15-20    │
  │                                   Major = 0-10     │
  │                                                    │
  │  TOTAL                  100 pts                    │
  │                                                    │
  ├──────────────────────────────────────────────────┤
  │                                                    │
  │  DECISION THRESHOLDS:                              │
  │                                                    │
  │  90-100  AUTO-APPROVE                              │
  │  ┌──────────────────────────────────────────┐      │
  │  │ Commit directly. No human review needed. │      │
  │  │ All checks passed. Behavior unchanged.   │      │
  │  └──────────────────────────────────────────┘      │
  │                                                    │
  │  70-89   REVIEW NEEDED                             │
  │  ┌──────────────────────────────────────────┐      │
  │  │ Commit but flag in PR for human review.  │      │
  │  │ Minor behavioral diffs or warnings.      │      │
  │  └──────────────────────────────────────────┘      │
  │                                                    │
  │  0-69    SKIP                                      │
  │  ┌──────────────────────────────────────────┐      │
  │  │ Do not commit. Add to "manual migration" │      │
  │  │ list. Use v1 workflow for this class.     │      │
  │  └──────────────────────────────────────────┘      │
  │                                                    │
  └──────────────────────────────────────────────────┘
```

**Example scoring:**

```
  InvoiceService.java
  ├── Compilation:     PASS     25/25
  ├── Tests:           PASS     25/25
  ├── Guardrails:      PASS     25/25
  └── Behavioral Diff: MINOR    20/25  (one SQL ORDER BY changed)
                                ──────
                       TOTAL:   95/100  → AUTO-APPROVE

  RefundWizard.java
  ├── Compilation:     PASS     25/25
  ├── Tests:           2 FAIL   15/25
  ├── Guardrails:      PASS     25/25
  └── Behavioral Diff: MAJOR     5/25  (validation behavior changed)
                                ──────
                       TOTAL:   70/100  → REVIEW NEEDED

  TaxCalculatorView.java
  ├── Compilation:     FAIL      0/25
  ├── Tests:           N/A       0/25
  ├── Guardrails:      N/A       0/25
  └── Behavioral Diff: N/A       0/25
                                ──────
                       TOTAL:    0/100  → SKIP (manual migration)
```

---

### Behavioral Diffing Framework

The diffing framework captures how the system **behaves** before and after migration, catching semantic bugs that compile fine but produce wrong results.

```
  BEHAVIORAL DIFFING PIPELINE
  ============================

  BEFORE MIGRATION              AFTER MIGRATION
  ================              ===============

  1. SERVICE OUTPUTS (DIFF-01)
  ┌─────────────────────┐      ┌─────────────────────┐
  │ Call each public     │      │ Call same methods    │
  │ method with test     │      │ with same inputs     │
  │ inputs               │      │                     │
  │                     │      │                     │
  │ Record: return      │      │ Record: return      │
  │ values, exceptions  │      │ values, exceptions  │
  └─────────┬───────────┘      └─────────┬───────────┘
            │                             │
            └──────────┬──────────────────┘
                       │
                  COMPARE
                       │
            ┌──────────▼──────────────────┐
            │ Identical?      → PASS      │
            │ Minor format?   → MINOR     │
            │ Different value?→ MAJOR     │
            └─────────────────────────────┘


  2. SQL QUERIES (DIFF-02)
  ┌─────────────────────┐      ┌─────────────────────┐
  │ Intercept all SQL    │      │ Intercept all SQL    │
  │ via datasource      │      │ via datasource      │
  │ proxy                │      │ proxy                │
  │                     │      │                     │
  │ Record: query text, │      │ Record: query text, │
  │ parameters, results │      │ parameters, results │
  └─────────┬───────────┘      └─────────┬───────────┘
            │                             │
            └──────────┬──────────────────┘
                       │
                  COMPARE
                       │
            ┌──────────▼──────────────────┐
            │ Same queries?   → PASS      │
            │ Reordered?      → MINOR     │
            │ Different WHERE?→ MAJOR     │
            │ Missing query?  → CRITICAL  │
            └─────────────────────────────┘


  3. VALIDATION BEHAVIOR (DIFF-03)
  ┌─────────────────────┐      ┌─────────────────────┐
  │ Submit valid +       │      │ Submit same inputs   │
  │ invalid inputs to   │      │ to migrated          │
  │ original validators │      │ validators           │
  │                     │      │                     │
  │ Record: which pass, │      │ Record: which pass, │
  │ which fail, errors  │      │ which fail, errors  │
  └─────────┬───────────┘      └─────────┬───────────┘
            │                             │
            └──────────┬──────────────────┘
                       │
                  COMPARE
                       │
            ┌──────────▼──────────────────┐
            │ Same pass/fail? → PASS      │
            │ Stricter?       → MINOR     │
            │ More lenient?   → CRITICAL  │
            │ (accepting previously       │
            │  invalid input = data bug)  │
            └─────────────────────────────┘
```

---

### Advanced Features

These enhance the core orchestration engine:

#### ADV-01: Multi-Layer RAG with Lexicon Scoring

The current RAG pipeline scores on 3 dimensions (vector similarity, graph proximity, risk). v2 adds a **4th dimension: lexicon match** — how many business terms does this chunk share with the focal class?

```
  v1 RAG Scoring:                   v2 RAG Scoring:
  finalScore =                      finalScore =
    vector   * 0.40                   vector    * 0.35
  + graph    * 0.35                 + graph     * 0.30
  + risk     * 0.25                 + risk      * 0.20
                                    + lexicon   * 0.15  ← NEW

  Lexicon score = shared business terms between
  focal class and context chunk / total terms
  in focal class.

  WHY: Code that talks about the same business
  concepts is more relevant for migration context
  than code that's just structurally close.
```

#### ADV-02: Natural Language Graph Queries

Ask questions about your codebase in plain English:

```bash
# v2 planned API
curl -X POST "http://localhost:8080/api/graph/ask" \
  -H "Content-Type: application/json" \
  -d '{"question": "Which services write to the invoices table?"}'

# Response: translates to Cypher, executes, returns results
{
  "cypher": "MATCH (s:JavaClass:Service)-[:DEPENDS_ON*]->(r)-[:MAPS_TO_TABLE]->(t:DBTable {name:'invoices'}) RETURN s",
  "results": [
    {"fqn": "com.acme.billing.InvoiceService", "risk": 0.78},
    {"fqn": "com.acme.billing.CreditNoteService", "risk": 0.45}
  ]
}
```

#### ADV-03: Trained Confidence Model

Replace the rule-based confidence scorer with a model trained on historical migration outcomes:

```
  TRAINING DATA
  =============

  For each past migration:
  - Input features: risk score, complexity, Vaadin 7 pattern count,
    dependency cone size, business term density, method count
  - Output: did the migration succeed without bugs?
    (tracked via post-migration bug reports)

  Over time, the model learns which class characteristics
  predict successful AI migration and which predict failure,
  giving more accurate confidence scores than static rules.
```

---

### What v2 Will NOT Do

These are explicitly out of scope to keep the system trustworthy:

| Feature | Reason |
|---------|--------|
| **Full automated rewrite without human validation** | AI hallucination risk too high for enterprise code. Humans always review PRs. |
| **Business rule inference without domain expert** | Critical rules require human SME curation via the Lexicon. |
| **Replacing CI/CD pipelines** | ESMP is a parallel intelligence layer, not a build system. |
| **Auto-merging PRs** | Even high-confidence PRs need human approval before merge. The engine *creates* PRs, humans *merge* them. |
| **Training on your proprietary code** | All AI calls use Claude's general model. Your code is sent via API but never used for training. |

---

### v2 Roadmap Status

| Requirement | Description | Status |
|-------------|-------------|--------|
| **ORCH-01** | AI orchestration engine (OpenRewrite → RAG → Claude → validate) | Planned |
| **ORCH-02** | Automated PR generation with confidence reports | Planned |
| **ORCH-03** | Deterministic guardrails (contracts, security, validation) | Planned |
| **ORCH-04** | AI confidence scoring (compilation + tests + guardrails + diff) | Planned |
| **DIFF-01** | Service output behavioral diffing | Planned |
| **DIFF-02** | SQL query behavioral diffing | Planned |
| **DIFF-03** | Validation behavior diffing | Planned |
| **ADV-01** | Multi-layer RAG with lexicon scoring | Planned |
| **ADV-02** | Natural language graph queries | Planned |
| **ADV-03** | Trained confidence scoring model | Planned |

**Foundation ready:** All v1 infrastructure (Phases 1-13) provides the building blocks — RAG pipeline, validation framework, risk scoring, graph API, incremental indexing, scheduling. v2 wires them together with Claude API integration and automated quality gates.

---

## License

See [LICENSE](LICENSE) file for details.
