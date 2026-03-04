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
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;

/** Unit tests for {@link VaadinPatternVisitor}. Verifies Vaadin 7 pattern detection. */
class VaadinPatternVisitorTest {

  private ExtractionAccumulator acc;

  @BeforeEach
  void setUp() throws Exception {
    List<SourceFile> parsedFixtures = parseFixtures();
    acc = new ExtractionAccumulator();
    VaadinPatternVisitor visitor = new VaadinPatternVisitor();
    for (SourceFile source : parsedFixtures) {
      visitor.visit(source, acc);
    }
  }

  @Test
  void marksSampleVaadinView_asVaadinView() {
    assertThat(acc.getVaadinViews())
        .as(
            "SampleVaadinView should be marked as VaadinView (implements com.vaadin.navigator.View)")
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
    // SampleVaadinView.addComponent(titleLabel), addComponent(refreshButton),
    // addComponent(customerTable)
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

  @Test
  void detectsBindsToEdge_fromBeanFieldGroupInstantiation() {
    // SampleVaadinForm uses new BeanFieldGroup<>(SampleEntity.class)
    assertThat(acc.getBindsToEdges()).isNotEmpty();
    assertThat(acc.getBindsToEdges())
        .anyMatch(e ->
            e.viewClassFqn().equals("com.example.sample.SampleVaadinForm")
            && e.entityClassFqn().equals("com.example.sample.SampleEntity")
            && e.bindingMechanism().equals("BeanFieldGroup"));
  }

  // ---------------------------------------------------------------------------
  // Simple-name fallback tests (Vaadin types NOT on classpath)
  // ---------------------------------------------------------------------------

  @Test
  void detectsBindsToEdge_viaSimpleName_whenBeanFieldGroupTypeIsUnresolved() {
    // Parse code WITHOUT Vaadin JAR so BeanFieldGroup type cannot be resolved by OpenRewrite
    String source = """
        package com.example.test;
        public class MyForm {
            private Object fieldGroup;
            public MyForm() {
                fieldGroup = new BeanFieldGroup(MyEntity.class);
            }
        }
        """;
    ExtractionAccumulator localAcc = parseInlineWithoutClasspath(source);

    // Even though type is unresolved, BINDS_TO edge should be emitted via simple-name fallback
    assertThat(localAcc.getBindsToEdges())
        .as("BINDS_TO edge should be emitted via simple-name fallback for BeanFieldGroup")
        .anyMatch(e ->
            e.viewClassFqn().equals("com.example.test.MyForm")
            && e.bindingMechanism().equals("BeanFieldGroup"));
  }

  @Test
  void detectsBindsToEdge_viaSimpleName_whenFieldGroupTypeIsUnresolved() {
    // Parse code WITHOUT Vaadin JAR so FieldGroup type cannot be resolved by OpenRewrite
    String source = """
        package com.example.test;
        public class MyForm {
            public MyForm() {
                Object fg = new FieldGroup();
            }
        }
        """;
    ExtractionAccumulator localAcc = parseInlineWithoutClasspath(source);

    assertThat(localAcc.getBindsToEdges())
        .as("BINDS_TO edge should be emitted via simple-name fallback for FieldGroup")
        .anyMatch(e ->
            e.viewClassFqn().equals("com.example.test.MyForm")
            && e.bindingMechanism().equals("FieldGroup"));
  }

  @Test
  void doesNotDetectBindsToEdge_forBeanItemContainer_viaSimpleName() {
    // BeanItemContainer is excluded from BINDS_TO — it's a data source, not a form binding
    String source = """
        package com.example.test;
        public class MyView {
            public MyView() {
                Object container = new BeanItemContainer(MyEntity.class);
            }
        }
        """;
    ExtractionAccumulator localAcc = parseInlineWithoutClasspath(source);

    assertThat(localAcc.getBindsToEdges())
        .as("BeanItemContainer should NOT emit BINDS_TO edge even via simple-name fallback")
        .isEmpty();
  }

  @Test
  void detectsVaadinComponent_viaSimpleName_whenTypeIsUnresolved() {
    // Parse code without Vaadin classpath — simple name of Button should still mark as VaadinComponent
    String source = """
        package com.example.test;
        public class MyView {
            public MyView() {
                Object btn = new Button("Click me");
            }
        }
        """;
    ExtractionAccumulator localAcc = parseInlineWithoutClasspath(source);

    assertThat(localAcc.getVaadinComponents())
        .as("VaadinComponent should be detected via simple name 'Button' when type is unresolved")
        .contains("com.example.test.MyView");
  }

  @Test
  void existingFqnBasedBindsToDetection_stillWorksAsRegression() {
    // Regression: fixture-based test with Vaadin classpath should still detect BINDS_TO via FQN
    assertThat(acc.getBindsToEdges())
        .as("FQN-based BINDS_TO detection must still work (regression)")
        .anyMatch(e ->
            e.viewClassFqn().equals("com.example.sample.SampleVaadinForm")
            && e.bindingMechanism().equals("BeanFieldGroup"));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private List<SourceFile> parseFixtures() throws URISyntaxException, IOException {
    Path fixturesDir =
        Paths.get(
            Objects.requireNonNull(getClass().getClassLoader().getResource("fixtures")).toURI());
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

  /**
   * Parses inline Java source WITHOUT any classpath so that type references like BeanFieldGroup
   * or Button cannot be resolved by OpenRewrite — simulating the no-Vaadin-JAR case.
   */
  private ExtractionAccumulator parseInlineWithoutClasspath(String source) {
    InMemoryExecutionContext ctx = new InMemoryExecutionContext(t -> {});
    JavaParser parser = JavaParser.fromJavaVersion()
        .typeCache(new JavaTypeCache())
        .logCompilationWarningsAndErrors(false)
        .build();
    List<SourceFile> sources = parser.parse(ctx, source)
        .toList();
    ExtractionAccumulator localAcc = new ExtractionAccumulator();
    VaadinPatternVisitor visitor = new VaadinPatternVisitor();
    for (SourceFile sf : sources) {
      visitor.visit(sf, localAcc);
    }
    return localAcc;
  }

  private String buildVaadinClasspathFile() throws Exception {
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
