# Phase 5: Domain Lexicon - Research

**Researched:** 2026-03-05
**Domain:** Term extraction (OpenRewrite LST), Neo4j graph modeling, Vaadin 24 UI
**Confidence:** HIGH (core stack), MEDIUM (Vaadin 24 Gradle integration details)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Term extraction rules**
- Split camelCase/PascalCase class names into individual words only (no compound terms) — 'InvoicePaymentService' → 'Invoice', 'Payment'
- Strip technical suffixes before extraction — Service, Repository, Controller, Impl, Abstract, Base, DTO, Entity are stop-words
- Enum type name + individual constants both become terms — 'PaymentStatus' enum → 'Payment Status', 'Active', 'Pending Approval'
- Javadoc class-level only for term/definition extraction — method-level and inline comments are too noisy
- DB table and column names are split on underscores and treated as term sources

**Curation and re-extraction**
- Never overwrite hand-edited definitions — mark edited terms with a 'curated' boolean flag; re-extraction skips curated terms entirely
- Editable fields: definition, criticality, and synonyms — other fields (source, relationships, usage count) are auto-derived
- Auto-seed definitions from Javadoc — use class-level Javadoc as initial definition text; terms without Javadoc get blank definitions
- Auto-add new terms on re-extraction, flag them as 'new/uncurated' status — user can filter to see only new terms for review

**Lexicon UI approach**
- Vaadin 24 — Java-only UI framework matching the Spring Boot stack; sets the pattern for Phase 12
- Searchable, sortable, filterable data grid — columns: term, definition, criticality, source type, usage count, curated status
- No bulk operations for now — single-term editing only; keep the UI simple for v1
- Usage count in grid, detail on click — grid shows count (e.g., '12 classes'); clicking expands to list of related FQNs

**Term metadata and scoring**
- 3-level criticality: Low / Medium / High — simple, decisive, feeds into Phase 7 risk weighting
- Heuristic seeding for criticality — keyword patterns auto-assign: financial terms (payment, invoice, billing) → High; auth/security terms → High; generic utility terms → Low; user can override
- 3-level migration sensitivity: None / Moderate / Critical — 'None' = safe to auto-migrate, 'Critical' = needs domain expert review
- Pattern-based business rule detection — classes with names containing 'Validator', 'Rule', 'Policy', 'Constraint', 'Calculator', 'Strategy' are DEFINES_RULE candidates; also classes with validation annotations

### Claude's Discretion
- Exact technical suffix stop-word list (beyond the core set)
- Vaadin 24 view implementation details (routing, layout components, form binding)
- Neo4j schema design for BusinessTermNode (properties, constraints, indexes)
- USES_TERM edge creation heuristics (how aggressively to link terms to code)
- Validation query definitions for LexiconValidationQueryRegistry
- Grid filtering/sorting implementation details
- Definition auto-seeding logic when Javadoc is ambiguous or multi-sentence

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| LEX-01 | System extracts business terms from class names, enums, DB schema, Javadoc, and comments | OpenRewrite LST visitor pattern (LexiconVisitor), camelCase splitting regex, DB table column underscore splitting |
| LEX-02 | System stores terms with definition, synonyms, related classes, related tables, criticality, and migration sensitivity | BusinessTermNode Neo4j model design, constraint, Spring Data Neo4j repository pattern |
| LEX-03 | System creates USES_TERM and DEFINES_RULE graph edges connecting terms to code | LinkingService idempotent Cypher MERGE pattern, post-extraction linking phase |
| LEX-04 | User can view and curate the domain lexicon | Vaadin 24 Grid with filtering/sorting, inline editor, curated flag persistence |
</phase_requirements>

---

## Summary

Phase 5 adds a domain lexicon layer to the code knowledge graph. The work breaks into three clear technical areas: (1) term extraction from the AST via a new OpenRewrite visitor, (2) Neo4j modeling of BusinessTermNode with the curated-flag persistence contract, and (3) the first Vaadin 24 UI in the project.

All three areas build directly on patterns established in earlier phases. The LexiconVisitor follows the same `JavaIsoVisitor<ExtractionAccumulator>` template as the five existing visitors. BusinessTermNode follows the same `@Node` + `@Id` + `@Version` MERGE-semantics pattern as ClassNode. The LexiconValidationQueryRegistry follows the extensible `@Component` pattern designed in Phase 4.

Vaadin 24 is the only net-new technology. It requires a Gradle plugin (`com.vaadin` version matching the platform version) and a BOM import for dependency alignment. The key Vaadin 24 addition for this project is the `vaadin-spring-boot-starter` dependency plus the Vaadin Gradle plugin — Spring Boot auto-configures the rest. Views are plain Java classes annotated with `@Route` that extend a layout component. Grid sorting and filtering use the standard `Grid<T>` API with `HeaderRow` filter components and a `ListDataProvider`.

