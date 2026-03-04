package com.esmp.graph.application;

import com.esmp.graph.api.ClassStructureResponse;
import com.esmp.graph.api.ClassStructureResponse.DependencySummary;
import com.esmp.graph.api.ClassStructureResponse.FieldSummary;
import com.esmp.graph.api.ClassStructureResponse.MethodSummary;
import com.esmp.graph.api.DependencyResponse;
import com.esmp.graph.api.DependencyResponse.ServiceEntry;
import com.esmp.graph.api.InheritanceChainResponse;
import com.esmp.graph.api.InheritanceChainResponse.AncestorEntry;
import com.esmp.graph.api.SearchResponse;
import com.esmp.graph.api.SearchResponse.SearchEntry;
import com.esmp.graph.persistence.GraphQueryRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.MapAccessor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Service layer for querying the code knowledge graph.
 *
 * <p>Uses {@link Neo4jClient} for complex variable-length Cypher queries (inheritance chains,
 * transitive dependencies) where Spring Data Neo4j cannot map path objects. Delegates to
 * {@link GraphQueryRepository} for simple SDN derived lookups.
 *
 * <p>All Cypher parameters are bound via {@code .bind(value).to("paramName")} — never via string
 * concatenation — to prevent Cypher injection and enable query plan caching.
 */
@Service
public class GraphQueryService {

  private final Neo4jClient neo4jClient;
  private final GraphQueryRepository graphQueryRepository;

  public GraphQueryService(Neo4jClient neo4jClient, GraphQueryRepository graphQueryRepository) {
    this.neo4jClient = neo4jClient;
    this.graphQueryRepository = graphQueryRepository;
  }

  /**
   * Finds the structural context of a Java class: methods, fields, dependencies, and annotation
   * nodes.
   *
   * @param fqn fully qualified class name
   * @return class structure, or empty if no class with that FQN exists
   */
  public Optional<ClassStructureResponse> findClassStructure(String fqn) {
    String cypher =
        """
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        OPTIONAL MATCH (c)-[:DECLARES_METHOD]->(m:JavaMethod)
        OPTIONAL MATCH (c)-[:DECLARES_FIELD]->(f:JavaField)
        OPTIONAL MATCH (c)-[:DEPENDS_ON]->(dep:JavaClass)
        OPTIONAL MATCH (c)-[:HAS_ANNOTATION]->(ann:JavaAnnotation)
        RETURN c,
               collect(DISTINCT m) AS methods,
               collect(DISTINCT f) AS fields,
               collect(DISTINCT dep) AS dependencies,
               collect(DISTINCT ann) AS annotationNodes
        """;

    return neo4jClient
        .query(cypher)
        .bind(fqn).to("fqn")
        .fetchAs(ClassStructureResponse.class)
        .mappedBy((typeSystem, record) -> {
          Value classValue = record.get("c");
          if (classValue.isNull()) {
            return null;
          }
          MapAccessor classMap = classValue.asNode();

          // Extract base labels (excluding JavaClass itself)
          List<String> labels = new ArrayList<>();
          classValue.asNode().labels().forEach(label -> {
            if (!label.equals("JavaClass")) {
              labels.add(label);
            }
          });

          // Extract annotation strings stored as property
          List<String> annotations = toStringList(classMap.get("annotations"));

          // Extract implemented interfaces
          List<String> implementedInterfaces = toStringList(classMap.get("implementedInterfaces"));

          // Map methods
          List<MethodSummary> methods = record.get("methods").asList(methodValue -> {
            if (methodValue.isNull()) return null;
            MapAccessor m = methodValue.asNode();
            return new MethodSummary(
                getString(m, "methodId"),
                getString(m, "simpleName"),
                getString(m, "returnType"),
                toStringList(m.get("parameterTypes")),
                toStringList(m.get("annotations")));
          }).stream().filter(x -> x != null).collect(Collectors.toList());

          // Map fields
          List<FieldSummary> fields = record.get("fields").asList(fieldValue -> {
            if (fieldValue.isNull()) return null;
            MapAccessor f = fieldValue.asNode();
            return new FieldSummary(
                getString(f, "fieldId"),
                getString(f, "simpleName"),
                getString(f, "fieldType"),
                toStringList(f.get("annotations")));
          }).stream().filter(x -> x != null).collect(Collectors.toList());

          // Map dependencies
          List<DependencySummary> dependencies = record.get("dependencies").asList(depValue -> {
            if (depValue.isNull()) return null;
            MapAccessor dep = depValue.asNode();
            return new DependencySummary(
                getString(dep, "fullyQualifiedName"),
                getString(dep, "simpleName"),
                null); // injectionType stored on the relationship, not on the target node
          }).stream().filter(x -> x != null).collect(Collectors.toList());

          // Map annotation node FQNs
          List<String> annotationNodes = record.get("annotationNodes").asList(annValue -> {
            if (annValue.isNull()) return null;
            return annValue.asNode().get("fullyQualifiedName").asString(null);
          }).stream().filter(x -> x != null).collect(Collectors.toList());

          return new ClassStructureResponse(
              getString(classMap, "fullyQualifiedName"),
              getString(classMap, "simpleName"),
              getString(classMap, "packageName"),
              labels,
              annotations,
              getString(classMap, "superClass"),
              implementedInterfaces,
              methods,
              fields,
              dependencies,
              annotationNodes);
        })
        .one();
  }

