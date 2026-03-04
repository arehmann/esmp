package com.esmp.extraction.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.parser.ClasspathLoader;
import com.esmp.extraction.parser.JavaSourceParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openrewrite.SourceFile;

/**
 * Unit tests for {@link JpaPatternVisitor}. Verifies JPA entity and query method detection.
 */
class JpaPatternVisitorTest {

  private ExtractionAccumulator acc;

  @BeforeEach
  void setUp() throws Exception {
    acc = new ExtractionAccumulator();
    JpaPatternVisitor visitor = new JpaPatternVisitor();
    for (SourceFile source : parseJava(
        ENTITY_WITH_TABLE_SOURCE,
        ENTITY_WITHOUT_TABLE_SOURCE,
        REPOSITORY_WITH_QUERY_SOURCE)) {
      visitor.visit(source, acc);
    }
  }

  // ---------------------------------------------------------------------------
  // @Entity + @Table detection
  // ---------------------------------------------------------------------------

  @Test
  void detectsEntityWithExplicitTableName() {
    // CustomerOrder has @Entity @Table(name="orders") -> addTableMapping(fqn, "orders")
    assertThat(acc.getTableMappings())
        .as("Expected table mapping for CustomerOrder to 'orders'")
        .containsEntry("com.example.jpa.CustomerOrder", "orders");
  }

  @Test
  void detectsEntityWithoutTable_usesSnakeCaseDefault() {
    // ProductCatalog has only @Entity -> table name should be "product_catalog" (snake_case)
    assertThat(acc.getTableMappings())
        .as("Expected table mapping for ProductCatalog to 'product_catalog' (JPA default)")
        .containsEntry("com.example.jpa.ProductCatalog", "product_catalog");
  }

  // ---------------------------------------------------------------------------
  // @Query method detection
  // ---------------------------------------------------------------------------

  @Test
  void detectsQueryAnnotatedMethod() {
    // OrderRepository.findAllByStatus has @Query annotation -> addQueryMethod
    boolean found = acc.getQueryMethods().stream()
        .anyMatch(q -> q.methodId().contains("OrderRepository")
            && q.methodId().contains("findAllByStatus"));
    assertThat(found)
        .as("Expected QueryMethodRecord for OrderRepository#findAllByStatus with @Query")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // Derived query method detection (findByX, deleteByX, countByX, existsByX)
  // ---------------------------------------------------------------------------

  @Test
  void detectsFindByDerivedQuery() {
    boolean found = acc.getQueryMethods().stream()
        .anyMatch(q -> q.methodId().contains("OrderRepository")
            && q.methodId().contains("findByCustomerId"));
    assertThat(found)
        .as("Expected QueryMethodRecord for OrderRepository#findByCustomerId (derived query)")
        .isTrue();
  }

  @Test
  void detectsDeleteByDerivedQuery() {
    boolean found = acc.getQueryMethods().stream()
        .anyMatch(q -> q.methodId().contains("OrderRepository")
            && q.methodId().contains("deleteByStatus"));
    assertThat(found)
        .as("Expected QueryMethodRecord for OrderRepository#deleteByStatus (derived query)")
        .isTrue();
  }

  @Test
  void detectsCountByDerivedQuery() {
    boolean found = acc.getQueryMethods().stream()
        .anyMatch(q -> q.methodId().contains("OrderRepository")
            && q.methodId().contains("countByCustomerId"));
    assertThat(found)
        .as("Expected QueryMethodRecord for OrderRepository#countByCustomerId (derived query)")
        .isTrue();
  }

  @Test
  void detectsExistsByDerivedQuery() {
    boolean found = acc.getQueryMethods().stream()
        .anyMatch(q -> q.methodId().contains("OrderRepository")
            && q.methodId().contains("existsByOrderNumber"));
    assertThat(found)
        .as("Expected QueryMethodRecord for OrderRepository#existsByOrderNumber (derived query)")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // Non-repository entity: only MAPS_TO_TABLE, no QUERIES
  // ---------------------------------------------------------------------------

  @Test
  void entityClass_hasTableMapping_butNoQueryMethods() {
    // CustomerOrder is an @Entity class, not a repository — no query methods from it
    boolean entityHasQueries = acc.getQueryMethods().stream()
        .anyMatch(q -> q.declaringClassFqn().equals("com.example.jpa.CustomerOrder"));
    assertThat(entityHasQueries)
        .as("Entity class CustomerOrder should not have any query methods")
        .isFalse();

    // But it should have table mapping
    assertThat(acc.getTableMappings()).containsKey("com.example.jpa.CustomerOrder");
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private static final String ENTITY_WITH_TABLE_SOURCE = """
      package com.example.jpa;
      import javax.persistence.Entity;
      import javax.persistence.Table;
      @Entity
      @Table(name = "orders")
      public class CustomerOrder {
        private Long id;
        private String status;
        private Long customerId;
      }
      """;

  private static final String ENTITY_WITHOUT_TABLE_SOURCE = """
      package com.example.jpa;
      import javax.persistence.Entity;
      @Entity
      public class ProductCatalog {
        private Long id;
        private String name;
      }
      """;

  private static final String REPOSITORY_WITH_QUERY_SOURCE = """
      package com.example.jpa;
      import java.util.List;
      import org.springframework.data.jpa.repository.JpaRepository;
      import org.springframework.data.jpa.repository.Query;
      public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {
        @Query("SELECT o FROM CustomerOrder o WHERE o.status = :status")
        List<CustomerOrder> findAllByStatus(String status);
        List<CustomerOrder> findByCustomerId(Long customerId);
        void deleteByStatus(String status);
        long countByCustomerId(Long customerId);
        boolean existsByOrderNumber(String orderNumber);
      }
      """;

  // ---------------------------------------------------------------------------
  // Parser helpers
  // ---------------------------------------------------------------------------

  private List<SourceFile> parseJava(String... sources) throws IOException {
    Path tempDir = Files.createTempDirectory("jpa-visitor-test");
    List<Path> sourcePaths = new ArrayList<>();

    for (int i = 0; i < sources.length; i++) {
      Path srcFile = tempDir.resolve("Source" + i + ".java");
      Files.writeString(srcFile, sources[i]);
      sourcePaths.add(srcFile);
    }

    for (Path p : sourcePaths) {
      p.toFile().deleteOnExit();
    }
    tempDir.toFile().deleteOnExit();

    ClasspathLoader loader = new ClasspathLoader();
    JavaSourceParser parser = new JavaSourceParser(loader);
    String classpathFile = buildFullClasspathFile();
    return parser.parse(sourcePaths, tempDir, classpathFile);
  }

  private String buildFullClasspathFile() throws IOException {
    String javaClassPath = System.getProperty("java.class.path", "");
    String[] entries = javaClassPath.split(java.io.File.pathSeparator);
    StringBuilder sb = new StringBuilder();
    for (String entry : entries) {
      if (entry.endsWith(".jar") && !entry.isBlank()) {
        sb.append(entry).append("\n");
      }
    }
    if (sb.isEmpty()) {
      return "";
    }
    Path tempFile = Files.createTempFile("full-classpath", ".txt");
    tempFile.toFile().deleteOnExit();
    Files.writeString(tempFile, sb.toString().trim());
    return tempFile.toString();
  }
}
