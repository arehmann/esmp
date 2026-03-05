package com.esmp.extraction.application;

import com.esmp.extraction.model.AnnotationNode;
import com.esmp.extraction.model.BusinessTermNode;
import com.esmp.extraction.model.CallsRelationship;
import com.esmp.extraction.model.ClassNode;
import com.esmp.extraction.model.ContainsComponentRelationship;
import com.esmp.extraction.model.DBTableNode;
import com.esmp.extraction.model.FieldNode;
import com.esmp.extraction.model.MethodNode;
import com.esmp.extraction.model.ModuleNode;
import com.esmp.extraction.model.PackageNode;
import com.esmp.extraction.visitor.ExtractionAccumulator;
import com.esmp.extraction.visitor.ExtractionAccumulator.AnnotationData;
import com.esmp.extraction.visitor.ExtractionAccumulator.BusinessTermData;
import com.esmp.extraction.visitor.ExtractionAccumulator.CallEdge;
import com.esmp.extraction.visitor.ExtractionAccumulator.ClassNodeData;
import com.esmp.extraction.visitor.ExtractionAccumulator.ComponentEdge;
import com.esmp.extraction.visitor.ExtractionAccumulator.FieldNodeData;
import com.esmp.extraction.visitor.ExtractionAccumulator.MethodNodeData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Maps extracted accumulator data into Spring Data Neo4j {@code @Node} entity objects.
 *
 * <p>The mapper is responsible for assembling the full entity graph in-memory — class nodes with
 * their method children, field children, CALLS relationships between methods, and
 * CONTAINS_COMPONENT relationships between class nodes — so that a single {@code saveAll()} call on
 * the {@link com.esmp.extraction.persistence.ClassNodeRepository} persists the entire graph in one
 * transaction.
 */
@Component
public class AccumulatorToModelMapper {

