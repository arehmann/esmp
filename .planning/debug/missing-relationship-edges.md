---
status: diagnosed
trigger: "Investigate why BINDS_TO, HAS_ANNOTATION, and QUERIES relationship edges are missing from Neo4j after extraction."
created: 2026-03-04T00:00:00Z
updated: 2026-03-04T00:00:00Z
---

## Current Focus

hypothesis: Three independent root causes found — one per missing relationship type.
test: Static code reading and data-flow tracing from fixture files through visitors to Cypher queries.
expecting: N/A — confirmed.
next_action: Return diagnosis to caller.

## Symptoms

expected: Neo4j shows BINDS_TO, HAS_ANNOTATION, and QUERIES relationship edges after extraction.
actual: All three relationship types are absent. Response shows vaadinDataBindingCount=0, annotationCount=2, tableCount=1.
errors: No runtime errors reported — silent failures.
reproduction: POST /api/extraction/trigger with sourceRoot=src/test/resources/fixtures
started: Observed on first run with these fixtures.

## Eliminated

- hypothesis: QUERIES edges fail because JpaPatternVisitor does not detect derived query methods.
  evidence: JpaPatternVisitor.isDerivedQueryMethod() lists "findBy" as a prefix and SampleRepository.findByName() matches — the QueryMethodRecord IS produced.
  timestamp: 2026-03-04

- hypothesis: BINDS_TO fails because VaadinPatternVisitor.visitNewClass does not fire for BeanFieldGroup.
  evidence: visitNewClass() is present and handles VAADIN_DATA_BINDING_TYPES. The real block is earlier — at the type resolution guard (instanceof JavaType.FullyQualified).
  timestamp: 2026-03-04

- hypothesis: HAS_ANNOTATION edges fail because annotations are never persisted as JavaAnnotation nodes.
  evidence: annotationCount=2 in the response, meaning AnnotationNode entities ARE persisted. The failure is elsewhere.
  timestamp: 2026-03-04

## Evidence

- timestamp: 2026-03-04
  checked: SampleRepository.java fixture
  found: No @Query annotation on any method. Only findByName() which is a Spring Data derived query method.
  implication: JpaPatternVisitor detects findByName() as a derived query and calls acc.addQueryMethod(methodId, "com.example.sample.SampleRepository").

- timestamp: 2026-03-04
  checked: LinkingService.linkQueryMethods() — the QUERIES resolution logic
  found: |
    String tableName = acc.getTableMappings().get(qm.declaringClassFqn());
    if (tableName == null) {
        log.debug("Skipping QUERIES link for method {} — no table mapping for declaring class {}", ...);
        continue;
    }
  The declaring class is "com.example.sample.SampleRepository". The tableMappings map is keyed by entity class FQN, not repository class FQN.
  implication: SampleRepository is NOT an @Entity, so it has no table mapping. The lookup returns null every time, and the debug log fires but the edge is never created.

- timestamp: 2026-03-04
  checked: SampleEntity.java fixture — what IS in tableMappings
  found: SampleEntity is @Entity with @Table(name="customers"). JpaPatternVisitor adds tableMappings["com.example.sample.SampleEntity"] = "customers".
  implication: The correct table name is known, but it is stored under the entity's FQN, not the repository's FQN.

