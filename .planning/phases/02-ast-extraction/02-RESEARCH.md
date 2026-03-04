# Phase 2: AST Extraction - Research

**Researched:** 2026-03-04
**Domain:** OpenRewrite LST parsing, Spring Data Neo4j persistence, Vaadin 7 component detection
**Confidence:** MEDIUM-HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Source input strategy**
- Config property (`esmp.target.source-root`) as default source path, REST endpoint can override per request
- REST trigger only: POST /api/extraction/trigger — developer explicitly kicks off extraction
- Full recursive scan by default, optional module filter parameter to narrow scope
- Synchronous request — blocks until extraction finishes, returns summary

**OpenRewrite usage model**
- Embed rewrite-java parser library directly in ESMP as a dependency
- The Phase 1 classpath isolation decision ("OpenRewrite as Gradle plugin on target codebase only") applies to recipe EXECUTION (Vaadin migration transforms), not to AST PARSING
- Type-attributed parsing — provide target module's compiled classpath for fully resolved types and accurate call graphs
- Classpath provided via pre-exported classpath file: target project runs a Gradle task (e.g. `./gradlew exportClasspath`) that writes classpath to a file, ESMP reads it

**Vaadin 7 capture depth**
- Target codebase uses mix of everything: UI/Views, data binding, custom components, custom themes/widgetsets, server push
- Include explicit Vaadin 7 pattern audit report: after extraction, produce report showing what patterns were found and what OpenRewrite could/couldn't parse
- Add secondary Neo4j labels for Vaadin-specific nodes: :VaadinView, :VaadinComponent, :VaadinDataBinding — enables easy querying of all Vaadin artifacts
- Capture Vaadin component trees: extract parent-child layout hierarchy (UI → VerticalLayout → HorizontalLayout → Button) stored as CONTAINS_COMPONENT edges

**Sample module strategy**
- Synthetic test fixtures for automated tests: 5-10 representative Java files covering Vaadin UI class, View with Navigator, service with injected repos, repository, entity, data-bound form
- Real Vaadin 7 as test-only dependency — fixtures compile against real Vaadin 7 classes for accurate type resolution
- Manual validation against real legacy module as documented Phase 2 task: run extraction, compare graph output against manually verified expectations, document gaps
- Both synthetic and real validation — synthetic for repeatable CI, real for confidence

### Claude's Discretion
- Neo4j node property schema (which properties on Class, Method, Field nodes)
- Exact OpenRewrite rewrite-java parser configuration
- Idempotency implementation strategy (hash-based, version-based, etc.)
- Error handling for unparseable files
- Extraction summary response format
- Gradle export classpath task design for target project

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| AST-01 | System can parse Java/Vaadin 7 source code into structured AST using OpenRewrite LST | OpenRewrite `rewrite-java` 8.74.x embedded as library; `JavaParser.fromJavaVersion().classpath(paths).build()` parses from disk paths |
| AST-02 | System extracts class metadata, method signatures, field definitions, annotations, and imports | `JavaIsoVisitor` with `visitClassDeclaration`, `visitMethodDeclaration`, `visitVariableDeclarations` yields all required metadata; type info from `JavaType.*` |
| AST-03 | System builds call graph edges between methods across classes | `visitMethodInvocation` on `J.MethodInvocation` yields caller/callee; `JavaType.Method.getDeclaringType()` gives owning class; persisted as CALLS relationship in Neo4j |
| AST-04 | System persists extracted nodes and relationships to Neo4j graph database | Spring Data Neo4j already on classpath; `@Node` + business-key `@Id` + `@Version` for idempotent `save()`; Neo4j uniqueness constraint as safety net |
</phase_requirements>

---

## Summary

OpenRewrite's `rewrite-java` library (version 8.74.x) can be embedded directly in ESMP as a runtime dependency for AST parsing — distinct from using it as a Gradle plugin for recipe execution. The key API is `JavaParser.fromJavaVersion().classpath(Collection<Path>).typeCache(javaTypeCache).build()`, which accepts a list of JAR paths for type-attributed parsing. Source files are parsed with `parser.parse(sourceFilePaths, baseDir, executionContext)`, returning `List<SourceFile>`. LST traversal is done with `JavaIsoVisitor` subclasses, overriding `visitClassDeclaration`, `visitMethodDeclaration`, `visitVariableDeclarations`, and `visitMethodInvocation` to extract all required metadata.

