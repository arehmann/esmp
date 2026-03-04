package com.esmp.extraction.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.parser.ClasspathLoader;
import com.esmp.extraction.parser.JavaSourceParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openrewrite.SourceFile;

/**
 * Unit tests for {@link DependencyVisitor}. Verifies dependency injection detection from
 * {@code @Autowired}/{@code @Inject} fields and constructor parameters.
 */
class DependencyVisitorTest {

  private ExtractionAccumulator acc;

  @BeforeEach
  void setUp() throws Exception {
    acc = new ExtractionAccumulator();
    DependencyVisitor visitor = new DependencyVisitor();
    for (SourceFile source : parseJava(SAMPLE_SERVICE_SOURCE, SAMPLE_CONSTRUCTOR_SERVICE_SOURCE,
        SAMPLE_INJECT_SERVICE_SOURCE)) {
      visitor.visit(source, acc);
    }
  }

  // ---------------------------------------------------------------------------
  // @Autowired field injection
  // ---------------------------------------------------------------------------

  @Test
  void detectsAutowiredFieldInjection() {
    // SampleServiceWithDeps has @Autowired SampleRepository repository field
    boolean found = acc.getDependencyEdges().stream()
        .anyMatch(e -> e.fromFqn().equals("com.example.test.SampleServiceWithDeps")
            && e.toFqn().equals("com.example.test.SampleRepository")
            && e.injectionType().equals("field")
            && e.fieldName().equals("repository"));
    assertThat(found)
        .as("Expected DEPENDS_ON edge from SampleServiceWithDeps to SampleRepository via @Autowired field")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // @Inject field injection
  // ---------------------------------------------------------------------------

  @Test
  void detectsInjectFieldInjection() {
    // SampleInjectService has @Inject SampleRepository repo field
    boolean found = acc.getDependencyEdges().stream()
        .anyMatch(e -> e.fromFqn().equals("com.example.test.SampleInjectService")
            && e.toFqn().equals("com.example.test.SampleRepository")
            && e.injectionType().equals("field")
            && e.fieldName().equals("repo"));
    assertThat(found)
        .as("Expected DEPENDS_ON edge from SampleInjectService to SampleRepository via @Inject field")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // @Autowired constructor injection
  // ---------------------------------------------------------------------------

  @Test
  void detectsAutowiredConstructorInjection() {
    // SampleConstructorService has @Autowired constructor with SampleRepository param
    boolean found = acc.getDependencyEdges().stream()
        .anyMatch(e -> e.fromFqn().equals("com.example.test.SampleConstructorService")
            && e.toFqn().equals("com.example.test.SampleRepository")
            && e.injectionType().equals("constructor"));
    assertThat(found)
        .as("Expected DEPENDS_ON edge from SampleConstructorService to SampleRepository via @Autowired constructor")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // JDK types filtered
  // ---------------------------------------------------------------------------

  @Test
  void jdkTypesAreFiltered() {
    // @Autowired String field does NOT create a dependency edge
    boolean jdkEdge = acc.getDependencyEdges().stream()
        .anyMatch(e -> e.toFqn().startsWith("java.lang.")
            || e.toFqn().startsWith("java.util.")
            || e.toFqn().equals("java.lang.String"));
    assertThat(jdkEdge)
        .as("JDK types (@Autowired String) should NOT produce dependency edges")
        .isFalse();
  }

  // ---------------------------------------------------------------------------
  // Fixtures (synthetic inline Java sources)
  // ---------------------------------------------------------------------------

  private static final String SAMPLE_SERVICE_SOURCE = """
      package com.example.test;
      import org.springframework.beans.factory.annotation.Autowired;
      import org.springframework.stereotype.Service;
      @Service
      public class SampleServiceWithDeps {
        @Autowired
        private SampleRepository repository;
        @Autowired
        private String name;
      }
      """;

  private static final String SAMPLE_INJECT_SERVICE_SOURCE = """
      package com.example.test;
      import javax.inject.Inject;
      public class SampleInjectService {
        @Inject
        private SampleRepository repo;
      }
      """;

  private static final String SAMPLE_CONSTRUCTOR_SERVICE_SOURCE = """
      package com.example.test;
      import org.springframework.beans.factory.annotation.Autowired;
      public class SampleConstructorService {
        private final SampleRepository repository;
        @Autowired
        public SampleConstructorService(SampleRepository repository) {
          this.repository = repository;
        }
      }
      """;

  private static final String SAMPLE_REPOSITORY_SOURCE = """
      package com.example.test;
      public interface SampleRepository {
        Object findAll();
      }
      """;

  // ---------------------------------------------------------------------------
  // Parser helpers
  // ---------------------------------------------------------------------------

  private List<SourceFile> parseJava(String... sources) throws IOException {
    Path tempDir = Files.createTempDirectory("dep-visitor-test");
    List<Path> sourcePaths = new java.util.ArrayList<>();

    // Always include the repository to provide type info
    Path repoFile = tempDir.resolve("SampleRepository.java");
    Files.writeString(repoFile, SAMPLE_REPOSITORY_SOURCE);
    sourcePaths.add(repoFile);

    for (int i = 0; i < sources.length; i++) {
      Path srcFile = tempDir.resolve("Source" + i + ".java");
      Files.writeString(srcFile, sources[i]);
      sourcePaths.add(srcFile);
    }

    // Register temp files for cleanup
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
