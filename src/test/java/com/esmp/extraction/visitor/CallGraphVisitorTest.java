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

/** Unit tests for {@link CallGraphVisitor}. Verifies call edge extraction against synthetic fixtures. */
class CallGraphVisitorTest {

  private ExtractionAccumulator acc;

  @BeforeEach
  void setUp() throws Exception {
    List<SourceFile> parsedFixtures = parseFixtures();
    acc = new ExtractionAccumulator();
    InMemoryExecutionContext ctx = new InMemoryExecutionContext();
    CallGraphVisitor visitor = new CallGraphVisitor();
    for (SourceFile source : parsedFixtures) {
      visitor.visit(source, acc, ctx);
    }
  }

  @Test
  void detectsCallFromSampleService_findAll_to_repository_findAll() {
    // SampleService.findAll() -> SampleRepository.findAll()
    boolean found =
        acc.getCallEdges().stream()
            .anyMatch(
                e ->
                    e.callerMethodId().contains("SampleService")
                        && e.callerMethodId().contains("findAll")
                        && e.calleeMethodId().contains("SampleRepository")
                        && e.calleeMethodId().contains("findAll"));
    assertThat(found)
        .as("Expected call edge from SampleService#findAll to SampleRepository#findAll")
        .isTrue();
  }

  @Test
  void detectsCallFromSampleService_save_to_repository_save() {
    // SampleService.save() -> SampleRepository.save()
    boolean found =
        acc.getCallEdges().stream()
            .anyMatch(
                e ->
                    e.callerMethodId().contains("SampleService")
                        && e.callerMethodId().contains("save")
                        && e.calleeMethodId().contains("SampleRepository")
                        && e.calleeMethodId().contains("save"));
    assertThat(found)
        .as("Expected call edge from SampleService#save to SampleRepository#save")
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
