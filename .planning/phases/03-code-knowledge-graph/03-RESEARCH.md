# Phase 3: Code Knowledge Graph - Research

**Researched:** 2026-03-04
**Domain:** Neo4j graph modeling, Spring Data Neo4j, OpenRewrite visitor pattern, Cypher query API
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Node type modeling:**
- Stereotype types (Service, Repository, UIView) use `@DynamicLabels` on ClassNode — same pattern as existing VaadinView/VaadinComponent/VaadinDataBinding labels
- Annotation becomes a first-class `AnnotationNode` in Neo4j with `HAS_ANNOTATION` edges from Class/Method/Field — enables queries like "find all classes with @Transactional"
- Package and Module both become first-class Neo4j nodes (`PackageNode`, `ModuleNode`) with hierarchy: Module → CONTAINS_PACKAGE → Package → CONTAINS_CLASS → Class
- Module = Gradle subproject or source root directory
- DB Table becomes a first-class `DBTableNode` with table name and schema properties, linked via `MAPS_TO_TABLE` edges from entity classes

**Relationship materialization:**
- EXTENDS and IMPLEMENTS: Post-extraction linking pass — after all classes are parsed and persisted, a separate linking step creates Neo4j relationship edges between ClassNode pairs using the existing `superClass` and `implementedInterfaces` string properties
- DEPENDS_ON: Derived from field injection (`@Autowired`, `@Inject` fields) and constructor parameter types — a class DEPENDS_ON another if it has a field typed as that class or takes it as a constructor argument
- BINDS_TO: Specifically for Vaadin 7 data binding connections — links a Vaadin UI View/Form to the entity/DTO it binds to (via BeanFieldGroup, FieldGroup, etc.)
- Relationships carry metadata properties: CALLS already has sourceFile+lineNumber; DEPENDS_ON carries injection type (constructor, field, setter); EXTENDS/IMPLEMENTS carry resolution confidence

**DB Table detection:**
- Annotation-based detection only: @Table, @Entity for MAPS_TO_TABLE; @Query annotations and Spring Data derived query method names (findByX) for QUERIES
- Target codebase is primarily JPA/Spring Data — annotation-based detection covers the dominant patterns
- Table name resolution: when @Entity exists but no @Table, derive table name using JPA default naming convention (e.g., CustomerOrder → customer_order)
- QUERIES edges are Method → DBTable (not Class → DBTable) for precise method-level impact analysis

**Query API design:**
- New package `com.esmp.graph` with dedicated REST controller — separation of concerns: extraction populates, graph queries
- Complex queries (inheritance chains, transitive dependencies) use custom Cypher via `@Query` annotations on repository methods — SDN derived queries cannot express variable-length path patterns
- Response format: flat JSON with nested arrays (className, methods, fields, dependencies, annotations, inheritanceChain) — simple, frontend-friendly, testable
- Primary lookup by fully qualified name (exact match); also support simple name search endpoint (GET /api/graph/search?name=X) returning a list of matches

### Claude's Discretion
- Exact Cypher query patterns for transitive dependency traversal
- AnnotationNode property schema (retention policy, target types, etc.)
- PackageNode/ModuleNode property design
- Neo4j index strategy for query performance
- Error handling for unresolved types in linking pass
- Whether to add new visitors or extend existing ones
- Batch size for post-extraction linking

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| CKG-01 | Graph stores Class, Method, Field, Annotation, Package, Module, UI View, Service, Repository, and DB Table nodes | New node types (AnnotationNode, PackageNode, ModuleNode, DBTableNode) and @DynamicLabels stereotype labels on ClassNode; all covered by Phase 2 extraction pipeline extension |
| CKG-02 | Graph stores CALLS, EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE relationships | CALLS already exists; EXTENDS/IMPLEMENTS via post-extraction linking pass; DEPENDS_ON via new DependencyVisitor; BINDS_TO via VaadinPatternVisitor extension; QUERIES/MAPS_TO_TABLE via new JpaPatternVisitor |
| CKG-03 | User can query the graph via structured API endpoints | New `com.esmp.graph` package with GraphQueryController; three required endpoints: structural context, inheritance chain, transitive service→repository dependencies |
</phase_requirements>

---

## Summary

Phase 3 extends the Phase 2 extraction pipeline with four types of work: (1) new Neo4j node types (AnnotationNode, PackageNode, ModuleNode, DBTableNode), (2) new relationship types on existing nodes (EXTENDS, IMPLEMENTS, DEPENDS_ON, BINDS_TO, QUERIES, MAPS_TO_TABLE), (3) stereotype label enrichment on ClassNode via @DynamicLabels, and (4) a new `com.esmp.graph` REST API package for querying the completed graph.

The codebase from Phase 2 provides strong foundations. The `ExtractionAccumulator` is a plain POJO that can be extended with new data maps. The `AccumulatorToModelMapper` is a Spring component that maps accumulator data to Neo4j entities — it can be extended or split to handle new node types. `ClassMetadataVisitor` already extracts annotations, superClass, and implementedInterfaces as string properties, which is exactly what the post-extraction linking pass needs to create EXTENDS/IMPLEMENTS edges. The `VaadinPatternVisitor` establishes the pattern for framework-specific detection that a new `JpaPatternVisitor` can follow for DB table detection.

The EXTENDS/IMPLEMENTS relationship design is the most architecturally critical piece: rather than building in-memory edges during visitor traversal (which requires all classes to be available), these are materialized in a separate post-extraction "linking pass" that runs after all ClassNodes are persisted. This is the right design because inheritance often spans files parsed separately. For the query API, variable-length Cypher path queries (e.g., `DEPENDS_ON*1..`) cannot be expressed as Spring Data Neo4j derived queries — they must use `@Query` with raw Cypher or use `Neo4jClient` for result mapping, which is the established pattern in `ClassNodeRepository`.