**Primary recommendation:** Implement in this sequence — (1) BusinessTermNode model + schema + repository, (2) LexiconVisitor + accumulator extension + mapper extension, (3) LinkingService extension for USES_TERM and DEFINES_RULE, (4) REST API at `/api/lexicon/`, (5) Vaadin 24 view with Grid and inline editor.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Data Neo4j (SDN) | via Spring Boot 3.5.11 BOM | BusinessTermNode persistence with MERGE semantics | Already in project; @Node + @Id + @Version pattern proven across all node types |
| OpenRewrite rewrite-java | 8.74.3 | AST traversal for class name, Javadoc, and enum extraction | Already in project; all 5 existing visitors use JavaIsoVisitor<ExtractionAccumulator> |
| Vaadin Flow + Spring Boot starter | 24.9.12 | Lexicon UI (grid, editor, dialogs) | Project decision; latest stable Vaadin 24.x on Maven Central |
| Vaadin Gradle Plugin | 24.9.12 | Frontend asset bundling for Vaadin views | Vaadin plugin version matches platform version; required for any Vaadin Flow view |
| Neo4jClient | via Spring Boot BOM | Complex Cypher for USES_TERM and DEFINES_RULE linking | Established project pattern for variable-length traversals |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Testcontainers Neo4j | 1.20.4 | Integration tests for LexiconVisitor and linking | All integration tests in this project use Testcontainers Neo4j |
| JUnit 5 + AssertJ | via Spring Boot BOM | Unit tests for TermExtractor logic and visitor | Standard project test stack |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Vaadin 24 | React/Angular frontend | Vaadin is Java-only — no TypeScript, no separate build pipeline; matches project convention |
| Vaadin 24 Grid inline editor | Vaadin CRUD component | CRUD is overkill for single-term editing; Grid editor is simpler and sufficient for v1 |
| ListDataProvider (in-memory) | Lazy DataProvider | Lexicon will have hundreds, not millions, of terms; in-memory is simpler and sufficient |

**Installation (additions to build.gradle.kts):**
```kotlin
// In plugins block — add Vaadin plugin
id("com.vaadin") version "24.9.12"

// In repositories block — add Vaadin add-ons repo
maven { url = uri("https://maven.vaadin.com/vaadin-addons") }

// In dependencyManagement block — add Vaadin BOM
imports {
    mavenBom("com.vaadin:vaadin-bom:24.9.12")
}

// In dependencies block
implementation("com.vaadin:vaadin-spring-boot-starter")
```

**Note on Vaadin plugin and Spring Boot conflict:** When both `org.springframework.boot` and `com.vaadin` plugins are present, `com.vaadin` plugin version must match the `vaadin-spring-boot-starter` version. The Vaadin plugin handles Node.js/npm download and frontend bundling automatically; no manual Node.js setup is required.

---

## Architecture Patterns

### Recommended Project Structure (additions)
```
src/main/java/com/esmp/
├── extraction/
│   ├── model/
│   │   └── BusinessTermNode.java          # new: @Node("BusinessTerm")
│   ├── visitor/
│   │   └── LexiconVisitor.java            # new: JavaIsoVisitor<ExtractionAccumulator>
│   ├── application/
│   │   ├── ExtractionAccumulator.java     # extend: add BusinessTermData map
│   │   ├── AccumulatorToModelMapper.java  # extend: add mapToBusinessTermNodes()
│   │   ├── LinkingService.java            # extend: add linkBusinessTermUsages() + linkBusinessRules()
│   │   └── ExtractionService.java         # extend: wire LexiconVisitor + BusinessTermNodeRepository
│   ├── persistence/
│   │   └── BusinessTermNodeRepository.java  # new: Neo4jRepository<BusinessTermNode, String>
│   └── config/
│       └── Neo4jSchemaInitializer.java    # extend: add BusinessTerm uniqueness constraint
├── graph/
│   ├── api/
│   │   └── LexiconController.java         # new: @RestController /api/lexicon/
│   ├── application/
│   │   └── LexiconService.java            # new: read + update term operations
│   └── validation/
│       └── LexiconValidationQueryRegistry.java  # new: @Component ValidationQueryRegistry
└── ui/
    └── LexiconView.java                   # new: @Route("lexicon") Vaadin 24 Grid view
```

