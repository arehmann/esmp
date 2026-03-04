---
status: diagnosed
phase: 03-code-knowledge-graph
source: [03-01-SUMMARY.md, 03-02-SUMMARY.md, 03-03-SUMMARY.md, 03-04-SUMMARY.md]
started: 2026-03-04T21:30:00Z
updated: 2026-03-04T22:15:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Extraction Trigger Populates Full Knowledge Graph
expected: Start Docker Compose services. POST to /api/extraction/trigger with sourceRoot pointing to test Java sources. Response returns counts for classCount, methodCount, fieldCount, annotationCount, packageCount, moduleCount, tableCount. At least classCount > 0.
result: pass

### 2. Graph Structure Query Returns Class Details
expected: GET /api/graph/class/{fqn} with a known class FQN. Response includes className, packageName, methods list, fields list, and dependencies list.
result: pass

### 3. Inheritance Chain Query Returns Ancestors
expected: GET /api/graph/class/{fqn}/inheritance with a class that extends another. Response includes the class and its ancestor chain.
result: pass

### 4. Transitive Dependency Query Returns Service Dependencies
expected: GET /api/graph/repository/{fqn}/service-dependents with a repository class. Response lists dependent services with hop count.
result: issue
reported: "Empty services list. The query filters by Service label, but from the search results earlier, SampleService has labels: [] — the stereotype label isn't being applied despite @Service annotation being detected. The DEPENDS_ON edge exists (3 in Neo4j), but the label-based filter finds no match."
severity: major

### 5. Search Endpoint Finds Classes by Name
expected: GET /api/graph/search?name=Sample. Response returns matching classes with FQN, simpleName, packageName. Case-insensitive matching works.
result: pass

### 6. Relationship Edges Created in Neo4j
expected: Neo4j shows multiple relationship types including CALLS, EXTENDS, IMPLEMENTS, DEPENDS_ON, HAS_ANNOTATION, CONTAINS_CLASS, BINDS_TO.
result: issue
reported: "Present (9): DECLARES_METHOD, DECLARES_FIELD, CALLS, CONTAINS_COMPONENT, CONTAINS_CLASS, EXTENDS, DEPENDS_ON, IMPLEMENTS, MAPS_TO_TABLE. Missing: BINDS_TO (VaadinPatternVisitor reported 0 data bindings — Vaadin types not resolved without classpath), HAS_ANNOTATION, QUERIES"
severity: major

### 7. Re-extraction Idempotency
expected: Run POST /api/extraction/trigger a second time. Counts should be the same, no duplicates.
result: pass

## Summary

total: 7
passed: 5
issues: 2
pending: 0
skipped: 0

## Gaps

- truth: "Service-dependents query returns transitive service dependencies for a repository class"
  status: failed
  reason: "User reported: Empty services list. The query filters by Service label, but SampleService has labels: [] — stereotype label not applied despite @Service annotation being detected."
  severity: major
  test: 4
  root_cause: "BUG 1: ClassMetadataVisitor.resolveAnnotationName() returns simple name 'Service' without classpath, but SERVICE_STEREOTYPES contains only FQNs — no match, acc.markAsService() never called. BUG 2: searchByName() reads cn.getExtraLabels() from SDN entity but @DynamicLabels not hydrated by derived queries — always returns empty even if labels existed in DB."
  artifacts:
    - path: "src/main/java/com/esmp/extraction/visitor/ClassMetadataVisitor.java"
      issue: "SERVICE_STEREOTYPES/REPOSITORY_STEREOTYPES only contain FQNs, no simple name fallback"
    - path: "src/main/java/com/esmp/graph/application/GraphQueryService.java"
      issue: "searchByName() uses SDN derived query which doesn't hydrate @DynamicLabels"
  missing:
    - "Add simple names (Service, Repository, Controller, RestController, Component) to stereotype sets as fallback"
    - "Replace searchByName() SDN derived query with Neo4jClient Cypher that reads labels() directly"
  debug_session: ".planning/debug/stereotype-labels-not-applied.md"

- truth: "Graph stores BINDS_TO, HAS_ANNOTATION, QUERIES relationship edges"
  status: failed
  reason: "User reported: Missing BINDS_TO, HAS_ANNOTATION, QUERIES edges. BINDS_TO: VaadinPatternVisitor reported 0 data bindings (Vaadin types not resolved without classpath). HAS_ANNOTATION and QUERIES also absent."
  severity: major
  test: 6
  root_cause: "BINDS_TO: VaadinPatternVisitor.visitNewClass() checks nc.getType() instanceof JavaType.FullyQualified — returns null/Unknown without Vaadin JAR, entire block skipped. No simple-name fallback. QUERIES: linkQueryMethods() looks up declaringClassFqn (repository) in tableMappings (keyed by entity class) — always null, every record skipped. HAS_ANNOTATION: ClassNode.annotations stores simple names ('Entity') but JavaAnnotation.fullyQualifiedName stores FQNs ('javax.persistence.Entity') — Cypher MATCH never finds a match."
  artifacts:
    - path: "src/main/java/com/esmp/extraction/visitor/VaadinPatternVisitor.java"
      issue: "visitNewClass() has no simple-name fallback for unresolved Vaadin types"
    - path: "src/main/java/com/esmp/extraction/application/LinkingService.java"
      issue: "linkQueryMethods() uses repository FQN to look up tableMappings keyed by entity FQN — always misses"
    - path: "src/main/java/com/esmp/extraction/application/LinkingService.java"
      issue: "linkAnnotations() Cypher UNWINDs c.annotations (simple names) but MATCHes JavaAnnotation.fullyQualifiedName (FQNs) — never matches"
  missing:
    - "VaadinPatternVisitor: add simple-name fallback for BeanFieldGroup/FieldGroup detection"
    - "linkQueryMethods(): resolve repository→entity via JpaRepository generic type, then look up entity in tableMappings"
    - "linkAnnotations(): normalize annotation names to FQNs before MATCH, or store FQNs in ClassNode.annotations"
  debug_session: ".planning/debug/missing-relationship-edges.md"
