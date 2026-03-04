---
status: diagnosed
trigger: "Investigate why @Service/@Repository stereotype labels are not being applied to ClassNode in Neo4j"
created: 2026-03-04T00:00:00Z
updated: 2026-03-04T00:00:00Z
---

## Current Focus

hypothesis: CONFIRMED — two independent bugs compound to cause the symptom
test: full static code trace across visitor -> accumulator -> mapper -> SDN query -> search response
expecting: n/a — root cause confirmed
next_action: report diagnosis

## Symptoms

expected: SampleService node in Neo4j has `Service` label; search returns `labels: ["Service"]`; findServiceDependents query returns results
actual: search shows `labels: []`; findServiceDependents returns empty
errors: none (silent failure)
reproduction: trigger extraction then call GET /api/graph/search?name=SampleService and GET /api/graph/dependents/{repoFqn}
started: always (design-time bug)

## Eliminated

- hypothesis: ClassMetadataVisitor fails to detect the @Service annotation
  evidence: visitor correctly iterates cd.getLeadingAnnotations(), calls resolveAnnotationName() which returns FQN when type is resolved, checks SERVICE_STEREOTYPES set, and calls acc.markAsService(fqn). If annotation FQN is fully resolved this path works.
  timestamp: 2026-03-04

- hypothesis: ExtractionAccumulator loses the stereotype data
  evidence: serviceClasses is a plain HashSet, markAsService() adds to it, getServiceClasses() returns unmodifiable view. No mutation or clearing happens elsewhere.
  timestamp: 2026-03-04

- hypothesis: AccumulatorToModelMapper fails to read stereotype sets or add to extraLabels
  evidence: Lines 134-139 of AccumulatorToModelMapper.java correctly check acc.getServiceClasses().contains(cData.fqn()) and add "Service" to extraLabels. ClassNode.setExtraLabels(extraLabels) is called on line 144. This path is correct.
  timestamp: 2026-03-04

- hypothesis: @DynamicLabels field is misconfigured on ClassNode
  evidence: ClassNode.extraLabels is a Set<String> annotated with @DynamicLabels from spring-data-neo4j. This is the correct annotation and type for SDN dynamic label support.
  timestamp: 2026-03-04

## Evidence

- timestamp: 2026-03-04
  checked: ClassMetadataVisitor.java lines 88-103 — stereotype detection block
  found: resolveAnnotationName() returns the FQN when type resolution succeeds, but falls back to simple name (e.g. "Service") when it fails (line 214). The SERVICE_STEREOTYPES set contains only FQNs like "org.springframework.stereotype.Service". If classpath is absent and OpenRewrite cannot resolve the annotation type, resolveAnnotationName() returns "Service" (simple name), which does NOT match any entry in SERVICE_STEREOTYPES. markAsService() is therefore never called.
  implication: BUG 1 — stereotype detection silently fails when OpenRewrite type resolution is incomplete (no classpath file provided). This is the primary cause for the accumulator not being populated.

- timestamp: 2026-03-04
  checked: GraphQueryService.java lines 268-284 — searchByName()
  found: The method calls graphQueryRepository.findBySimpleNameContainingIgnoreCase(name), which returns a List<ClassNode> loaded by SDN. Then on line 274 it builds labels from cn.getExtraLabels(). SDN's Neo4jRepository.findBy* derived queries do NOT load @DynamicLabels by default — they load only mapped properties and declared relationships. The @DynamicLabels field is populated during full entity hydration but SDN derived queries return partial projections that may leave extraLabels as the default empty HashSet initialised in the field declaration on ClassNode line 38.
  implication: BUG 2 — even if Neo4j actually stored the Service label on the node, searchByName() reads extraLabels from the in-memory SDN entity returned by the derived query, which is not guaranteed to include dynamic labels. The search response labels: [] can occur here independently of whether the label is stored.

- timestamp: 2026-03-04
  checked: GraphQueryService.java lines 80-86 — findClassStructure() labels extraction
  found: This method uses Neo4jClient with a raw Cypher MATCH and reads labels directly from classValue.asNode().labels(). This path correctly reads all Neo4j node labels from the wire, bypassing SDN entity mapping entirely.
  implication: findClassStructure() reports labels accurately. searchByName() does not. This explains why the structure response shows annotations correctly (stored as a property) while the search response shows labels: [] (reads SDN entity extraLabels which is unreliable from a derived query).

- timestamp: 2026-03-04
  checked: AccumulatorToModelMapper.java lines 107-144 — ClassNode building and extraLabels assignment
  found: The mapper correctly builds extraLabels and calls classNode.setExtraLabels(extraLabels). If the accumulator was populated (i.e. BUG 1 did not prevent it), the ClassNode entity going into saveAll() would have the correct extraLabels set, and SDN would persist those as Neo4j dynamic labels. The mapper itself is not buggy.
  implication: The mapper is not part of the root cause. The two bugs are upstream (visitor) and downstream (search query).

## Resolution

root_cause: |
  TWO independent bugs compound to produce the symptom:

  BUG 1 (primary — prevents label being persisted at all):
  ClassMetadataVisitor.resolveAnnotationName() returns a simple name ("Service") when OpenRewrite
  cannot resolve the annotation type due to a missing classpath file. The SERVICE_STEREOTYPES /
  REPOSITORY_STEREOTYPES sets contain only fully qualified names
  ("org.springframework.stereotype.Service" etc.). When the FQN is unresolvable the simple-name
  fallback never matches, so acc.markAsService() / acc.markAsRepository() are never called, and
  the "Service" / "Repository" label is never added to extraLabels, and therefore never persisted
  to Neo4j as a dynamic label on the node.

  BUG 2 (secondary — hides the label even if it were persisted):
  GraphQueryService.searchByName() retrieves ClassNode entities via the SDN derived query
  findBySimpleNameContainingIgnoreCase(). SDN derived queries do not re-hydrate @DynamicLabels
  from Neo4j — they populate only properties and declared @Relationship fields. The extraLabels
  field is left as the empty HashSet from ClassNode's field initialiser. Consequently the search
  response always returns labels: [] regardless of what labels are stored in the database.

fix: not applied (diagnose-only mode)

verification: n/a

files_changed: []