### Pattern 1: LexiconVisitor following JavaIsoVisitor template
**What:** New OpenRewrite visitor that extracts business terms from class names, enums, and Javadoc.
**When to use:** Added to ExtractionService.extract() visitor sequence alongside existing 5 visitors.
**Example:**
```java
// Source: established project pattern from ClassMetadataVisitor + OpenRewrite 8.74.3 API
public class LexiconVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  // Technical suffix stop-words: stripped before extracting terms
  private static final Set<String> STOP_SUFFIXES = Set.of(
      "Service", "Repository", "Controller", "Impl", "Abstract",
      "Base", "DTO", "Entity", "Helper", "Util", "Manager", "Handler",
      "Factory", "Builder", "Adapter", "Facade", "Wrapper", "Processor",
      "Validator", "Converter", "Mapper", "Provider");

  // CamelCase split regex: handles PascalCase and consecutive capitals
  // "InvoicePaymentService" → ["Invoice", "Payment", "Service"]
  private static final Pattern CAMEL_SPLIT =
      Pattern.compile("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");

  @Override
  public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd,
      ExtractionAccumulator acc) {
    JavaType.FullyQualified type = cd.getType();
    if (type == null) return super.visitClassDeclaration(cd, acc);

    String simpleName = cd.getSimpleName();
    String fqn = type.getFullyQualifiedName();
    boolean isEnum = type.getKind() == JavaType.FullyQualified.Kind.Enum;

    // Extract Javadoc from class prefix comments
    String javadocText = extractJavadoc(cd);

    // Split class name into candidate terms, apply stop-suffix stripping
    List<String> nameTerms = splitAndFilter(simpleName);
    for (String term : nameTerms) {
      acc.addBusinessTerm(term, fqn, "CLASS_NAME", javadocText);
    }

    // For enums, also extract constants as terms
    if (isEnum) {
      cd.getBody().getStatements().stream()
          .filter(s -> s instanceof J.EnumValue)
          .map(s -> ((J.EnumValue) s).getName().getSimpleName())
          .forEach(constant -> acc.addBusinessTerm(constant, fqn, "ENUM_CONSTANT", null));
    }

    return super.visitClassDeclaration(cd, acc);
  }

  private String extractJavadoc(J.ClassDeclaration cd) {
    // Javadoc is stored in the prefix space comments of the class declaration
    return cd.getPrefix().getComments().stream()
        .filter(c -> c instanceof Javadoc.DocComment)
        .map(c -> ((Javadoc.DocComment) c).getBody())
        .map(body -> body.stream()
            .map(Object::toString)
            .collect(Collectors.joining(" "))
            .replaceAll("\\s+", " ")
            .trim())
        .findFirst()
        .orElse(null);
  }

  private List<String> splitAndFilter(String name) {
    String[] parts = CAMEL_SPLIT.split(name);
    return Arrays.stream(parts)
        .filter(p -> !STOP_SUFFIXES.contains(p))
        .filter(p -> p.length() > 2)  // skip single/double-char fragments
        .distinct()
        .collect(Collectors.toList());
  }
}
```

**IMPORTANT NOTE on Javadoc extraction:** The OpenRewrite `Javadoc.DocComment` class is in `org.openrewrite.java.tree.Javadoc`. Its `getBody()` returns a list of `Javadoc.Element` objects. The simplest extraction strategy is to call `print(new Cursor(null, cd))` on the `Javadoc.DocComment` instance and strip the `/** ... */` delimiters, or iterate body elements calling `toString()`. The exact API requires verification against OpenRewrite 8.74.3 — treat this as MEDIUM confidence and write a unit test fixture first.

### Pattern 2: BusinessTermNode following ClassNode model template
**What:** Neo4j node entity for a domain term with curated flag for re-extraction idempotency.
**When to use:** The 'curated' boolean is the LEX-04 compliance mechanism — re-extraction uses MERGE but skips definition update when curated=true.
**Example:**
```java
// Source: ClassNode pattern from extraction/model/ClassNode.java
@Node("BusinessTerm")
public class BusinessTermNode {

  /** Business key: normalized lowercase term text. */
  @Id private String termId;  // e.g., "invoice", "payment_status"

  @Version private Long version;  // enables SDN MERGE semantics

  private String displayName;     // original casing, e.g., "Invoice"
  private String definition;      // Javadoc seed or hand-edited
  private String criticality;     // "Low", "Medium", "High"
  private String migrationSensitivity;  // "None", "Moderate", "Critical"

  @Property("synonyms")
  private List<String> synonyms = new ArrayList<>();

  private boolean curated;        // true = hand-edited, protected from re-extraction overwrite
  private String status;          // "new", "curated", "auto"
  private String sourceType;      // "CLASS_NAME", "ENUM_CONSTANT", "DB_TABLE", "DB_COLUMN"
  private String primarySourceFqn; // FQN of the class/table where term was first observed
  private int usageCount;         // count of USES_TERM incoming edges (refreshed post-linking)
}
```

