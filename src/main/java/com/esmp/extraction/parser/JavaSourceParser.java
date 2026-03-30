package com.esmp.extraction.parser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * OpenRewrite-based Java source parser that produces type-attributed LSTs (Language Syntax Trees)
 * from Java source files.
 *
 * <p>Type attribution requires providing the target project's compiled classpath so that fully
 * qualified types (including Vaadin 7 types) are resolved in visitor traversal. Without the
 * classpath, types resolve as {@code Unknown} and call graph extraction degrades silently.
 *
 * <p>Large file lists are automatically split into batches to prevent OpenRewrite from hanging
 * during cross-file type resolution.
 *
 * <p>Malformed Java files are handled gracefully: parse errors are logged as warnings rather than
 * thrown as exceptions. The parser returns whatever partial results it produced.
 */
@Service
public class JavaSourceParser {

  private static final Logger log = LoggerFactory.getLogger(JavaSourceParser.class);

  /**
   * Maximum number of source files per OpenRewrite parse call. OpenRewrite performs cross-file
   * type resolution that scales poorly beyond this threshold, causing hangs on large codebases.
   */
  private static final int PARSE_BATCH_SIZE = 500;

  private final ClasspathLoader classpathLoader;

  public JavaSourceParser(ClasspathLoader classpathLoader) {
    this.classpathLoader = classpathLoader;
  }

  /**
   * Parses the given Java source files into OpenRewrite {@link SourceFile} LSTs.
   *
   * @param javaSourcePaths the Java source files to parse
   * @param projectRoot base directory used for relative source path computation in the LST
   * @param classpathFilePath path to the classpath text file (one JAR per line); may be null or
   *     empty — parsing continues with degraded type resolution
   * @return list of parsed {@link SourceFile} objects; may be shorter than input if some files fail
   *     to parse
   */
  public List<SourceFile> parse(
      List<Path> javaSourcePaths, Path projectRoot, String classpathFilePath) {
    if (javaSourcePaths == null || javaSourcePaths.isEmpty()) {
      return Collections.emptyList();
    }

    List<Path> classpathJars = classpathLoader.load(classpathFilePath);

    if (classpathJars.isEmpty()) {
      log.warn(
          "Parsing {} source files without classpath — Vaadin type resolution will be degraded",
          javaSourcePaths.size());
    } else {
      log.info(
          "Parsing {} source files with {} classpath entries",
          javaSourcePaths.size(),
          classpathJars.size());
    }

    return parseBatched(javaSourcePaths, projectRoot, classpathJars);
  }

  /**
   * Parses Java source files with compiled class directories as classpath.
   *
   * <p>Used by module-aware extraction where each module's classpath is the compiled class
   * directories of its upstream dependency modules.
   *
   * @param javaSourcePaths source files to parse
   * @param projectRoot base directory for relative path computation
   * @param compiledClasspathDirs directories containing .class files (not JARs)
   * @return parsed SourceFile LSTs
   */
  public List<SourceFile> parse(
      List<Path> javaSourcePaths, Path projectRoot, List<Path> compiledClasspathDirs) {
    if (javaSourcePaths == null || javaSourcePaths.isEmpty()) {
      return Collections.emptyList();
    }

    log.info("Parsing {} source files with {} compiled classpath directories",
        javaSourcePaths.size(), compiledClasspathDirs != null ? compiledClasspathDirs.size() : 0);

    return parseBatched(javaSourcePaths, projectRoot,
        compiledClasspathDirs != null ? compiledClasspathDirs : List.of());
  }

  /**
   * Splits large file lists into batches and parses each with a fresh parser instance.
   */
  private List<SourceFile> parseBatched(
      List<Path> javaSourcePaths, Path projectRoot, List<Path> classpath) {
    int totalFiles = javaSourcePaths.size();

    if (totalFiles <= PARSE_BATCH_SIZE) {
      return parseSingleBatch(javaSourcePaths, projectRoot, classpath);
    }

    log.info("Splitting {} files into batches of {} for parsing", totalFiles, PARSE_BATCH_SIZE);
    List<SourceFile> allResults = new ArrayList<>();
    for (int i = 0; i < totalFiles; i += PARSE_BATCH_SIZE) {
      int end = Math.min(i + PARSE_BATCH_SIZE, totalFiles);
      List<Path> batch = javaSourcePaths.subList(i, end);
      log.info("Parsing batch {}-{} of {} files", i + 1, end, totalFiles);
      long batchStart = System.currentTimeMillis();
      List<SourceFile> batchResult = parseSingleBatch(batch, projectRoot, classpath);
      long batchMs = System.currentTimeMillis() - batchStart;
      log.info("Batch {}-{}: parsed {}/{} files in {}ms",
          i + 1, end, batchResult.size(), batch.size(), batchMs);
      allResults.addAll(batchResult);
    }
    log.info("Parsed {}/{} source files total (batched)", allResults.size(), totalFiles);
    return allResults;
  }

  /**
   * Parses a single batch of source files with a fresh parser and type cache.
   */
  private List<SourceFile> parseSingleBatch(
      List<Path> javaSourcePaths, Path projectRoot, List<Path> classpath) {
    InMemoryExecutionContext ctx = new InMemoryExecutionContext(
        throwable -> log.warn("Parse error (file will be skipped): {}", throwable.getMessage()));

    try {
      JavaParser.Builder<? extends JavaParser, ?> builder =
          JavaParser.fromJavaVersion()
              .typeCache(new JavaTypeCache())
              .logCompilationWarningsAndErrors(false);

      if (classpath != null && !classpath.isEmpty()) {
        builder = builder.classpath(classpath);
      }

      JavaParser javaParser = builder.build();
      return javaParser.parse(javaSourcePaths, projectRoot, ctx).toList();
    } catch (Exception e) {
      log.warn("Unexpected error during parsing batch — returning empty result. Error: {}",
          e.getMessage());
      return Collections.emptyList();
    }
  }
}