**Primary recommendation:** Extend ExtractionAccumulator with new data holders, create new visitor classes (DependencyVisitor, JpaPatternVisitor) following the existing visitor pattern, add a `LinkingService` that runs as a separate step after `saveAll()`, create new @Node entity classes for AnnotationNode/PackageNode/ModuleNode/DBTableNode, and add a `com.esmp.graph` package with GraphQueryController + GraphQueryService + repository interfaces using @Query Cypher for the three required endpoints.

---

## Standard Stack

### Core (already in project)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| spring-data-neo4j | via Spring Boot 3.5 BOM | SDN @Node, @RelationshipProperties, Neo4jRepository | Already used for ClassNode/MethodNode/FieldNode |
| spring-data-neo4j Neo4jClient | via Spring Boot 3.5 BOM | Low-level Cypher execution for complex queries | Required for variable-length path result mapping |
| openrewrite-java | in build.gradle.kts | JavaIsoVisitor for new DependencyVisitor and JpaPatternVisitor | Same visitor pattern as CallGraphVisitor |

### New Dependencies Needed
None — all required libraries are already on the classpath. The existing Neo4j, Spring Data Neo4j, OpenRewrite, and Spring Boot dependencies cover all Phase 3 needs.

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| testcontainers-neo4j | via Spring Boot testcontainers BOM | Integration tests for new node types and query API | Already used in ExtractionIntegrationTest |
| AssertJ | via spring-boot-starter-test | Fluent assertions in test methods | Already used throughout test suite |

**Installation:** No new dependencies required.

---

## Architecture Patterns

### Recommended Package Structure for Phase 3

```
src/main/java/com/esmp/
├── extraction/
│   ├── model/
│   │   ├── ClassNode.java           (extend: add stereotype labels via @DynamicLabels)
│   │   ├── AnnotationNode.java      (NEW: @Node("JavaAnnotation"))
│   │   ├── PackageNode.java         (NEW: @Node("JavaPackage"))
│   │   ├── ModuleNode.java          (NEW: @Node("JavaModule"))
│   │   ├── DBTableNode.java         (NEW: @Node("DBTable"))
│   │   ├── DependsOnRelationship.java (NEW: @RelationshipProperties)
│   │   ├── ExtendsRelationship.java   (NEW: @RelationshipProperties)
│   │   ├── ImplementsRelationship.java (NEW: @RelationshipProperties)
│   │   ├── BindsToRelationship.java   (NEW: @RelationshipProperties)
│   │   ├── QueriesRelationship.java   (NEW: @RelationshipProperties)
│   │   └── MapsToTableRelationship.java (NEW: @RelationshipProperties, entity→DBTable)
│   ├── visitor/
│   │   ├── ExtractionAccumulator.java (extend: new data holders for all new types)
│   │   ├── DependencyVisitor.java     (NEW: DEPENDS_ON + BINDS_TO detection)
│   │   └── JpaPatternVisitor.java     (NEW: @Entity/@Table + @Query + findByX detection)
│   ├── application/
│   │   ├── ExtractionService.java     (extend: add new visitors + call LinkingService)
│   │   ├── AccumulatorToModelMapper.java (extend: map new node types)
│   │   └── LinkingService.java        (NEW: post-extraction EXTENDS/IMPLEMENTS pass)
│   ├── persistence/
│   │   ├── AnnotationNodeRepository.java (NEW)
│   │   ├── PackageNodeRepository.java    (NEW)
│   │   ├── ModuleNodeRepository.java     (NEW)
│   │   └── DBTableNodeRepository.java    (NEW)
│   └── config/
│       └── Neo4jSchemaInitializer.java   (extend: add constraints for new node types)
└── graph/                               (NEW package)
    ├── api/
    │   ├── GraphQueryController.java    (NEW: GET /api/graph/*)
    │   ├── ClassStructureResponse.java  (NEW: flat JSON response record)
    │   ├── InheritanceChainResponse.java (NEW)
    │   └── DependencyResponse.java      (NEW)
    ├── application/
    │   └── GraphQueryService.java       (NEW: orchestrates Cypher queries)
    └── persistence/
        └── GraphQueryRepository.java    (NEW: custom @Query Cypher methods)
```

### Pattern 1: New @Node Entity with Business Key

Follow the exact same pattern as `ClassNode`, `MethodNode`, `FieldNode`. Use `@Id` with a string business key, `@Version` for MERGE semantics, no `@GeneratedValue`.

```java
// Source: established project pattern (ClassNode.java, MethodNode.java)
@Node("JavaAnnotation")
public class AnnotationNode {

    /** Business key: fully qualified annotation name (e.g., org.springframework.stereotype.Service). */
    @Id private String fullyQualifiedName;

    @Version private Long version;

    private String simpleName;

    /** Package the annotation belongs to. */
    private String packageName;

    // Optional: retention policy ("RUNTIME", "CLASS", "SOURCE") — parsed from @Retention if present
    private String retention;

    public AnnotationNode() {}

    public AnnotationNode(String fullyQualifiedName) {
        this.fullyQualifiedName = fullyQualifiedName;
    }
    // ... getters/setters
}
```

```java
// Source: established project pattern
@Node("JavaPackage")
public class PackageNode {

    /** Business key: fully qualified package name (e.g., com.example.sample). */
    @Id private String packageName;

    @Version private Long version;

    private String simpleName;   // last segment, e.g. "sample"

    private String moduleName;   // owning module name

    @Relationship(type = "CONTAINS_CLASS", direction = Relationship.Direction.OUTGOING)
    private List<ClassNode> classes = new ArrayList<>();

    public PackageNode() {}

    public PackageNode(String packageName) {
        this.packageName = packageName;
    }
    // ... getters/setters
}
```

```java
// Source: established project pattern
@Node("JavaModule")
public class ModuleNode {

    /** Business key: module name (Gradle subproject name or source root directory name). */
    @Id private String moduleName;

    @Version private Long version;

    private String sourceRoot;   // absolute path to source root

    private boolean isMultiModuleSubproject;

    @Relationship(type = "CONTAINS_PACKAGE", direction = Relationship.Direction.OUTGOING)
    private List<PackageNode> packages = new ArrayList<>();

    public ModuleNode() {}

    public ModuleNode(String moduleName) {
        this.moduleName = moduleName;
    }
    // ... getters/setters
}
```