  /**
   * Finds the full inheritance chain for a class by traversing EXTENDS relationships.
   *
   * <p>Uses Neo4jClient with variable-length path query (*1..10) — Spring Data Neo4j cannot map
   * Cypher path objects, so @Query on a repository is not viable for this query.
   *
   * @param fqn fully qualified class name
   * @return inheritance chain response (empty chain if class has no ancestors)
   */
  public InheritanceChainResponse findInheritanceChain(String fqn) {
    // Single combined query: find the root class and all its ancestors in one go
    String cypher =
        """
        MATCH (c:JavaClass {fullyQualifiedName: $fqn})
        OPTIONAL MATCH path = (c)-[:EXTENDS*1..10]->(ancestor:JavaClass)
        WITH c, ancestor, path
        RETURN c.implementedInterfaces AS rootIfaces,
               ancestor.fullyQualifiedName AS ancestorFqn,
               ancestor.simpleName AS ancestorName,
               ancestor.implementedInterfaces AS ancestorIfaces,
               CASE WHEN path IS NOT NULL THEN length(path) ELSE null END AS depth
        """;

    Collection<Map<String, Object>> rows = neo4jClient
        .query(cypher)
        .bind(fqn).to("fqn")
        .fetch()
        .all();

    if (rows.isEmpty()) {
      // Class does not exist
      return new InheritanceChainResponse(fqn, List.of(), List.of());
    }

    List<AncestorEntry> chain = new ArrayList<>();
    List<String> allInterfaces = new ArrayList<>();
    boolean rootIfacesCollected = false;

    for (Map<String, Object> row : rows) {
      // Collect root class interfaces (same in every row, so only once)
      if (!rootIfacesCollected) {
        Object rootIfaces = row.get("rootIfaces");
        if (rootIfaces instanceof List<?> list) {
          for (Object iface : list) {
            if (iface instanceof String s) allInterfaces.add(s);
          }
        }
        rootIfacesCollected = true;
      }

      // Each row may contain an ancestor (or null if class has no parents)
      String ancestorFqn = (String) row.get("ancestorFqn");
      if (ancestorFqn != null) {
        String ancestorName = (String) row.get("ancestorName");
        int depth = row.get("depth") != null ? ((Long) row.get("depth")).intValue() : 0;
        chain.add(new AncestorEntry(ancestorFqn, ancestorName, depth));

        // Collect ancestor interfaces too
        Object ancestorIfaces = row.get("ancestorIfaces");
        if (ancestorIfaces instanceof List<?> list) {
          for (Object iface : list) {
            if (iface instanceof String s && !allInterfaces.contains(s)) {
              allInterfaces.add(s);
            }
          }
        }
      }
    }

    // Sort chain by depth (ascending)
    chain.sort((a, b) -> Integer.compare(a.depth(), b.depth()));

    return new InheritanceChainResponse(fqn, chain, allInterfaces);
  }