  /**
   * Converts an {@link ExtractionAccumulator} into a list of fully-wired {@link ClassNode} objects
   * ready for persistence.
   *
   * @param acc the accumulator populated by visitor traversal
   * @return list of ClassNode entities with nested MethodNode, FieldNode, and relationship objects
   */
  public List<ClassNode> mapToClassNodes(ExtractionAccumulator acc) {
    // Step 1 — build all MethodNode entities indexed by methodId (needed for CALLS wiring)
    Map<String, MethodNode> methodNodesByMethodId = new HashMap<>();
    for (MethodNodeData mData : acc.getMethods().values()) {
      MethodNode methodNode = new MethodNode(mData.methodId());
      methodNode.setSimpleName(mData.simpleName());
      methodNode.setReturnType(mData.returnType());
      methodNode.setDeclaringClass(mData.declaringClass());
      methodNode.setParameterTypes(new ArrayList<>(mData.parameterTypes()));
      methodNode.setAnnotations(new ArrayList<>(mData.annotations()));
      methodNode.setModifiers(new ArrayList<>(mData.modifiers()));
      methodNode.setConstructor(mData.isConstructor());
      methodNodesByMethodId.put(mData.methodId(), methodNode);
    }

    // Step 2 — wire CALLS relationships onto each MethodNode
    for (CallEdge edge : acc.getCallEdges()) {
      MethodNode caller = methodNodesByMethodId.get(edge.callerMethodId());
      MethodNode callee = methodNodesByMethodId.get(edge.calleeMethodId());
      if (caller != null && callee != null) {
        CallsRelationship callRel = new CallsRelationship(callee);
        callRel.setCallerMethodId(edge.callerMethodId());
        callRel.setCallSiteFile(edge.sourceFile());
        callRel.setCallSiteLine(edge.lineNumber());
        caller.getCallsOut().add(callRel);
      }
    }

    // Step 3 — build FieldNode entities indexed by fieldId
    Map<String, FieldNode> fieldNodesByFieldId = new HashMap<>();
    for (FieldNodeData fData : acc.getFields().values()) {
      FieldNode fieldNode = new FieldNode(fData.fieldId());
      fieldNode.setSimpleName(fData.simpleName());
      fieldNode.setFieldType(fData.fieldType());
      fieldNode.setDeclaringClass(fData.declaringClass());
      fieldNode.setAnnotations(new ArrayList<>(fData.annotations()));
      fieldNode.setModifiers(new ArrayList<>(fData.modifiers()));
      fieldNodesByFieldId.put(fData.fieldId(), fieldNode);
    }

    // Step 4 — build ClassNode entities; group children by declaring class
    Map<String, List<MethodNode>> methodsByClass = new HashMap<>();
    for (MethodNode mn : methodNodesByMethodId.values()) {
      methodsByClass.computeIfAbsent(mn.getDeclaringClass(), k -> new ArrayList<>()).add(mn);
    }

    Map<String, List<FieldNode>> fieldsByClass = new HashMap<>();
    for (FieldNode fn : fieldNodesByFieldId.values()) {
      fieldsByClass.computeIfAbsent(fn.getDeclaringClass(), k -> new ArrayList<>()).add(fn);
    }

    // Step 5 — group ComponentEdge by parentClassFqn
    Map<String, List<ComponentEdge>> componentEdgesByParent = new HashMap<>();
    for (ComponentEdge ce : acc.getComponentEdges()) {
      componentEdgesByParent.computeIfAbsent(ce.parentClassFqn(), k -> new ArrayList<>()).add(ce);
    }

    // Step 6 — build ClassNode index (needed for CONTAINS_COMPONENT target lookup)
    Map<String, ClassNode> classNodesByFqn = new HashMap<>();
    for (ClassNodeData cData : acc.getClasses().values()) {
      ClassNode classNode = new ClassNode(cData.fqn());
      classNode.setSimpleName(cData.simpleName());
      classNode.setPackageName(cData.packageName());
      classNode.setAnnotations(new ArrayList<>(cData.annotations()));
      classNode.setModifiers(new ArrayList<>(cData.modifiers()));
      classNode.setInterface(cData.isInterface());
      classNode.setAbstract(cData.isAbstract());
      classNode.setEnum(cData.isEnum());
      classNode.setSuperClass(cData.superClass());
      classNode.setImplementedInterfaces(new ArrayList<>(cData.implementedInterfaces()));
      classNode.setSourceFilePath(cData.sourceFilePath());
      classNode.setContentHash(cData.contentHash());

      // Apply Vaadin secondary labels
      Set<String> extraLabels = new HashSet<>();
      if (acc.getVaadinViews().contains(cData.fqn())) {
        extraLabels.add("VaadinView");
      }
      if (acc.getVaadinComponents().contains(cData.fqn())) {
        extraLabels.add("VaadinComponent");
      }
      if (acc.getVaadinDataBindings().contains(cData.fqn())) {
        extraLabels.add("VaadinDataBinding");
      }

      // Apply Phase 3 stereotype labels
      if (acc.getServiceClasses().contains(cData.fqn())) {
        extraLabels.add("Service");
      }
      if (acc.getRepositoryClasses().contains(cData.fqn())) {
        extraLabels.add("Repository");
      }
      if (acc.getUIViewClasses().contains(cData.fqn())) {
        extraLabels.add("UIView");
      }

      classNode.setExtraLabels(extraLabels);

      // Attach method children
      classNode.setMethods(methodsByClass.getOrDefault(cData.fqn(), new ArrayList<>()));

      // Attach field children
      classNode.setFields(fieldsByClass.getOrDefault(cData.fqn(), new ArrayList<>()));

      classNodesByFqn.put(cData.fqn(), classNode);
    }

    // Step 7 — wire CONTAINS_COMPONENT relationships using the ClassNode index
    for (Map.Entry<String, List<ComponentEdge>> entry : componentEdgesByParent.entrySet()) {
      ClassNode parent = classNodesByFqn.get(entry.getKey());
      if (parent == null) {
        continue;
      }
      List<ContainsComponentRelationship> componentRels =
          entry.getValue().stream()
              .map(
                  ce -> {
                    // Look up existing child ClassNode; if not found, create a stub with just FQN
                    ClassNode child =
                        classNodesByFqn.computeIfAbsent(
                            ce.childClassFqn(), fqn -> new ClassNode(fqn));
                    ContainsComponentRelationship rel = new ContainsComponentRelationship(child);
                    rel.setParentComponentType(ce.parentType());
                    rel.setChildComponentType(ce.childType());
                    return rel;
                  })
              .collect(Collectors.toList());
      parent.setComponentChildren(componentRels);
    }

    return new ArrayList<>(classNodesByFqn.values());
  }

