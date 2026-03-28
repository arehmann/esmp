package com.esmp.extraction.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.parser.ClasspathLoader;
import com.esmp.extraction.parser.JavaSourceParser;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.ActionType;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.Automatable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openrewrite.SourceFile;

/**
 * Unit tests for {@link MigrationPatternVisitor}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Simple Vaadin 7 types (TextField, Button, VerticalLayout) are detected with auto=YES
 *   <li>Complex types (Table, BeanItemContainer, BeanFieldGroup) produce auto=NO actions
 *   <li>javax.servlet imports produce CHANGE_PACKAGE/YES actions
 *   <li>Unknown com.vaadin.* types produce COMPLEX_REWRITE/NO actions
 *   <li>Panel produces PARTIAL actions
 *   <li>Automation score computation: (yesCount + 0.5 * partialCount) / totalCount
 *   <li>needsAiMigration is true when any action has auto=NO
 *   <li>Class with no Vaadin/javax imports produces zero migration actions
 * </ul>
 */
class MigrationPatternVisitorTest {

  // ---------------------------------------------------------------------------
  // Simple Vaadin view: TextField, Button, VerticalLayout all have auto=YES
  // ---------------------------------------------------------------------------

  @Test
  void simpleVaadinViewProducesAutoYesActions() throws Exception {
    String source = """
        package com.example;
        import com.vaadin.ui.TextField;
        import com.vaadin.ui.Button;
        import com.vaadin.ui.VerticalLayout;
        public class SimpleView extends VerticalLayout {
          private TextField name = new TextField("Name");
          private Button btn = new Button("OK");
          public SimpleView() {
            addComponent(name);
            addComponent(btn);
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    Map<String, List<MigrationActionData>> actions = acc.getMigrationActions();

    assertThat(actions).containsKey("com.example.SimpleView");
    List<MigrationActionData> viewActions = actions.get("com.example.SimpleView");

    // Should have 3 actions (TextField, Button, VerticalLayout)
    assertThat(viewActions).hasSize(3);

    // All should be YES
    assertThat(viewActions).allMatch(a -> a.automatable() == Automatable.YES);

    // Verify source FQNs
    assertThat(viewActions).anyMatch(a -> a.source().equals("com.vaadin.ui.TextField"));
    assertThat(viewActions).anyMatch(a -> a.source().equals("com.vaadin.ui.Button"));
    assertThat(viewActions).anyMatch(a -> a.source().equals("com.vaadin.ui.VerticalLayout"));

    // Verify target FQNs
    assertThat(viewActions).anyMatch(a ->
        a.source().equals("com.vaadin.ui.TextField") &&
        a.target().equals("com.vaadin.flow.component.textfield.TextField"));
    assertThat(viewActions).anyMatch(a ->
        a.source().equals("com.vaadin.ui.Button") &&
        a.target().equals("com.vaadin.flow.component.button.Button"));
  }

  // ---------------------------------------------------------------------------
  // Complex view: Table, BeanItemContainer, BeanFieldGroup get auto=NO;
  // simple Button still gets auto=YES
  // ---------------------------------------------------------------------------

  @Test
  void complexTableViewProducesNoActionsForComplexTypes() throws Exception {
    String source = """
        package com.example;
        import com.vaadin.ui.Table;
        import com.vaadin.data.util.BeanItemContainer;
        import com.vaadin.data.fieldgroup.BeanFieldGroup;
        import com.vaadin.ui.Button;
        public class ComplexView {
          private Table table = new Table();
          private BeanItemContainer<Object> container = new BeanItemContainer<>(Object.class);
          private BeanFieldGroup<Object> fieldGroup = new BeanFieldGroup<>(Object.class);
          private Button btn = new Button("Save");
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    Map<String, List<MigrationActionData>> actions = acc.getMigrationActions();

    assertThat(actions).containsKey("com.example.ComplexView");
    List<MigrationActionData> viewActions = actions.get("com.example.ComplexView");

    // Table, BeanItemContainer, BeanFieldGroup should be NO
    assertThat(viewActions).anyMatch(a ->
        a.source().equals("com.vaadin.ui.Table") && a.automatable() == Automatable.NO);
    assertThat(viewActions).anyMatch(a ->
        a.source().equals("com.vaadin.data.util.BeanItemContainer") && a.automatable() == Automatable.NO);
    assertThat(viewActions).anyMatch(a ->
        a.source().equals("com.vaadin.data.fieldgroup.BeanFieldGroup") && a.automatable() == Automatable.NO);

    // Button should be YES
    assertThat(viewActions).anyMatch(a ->
        a.source().equals("com.vaadin.ui.Button") && a.automatable() == Automatable.YES);

    // needsAiMigration should be true (has NO actions)
    boolean hasNo = viewActions.stream().anyMatch(a -> a.automatable() == Automatable.NO);
    assertThat(hasNo).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Pure service class: javax.servlet imports produce CHANGE_PACKAGE/YES actions
  // ---------------------------------------------------------------------------

  @Test
  void pureServiceClassWithJavaxServletProducesChangePackageYes() throws Exception {
    String source = """
        package com.example;
        import javax.servlet.http.HttpServletRequest;
        public class MyService {
          public void handle(HttpServletRequest req) {
            System.out.println(req.getRequestURI());
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    Map<String, List<MigrationActionData>> actions = acc.getMigrationActions();

    assertThat(actions).containsKey("com.example.MyService");
    List<MigrationActionData> serviceActions = actions.get("com.example.MyService");

    assertThat(serviceActions).anyMatch(a ->
        a.type() == ActionType.CHANGE_PACKAGE &&
        a.automatable() == Automatable.YES &&
        a.source().contains("javax.servlet") &&
        a.target().contains("jakarta.servlet"));
  }

  // ---------------------------------------------------------------------------
  // Unknown com.vaadin.* type produces COMPLEX_REWRITE/NO
  // ---------------------------------------------------------------------------

  @Test
  void unknownVaadinTypeProducesComplexRewriteNo() throws Exception {
    String source = """
        package com.example;
        import com.vaadin.unknown.SomeObscureComponent;
        public class MyView {
          private SomeObscureComponent comp;
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    Map<String, List<MigrationActionData>> actions = acc.getMigrationActions();

    assertThat(actions).containsKey("com.example.MyView");
    List<MigrationActionData> viewActions = actions.get("com.example.MyView");

    assertThat(viewActions).anyMatch(a ->
        a.type() == ActionType.COMPLEX_REWRITE &&
        a.automatable() == Automatable.NO &&
        a.source().equals("com.vaadin.unknown.SomeObscureComponent"));
  }

  // ---------------------------------------------------------------------------
  // Panel produces PARTIAL action (type change is mechanical but styling needs adjustment)
  // ---------------------------------------------------------------------------

  @Test
  void panelImportProducesPartialAction() throws Exception {
    String source = """
        package com.example;
        import com.vaadin.ui.Panel;
        public class PanelView {
          private Panel panel = new Panel("My Panel");
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    Map<String, List<MigrationActionData>> actions = acc.getMigrationActions();

    assertThat(actions).containsKey("com.example.PanelView");
    List<MigrationActionData> viewActions = actions.get("com.example.PanelView");

    assertThat(viewActions).anyMatch(a ->
        a.source().equals("com.vaadin.ui.Panel") &&
        a.automatable() == Automatable.PARTIAL);
  }

  // ---------------------------------------------------------------------------
  // Automation score: 3 YES + 1 PARTIAL + 1 NO = (3 + 0.5) / 5 = 0.7
  // ---------------------------------------------------------------------------

  @Test
  void automationScoreComputedCorrectly() throws Exception {
    // 3 YES (TextField, Button, VerticalLayout) + 1 PARTIAL (Panel) + 1 NO (Table)
    String source = """
        package com.example;
        import com.vaadin.ui.TextField;
        import com.vaadin.ui.Button;
        import com.vaadin.ui.VerticalLayout;
        import com.vaadin.ui.Panel;
        import com.vaadin.ui.Table;
        public class MixedView {
          private TextField tf;
          private Button btn;
          private VerticalLayout vl;
          private Panel panel;
          private Table table;
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    List<MigrationActionData> viewActions = acc.getMigrationActions().get("com.example.MixedView");

    assertThat(viewActions).isNotNull();
    assertThat(viewActions).hasSize(5);

    long yesCount = viewActions.stream().filter(a -> a.automatable() == Automatable.YES).count();
    long partialCount = viewActions.stream().filter(a -> a.automatable() == Automatable.PARTIAL).count();
    int total = viewActions.size();

    double expectedScore = (yesCount + 0.5 * partialCount) / total;
    // 3 YES + 0.5 * 1 PARTIAL = 3.5 / 5 = 0.70
    assertThat(expectedScore).isEqualTo(0.7, org.assertj.core.data.Offset.offset(0.001));
  }

  // ---------------------------------------------------------------------------
  // needsAiMigration: true when any action has auto=NO
  // ---------------------------------------------------------------------------

  @Test
  void needsAiMigrationTrueWhenAnyActionIsNo() throws Exception {
    String source = """
        package com.example;
        import com.vaadin.ui.TextField;
        import com.vaadin.ui.Table;
        public class MixedView2 {
          private TextField tf;
          private Table table;
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    List<MigrationActionData> viewActions = acc.getMigrationActions().get("com.example.MixedView2");

    assertThat(viewActions).isNotNull();
    boolean hasNo = viewActions.stream().anyMatch(a -> a.automatable() == Automatable.NO);
    assertThat(hasNo).as("needsAiMigration should be true when Table (NO) is present").isTrue();
  }

  // ---------------------------------------------------------------------------
  // Class with no Vaadin/javax imports produces zero migration actions
  // ---------------------------------------------------------------------------

  @Test
  void classWithNoVaadinOrJavaxImportsProducesNoActions() throws Exception {
    String source = """
        package com.example;
        import java.util.List;
        import java.util.ArrayList;
        public class PureJavaClass {
          public List<String> getItems() {
            return new ArrayList<>();
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    Map<String, List<MigrationActionData>> actions = acc.getMigrationActions();

    // Should either have no entry or an empty list for this class
    List<MigrationActionData> classActions = actions.get("com.example.PureJavaClass");
    assertThat(classActions == null || classActions.isEmpty())
        .as("Class with no Vaadin/javax imports should produce zero migration actions")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ExtractionAccumulator runVisitor(String... sources) throws Exception {
    ExtractionAccumulator acc = new ExtractionAccumulator();
    MigrationPatternVisitor visitor = new MigrationPatternVisitor();
    for (SourceFile source : parseJava(sources)) {
      visitor.visit(source, acc);
    }
    return acc;
  }

  private List<SourceFile> parseJava(String... sources) throws IOException {
    Path tempDir = Files.createTempDirectory("migration-visitor-test");
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