- timestamp: 2026-03-04
  checked: VaadinPatternVisitor.visitNewClass() guard for BINDS_TO
  found: |
    if (nc.getType() instanceof JavaType.FullyQualified fq
        && VAADIN_DATA_BINDING_TYPES.contains(fq.getFullyQualifiedName())) {
    VAADIN_DATA_BINDING_TYPES = Set.of(
        "com.vaadin.data.fieldgroup.BeanFieldGroup",
        "com.vaadin.data.fieldgroup.FieldGroup",
        "com.vaadin.data.util.BeanItemContainer");
  implication: When no Vaadin JAR is on the classpath at parse time, OpenRewrite cannot attribute the type of `new BeanFieldGroup<>(SampleEntity.class)`. nc.getType() returns null or a JavaType.Unknown — not a JavaType.FullyQualified. The entire if-block is skipped. No BINDS_TO edge or vaadinDataBinding marking is produced. This explains vaadinDataBindingCount=0.

- timestamp: 2026-03-04
  checked: VaadinPatternVisitor — is there a simple-name fallback for visitNewClass()?
  found: No. The visitNewClass() block has no fallback. Compare this with detectDataBindingCall() and isVaadinUiMethodCall() which have explicit heuristic fallbacks. The new-instance path is strictly FQN-only.
  implication: Without Vaadin JARs on the classpath, BINDS_TO detection is completely blind.

- timestamp: 2026-03-04
  checked: LinkingService.linkAnnotations() Cypher query and its guard
  found: |
    if (acc.getAnnotations().isEmpty()) {
        return 0;
    }
    // Cypher:
    MATCH (c:JavaClass)
    WHERE c.annotations IS NOT NULL AND size(c.annotations) > 0
    UNWIND c.annotations AS annotFqn
    MATCH (a:JavaAnnotation {fullyQualifiedName: annotFqn})
    MERGE (c)-[r:HAS_ANNOTATION]->(a)
  The guard passes (acc.getAnnotations() has 2 entries — @Entity and @Repository).
  implication: The Cypher executes. The MATCH (a:JavaAnnotation) can only succeed if the annotFqn stored on the class node matches the fullyQualifiedName property of a persisted JavaAnnotation node.

- timestamp: 2026-03-04
  checked: How class annotations are stored — ClassMetadataVisitor.resolveAnnotationName()
  found: |
    private static String resolveAnnotationName(J.Annotation annotation) {
        if (annotation.getAnnotationType() != null
            && annotation.getAnnotationType().getType() instanceof JavaType.FullyQualified fq) {
            String fqn = fq.getFullyQualifiedName();
            if (fqn != null && !fqn.startsWith("<")) {
                return fqn;
            }
        }
        return annotation.getSimpleName(); // fallback: returns "Entity", "Repository", "Table", etc.
    }
  When JPA/Spring jars are NOT on the parser classpath, type resolution fails and the fallback fires.
  SampleEntity would store annotations = ["Entity", "Table"] (simple names, NOT FQNs).
  SampleRepository would store annotations = ["Repository"] (simple name).
  implication: The class node's `annotations` list contains simple names ("Entity", "Repository"), not FQNs.

- timestamp: 2026-03-04
  checked: How JavaAnnotation nodes are registered — ClassMetadataVisitor visitClassDeclaration()
  found: |
    if (annotFqn != null && !annotFqn.startsWith("<") && annotFqn.contains(".")) {
        int lastDot = annotFqn.lastIndexOf('.');
        String annotSimple = annotFqn.substring(lastDot + 1);
        String annotPkg = annotFqn.substring(0, lastDot);
        acc.addAnnotation(annotFqn, annotSimple, annotPkg);
    }
  The condition annotFqn.contains(".") FILTERS OUT simple names. So when resolveAnnotationName() returns "Entity" (no dot), addAnnotation() is never called.
  implication: When type resolution fails, NO JavaAnnotation node is registered at all for class-level annotations. BUT annotationCount=2 means some annotations ARE being registered.

- timestamp: 2026-03-04
  checked: JpaPatternVisitor.visitClassDeclaration() — second annotation registration path
  found: |
    for (J.Annotation annotation : annotations) {
        String annotFqn = resolveAnnotationFqn(annotation);
        if (annotFqn != null && !annotFqn.startsWith("<")) {
            String simpleName = annotation.getSimpleName();
            String packageName = extractPackageName(annotFqn);
            acc.addAnnotation(annotFqn, simpleName, packageName);
        }
    }
    Where resolveAnnotationFqn() has a simple-name-to-FQN fallback:
        case "Entity" -> "javax.persistence.Entity";
        case "Table" -> "javax.persistence.Table";
        case "Query" -> "org.springframework.data.jpa.repository.Query";
  implication: JpaPatternVisitor registers "javax.persistence.Entity" and "javax.persistence.Table" as JavaAnnotation nodes (hence annotationCount=2). Those nodes have fullyQualifiedName = "javax.persistence.Entity" / "javax.persistence.Table".

- timestamp: 2026-03-04
  checked: The mismatch between class.annotations list and JavaAnnotation node FQNs
  found: |
    - The JavaAnnotation nodes are persisted with: fullyQualifiedName = "javax.persistence.Entity", "javax.persistence.Table"
    - The class node's `annotations` property stores: ["Entity", "Table"] (simple names — because ClassMetadataVisitor fallback fires)
    - The Cypher MATCH (a:JavaAnnotation {fullyQualifiedName: annotFqn}) is fed annotFqn = "Entity"
    - There is no JavaAnnotation node with fullyQualifiedName = "Entity"
    - MATCH fails → MERGE never runs → zero HAS_ANNOTATION edges
  implication: Root cause confirmed. The class annotations list and the JavaAnnotation node FQNs are produced by different code paths with different fallback strategies, creating an FQN mismatch that silently causes zero MATCH hits.

## Resolution

root_cause: |
  THREE independent root causes:

  1. BINDS_TO (vaadinDataBindingCount=0):
     VaadinPatternVisitor.visitNewClass() has no simple-name fallback for BeanFieldGroup instantiation.
     The check `nc.getType() instanceof JavaType.FullyQualified` fails when Vaadin JARs are absent from
     the parser classpath — nc.getType() returns null/JavaType.Unknown. The entire detection block is
     skipped. Neither the vaadinDataBindings mark nor the BindsToRecord is produced.
     File: src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java, visitNewClass(), lines 126-156.

  2. QUERIES (tableCount=1, but 0 QUERIES edges):
     LinkingService.linkQueryMethods() looks up the query method's declaring class FQN in
     acc.getTableMappings(). For SampleRepository the declaring class is
     "com.example.sample.SampleRepository", but tableMappings only contains entries for @Entity
     classes ("com.example.sample.SampleEntity" → "customers"). A repository interface is never an
     @Entity, so the lookup always returns null, the debug log fires, and no QUERIES edge is created.
     File: src/main/java/com/esmp/extraction/application/LinkingService.java, linkQueryMethods(), lines 230-234.

  3. HAS_ANNOTATION (annotationCount=2, but 0 HAS_ANNOTATION edges):
     FQN mismatch between two independently produced data structures:
     - ClassMetadataVisitor stores annotations on class nodes using resolveAnnotationName() which falls
       back to simple names ("Entity", "Repository") when type resolution fails (no JPA JAR on classpath).
     - JpaPatternVisitor registers JavaAnnotation nodes using its own resolveAnnotationFqn() which maps
       simple names back to FQNs via a hardcoded switch ("Entity" → "javax.persistence.Entity").
     - The Cypher MATCH in linkAnnotations() uses UNWIND c.annotations AS annotFqn, then
       MATCH (a:JavaAnnotation {fullyQualifiedName: annotFqn}). It feeds "Entity" into the MATCH,
       but the persisted node has fullyQualifiedName = "javax.persistence.Entity". No MATCH hits.
       Zero HAS_ANNOTATION edges result.
     Files:
       - src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java, resolveAnnotationName(), line 204-215
       - src/main/java/com/esmp/extraction/visitor/JpaPatternVisitor.java, resolveAnnotationFqn(), lines 131-149
       - src/main/java/com/esmp/extraction/application/LinkingService.java, linkAnnotations() Cypher, lines 277-284

fix: Not applied (goal: find_root_cause_only)
verification: Not performed.
files_changed: []
