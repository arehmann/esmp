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

/** Unit tests for {@link VaadinPatternVisitor}. Verifies Vaadin 7 pattern detection. */
class VaadinPatternVisitorTest {

  private ExtractionAccumulator acc;

  @BeforeEach
  void setUp() throws Exception {
    List<SourceFile> parsedFixtures = parseFixtures();
    acc = new ExtractionAccumulator();
    InMemoryExecutionContext ctx = new InMemoryExecutionContext();
    VaadinPatternVisitor visitor = new VaadinPatternVisitor();
    for (SourceFile source : parsedFixtures) {
      visitor.visit(source, acc, ctx);
    }
  }

  @Test
  void marksSampleVaadinView_asVaadinView() {
    assertThat(acc.getVaadinViews())
        .as("SampleVaadinView should be marked as VaadinView (implements com.vaadin.navigator.View)")
        .contains("com.example.sample.SampleVaadinView");
  }

  @Test
  void marksSampleUI_asVaadinView() {
    assertThat(acc.getVaadinViews())
        .as("SampleUI should be marked as VaadinView (extends com.vaadin.ui.UI)")
        .contains("com.example.sample.SampleUI");
  }

  @Test
  void marksSampleVaadinForm_asVaadinDataBinding() {
    assertThat(acc.getVaadinDataBindings())
        .as("SampleVaadinForm should be marked as VaadinDataBinding (uses BeanFieldGroup)")
        .contains("com.example.sample.SampleVaadinForm");
  }

  @Test
  void detectsAddComponentCalls_inSampleVaadinView() {
    // SampleVaadinView.addComponent(titleLabel), addComponent(refreshButton), addComponent(customerTable)
    long edgesFromView =
        acc.getComponentEdges().stream()
            .filter(e -> e.parentClassFqn().contains("SampleVaadinView"))
            .count();
    assertThat(edgesFromView)
        .as("Expected CONTAINS_COMPONENT edges from SampleVaadinView addComponent() calls")
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void detectsVaadinComponent_fromNewExpressions() {
    // Classes that use Vaadin component new-expressions should be marked as VaadinComponent users
    // SampleVaadinView creates Label, Button, Table — should be marked
    assertThat(acc.getVaadinComponents()).isNotEmpty();
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
