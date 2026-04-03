package com.esmp.extraction.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.config.MigrationConfig;
import com.esmp.extraction.parser.ClasspathLoader;
import com.esmp.extraction.parser.JavaSourceParser;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.ActionType;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.Automatable;
import com.esmp.migration.application.RecipeBookRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openrewrite.SourceFile;

/**
 * Unit tests for {@link MigrationPatternVisitor} — Alfa* detection path.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Known Alfa* type (AlfaButton) produces a MigrationActionData with actionType=CHANGE_TYPE
 *       (resolved from the ALFA-B-001 rule in the Alfa* overlay)
 *   <li>Unknown Alfa* type produces fallback COMPLEX_REWRITE/NO action
 *   <li>Existing Vaadin 7 import detection is unaffected by the Alfa* overlay
 * </ul>
 */
class MigrationPatternVisitorAlfaTest {

  private static RecipeBookRegistry registry;

  @BeforeAll
  static void setupRegistry() throws IOException {
    // Build registry with Alfa* overlay loaded from classpath
    Path tempDir = Files.createTempDirectory("alfa-visitor-test-registry");
    MigrationConfig config = new MigrationConfig();
    config.setRecipeBookPath(tempDir.resolve("vaadin-recipe-book.json").toString());
    config.setCustomRecipeBookPath("");
    config.setAlfaOverlayPath("classpath:/migration/alfa-recipe-book-overlay.json");

    registry = new RecipeBookRegistry(config, new ObjectMapper());
    registry.load();
  }

  // ---------------------------------------------------------------------------
  // Known Alfa* type: AlfaButton -> CHANGE_TYPE (from ALFA-B-001 rule)
  // ---------------------------------------------------------------------------

  @Test
  void knownAlfaButtonProducesMigrationAction() throws Exception {
    String source = """
        package com.test;
        import com.alfa.ui.AlfaButton;
        public class TestView {
          AlfaButton btn;
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    Map<String, List<MigrationActionData>> actions = acc.getMigrationActions();

    assertThat(actions).containsKey("com.test.TestView");
    List<MigrationActionData> viewActions = actions.get("com.test.TestView");

    assertThat(viewActions)
        .as("Should have exactly one action for AlfaButton import")
        .hasSize(1);

    MigrationActionData action = viewActions.get(0);
    assertThat(action.source())
        .as("Source FQN must be AlfaButton")
        .isEqualTo("com.alfa.ui.AlfaButton");
    assertThat(action.type())
        .as("ALFA-B-001 rule specifies CHANGE_TYPE actionType")
        .isEqualTo(ActionType.CHANGE_TYPE);
    assertThat(action.automatable())
        .as("ALFA-B-001 rule specifies automatable=YES")
        .isEqualTo(Automatable.YES);
  }

  // ---------------------------------------------------------------------------
  // Unknown Alfa* type: fallback COMPLEX_REWRITE/NO
  // ---------------------------------------------------------------------------

  @Test
  void unknownAlfaTypeProducesFallbackAction() throws Exception {
    String source = """
        package com.test;
        import com.alfa.ui.AlfaUnknownWidget99;
        public class TestWidget {
          AlfaUnknownWidget99 widget;
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    Map<String, List<MigrationActionData>> actions = acc.getMigrationActions();

    assertThat(actions).containsKey("com.test.TestWidget");
    List<MigrationActionData> widgetActions = actions.get("com.test.TestWidget");

    assertThat(widgetActions)
        .as("Should have exactly one fallback action for unknown Alfa* type")
        .hasSize(1);

    MigrationActionData action = widgetActions.get(0);
    assertThat(action.source())
        .as("Source FQN must be the unknown Alfa* type")
        .isEqualTo("com.alfa.ui.AlfaUnknownWidget99");
    assertThat(action.type())
        .as("Fallback action type must be COMPLEX_REWRITE")
        .isEqualTo(ActionType.COMPLEX_REWRITE);
    assertThat(action.automatable())
        .as("Fallback must be NOT automatable")
        .isEqualTo(Automatable.NO);
  }

  // ---------------------------------------------------------------------------
  // Vaadin 7 import detection is still working alongside Alfa* detection
  // ---------------------------------------------------------------------------

  @Test
  void vaadinImportStillDetected() throws Exception {
    String source = """
        package com.test;
        import com.vaadin.ui.Button;
        public class VaadinView {
          com.vaadin.ui.Button btn;
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);
    Map<String, List<MigrationActionData>> actions = acc.getMigrationActions();

    assertThat(actions).containsKey("com.test.VaadinView");
    List<MigrationActionData> viewActions = actions.get("com.test.VaadinView");

    assertThat(viewActions)
        .as("Should detect the com.vaadin.ui.Button import")
        .anyMatch(a -> a.source().equals("com.vaadin.ui.Button"));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ExtractionAccumulator runVisitor(String... sources) throws Exception {
    ExtractionAccumulator acc = new ExtractionAccumulator();
    MigrationPatternVisitor visitor = new MigrationPatternVisitor(registry);
    for (SourceFile source : parseJava(sources)) {
      visitor.visit(source, acc);
    }
    return acc;
  }

  private List<SourceFile> parseJava(String... sources) throws IOException {
    Path tempDir = Files.createTempDirectory("alfa-visitor-test");
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