**Idempotent MERGE pattern for curated protection:**
```cypher
// Source: LinkingService MERGE pattern adapted for curated flag
MERGE (t:BusinessTerm {termId: $termId})
ON CREATE SET
  t.displayName = $displayName,
  t.definition  = $definition,
  t.criticality = $criticality,
  t.migrationSensitivity = $sensitivity,
  t.curated = false,
  t.status  = 'auto',
  t.sourceType = $sourceType,
  t.primarySourceFqn = $fqn,
  t.usageCount = 0
ON MATCH SET
  t.displayName = CASE WHEN t.curated THEN t.displayName ELSE $displayName END,
  t.definition  = CASE WHEN t.curated THEN t.definition  ELSE $definition  END,
  t.status = CASE WHEN t.curated THEN 'curated' ELSE 'auto' END
RETURN t
```

### Pattern 3: Vaadin 24 LexiconView
**What:** First Vaadin view in the project. A `@Route`-annotated class that renders a sortable/filterable grid with inline editing.
**When to use:** Accessed at `/lexicon` in the browser; Spring Boot auto-discovers it.
**Example:**
```java
// Source: Vaadin 24 official routing docs + Grid inline editor API
@Route("lexicon")
@PageTitle("Domain Lexicon")
public class LexiconView extends VerticalLayout {

  private final LexiconService lexiconService;
  private final Grid<BusinessTermNode> grid = new Grid<>(BusinessTermNode.class, false);
  private final ListDataProvider<BusinessTermNode> dataProvider;

  public LexiconView(LexiconService lexiconService) {
    this.lexiconService = lexiconService;
    List<BusinessTermNode> terms = lexiconService.findAll();
    dataProvider = new ListDataProvider<>(terms);
    grid.setDataProvider(dataProvider);
    configureGrid();
    configureFilters();
    add(buildToolbar(), grid);
    setSizeFull();
  }

  private void configureGrid() {
    grid.addColumn(BusinessTermNode::getDisplayName).setHeader("Term").setSortable(true);
    grid.addColumn(BusinessTermNode::getDefinition).setHeader("Definition").setSortable(false);
    grid.addColumn(BusinessTermNode::getCriticality).setHeader("Criticality").setSortable(true);
    grid.addColumn(BusinessTermNode::getSourceType).setHeader("Source").setSortable(true);
    grid.addColumn(BusinessTermNode::getUsageCount).setHeader("Usage Count").setSortable(true);
    grid.addColumn(t -> t.isCurated() ? "Curated" : "Auto").setHeader("Status").setSortable(true);
    grid.addComponentColumn(term -> {
      Button editBtn = new Button("Edit", e -> openEditor(term));
      return editBtn;
    }).setHeader("Actions");
  }

  private void configureFilters() {
    HeaderRow filterRow = grid.appendHeaderRow();
    TextField termFilter = new TextField();
    termFilter.setPlaceholder("Filter...");
    termFilter.addValueChangeListener(e ->
        dataProvider.addFilter(t -> t.getDisplayName().toLowerCase()
            .contains(e.getValue().toLowerCase())));
    filterRow.getCell(grid.getColumns().get(0)).setComponent(termFilter);
  }
}
```

### Pattern 4: DB table and column name extraction in LexiconVisitor
**What:** ExtractionAccumulator already has `getTableMappings()`. LexiconVisitor processes table/column terms from DBTableNode data (post-mapping, not from the visitor pass itself).
**When to use:** Run as a separate post-extraction step in ExtractionService after DBTableNodes are persisted — iterate `acc.getTableMappings()` entries.
**Example:**
```java
// DB table name: split on underscore
// "invoice_payment_detail" → "invoice", "payment", "detail"
for (Map.Entry<String, String> entry : acc.getTableMappings().entrySet()) {
  String tableName = entry.getValue();  // already lowercased
  Arrays.stream(tableName.split("_"))
      .filter(w -> w.length() > 2)
      .forEach(word -> acc.addBusinessTerm(
          word, entry.getKey(), "DB_TABLE", null));
}
```