```java
// Source: established project pattern
@Node("DBTable")
public class DBTableNode {

    /** Business key: table name (lowercased, as stored in the DB). */
    @Id private String tableName;

    @Version private Long version;

    private String schemaName;   // optional, nullable

    public DBTableNode() {}

    public DBTableNode(String tableName) {
        this.tableName = tableName;
    }
    // ... getters/setters
}
```

### Pattern 2: @RelationshipProperties with Metadata

For relationships with properties, follow the `CallsRelationship` and `ContainsComponentRelationship` pattern. `@Id @GeneratedValue` for the internal relationship ID, `@TargetNode` for the target entity.

```java
// Source: established project pattern (CallsRelationship.java)
@RelationshipProperties
public class DependsOnRelationship {

    @Id @GeneratedValue private Long id;

    @TargetNode private ClassNode target;

    /** How the dependency is injected: "field", "constructor", or "setter". */
    private String injectionType;

    /** Field name that carries the injection (e.g., "repository"). */
    private String fieldName;

    public DependsOnRelationship() {}

    public DependsOnRelationship(ClassNode target, String injectionType, String fieldName) {
        this.target = target;
        this.injectionType = injectionType;
        this.fieldName = fieldName;
    }
    // ... getters/setters
}
```

```java
// Source: established project pattern
@RelationshipProperties
public class ExtendsRelationship {

    @Id @GeneratedValue private Long id;

    @TargetNode private ClassNode target;

    /**
     * Resolution confidence: "RESOLVED" when the superclass FQN matched a persisted ClassNode;
     * "UNRESOLVED" when no ClassNode with that FQN exists (e.g., external library class).
     */
    private String resolutionConfidence;

    public ExtendsRelationship() {}

    public ExtendsRelationship(ClassNode target, String resolutionConfidence) {
        this.target = target;
        this.resolutionConfidence = resolutionConfidence;
    }
    // ... getters/setters
}
```

### Pattern 3: @DynamicLabels for Stereotype Labels on ClassNode

The `ClassNode.extraLabels` field already exists and already contains VaadinView, VaadinComponent, VaadinDataBinding. Phase 3 adds stereotype labels to this same field. No structural change to ClassNode is needed — only the `AccumulatorToModelMapper` label-application logic is extended.

```java
// Source: AccumulatorToModelMapper.java (established pattern, lines 116–126)
// In AccumulatorToModelMapper.mapToClassNodes(), add after existing Vaadin label logic:

if (acc.getServiceClasses().contains(cData.fqn())) {
    extraLabels.add("Service");
}
if (acc.getRepositoryClasses().contains(cData.fqn())) {
    extraLabels.add("Repository");
}
if (acc.getUiViewClasses().contains(cData.fqn())) {
    extraLabels.add("UIView");
}
```

### Pattern 4: New OpenRewrite Visitor

Follow `VaadinPatternVisitor` as the model. Each new concern gets its own `JavaIsoVisitor<ExtractionAccumulator>`. Always call `super.visit*()` for recursion.

```java
// Source: established project pattern (VaadinPatternVisitor.java, CallGraphVisitor.java)
public class DependencyVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
        "org.springframework.beans.factory.annotation.Autowired",
        "javax.inject.Inject",
        "jakarta.inject.Inject"
    );

    @Override
    public J.VariableDeclarations visitVariableDeclarations(
            J.VariableDeclarations vd, ExtractionAccumulator acc) {

        // Only class-level fields (not local variables)
        boolean insideMethod = getCursor().firstEnclosing(J.MethodDeclaration.class) != null;
        if (!insideMethod) {
            boolean hasInjectionAnnotation = vd.getLeadingAnnotations().stream()
                .anyMatch(a -> {
                    if (a.getAnnotationType().getType() instanceof JavaType.FullyQualified fq) {
                        return INJECTION_ANNOTATIONS.contains(fq.getFullyQualifiedName());
                    }
                    return false;
                });

            if (hasInjectionAnnotation && vd.getTypeExpression() != null
                    && vd.getTypeExpression().getType() instanceof JavaType.FullyQualified fieldType) {

                J.ClassDeclaration enclosingClass =
                    getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosingClass != null && enclosingClass.getType() != null) {
                    String declaringFqn = enclosingClass.getType().getFullyQualifiedName();
                    String targetFqn = fieldType.getFullyQualifiedName();
                    String fieldName = vd.getVariables().isEmpty()
                        ? "unknown" : vd.getVariables().get(0).getSimpleName();
                    acc.addDependencyEdge(declaringFqn, targetFqn, "field", fieldName);
                }
            }
        }

        return super.visitVariableDeclarations(vd, acc);
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(
            J.MethodDeclaration md, ExtractionAccumulator acc) {

        // Detect constructor injection — constructor with @Autowired or single-constructor idiom
        if (md.isConstructor() && md.getMethodType() != null) {
            boolean isAutowired = md.getLeadingAnnotations().stream()
                .anyMatch(a -> {
                    if (a.getAnnotationType().getType() instanceof JavaType.FullyQualified fq) {
                        return INJECTION_ANNOTATIONS.contains(fq.getFullyQualifiedName());
                    }
                    return false;
                });

            if (isAutowired) {
                String declaringFqn = md.getMethodType().getDeclaringType().getFullyQualifiedName();
                for (J.VariableDeclarations param : md.getParameters().stream()
                        .filter(p -> p instanceof J.VariableDeclarations)
                        .map(p -> (J.VariableDeclarations) p)
                        .toList()) {
                    if (param.getTypeExpression() != null
                            && param.getTypeExpression().getType() instanceof JavaType.FullyQualified pt) {
                        String fieldName = param.getVariables().isEmpty()
                            ? "unknown" : param.getVariables().get(0).getSimpleName();
                        acc.addDependencyEdge(declaringFqn, pt.getFullyQualifiedName(),
                            "constructor", fieldName);
                    }
                }
            }
        }

        return super.visitMethodDeclaration(md, acc);
    }
}
```