Spring Data Neo4j is already on the classpath. The idempotency challenge (re-running extraction without creating duplicates) is solved by using business-key `@Id` fields with a `@Version` annotation on each `@Node` entity, which allows SDN's `save()` to correctly issue MERGE statements. A Neo4j uniqueness constraint on each label's business-key property acts as a database-level safety net. Vaadin 7 detection is performed within the `visitClassDeclaration` and `visitMethodInvocation` visitors by checking fully-qualified type names against a known set of `com.vaadin.ui.*` classes. Component tree hierarchy (CONTAINS_COMPONENT edges) is captured via `visitMethodInvocation` targeting `addComponent()`/`addComponents()` call sites.

The STATE.md blocker — "OpenRewrite Vaadin 7 recipe coverage is LOW confidence" — must be addressed by this phase's Vaadin pattern audit task. The audit runs extraction against real legacy source and documents what OpenRewrite parses correctly versus what gaps exist, outputting findings to a JSON/Markdown report that informs Phase 3+ recipe strategy.

**Primary recommendation:** Embed `org.openrewrite:rewrite-java:8.74.3` + `org.openrewrite:rewrite-java-21:8.74.3` directly; use `JavaIsoVisitor` pattern for metadata extraction; use business-key + `@Version` on all `@Node` classes for idempotent Neo4j persistence.

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| org.openrewrite:rewrite-java | 8.74.3 | Java LST parser and visitor API | Official OpenRewrite parser; handles type attribution |
| org.openrewrite:rewrite-java-21 | 8.74.3 | Java 21 compiler bindings for OpenRewrite | Required when ESMP JVM is Java 21; version-specific module |
| spring-boot-starter-data-neo4j | 3.5.11 (managed) | Neo4j OGM with Spring | Already on classpath; `@Node`/`@Relationship`/Neo4jRepository |
| spring-boot-starter-web | 3.5.11 (managed) | REST endpoint for extraction trigger | Already on classpath |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| com.vaadin:vaadin-server | 7.7.48 | Real Vaadin 7 types for test classpath type resolution | testImplementation only — provides Vaadin 7 class symbols for accurate type attribution in test fixture parsing |
| org.openrewrite:rewrite-core | 8.74.3 | ExecutionContext, InMemoryExecutionContext, SourceFile | Pulled transitively by rewrite-java; import explicitly if needed |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| rewrite-java embedded | JavaParser (com.github.javaparser) | JavaParser has no built-in visitor infrastructure for type-attributed call graph; would require hand-rolling type resolution |
| rewrite-java embedded | Eclipse JDT | Eclipse JDT works but is heavier, less well-documented for embedded use, and we'd lose OpenRewrite's visitor API consistency with future recipe phases |
| @Version for idempotency | SHA-256 hash property | Hash approach requires custom dirty-checking; `@Version` works out of the box with SDN's `save()` |

**Installation — add to `build.gradle.kts`:**
```kotlin
// libs.versions.toml additions:
// openrewrite = "8.74.3"
// vaadin-server = "7.7.48"

implementation("org.openrewrite:rewrite-java:8.74.3")
implementation("org.openrewrite:rewrite-java-21:8.74.3")
testImplementation("com.vaadin:vaadin-server:7.7.48")
```

> **Note on rewrite-recipe-bom:** The BOM (`org.openrewrite.recipe:rewrite-recipe-bom:3.25.0`) is designed for recipe jars, not embedded parsing. For ESMP, directly pin `rewrite-java` and `rewrite-java-21` versions in the version catalog.

---

## Architecture Patterns

### Recommended Project Structure

```
src/main/java/com/esmp/extraction/
├── api/
│   └── ExtractionController.java      # POST /api/extraction/trigger
├── application/
│   └── ExtractionService.java         # Orchestrates parse → visit → persist
├── parser/
│   ├── JavaSourceParser.java          # Wraps JavaParser, reads classpath file
│   └── ClasspathLoader.java           # Reads exportClasspath file into List<Path>
├── visitor/
│   ├── ClassMetadataVisitor.java      # visitClassDeclaration, visitMethodDeclaration
│   ├── CallGraphVisitor.java          # visitMethodInvocation → CALLS edges
│   └── VaadinPatternVisitor.java      # com.vaadin.ui.* detection, CONTAINS_COMPONENT
├── model/
│   ├── ClassNode.java                 # @Node("Class")
│   ├── MethodNode.java                # @Node("Method")
│   ├── FieldNode.java                 # @Node("Field")
│   └── CallsRelationship.java         # @RelationshipProperties
├── persistence/
│   ├── ClassNodeRepository.java       # Neo4jRepository<ClassNode, String>
│   ├── MethodNodeRepository.java
│   └── FieldNodeRepository.java
├── audit/
│   └── VaadinPatternAuditReport.java  # Documents parsed vs unparseable Vaadin patterns
└── config/
    └── ExtractionConfig.java          # @ConfigurationProperties("esmp.extraction")
```