### Anti-Patterns to Avoid
- **Visiting method-level comments for definitions:** Javadoc class-level only (locked decision). Method-level Javadoc is too noisy and creates duplicate/inconsistent definitions.
- **Storing compound terms:** 'InvoicePaymentService' → should NOT produce 'InvoicePayment'. Split into individual words only.
- **Overwriting curated definitions in the ON MATCH Cypher clause:** The `CASE WHEN t.curated` guard is the single point of LEX-04 compliance — omitting it breaks the invariant silently.
- **Putting Vaadin route classes outside the `com.esmp` package:** Spring Boot scans only under the @SpringBootApplication package. `LexiconView` must be in `com.esmp.ui` or a sub-package of `com.esmp`.
- **Mixing Neo4j transaction manager with Vaadin UI thread:** Vaadin's `UI.access()` is for push updates from background threads. The lexicon service save calls must complete in the HTTP request thread, not a background thread, to use the standard `neo4jTransactionManager`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| camelCase/PascalCase word splitting | Custom char-by-char parser | Java regex `(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])` | Well-known pattern; handles consecutive caps (e.g., "XMLParser" → "XML", "Parser") |
| Grid sorting | Custom sort comparator wiring | `column.setSortable(true)` in Vaadin 24 | SDN sorts in-memory with ListDataProvider automatically |
| Re-extraction idempotency | Application-level read-before-write | `MERGE ... ON CREATE / ON MATCH` with curated guard | Same MERGE pattern used for all existing nodes; transactional and atomic |
| Neo4j schema constraints | Runtime duplicate checks | `CREATE CONSTRAINT IF NOT EXISTS` in Neo4jSchemaInitializer | Already established pattern; database-level safety net |
| Term deduplication | Secondary map/set tracking | `termId = term.toLowerCase()` as @Id key + SDN MERGE | Business-key @Id with @Version provides dedup automatically |

**Key insight:** The curated flag + MERGE ON MATCH CASE expression is the entire re-extraction protection contract. Any approach that reads the node then conditionally writes is vulnerable to race conditions on concurrent extractions.

---

## Common Pitfalls

### Pitfall 1: Vaadin Gradle plugin version mismatch
**What goes wrong:** Build fails with `ClassNotFoundException` or frontend bundle errors if `com.vaadin` plugin version does not match `vaadin-spring-boot-starter` version imported via BOM.
**Why it happens:** The Vaadin Gradle plugin generates frontend build tasks tied to the specific platform version's npm packages.
**How to avoid:** Pin both the plugin version and BOM version to the same string: `24.9.12`. If using `implementation("com.vaadin:vaadin-spring-boot-starter")` with BOM, no version on the dependency, but plugin must still be explicit.
**Warning signs:** Gradle task `:vaadinBuildFrontend` fails; browser shows blank page or 404 on Vaadin routes.

### Pitfall 2: Vaadin route not discovered by Spring Boot
**What goes wrong:** `@Route("lexicon")` class exists but navigating to `/lexicon` returns 404.
**Why it happens:** Spring Boot scans only the package containing `@SpringBootApplication` and sub-packages. If `LexiconView` is outside `com.esmp`, it is invisible to Vaadin's route registry.
**How to avoid:** Place `LexiconView` in `com.esmp.ui` (a sub-package of `com.esmp`). Alternatively, add `@EnableVaadin("com.esmp.ui")` to the main application class if a separate package is preferred.
**Warning signs:** Route list in Vaadin DevTools shows no user-defined routes; application starts without error but view is unreachable.

### Pitfall 3: Javadoc extraction from OpenRewrite LST — empty body
**What goes wrong:** `cd.getPrefix().getComments()` returns empty list even when the source file has a Javadoc comment on the class.
**Why it happens:** OpenRewrite stores Javadoc in the leading whitespace/prefix of the first token of the class declaration (the annotations or class keyword). The comment belongs to the `J.ClassDeclaration`'s prefix, not to the class body. However, when no classpath is provided, type resolution may partially fail and some prefix metadata is stripped.
**How to avoid:** Test against a fixture file with a real `/** ... */` class comment in the unit test. If empty, fall back to extracting from `cd.getName().getPrefix().getComments()`. Accept null definition gracefully (blank Javadoc seed is valid per locked decision).
**Warning signs:** All terms have null/blank definitions even for classes that clearly have Javadoc.

### Pitfall 4: ListDataProvider filter accumulation
**What goes wrong:** Each keystroke in the filter TextField adds a new filter lambda to the `ListDataProvider`, causing AND-combination of all past filters.
**Why it happens:** `dataProvider.addFilter()` appends; it does not replace the previous filter.
**How to avoid:** Keep a reference to the filter object and call `dataProvider.clearFilters()` then re-apply, OR use `dataProvider.setFilter()` if available, OR maintain a single composite predicate field updated on each change and use `refreshAll()`.
**Warning signs:** Grid becomes increasingly restrictive and impossible to clear filters after typing multiple searches.