### Pattern 5: JPA Pattern Visitor

```java
// Source: established project pattern (VaadinPatternVisitor.java)
public class JpaPatternVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

    private static final String JPA_TABLE = "javax.persistence.Table";
    private static final String JAKARTA_TABLE = "jakarta.persistence.Table";
    private static final String JPA_ENTITY = "javax.persistence.Entity";
    private static final String JAKARTA_ENTITY = "jakarta.persistence.Entity";
    private static final String SPRING_QUERY = "org.springframework.data.jpa.repository.Query";

    @Override
    public J.ClassDeclaration visitClassDeclaration(
            J.ClassDeclaration cd, ExtractionAccumulator acc) {

        if (cd.getType() != null) {
            String fqn = cd.getType().getFullyQualifiedName();
            boolean hasEntity = false;
            String tableName = null;

            for (J.Annotation annotation : cd.getLeadingAnnotations()) {
                String annotFqn = resolveAnnotationFqn(annotation);
                if (JPA_ENTITY.equals(annotFqn) || JAKARTA_ENTITY.equals(annotFqn)) {
                    hasEntity = true;
                }
                if (JPA_TABLE.equals(annotFqn) || JAKARTA_TABLE.equals(annotFqn)) {
                    tableName = extractAnnotationAttributeValue(annotation, "name");
                }
            }

            if (hasEntity) {
                if (tableName == null || tableName.isBlank()) {
                    // JPA default naming: CamelCase → snake_case
                    tableName = toSnakeCase(cd.getSimpleName());
                }
                acc.addTableMapping(fqn, tableName);
            }
        }

        return super.visitClassDeclaration(cd, acc);
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(
            J.MethodDeclaration md, ExtractionAccumulator acc) {

        if (md.getMethodType() != null) {
            String declaringFqn = md.getMethodType().getDeclaringType().getFullyQualifiedName();
            String methodId = buildMethodId(declaringFqn, md.getSimpleName(),
                md.getMethodType().getParameterTypes());

            // Detect @Query annotation (explicit SQL/JPQL)
            for (J.Annotation annotation : md.getLeadingAnnotations()) {
                String annotFqn = resolveAnnotationFqn(annotation);
                if (SPRING_QUERY.equals(annotFqn)) {
                    acc.addQueryMethod(methodId, declaringFqn);
                    break;
                }
            }

            // Detect Spring Data derived query method names (findByX, deleteByX, countByX)
            String simpleName = md.getSimpleName();
            if (simpleName.startsWith("findBy") || simpleName.startsWith("deleteBy")
                    || simpleName.startsWith("countBy") || simpleName.startsWith("existsBy")) {
                acc.addQueryMethod(methodId, declaringFqn);
            }
        }

        return super.visitMethodDeclaration(md, acc);
    }

    private static String toSnakeCase(String camelCase) {
        // CamelCase → snake_case per JPA default ImplicitNamingStrategyLegacyHbmImpl
        return camelCase
            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
            .replaceAll("([a-z])([A-Z])", "$1_$2")
            .toLowerCase();
    }
    // ... resolveAnnotationFqn, extractAnnotationAttributeValue, buildMethodId helpers
}
```

### Pattern 6: Post-Extraction Linking Pass (LinkingService)

The linking pass runs after `classNodeRepository.saveAll(classNodes)` inside `ExtractionService.extract()`. It queries Neo4j for persisted ClassNode data and issues MERGE Cypher statements to create EXTENDS/IMPLEMENTS edges.

```java
// Source: CONTEXT.md decision, established Neo4jClient pattern
@Service
public class LinkingService {

    private final Neo4jClient neo4jClient;

    public LinkingService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Creates EXTENDS and IMPLEMENTS edges between ClassNodes using the superClass and
     * implementedInterfaces string properties already stored on each ClassNode.
     *
     * Idempotent: MERGE ensures no duplicate relationships.
     * Only links to ClassNodes that exist in the graph (internally resolved classes).
     * Unresolved external types (e.g., java.lang.Object, external library classes)
     * are skipped with a WARN log — not an error.
     */
    @Transactional("neo4jTransactionManager")
    public LinkingResult linkInheritanceRelationships() {
        // MATCH all classes that have a non-null superClass and find the matching target ClassNode
        // MERGE creates the edge; ON CREATE SET adds confidence property
        String extendsQuery =
            "MATCH (child:JavaClass) WHERE child.superClass IS NOT NULL " +
            "MATCH (parent:JavaClass {fullyQualifiedName: child.superClass}) " +
            "MERGE (child)-[r:EXTENDS]->(parent) " +
            "ON CREATE SET r.resolutionConfidence = 'RESOLVED' " +
            "RETURN count(r) AS count";

        Long extendsCount = neo4jClient.query(extendsQuery)
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one().orElse(0L);

        // MATCH all classes that have implementedInterfaces and find matching interface ClassNodes
        String implementsQuery =
            "MATCH (child:JavaClass) " +
            "WHERE child.implementedInterfaces IS NOT NULL AND size(child.implementedInterfaces) > 0 " +
            "UNWIND child.implementedInterfaces AS ifaceFqn " +
            "MATCH (iface:JavaClass {fullyQualifiedName: ifaceFqn}) " +
            "MERGE (child)-[r:IMPLEMENTS]->(iface) " +
            "ON CREATE SET r.resolutionConfidence = 'RESOLVED' " +
            "RETURN count(r) AS count";

        Long implementsCount = neo4jClient.query(implementsQuery)
            .fetchAs(Long.class)
            .mappedBy((typeSystem, record) -> record.get("count").asLong())
            .one().orElse(0L);

        return new LinkingResult(extendsCount, implementsCount);
    }

    public record LinkingResult(long extendsEdges, long implementsEdges) {}
}
```

### Pattern 7: Graph Query API (com.esmp.graph package)

