package com.esmp.vector.application;

import com.esmp.extraction.util.ModuleDeriver;
import com.esmp.vector.model.ChunkType;
import com.esmp.vector.model.CodeChunk;
import com.esmp.vector.model.DomainTermRef;
import com.esmp.vector.util.ChunkIdGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Reads JavaClass and JavaMethod nodes from Neo4j, fetches their source files from disk, and
 * produces enriched {@link CodeChunk} records ready for embedding and Qdrant upsert.
 *
 * <p>For each class with a non-null {@code sourceFilePath}, this service produces:
 * <ol>
 *   <li>One {@link ChunkType#CLASS_HEADER} chunk containing the class javadoc, package, and field
 *       declarations.
 *   <li>One {@link ChunkType#METHOD} chunk per method declared by the class.
 * </ol>
 *
 * <p>All chunks carry class-level enrichment: 1-hop graph neighbours (callers, callees,
 * dependencies, implementors), domain terms from {@code USES_TERM} edges, full risk breakdown, and
 * Vaadin 7 migration state.
 *
 * <p>Follows the {@code RiskService} pattern: decoupled from the extraction pipeline, callable
 * independently after the full extraction + linking + risk computation has run.
 */
@Service
public class ChunkingService {

  private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

  /** Vaadin stereotype labels that indicate Vaadin 7 usage. */
  private static final Set<String> VAADIN7_LABELS =
      Set.of("VaadinView", "VaadinComponent", "VaadinDataBinding");

  /** Primary stereotype labels in priority order. */
  private static final List<String> STEREOTYPE_PRIORITY =
      List.of("Service", "Repository", "VaadinView", "Controller");

  private final Neo4jClient neo4jClient;

  public ChunkingService(Neo4jClient neo4jClient) {
    this.neo4jClient = neo4jClient;
  }

  /**
   * Chunks all JavaClass nodes that have a non-null sourceFilePath, reads source files from disk,
   * and returns a flat list of enriched {@link CodeChunk} records.
   *
   * @param sourceRoot base path prepended to relative sourcePaths (may be empty string for
   *     absolute paths stored in Neo4j)
   * @return all class-header + method chunks for all processable classes
   */
  public List<CodeChunk> chunkClasses(String sourceRoot) {
    log.info("Starting chunking pass (sourceRoot='{}')", sourceRoot);

    Collection<Map<String, Object>> classRows = queryAllClasses();
    log.info("Found {} JavaClass nodes with non-null sourceFilePath.", classRows.size());

    List<CodeChunk> result = new ArrayList<>();

    for (Map<String, Object> row : classRows) {
      String fqn = (String) row.get("fqn");
      String simpleName = (String) row.get("simpleName");
      String pkg = (String) row.get("pkg");
      String sourcePath = (String) row.get("sourcePath");
      String contentHash = (String) row.get("hash");
      double srs = toDouble(row.get("srs"));
      double ers = toDouble(row.get("ers"));
      double dc = toDouble(row.get("dc"));
      double ss = toDouble(row.get("ss"));
      double fi = toDouble(row.get("fi"));
      double brd = toDouble(row.get("brd"));
      @SuppressWarnings("unchecked")
      List<String> labels = (List<String>) row.getOrDefault("labels", List.of());

      // Guard: skip if source file is missing
      Path path = resolveSourcePath(sourceRoot, sourcePath);
      if (!Files.exists(path)) {
        log.debug("Skipping '{}' — source file not found: {}", fqn, path);
        continue;
      }

      String source;
      try {
        source = Files.readString(path);
      } catch (IOException e) {
        log.warn("Could not read source file for '{}' at '{}': {}", fqn, path, e.getMessage());
        continue;
      }

      // Enrich from graph
      Map<String, Object> enrichment = queryEnrichment(fqn);
      @SuppressWarnings("unchecked")
      List<String> callers = nullSafeList((List<String>) enrichment.get("callers"));
      @SuppressWarnings("unchecked")
      List<String> dependencies = nullSafeList((List<String>) enrichment.get("dependencies"));
      @SuppressWarnings("unchecked")
      List<String> implementors = nullSafeList((List<String>) enrichment.get("implementors"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> termMaps =
          nullSafeList((List<Map<String, Object>>) enrichment.get("terms"));
      @SuppressWarnings("unchecked")
      List<String> methodIds = nullSafeList((List<String>) enrichment.get("methodIds"));

      List<String> callees = queryCallees(fqn);

      List<DomainTermRef> domainTerms = termMaps.stream()
          .filter(t -> t != null && t.get("termId") != null)
          .map(t -> new DomainTermRef((String) t.get("termId"), (String) t.get("displayName")))
          .collect(Collectors.toList());

      // Vaadin detection
      List<String> vaadinPatterns = labels.stream()
          .filter(VAADIN7_LABELS::contains)
          .collect(Collectors.toList());
      boolean vaadin7Detected = !vaadinPatterns.isEmpty();

      // Stereotype + module
      String stereotype = labels.stream()
          .filter(STEREOTYPE_PRIORITY::contains)
          .findFirst()
          .orElse("");
      String module = row.get("module") instanceof String s && !s.isBlank()
          ? s : ModuleDeriver.fromPackageName(pkg);

      // Build CLASS_HEADER chunk
      UUID headerId = ChunkIdGenerator.chunkId(fqn, "__HEADER__");
      String headerText = buildClassHeaderText(simpleName, pkg, source);

      CodeChunk header = new CodeChunk(
          headerId, ChunkType.CLASS_HEADER, fqn, null, null,
          headerText, module, stereotype, contentHash,
          srs, ers, dc, ss, fi, brd,
          vaadin7Detected, vaadinPatterns,
          callers, callees, dependencies, implementors, domainTerms);
      result.add(header);

      // Build METHOD chunks
      for (String methodId : methodIds) {
        String methodSignature = methodSignatureFrom(methodId);
        UUID methodPointId = ChunkIdGenerator.chunkId(fqn, methodSignature);
        String methodText = buildMethodText(simpleName, methodSignature, source);

        CodeChunk method = new CodeChunk(
            methodPointId, ChunkType.METHOD, fqn, methodId, headerId.toString(),
            methodText, module, stereotype, contentHash,
            srs, ers, dc, ss, fi, brd,
            vaadin7Detected, vaadinPatterns,
            callers, callees, dependencies, implementors, domainTerms);
        result.add(method);
      }
    }

    log.info("Chunking complete: {} total chunks produced.", result.size());
    return result;
  }

  /**
   * Chunks only the specified JavaClass FQNs. Identical to {@link #chunkClasses(String)} but
   * limits the Neo4j query to the supplied FQN list.
   *
   * <p>This is the performance-critical overload used by the incremental indexing pipeline
   * (SLO-03): instead of chunking all classes, only the changed classes are re-chunked and
   * re-embedded.
   *
   * @param fqns       list of fully-qualified class names to chunk; if empty, returns an empty list
   * @param sourceRoot base path prepended to relative sourcePaths (may be empty string)
   * @return class-header + method chunks for the specified classes only
   */
  public List<CodeChunk> chunkByFqns(List<String> fqns, String sourceRoot) {
    if (fqns == null || fqns.isEmpty()) {
      return List.of();
    }
    log.info("Starting selective chunking pass for {} FQNs (sourceRoot='{}')", fqns.size(), sourceRoot);

    Collection<Map<String, Object>> classRows = queryClassesByFqns(fqns);
    log.info("Found {} JavaClass nodes for FQN-filtered chunking.", classRows.size());

    List<CodeChunk> result = new ArrayList<>();

    for (Map<String, Object> row : classRows) {
      String fqn = (String) row.get("fqn");
      String simpleName = (String) row.get("simpleName");
      String pkg = (String) row.get("pkg");
      String sourcePath = (String) row.get("sourcePath");
      String contentHash = (String) row.get("hash");
      double srs = toDouble(row.get("srs"));
      double ers = toDouble(row.get("ers"));
      double dc = toDouble(row.get("dc"));
      double ss = toDouble(row.get("ss"));
      double fi = toDouble(row.get("fi"));
      double brd = toDouble(row.get("brd"));
      @SuppressWarnings("unchecked")
      List<String> labels = (List<String>) row.getOrDefault("labels", List.of());

      // Guard: skip if source file is missing
      Path path = resolveSourcePath(sourceRoot, sourcePath);
      if (!Files.exists(path)) {
        log.debug("Skipping '{}' — source file not found: {}", fqn, path);
        continue;
      }

      String source;
      try {
        source = Files.readString(path);
      } catch (IOException e) {
        log.warn("Could not read source file for '{}' at '{}': {}", fqn, path, e.getMessage());
        continue;
      }

      // Enrich from graph
      Map<String, Object> enrichment = queryEnrichment(fqn);
      @SuppressWarnings("unchecked")
      List<String> callers = nullSafeList((List<String>) enrichment.get("callers"));
      @SuppressWarnings("unchecked")
      List<String> dependencies = nullSafeList((List<String>) enrichment.get("dependencies"));
      @SuppressWarnings("unchecked")
      List<String> implementors = nullSafeList((List<String>) enrichment.get("implementors"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> termMaps =
          nullSafeList((List<Map<String, Object>>) enrichment.get("terms"));
      @SuppressWarnings("unchecked")
      List<String> methodIds = nullSafeList((List<String>) enrichment.get("methodIds"));

      List<String> callees = queryCallees(fqn);

      List<DomainTermRef> domainTerms = termMaps.stream()
          .filter(t -> t != null && t.get("termId") != null)
          .map(t -> new DomainTermRef((String) t.get("termId"), (String) t.get("displayName")))
          .collect(Collectors.toList());

      // Vaadin detection
      List<String> vaadinPatterns = labels.stream()
          .filter(VAADIN7_LABELS::contains)
          .collect(Collectors.toList());
      boolean vaadin7Detected = !vaadinPatterns.isEmpty();

      // Stereotype + module
      String stereotype = labels.stream()
          .filter(STEREOTYPE_PRIORITY::contains)
          .findFirst()
          .orElse("");
      String module = row.get("module") instanceof String s && !s.isBlank()
          ? s : ModuleDeriver.fromPackageName(pkg);

      // Build CLASS_HEADER chunk
      UUID headerId = ChunkIdGenerator.chunkId(fqn, "__HEADER__");
      String headerText = buildClassHeaderText(simpleName, pkg, source);

      CodeChunk header = new CodeChunk(
          headerId, ChunkType.CLASS_HEADER, fqn, null, null,
          headerText, module, stereotype, contentHash,
          srs, ers, dc, ss, fi, brd,
          vaadin7Detected, vaadinPatterns,
          callers, callees, dependencies, implementors, domainTerms);
      result.add(header);

      // Build METHOD chunks
      for (String methodId : methodIds) {
        String methodSignature = methodSignatureFrom(methodId);
        UUID methodPointId = ChunkIdGenerator.chunkId(fqn, methodSignature);
        String methodText = buildMethodText(simpleName, methodSignature, source);

        CodeChunk method = new CodeChunk(
            methodPointId, ChunkType.METHOD, fqn, methodId, headerId.toString(),
            methodText, module, stereotype, contentHash,
            srs, ers, dc, ss, fi, brd,
            vaadin7Detected, vaadinPatterns,
            callers, callees, dependencies, implementors, domainTerms);
        result.add(method);
      }
    }

    log.info("Selective chunking complete: {} total chunks produced for {} FQNs.", result.size(), fqns.size());
    return result;
  }

  // -------------------------------------------------------------------------
  // Neo4j queries
  // -------------------------------------------------------------------------

  private Collection<Map<String, Object>> queryAllClasses() {
    String cypher =
        "MATCH (c:JavaClass) WHERE c.sourceFilePath IS NOT NULL "
            + "RETURN c.fullyQualifiedName AS fqn, c.simpleName AS simpleName, "
            + "c.packageName AS pkg, c.sourceFilePath AS sourcePath, "
            + "c.contentHash AS hash, c.module AS module, "
            + "c.structuralRiskScore AS srs, c.enhancedRiskScore AS ers, "
            + "c.domainCriticality AS dc, c.securitySensitivity AS ss, "
            + "c.financialInvolvement AS fi, c.businessRuleDensity AS brd, "
            + "labels(c) AS labels";
    return neo4jClient.query(cypher).fetch().all();
  }

  /**
   * Returns class rows for only the specified FQN list. Mirrors {@link #queryAllClasses()} but
   * adds a {@code WHERE c.fullyQualifiedName IN $fqns} filter for selective chunking.
   *
   * @param fqns non-empty list of fully-qualified class names
   * @return rows containing the same columns as {@link #queryAllClasses()}
   */
  private Collection<Map<String, Object>> queryClassesByFqns(List<String> fqns) {
    String cypher =
        "MATCH (c:JavaClass) "
            + "WHERE c.sourceFilePath IS NOT NULL AND c.fullyQualifiedName IN $fqns "
            + "RETURN c.fullyQualifiedName AS fqn, c.simpleName AS simpleName, "
            + "c.packageName AS pkg, c.sourceFilePath AS sourcePath, "
            + "c.contentHash AS hash, c.module AS module, "
            + "c.structuralRiskScore AS srs, c.enhancedRiskScore AS ers, "
            + "c.domainCriticality AS dc, c.securitySensitivity AS ss, "
            + "c.financialInvolvement AS fi, c.businessRuleDensity AS brd, "
            + "labels(c) AS labels";
    return neo4jClient.query(cypher)
        .bind(fqns).to("fqns")
        .fetch()
        .all();
  }

  private Map<String, Object> queryEnrichment(String fqn) {
    String cypher =
        "MATCH (c:JavaClass {fullyQualifiedName: $fqn}) "
            + "OPTIONAL MATCH (caller:JavaClass)-[:DEPENDS_ON]->(c) "
            + "OPTIONAL MATCH (c)-[:DEPENDS_ON]->(dep:JavaClass) "
            + "OPTIONAL MATCH (c)-[:IMPLEMENTS]->(iface:JavaClass) "
            + "OPTIONAL MATCH (c)-[:USES_TERM]->(t:BusinessTerm) "
            + "OPTIONAL MATCH (c)-[:DECLARES_METHOD]->(m:JavaMethod) "
            + "RETURN collect(DISTINCT caller.fullyQualifiedName) AS callers, "
            + "collect(DISTINCT dep.fullyQualifiedName) AS dependencies, "
            + "collect(DISTINCT iface.fullyQualifiedName) AS implementors, "
            + "collect(DISTINCT {termId: t.termId, displayName: t.displayName}) AS terms, "
            + "collect(DISTINCT m.methodId) AS methodIds";
    return neo4jClient.query(cypher)
        .bind(fqn).to("fqn")
        .fetch()
        .one()
        .orElse(Map.of());
  }

  private List<String> queryCallees(String fqn) {
    String cypher =
        "MATCH (c:JavaClass {fullyQualifiedName: $fqn})"
            + "-[:DECLARES_METHOD]->(m:JavaMethod)-[:CALLS]->(target:JavaMethod) "
            + "RETURN collect(DISTINCT target.declaringClass) AS callees";
    return neo4jClient.query(cypher)
        .bind(fqn).to("fqn")
        .fetch()
        .one()
        .map(row -> {
          @SuppressWarnings("unchecked")
          List<String> callees = (List<String>) row.get("callees");
          return nullSafeList(callees);
        })
        .orElse(List.of());
  }

  // -------------------------------------------------------------------------
  // Text builders
  // -------------------------------------------------------------------------

  /**
   * Builds the text for a CLASS_HEADER chunk.
   *
   * <p>Format: {@code [CLASS: SimpleName] [PACKAGE: pkg]\n{javadoc}\n{field declarations}}
   */
  String buildClassHeaderText(String simpleName, String packageName, String source) {
    StringBuilder sb = new StringBuilder();
    sb.append("[CLASS: ").append(simpleName).append("] ");
    sb.append("[PACKAGE: ").append(packageName).append("]\n");

    // Extract class-level Javadoc (block comment immediately before class declaration)
    String javadoc = extractClassJavadoc(source);
    if (!javadoc.isBlank()) {
      sb.append(javadoc).append("\n");
    }

    // Extract field declarations from the class body (before first method)
    String fields = extractFieldDeclarations(source);
    if (!fields.isBlank()) {
      sb.append(fields);
    }

    return sb.toString().trim();
  }

  /**
   * Builds the text for a METHOD chunk.
   *
   * <p>Format: {@code [CLASS: SimpleName] [METHOD: signature]\n{method body source}}
   */
  String buildMethodText(String simpleName, String methodSignature, String source) {
    StringBuilder sb = new StringBuilder();
    sb.append("[CLASS: ").append(simpleName).append("] ");
    sb.append("[METHOD: ").append(methodSignature).append("]\n");

    String body = extractMethodBody(source, methodSignature);
    sb.append(body);

    return sb.toString().trim();
  }

  // -------------------------------------------------------------------------
  // Source extraction helpers
  // -------------------------------------------------------------------------

  /**
   * Extracts the class-level Javadoc comment (immediately before {@code class} or
   * {@code interface} declaration).
   */
  private String extractClassJavadoc(String source) {
    int classIdx = findClassDeclarationIndex(source);
    if (classIdx < 0) return "";

    String before = source.substring(0, classIdx);
    int end = before.lastIndexOf("*/");
    if (end < 0) return "";
    int start = before.lastIndexOf("/**", end);
    if (start < 0) return "";
    return before.substring(start, end + 2).trim();
  }

  /**
   * Extracts field declarations from the class body. Returns lines from the opening brace up to
   * (but not including) the first method signature.
   */
  private String extractFieldDeclarations(String source) {
    int classIdx = findClassDeclarationIndex(source);
    if (classIdx < 0) return "";

    int braceIdx = source.indexOf('{', classIdx);
    if (braceIdx < 0) return "";

    // Find the first method-like pattern: access-modifier + type + name + '('
    // Use a simple heuristic: look for lines that match "... name(" after the opening brace
    String body = source.substring(braceIdx + 1);
    int methodStart = findFirstMethodStart(body);

    String fieldSection = methodStart >= 0 ? body.substring(0, methodStart) : body;

    // Filter to lines that look like field declarations (contain ';' but not class/enum keywords)
    return fieldSection.lines()
        .filter(line -> line.contains(";") && !line.trim().startsWith("//"))
        .filter(line -> !line.contains("class ") && !line.contains("enum ") && !line.contains("interface "))
        .collect(Collectors.joining("\n"))
        .trim();
  }

  /**
   * Extracts the method source body for the given signature. Searches for the method name
   * (simple name part before the parameter list) in the source and extracts the brace-enclosed
   * body.
   */
  private String extractMethodBody(String source, String methodSignature) {
    // methodSignature is like "myMethod(String,int)" — extract simple method name
    int parenIdx = methodSignature.indexOf('(');
    String methodName = parenIdx >= 0 ? methodSignature.substring(0, parenIdx) : methodSignature;

    // Find the method in source
    int idx = source.indexOf(methodName + "(");
    if (idx < 0) {
      return "[source not found]";
    }

    // Find the opening brace after the method signature
    int braceStart = source.indexOf('{', idx);
    if (braceStart < 0) {
      return "[source not found]";
    }

    // Extract balanced braces
    int depth = 0;
    int end = braceStart;
    for (int i = braceStart; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) {
          end = i + 1;
          break;
        }
      }
    }

    return source.substring(braceStart, end).trim();
  }

  // -------------------------------------------------------------------------
  // Utility helpers
  // -------------------------------------------------------------------------

  private int findClassDeclarationIndex(String source) {
    // Find "class " or "interface " or "enum " keywords (not in comments)
    for (String keyword : List.of(" class ", " interface ", " enum ")) {
      int idx = source.indexOf(keyword);
      if (idx >= 0) return idx;
    }
    return -1;
  }

  private int findFirstMethodStart(String bodyAfterBrace) {
    // Heuristic: look for a line containing "(" that is preceded by access modifier or return type
    // We look for the pattern: non-empty line ending with a ")" that is followed by " {" or "{"
    // to distinguish from field declarations
    String[] lines = bodyAfterBrace.split("\n");
    int charOffset = 0;
    for (String line : lines) {
      String trimmed = line.trim();
      // A method signature line contains '(' and ')' and does not contain '=' (assignment)
      if (trimmed.contains("(") && trimmed.contains(")") && !trimmed.contains("=")
          && !trimmed.startsWith("//") && !trimmed.startsWith("*")) {
        return charOffset;
      }
      charOffset += line.length() + 1;
    }
    return -1;
  }

  /** Extracts the method signature part from a full methodId (the part after '#'). */
  static String methodSignatureFrom(String methodId) {
    int hashIdx = methodId.indexOf('#');
    return hashIdx >= 0 ? methodId.substring(hashIdx + 1) : methodId;
  }

  /** Derives module name from package: second segment after "com.esmp." or the full package. */
  @Deprecated
  static String deriveModule(String packageName) {
    if (packageName == null || packageName.isBlank()) return "";
    String prefix = "com.esmp.";
    if (packageName.startsWith(prefix)) {
      String remainder = packageName.substring(prefix.length());
      int dot = remainder.indexOf('.');
      return dot >= 0 ? remainder.substring(0, dot) : remainder;
    }
    return packageName;
  }

  /**
   * Resolves a relative source path against the source root, searching module subdirectories.
   *
   * <p>The {@code sourceFilePath} stored in Neo4j is relative to the module's
   * {@code src/main/java/} directory (e.g., {@code de/alfa/openMedia/MyClass.java}).
   * This method searches for the file under {@code sourceRoot/}*{@code /src/main/java/}
   * subdirectories (Gradle/Maven multi-module layout).
   */
  private Path resolveSourcePath(String sourceRoot, String sourcePath) {
    if (sourceRoot == null || sourceRoot.isBlank()) {
      return Path.of(sourcePath);
    }

    // Direct path: sourceRoot + sourcePath (works for single-module projects)
    Path direct = Path.of(sourceRoot, sourcePath);
    if (Files.exists(direct)) return direct;

    // Multi-module: search sourceRoot/*/src/main/java/ + sourcePath
    if (sourceJavaDirs == null) {
      sourceJavaDirs = discoverSourceJavaDirs(sourceRoot);
    }
    for (Path javaDir : sourceJavaDirs) {
      Path candidate = javaDir.resolve(sourcePath);
      if (Files.exists(candidate)) return candidate;
    }

    return direct; // fallback — will fail exists() check in caller
  }

  /** Cached list of module src/main/java directories under the source root. */
  private volatile List<Path> sourceJavaDirs;

  private List<Path> discoverSourceJavaDirs(String sourceRoot) {
    List<Path> dirs = new ArrayList<>();
    try {
      Path root = Path.of(sourceRoot);
      if (Files.isDirectory(root)) {
        Files.list(root)
            .filter(Files::isDirectory)
            .map(module -> module.resolve("src/main/java"))
            .filter(Files::isDirectory)
            .forEach(dirs::add);
      }
    } catch (IOException e) {
      log.warn("Failed to discover source directories under {}: {}", sourceRoot, e.getMessage());
    }
    log.info("Discovered {} source directories: {}", dirs.size(),
        dirs.stream().map(Path::toString).collect(Collectors.joining(", ")));
    return dirs;
  }

  private double toDouble(Object value) {
    if (value == null) return 0.0;
    if (value instanceof Number n) return n.doubleValue();
    return 0.0;
  }

  @SuppressWarnings("unchecked")
  private <T> List<T> nullSafeList(List<T> list) {
    return list != null ? list : List.of();
  }
}