### Pattern 1: Type-Attributed Embedded Parsing

**What:** Build `JavaParser` with target project's classpath JARs so fully qualified types resolve (e.g., `com.vaadin.ui.Button` is recognized, not `Unknown`).

**When to use:** Any time accurate call graph resolution is needed. Without type attribution, `visitMethodInvocation` callee types are `Unknown`.

**Example:**
```java
// Source: DefaultProjectParser.java in rewrite-gradle-plugin (MEDIUM confidence)
JavaTypeCache typeCache = new JavaTypeCache();
List<Path> classpathJars = ClasspathLoader.loadFrom(classpathFilePath);

JavaParser parser = JavaParser.fromJavaVersion()
    .classpath(classpathJars)
    .typeCache(typeCache)
    .logCompilationWarningsAndErrors(false)
    .build();

InMemoryExecutionContext ctx = new InMemoryExecutionContext(
    throwable -> log.warn("Parse error: {}", throwable.getMessage()));

List<SourceFile> lst = parser.parse(javaSourcePaths, projectBaseDir, ctx);
```

### Pattern 2: JavaIsoVisitor for Metadata Extraction

**What:** Extend `JavaIsoVisitor<ExecutionContext>` (isomorphic — returns same LST type, compile-time safe) and override visit methods to collect metadata into an accumulator object passed through `ExecutionContext`.

**When to use:** Read-only extraction where you never mutate the LST.

**Example:**
```java
// Source: OpenRewrite docs/visitors (HIGH confidence pattern)
public class ClassMetadataVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

    @Override
    public J.ClassDeclaration visitClassDeclaration(
            J.ClassDeclaration cd, ExtractionAccumulator acc) {
        // Extract fully qualified name
        if (cd.getType() != null) {
            String fqn = cd.getType().getFullyQualifiedName();
            String simpleName = cd.getSimpleName();
            // cd.getModifiers() — public/abstract/final etc.
            // cd.getLeadingAnnotations() — @SpringView, @Entity etc.
            // cd.getImplements() — List<TypeTree>
            // cd.getExtends() — TypeTree (superclass)
            acc.addClass(fqn, simpleName, cd);
        }
        return super.visitClassDeclaration(cd, acc);  // MUST call super to recurse
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(
            J.MethodDeclaration md, ExtractionAccumulator acc) {
        // md.getSimpleName() — method name
        // md.getReturnTypeExpression() — return type
        // md.getParameters() — List<Statement> (cast to J.VariableDeclarations)
        // md.getLeadingAnnotations() — @Override, @Transactional etc.
        // md.getModifiers() — public/private/static etc.
        acc.addMethod(md, getCursor());  // getCursor() gives enclosing class context
        return super.visitMethodDeclaration(md, acc);
    }

    @Override
    public J.VariableDeclarations visitVariableDeclarations(
            J.VariableDeclarations vd, ExtractionAccumulator acc) {
        // vd.getTypeExpression() — declared type
        // vd.getVariables() — List<J.VariableDeclarations.NamedVariable>
        // vd.getLeadingAnnotations() — @Autowired, @Column etc.
        // Check if inside class body (not method param) via getCursor()
        acc.addField(vd, getCursor());
        return super.visitVariableDeclarations(vd, acc);
    }
}
```

### Pattern 3: Call Graph via visitMethodInvocation

**What:** Intercept every method call site, use the type system to resolve the callee's declaring class.

**When to use:** Building CALLS relationships between ClassNode/MethodNode pairs.

**Example:**
```java
// Source: OpenRewrite docs, FindCallGraph recipe pattern (MEDIUM confidence)
@Override
public J.MethodInvocation visitMethodInvocation(
        J.MethodInvocation mi, ExtractionAccumulator acc) {
    if (mi.getMethodType() != null) {
        JavaType.Method callee = mi.getMethodType();
        String calleeClass = callee.getDeclaringType().getFullyQualifiedName();
        String calleeMethod = callee.getName();

        // Caller context — walk cursor up to enclosing MethodDeclaration
        J.MethodDeclaration enclosingMethod = getCursor()
            .firstEnclosing(J.MethodDeclaration.class);
        if (enclosingMethod != null && enclosingMethod.getMethodType() != null) {
            acc.addCall(
                enclosingMethod.getMethodType().getDeclaringType().getFullyQualifiedName(),
                enclosingMethod.getSimpleName(),
                calleeClass,
                calleeMethod
            );
        }
    }
    return super.visitMethodInvocation(mi, acc);
}
```