The three required query API capabilities map to the three success criteria:

**Endpoint 1: Structural context for a class**
```java
// GET /api/graph/class/{fqn} — returns methods, fields, dependencies, annotations
// Source: CONTEXT.md decision + ClassNodeRepository @Query pattern
@Query("""
    MATCH (c:JavaClass {fullyQualifiedName: $fqn})
    OPTIONAL MATCH (c)-[:DECLARES_METHOD]->(m:JavaMethod)
    OPTIONAL MATCH (c)-[:DECLARES_FIELD]->(f:JavaField)
    OPTIONAL MATCH (c)-[:DEPENDS_ON]->(dep:JavaClass)
    OPTIONAL MATCH (c)-[:HAS_ANNOTATION]->(ann:JavaAnnotation)
    RETURN c, collect(DISTINCT m) AS methods, collect(DISTINCT f) AS fields,
           collect(DISTINCT dep) AS dependencies, collect(DISTINCT ann) AS annotations
    """)
ClassStructureResult findStructuralContext(String fqn);
```

**Endpoint 2: Inheritance chain for a class**
```java
// GET /api/graph/class/{fqn}/inheritance
// Variable-length path: cannot use SDN derived queries — must use Neo4jClient or raw @Query
// Source: Neo4j Cypher docs - variable-length patterns
@Query("""
    MATCH (c:JavaClass {fullyQualifiedName: $fqn})
    OPTIONAL MATCH chain = (c)-[:EXTENDS*1..]->(ancestor:JavaClass)
    RETURN c, nodes(chain) AS inheritanceChain
    """)
// NOTE: SDN cannot map path objects — use Neo4jClient with manual mapping instead
```

For the inheritance chain query, use `Neo4jClient` directly rather than `@Query` on a repository method, because SDN cannot map Cypher `nodes(path)` to domain entities. The pattern from `ExtractionIntegrationTest` shows how `Neo4jClient.query().fetchAs().mappedBy()` is used for custom result mapping.

**Endpoint 3: Services that transitively depend on a Repository**
```java
// GET /api/graph/repository/{fqn}/service-dependents
// Transitive dependency: DEPENDS_ON*1.. from any Service-labeled node to target Repository
// Source: Neo4j Cypher variable-length pattern docs
String query =
    "MATCH (repo:JavaClass {fullyQualifiedName: $fqn}) " +
    "MATCH (svc:JavaClass:Service)-[:DEPENDS_ON*1..]->(repo) " +
    "RETURN DISTINCT svc.fullyQualifiedName AS fqn, svc.simpleName AS simpleName";
```

### Pattern 8: Cypher Variable-Length Path Syntax
```cypher
// Source: Neo4j Cypher Manual - variable-length patterns
// Find all ancestors via inheritance chain (variable depth)
MATCH (c:JavaClass {fullyQualifiedName: $fqn})-[:EXTENDS*1..]->(ancestor)
RETURN ancestor.fullyQualifiedName, ancestor.simpleName

// Find all Services transitively depending on a Repository
MATCH (svc:JavaClass:Service)-[:DEPENDS_ON*1..]->(repo:JavaClass {fullyQualifiedName: $fqn})
RETURN DISTINCT svc.fullyQualifiedName AS fqn, svc.simpleName AS simpleName

// CRITICAL: Neo4j default relationship-traversal depth limit is 1 (for MATCH).
// Variable paths require explicit *1.. or *..N bounds. No bounds = can be very slow on large graphs.
// Recommend bounding at *1..10 for safety on large codebases.
```

### Anti-Patterns to Avoid

- **Storing the full inheritance chain as a property on ClassNode:** This duplicates data that the graph edge already encodes. Use the graph structure.
- **Doing the EXTENDS/IMPLEMENTS linking during visitor traversal:** Visitors process one file at a time; the target class may not yet be parsed. Post-extraction linking is required.
- **Returning Cypher `path` objects from `@Query` repository methods:** SDN cannot map Cypher path types to entity objects. Use `Neo4jClient` with manual `mappedBy()` for path queries.
- **Using `@GeneratedValue` as the `@Id` on new node types:** All new node types must use string business keys for MERGE idempotency. Only `@RelationshipProperties` classes use `@Id @GeneratedValue`.
- **Running the linking pass inside the visitor loop:** Linking must happen after all nodes are saved to the database — not during the AST traversal.
- **Creating DEPENDS_ON edges for java.lang.* and java.util.* types:** These are JDK internals, not architectural dependencies. Apply the same filter logic as `CallGraphVisitor.isJdkClass()`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Idempotent MERGE on new nodes | Custom duplicate detection logic | `@Id` string business key + `@Version` on @Node entities | SDN saveAll() uses MERGE semantics; @Version enables optimistic locking — this is the established project pattern |
| Variable-length inheritance chain query | Recursive Java code fetching parent class repeatedly | Cypher `[:EXTENDS*1..]` variable-length path in @Query | Neo4j handles graph traversal natively in one round-trip |
| Transitive dependency query | BFS/DFS in Java across Neo4j results | Cypher `[:DEPENDS_ON*1..]` with Service label filter | Single Cypher query traverses entire dependency graph |
| JPA table name derivation (CamelCase → snake_case) | Custom regex/string manipulation | Standard JPA naming convention: `toSnakeCase()` one-liner | JPA spec defines the convention; it's a simple string transform |
| Neo4j uniqueness constraints for new node types | No constraints (allow duplicates) | `CREATE CONSTRAINT IF NOT EXISTS` in Neo4jSchemaInitializer | Database-level safety net against duplicate nodes |

**Key insight:** Neo4j's graph traversal is fundamentally superior to any Java-side iteration for path queries. Never bring full node collections into Java memory to simulate what a single Cypher `*1..` pattern does in one database call.

---

## Common Pitfalls

