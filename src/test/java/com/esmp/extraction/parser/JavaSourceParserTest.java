package com.esmp.extraction.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.esmp.extraction.visitor.ExtractionAccumulator;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openrewrite.SourceFile;

/** Unit tests for JavaSourceParser and ClasspathLoader. */
class JavaSourceParserTest {

  private JavaSourceParser parser;
  private Path fixturesDir;
  private Path projectRoot;

  @BeforeEach
  void setUp() throws URISyntaxException {
    ClasspathLoader loader = new ClasspathLoader();
    parser = new JavaSourceParser(loader);
    fixturesDir =
        Paths.get(
            Objects.requireNonNull(getClass().getClassLoader().getResource("fixtures")).toURI());
    projectRoot = fixturesDir.getParent();
  }

  // --- ClasspathLoader tests ---

  @Test
  void classpathLoader_returnsEmptyList_whenFileDoesNotExist() {
    ClasspathLoader loader = new ClasspathLoader();
    List<Path> result = loader.load("/nonexistent/path/classpath.txt");
    assertThat(result).isEmpty();
  }

  @Test
  void classpathLoader_skipsNonExistentPaths_andLogsWarning() throws IOException {
    Path tempFile = Files.createTempFile("classpath-test", ".txt");
    try {
      Files.writeString(
          tempFile,
          "/nonexistent/jar1.jar\n"
              + System.getProperty("java.home")
              + "/lib/rt.jar\n"
              + "/another/missing.jar\n");
      ClasspathLoader loader = new ClasspathLoader();
      // Should not throw; just returns existing paths
      assertThatCode(() -> loader.load(tempFile.toString())).doesNotThrowAnyException();
      List<Path> result = loader.load(tempFile.toString());
      // Non-existent paths must be filtered out
      assertThat(result).allMatch(Files::exists, "all returned paths should exist on disk");
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  // --- JavaSourceParser tests ---

  @Test
  void parser_parsesAllSixFixtureFiles() throws Exception {
    List<Path> sources = collectJavaSources(fixturesDir);
    assertThat(sources).hasSize(6);

    String classpathFilePath = buildVaadinClasspathFile();
    List<SourceFile> result = parser.parse(sources, projectRoot, classpathFilePath);

    assertThat(result).hasSize(6);
  }

  @Test
  void parser_returnsEmptyList_forEmptySourceList() {
    List<SourceFile> result = parser.parse(Collections.emptyList(), projectRoot, "");
    assertThat(result).isEmpty();
  }

  @Test
  void parser_handlesNullClasspathFile_gracefully() throws Exception {
    List<Path> sources = collectJavaSources(fixturesDir);
    // Should not throw even when classpath file path is null or empty
    assertThatCode(() -> parser.parse(sources, projectRoot, null)).doesNotThrowAnyException();
    assertThatCode(() -> parser.parse(sources, projectRoot, "")).doesNotThrowAnyException();
  }

  @Test
  void parser_handlesMalformedJavaSource_withoutException() throws Exception {
    Path tempDir = Files.createTempDirectory("malformed-test");
    Path malformed = tempDir.resolve("Malformed.java");
    try {
      Files.writeString(malformed, "this is not valid java {{ syntax error !!!");
      assertThatCode(() -> parser.parse(List.of(malformed), tempDir, ""))
          .doesNotThrowAnyException();
    } finally {
      Files.deleteIfExists(malformed);
      Files.deleteIfExists(tempDir);
    }
  }

  // --- ExtractionAccumulator tests ---

  @Test
  void accumulator_collectsClassesMethodsFieldsAndEdges() {
    ExtractionAccumulator acc = new ExtractionAccumulator();
    assertThat(acc.getClasses()).isEmpty();
    assertThat(acc.getMethods()).isEmpty();
    assertThat(acc.getFields()).isEmpty();
    assertThat(acc.getCallEdges()).isEmpty();
    assertThat(acc.getComponentEdges()).isEmpty();

    acc.addClass(
        "com.example.Foo",
        "Foo",
        "com.example",
        List.of(),
        List.of(),
        false,
        false,
        false,
        null,
        List.of(),
        "/Foo.java",
        "abc123");
    acc.addMethod(
        "com.example.Foo#doSomething(String)",
        "doSomething",
        "String",
        "com.example.Foo",
        List.of("String"),
        List.of(),
        List.of(),
        false);
    acc.addField(
        "com.example.Foo#value", "value", "String", "com.example.Foo", List.of(), List.of());
    acc.addCall("com.example.Foo#doSomething(String)", "com.example.Bar#handle()", "/Foo.java", 10);
    acc.addComponentEdge("com.example.Foo", "com.example.Bar", "VerticalLayout", "Button");

    assertThat(acc.getClasses()).hasSize(1);
    assertThat(acc.getMethods()).hasSize(1);
    assertThat(acc.getFields()).hasSize(1);
    assertThat(acc.getCallEdges()).hasSize(1);
    assertThat(acc.getComponentEdges()).hasSize(1);
  }

  @Test
  void accumulator_deduplicates_onSameKey() {
    ExtractionAccumulator acc = new ExtractionAccumulator();
    acc.addClass(
        "com.example.Foo",
        "Foo",
        "com.example",
        List.of(),
        List.of(),
        false,
        false,
        false,
        null,
        List.of(),
        "/Foo.java",
        "abc123");
    // Adding same FQN again should update the entry (not create duplicate)
    acc.addClass(
        "com.example.Foo",
        "Foo",
        "com.example",
        List.of("@Service"),
        List.of(),
        false,
        false,
        false,
        null,
        List.of(),
        "/Foo.java",
        "abc456");
    assertThat(acc.getClasses()).hasSize(1);
  }

  // --- Helpers ---

  private List<Path> collectJavaSources(Path dir) throws IOException {
    // maxDepth(1) to scan only the top-level fixtures directory, not subdirectories
    // (subdirectories like fixtures/lexicon/ contain lexicon-specific fixtures)
    try (var stream = Files.walk(dir, 1)) {
      return stream.filter(p -> p.toString().endsWith(".java")).toList();
    }
  }

  /**
   * Writes a classpath file containing the vaadin-server JAR path so the parser can resolve Vaadin
   * 7 types during fixture parsing.
   */
  private String buildVaadinClasspathFile() throws Exception {
    // Resolve vaadin-server JAR location from the test classpath
    String vaadinJarPath = resolveJarPath("com.vaadin.ui.UI");
    if (vaadinJarPath == null) {
      return ""; // degrade gracefully
    }
    Path tempFile = Files.createTempFile("vaadin-classpath", ".txt");
    tempFile.toFile().deleteOnExit();
    Files.writeString(tempFile, vaadinJarPath);
    return tempFile.toString();
  }

  private String resolveJarPath(String fqClassName) {
    try {
      Class<?> clazz = Class.forName(fqClassName);
      var location = clazz.getProtectionDomain().getCodeSource().getLocation();
      if (location != null) {
        return Paths.get(location.toURI()).toString();
      }
    } catch (Exception e) {
      // Not on classpath — return null and degrade gracefully
    }
    return null;
  }
}