### Pattern 4: Vaadin 7 Component Detection

**What:** Detect Vaadin 7 patterns from type names in the LST.

**When to use:** Labelling VaadinView/VaadinComponent nodes and building CONTAINS_COMPONENT edges.

**Example:**
```java
// Source: research synthesis (MEDIUM confidence)
private static final String VAADIN_PACKAGE = "com.vaadin.ui";
private static final String ADD_COMPONENT = "addComponent";
private static final Set<String> VAADIN_VIEW_TYPES = Set.of(
    "com.vaadin.navigator.View",
    "com.vaadin.ui.UI"
);

@Override
public J.ClassDeclaration visitClassDeclaration(
        J.ClassDeclaration cd, ExtractionAccumulator acc) {
    // Detect VaadinView by implements
    if (cd.getImplements() != null) {
        cd.getImplements().forEach(impl -> {
            if (impl.getType() instanceof JavaType.Class ct) {
                if (VAADIN_VIEW_TYPES.contains(ct.getFullyQualifiedName())) {
                    acc.markAsVaadinView(cd.getType().getFullyQualifiedName());
                }
            }
        });
    }
    return super.visitClassDeclaration(cd, acc);
}

@Override
public J.MethodInvocation visitMethodInvocation(
        J.MethodInvocation mi, ExtractionAccumulator acc) {
    // Detect addComponent() calls for component tree
    if (ADD_COMPONENT.equals(mi.getSimpleName())
            && mi.getMethodType() != null
            && mi.getMethodType().getDeclaringType()
                   .getFullyQualifiedName().startsWith(VAADIN_PACKAGE)) {
        // mi.getSelect() = parent container expression
        // mi.getArguments().get(0) = child component expression
        acc.addComponentEdge(mi, getCursor());
    }
    return super.visitMethodInvocation(mi, acc);
}
```

### Pattern 5: Spring Data Neo4j @Node with Business Key + @Version

**What:** Business keys (fully qualified name) as `@Id` with `@Version` allow `save()` to MERGE idempotently.

**When to use:** All node entities in this phase.

**Example:**
```java
// Source: Spring Data Neo4j reference docs (HIGH confidence)
@Node({"JavaClass", "Class"})  // primary label + secondary label
public class ClassNode {

    @Id                          // business key — never changes for a class
    private String fullyQualifiedName;

    @Version                     // enables SDN to detect new vs existing entities
    private Long version;

    private String simpleName;
    private String packageName;
    private List<String> annotations;
    private List<String> modifiers;
    private boolean isInterface;
    private boolean isAbstract;

    // For VaadinView nodes, add extra label programmatically
    // or use a boolean flag + @Label if SDN version supports dynamic labels
}
```

**Vaadin secondary labels:** Spring Data Neo4j 7 supports multiple labels via the `@Node` annotation array. For dynamic labelling (e.g., only some ClassNodes get `:VaadinView`), use a `@DynamicLabels Collection<String> extraLabels` field, which SDN reads and writes as additional Neo4j node labels.

```java
// Source: Spring Data Neo4j docs (MEDIUM confidence)
@DynamicLabels
private Set<String> extraLabels = new HashSet<>();
// When VaadinView is detected: classNode.getExtraLabels().add("VaadinView");
```

### Anti-Patterns to Avoid