### Pitfall 5: Enum constant extraction produces noise terms
**What goes wrong:** Single-letter constants or generic constants like 'ACTIVE', 'INACTIVE', 'TRUE', 'FALSE' pollute the lexicon with non-domain terms.
**Why it happens:** Enum constant names are included without filtering.
**How to avoid:** Apply minimum length filter (>2 chars) and a generic stop-words list for common programming constants (ACTIVE, INACTIVE, ENABLED, DISABLED, TRUE, FALSE, NULL, DEFAULT, UNKNOWN).
**Warning signs:** Lexicon contains dozens of terms like 'True', 'False', 'Active' with no meaningful definitions.

### Pitfall 6: USES_TERM over-linking
**What goes wrong:** Every class that contains the word 'Invoice' in its name gets a USES_TERM edge to the 'Invoice' term, even if it's a test fixture or utility class.
**Why it happens:** Aggressive linking treats all term occurrences as semantic usage.
**How to avoid:** Limit USES_TERM edges to: (a) the primary source class where the term was extracted from, and (b) classes that depend on that class via DEPENDS_ON. Avoid creating USES_TERM for test classes (check sourceFilePath contains '/test/').
**Warning signs:** usageCount for common terms is in the hundreds; the related-classes list in the UI is unmanageably long.

---

## Code Examples

### Term normalization: termId key
```java
// Source: project convention — DBTableNode uses lowercase for deduplication
// Term key is normalized: lowercase, no spaces
private static String toTermId(String word) {
    return word.toLowerCase().trim();
}
// "Invoice" → "invoice", "PaymentStatus" → "paymentstatus" (NOT used — split first)
// After splitting: "Payment" → termId "payment", "Status" → termId "status"
```

### ExtractionAccumulator extension
```java
// Source: established ExtractionAccumulator inner-record pattern
// Add to ExtractionAccumulator:
private final Map<String, BusinessTermData> businessTerms = new HashMap<>();

public void addBusinessTerm(String word, String sourceFqn, String sourceType, String javadoc) {
    String termId = word.toLowerCase().trim();
    // putIfAbsent: first occurrence wins for primary source; accumulates synonymFqns
    businessTerms.computeIfAbsent(termId, k ->
        new BusinessTermData(termId, word, sourceFqn, sourceType, javadoc));
    // Track all source FQNs for usage count
    businessTerms.get(termId).addSourceFqn(sourceFqn);
}

public record BusinessTermData(
    String termId,
    String displayName,
    String primarySourceFqn,
    String sourceType,
    String javadocSeed) {
    // mutable usage tracking
    private final Set<String> allSourceFqns = new HashSet<>();
    public void addSourceFqn(String fqn) { allSourceFqns.add(fqn); }
}
```

### USES_TERM linking Cypher (in LinkingService)
```cypher
// Source: LinkingService idempotent MERGE pattern
MATCH (c:JavaClass {fullyQualifiedName: $classFqn})
MATCH (t:BusinessTerm {termId: $termId})
MERGE (c)-[r:USES_TERM]->(t)
RETURN count(r) AS cnt
```

### DEFINES_RULE linking Cypher (in LinkingService)
```cypher
// Source: pattern-based detection from CONTEXT.md decision
// Classes containing 'Validator', 'Rule', 'Policy', 'Constraint', 'Calculator', 'Strategy'
MATCH (c:JavaClass)
WHERE c.simpleName =~ '.*(Validator|Rule|Policy|Constraint|Calculator|Strategy).*'
MATCH (t:BusinessTerm)
WHERE t.termId IN [term extracted from class name split]
MERGE (c)-[r:DEFINES_RULE]->(t)
RETURN count(r) AS cnt
```

### BusinessTermNode Neo4j uniqueness constraint (in Neo4jSchemaInitializer)
```java
// Source: Neo4jSchemaInitializer existing pattern
createConstraint(
    "business_term_id_unique",
    "CREATE CONSTRAINT business_term_id_unique IF NOT EXISTS"
        + " FOR (n:BusinessTerm) REQUIRE n.termId IS UNIQUE");
```

### REST API update endpoint (LexiconController)
```java
// Source: project GraphQueryController pattern + curated flag logic
@PutMapping("/{termId:.+}")
public ResponseEntity<BusinessTermResponse> updateTerm(
    @PathVariable String termId,
    @RequestBody UpdateTermRequest request) {
    return lexiconService.updateTerm(termId, request.definition(),
        request.criticality(), request.synonyms())
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
}
// UpdateTermRequest sets curated=true on any successful edit
```