  /**
   * Finds all Service-labeled classes that transitively depend on the given Repository class.
   *
   * <p>Uses Neo4jClient with variable-length DEPENDS_ON path query (up to 10 hops).
   *
   * @param repositoryFqn fully qualified name of the repository class
   * @return dependency response (empty services list if repository not found or has no dependents)
   */
  public DependencyResponse findServiceDependents(String repositoryFqn) {
    String cypher =
        """
        MATCH (repo:JavaClass {fullyQualifiedName: $fqn})
        WHERE repo:Repository OR ANY(label IN labels(repo) WHERE label = 'Repository')
        WITH repo
        MATCH path = (svc:JavaClass)-[:DEPENDS_ON*1..10]->(repo)
        WHERE ANY(label IN labels(svc) WHERE label = 'Service')
        RETURN DISTINCT svc.fullyQualifiedName AS fqn,
                        svc.simpleName AS name,
                        min(length(path)) AS hops
        ORDER BY hops ASC
        """;

    Collection<Map<String, Object>> rows = neo4jClient
        .query(cypher)
        .bind(repositoryFqn).to("fqn")
        .fetch()
        .all();

    List<ServiceEntry> services = rows.stream()
        .map(row -> new ServiceEntry(
            (String) row.get("fqn"),
            (String) row.get("name"),
            ((Long) row.get("hops")).intValue()))
        .collect(Collectors.toList());

    return new DependencyResponse(repositoryFqn, services);
  }

  /**
   * Searches for classes by simple name (case-insensitive substring match).
   *
   * <p>Uses Neo4jClient with a Cypher {@code labels()} query rather than an SDN derived query.
   * SDN's derived query ({@code findBySimpleNameContainingIgnoreCase}) does not hydrate
   * {@code @DynamicLabels} — the extra labels (Service, Repository, etc.) are always empty when
   * returned via Spring Data entity mapping. Reading {@code labels(c)} directly from the Cypher
   * wire protocol avoids this limitation.
   *
   * @param name the substring to search for in simple names
   * @return search response with all matching classes and their dynamic labels
   */
  public SearchResponse searchByName(String name) {
    String cypher =
        """
        MATCH (c:JavaClass)
        WHERE toLower(c.simpleName) CONTAINS toLower($name)
        RETURN c.fullyQualifiedName AS fqn,
               c.simpleName        AS simpleName,
               c.packageName       AS packageName,
               [label IN labels(c) WHERE label <> 'JavaClass'] AS labels
        ORDER BY c.simpleName
        """;

    Collection<Map<String, Object>> rows =
        neo4jClient
            .query(cypher)
            .bind(name).to("name")
            .fetch()
            .all();

    List<SearchEntry> results =
        rows.stream()
            .map(
                row -> {
                  @SuppressWarnings("unchecked")
                  List<String> labels =
                      row.get("labels") instanceof List<?> list
                          ? list.stream()
                              .filter(l -> l instanceof String)
                              .map(l -> (String) l)
                              .collect(Collectors.toList())
                          : new ArrayList<>();
                  return new SearchEntry(
                      (String) row.get("fqn"),
                      (String) row.get("simpleName"),
                      (String) row.get("packageName"),
                      labels);
                })
            .collect(Collectors.toList());

    return new SearchResponse(name, results);
  }

  // --- helper methods ---

  private String getString(MapAccessor map, String key) {
    Value v = map.get(key);
    return (v == null || v.isNull()) ? null : v.asString(null);
  }

  private List<String> toStringList(Value value) {
    if (value == null || value.isNull()) {
      return new ArrayList<>();
    }
    try {
      return value.asList(Value::asString);
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }
}
