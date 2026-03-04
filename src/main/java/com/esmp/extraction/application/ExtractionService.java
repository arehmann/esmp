package com.esmp.extraction.application;

import com.esmp.extraction.audit.VaadinAuditReport;
import com.esmp.extraction.audit.VaadinAuditService;
import com.esmp.extraction.config.ExtractionConfig;
import com.esmp.extraction.model.AnnotationNode;
import com.esmp.extraction.model.ClassNode;
import com.esmp.extraction.model.DBTableNode;
import com.esmp.extraction.model.ModuleNode;
import com.esmp.extraction.model.PackageNode;
import com.esmp.extraction.parser.JavaSourceParser;
import com.esmp.extraction.persistence.AnnotationNodeRepository;
import com.esmp.extraction.persistence.ClassNodeRepository;
import com.esmp.extraction.persistence.DBTableNodeRepository;
import com.esmp.extraction.persistence.ModuleNodeRepository;
import com.esmp.extraction.persistence.PackageNodeRepository;
import com.esmp.extraction.visitor.CallGraphVisitor;
import com.esmp.extraction.visitor.ClassMetadataVisitor;
import com.esmp.extraction.visitor.DependencyVisitor;
import com.esmp.extraction.visitor.ExtractionAccumulator;
import com.esmp.extraction.visitor.JpaPatternVisitor;
import com.esmp.extraction.visitor.VaadinPatternVisitor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.openrewrite.SourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the full extraction pipeline: scan → parse → visit → map → persist → audit.
 *
 * <p>The pipeline is idempotent: re-running on unchanged files will MERGE existing Neo4j nodes
 * rather than creating duplicates, because {@link ClassNode}, {@code MethodNode}, and {@code
 * FieldNode} entities all use business-key {@code @Id} with {@code @Version} for optimistic
 * locking.
 */
@Service
public class ExtractionService {

  private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

  private final JavaSourceParser javaSourceParser;
  private final AccumulatorToModelMapper mapper;
  private final ClassNodeRepository classNodeRepository;
  private final AnnotationNodeRepository annotationNodeRepository;
  private final PackageNodeRepository packageNodeRepository;
  private final ModuleNodeRepository moduleNodeRepository;
  private final DBTableNodeRepository dbTableNodeRepository;
  private final LinkingService linkingService;
  private final VaadinAuditService vaadinAuditService;
  private final ExtractionConfig extractionConfig;

  public ExtractionService(
      JavaSourceParser javaSourceParser,
      AccumulatorToModelMapper mapper,
      ClassNodeRepository classNodeRepository,
      AnnotationNodeRepository annotationNodeRepository,
      PackageNodeRepository packageNodeRepository,
      ModuleNodeRepository moduleNodeRepository,
      DBTableNodeRepository dbTableNodeRepository,
      LinkingService linkingService,
      VaadinAuditService vaadinAuditService,
      ExtractionConfig extractionConfig) {
    this.javaSourceParser = javaSourceParser;
    this.mapper = mapper;
    this.classNodeRepository = classNodeRepository;
    this.annotationNodeRepository = annotationNodeRepository;
    this.packageNodeRepository = packageNodeRepository;
    this.moduleNodeRepository = moduleNodeRepository;
    this.dbTableNodeRepository = dbTableNodeRepository;
    this.linkingService = linkingService;
    this.vaadinAuditService = vaadinAuditService;
    this.extractionConfig = extractionConfig;
  }