  /**
   * Maps annotation data from the accumulator into {@link AnnotationNode} entities.
   *
   * @param acc the accumulator populated by visitor traversal
   * @return list of AnnotationNode entities ready for {@code saveAll()}
   */
  public List<AnnotationNode> mapToAnnotationNodes(ExtractionAccumulator acc) {
    List<AnnotationNode> result = new ArrayList<>();
    for (AnnotationData data : acc.getAnnotations().values()) {
      AnnotationNode node = new AnnotationNode(data.fqn());
      node.setSimpleName(data.simpleName());
      node.setPackageName(data.packageName());
      result.add(node);
    }
    return result;
  }

  /**
   * Derives unique {@link PackageNode} entities from the class data in the accumulator.
   *
   * <p>CONTAINS_CLASS relationships are NOT wired here — the {@code LinkingService} handles them
   * via Cypher MERGE to avoid circular SDN save issues.
   *
   * @param acc the accumulator populated by visitor traversal
   * @return list of PackageNode entities (one per unique package name) ready for {@code saveAll()}
   */
  public List<PackageNode> mapToPackageNodes(ExtractionAccumulator acc) {
    Map<String, PackageNode> byPackageName = new HashMap<>();
    for (ClassNodeData cData : acc.getClasses().values()) {
      String pkgName = cData.packageName();
      if (pkgName != null && !pkgName.isBlank()) {
        byPackageName.computeIfAbsent(pkgName, name -> {
          PackageNode node = new PackageNode(name);
          // simpleName = last segment
          int lastDot = name.lastIndexOf('.');
          node.setSimpleName(lastDot >= 0 ? name.substring(lastDot + 1) : name);
          return node;
        });
      }
    }
    return new ArrayList<>(byPackageName.values());
  }

  /**
   * Creates a single {@link ModuleNode} representing the source root being extracted.
   *
   * <p>For single-module projects the module name is the last path segment of {@code sourceRoot}.
   * CONTAINS_PACKAGE relationships are NOT wired here — handled by {@code LinkingService}.
   *
   * @param acc the accumulator populated by visitor traversal
   * @param sourceRoot absolute path to the Java source root directory
   * @return list containing exactly one ModuleNode for the current source root
   */
  public List<ModuleNode> mapToModuleNodes(ExtractionAccumulator acc, String sourceRoot) {
    if (acc.getClasses().isEmpty()) {
      return List.of();
    }
    String moduleName = sourceRoot != null && !sourceRoot.isBlank()
        ? Path.of(sourceRoot).getFileName().toString()
        : "unknown-module";
    ModuleNode node = new ModuleNode(moduleName);
    node.setSourceRoot(sourceRoot);
    node.setMultiModuleSubproject(false);
    return List.of(node);
  }

  /**
   * Maps table mapping data from the accumulator into {@link DBTableNode} entities.
   *
   * @param acc the accumulator populated by visitor traversal
   * @return list of DBTableNode entities (one per unique table name) ready for {@code saveAll()}
   */
  public List<DBTableNode> mapToDBTableNodes(ExtractionAccumulator acc) {
    // Deduplicate by table name (which is already lowercased in the accumulator)
    Map<String, DBTableNode> byTableName = new HashMap<>();
    for (String tableName : acc.getTableMappings().values()) {
      byTableName.computeIfAbsent(tableName, name -> new DBTableNode(name));
    }
    return new ArrayList<>(byTableName.values());
  }

