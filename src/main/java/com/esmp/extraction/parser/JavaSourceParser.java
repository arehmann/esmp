package com.esmp.extraction.parser;

import java.nio.file.Path;
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
 * <p>Malformed Java files are handled gracefully: parse errors are logged as warnings rather than
 * thrown as exceptions. The parser returns whatever partial results it produced.
 */
@Service
public class JavaSourceParser {

  private static final Logger log = LoggerFactory.getLogger(JavaSourceParser.class);

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
      log.debug(
          "Parsing {} source files with {} classpath entries",
          javaSourcePaths.size(),
          classpathJars.size());
    }

    // Execution context that logs parse errors as warnings instead of throwing
    InMemoryExecutionContext ctx =
        new InMemoryExecutionContext(
            throwable ->
                log.warn("Parse error (file will be skipped): {}", throwable.getMessage()));

    try {
      JavaParser.Builder<? extends JavaParser, ?> builder =
          JavaParser.fromJavaVersion()
              .typeCache(new JavaTypeCache())
              .logCompilationWarningsAndErrors(false);

      if (!classpathJars.isEmpty()) {
        builder = builder.classpath(classpathJars);
      }

      JavaParser javaParser = builder.build();
      List<SourceFile> result = javaParser.parse(javaSourcePaths, projectRoot, ctx).toList();
      log.info("Parsed {}/{} source files successfully", result.size(), javaSourcePaths.size());
      return result;
    } catch (Exception e) {
      log.warn(
          "Unexpected error during parsing — returning empty result. Error: {}", e.getMessage());
      return Collections.emptyList();
    }
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
        javaSourcePaths.size(), compiledClasspathDirs.size());

    InMemoryExecutionContext ctx = new InMemoryExecutionContext(
        throwable -> log.warn("Parse error (file will be skipped): {}", throwable.getMessage()));

    try {
      JavaParser.Builder<? extends JavaParser, ?> builder =
          JavaParser.fromJavaVersion()
              .typeCache(new JavaTypeCache())
              .logCompilationWarningsAndErrors(false);

      if (!compiledClasspathDirs.isEmpty()) {
        builder = builder.classpath(compiledClasspathDirs);
      }

      JavaParser javaParser = builder.build();
      List<SourceFile> result = javaParser.parse(javaSourcePaths, projectRoot, ctx).toList();
      log.info("Parsed {}/{} source files successfully", result.size(), javaSourcePaths.size());
      return result;
    } catch (Exception e) {
      log.warn("Unexpected error during parsing -- returning empty result. Error: {}", e.getMessage());
      return Collections.emptyList();
    }
  }
}
