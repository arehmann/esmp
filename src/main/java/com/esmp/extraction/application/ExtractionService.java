package com.esmp.extraction.application;

import com.esmp.extraction.audit.VaadinAuditReport;
import com.esmp.extraction.audit.VaadinAuditService;
import com.esmp.extraction.config.ExtractionConfig;
import com.esmp.graph.application.RiskService;
import com.esmp.extraction.model.AnnotationNode;
import com.esmp.extraction.model.BusinessTermNode;
import com.esmp.extraction.model.ClassNode;
import com.esmp.extraction.model.DBTableNode;
import com.esmp.extraction.model.ModuleNode;
import com.esmp.extraction.model.PackageNode;
import com.esmp.extraction.parser.ClasspathLoader;
import com.esmp.extraction.parser.JavaSourceParser;
import com.esmp.extraction.parser.NlsXmlParser;
import com.esmp.extraction.persistence.AnnotationNodeRepository;
import com.esmp.extraction.persistence.BusinessTermNodeRepository;
import com.esmp.extraction.persistence.ClassNodeRepository;
import com.esmp.extraction.persistence.DBTableNodeRepository;
import com.esmp.extraction.persistence.ModuleNodeRepository;
import com.esmp.extraction.persistence.PackageNodeRepository;
import com.esmp.extraction.model.MigrationActionNode;
import com.esmp.extraction.persistence.MigrationActionNodeRepository;
import com.esmp.extraction.visitor.CallGraphVisitor;
import com.esmp.extraction.visitor.ClassMetadataVisitor;
import com.esmp.extraction.visitor.ComplexityVisitor;
import com.esmp.extraction.visitor.DependencyVisitor;
import com.esmp.extraction.visitor.ExtractionAccumulator;
import com.esmp.extraction.visitor.JpaPatternVisitor;
import com.esmp.extraction.visitor.LexiconVisitor;
import com.esmp.extraction.visitor.MigrationPatternVisitor;
import com.esmp.extraction.visitor.VaadinPatternVisitor;
import com.esmp.extraction.module.ModuleDescriptor;
import com.esmp.extraction.module.ModuleDetectionResult;
import com.esmp.extraction.module.ModuleDetectionService;
import com.esmp.migration.application.MigrationRecipeService;
import com.esmp.migration.application.RecipeBookRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.openrewrite.SourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the full extraction pipeline: scan → parse → visit → map → persist → audit.
 *
 * <p>When the source root contains a recognized build file (settings.gradle or pom.xml with
 * modules), the pipeline automatically switches to module-aware mode: each module is parsed with
 * the compiled class directories of its upstream dependencies as the classpath, and each module's
 * nodes are persisted in a separate transaction before proceeding to the next wave.
 *
 * <p>When no build file is detected (BuildSystem.NONE), the existing single-shot behaviour is
 * used unchanged.
 *
 * <p>The pipeline is idempotent: re-running on unchanged files will MERGE existing Neo4j nodes
 * rather than creating duplicates, because {@link ClassNode}, {@code MethodNode}, and {@code
 * FieldNode} entities all use business-key {@code @Id} with {@code @Version} for optimistic
 * locking.
 */
@Service
public class ExtractionService {

  private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

  private static final int BATCH_SIZE = 2000;

  private final JavaSourceParser javaSourceParser;
  private final AccumulatorToModelMapper mapper;
  private final ClassNodeRepository classNodeRepository;
  private final AnnotationNodeRepository annotationNodeRepository;
  private final PackageNodeRepository packageNodeRepository;
  private final ModuleNodeRepository moduleNodeRepository;
  private final DBTableNodeRepository dbTableNodeRepository;
  private final BusinessTermNodeRepository businessTermNodeRepository;
  private final MigrationActionNodeRepository migrationActionNodeRepository;
  private final Neo4jClient neo4jClient;
  private final LinkingService linkingService;
  private final RiskService riskService;
  private final VaadinAuditService vaadinAuditService;
  private final ExtractionConfig extractionConfig;
  private final TaskExecutor extractionExecutor;
  private final ExtractionProgressService progressService;
  private final RecipeBookRegistry recipeBookRegistry;
  private final MigrationRecipeService migrationRecipeService;
  private final ModuleDetectionService moduleDetectionService;
  private final NlsXmlParser nlsXmlParser = new NlsXmlParser();

  /** NLS entries loaded at the start of each extraction run. Thread-safe (immutable after load). */
  private volatile Map<String, NlsXmlParser.NlsEntry> nlsMap = Collections.emptyMap();
  private final ClasspathLoader classpathLoader;