- **Calling `parser.parse()` on the full codebase without the classpath file:** Without type attribution, all `getMethodType()` calls return null or Unknown types, making call graph extraction useless. Always provide the classpath before parsing.
- **Forgetting `super.visit*()` in visitor methods:** OpenRewrite visitors only recurse into children if you call the super method. Omitting it means nested class methods are never visited.
- **Using SDN `save()` without `@Version` on business-key entities:** Without `@Version`, SDN cannot determine if an entity is new or existing. It will treat every `save()` as a create, producing `CREATE` instead of `MERGE` and causing duplicate nodes on re-extraction.
- **Running extraction synchronously on very large codebases inside an HTTP request:** The decision is synchronous by design for Phase 2. Flag if parsing 1000+ files exceeds HTTP timeout. Spring's `@Async` + SSE or a polling endpoint should be considered in a later phase.
- **Mutating the LST in an extraction visitor:** Use `JavaIsoVisitor` (read-only extraction intent). Using `JavaVisitor` with mutations is recipe authoring territory, not extraction.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Java source parsing | Custom regex / string parsing | `rewrite-java` JavaParser | Handles all Java syntax edge cases, generics, lambdas, nested classes |
| Type resolution | Walking import statements manually | Type-attributed JavaParser with classpath | Imports alone cannot resolve overloaded methods or inherited members |
| Call graph extraction | Walking strings for method names | `visitMethodInvocation` + `JavaType.Method.getDeclaringType()` | Only type-attributed LST gives caller's enclosing class and callee's declaring class reliably |
| Duplicate detection | Custom SELECT-then-INSERT logic | `@Id` + `@Version` + SDN `save()` | SDN issues MERGE on the `@Id` property; correct with `@Version` to avoid extra CREATE |
| Neo4j schema | Manual schema creation | Spring Boot auto-schema + explicit uniqueness constraint in startup | SDN can create indexes but NOT uniqueness constraints automatically; add a Cypher startup script |

**Key insight:** OpenRewrite's visitor infrastructure eliminates nearly all AST traversal boilerplate. The entire call graph, class hierarchy, and field/method extraction is a matter of overriding the right `visit*` methods.

---

## Common Pitfalls

### Pitfall 1: Null `getMethodType()` on Invocations Without Classpath

**What goes wrong:** `visitMethodInvocation` fires, but `mi.getMethodType()` is null. Callee class is unknown. Call graph has empty CALLS relationships.
**Why it happens:** Parser was built without `classpath(paths)` or the classpath file was empty/wrong.
**How to avoid:** Validate the classpath file exists and has entries before parsing. Log a warning and skip call graph extraction (not fail fast) when type is null; mark affected nodes with a `typeResolved: false` property.
**Warning signs:** Many CALLS edges with `null` or `"Unknown"` callee classes in the graph.

### Pitfall 2: Duplicate Nodes on Re-Extraction

**What goes wrong:** Running extraction twice creates two `ClassNode` nodes with the same FQN.
**Why it happens:** `@Id` business key entity without `@Version` — SDN cannot determine entity is existing, issues `CREATE` instead of `MERGE`.
**How to avoid:** Add `@Version private Long version;` to every `@Node` entity. Add a Neo4j uniqueness constraint as a database safety net.
**Warning signs:** `MATCH (n:JavaClass) WITH n.fullyQualifiedName, count(*) as c WHERE c > 1 RETURN n` returns rows.

### Pitfall 3: rewrite-java Version Conflicts with Spring Boot Classpath

**What goes wrong:** `rewrite-java` pulls Jackson, ASM, or other transitive dependencies that conflict with Spring Boot's managed versions.
**Why it happens:** OpenRewrite has its own dependency tree that may version-conflict with Spring Boot BOM.
**How to avoid:** Add `rewrite-java` dependency outside Spring Boot's dependency management scope. Use Gradle's `configurations.implementation.resolutionStrategy` to force Spring Boot's versions for shared libraries if needed. Test by running `./gradlew dependencies` and inspecting for conflicts.
**Warning signs:** `ClassNotFoundException` or `NoSuchMethodError` at startup or during parsing.

### Pitfall 4: rewrite-java-21 Module Missing

**What goes wrong:** `JavaParser.fromJavaVersion()` fails or silently falls back to Java 11 parser, causing Java 21 syntax (`record`, `sealed class`, `pattern matching`) to fail parsing.
**Why it happens:** `rewrite-java` core does not bundle the version-specific compiler module. `rewrite-java-21` must be explicitly added as a dependency.
**How to avoid:** Add `org.openrewrite:rewrite-java-21` explicitly. `fromJavaVersion()` auto-selects the right module from classpath.
**Warning signs:** Parse errors on files using Java 21 features.

### Pitfall 5: Component Tree Extraction From Static Code Only

**What goes wrong:** `addComponent()` calls inside conditional blocks, loops, or helper methods are missed, giving incomplete component trees.
**Why it happens:** Component trees in legacy Vaadin 7 code are often built procedurally in `init()`, conditional branches, or lazy-init helpers — not flat constructors.
**How to avoid:** Accept static best-effort extraction. Document in the Vaadin audit report that component trees are conservative (may be incomplete). Never assert completeness in tests.
**Warning signs:** VaadinView nodes with zero CONTAINS_COMPONENT edges despite clearly having child components.

### Pitfall 6: Classpath File Path Is a Windows Path on Linux CI

