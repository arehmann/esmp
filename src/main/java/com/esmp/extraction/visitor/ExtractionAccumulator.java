package com.esmp.extraction.visitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Accumulates all extracted AST data from visitor traversal of OpenRewrite LSTs.
 *
 * <p>This is a plain POJO (not a Spring bean) that is created fresh for each extraction run. It
 * holds maps keyed by stable identifiers so that multiple visitors traversing the same source set
 * can add data independently without creating duplicates.
 *
 * <p>Key conventions:
 *
 * <ul>
 *   <li>Class key: fully qualified name (e.g. {@code com.example.Foo})
 *   <li>Method key: {@code FQN#methodName(ParamType1,ParamType2)} (e.g. {@code
 *       com.example.Foo#save(SampleEntity)})
 *   <li>Field key: {@code FQN#fieldName} (e.g. {@code com.example.Foo#repository})
 * </ul>
 */
public class ExtractionAccumulator {

  // ---------- node data maps ----------

  private final Map<String, ClassNodeData> classes = new HashMap<>();
  private final Map<String, MethodNodeData> methods = new HashMap<>();
  private final Map<String, FieldNodeData> fields = new HashMap<>();

  // ---------- edge lists ----------

  private final List<CallEdge> callEdges = new ArrayList<>();
  private final List<ComponentEdge> componentEdges = new ArrayList<>();

  // ---------- Vaadin label sets ----------

  private final Set<String> vaadinViews = new HashSet<>();
  private final Set<String> vaadinComponents = new HashSet<>();
  private final Set<String> vaadinDataBindings = new HashSet<>();

  // ---------- Phase 3: stereotype sets ----------

  private final Set<String> serviceClasses = new HashSet<>();
  private final Set<String> repositoryClasses = new HashSet<>();
  private final Set<String> uiViewClasses = new HashSet<>();

  // ---------- Phase 3: annotation and table maps ----------

  private final Map<String, AnnotationData> annotations = new HashMap<>();
  private final Map<String, String> tableMappings = new HashMap<>();

  // ---------- Phase 3: edge lists ----------

  private final List<DependencyEdge> dependencyEdges = new ArrayList<>();
  private final List<QueryMethodRecord> queryMethods = new ArrayList<>();
  private final List<BindsToRecord> bindsToEdges = new ArrayList<>();

  // ---------- Phase 5: domain lexicon ----------

  private final Map<String, BusinessTermData> businessTerms = new HashMap<>();

  // ---------- Phase 6: structural risk metrics ----------

  private final Map<String, MethodComplexityData> methodComplexities = new HashMap<>();
  private final Map<String, ClassWriteData> classWriteData = new HashMap<>();

  // =========================================================================
  // Mutation methods
  // =========================================================================

  /**
   * Adds or replaces a class entry.
   *
   * @param fqn fully qualified class name (map key)
   * @param simpleName simple (unqualified) class name
   * @param packageName Java package
   * @param annotations list of annotation FQNs or simple names
   * @param modifiers list of modifier strings (public, abstract, etc.)
   * @param isInterface true if the type is an interface
   * @param isAbstract true if the class is abstract
   * @param isEnum true if the type is an enum
   * @param superClass FQN of the direct superclass, or null
   * @param implementedInterfaces list of interface FQNs
   * @param sourceFilePath source file path for traceability
   * @param contentHash SHA-256 hash of the source file for change detection
   */
  public void addClass(
      String fqn,
      String simpleName,
      String packageName,
      List<String> annotations,
      List<String> modifiers,
      boolean isInterface,
      boolean isAbstract,
      boolean isEnum,
      String superClass,
      List<String> implementedInterfaces,
      String sourceFilePath,
      String contentHash) {
    classes.put(
        fqn,
        new ClassNodeData(
            fqn,
            simpleName,
            packageName,
            annotations,
            modifiers,
            isInterface,
            isAbstract,
            isEnum,
            superClass,
            implementedInterfaces,
            sourceFilePath,
            contentHash));
  }

  /**
   * Adds or replaces a method entry.
   *
   * @param methodId stable method identifier in the form {@code FQN#method(ParamType1,ParamType2)}
   * @param simpleName method simple name
   * @param returnType return type simple or FQN
   * @param declaringClass FQN of the class that declares this method
   * @param parameterTypes ordered list of parameter types
   * @param annotations annotation names present on the method
   * @param modifiers modifier strings
   * @param isConstructor true if this is a constructor
   */
  public void addMethod(
      String methodId,
      String simpleName,
      String returnType,
      String declaringClass,
      List<String> parameterTypes,
      List<String> annotations,
      List<String> modifiers,
      boolean isConstructor) {
    methods.put(
        methodId,
        new MethodNodeData(
            methodId,
            simpleName,
            returnType,
            declaringClass,
            parameterTypes,
            annotations,
            modifiers,
            isConstructor));
  }

  /**
   * Adds or replaces a field entry.
   *
   * @param fieldId stable field identifier in the form {@code FQN#fieldName}
   * @param simpleName field simple name
   * @param fieldType declared field type
   * @param declaringClass FQN of the class that declares this field
   * @param annotations annotation names present on the field
   * @param modifiers modifier strings
   */
  public void addField(
      String fieldId,
      String simpleName,
      String fieldType,
      String declaringClass,
      List<String> annotations,
      List<String> modifiers) {
    fields.put(
        fieldId,
        new FieldNodeData(fieldId, simpleName, fieldType, declaringClass, annotations, modifiers));
  }

  /**
   * Appends a directed call edge.
   *
   * @param callerMethodId methodId of the calling method
   * @param calleeMethodId methodId of the called method
   * @param sourceFile source file containing the call site
   * @param lineNumber line number of the call site
   */
  public void addCall(
      String callerMethodId, String calleeMethodId, String sourceFile, int lineNumber) {
    callEdges.add(new CallEdge(callerMethodId, calleeMethodId, sourceFile, lineNumber));
  }

  /**
   * Appends a CONTAINS_COMPONENT edge between a parent container and a child component.
   *
   * @param parentClassFqn FQN of the parent container class
   * @param childClassFqn FQN of the child component class (from the argument type)
   * @param parentType simple type name of the parent container
   * @param childType simple type name of the child component
   */
  public void addComponentEdge(
      String parentClassFqn, String childClassFqn, String parentType, String childType) {
    componentEdges.add(new ComponentEdge(parentClassFqn, childClassFqn, parentType, childType));
  }

  /** Marks the given class FQN as a Vaadin View (will receive :VaadinView label). */
  public void markAsVaadinView(String fqn) {
    vaadinViews.add(fqn);
  }

  /** Marks the given class FQN as a Vaadin Component user (will receive :VaadinComponent label). */
  public void markAsVaadinComponent(String fqn) {
    vaadinComponents.add(fqn);
  }

  /**
   * Marks the given class FQN as a Vaadin Data Binding user (will receive :VaadinDataBinding
   * label).
   */
  public void markAsVaadinDataBinding(String fqn) {
    vaadinDataBindings.add(fqn);
  }

  // =========================================================================
  // Phase 3: Mutation methods
  // =========================================================================

  /**
   * Marks the given class FQN as a service bean (will receive :ServiceClass stereotype label).
   *
   * @param fqn fully qualified class name
   */
  public void markAsService(String fqn) {
    serviceClasses.add(fqn);
  }

  /**
   * Marks the given class FQN as a repository bean (will receive :RepositoryClass stereotype
   * label).
   *
   * @param fqn fully qualified class name
   */
  public void markAsRepository(String fqn) {
    repositoryClasses.add(fqn);
  }

  /**
   * Marks the given class FQN as a UI view (non-Vaadin-specific general view marker).
   *
   * @param fqn fully qualified class name
   */
  public void markAsUIView(String fqn) {
    uiViewClasses.add(fqn);
  }

  /**
   * Registers an annotation type. Deduplicates by FQN — subsequent calls for the same FQN are
   * silently ignored to avoid overwriting already-captured metadata.
   *
   * @param fqn fully qualified annotation type name
   * @param simpleName simple (unqualified) name
   * @param packageName Java package of the annotation type
   */
  public void addAnnotation(String fqn, String simpleName, String packageName) {
    annotations.putIfAbsent(fqn, new AnnotationData(fqn, simpleName, packageName));
  }

  /**
   * Records that the given JPA entity class maps to the specified table name.
   *
   * @param classFqn fully qualified name of the entity class
   * @param tableName lowercased table name
   */
  public void addTableMapping(String classFqn, String tableName) {
    tableMappings.put(classFqn, tableName);
  }

  /**
   * Appends a directed DEPENDS_ON edge from one class to another.
   *
   * @param fromFqn FQN of the dependent class
   * @param toFqn FQN of the dependency class
   * @param injectionType how the dependency is injected ("field", "constructor", or "setter")
   * @param fieldName name of the field or constructor/setter parameter
   */
  public void addDependencyEdge(
      String fromFqn, String toFqn, String injectionType, String fieldName) {
    dependencyEdges.add(new DependencyEdge(fromFqn, toFqn, injectionType, fieldName));
  }

  /**
   * Appends a query method record indicating the given method issues database queries.
   *
   * @param methodId stable method identifier in the form {@code FQN#method(ParamTypes...)}
   * @param declaringClassFqn FQN of the class that declares this method
   */
  public void addQueryMethod(String methodId, String declaringClassFqn) {
    queryMethods.add(new QueryMethodRecord(methodId, declaringClassFqn));
  }

  /**
   * Appends a BINDS_TO edge from a view class to an entity class via a Vaadin data binding
   * mechanism.
   *
   * @param viewClassFqn FQN of the Vaadin view class
   * @param entityClassFqn FQN of the bound entity class
   * @param bindingMechanism Vaadin 7 binding type (e.g., "BeanFieldGroup", "FieldGroup")
   */
  public void addBindsToEdge(
      String viewClassFqn, String entityClassFqn, String bindingMechanism) {
    bindsToEdges.add(new BindsToRecord(viewClassFqn, entityClassFqn, bindingMechanism));
  }

  // =========================================================================
  // Phase 5: Domain lexicon mutation methods
  // =========================================================================

  /**
   * Adds a business term extracted from the given source. Deduplicates by termId (lowercase
   * normalized form) — first occurrence wins for {@code displayName}, {@code primarySourceFqn}, and
   * {@code sourceType}. Every call always records the {@code sourceFqn} in the term's
   * {@code allSourceFqns} set.
   *
   * @param word the raw term word (will be lowercased to form the termId)
   * @param sourceFqn FQN of the class/type where this term was found
   * @param sourceType extraction source category (e.g., "CLASS_NAME", "ENUM_CONSTANT", "DB_TABLE")
   * @param javadoc class-level Javadoc text to use as a definition seed; null if not present
   */
  public void addBusinessTerm(String word, String sourceFqn, String sourceType, String javadoc) {
    String termId = word.toLowerCase().trim();
    businessTerms.computeIfAbsent(
        termId, id -> new BusinessTermData(id, capitalize(word), sourceFqn, sourceType, javadoc));
    // Always add sourceFqn to the set regardless of whether it was just created
    businessTerms.get(termId).allSourceFqns.add(sourceFqn);
  }

  private static String capitalize(String word) {
    if (word == null || word.isEmpty()) return word;
    return Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase();
  }

  // =========================================================================
  // Phase 6: Structural risk mutation methods
  // =========================================================================

  /**
   * Records the cyclomatic complexity for a single method.
   *
   * @param methodId stable method identifier in the form {@code FQN#method(ParamTypes...)}
   * @param declaringClassFqn FQN of the class that declares this method
   * @param cc computed cyclomatic complexity value (must be >= 1)
   */
  public void addMethodComplexity(String methodId, String declaringClassFqn, int cc) {
    methodComplexities.put(methodId, new MethodComplexityData(methodId, declaringClassFqn, cc));
  }

  /**
   * Increments the DB write method count for the given class. Each call indicates one additional
   * method in the class that performs a database write operation.
   *
   * @param classFqn fully qualified name of the class containing the write method
   */
  public void incrementClassDbWrites(String classFqn) {
    classWriteData.merge(
        classFqn,
        new ClassWriteData(classFqn, 1),
        (existing, increment) -> new ClassWriteData(classFqn, existing.writeCount() + 1));
  }

  // =========================================================================
  // Merge (for parallel extraction)
  // =========================================================================

  /**
   * Merges another accumulator's data into this accumulator and returns {@code this}.
   *
   * <p>Designed for use after parallel extraction where each partition produces its own
   * {@code ExtractionAccumulator}. After all partitions complete, their accumulators are reduced
   * via this method into a single merged accumulator. The merge runs after all parallel tasks have
   * finished — no concurrent access occurs during the merge itself.
   *
   * <p>Merge semantics per collection:
   * <ul>
   *   <li>Maps keyed by FQN ({@code classes}, {@code methods}, {@code fields}, {@code annotations},
   *       {@code tableMappings}): {@code putAll} — last-write-wins for identical FQN keys, which is
   *       safe because identical FQNs represent the same entity.
   *   <li>{@code annotations}: {@code putIfAbsent} — first-occurrence-wins to preserve any
   *       additional metadata captured by the first partition.
   *   <li>Lists ({@code callEdges}, {@code componentEdges}, {@code dependencyEdges},
   *       {@code queryMethods}, {@code bindsToEdges}): {@code addAll} — all edges from both
   *       partitions are kept (deduplication happens at graph MERGE level).
   *   <li>Sets ({@code vaadinViews}, {@code vaadinComponents}, {@code vaadinDataBindings},
   *       {@code serviceClasses}, {@code repositoryClasses}, {@code uiViewClasses}): {@code addAll}
   *       — set union.
   *   <li>{@code businessTerms}: merge per-term {@code allSourceFqns} sets so all class references
   *       are preserved.
   *   <li>{@code methodComplexities}: {@code putAll} — keyed by methodId, no conflict expected.
   *   <li>{@code classWriteData}: merge write counts — same class FQN from two partitions is
   *       unexpected but handled by summing counts.
   * </ul>
   *
   * @param other the other accumulator to merge into this one (not modified)
   * @return {@code this} with all of {@code other}'s data incorporated
   */
  public ExtractionAccumulator merge(ExtractionAccumulator other) {
    // Maps keyed by FQN — putAll is safe (identical FQN means same entity)
    this.classes.putAll(other.classes);
    this.methods.putAll(other.methods);
    this.fields.putAll(other.fields);

    // Lists — append all (edge deduplication handled at Neo4j MERGE level)
    this.callEdges.addAll(other.callEdges);
    this.componentEdges.addAll(other.componentEdges);
    this.dependencyEdges.addAll(other.dependencyEdges);
    this.queryMethods.addAll(other.queryMethods);
    this.bindsToEdges.addAll(other.bindsToEdges);

    // Sets — union
    this.vaadinViews.addAll(other.vaadinViews);
    this.vaadinComponents.addAll(other.vaadinComponents);
    this.vaadinDataBindings.addAll(other.vaadinDataBindings);
    this.serviceClasses.addAll(other.serviceClasses);
    this.repositoryClasses.addAll(other.repositoryClasses);
    this.uiViewClasses.addAll(other.uiViewClasses);

    // Annotations — putIfAbsent so first-captured metadata is preserved
    other.annotations.forEach((k, v) -> this.annotations.putIfAbsent(k, v));

    // Table mappings — putAll (keyed by classFqn, no conflict expected)
    this.tableMappings.putAll(other.tableMappings);

    // Business terms — merge allSourceFqns sets so all class references are preserved
    other.businessTerms.forEach((termId, otherTerm) -> {
      BusinessTermData existing = this.businessTerms.get(termId);
      if (existing != null) {
        existing.allSourceFqns.addAll(otherTerm.allSourceFqns);
      } else {
        this.businessTerms.put(termId, otherTerm);
      }
    });

    // Method complexities — putAll (keyed by methodId, no conflict expected)
    this.methodComplexities.putAll(other.methodComplexities);

    // Class write data — merge counts (same FQN in two partitions is unusual but handled safely)
    other.classWriteData.forEach((fqn, otherWrite) ->
        this.classWriteData.merge(fqn, otherWrite,
            (existing, incoming) -> new ClassWriteData(fqn, existing.writeCount() + incoming.writeCount())));

    return this;
  }

  // =========================================================================
  // Read accessors
  // =========================================================================

  public Map<String, ClassNodeData> getClasses() {
    return Collections.unmodifiableMap(classes);
  }

  public Map<String, MethodNodeData> getMethods() {
    return Collections.unmodifiableMap(methods);
  }

  public Map<String, FieldNodeData> getFields() {
    return Collections.unmodifiableMap(fields);
  }

  public List<CallEdge> getCallEdges() {
    return Collections.unmodifiableList(callEdges);
  }

  public List<ComponentEdge> getComponentEdges() {
    return Collections.unmodifiableList(componentEdges);
  }

  public Set<String> getVaadinViews() {
    return Collections.unmodifiableSet(vaadinViews);
  }

  public Set<String> getVaadinComponents() {
    return Collections.unmodifiableSet(vaadinComponents);
  }

  public Set<String> getVaadinDataBindings() {
    return Collections.unmodifiableSet(vaadinDataBindings);
  }

  /** Returns an unmodifiable view of all class FQNs marked as service beans. */
  public Set<String> getServiceClasses() {
    return Collections.unmodifiableSet(serviceClasses);
  }

  /** Returns an unmodifiable view of all class FQNs marked as repository beans. */
  public Set<String> getRepositoryClasses() {
    return Collections.unmodifiableSet(repositoryClasses);
  }

  /** Returns an unmodifiable view of all class FQNs marked as UI views. */
  public Set<String> getUIViewClasses() {
    return Collections.unmodifiableSet(uiViewClasses);
  }

  /** Returns an unmodifiable view of all registered annotation types, keyed by FQN. */
  public Map<String, AnnotationData> getAnnotations() {
    return Collections.unmodifiableMap(annotations);
  }

  /** Returns an unmodifiable view of entity class FQN to table name mappings. */
  public Map<String, String> getTableMappings() {
    return Collections.unmodifiableMap(tableMappings);
  }

  /** Returns an unmodifiable view of all DEPENDS_ON edge records. */
  public List<DependencyEdge> getDependencyEdges() {
    return Collections.unmodifiableList(dependencyEdges);
  }

  /** Returns an unmodifiable view of all query method records. */
  public List<QueryMethodRecord> getQueryMethods() {
    return Collections.unmodifiableList(queryMethods);
  }

  /** Returns an unmodifiable view of all BINDS_TO edge records. */
  public List<BindsToRecord> getBindsToEdges() {
    return Collections.unmodifiableList(bindsToEdges);
  }

  /** Returns an unmodifiable view of all extracted business terms, keyed by termId. */
  public Map<String, BusinessTermData> getBusinessTerms() {
    return Collections.unmodifiableMap(businessTerms);
  }

  /** Returns an unmodifiable view of per-method complexity data, keyed by methodId. */
  public Map<String, MethodComplexityData> getMethodComplexities() {
    return Collections.unmodifiableMap(methodComplexities);
  }

  /** Returns an unmodifiable view of per-class DB write data, keyed by class FQN. */
  public Map<String, ClassWriteData> getClassWriteData() {
    return Collections.unmodifiableMap(classWriteData);
  }

  // =========================================================================
  // Inner record types
  // =========================================================================

  /** Extracted data for a single Java class or interface. */
  public record ClassNodeData(
      String fqn,
      String simpleName,
      String packageName,
      List<String> annotations,
      List<String> modifiers,
      boolean isInterface,
      boolean isAbstract,
      boolean isEnum,
      String superClass,
      List<String> implementedInterfaces,
      String sourceFilePath,
      String contentHash) {}

  /** Extracted data for a single Java method or constructor. */
  public record MethodNodeData(
      String methodId,
      String simpleName,
      String returnType,
      String declaringClass,
      List<String> parameterTypes,
      List<String> annotations,
      List<String> modifiers,
      boolean isConstructor) {}

  /** Extracted data for a single Java field declaration. */
  public record FieldNodeData(
      String fieldId,
      String simpleName,
      String fieldType,
      String declaringClass,
      List<String> annotations,
      List<String> modifiers) {}

  /** A directed call edge from one method to another. */
  public record CallEdge(
      String callerMethodId, String calleeMethodId, String sourceFile, int lineNumber) {}

  /** A CONTAINS_COMPONENT edge representing a parent-child layout relationship in Vaadin 7. */
  public record ComponentEdge(
      String parentClassFqn, String childClassFqn, String parentType, String childType) {}

  /** Extracted data for a unique Java annotation type encountered during extraction. */
  public record AnnotationData(String fqn, String simpleName, String packageName) {}

  /**
   * A directed DEPENDS_ON edge indicating that {@code fromFqn} depends on {@code toFqn}, with
   * metadata about how the dependency is injected.
   */
  public record DependencyEdge(
      String fromFqn, String toFqn, String injectionType, String fieldName) {}

  /** Identifies a repository query method and its declaring class. */
  public record QueryMethodRecord(String methodId, String declaringClassFqn) {}

  /**
   * A BINDS_TO edge from a Vaadin view to an entity class, capturing the Vaadin 7 data binding
   * mechanism used.
   */
  public record BindsToRecord(
      String viewClassFqn, String entityClassFqn, String bindingMechanism) {}

  /** Cyclomatic complexity data for a single method. */
  public record MethodComplexityData(
      String methodId, String declaringClassFqn, int cyclomaticComplexity) {}

  /** DB write count data for a single class. */
  public record ClassWriteData(String classFqn, int writeCount) {}

  /**
   * Holds extracted data for a single domain business term.
   *
   * <p>Uses a mutable class (not a record) because {@code allSourceFqns} must be updated each time
   * the same term is encountered in a new source class. The {@code termId}, {@code displayName},
   * {@code primarySourceFqn}, {@code sourceType}, and {@code javadocSeed} are set on first
   * occurrence only (first-occurrence-wins policy for deduplication).
   */
  public static class BusinessTermData {
    public final String termId;
    public final String displayName;
    public final String primarySourceFqn;
    public final String sourceType;
    public final String javadocSeed;
    /** All class/type FQNs that reference this term. Updated on each occurrence. */
    public final Set<String> allSourceFqns = new LinkedHashSet<>();

    public BusinessTermData(
        String termId,
        String displayName,
        String primarySourceFqn,
        String sourceType,
        String javadocSeed) {
      this.termId = termId;
      this.displayName = displayName;
      this.primarySourceFqn = primarySourceFqn;
      this.sourceType = sourceType;
      this.javadocSeed = javadocSeed;
    }
  }
}