  public ExtractionService(
      JavaSourceParser javaSourceParser,
      AccumulatorToModelMapper mapper,
      ClassNodeRepository classNodeRepository,
      AnnotationNodeRepository annotationNodeRepository,
      PackageNodeRepository packageNodeRepository,
      ModuleNodeRepository moduleNodeRepository,
      DBTableNodeRepository dbTableNodeRepository,
      BusinessTermNodeRepository businessTermNodeRepository,
      MigrationActionNodeRepository migrationActionNodeRepository,
      Neo4jClient neo4jClient,
      LinkingService linkingService,
      RiskService riskService,
      VaadinAuditService vaadinAuditService,
      ExtractionConfig extractionConfig,
      @Qualifier("extractionExecutor") TaskExecutor extractionExecutor,
      ExtractionProgressService progressService,
      RecipeBookRegistry recipeBookRegistry,
      MigrationRecipeService migrationRecipeService,
      ModuleDetectionService moduleDetectionService,
      ClasspathLoader classpathLoader) {
    this.javaSourceParser = javaSourceParser;
    this.mapper = mapper;
    this.classNodeRepository = classNodeRepository;
    this.annotationNodeRepository = annotationNodeRepository;
    this.packageNodeRepository = packageNodeRepository;
    this.moduleNodeRepository = moduleNodeRepository;
    this.dbTableNodeRepository = dbTableNodeRepository;
    this.businessTermNodeRepository = businessTermNodeRepository;
    this.migrationActionNodeRepository = migrationActionNodeRepository;
    this.neo4jClient = neo4jClient;
    this.linkingService = linkingService;
    this.riskService = riskService;
    this.vaadinAuditService = vaadinAuditService;
    this.extractionConfig = extractionConfig;
    this.extractionExecutor = extractionExecutor;
    this.progressService = progressService;
    this.recipeBookRegistry = recipeBookRegistry;
    this.migrationRecipeService = migrationRecipeService;
    this.moduleDetectionService = moduleDetectionService;
    this.classpathLoader = classpathLoader;
  }

  /**
   * Runs the full extraction pipeline (synchronous, no progress streaming).
   *
   * @param sourceRoot absolute path to the Java source directory; if null or blank, falls back to
   *     {@code ExtractionConfig.sourceRoot}
   * @param classpathFile path to the classpath text file; if null or blank, falls back to {@code
   *     ExtractionConfig.classpathFile}
   * @return extraction result with counts and Vaadin audit report
   */
  public ExtractionResult extract(String sourceRoot, String classpathFile) {
    return extract(sourceRoot, classpathFile, null);
  }

  /**
   * Runs the full extraction pipeline with optional SSE progress streaming.
   *
   * <p>Detects the build system at {@code sourceRoot}. If a multi-module project is detected
   * (Gradle or Maven), delegates to {@link #extractModuleAware}. Otherwise, runs the existing
   * single-shot path via {@link #extractSingleShot}.
   *
   * @param sourceRoot    absolute path to the Java source directory; if null or blank, falls back to
   *                      {@code ExtractionConfig.sourceRoot}
   * @param classpathFile path to the classpath text file; if null or blank, falls back to {@code
   *                      ExtractionConfig.classpathFile}
   * @param jobId         optional job identifier for progress streaming via
   *                      {@link ExtractionProgressService}; may be {@code null} to disable streaming
   * @return extraction result with counts and Vaadin audit report
   */
  public ExtractionResult extract(String sourceRoot, String classpathFile, String jobId) {
    String resolvedSourceRoot =
        (sourceRoot != null && !sourceRoot.isBlank())
            ? sourceRoot
            : extractionConfig.getSourceRoot();
    String resolvedClasspathFile =
        (classpathFile != null && !classpathFile.isBlank())
            ? classpathFile
            : extractionConfig.getClasspathFile();

    Path sourceRootPath = Path.of(resolvedSourceRoot);

    // Load NLS entries once for this extraction run — used by LexiconVisitor for domain terms
    this.nlsMap = nlsXmlParser.parse(sourceRootPath);
    log.info("Loaded {} NLS entries for domain lexicon enrichment", nlsMap.size());

    ModuleDetectionResult moduleDetection = moduleDetectionService.detect(sourceRootPath);

    if (moduleDetection.isMultiModule()) {
      return extractModuleAware(sourceRootPath, resolvedClasspathFile, jobId, moduleDetection);
    }

    return extractSingleShot(sourceRootPath, resolvedClasspathFile, jobId);
  }