**What goes wrong:** Target project exports classpath as `C:\path\to\jar` but ESMP running in Docker/CI on Linux cannot resolve it.
**Why it happens:** The `exportClasspath` Gradle task writes system-native path separators.
**How to avoid:** Normalize paths in `ClasspathLoader`. Reject paths that don't exist with a clear error. Document that classpath export and ESMP must run on the same OS (or use Docker-mounted volumes).
**Warning signs:** `FileNotFoundException` on classpath JARs despite file being listed.

---

## Code Examples

Verified patterns from official sources:

### Parsing Source Files with Type Attribution
```java
// Source: rewrite-gradle-plugin DefaultProjectParser (MEDIUM confidence)
// Based on: https://github.com/openrewrite/rewrite-gradle-plugin/blob/main/plugin/src/main/java/org/openrewrite/gradle/isolated/DefaultProjectParser.java

JavaTypeCache typeCache = new JavaTypeCache();
List<Path> classpathJars = Files.readAllLines(classpathFilePath).stream()
    .map(Paths::get)
    .filter(Files::exists)
    .collect(Collectors.toList());

JavaParser parser = JavaParser.fromJavaVersion()
    .classpath(classpathJars)
    .typeCache(typeCache)
    .logCompilationWarningsAndErrors(false)
    .build();

InMemoryExecutionContext ctx = new InMemoryExecutionContext(
    ex -> log.warn("Parse warning: {}", ex.getMessage()));

List<SourceFile> sources = parser.parse(javaSourcePaths, projectRoot, ctx);
```

### Running Multiple Visitors Over Parsed LST
```java
// Source: OpenRewrite visitor pattern docs (HIGH confidence)
// Run visitor against each SourceFile — visitors are stateless, accumulator is passed through
ExtractionAccumulator acc = new ExtractionAccumulator();
InMemoryExecutionContext ctx = new InMemoryExecutionContext();

for (SourceFile source : sources) {
    new ClassMetadataVisitor().visit(source, acc, ctx);
    new CallGraphVisitor().visit(source, acc, ctx);
    new VaadinPatternVisitor().visit(source, acc, ctx);
}
// acc now contains all extracted nodes and edges ready for Neo4j persistence
```

### Spring Data Neo4j @Node Entity with Idempotent Save
```java
// Source: Spring Data Neo4j reference — ID handling docs (HIGH confidence)
// https://docs.spring.io/spring-data/neo4j/reference/object-mapping/mapping-ids.html

@Node("JavaClass")
public class ClassNode {
    @Id
    private final String fullyQualifiedName;  // business key

    @Version
    private Long version;                      // enables MERGE detection

    @DynamicLabels
    private Set<String> extraLabels = new HashSet<>();  // for VaadinView, VaadinComponent

    private String simpleName;
    private String packageName;

    @Relationship(type = "DECLARES_METHOD", direction = OUTGOING)
    private List<MethodNode> methods = new ArrayList<>();

    @Relationship(type = "DECLARES_FIELD", direction = OUTGOING)
    private List<FieldNode> fields = new ArrayList<>();
}
```

### Neo4j Uniqueness Constraint (startup Cypher)
```cypher
-- Source: Neo4j Cypher docs (HIGH confidence)
CREATE CONSTRAINT java_class_fqn_unique IF NOT EXISTS
  FOR (n:JavaClass) REQUIRE n.fullyQualifiedName IS UNIQUE;

CREATE CONSTRAINT java_method_id_unique IF NOT EXISTS
  FOR (n:JavaMethod) REQUIRE n.methodId IS UNIQUE;
```