### LexiconValidationQueryRegistry (@Component)
```java
// Source: ValidationQueryRegistry extensible pattern from Phase 4
@Component
public class LexiconValidationQueryRegistry extends ValidationQueryRegistry {
    public LexiconValidationQueryRegistry() {
        super(List.of(
            new ValidationQuery(
                "ORPHAN_BUSINESS_TERMS",
                "BusinessTerm nodes with no USES_TERM incoming edges",
                """
                OPTIONAL MATCH (t:BusinessTerm) WHERE NOT ()-[:USES_TERM]->(t)
                RETURN count(t) AS count, collect(t.termId)[0..20] AS details
                """,
                ValidationSeverity.WARNING),
            new ValidationQuery(
                "DEFINES_RULE_COVERAGE",
                "Classes matching business-rule naming patterns with no DEFINES_RULE edge",
                """
                OPTIONAL MATCH (c:JavaClass)
                WHERE c.simpleName =~ '.*(Validator|Rule|Policy|Constraint).*'
                  AND NOT (c)-[:DEFINES_RULE]->()
                RETURN count(c) AS count, collect(c.fullyQualifiedName)[0..20] AS details
                """,
                ValidationSeverity.WARNING)
        ));
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Vaadin 7 (project target) | Vaadin 24 (project stack for tooling UI) | Vaadin 14+ → 24 | Jakarta EE 10, Spring Boot 3, no more Vaadin 7 APIs in tooling code |
| Separate frontend (React/Angular) for admin UIs | Vaadin Flow Java-only views | Project decision | No TypeScript, no separate build pipeline, Spring injection works in views |
| Manual term curation spreadsheets | Graph-native BusinessTermNode with curated flag | Phase 5 (new) | Re-extraction is idempotent; curated edits survive future code changes |

**Deprecated/outdated:**
- Vaadin 7 `BeanFieldGroup`, `FieldGroup`: Not used in tooling UI. Vaadin 24 uses `Binder<T>` for form binding.
- OpenRewrite 7.x `visitCompilationUnit` patterns: Project uses 8.74.3 which uses `JavaIsoVisitor<P>` with execution context; same pattern established in existing visitors.

---

## Open Questions

1. **Javadoc body extraction — exact Javadoc.DocComment API**
   - What we know: OpenRewrite stores Javadoc in prefix comments; `Javadoc.DocComment` is in `org.openrewrite.java.tree.Javadoc`; `getBody()` returns `List<Javadoc.Element>`
   - What's unclear: Whether iterating elements with `toString()` produces clean text or includes tags like `@param`, `@return`. Multi-sentence Javadoc may need first-sentence extraction.
   - Recommendation: Write `LexiconVisitorTest` with a fixture class that has a class-level Javadoc comment as the first unit test. Inspect actual output before building the seed-from-Javadoc feature.

2. **LexiconValidationQueryRegistry inheritance vs. delegation**
   - What we know: `ValidationService` accepts `List<ValidationQueryRegistry>` and calls `getQueries()` on each. `ValidationQueryRegistry` is a `@Component` class, not an interface or abstract class.
   - What's unclear: Whether extending `ValidationQueryRegistry` (which has its own `@Component`) creates bean ambiguity, or whether it is better to create a new independent class with the same `getQueries()` method signature (duck typing via the service's iteration).
   - Recommendation: Make `LexiconValidationQueryRegistry` a separate POJO class (not extending `ValidationQueryRegistry`) implementing the same `getQueries()` contract. Check whether `ValidationService` uses the type directly or ducks to the method. If the service iterates `List<ValidationQueryRegistry>`, `LexiconValidationQueryRegistry` must extend it or both must implement a shared interface. Check the actual `ValidationService` source before coding.

3. **Vaadin 24 production build vs. development mode**
   - What we know: Vaadin runs in development mode by default (uses Node.js dev server for fast refresh); production mode bundles all frontend assets.
   - What's unclear: Whether this project needs to configure production mode now or if development mode is acceptable for v1.
   - Recommendation: Development mode is fine for v1. No `vaadin.productionMode=true` property needed. The Vaadin Gradle plugin handles the build automatically.

---

## Validation Architecture

> `workflow.nyquist_validation` is `true` in `.planning/config.json` — this section is required.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + AssertJ + Testcontainers (existing project stack) |
| Config file | No separate config — `tasks.withType<Test> { useJUnitPlatform() }` in build.gradle.kts |
| Quick run command | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| LEX-01 | camelCase class names split into individual word terms | unit | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` | Wave 0 |
| LEX-01 | Technical suffixes stripped from extracted terms | unit | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` | Wave 0 |
| LEX-01 | Enum type name and constants extracted as terms | unit | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` | Wave 0 |
| LEX-01 | DB table and column names produce terms | unit | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` | Wave 0 |
| LEX-01 | Class-level Javadoc seeds the definition field | unit | `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"` | Wave 0 |
| LEX-02 | BusinessTermNode persists with all required properties to Neo4j | integration | `./gradlew test --tests "com.esmp.extraction.application.LexiconIntegrationTest"` | Wave 0 |
| LEX-02 | Re-extraction does not overwrite curated=true term definitions | integration | `./gradlew test --tests "com.esmp.extraction.application.LexiconIntegrationTest"` | Wave 0 |
| LEX-03 | USES_TERM edge created between JavaClass and BusinessTermNode | integration | `./gradlew test --tests "com.esmp.extraction.application.LexiconIntegrationTest"` | Wave 0 |
| LEX-03 | DEFINES_RULE edge created for Validator/Rule/Policy pattern classes | integration | `./gradlew test --tests "com.esmp.extraction.application.LexiconIntegrationTest"` | Wave 0 |
| LEX-04 | GET /api/lexicon/ returns all terms with required fields | integration | `./gradlew test --tests "com.esmp.graph.api.LexiconControllerTest"` | Wave 0 |
| LEX-04 | PUT /api/lexicon/{termId} persists edits and sets curated=true | integration | `./gradlew test --tests "com.esmp.graph.api.LexiconControllerTest"` | Wave 0 |
| LEX-04 | Vaadin LexiconView loads without error (smoke test) | manual | Browser: `http://localhost:8080/lexicon` | manual-only |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.extraction.visitor.LexiconVisitorTest"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/extraction/visitor/LexiconVisitorTest.java` — covers LEX-01 (all extraction scenarios)
- [ ] `src/test/java/com/esmp/extraction/application/LexiconIntegrationTest.java` — covers LEX-02, LEX-03 (Testcontainers Neo4j)
- [ ] `src/test/java/com/esmp/graph/api/LexiconControllerTest.java` — covers LEX-04 REST API (Testcontainers Neo4j + MockMvc)
- [ ] `src/test/resources/fixtures/lexicon/` — Java fixture files for LexiconVisitorTest (SampleInvoiceService.java, PaymentStatusEnum.java)