### Pitfall 1: SDN Path Mapping Limitation
**What goes wrong:** Writing a `@Query` on a repository interface that returns a Cypher path (using `nodes(p)`, `relationships(p)`, or a path variable), then expecting SDN to map it to a list of `@Node` entities. The result is a mapping exception or empty result.
**Why it happens:** Spring Data Neo4j does not support mapping Cypher `path` types to domain entities. It can only map record columns that are nodes or relationships with known `@Node` labels.
**How to avoid:** For variable-length path queries where you need the intermediate nodes, use `Neo4jClient` with a manual `mappedBy()` lambda. Return `DISTINCT` nodes as separate record columns, not as path objects.
**Warning signs:** `MappingException` at runtime when calling an `@Query` repository method. Empty Optional from `findOne()` despite data existing.

### Pitfall 2: Post-Extraction Linking Pass Ordering
**What goes wrong:** Running the EXTENDS/IMPLEMENTS linking Cypher before `classNodeRepository.saveAll(classNodes)` completes. The MATCH in the Cypher finds no ClassNode pairs and creates 0 edges.
**Why it happens:** Within the same transaction, uncommitted node saves may not be visible to subsequent MATCH queries depending on SDN's session management.
**How to avoid:** Ensure `saveAll()` completes before calling `LinkingService`. Commit the save transaction first, then run the linking in a separate `@Transactional` call. Or use a single Neo4j transaction but flush before the MATCH: call `neo4jClient.query("CALL db.flushes()").run()` (Neo4j Enterprise) — or better, structure as two sequential `@Transactional` calls.
**Warning signs:** `linkInheritanceRelationships()` returns 0 edges even though ClassNodes with `superClass` properties exist.

### Pitfall 3: DEPENDS_ON Edge Noise from JDK Types
**What goes wrong:** `DependencyVisitor` records DEPENDS_ON edges to `java.lang.String`, `java.util.List`, `java.util.Optional`, etc. — types that cannot have ClassNode entries and would generate thousands of spurious edges.
**Why it happens:** Any `@Autowired` field with a JDK type still gets recorded if the visitor doesn't filter JDK packages.
**How to avoid:** Apply the same JDK prefix filter from `CallGraphVisitor.isJdkClass()` in `DependencyVisitor`. Also filter `org.springframework.*` framework types if those are not first-class nodes.
**Warning signs:** ExtractionAccumulator dependency edge count is orders of magnitude larger than expected class count.

### Pitfall 4: MERGE Creating Duplicate DEPENDS_ON Edges
**What goes wrong:** Re-running extraction creates duplicate DEPENDS_ON relationship objects stored within ClassNode entities, because SDN's `saveAll()` MERGE semantics apply to nodes but relationship collections get replaced/appended differently.
**Why it happens:** `@RelationshipProperties` relationships with `@Id @GeneratedValue` use generated IDs — SDN cannot detect "same edge as before" without a stable business key. Each `saveAll()` round may create new relationship instances.
**How to avoid:** Two strategies: (a) Clear DEPENDS_ON edges in a pre-extraction step via Cypher `MATCH (:JavaClass)-[r:DEPENDS_ON]->(:JavaClass) DELETE r` before re-running, or (b) use Neo4jClient raw MERGE Cypher for DEPENDS_ON rather than SDN relationship collections. Strategy (a) is simpler given the existing `MATCH (n) DETACH DELETE n` pattern in tests.
**Warning signs:** DEPENDS_ON edge count doubles on second extraction run.

### Pitfall 5: AnnotationNode Business Key Collisions
**What goes wrong:** Two classes in different packages both annotated with `@Service` (from Spring) cause the AnnotationNode for `org.springframework.stereotype.Service` to be persisted twice.
**Why it happens:** If the `AnnotationNode` accumulator uses a non-FQN key (e.g., simple name), it creates separate entries. If FQN is used, MERGE correctly deduplicates.
**How to avoid:** Always use the fully qualified annotation name as the AnnotationNode business key. Only create one AnnotationNode per unique FQN; multiple HAS_ANNOTATION edges from different ClassNodes can all point to the same AnnotationNode.
**Warning signs:** Duplicate AnnotationNode entries for the same annotation FQN in the graph.

### Pitfall 6: Module Detection Ambiguity in Single-Module Projects
**What goes wrong:** Trying to detect "Gradle subprojects" in a single-module project returns nothing, and the code fails to create any ModuleNode.
**Why it happens:** The module detection strategy depends on whether the target codebase is a multi-module Gradle build.
**How to avoid:** Implement a fallback: if no Gradle subprojects are detected in the source root, treat the source root directory itself as a single module. The module name is the directory name.
**Warning signs:** Zero ModuleNodes created after extraction on a single-module codebase.

---

## Code Examples

### Neo4j Schema Constraints for New Node Types
```java
// Source: established pattern from Neo4jSchemaInitializer.java
// Extend the run() method with:
createConstraint(
    "java_annotation_fqn_unique",
    "CREATE CONSTRAINT java_annotation_fqn_unique IF NOT EXISTS"
        + " FOR (n:JavaAnnotation) REQUIRE n.fullyQualifiedName IS UNIQUE");

createConstraint(
    "java_package_name_unique",
    "CREATE CONSTRAINT java_package_name_unique IF NOT EXISTS"
        + " FOR (n:JavaPackage) REQUIRE n.packageName IS UNIQUE");

createConstraint(
    "java_module_name_unique",
    "CREATE CONSTRAINT java_module_name_unique IF NOT EXISTS"
        + " FOR (n:JavaModule) REQUIRE n.moduleName IS UNIQUE");

createConstraint(
    "db_table_name_unique",
    "CREATE CONSTRAINT db_table_name_unique IF NOT EXISTS"
        + " FOR (n:DBTable) REQUIRE n.tableName IS UNIQUE");
```

