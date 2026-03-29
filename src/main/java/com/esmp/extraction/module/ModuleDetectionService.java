package com.esmp.extraction.module;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Detects modules in a multi-module Gradle or Maven project and builds the inter-module dependency
 * graph as a sequence of topologically sorted waves.
 *
 * <p>Detection priority:
 * <ol>
 *   <li>If {@code settings.gradle} exists at {@code sourceRoot} → Gradle detection
 *   <li>Else if {@code pom.xml} with a {@code <modules>} element exists → Maven detection
 *   <li>Else → returns {@link BuildSystem#NONE} (single-module fallback)
 * </ol>
 */
@Service
public class ModuleDetectionService {

  private static final Logger log = LoggerFactory.getLogger(ModuleDetectionService.class);

  /**
   * Regex that matches module names inside Gradle {@code include} statements.
   * Handles colon-prefixed ({@code ":module-a"}) and plain ({@code "module-a"}) syntax.
   * Captures the part after any leading colon/dot.
   */
  private static final Pattern GRADLE_INCLUDE_PATTERN =
      Pattern.compile("['\"][:.]?([^'\":/]+)['\"]");

  /**
   * Regex that matches {@code project(':some-module')} and {@code project("some-module")}
   * dependency declarations in a {@code build.gradle} file.
   */
  private static final Pattern GRADLE_PROJECT_DEP_PATTERN =
      Pattern.compile("project\\s*\\(\\s*['\"][:.]?([^'\"]+)['\"]\\s*\\)");

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /**
   * Detects the build system and module structure rooted at {@code sourceRoot}.
   *
   * @param sourceRoot root directory of the project
   * @return detection result with module waves, skipped modules, and totals
   */
  public ModuleDetectionResult detect(Path sourceRoot) {
    Path settingsGradle = sourceRoot.resolve("settings.gradle");
    Path pomXml = sourceRoot.resolve("pom.xml");

    if (Files.exists(settingsGradle)) {
      log.info("Detected Gradle multi-module project at {}", sourceRoot);
      return detectGradle(sourceRoot);
    }

    if (Files.exists(pomXml) && hasMavenModules(pomXml)) {
      log.info("Detected Maven multi-module project at {}", sourceRoot);
      return detectMaven(sourceRoot, pomXml);
    }

    log.info("No recognized build file at {} — using NONE fallback", sourceRoot);
    return new ModuleDetectionResult(BuildSystem.NONE, List.of(), List.of(), 0, 0);
  }

  // -------------------------------------------------------------------------
  // Gradle detection
  // -------------------------------------------------------------------------

  private ModuleDetectionResult detectGradle(Path sourceRoot) {
    Path settingsGradle = sourceRoot.resolve("settings.gradle");
    List<String> moduleNames = parseGradleModuleNames(settingsGradle);
    log.debug("Gradle settings.gradle: found {} module names", moduleNames.size());

    Set<String> moduleNameSet = new HashSet<>(moduleNames);
    Map<String, ModuleDescriptor> validModules = new LinkedHashMap<>();
    List<ModuleDetectionResult.SkippedModule> skippedModules = new ArrayList<>();

    for (String name : moduleNames) {
      Path moduleDir = sourceRoot.resolve(name);
      Path sourceDir = moduleDir.resolve("src/main/java");
      Path compiledClassesDir = moduleDir.resolve("build/classes/java/main");

      if (!Files.isDirectory(sourceDir)) {
        String reason = "src/main/java directory not found at " + sourceDir;
        log.debug("Skipping module '{}': {}", name, reason);
        skippedModules.add(new ModuleDetectionResult.SkippedModule(name, reason));
        continue;
      }

      if (!Files.isDirectory(compiledClassesDir)) {
        String reason = "compiled classes not found at build/classes/java/main";
        log.debug("Skipping module '{}': {}", name, reason);
        skippedModules.add(new ModuleDetectionResult.SkippedModule(name, reason));
        continue;
      }

      List<String> deps = parseGradleProjectDeps(moduleDir.resolve("build.gradle"), moduleNameSet);
      List<Path> javaFiles = scanJavaFiles(sourceDir);

      validModules.put(name, new ModuleDescriptor(name, sourceDir, compiledClassesDir, deps, javaFiles));
    }

    return buildResult(BuildSystem.GRADLE, validModules, skippedModules, moduleNames.size());
  }

  /**
   * Parses module names from a {@code settings.gradle} file.
   * Only lines containing {@code include} are examined.
   */
  private List<String> parseGradleModuleNames(Path settingsGradle) {
    List<String> names = new ArrayList<>();
    if (!Files.exists(settingsGradle)) return names;

    try {
      List<String> lines = Files.readAllLines(settingsGradle);
      for (String line : lines) {
        if (!line.contains("include")) continue;
        Matcher m = GRADLE_INCLUDE_PATTERN.matcher(line);
        while (m.find()) {
          String name = m.group(1).trim();
          if (!name.isEmpty()) {
            names.add(name);
          }
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read settings.gradle at {}: {}", settingsGradle, e.getMessage());
    }
    return names;
  }

  /**
   * Extracts inter-module {@code project()} dependency declarations from a {@code build.gradle}.
   * Only dependencies whose names are in {@code knownModules} are returned (external references are
   * ignored).
   */
  private List<String> parseGradleProjectDeps(Path buildGradle, Set<String> knownModules) {
    List<String> deps = new ArrayList<>();
    if (!Files.exists(buildGradle)) return deps;

    try {
      String content = Files.readString(buildGradle);
      Matcher m = GRADLE_PROJECT_DEP_PATTERN.matcher(content);
      while (m.find()) {
        String dep = m.group(1).trim();
        if (knownModules.contains(dep)) {
          deps.add(dep);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read build.gradle at {}: {}", buildGradle, e.getMessage());
    }
    return deps;
  }

  // -------------------------------------------------------------------------
  // Maven detection
  // -------------------------------------------------------------------------

  private ModuleDetectionResult detectMaven(Path sourceRoot, Path rootPom) {
    String parentGroupId = extractMavenGroupId(rootPom);
    List<String> moduleNames = parseMavenModuleNames(rootPom);
    log.debug("Maven pom.xml: found {} module names, parentGroupId={}", moduleNames.size(), parentGroupId);

    Set<String> moduleNameSet = new HashSet<>(moduleNames);
    Map<String, ModuleDescriptor> validModules = new LinkedHashMap<>();
    List<ModuleDetectionResult.SkippedModule> skippedModules = new ArrayList<>();

    for (String name : moduleNames) {
      Path moduleDir = sourceRoot.resolve(name);
      Path sourceDir = moduleDir.resolve("src/main/java");
      Path compiledClassesDir = moduleDir.resolve("target/classes");

      if (!Files.isDirectory(sourceDir)) {
        String reason = "src/main/java directory not found at " + sourceDir;
        log.debug("Skipping Maven module '{}': {}", name, reason);
        skippedModules.add(new ModuleDetectionResult.SkippedModule(name, reason));
        continue;
      }

      if (!Files.isDirectory(compiledClassesDir)) {
        String reason = "compiled classes not found at target/classes";
        log.debug("Skipping Maven module '{}': {}", name, reason);
        skippedModules.add(new ModuleDetectionResult.SkippedModule(name, reason));
        continue;
      }

      List<String> deps = parseMavenInterModuleDeps(
          moduleDir.resolve("pom.xml"), moduleNameSet, parentGroupId);
      List<Path> javaFiles = scanJavaFiles(sourceDir);

      validModules.put(name, new ModuleDescriptor(name, sourceDir, compiledClassesDir, deps, javaFiles));
    }

    return buildResult(BuildSystem.MAVEN, validModules, skippedModules, moduleNames.size());
  }

  /**
   * Returns {@code true} if the given {@code pom.xml} contains a {@code <modules>} element with at
   * least one child {@code <module>} entry.
   */
  private boolean hasMavenModules(Path pomXml) {
    try {
      Document doc = parsePomXml(pomXml);
      if (doc == null) return false;
      NodeList modules = doc.getElementsByTagName("module");
      return modules.getLength() > 0;
    } catch (Exception e) {
      return false;
    }
  }

  private List<String> parseMavenModuleNames(Path rootPom) {
    List<String> names = new ArrayList<>();
    try {
      Document doc = parsePomXml(rootPom);
      if (doc == null) return names;
      NodeList modules = doc.getElementsByTagName("module");
      for (int i = 0; i < modules.getLength(); i++) {
        String name = modules.item(i).getTextContent().trim();
        if (!name.isEmpty()) names.add(name);
      }
    } catch (Exception e) {
      log.warn("Failed to parse Maven modules from {}: {}", rootPom, e.getMessage());
    }
    return names;
  }

  private String extractMavenGroupId(Path rootPom) {
    try {
      Document doc = parsePomXml(rootPom);
      if (doc == null) return "";
      // Only look at direct children of <project>
      Element projectEl = doc.getDocumentElement();
      NodeList children = projectEl.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        if (children.item(i) instanceof Element el && "groupId".equals(el.getTagName())) {
          return el.getTextContent().trim();
        }
      }
    } catch (Exception e) {
      log.warn("Failed to extract groupId from {}: {}", rootPom, e.getMessage());
    }
    return "";
  }

  /**
   * Extracts inter-module dependencies from a child module's {@code pom.xml}.
   *
   * <p>A dependency is considered inter-module if its {@code <groupId>} matches the parent's
   * groupId (or the Maven {@code ${project.groupId}} placeholder) AND its {@code <artifactId>}
   * is in the set of known module names.
   */
  private List<String> parseMavenInterModuleDeps(
      Path childPom, Set<String> knownModules, String parentGroupId) {
    List<String> deps = new ArrayList<>();
    if (!Files.exists(childPom)) return deps;

    try {
      Document doc = parsePomXml(childPom);
      if (doc == null) return deps;

      NodeList dependencyNodes = doc.getElementsByTagName("dependency");
      for (int i = 0; i < dependencyNodes.getLength(); i++) {
        Element dep = (Element) dependencyNodes.item(i);
        String groupId = getText(dep, "groupId");
        String artifactId = getText(dep, "artifactId");

        boolean isParentGroup = groupId.equals(parentGroupId)
            || groupId.equals("${project.groupId}")
            || groupId.equals("${project.parent.groupId}");

        if (isParentGroup && knownModules.contains(artifactId)) {
          deps.add(artifactId);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to parse Maven deps from {}: {}", childPom, e.getMessage());
    }
    return deps;
  }

  // -------------------------------------------------------------------------
  // Shared helpers
  // -------------------------------------------------------------------------

  private Document parsePomXml(Path pomXml) {
    if (!Files.exists(pomXml)) return null;
    try (InputStream is = Files.newInputStream(pomXml)) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      // Disable external entity resolution for security
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(null); // suppress stderr logging for malformed XML
      return builder.parse(is);
    } catch (Exception e) {
      log.warn("XML parse error for {}: {}", pomXml, e.getMessage());
      return null;
    }
  }

  private String getText(Element parent, String tagName) {
    NodeList nl = parent.getElementsByTagName(tagName);
    if (nl.getLength() == 0) return "";
    return nl.item(0).getTextContent().trim();
  }

  private List<Path> scanJavaFiles(Path sourceDir) {
    try (Stream<Path> stream = Files.walk(sourceDir)) {
      return stream
          .filter(p -> p.toString().endsWith(".java"))
          .toList();
    } catch (IOException e) {
      log.warn("Failed to scan java files under {}: {}", sourceDir, e.getMessage());
      return List.of();
    }
  }

  private ModuleDetectionResult buildResult(
      BuildSystem buildSystem,
      Map<String, ModuleDescriptor> validModules,
      List<ModuleDetectionResult.SkippedModule> skippedModules,
      int totalDeclared) {

    ModuleGraph graph = new ModuleGraph(validModules);
    graph.setSkippedModules(skippedModules);
    List<List<ModuleDescriptor>> waves = graph.computeWaves();

    int totalJavaFiles = validModules.values().stream()
        .mapToInt(m -> m.javaFiles().size())
        .sum();

    log.info("Module detection complete: buildSystem={}, declared={}, valid={}, skipped={}, waves={}, javaFiles={}",
        buildSystem, totalDeclared, validModules.size(), skippedModules.size(), waves.size(), totalJavaFiles);

    return new ModuleDetectionResult(buildSystem, waves, skippedModules, totalDeclared, totalJavaFiles);
  }
}