  /**
   * Runs the full extraction pipeline.
   *
   * @param sourceRoot absolute path to the Java source directory; if null or blank, falls back to
   *     {@code ExtractionConfig.sourceRoot}
   * @param classpathFile path to the classpath text file; if null or blank, falls back to {@code
   *     ExtractionConfig.classpathFile}
   * @return extraction result with counts and Vaadin audit report
   */
  @Transactional("neo4jTransactionManager")
  public ExtractionResult extract(String sourceRoot, String classpathFile) {
    long startMs = System.currentTimeMillis();

    // Resolve source root
    String resolvedSourceRoot =
        (sourceRoot != null && !sourceRoot.isBlank())
            ? sourceRoot
            : extractionConfig.getSourceRoot();
    String resolvedClasspathFile =
        (classpathFile != null && !classpathFile.isBlank())
            ? classpathFile
            : extractionConfig.getClasspathFile();

    Path sourceRootPath = Path.of(resolvedSourceRoot);

    // Scan for .java files
    List<Path> javaPaths = scanJavaFiles(sourceRootPath);
    log.info("Scanning {} for Java sources: found {} files", sourceRootPath, javaPaths.size());

    // Parse all Java source files into OpenRewrite LSTs
    List<SourceFile> sourceFiles =
        javaSourceParser.parse(javaPaths, sourceRootPath, resolvedClasspathFile);

    // Run visitors to collect AST data into accumulator
    ExtractionAccumulator accumulator = new ExtractionAccumulator();
    List<String> errors = new ArrayList<>();
    int errorCount = 0;

    ClassMetadataVisitor classMetadataVisitor = new ClassMetadataVisitor();
    CallGraphVisitor callGraphVisitor = new CallGraphVisitor();
    VaadinPatternVisitor vaadinPatternVisitor = new VaadinPatternVisitor();
    DependencyVisitor dependencyVisitor = new DependencyVisitor();
    JpaPatternVisitor jpaPatternVisitor = new JpaPatternVisitor();

    for (SourceFile sourceFile : sourceFiles) {
      try {
        classMetadataVisitor.visit(sourceFile, accumulator);
        callGraphVisitor.visit(sourceFile, accumulator);
        vaadinPatternVisitor.visit(sourceFile, accumulator);
        dependencyVisitor.visit(sourceFile, accumulator);
        jpaPatternVisitor.visit(sourceFile, accumulator);
      } catch (Exception e) {
        errorCount++;
        String error = "Error visiting " + sourceFile.getSourcePath() + ": " + e.getMessage();
        errors.add(error);
        log.warn(error, e);
      }
    }

    // Map accumulator data to entity objects
    List<ClassNode> classNodes = mapper.mapToClassNodes(accumulator);
    List<AnnotationNode> annotationNodes = mapper.mapToAnnotationNodes(accumulator);
    List<PackageNode> packageNodes = mapper.mapToPackageNodes(accumulator);
    List<ModuleNode> moduleNodes = mapper.mapToModuleNodes(accumulator, resolvedSourceRoot);
    List<DBTableNode> dbTableNodes = mapper.mapToDBTableNodes(accumulator);

    // Persist all node types — SDN saveAll() performs MERGE via @Id business keys
    classNodeRepository.saveAll(classNodes);
    log.info("Persisted {} class nodes to Neo4j", classNodes.size());

    annotationNodeRepository.saveAll(annotationNodes);
    log.info("Persisted {} annotation nodes to Neo4j", annotationNodes.size());

    packageNodeRepository.saveAll(packageNodes);
    log.info("Persisted {} package nodes to Neo4j", packageNodes.size());

    moduleNodeRepository.saveAll(moduleNodes);
    log.info("Persisted {} module nodes to Neo4j", moduleNodes.size());

    dbTableNodeRepository.saveAll(dbTableNodes);
    log.info("Persisted {} DB table nodes to Neo4j", dbTableNodes.size());

    // Run linking pass — creates cross-class relationships via idempotent Cypher MERGE
    LinkingService.LinkingResult linkingResult = linkingService.linkAllRelationships(accumulator);

    // Generate Vaadin audit report
    VaadinAuditReport auditReport = vaadinAuditService.generateReport(accumulator);

    long durationMs = System.currentTimeMillis() - startMs;

    return new ExtractionResult(
        accumulator.getClasses().size(),
        accumulator.getMethods().size(),
        accumulator.getFields().size(),
        accumulator.getCallEdges().size(),
        accumulator.getVaadinViews().size(),
        accumulator.getVaadinComponents().size(),
        accumulator.getVaadinDataBindings().size(),
        annotationNodes.size(),
        packageNodes.size(),
        moduleNodes.size(),
        dbTableNodes.size(),
        linkingResult,
        errorCount,
        errors,
        auditReport,
        durationMs);
  }

  private List<Path> scanJavaFiles(Path root) {
    if (!Files.exists(root) || !Files.isDirectory(root)) {
      log.warn("Source root does not exist or is not a directory: {}", root);
      return List.of();
    }
    try {
      return Files.walk(root)
          .filter(p -> p.toString().endsWith(".java"))
          .collect(Collectors.toList());
    } catch (IOException e) {
      log.error("Failed to scan Java files under {}: {}", root, e.getMessage());
      return List.of();
    }
  }

  /** Result of a single extraction run. */
  public record ExtractionResult(
      int classCount,
      int methodCount,
      int fieldCount,
      int callEdgeCount,
      int vaadinViewCount,
      int vaadinComponentCount,
      int vaadinDataBindingCount,
      int annotationCount,
      int packageCount,
      int moduleCount,
      int tableCount,
      LinkingService.LinkingResult linkingResult,
      int errorCount,
      List<String> errors,
      VaadinAuditReport auditReport,
      long durationMs) {}
}