---

## Sources

### Primary (HIGH confidence)
- Project codebase (`ExtractionAccumulator.java`, `ClassNode.java`, `LinkingService.java`, `ValidationQueryRegistry.java`, `Neo4jSchemaInitializer.java`, `ClassMetadataVisitor.java`, `ExtractionService.java`) — all existing patterns directly reused
- Vaadin official docs (vaadin.com/docs/latest/flow/routing/route) — @Route annotation pattern and view structure
- Vaadin official docs (vaadin.com/docs/latest/components/grid) — Grid API, column sorting, setItems
- Vaadin official docs (vaadin.com/docs/latest/components/grid/inline-editing) — Editor API, buffered mode, Binder

### Secondary (MEDIUM confidence)
- Maven Central sonatype.com — Vaadin 24.9.12 as latest stable 24.x release (verified on central.sonatype.com)
- WebSearch multiple sources — Vaadin Gradle plugin version must match platform version (24.9.x)
- WebSearch + OpenRewrite GitHub source — `Javadoc.DocComment` in `org.openrewrite.java.tree.Javadoc`, accessed via `cd.getPrefix().getComments()`
- WebSearch multiple sources — camelCase split regex `(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])` widely verified

### Tertiary (LOW confidence — verify before implementing)
- Javadoc body element iteration API (`getBody()` → `List<Javadoc.Element>` → `toString()`) — not independently verified against 8.74.3; write unit test first
- `LexiconValidationQueryRegistry` extension/delegation from `ValidationQueryRegistry` — requires checking `ValidationService` injection type before coding

---

## Metadata

**Confidence breakdown:**
- Standard stack (OpenRewrite, SDN, Neo4jClient): HIGH — all in use in existing code, same APIs
- BusinessTermNode schema design: HIGH — follows ClassNode pattern exactly
- Vaadin 24 Grid + Route basics: HIGH — verified against official docs
- Vaadin 24 Gradle plugin integration: MEDIUM — version numbers verified on Maven Central; Kotlin DSL syntax inferred from Groovy starter
- Javadoc extraction from OpenRewrite LST: MEDIUM — package and class name confirmed; body iteration API needs unit test verification
- LexiconValidationQueryRegistry wiring: MEDIUM — requires checking ValidationService source to confirm injection type

**Research date:** 2026-03-05
**Valid until:** 2026-04-05 (Vaadin 24.9.x stable series; OpenRewrite 8.x stable series)
