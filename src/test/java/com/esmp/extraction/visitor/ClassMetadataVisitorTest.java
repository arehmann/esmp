package com.esmp.extraction.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.parser.ClasspathLoader;
import com.esmp.extraction.parser.JavaSourceParser;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;

/** Unit tests for {@link ClassMetadataVisitor}. Verifies extraction against synthetic fixtures. */
class ClassMetadataVisitorTest {

  private List<SourceFile> parsedFixtures;
  private ExtractionAccumulator acc;

  @BeforeEach
  void setUp() throws Exception {
    parsedFixtures = parseFixtures();
    acc = new ExtractionAccumulator();
    InMemoryExecutionContext ctx = new InMemoryExecutionContext();
    ClassMetadataVisitor visitor = new ClassMetadataVisitor();
    for (SourceFile source : parsedFixtures) {
      visitor.visit(source, acc, ctx);
    }
  }

  @Test
  void extractsSampleServiceClass_withCorrectFqn() {
    assertThat(acc.getClasses()).containsKey("com.example.sample.SampleService");
  }

  @Test
  void extractsSampleServiceClass_withServiceAnnotation() {
    ExtractionAccumulator.ClassNodeData data =
        acc.getClasses().get("com.example.sample.SampleService");
    assertThat(data).isNotNull();
    assertThat(data.annotations())
        .anyMatch(a -> a.contains("Service") || a.contains("org.springframework.stereotype.Service"));
  }

  @Test
  void extractsSampleServiceClass_withThreeMethods() {
    long methodCount =
        acc.getMethods().values().stream()
            .filter(m -> "com.example.sample.SampleService".equals(m.declaringClass()))
            .count();
    // findAll, findByName, save — 3 methods (not counting constructors)
    assertThat(methodCount).isGreaterThanOrEqualTo(3);
  }

  @Test
  void extractsSampleServiceClass_withAutowiredField() {
    long fieldCount =
        acc.getFields().values().stream()
            .filter(f -> "com.example.sample.SampleService".equals(f.declaringClass()))
            .filter(f -> f.annotations().stream().anyMatch(a -> a.contains("Autowired")))
            .count();
    assertThat(fieldCount).isGreaterThanOrEqualTo(1);
  }

  @Test
  void detectsSampleRepositoryAsInterface() {
    ExtractionAccumulator.ClassNodeData data =
        acc.getClasses().get("com.example.sample.SampleRepository");
    assertThat(data).isNotNull();
    assertThat(data.isInterface()).isTrue();
  }

  @Test
  void extractsSampleEntityWithEntityAnnotation() {
    ExtractionAccumulator.ClassNodeData data =
        acc.getClasses().get("com.example.sample.SampleEntity");
    assertThat(data).isNotNull();
    assertThat(data.annotations())
        .anyMatch(a -> a.contains("Entity") || a.contains("javax.persistence.Entity"));
  }

  @Test
  void extractsAllSixFixtureClasses() {
    // Should have exactly 6 class entries (one per fixture file)
    assertThat(acc.getClasses()).hasSize(6);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private List<SourceFile> parseFixtures() throws URISyntaxException, IOException {
    Path fixturesDir =
        Paths.get(
            Objects.requireNonNull(
                    getClass().getClassLoader().getResource("fixtures"))
                .toURI());
    Path projectRoot = fixturesDir.getParent();

    List<Path> sources;
    try (var stream = Files.walk(fixturesDir)) {
      sources = stream.filter(p -> p.toString().endsWith(".java")).toList();
    }

    ClasspathLoader loader = new ClasspathLoader();
    JavaSourceParser parser = new JavaSourceParser(loader);
    String classpathFile = buildVaadinClasspathFile();
    return parser.parse(sources, projectRoot, classpathFile);
  }

  private String buildVaadinClasspathFile() throws Exception {
    try {
      Class<?> clazz = Class.forName("com.vaadin.ui.UI");
      var location = clazz.getProtectionDomain().getCodeSource().getLocation();
      if (location != null) {
        Path jarPath = Paths.get(location.toURI());
        Path tempFile = Files.createTempFile("vaadin-classpath", ".txt");
        tempFile.toFile().deleteOnExit();
        Files.writeString(tempFile, jarPath.toString());
        return tempFile.toString();
      }
    } catch (Exception e) {
      // degrade gracefully
    }
    return "";
  }
}