  /**
   * Maps business term data from the accumulator into {@link BusinessTermNode} entities.
   *
   * <p>Terms from the visitor pass (class names, enum names/constants, Javadoc) are merged with
   * terms derived from DB table names (from {@code acc.getTableMappings()}, populated by
   * JpaPatternVisitor). DB table terms are added with {@code "DB_TABLE"} source type; existing
   * terms from the visitor pass take precedence (termId deduplication).
   *
   * <p>Heuristic criticality seeding: financial and security terms get {@code "High"} criticality
   * and {@code "Moderate"} migration sensitivity; all others default to {@code "Low"} / {@code
   * "None"}.
   *
   * @param acc the accumulator populated by visitor traversal
   * @return list of BusinessTermNode entities ready for curated-guard MERGE persistence
   */
  public List<BusinessTermNode> mapToBusinessTermNodes(ExtractionAccumulator acc) {
    // Use a map to merge visitor-extracted terms with DB table terms (visitor wins on conflict)
    Map<String, BusinessTermNode> byTermId = new HashMap<>();

    // Step 1 — map visitor-extracted terms (class names, enum names/constants)
    for (BusinessTermData data : acc.getBusinessTerms().values()) {
      BusinessTermNode node = createBusinessTermNode(data);
      byTermId.put(data.termId, node);
    }

    // Step 2 — add DB table name terms (skip if already present from visitor pass)
    for (Map.Entry<String, String> entry : acc.getTableMappings().entrySet()) {
      String entityFqn = entry.getKey();
      String tableName = entry.getValue(); // already lowercased
      for (String part : tableName.split("_")) {
        String termId = part.toLowerCase().trim();
        if (termId.length() <= 2) continue;
        if (!byTermId.containsKey(termId)) {
          BusinessTermNode node = new BusinessTermNode(termId);
          node.setDisplayName(capitalize(part));
          node.setDefinition(null);
          node.setCriticality(seedCriticality(termId));
          node.setMigrationSensitivity(seedCriticality(termId).equals("High") ? "Moderate" : "None");
          node.setCurated(false);
          node.setStatus("auto");
          node.setSourceType("DB_TABLE");
          node.setPrimarySourceFqn(entityFqn);
          node.setUsageCount(1);
          byTermId.put(termId, node);
        }
      }
    }

    return new ArrayList<>(byTermId.values());
  }

  private BusinessTermNode createBusinessTermNode(BusinessTermData data) {
    BusinessTermNode node = new BusinessTermNode(data.termId);
    node.setDisplayName(data.displayName);
    node.setDefinition(data.javadocSeed);
    String criticality = seedCriticality(data.termId);
    node.setCriticality(criticality);
    node.setMigrationSensitivity(criticality.equals("High") ? "Moderate" : "None");
    node.setCurated(false);
    node.setStatus("auto");
    node.setSourceType(data.sourceType);
    node.setPrimarySourceFqn(data.primarySourceFqn);
    node.setUsageCount(data.allSourceFqns.size());
    return node;
  }

  /**
   * Heuristic criticality seed: returns "High" for financial and security-related terms, "Low" for
   * all others.
   *
   * @param termId lowercased term identifier
   * @return "High" or "Low"
   */
  static String seedCriticality(String termId) {
    // Financial keywords
    Set<String> financial = Set.of(
        "payment", "invoice", "billing", "ledger", "transaction", "price", "cost",
        "fee", "amount", "currency", "refund", "credit", "debit");
    // Auth/security keywords
    Set<String> security = Set.of(
        "auth", "login", "password", "credential", "token", "session", "permission",
        "role", "encrypt", "decrypt", "security", "access");
    return (financial.contains(termId) || security.contains(termId)) ? "High" : "Low";
  }

  private static String capitalize(String word) {
    if (word == null || word.isEmpty()) return word;
    return Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase();
  }
}
