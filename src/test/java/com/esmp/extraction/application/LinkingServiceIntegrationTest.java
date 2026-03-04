package com.esmp.extraction.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.model.ClassNode;
import com.esmp.extraction.persistence.ClassNodeRepository;
import com.esmp.extraction.visitor.ExtractionAccumulator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link LinkingService} using a real Neo4j Testcontainers instance.
 *
 * <p>Uses the full Spring Boot context (same approach as {@code ExtractionIntegrationTest}) to
 * avoid slice test configuration issues with the dual transaction manager setup.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>EXTENDS edges are created when a child class's {@code superClass} FQN matches a persisted
 *       ClassNode
 *   <li>IMPLEMENTS edges are created when a class's {@code implementedInterfaces} list contains the
 *       FQN of a persisted ClassNode
 *   <li>Both linking operations are idempotent — running twice does not double the edge count
 *   <li>DEPENDS_ON edges are created from accumulator dependency data
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class LinkingServiceIntegrationTest {

  @Container
  static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:2026.01.4").withoutAuthentication();

  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("esmp")
          .withUsername("esmp")
          .withPassword("esmp-test");

  @Container
  static GenericContainer<?> qdrant =
      new GenericContainer<>("qdrant/qdrant:latest")
          .withExposedPorts(6333, 6334)
          .waitingFor(Wait.forHttp("/healthz").forPort(6333));

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
    registry.add("spring.neo4j.authentication.username", () -> "neo4j");
    registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", mysql::getUsername);
    registry.add("spring.datasource.password", mysql::getPassword);
    registry.add("qdrant.host", qdrant::getHost);
    registry.add("qdrant.port", () -> qdrant.getMappedPort(6334));
  }

  @Autowired
  private ClassNodeRepository classNodeRepository;

  @Autowired
  private Neo4jClient neo4jClient;

  @Autowired
  private LinkingService linkingService;

  @BeforeEach
  void clearDatabase() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
  }

  // ---------------------------------------------------------------------------
  // EXTENDS edge tests
  // ---------------------------------------------------------------------------

  @Test
  void createsExtendsEdge_whenChildSuperClassMatchesPersistedNode() {
    // Arrange: parent class (Animal) and child class (Dog extends Animal)
    ClassNode animal = new ClassNode("com.example.Animal");
    animal.setSimpleName("Animal");
    animal.setPackageName("com.example");
    animal.setImplementedInterfaces(List.of());

    ClassNode dog = new ClassNode("com.example.Dog");
    dog.setSimpleName("Dog");
    dog.setPackageName("com.example");
    dog.setSuperClass("com.example.Animal");
    dog.setImplementedInterfaces(List.of());

    classNodeRepository.saveAll(List.of(animal, dog));

    // Act
    linkingService.linkInheritanceRelationships();

    // Assert: EXTENDS edge from Dog to Animal
    Long extendsCount = neo4jClient
        .query("MATCH (child:JavaClass {simpleName: 'Dog'})-[:EXTENDS]->(parent:JavaClass {simpleName: 'Animal'}) "
            + "RETURN count(*) AS cnt")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(extendsCount)
        .as("Expected one EXTENDS edge from Dog to Animal")
        .isEqualTo(1L);
  }

  @Test
  void extendsEdge_isIdempotent() {
    // Arrange
    ClassNode base = new ClassNode("com.example.Base");
    base.setSimpleName("Base");
    base.setPackageName("com.example");
    base.setImplementedInterfaces(List.of());

    ClassNode derived = new ClassNode("com.example.Derived");
    derived.setSimpleName("Derived");
    derived.setPackageName("com.example");
    derived.setSuperClass("com.example.Base");
    derived.setImplementedInterfaces(List.of());

    classNodeRepository.saveAll(List.of(base, derived));

    // Act: run linking twice
    linkingService.linkInheritanceRelationships();
    linkingService.linkInheritanceRelationships();

    // Assert: still exactly one EXTENDS edge
    Long extendsCount = neo4jClient
        .query("MATCH (:JavaClass)-[r:EXTENDS]->(:JavaClass) RETURN count(r) AS cnt")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(extendsCount)
        .as("EXTENDS edges should not double on second linking run (idempotent MERGE)")
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // IMPLEMENTS edge tests
  // ---------------------------------------------------------------------------

  @Test
  void createsImplementsEdge_whenClassImplementsInterface() {
    // Arrange: interface and class that implements it
    ClassNode serializable = new ClassNode("com.example.Serializable");
    serializable.setSimpleName("Serializable");
    serializable.setPackageName("com.example");
    serializable.setInterface(true);
    serializable.setImplementedInterfaces(List.of());

    ClassNode myEntity = new ClassNode("com.example.MyEntity");
    myEntity.setSimpleName("MyEntity");
    myEntity.setPackageName("com.example");
    myEntity.setImplementedInterfaces(List.of("com.example.Serializable"));

    classNodeRepository.saveAll(List.of(serializable, myEntity));

    // Act
    linkingService.linkInheritanceRelationships();

    // Assert
    Long implementsCount = neo4jClient
        .query("MATCH (c:JavaClass {simpleName: 'MyEntity'})-[:IMPLEMENTS]->(i:JavaClass {simpleName: 'Serializable'}) "
            + "RETURN count(*) AS cnt")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(implementsCount)
        .as("Expected one IMPLEMENTS edge from MyEntity to Serializable")
        .isEqualTo(1L);
  }

  @Test
  void implementsEdge_isIdempotent() {
    // Arrange
    ClassNode iface = new ClassNode("com.example.MyInterface");
    iface.setSimpleName("MyInterface");
    iface.setPackageName("com.example");
    iface.setInterface(true);
    iface.setImplementedInterfaces(List.of());

    ClassNode impl = new ClassNode("com.example.MyImpl");
    impl.setSimpleName("MyImpl");
    impl.setPackageName("com.example");
    impl.setImplementedInterfaces(List.of("com.example.MyInterface"));

    classNodeRepository.saveAll(List.of(iface, impl));

    // Act: run twice
    linkingService.linkInheritanceRelationships();
    linkingService.linkInheritanceRelationships();

    // Assert: exactly one IMPLEMENTS edge
    Long implementsCount = neo4jClient
        .query("MATCH (:JavaClass)-[r:IMPLEMENTS]->(:JavaClass) RETURN count(r) AS cnt")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(implementsCount)
        .as("IMPLEMENTS edges should not double on second run (idempotent MERGE)")
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // DEPENDS_ON edge test
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // BINDS_TO edge tests
  // ---------------------------------------------------------------------------

  @Test
  void linkBindsToEdges_createsBindsToRelationship() {
    // Pre-populate two ClassNodes: a view and an entity
    neo4jClient.query("""
        CREATE (v:JavaClass {fullyQualifiedName: 'com.test.MyForm', simpleName: 'MyForm',
                packageName: 'com.test', sourceFile: 'MyForm.java', annotations: [],
                implementedInterfaces: [], extraLabels: ['VaadinDataBinding']})
        CREATE (e:JavaClass {fullyQualifiedName: 'com.test.MyEntity', simpleName: 'MyEntity',
                packageName: 'com.test', sourceFile: 'MyEntity.java', annotations: [],
                implementedInterfaces: [], extraLabels: []})
        """).run();

    // Create accumulator with a BINDS_TO edge
    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addBindsToEdge("com.test.MyForm", "com.test.MyEntity", "BeanFieldGroup");

    int count = linkingService.linkBindsToEdges(acc);
    assertThat(count).isEqualTo(1);

    // Verify the edge exists in Neo4j
    Long edgeCount = neo4jClient.query("""
        MATCH (v:JavaClass {fullyQualifiedName: 'com.test.MyForm'})
              -[r:BINDS_TO]->(e:JavaClass {fullyQualifiedName: 'com.test.MyEntity'})
        RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);
    assertThat(edgeCount).isEqualTo(1L);

    // Verify idempotency: call again, count should still be 1
    linkingService.linkBindsToEdges(acc);
    Long edgeCountAfterRerun = neo4jClient.query("""
        MATCH (:JavaClass {fullyQualifiedName: 'com.test.MyForm'})
              -[r:BINDS_TO]->(:JavaClass {fullyQualifiedName: 'com.test.MyEntity'})
        RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);
    assertThat(edgeCountAfterRerun).isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // HAS_ANNOTATION edge tests
  // ---------------------------------------------------------------------------

  @Test
  void linkAnnotations_createsHasAnnotationEdge_whenClassAnnotationsContainFqnMatchingAnnotationNode() {
    // Arrange: a ClassNode whose annotations list contains an FQN, and a matching JavaAnnotation node
    neo4jClient.query("""
        CREATE (c:JavaClass {fullyQualifiedName: 'com.test.MyEntity', simpleName: 'MyEntity',
                packageName: 'com.test', sourceFile: 'MyEntity.java',
                annotations: ['javax.persistence.Entity'],
                implementedInterfaces: [], extraLabels: []})
        CREATE (a:JavaAnnotation {fullyQualifiedName: 'javax.persistence.Entity',
                simpleName: 'Entity', packageName: 'javax.persistence'})
        """).run();

    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addAnnotation("javax.persistence.Entity", "Entity", "javax.persistence");

    // Act
    int count = linkingService.linkAnnotations(acc);

    // Assert: one HAS_ANNOTATION edge created
    assertThat(count).as("linkAnnotations should create 1 HAS_ANNOTATION edge").isEqualTo(1);

    Long edgeCount = neo4jClient.query("""
        MATCH (c:JavaClass {fullyQualifiedName: 'com.test.MyEntity'})
              -[r:HAS_ANNOTATION]->(a:JavaAnnotation {fullyQualifiedName: 'javax.persistence.Entity'})
        RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);
    assertThat(edgeCount).as("Expected 1 HAS_ANNOTATION edge in Neo4j").isEqualTo(1L);
  }

  @Test
  void linkAnnotations_isIdempotent() {
    // Arrange
    neo4jClient.query("""
        CREATE (c:JavaClass {fullyQualifiedName: 'com.test.AService', simpleName: 'AService',
                packageName: 'com.test', sourceFile: 'AService.java',
                annotations: ['org.springframework.stereotype.Service'],
                implementedInterfaces: [], extraLabels: []})
        CREATE (a:JavaAnnotation {fullyQualifiedName: 'org.springframework.stereotype.Service',
                simpleName: 'Service', packageName: 'org.springframework.stereotype'})
        """).run();

    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addAnnotation("org.springframework.stereotype.Service", "Service", "org.springframework.stereotype");

    // Act: run twice
    linkingService.linkAnnotations(acc);
    linkingService.linkAnnotations(acc);

    // Assert: still exactly one edge
    Long edgeCount = neo4jClient.query("""
        MATCH (:JavaClass)-[r:HAS_ANNOTATION]->(:JavaAnnotation) RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);
    assertThat(edgeCount).as("HAS_ANNOTATION edges should not double on second run (idempotent MERGE)").isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // QUERIES edge tests
  // ---------------------------------------------------------------------------

  @Test
  void linkQueryMethods_createsQueriesEdge_byTraversingRepositoryToEntityToTable() {
    // Arrange: SampleRepository -> (IMPLEMENTS) -> JpaRepositoryIface -> (MAPS_TO_TABLE via entity)
    // Simpler graph: repo -> (DEPENDS_ON) -> entity -> (MAPS_TO_TABLE) -> table
    // Use the graph traversal: MATCH (m)-[:QUERIES]->(t) after linking
    neo4jClient.query("""
        CREATE (repo:JavaClass {fullyQualifiedName: 'com.test.CustomerRepository',
                simpleName: 'CustomerRepository', packageName: 'com.test',
                annotations: [], implementedInterfaces: [], extraLabels: []})
        CREATE (entity:JavaClass {fullyQualifiedName: 'com.test.Customer',
                simpleName: 'Customer', packageName: 'com.test',
                annotations: [], implementedInterfaces: [], extraLabels: []})
        CREATE (table:DBTable {tableName: 'customers'})
        CREATE (method:JavaMethod {methodId: 'com.test.CustomerRepository#findByName(java.lang.String)',
                simpleName: 'findByName', declaringClass: 'com.test.CustomerRepository'})
        CREATE (repo)-[:DEPENDS_ON]->(entity)
        CREATE (entity)-[:MAPS_TO_TABLE]->(table)
        """).run();

    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addQueryMethod(
        "com.test.CustomerRepository#findByName(java.lang.String)",
        "com.test.CustomerRepository");

    // Act
    int count = linkingService.linkQueryMethods(acc);

    // Assert: one QUERIES edge created
    assertThat(count).as("linkQueryMethods should create 1 QUERIES edge via graph traversal").isEqualTo(1);

    Long edgeCount = neo4jClient.query("""
        MATCH (m:JavaMethod {methodId: 'com.test.CustomerRepository#findByName(java.lang.String)'})
              -[r:QUERIES]->(t:DBTable {tableName: 'customers'})
        RETURN count(r) AS cnt
        """)
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);
    assertThat(edgeCount).as("Expected 1 QUERIES edge from findByName to customers table").isEqualTo(1L);
  }

  @Test
  void createsDependsOnEdge_fromAccumulatorData() {
    // Arrange: two ClassNodes
    ClassNode service = new ClassNode("com.example.OrderService");
    service.setSimpleName("OrderService");
    service.setPackageName("com.example");
    service.setImplementedInterfaces(List.of());

    ClassNode repo = new ClassNode("com.example.OrderRepo");
    repo.setSimpleName("OrderRepo");
    repo.setPackageName("com.example");
    repo.setImplementedInterfaces(List.of());

    classNodeRepository.saveAll(List.of(service, repo));

    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addDependencyEdge(
        "com.example.OrderService", "com.example.OrderRepo", "field", "orderRepo");

    // Act
    linkingService.linkDependencies(acc);

    // Assert
    Long dependsOnCount = neo4jClient
        .query("MATCH (:JavaClass {simpleName: 'OrderService'})-[r:DEPENDS_ON]->(:JavaClass {simpleName: 'OrderRepo'}) "
            + "RETURN count(r) AS cnt")
        .fetchAs(Long.class)
        .mappedBy((ts, record) -> record.get("cnt").asLong())
        .one()
        .orElse(0L);

    assertThat(dependsOnCount)
        .as("Expected one DEPENDS_ON edge from OrderService to OrderRepo")
        .isEqualTo(1L);
  }
}
