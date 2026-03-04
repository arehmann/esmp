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
import org.openrewrite.SourceFile;

/** Unit tests for {@link CallGraphVisitor}. Verifies call edge extraction against synthetic fixtures. */
class CallGraphVisitorTest {

  private ExtractionAccumulator acc;

  @BeforeEach
  void setUp() throws Exception {
    List<SourceFile> parsedFixtures = parseFixtures();
    acc = new ExtractionAccumulator();
    CallGraphVisitor visitor = new CallGraphVisitor();
    for (SourceFile source : parsedFixtures) {
      visitor.visit(source, acc);
    }
  }

  @Test
  void detectsCallFromSampleService_findByName_to_repository_findByName() {
    // SampleService.findByName() -> SampleRepository.findByName() (custom method, not inherited)
    // findByName is declared directly on SampleRepository, so its declaring type IS SampleRepository
    boolean found =
        acc.getCallEdges().stream()
            .anyMatch(
                e ->
                    e.callerMethodId().contains("SampleService")
                        && e.callerMethodId().contains("findByName")
                        && e.calleeMethodId().contains("SampleRepository")
                        && e.calleeMethodId().contains("findByName"));
    assertThat(found)
        .as(
            "Expected call edge from SampleService#findByName to SampleRepository#findByName. "
                + "Note: findAll/save are inherited from JpaRepository so their declaring type is a Spring Data parent interface.")
        .isTrue();
  }

  @Test
  void detectsCallFromSampleService_findAll_to_anyRepository() {
    // SampleService.findAll() calls repository.findAll().
    // The callee's declaring type may be a JpaRepository parent (e.g., ListCrudRepository)
    // because findAll is an inherited method — the exact declaring type depends on classpath resolution.
    boolean found =
        acc.getCallEdges().stream()
            .anyMatch(
                e ->
                    e.callerMethodId().contains("SampleService")
                        && e.callerMethodId().contains("findAll")
                        && e.calleeMethodId().contains("findAll"));
    assertThat(found)
        .as("Expected a call edge from SampleService#findAll to some repository findAll method")
        .isTrue();
  }

  @Test
  void callEdges_areNonEmpty_forParsedFixtures() {
    // With Vaadin classpath resolved, there should be some call edges
    assertThat(acc.getCallEdges()).isNotEmpty();
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
    String classpathFile;
    try {
      classpathFile = buildVaadinClasspathFile();
    } catch (Exception e) {
      classpathFile = "";
    }
    return parser.parse(sources, projectRoot, classpathFile);
  }

  private String buildVaadinClasspathFile() throws Exception {
    // Write ALL test classpath JARs so Spring Data, Vaadin, and JPA types resolve
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