### ExtractionAccumulator Extensions
```java
// Source: established pattern from ExtractionAccumulator.java
// New data holders to add as private final fields:

private final Set<String> serviceClasses = new HashSet<>();
private final Set<String> repositoryClasses = new HashSet<>();
private final Set<String> uiViewClasses = new HashSet<>();
private final Map<String, AnnotationNodeData> annotations = new HashMap<>();
private final Map<String, PackageNodeData> packages = new HashMap<>();
private final Map<String, String> tableMappings = new HashMap<>(); // class FQN → table name
private final List<DependencyEdge> dependencyEdges = new ArrayList<>();
private final List<QueryMethodRecord> queryMethods = new ArrayList<>();

// Records for new data types:
public record AnnotationNodeData(String fqn, String simpleName, String packageName) {}
public record PackageNodeData(String packageName, String simpleName, String moduleName) {}
public record DependencyEdge(String fromFqn, String toFqn, String injectionType, String fieldName) {}
public record QueryMethodRecord(String methodId, String declaringClassFqn) {}
```

### ExtractionService Extension Points
```java
// Source: ExtractionService.java (lines 95–116 — the visitor execution loop)
// Add new visitors alongside existing ones:
DependencyVisitor dependencyVisitor = new DependencyVisitor();
JpaPatternVisitor jpaPatternVisitor = new JpaPatternVisitor();

for (SourceFile sourceFile : sourceFiles) {
    try {
        classMetadataVisitor.visit(sourceFile, accumulator);
        callGraphVisitor.visit(sourceFile, accumulator);
        vaadinPatternVisitor.visit(sourceFile, accumulator);
        dependencyVisitor.visit(sourceFile, accumulator);   // NEW
        jpaPatternVisitor.visit(sourceFile, accumulator);   // NEW
    } catch (Exception e) {
        // ... existing error handling
    }
}

// Map accumulator data to entity objects (extended mapper handles new types)
List<ClassNode> classNodes = mapper.mapToClassNodes(accumulator);

// Persist class nodes (and new annotation/package/module/DBTable nodes via mapper)
classNodeRepository.saveAll(classNodes);
annotationNodeRepository.saveAll(mapper.mapToAnnotationNodes(accumulator));
packageNodeRepository.saveAll(mapper.mapToPackageNodes(accumulator));
moduleNodeRepository.saveAll(mapper.mapToModuleNodes(accumulator));
dbTableNodeRepository.saveAll(mapper.mapToDBTableNodes(accumulator));

log.info("Persisted {} class nodes to Neo4j", classNodes.size());

// Run post-extraction linking pass (separate transactional call)
LinkingResult linkingResult = linkingService.linkAllRelationships(accumulator);
log.info("Linking pass: {} EXTENDS, {} IMPLEMENTS, {} DEPENDS_ON, {} MAPS_TO_TABLE edges",
    linkingResult.extendsEdges(), linkingResult.implementsEdges(),
    linkingResult.dependsOnEdges(), linkingResult.mapsToTableEdges());
```