### Exporting Classpath from Target Gradle Project
```kotlin
// Source: Gradle API conventions (MEDIUM confidence)
// Place in the TARGET project's build.gradle.kts, not ESMP
tasks.register("exportClasspath") {
    doLast {
        val cpFile = file("build/esmp-classpath.txt")
        cpFile.parentFile.mkdirs()
        val classpath = configurations.runtimeClasspath.get()
            .resolvedConfiguration.resolvedArtifacts
            .map { it.file.absolutePath }
        cpFile.writeText(classpath.joinToString("\n"))
        println("Classpath written to ${cpFile.absolutePath} (${classpath.size} entries)")
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| JavaParser (GitHub javaparser) for AST | OpenRewrite rewrite-java for type-attributed LST | 2018-2020 | Full type resolution including transitive types; visitor API included |
| Neo4j OGM (neo4j-ogm) | Spring Data Neo4j 6+ (OGM dropped) | SDN 6.0 (2020) | Annotation-based, no separate OGM jar needed |
| `@Id @GeneratedValue` (internal Neo4j ID) | Business key `@Id` + `@Version` for domain entities | SDN 6+ | Stable IDs across re-imports; idempotent merge via MERGE |
| OpenRewrite Gradle plugin for parsing | Embedded `rewrite-java` library for parsing only | Always an option; clarified in Phase 2 | Plugin isolation is for recipe execution only; parsing can be embedded |

**Deprecated/outdated:**
- `neo4j-ogm`: Replaced by SDN 6+. Do NOT add `org.neo4j:neo4j-ogm-core` — it conflicts with modern SDN.
- `rewrite-java-8`, `rewrite-java-11`, `rewrite-java-17` modules: Not needed since ESMP runs on Java 21. `fromJavaVersion()` auto-selects `rewrite-java-21`.

---

## Open Questions

1. **OpenRewrite classpath conflict with Spring Boot BOM**
   - What we know: `rewrite-java` 8.74.x has its own transitive dependency tree (Jackson, ASM, etc.)
   - What's unclear: Whether Spring Boot 3.5.11's managed versions conflict with rewrite's versions
   - Recommendation: Run `./gradlew dependencies` immediately after adding the dependency. Use `exclude` groups or `resolutionStrategy.force` if conflicts appear. LOW confidence until tested.

2. **Vaadin 7 + Java 21 compile compatibility for test fixtures**
   - What we know: `vaadin-server:7.7.48` was built for Java 8; it uses `javax.servlet`, not `jakarta.servlet`
   - What's unclear: Whether Vaadin 7 server JARs can compile against Java 21 test sources (they likely can — Java 21 is backward compatible for compilation; it's runtime servlet API that breaks)
   - Recommendation: Add `vaadin-server` as `testImplementation` only, not runtime. Test fixtures must not reference Jakarta Servlet APIs that Vaadin 7 conflicts with at runtime. The JARs are needed for classpath type resolution only during parsing, not runtime.

3. **`@DynamicLabels` support for secondary Vaadin labels in current SDN version**
   - What we know: `@DynamicLabels` annotation exists in Spring Data Neo4j for adding extra labels at runtime
   - What's unclear: Exact SDN version that fully supports `@DynamicLabels` with `save()` and custom queries
   - Recommendation: If `@DynamicLabels` is problematic, use explicit boolean flags (`isVaadinView`, `isVaadinComponent`) on node entities as a fallback. LOW confidence on `@DynamicLabels` behaviour — verify in Wave 0 spike.

4. **Call graph completeness with static parsing**
   - What we know: `visitMethodInvocation` captures all statically-resolvable call sites
   - What's unclear: Reflective invocations (common in legacy Spring/Vaadin code) are invisible to static analysis
   - Recommendation: Accept as a known limitation. Document in audit report. Static call graph is sufficient for Phase 2 scope.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (via `spring-boot-starter-test`, already configured) |
| Config file | `build.gradle.kts` — `tasks.withType<Test> { useJUnitPlatform() }` already present |
| Quick run command | `./gradlew test --tests "com.esmp.extraction.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| AST-01 | Parse a synthetic Java/Vaadin 7 source file without error | Unit | `./gradlew test --tests "com.esmp.extraction.parser.JavaSourceParserTest"` | Wave 0 |
| AST-01 | Parse fails gracefully on malformed Java file | Unit | `./gradlew test --tests "com.esmp.extraction.parser.JavaSourceParserTest"` | Wave 0 |
| AST-02 | Class metadata extracted correctly from fixture (name, package, annotations, imports) | Unit | `./gradlew test --tests "com.esmp.extraction.visitor.ClassMetadataVisitorTest"` | Wave 0 |
| AST-02 | Method signatures extracted (name, return type, params, annotations) | Unit | `./gradlew test --tests "com.esmp.extraction.visitor.ClassMetadataVisitorTest"` | Wave 0 |
| AST-02 | Field definitions extracted (name, type, annotations) | Unit | `./gradlew test --tests "com.esmp.extraction.visitor.ClassMetadataVisitorTest"` | Wave 0 |
| AST-03 | CALLS edges extracted between methods across fixture classes | Unit | `./gradlew test --tests "com.esmp.extraction.visitor.CallGraphVisitorTest"` | Wave 0 |
| AST-04 | Extracted nodes persisted to Neo4j; re-running does not create duplicates | Integration | `./gradlew test --tests "com.esmp.extraction.ExtractionIntegrationTest"` | Wave 0 |
| AST-04 | POST /api/extraction/trigger returns 200 with extraction summary | Integration | `./gradlew test --tests "com.esmp.extraction.ExtractionIntegrationTest"` | Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.extraction.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/com/esmp/extraction/parser/JavaSourceParserTest.java` — covers AST-01
- [ ] `src/test/java/com/esmp/extraction/visitor/ClassMetadataVisitorTest.java` — covers AST-02
- [ ] `src/test/java/com/esmp/extraction/visitor/CallGraphVisitorTest.java` — covers AST-03
- [ ] `src/test/java/com/esmp/extraction/ExtractionIntegrationTest.java` — covers AST-04 (uses Testcontainers Neo4j, already configured)
- [ ] `src/test/resources/fixtures/` directory — synthetic Java source fixtures (Vaadin UI class, View, service, repo, entity, data-bound form)
- [ ] `build.gradle.kts` additions: `rewrite-java:8.74.3`, `rewrite-java-21:8.74.3`, `vaadin-server:7.7.48` (testImplementation)
- [ ] `libs.versions.toml` additions: `openrewrite = "8.74.3"`, `vaadin-server = "7.7.48"`

---

## Sources

### Primary (HIGH confidence)
- [OpenRewrite Visitors docs](https://docs.openrewrite.org/concepts-and-explanations/visitors) — visitor API, JavaIsoVisitor vs JavaVisitor, visit method signatures
- [OpenRewrite Java LST examples](https://docs.openrewrite.org/concepts-and-explanations/lst-examples) — J.ClassDeclaration, J.MethodDeclaration, J.VariableDeclarations structure
- [Spring Data Neo4j — ID handling](https://docs.spring.io/spring-data/neo4j/reference/object-mapping/mapping-ids.html) — @Id + @Version business key pattern, UUIDStringGenerator
- [Spring Data Neo4j — Custom queries](https://docs.spring.io/spring-data/neo4j/reference/appendix/custom-queries.html) — @Query + MERGE patterns, collect() anti-cartesian-product pattern
- [OpenRewrite latest versions](https://docs.openrewrite.org/reference/latest-versions-of-every-openrewrite-module) — rewrite-core 8.74.3, rewrite-java 8.74.3, rewrite-recipe-bom 3.25.0

### Secondary (MEDIUM confidence)
- [DefaultProjectParser.java in rewrite-gradle-plugin](https://github.com/openrewrite/rewrite-gradle-plugin/blob/main/plugin/src/main/java/org/openrewrite/gradle/isolated/DefaultProjectParser.java) — JavaParser.fromJavaVersion().classpath(paths).typeCache(cache).build() pattern; verified against official GitHub source
- [FindCallGraph recipe docs](https://docs.openrewrite.org/recipes/core/findcallgraph) — visitMethodInvocation produces caller/callee rows with FQN class names
- [OpenRewrite type attribution docs](https://docs.openrewrite.org/concepts-and-explanations/type-attribution) — requires full classpath; parser needs same classpath as compiler
- [vaadin-server Maven Central](https://repo1.maven.org/maven2/com/vaadin/vaadin-server/) — version 7.7.48 is latest in 7.x series (2025-08-08)
- [Vaadin 7 Navigator docs](https://vaadin.com/docs/v7/framework/advanced/advanced-navigator) — @SpringView, View interface, ComponentContainer, Navigator pattern
- [OpenRewrite GitHub discussions #4265](https://github.com/openrewrite/rewrite/discussions/4265) — standalone parsing complexity; classpath setup is the key challenge

### Tertiary (LOW confidence — flag for validation)
- OpenRewrite `@DynamicLabels` behaviour with `save()` in SDN 7 — found in Spring docs but exact interaction with `@Version` not confirmed via test
- `rewrite-java` transitive dependency conflict with Spring Boot 3.5.11 BOM — no confirmed conflict found, but untested; must be verified in Wave 0

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — official OpenRewrite latest versions confirmed, SDN already on classpath, Vaadin 7 Maven availability confirmed
- Architecture: MEDIUM-HIGH — JavaIsoVisitor pattern confirmed via official docs and Gradle plugin source; exact property schema is Claude's discretion
- Pitfalls: MEDIUM — classpath/null-type issues confirmed via discussions; `@Version` idempotency confirmed via SDN docs; Windows path pitfall is reasoned extrapolation

**Research date:** 2026-03-04
**Valid until:** 2026-04-04 (OpenRewrite releases frequently; check for patch updates before implementation)
