package com.esmp.extraction.application;

import com.esmp.extraction.model.CallsRelationship;
import com.esmp.extraction.model.ClassNode;
import com.esmp.extraction.model.ContainsComponentRelationship;
import com.esmp.extraction.model.FieldNode;
import com.esmp.extraction.model.MethodNode;
import com.esmp.extraction.visitor.ExtractionAccumulator;
import com.esmp.extraction.visitor.ExtractionAccumulator.CallEdge;
import com.esmp.extraction.visitor.ExtractionAccumulator.ClassNodeData;
import com.esmp.extraction.visitor.ExtractionAccumulator.ComponentEdge;
import com.esmp.extraction.visitor.ExtractionAccumulator.FieldNodeData;
import com.esmp.extraction.visitor.ExtractionAccumulator.MethodNodeData;
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
}