### REST API Controller Skeleton (com.esmp.graph)
```java
// Source: CONTEXT.md API design decisions + ExtractionController.java pattern
@RestController
@RequestMapping("/api/graph")
public class GraphQueryController {

    private final GraphQueryService graphQueryService;

    public GraphQueryController(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    /** Returns full structural context for a class by fully qualified name. */
    @GetMapping("/class/{fqn}")
    public ResponseEntity<ClassStructureResponse> getClassStructure(
            @PathVariable String fqn) {
        return graphQueryService.findClassStructure(fqn)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** Returns the full inheritance chain for a class. */
    @GetMapping("/class/{fqn}/inheritance")
    public ResponseEntity<InheritanceChainResponse> getInheritanceChain(
            @PathVariable String fqn) {
        return ResponseEntity.ok(graphQueryService.findInheritanceChain(fqn));
    }

    /** Returns all Service classes that directly or transitively depend on a given Repository. */
    @GetMapping("/repository/{fqn}/service-dependents")
    public ResponseEntity<DependencyResponse> getServiceDependents(
            @PathVariable String fqn) {
        return ResponseEntity.ok(graphQueryService.findTransitiveServiceDependents(fqn));
    }

    /** Simple name search — returns list of matching class FQNs. */
    @GetMapping("/search")
    public ResponseEntity<List<String>> searchBySimpleName(
            @RequestParam String name) {
        return ResponseEntity.ok(graphQueryService.searchBySimpleName(name));
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Neo4j OGM (legacy) | Spring Data Neo4j 7 with @Node/@RelationshipProperties | SDN 7 (2021+) | Business-key @Id + @Version replaces OGM's internal ID approach; MERGE semantics are stable |
| Derived queries only | @Query with raw Cypher for complex patterns | SDN 7 | Enables variable-length path queries that derived queries cannot express |
| Neo4j path return type from @Query | Neo4jClient with manual mappedBy() | SDN 7 | Paths cannot be mapped by SDN; Neo4jClient provides full control |
| javax.persistence.* | jakarta.persistence.* | Spring Boot 3 / Jakarta EE 9 | Target codebase uses javax.* (legacy), new visitors must handle BOTH javax and jakarta annotation FQNs |

**Deprecated/outdated:**
- `javax.persistence.*` annotations: ESMP runtime uses jakarta, but the **target codebase being analyzed** likely uses `javax.persistence.*` (Vaadin 7 era). JpaPatternVisitor must detect both `javax.persistence.Entity` and `jakarta.persistence.Entity` to be forward-compatible.

---

## Open Questions

1. **DEPENDS_ON edge deduplication strategy on re-extraction**
   - What we know: `@RelationshipProperties` with `@Id @GeneratedValue` cannot be MERGE'd by SDN — each saveAll() may append new relationship instances
   - What's unclear: Whether the project's idempotency requirement (from ExtractionService Javadoc) extends to relationship collections, or whether a pre-extraction DELETE of structural edges is acceptable
   - Recommendation: Use raw Cypher MERGE in `LinkingService` for all new relationship types (EXTENDS, IMPLEMENTS, DEPENDS_ON, MAPS_TO_TABLE) rather than SDN relationship collections, which sidesteps the deduplication issue entirely

2. **BINDS_TO edge source identification**
   - What we know: BINDS_TO links a Vaadin View/Form to the entity/DTO it binds (via BeanFieldGroup, FieldGroup). VaadinPatternVisitor already detects BeanFieldGroup usage.
   - What's unclear: The exact field type of the BeanFieldGroup's bound entity (generic type parameter) — OpenRewrite type resolution of generic parameters is sometimes incomplete
   - Recommendation: Detect the type argument of `BeanFieldGroup<MyEntity>` via `JavaType.Parameterized.getTypeParameters()` if type-resolved; fall back to the field name heuristic if generic type is unresolved

3. **Constructor injection without @Autowired (single-constructor Spring convention)**
   - What we know: Spring 4.3+ supports implicit constructor injection for single-constructor classes without `@Autowired`
   - What's unclear: Whether the target legacy Vaadin 7 codebase uses single-constructor implicit injection or explicit `@Autowired`
   - Recommendation: Initially detect only explicit `@Autowired`/`@Inject` constructor injection. Add single-constructor detection as a follow-up if coverage is insufficient.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) via spring-boot-starter-test |
| Config file | No explicit config — `useJUnitPlatform()` in build.gradle.kts |
| Quick run command | `./gradlew test --tests "com.esmp.graph.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| CKG-01 | After extraction, graph contains AnnotationNode, PackageNode, ModuleNode, DBTableNode, and ClassNode with Service/Repository/UIView labels | integration | `./gradlew test --tests "com.esmp.extraction.GraphNodeIntegrationTest"` | Wave 0 |
| CKG-01 | After extraction, SampleEntity class has DBTableNode linked via MAPS_TO_TABLE with tableName="customers" | integration | `./gradlew test --tests "com.esmp.extraction.GraphNodeIntegrationTest#entityClassHasTableMapping"` | Wave 0 |
| CKG-01 | After extraction, SampleService ClassNode has "Service" extra label | integration | `./gradlew test --tests "com.esmp.extraction.GraphNodeIntegrationTest#serviceClassHasServiceLabel"` | Wave 0 |
| CKG-02 | After extraction, EXTENDS/IMPLEMENTS edges exist between ClassNodes | integration | `./gradlew test --tests "com.esmp.extraction.GraphRelationshipIntegrationTest#extendsEdgesExist"` | Wave 0 |
| CKG-02 | After extraction, DEPENDS_ON edge exists from SampleService to SampleRepository | integration | `./gradlew test --tests "com.esmp.extraction.GraphRelationshipIntegrationTest#dependsOnEdgeExists"` | Wave 0 |
| CKG-02 | After extraction, MAPS_TO_TABLE edge exists from SampleEntity to DBTableNode("customers") | integration | `./gradlew test --tests "com.esmp.extraction.GraphRelationshipIntegrationTest#mapsToTableEdgeExists"` | Wave 0 |
| CKG-03 | GET /api/graph/class/{fqn} returns 200 with non-empty methods and fields arrays | integration | `./gradlew test --tests "com.esmp.graph.GraphQueryControllerTest#getClassStructureReturns200"` | Wave 0 |
| CKG-03 | GET /api/graph/class/{fqn}/inheritance returns inheritance chain list | integration | `./gradlew test --tests "com.esmp.graph.GraphQueryControllerTest#getInheritanceChainReturnsChain"` | Wave 0 |
| CKG-03 | GET /api/graph/repository/{fqn}/service-dependents returns SampleService | integration | `./gradlew test --tests "com.esmp.graph.GraphQueryControllerTest#getServiceDependentsReturnsSampleService"` | Wave 0 |
| CKG-03 | Idempotency: re-running extraction does not duplicate graph edges | integration | `./gradlew test --tests "com.esmp.extraction.GraphRelationshipIntegrationTest#reExtractionDoesNotDuplicateEdges"` | Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.esmp.extraction.*" --tests "com.esmp.graph.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/esmp/extraction/GraphNodeIntegrationTest.java` — covers CKG-01 (new node types in graph)
- [ ] `src/test/java/com/esmp/extraction/GraphRelationshipIntegrationTest.java` — covers CKG-02 (new relationship types)
- [ ] `src/test/java/com/esmp/graph/GraphQueryControllerTest.java` — covers CKG-03 (REST API endpoints)
- [ ] Extend `src/test/resources/fixtures/` with test fixtures that have explicit EXTENDS/IMPLEMENTS relationships for linking pass tests

---

## Sources

### Primary (HIGH confidence)
- Project source code (ExtractionAccumulator.java, ClassNode.java, CallsRelationship.java, VaadinPatternVisitor.java, ExtractionService.java, Neo4jSchemaInitializer.java) — existing patterns directly verified by reading production code
- [MERGE - Cypher Manual](https://neo4j.com/docs/cypher-manual/current/clauses/merge/) — idempotent MERGE semantics for nodes and relationships
- [Variable-length patterns - Cypher Manual](https://neo4j.com/docs/cypher-manual/current/patterns/variable-length-patterns/) — `*1..` syntax for transitive dependency queries

### Secondary (MEDIUM confidence)
- [Custom queries :: Spring Data Neo4j](https://docs.spring.io/spring-data/neo4j/reference/appendix/custom-queries.html) — @Query annotation patterns and SDN path mapping limitations
- [Mapping a path query in Spring Data Neo4j](https://lankydan.dev/mapping-a-path-query-in-spring-data-neo4j/) — confirmed SDN path mapping limitation, Neo4jClient workaround

### Tertiary (LOW confidence)
- None — all critical claims verified by primary sources (project code + official docs)

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in project, verified from build.gradle.kts
- Architecture patterns: HIGH — patterns verified from production code in Phase 2; new code follows identical structure
- Pitfalls: HIGH — MERGE deduplication pitfall verified from official Neo4j docs; SDN path mapping limitation verified from official SDN docs and community sources; others derived from reading actual codebase
- Cypher queries: MEDIUM — syntax verified against official Cypher Manual; actual runtime behavior on this specific graph schema requires integration test validation

**Research date:** 2026-03-04
**Valid until:** 2026-09-04 (stable ecosystem — Spring Data Neo4j and Cypher syntax change slowly)