  /**
   * Single-shot extraction: scans, parses, visits and persists all files in one transaction.
   * Used when no recognized build file is found (BuildSystem.NONE).
   */
  @Transactional("neo4jTransactionManager")
  ExtractionResult extractSingleShot(Path sourceRootPath, String classpathFile, String jobId) {
    long startMs = System.currentTimeMillis();

    // Scan for .java files
    List<Path> javaPaths = scanJavaFiles(sourceRootPath);
    log.info("Scanning {} for Java sources: found {} files", sourceRootPath, javaPaths.size());
    sendProgress(jobId, "SCANNING", 0, javaPaths.size());

    // Parse all Java source files into OpenRewrite LSTs
    List<SourceFile> sourceFiles =
        javaSourceParser.parse(javaPaths, sourceRootPath, classpathFile);
    sendProgress(jobId, "PARSING", sourceFiles.size(), sourceFiles.size());

    // Run visitors to collect AST data into accumulator — parallel path for large codebases
    List<String> errors = Collections.synchronizedList(new ArrayList<>());

    ExtractionAccumulator accumulator;
    if (sourceFiles.size() > extractionConfig.getParallelThreshold()) {
      log.info("Parallel extraction: {} files in partitions of {}",
          sourceFiles.size(), extractionConfig.getPartitionSize());
      accumulator = visitInParallel(sourceFiles, errors, jobId);
    } else {
      log.info("Sequential extraction: {} files (below parallel threshold {})",
          sourceFiles.size(), extractionConfig.getParallelThreshold());
      accumulator = visitSequentially(sourceFiles, errors, jobId);
    }
    int errorCount = errors.size();

    // Map accumulator data to entity objects
    List<ClassNode> classNodes = mapper.mapToClassNodes(accumulator);
    List<AnnotationNode> annotationNodes = mapper.mapToAnnotationNodes(accumulator);
    List<PackageNode> packageNodes = mapper.mapToPackageNodes(accumulator);
    List<ModuleNode> moduleNodes = mapper.mapToModuleNodes(accumulator, sourceRootPath.toString());
    List<DBTableNode> dbTableNodes = mapper.mapToDBTableNodes(accumulator);
    List<BusinessTermNode> businessTermNodes = mapper.mapToBusinessTermNodes(accumulator);
    List<MigrationActionNode> migrationActionNodes = mapper.mapToMigrationActionNodes(accumulator);

    // Persist all node types
    // ClassNode uses SDN saveAll() — correct @Id/@Version semantics for class graph wiring
    classNodeRepository.saveAll(classNodes);
    log.info("Persisted {} class nodes to Neo4j", classNodes.size());
    sendProgress(jobId, "PERSISTING", classNodes.size(), classNodes.size());

    // Annotation/Package/Module/DBTable use batched UNWIND MERGE for enterprise-scale performance
    persistAnnotationNodesBatched(annotationNodes);
    persistPackageNodesBatched(packageNodes);
    persistModuleNodesBatched(moduleNodes);
    persistDBTableNodesBatched(dbTableNodes);

    // Persist business term nodes using curated-guard MERGE to protect human-curated definitions
    // (LEX-02/LEX-04 compliance: curated=true terms are never overwritten by re-extraction)
    persistBusinessTermNodes(businessTermNodes);

    // Persist migration action nodes using batched UNWIND MERGE
    // Must persist BEFORE linkAllRelationships() so HAS_MIGRATION_ACTION MATCH can find them
    persistMigrationActionNodesBatched(migrationActionNodes);

    // Run linking pass — creates cross-class relationships via idempotent Cypher MERGE
    LinkingService.LinkingResult linkingResult = linkingService.linkAllRelationships(accumulator);
    sendProgress(jobId, "LINKING", classNodes.size(), classNodes.size());

    // Compute fan-in/out and composite structural risk scores from DEPENDS_ON edges.
    // MUST run after linking — DEPENDS_ON edges must exist for fan-in/out to be accurate.
    riskService.computeAndPersistRiskScores();

    // Run migration post-processing: transitive detection, score recompute, enrichment
    // MUST run after linkAllRelationships() (EXTENDS edges exist) AND computeRiskScores()
    migrationRecipeService.migrationPostProcessing();

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
        businessTermNodes.size(),
        linkingResult,
        errorCount,
        errors,
        auditReport,
        durationMs,
        null,
        null,
        null);
  }

  /**
   * Module-aware extraction orchestrator (NOT @Transactional).
   *
   * <p>Processes modules in topological wave order. Each module's nodes are persisted in their own
   * transaction via {@link #persistModuleNodes}. Cross-module linking, risk scoring, and migration
   * post-processing run as a single final pass after all modules are persisted.
   *
   * <p>Individual module failures are caught, logged, and reported without blocking remaining
   * modules (per MODEX-04 isolation requirement).
   */
  private ExtractionResult extractModuleAware(
      Path sourceRootPath, String resolvedClasspathFile, String jobId,
      ModuleDetectionResult detection) {

    long startMs = System.currentTimeMillis();
    log.info("Module-aware extraction: {} modules in {} waves, build system: {}",
        detection.totalModules(), detection.waves().size(), detection.buildSystem());

    // Log skipped modules
    for (var skipped : detection.skippedModules()) {
      log.warn("Skipped module {}: {}", skipped.name(), skipped.reason());
      sendModuleProgress(jobId, skipped.name(), "SKIPPED", 0, 0, skipped.reason(), null);
    }

    // Track aggregate counts
    ExtractionAccumulator mergedAccumulator = new ExtractionAccumulator();
    List<String> allErrors = Collections.synchronizedList(new ArrayList<>());
    List<ModuleExtractionSummary> moduleSummaries = new ArrayList<>();

    // Build a map of module name -> ModuleDescriptor for classpath resolution
    Map<String, ModuleDescriptor> allModules = new HashMap<>();
    for (var wave : detection.waves()) {
      for (var mod : wave) {
        allModules.put(mod.name(), mod);
      }
    }

    // Process waves sequentially; modules within a wave can be parallelized in the future
    for (int waveIdx = 0; waveIdx < detection.waves().size(); waveIdx++) {
      List<ModuleDescriptor> wave = detection.waves().get(waveIdx);
      log.info("Processing wave {} with {} modules: {}", waveIdx,
          wave.size(), wave.stream().map(ModuleDescriptor::name).toList());

      for (ModuleDescriptor module : wave) {
        try {
          long moduleStartMs = System.currentTimeMillis();
          int fileCount = module.javaFiles().size();

          sendModuleProgress(jobId, module.name(), "PARSING", 0, fileCount, null, null);

          // Build classpath: upstream dependency modules' compiled classes.
          // Note: the module's own compiled classes are NOT included — OpenRewrite hangs when
          // scanning large class directories during parser initialization.
          List<Path> fullClasspath = new ArrayList<>();
          module.dependsOn().stream()
              .map(allModules::get)
              .filter(dep -> dep != null && Files.isDirectory(dep.compiledClassesDir()))
              .map(ModuleDescriptor::compiledClassesDir)
              .forEach(fullClasspath::add);
          if (resolvedClasspathFile != null && !resolvedClasspathFile.isBlank()) {
            List<Path> externalJars = classpathLoader.load(resolvedClasspathFile);
            fullClasspath.addAll(externalJars);
          }

          // Parse this module's files with module-specific classpath.
          // IMPORTANT: use module.sourceDir() as projectRoot (not sourceRootPath) so that
          // OpenRewrite can correctly infer package structure from relative file paths.
          // Using the overall project root causes path-based package inference to produce
          // "adsuite-persistent.src.main.java.com..." instead of "com..." which triggers
          // infinite type resolution loops.
          List<SourceFile> sourceFiles = javaSourceParser.parse(
              module.javaFiles(), module.sourceDir(), fullClasspath);

          sendModuleProgress(jobId, module.name(), "PARSING", fileCount, fileCount, null, null);

          // Visit (parallel or sequential based on threshold)
          sendModuleProgress(jobId, module.name(), "VISITING", 0, fileCount, null, null);
          ExtractionAccumulator moduleAccumulator;
          if (sourceFiles.size() > extractionConfig.getParallelThreshold()) {
            moduleAccumulator = visitInParallel(sourceFiles, allErrors, jobId);
          } else {
            moduleAccumulator = visitSequentially(sourceFiles, allErrors, jobId);
          }
          sendModuleProgress(jobId, module.name(), "VISITING", fileCount, fileCount, null, null);

          // Persist this module's nodes in its own transaction
          sendModuleProgress(jobId, module.name(), "PERSISTING", 0, fileCount, null, null);
          persistModuleNodes(moduleAccumulator, sourceRootPath.toString());
          sendModuleProgress(jobId, module.name(), "PERSISTING", fileCount, fileCount, null, null);

          // Merge into aggregate accumulator for cross-module linking
          mergedAccumulator = mergedAccumulator.merge(moduleAccumulator);

          long moduleDurationMs = System.currentTimeMillis() - moduleStartMs;
          sendModuleProgress(jobId, module.name(), "COMPLETE", fileCount, fileCount, null, moduleDurationMs);

          // Track per-module summary
          moduleSummaries.add(new ModuleExtractionSummary(
              module.name(), moduleAccumulator.getClasses().size(),
              sourceFiles.size(), moduleDurationMs));

          log.info("Module {} complete: {} classes, {} files, {}ms",
              module.name(), moduleAccumulator.getClasses().size(), fileCount, moduleDurationMs);

        } catch (Exception e) {
          log.error("Module {} failed: {} -- continuing with remaining modules",
              module.name(), e.getMessage(), e);
          allErrors.add("Module " + module.name() + " failed: " + e.getMessage());
          sendModuleProgress(jobId, module.name(), "FAILED", 0, module.javaFiles().size(),
              "Error: " + e.getMessage(), null);
        }
      }
    }

    // Cross-module linking pass
    sendModuleProgress(jobId, null, "LINKING", 0, 0, "Cross-module linking pass", null);
    LinkingService.LinkingResult linkingResult = linkingService.linkAllRelationships(mergedAccumulator);

    // Risk scoring
    sendModuleProgress(jobId, null, "RISK_SCORING", 0, 0, "Computing risk scores", null);
    riskService.computeAndPersistRiskScores();

    // Migration post-processing
    sendModuleProgress(jobId, null, "MIGRATION", 0, 0, "Migration post-processing", null);
    migrationRecipeService.migrationPostProcessing();

    // Vaadin audit
    VaadinAuditReport auditReport = vaadinAuditService.generateReport(mergedAccumulator);

    long durationMs = System.currentTimeMillis() - startMs;
    sendModuleProgress(jobId, null, "EXTRACTION_COMPLETE", detection.totalJavaFiles(), detection.totalJavaFiles(),
        String.format("%d modules, %d files", detection.totalModules(), detection.totalJavaFiles()), durationMs);

    // Build skipped module name list for result
    List<String> skippedModuleNames = detection.skippedModules().stream()
        .map(s -> s.name() + ": " + s.reason())
        .toList();

    return new ExtractionResult(
        mergedAccumulator.getClasses().size(),
        mergedAccumulator.getMethods().size(),
        mergedAccumulator.getFields().size(),
        mergedAccumulator.getCallEdges().size(),
        mergedAccumulator.getVaadinViews().size(),
        mergedAccumulator.getVaadinComponents().size(),
        mergedAccumulator.getVaadinDataBindings().size(),
        0, // annotationCount from mapper — not separately tracked in module-aware path
        0, // packageCount
        0, // moduleCount
        0, // tableCount
        0, // businessTermCount
        linkingResult,
        allErrors.size(),
        allErrors,
        auditReport,
        durationMs,
        detection.buildSystem().name(),
        moduleSummaries,
        skippedModuleNames);
  }

  /**
   * Persists all node types for a single module in a dedicated Neo4j transaction.
   *
   * <p>Called once per module in the module-aware extraction path. Each invocation commits
   * independently, preventing SDN session-cache OOM for large multi-module projects.
   */
  @Transactional("neo4jTransactionManager")
  void persistModuleNodes(ExtractionAccumulator accumulator, String sourceRoot) {
    List<ClassNode> classNodes = mapper.mapToClassNodes(accumulator);
    List<AnnotationNode> annotationNodes = mapper.mapToAnnotationNodes(accumulator);
    List<PackageNode> packageNodes = mapper.mapToPackageNodes(accumulator);
    List<ModuleNode> moduleNodes = mapper.mapToModuleNodes(accumulator, sourceRoot);
    List<DBTableNode> dbTableNodes = mapper.mapToDBTableNodes(accumulator);
    List<BusinessTermNode> businessTermNodes = mapper.mapToBusinessTermNodes(accumulator);
    List<MigrationActionNode> migrationActionNodes = mapper.mapToMigrationActionNodes(accumulator);

    classNodeRepository.saveAll(classNodes);
    persistAnnotationNodesBatched(annotationNodes);
    persistPackageNodesBatched(packageNodes);
    persistModuleNodesBatched(moduleNodes);
    persistDBTableNodesBatched(dbTableNodes);
    persistBusinessTermNodes(businessTermNodes);
    persistMigrationActionNodesBatched(migrationActionNodes);

    log.info("Persisted module nodes: {} classes, {} annotations, {} packages",
        classNodes.size(), annotationNodes.size(), packageNodes.size());
  }

  // =========================================================================
  // Visitor execution — sequential and parallel paths
  // =========================================================================

  /**
   * Visits all source files sequentially in a single accumulator (original behaviour).
   * Used when {@code sourceFiles.size() <= extractionConfig.getParallelThreshold()}.
   */
  private ExtractionAccumulator visitSequentially(List<SourceFile> sourceFiles, List<String> errors) {
    return visitSequentially(sourceFiles, errors, null);
  }

  /**
   * Visits all source files sequentially with optional progress streaming.
   * Used when {@code sourceFiles.size() <= extractionConfig.getParallelThreshold()}.
   */
  private ExtractionAccumulator visitSequentially(List<SourceFile> sourceFiles, List<String> errors, String jobId) {
    ExtractionAccumulator accumulator = new ExtractionAccumulator();
    ClassMetadataVisitor classMetadataVisitor = new ClassMetadataVisitor();
    CallGraphVisitor callGraphVisitor = new CallGraphVisitor();
    VaadinPatternVisitor vaadinPatternVisitor = new VaadinPatternVisitor();
    DependencyVisitor dependencyVisitor = new DependencyVisitor();
    JpaPatternVisitor jpaPatternVisitor = new JpaPatternVisitor();
    LexiconVisitor lexiconVisitor = new LexiconVisitor(nlsMap);
    ComplexityVisitor complexityVisitor = new ComplexityVisitor();
    MigrationPatternVisitor migrationPatternVisitor = new MigrationPatternVisitor(recipeBookRegistry);

    int total = sourceFiles.size();
    int processed = 0;
    for (SourceFile sourceFile : sourceFiles) {
      try {
        classMetadataVisitor.visit(sourceFile, accumulator);
        callGraphVisitor.visit(sourceFile, accumulator);
        vaadinPatternVisitor.visit(sourceFile, accumulator);
        dependencyVisitor.visit(sourceFile, accumulator);
        jpaPatternVisitor.visit(sourceFile, accumulator);
        lexiconVisitor.visit(sourceFile, accumulator);
        complexityVisitor.visit(sourceFile, accumulator);
        migrationPatternVisitor.visit(sourceFile, accumulator);
      } catch (Exception e) {
        String error = "Error visiting " + sourceFile.getSourcePath() + ": " + e.getMessage();
        errors.add(error);
        log.warn(error, e);
      }
      processed++;
      sendProgress(jobId, "VISITING", processed, total);
    }
    return accumulator;
  }

  /**
   * Partitions the source file list and visits each partition in a separate task on the
   * {@code extractionExecutor} thread pool. Each partition gets its own visitor instances and
   * accumulator, avoiding shared mutable state. After all partitions complete, their accumulators
   * are reduced into one via {@link ExtractionAccumulator#merge(ExtractionAccumulator)}.
   */
  private ExtractionAccumulator visitInParallel(List<SourceFile> sourceFiles, List<String> errors) {
    return visitInParallel(sourceFiles, errors, null);
  }

  /**
   * Partitions the source file list and visits each partition in a separate task on the
   * {@code extractionExecutor} thread pool. Each partition gets its own visitor instances and
   * accumulator, avoiding shared mutable state. After all partitions complete, their accumulators
   * are reduced into one via {@link ExtractionAccumulator#merge(ExtractionAccumulator)}.
   *
   * <p>Progress events are sent after each file visit via {@link ExtractionProgressService} when
   * {@code jobId} is non-null.
   */
  private ExtractionAccumulator visitInParallel(List<SourceFile> sourceFiles, List<String> errors, String jobId) {
    int partSize = extractionConfig.getPartitionSize();
    List<List<SourceFile>> partitions = new ArrayList<>();
    for (int i = 0; i < sourceFiles.size(); i += partSize) {
      partitions.add(sourceFiles.subList(i, Math.min(i + partSize, sourceFiles.size())));
    }
    log.info("Visiting {} partitions in parallel", partitions.size());

    int total = sourceFiles.size();
    AtomicInteger progressCounter = new AtomicInteger(0);

    List<CompletableFuture<ExtractionAccumulator>> futures = partitions.stream()
        .map(batch -> CompletableFuture.supplyAsync(
            () -> visitBatch(batch, errors, jobId, progressCounter, total), extractionExecutor))
        .toList();

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    return futures.stream()
        .map(CompletableFuture::join)
        .reduce(new ExtractionAccumulator(), ExtractionAccumulator::merge);
  }

  /**
   * Visits a single batch of source files with freshly created visitor instances. Returns the
   * populated accumulator for this batch.
   *
   * <p>Each batch creates its own visitor instances because visitors (e.g. {@link ComplexityVisitor})
   * have internal state (a Deque counter stack) that must not be shared across concurrent tasks.
   */
  private ExtractionAccumulator visitBatch(List<SourceFile> batch, List<String> errors) {
    return visitBatch(batch, errors, null, null, 0);
  }

  /**
   * Visits a single batch of source files with optional progress streaming.
   *
   * @param batch           the source files to visit
   * @param errors          shared error list (synchronised externally)
   * @param jobId           optional job ID for SSE progress streaming
   * @param progressCounter shared atomic counter across all parallel batches
   * @param total           total number of source files for progress denominator
   */
  private ExtractionAccumulator visitBatch(
      List<SourceFile> batch,
      List<String> errors,
      String jobId,
      AtomicInteger progressCounter,
      int total) {
    ExtractionAccumulator acc = new ExtractionAccumulator();
    ClassMetadataVisitor classMetadataVisitor = new ClassMetadataVisitor();
    CallGraphVisitor callGraphVisitor = new CallGraphVisitor();
    VaadinPatternVisitor vaadinPatternVisitor = new VaadinPatternVisitor();
    DependencyVisitor dependencyVisitor = new DependencyVisitor();
    JpaPatternVisitor jpaPatternVisitor = new JpaPatternVisitor();
    LexiconVisitor lexiconVisitor = new LexiconVisitor(nlsMap);
    ComplexityVisitor complexityVisitor = new ComplexityVisitor();
    MigrationPatternVisitor migrationPatternVisitor = new MigrationPatternVisitor(recipeBookRegistry);

    for (SourceFile sf : batch) {
      try {
        classMetadataVisitor.visit(sf, acc);
        callGraphVisitor.visit(sf, acc);
        vaadinPatternVisitor.visit(sf, acc);
        dependencyVisitor.visit(sf, acc);
        jpaPatternVisitor.visit(sf, acc);
        lexiconVisitor.visit(sf, acc);
        complexityVisitor.visit(sf, acc);
        migrationPatternVisitor.visit(sf, acc);
      } catch (Exception e) {
        errors.add("Error visiting " + sf.getSourcePath() + ": " + e.getMessage());
        log.warn("Error visiting {}: {}", sf.getSourcePath(), e.getMessage(), e);
      }
      if (jobId != null && progressCounter != null) {
        int processed = progressCounter.incrementAndGet();
        sendProgress(jobId, "VISITING", processed, total);
      }
    }
    return acc;
  }

  /**
   * Sends a legacy (non-module-aware) progress event via {@link ExtractionProgressService}.
   * No-op if jobId is null or blank (synchronous / no-streaming path).
   */
  private void sendProgress(String jobId, String phase, int filesProcessed, int totalFiles) {
    if (jobId != null && !jobId.isBlank()) {
      progressService.send(jobId,
          ExtractionProgressService.ProgressEvent.legacy(phase, filesProcessed, totalFiles));
    }
  }

  /**
   * Sends a module-aware progress event via {@link ExtractionProgressService}.
   * No-op if jobId is null or blank.
   */
  private void sendModuleProgress(String jobId, String module, String stage,
                                   int filesProcessed, int totalFiles, String message, Long durationMs) {
    if (jobId != null && !jobId.isBlank()) {
      progressService.send(jobId,
          new ExtractionProgressService.ProgressEvent(module, stage, filesProcessed, totalFiles, message, durationMs));
    }
  }

  // =========================================================================
  // Batched UNWIND MERGE persistence helpers
  // =========================================================================

  /**
   * Persists annotation nodes using batched UNWIND MERGE Cypher.
   * The Neo4j label {@code JavaAnnotation} matches {@code @Node("JavaAnnotation")} on
   * {@link com.esmp.extraction.model.AnnotationNode}. Business key: {@code fullyQualifiedName}.
   */
  private void persistAnnotationNodesBatched(List<AnnotationNode> nodes) {
    if (nodes.isEmpty()) return;
    String cypher = "UNWIND $rows AS row "
        + "MERGE (a:JavaAnnotation {fullyQualifiedName: row.fqn}) "
        + "ON CREATE SET a.simpleName = row.simpleName, a.packageName = row.packageName "
        + "ON MATCH SET a.simpleName = row.simpleName, a.packageName = row.packageName";
    List<Map<String, Object>> rows = nodes.stream()
        .map(n -> Map.<String, Object>of(
            "fqn", n.getFullyQualifiedName() != null ? n.getFullyQualifiedName() : "",
            "simpleName", n.getSimpleName() != null ? n.getSimpleName() : "",
            "packageName", n.getPackageName() != null ? n.getPackageName() : ""))
        .toList();
    for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
      neo4jClient.query(cypher)
          .bind(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()))).to("rows").run();
    }
    log.info("Persisted {} annotation nodes via batched UNWIND MERGE", nodes.size());
  }

  /**
   * Persists package nodes using batched UNWIND MERGE Cypher.
   * The Neo4j label {@code JavaPackage} matches {@code @Node("JavaPackage")} on
   * {@link com.esmp.extraction.model.PackageNode}. Business key: {@code packageName}.
   */
  private void persistPackageNodesBatched(List<PackageNode> nodes) {
    if (nodes.isEmpty()) return;
    String cypher = "UNWIND $rows AS row "
        + "MERGE (p:JavaPackage {packageName: row.packageName}) "
        + "ON CREATE SET p.simpleName = row.simpleName, p.moduleName = row.moduleName "
        + "ON MATCH SET p.simpleName = row.simpleName, p.moduleName = row.moduleName";
    List<Map<String, Object>> rows = nodes.stream()
        .map(n -> Map.<String, Object>of(
            "packageName", n.getPackageName() != null ? n.getPackageName() : "",
            "simpleName", n.getSimpleName() != null ? n.getSimpleName() : "",
            "moduleName", n.getModuleName() != null ? n.getModuleName() : ""))
        .toList();
    for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
      neo4jClient.query(cypher)
          .bind(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()))).to("rows").run();
    }
    log.info("Persisted {} package nodes via batched UNWIND MERGE", nodes.size());
  }

  /**
   * Persists module nodes using batched UNWIND MERGE Cypher.
   * The Neo4j label {@code JavaModule} matches {@code @Node("JavaModule")} on
   * {@link com.esmp.extraction.model.ModuleNode}. Business key: {@code moduleName}.
   */
  private void persistModuleNodesBatched(List<ModuleNode> nodes) {
    if (nodes.isEmpty()) return;
    String cypher = "UNWIND $rows AS row "
        + "MERGE (m:JavaModule {moduleName: row.moduleName}) "
        + "ON CREATE SET m.sourceRoot = row.sourceRoot "
        + "ON MATCH SET m.sourceRoot = row.sourceRoot";
    List<Map<String, Object>> rows = nodes.stream()
        .map(n -> Map.<String, Object>of(
            "moduleName", n.getModuleName() != null ? n.getModuleName() : "",
            "sourceRoot", n.getSourceRoot() != null ? n.getSourceRoot() : ""))
        .toList();
    for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
      neo4jClient.query(cypher)
          .bind(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()))).to("rows").run();
    }
    log.info("Persisted {} module nodes via batched UNWIND MERGE", nodes.size());
  }

  /**
   * Persists DB table nodes using batched UNWIND MERGE Cypher.
   * The Neo4j label {@code DBTable} matches {@code @Node("DBTable")} on
   * {@link com.esmp.extraction.model.DBTableNode}. Business key: {@code tableName}.
   */
  private void persistDBTableNodesBatched(List<DBTableNode> nodes) {
    if (nodes.isEmpty()) return;
    String cypher = "UNWIND $rows AS row "
        + "MERGE (t:DBTable {tableName: row.tableName}) "
        + "ON CREATE SET t.schemaName = row.schemaName "
        + "ON MATCH SET t.schemaName = row.schemaName";
    List<Map<String, Object>> rows = nodes.stream()
        .map(n -> Map.<String, Object>of(
            "tableName", n.getTableName() != null ? n.getTableName() : "",
            "schemaName", n.getSchemaName() != null ? n.getSchemaName() : ""))
        .toList();
    for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
      neo4jClient.query(cypher)
          .bind(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()))).to("rows").run();
    }
    log.info("Persisted {} DB table nodes via batched UNWIND MERGE", nodes.size());
  }

  /**
   * Persists business term nodes using batched UNWIND MERGE Cypher with curated-guard semantics.
   *
   * <p>ON CREATE: sets all properties for new terms.
   * ON MATCH: preserves displayName, definition, and criticality for curated=true terms;
   * always updates usageCount and status. Batched in groups of {@link #BATCH_SIZE}.
   *
   * @param businessTermNodes list of BusinessTermNode entities to persist
   */
  private void persistBusinessTermNodes(List<BusinessTermNode> businessTermNodes) {
    if (businessTermNodes.isEmpty()) return;
    String cypher =
        "UNWIND $rows AS row "
            + "MERGE (t:BusinessTerm {termId: row.termId}) "
            + "ON CREATE SET "
            + "  t.displayName = row.displayName, "
            + "  t.definition = row.definition, "
            + "  t.criticality = row.criticality, "
            + "  t.migrationSensitivity = row.sensitivity, "
            + "  t.curated = false, "
            + "  t.status = 'auto', "
            + "  t.sourceType = row.sourceType, "
            + "  t.primarySourceFqn = row.fqn, "
            + "  t.usageCount = row.usageCount, "
            + "  t.synonyms = [] "
            + "ON MATCH SET "
            + "  t.displayName = CASE WHEN t.curated THEN t.displayName ELSE row.displayName END, "
            + "  t.definition = CASE WHEN t.curated THEN t.definition ELSE row.definition END, "
            + "  t.criticality = CASE WHEN t.curated THEN t.criticality ELSE row.criticality END, "
            + "  t.usageCount = row.usageCount, "
            + "  t.status = CASE WHEN t.curated THEN 'curated' ELSE 'auto' END";

    List<Map<String, Object>> rows = businessTermNodes.stream()
        .map(node -> Map.<String, Object>of(
            "termId", node.getTermId(),
            "displayName", node.getDisplayName() != null ? node.getDisplayName() : node.getTermId(),
            "definition", node.getDefinition() != null ? node.getDefinition() : "",
            "criticality", node.getCriticality(),
            "sensitivity", node.getMigrationSensitivity(),
            "sourceType", node.getSourceType() != null ? node.getSourceType() : "UNKNOWN",
            "fqn", node.getPrimarySourceFqn() != null ? node.getPrimarySourceFqn() : "",
            "usageCount", node.getUsageCount()))
        .toList();

    for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
      neo4jClient.query(cypher)
          .bind(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()))).to("rows").run();
    }
    log.info("Persisted {} business term nodes via batched UNWIND MERGE", businessTermNodes.size());
  }

  /**
   * Persists migration action nodes using batched UNWIND MERGE Cypher.
   * The Neo4j label {@code MigrationAction} matches {@code @Node("MigrationAction")} on
   * {@link com.esmp.extraction.model.MigrationActionNode}. Business key: {@code actionId}.
   */
  private void persistMigrationActionNodesBatched(List<MigrationActionNode> nodes) {
    if (nodes.isEmpty()) return;
    String cypher = "UNWIND $rows AS row "
        + "MERGE (ma:MigrationAction {actionId: row.actionId}) "
        + "ON CREATE SET ma.classFqn = row.classFqn, ma.actionType = row.actionType, "
        + "  ma.source = row.source, ma.target = row.target, "
        + "  ma.automatable = row.automatable, ma.context = row.context "
        + "ON MATCH SET ma.classFqn = row.classFqn, ma.actionType = row.actionType, "
        + "  ma.source = row.source, ma.target = row.target, "
        + "  ma.automatable = row.automatable, ma.context = row.context";
    List<Map<String, Object>> rows = nodes.stream()
        .map(n -> Map.<String, Object>of(
            "actionId", n.getActionId() != null ? n.getActionId() : "",
            "classFqn", n.getClassFqn() != null ? n.getClassFqn() : "",
            "actionType", n.getActionType() != null ? n.getActionType() : "",
            "source", n.getSource() != null ? n.getSource() : "",
            "target", n.getTarget() != null ? n.getTarget() : "",
            "automatable", n.getAutomatable() != null ? n.getAutomatable() : "",
            "context", n.getContext() != null ? n.getContext() : ""))
        .toList();
    for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
      neo4jClient.query(cypher)
          .bind(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()))).to("rows").run();
    }
    log.info("Persisted {} migration action nodes via batched UNWIND MERGE", nodes.size());
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

  /** Per-module extraction summary for reporting in multi-module mode. */
  public record ModuleExtractionSummary(
      String moduleName,
      int classCount,
      int fileCount,
      long durationMs) {}

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
      int businessTermCount,
      LinkingService.LinkingResult linkingResult,
      int errorCount,
      List<String> errors,
      VaadinAuditReport auditReport,
      long durationMs,
      String buildSystem,
      List<ModuleExtractionSummary> moduleSummaries,
      List<String> skippedModules) {}
}
