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
- [REST API Reference](#rest-api-reference)
- [Vaadin Dashboard Views](#vaadin-dashboard-views)
- [Configuration Reference](#configuration-reference)
- [Running Tests](#running-tests)
- [Monitoring](#monitoring)
- [Troubleshooting](#troubleshooting)

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
| **Testing** | JUnit 5 + Testcontainers | Unit + integration tests |

---

## Quick Start

If you just want to get ESMP running as fast as possible:

```bash
# 1. Clone the repo
git clone https://github.com/arehmann/esmp.git
cd esmp

# 2. Start all databases
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

### Prerequisites

Before you begin, make sure you have:

| Requirement | Version | How to check |
|-------------|---------|-------------|
| **Java JDK** | 21+ | `java -version` |
| **Docker** | 20+ | `docker --version` |
| **Docker Compose** | 2.0+ | `docker compose version` |
| **Git** | 2.30+ | `git --version` |
| **Disk Space** | ~5 GB | For Docker images + data |

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
# Point ESMP at your legacy codebase
curl -X POST "http://localhost:8080/api/extraction/trigger" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/absolute/path/to/your/java/src/main/java",
    "classpathFile": "/path/to/classpath.txt"
  }'
```

> **What is classpathFile?** A text file with one JAR path per line. It helps ESMP detect Vaadin types accurately. If you don't have one, omit it - extraction still works but with reduced Vaadin detection accuracy.

**Generating a classpath file from Gradle:**

```bash
# In your legacy project directory:
./gradlew dependencies --configuration runtimeClasspath \
  | grep '\\---' | sed 's/.*--- //' | sort -u > classpath.txt
```

The extraction response tells you what was found:

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
# Trigger extraction
curl -X POST "http://localhost:8080/api/extraction/trigger" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceRoot": "/path/to/your/src/main/java",
    "classpathFile": "/path/to/classpath.txt"
  }'
```

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

## REST API Reference

Complete list of all 18 endpoints:

### Extraction

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/extraction/trigger` | Parse Java source code and populate the knowledge graph |

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

---

## Vaadin Dashboard Views

| URL | View | What it shows |
|-----|------|---------------|
| `http://localhost:8080/` | Dashboard | Metrics, risk heatmap, dependency graphs, business concepts |
| `http://localhost:8080/lexicon` | Lexicon | Business term curation with usage tracking |
| `http://localhost:8080/schedule` | Schedule | Migration wave planning with drill-down |

---

## Configuration Reference

All configuration is in `src/main/resources/application.yml`. Key settings you might want to change:

### Database Connections

```yaml
spring:
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: ${NEO4J_PASSWORD:esmp-local-password}
  datasource:
    url: jdbc:mysql://localhost:3307/esmp
    username: esmp
    password: ${MYSQL_PASSWORD:esmp-local-password}

qdrant:
  host: localhost
  port: 6334
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
| Graph (queries, validation) | - | 3 suites |
| Risk (structural + domain) | - | 2 suites (24 tests) |
| Vector (chunking, indexing) | 2 suites (13 tests) | 2 suites (12 tests) |
| Pilot | - | 2 suites (13 tests) |
| RAG | - | 2 suites (14 tests) |
| Incremental Indexing | - | 1 suite (8 tests) |
| Scheduling | 1 suite (3 tests) | 1 suite (6 tests) |
| Dashboard | - | 1 suite (7 tests) |
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

### Docker services won't start

**Check Docker is running:**

```bash
docker info
```

**Check port conflicts:**

```bash
# These ports must be free:
# 7474, 7687 (Neo4j), 6333, 6334 (Qdrant), 3307 (MySQL),
# 9090 (Prometheus), 3000 (Grafana)
netstat -an | grep -E "7474|7687|6333|6334|3307|9090|3000"
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
  docker compose up -d             POST /api/extraction/trigger
  ./gradlew bootRun                POST /api/vector/index

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

## License

See [LICENSE](LICENSE) file for details.
