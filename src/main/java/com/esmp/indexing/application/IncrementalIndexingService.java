package com.esmp.indexing.application;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.VectorsFactory.vectors;

import com.esmp.extraction.application.AccumulatorToModelMapper;
import com.esmp.extraction.application.LinkingService;
import com.esmp.extraction.application.LinkingService.LinkingResult;
import com.esmp.extraction.config.ExtractionConfig;
import com.esmp.extraction.model.AnnotationNode;
import com.esmp.extraction.model.BusinessTermNode;
import com.esmp.extraction.model.ClassNode;
import com.esmp.extraction.model.DBTableNode;
import com.esmp.extraction.model.ModuleNode;
import com.esmp.extraction.model.PackageNode;
import com.esmp.extraction.parser.JavaSourceParser;
import com.esmp.extraction.persistence.AnnotationNodeRepository;
import com.esmp.extraction.persistence.BusinessTermNodeRepository;
import com.esmp.extraction.persistence.ClassNodeRepository;
import com.esmp.extraction.persistence.DBTableNodeRepository;
import com.esmp.extraction.persistence.ModuleNodeRepository;
import com.esmp.extraction.persistence.PackageNodeRepository;
import com.esmp.extraction.visitor.CallGraphVisitor;
import com.esmp.extraction.visitor.ClassMetadataVisitor;
import com.esmp.extraction.visitor.ComplexityVisitor;
import com.esmp.extraction.visitor.DependencyVisitor;
import com.esmp.extraction.visitor.ExtractionAccumulator;
import com.esmp.extraction.visitor.JpaPatternVisitor;
import com.esmp.extraction.visitor.LexiconVisitor;
import com.esmp.extraction.visitor.VaadinPatternVisitor;
import com.esmp.graph.application.RiskService;
import com.esmp.indexing.api.IncrementalIndexRequest;
import com.esmp.mcp.application.McpCacheEvictionService;
import com.esmp.indexing.api.IncrementalIndexResponse;
import com.esmp.indexing.util.FileHashUtil;
import com.esmp.vector.application.ChunkingService;
import com.esmp.vector.application.VectorIndexingService;
import com.esmp.vector.config.VectorConfig;
import com.esmp.vector.model.CodeChunk;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.PointStruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.openrewrite.SourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the incremental indexing pipeline for the ESMP knowledge graph and vector store.
 *
 * <p>Unlike {@link com.esmp.extraction.application.ExtractionService}, this service processes only
 * the changed and deleted files supplied by a CI/CD pipeline call, rather than scanning the entire
 * source tree. The pipeline is designed to keep the knowledge graph and Qdrant vector store current
 * without the latency of a full re-extraction run (CI-01, CI-02, CI-03, SLO-03, SLO-04).
 *
 * <h2>Pipeline steps</h2>
 * <ol>
 *   <li>Validate input — resolve source root, confirm directory exists.
 *   <li>Delete pass — DETACH DELETE removed class nodes from Neo4j, remove their Qdrant points.
 *   <li>Hash filter — compare SHA-256 of changed files against stored {@code contentHash}; skip
 *       files whose content has not changed.
 *   <li>Parse and persist — parse only truly-changed files, run all 7 visitors, save all entity
 *       types with hash injection.
 *   <li>Global link — {@code LinkingService.linkAllRelationships()} creates/merges all 9 edge types.
 *   <li>Global risk — {@code RiskService.computeAndPersistRiskScores()} recomputes all risk scores.
 *   <li>Selective vector re-embed — delete old Qdrant points for changed classes, chunk and
 *       re-embed only those classes.
 *   <li>Build and return response with all counts.
 * </ol>
 *
 * <h2>Transactional design</h2>
 * <p>{@link #runIncremental} is intentionally NOT {@code @Transactional}. The delete step and the
 * extract-persist step each carry their own {@code @Transactional("neo4jTransactionManager")}
 * annotation. This ensures the delete commits before extraction begins, avoiding SDN session-cache
 * version conflicts (see 10-RESEARCH.md Pitfall 1).
 */
@Service
public class IncrementalIndexingService {

  private static final Logger log = LoggerFactory.getLogger(IncrementalIndexingService.class);

  private final JavaSourceParser javaSourceParser;
  private final AccumulatorToModelMapper mapper;
  private final ClassNodeRepository classNodeRepository;
  private final AnnotationNodeRepository annotationNodeRepository;
  private final PackageNodeRepository packageNodeRepository;
  private final ModuleNodeRepository moduleNodeRepository;
  private final DBTableNodeRepository dbTableNodeRepository;
  private final BusinessTermNodeRepository businessTermNodeRepository;
  private final Neo4jClient neo4jClient;
  private final LinkingService linkingService;
  private final RiskService riskService;
  private final VectorIndexingService vectorIndexingService;
  private final ChunkingService chunkingService;
  private final EmbeddingModel embeddingModel;
  private final QdrantClient qdrantClient;
  private final VectorConfig vectorConfig;
  private final ExtractionConfig extractionConfig;
  private final McpCacheEvictionService mcpCacheEvictionService;

  public IncrementalIndexingService(
      JavaSourceParser javaSourceParser,
      AccumulatorToModelMapper mapper,
      ClassNodeRepository classNodeRepository,
      AnnotationNodeRepository annotationNodeRepository,
      PackageNodeRepository packageNodeRepository,
      ModuleNodeRepository moduleNodeRepository,
      DBTableNodeRepository dbTableNodeRepository,
      BusinessTermNodeRepository businessTermNodeRepository,
      Neo4jClient neo4jClient,
      LinkingService linkingService,
      RiskService riskService,
      VectorIndexingService vectorIndexingService,
      ChunkingService chunkingService,
      EmbeddingModel embeddingModel,
      QdrantClient qdrantClient,
      VectorConfig vectorConfig,
      ExtractionConfig extractionConfig,
      McpCacheEvictionService mcpCacheEvictionService) {
    this.javaSourceParser = javaSourceParser;
    this.mapper = mapper;
    this.classNodeRepository = classNodeRepository;
    this.annotationNodeRepository = annotationNodeRepository;
    this.packageNodeRepository = packageNodeRepository;
    this.moduleNodeRepository = moduleNodeRepository;
    this.dbTableNodeRepository = dbTableNodeRepository;
    this.businessTermNodeRepository = businessTermNodeRepository;
    this.neo4jClient = neo4jClient;
    this.linkingService = linkingService;
    this.riskService = riskService;
    this.vectorIndexingService = vectorIndexingService;
    this.chunkingService = chunkingService;
    this.embeddingModel = embeddingModel;
    this.qdrantClient = qdrantClient;
    this.vectorConfig = vectorConfig;
    this.extractionConfig = extractionConfig;
    this.mcpCacheEvictionService = mcpCacheEvictionService;
  }

  // -------------------------------------------------------------------------
  // Main entry point — NOT @Transactional (delete and persist use separate TXs)
  // -------------------------------------------------------------------------

  /**
   * Runs the full incremental indexing pipeline for the supplied changed and deleted files.
   *
   * @param request incremental index request carrying changed/deleted file lists and source root
   * @return response with per-stage counts and any non-fatal error messages
   */
  public IncrementalIndexResponse runIncremental(IncrementalIndexRequest request) {
    long startMs = System.currentTimeMillis();
    List<String> errors = new ArrayList<>();

    // Step 1 — Validate
    String resolvedSourceRoot =
        (request.sourceRoot() != null && !request.sourceRoot().isBlank())
            ? request.sourceRoot()
            : extractionConfig.getSourceRoot();
    String resolvedClasspathFile =
        (request.classpathFile() != null && !request.classpathFile().isBlank())
            ? request.classpathFile()
            : extractionConfig.getClasspathFile();

    Path sourceRootPath = Path.of(resolvedSourceRoot);
    if (!Files.isDirectory(sourceRootPath)) {
      String msg = "sourceRoot does not exist or is not a directory: " + sourceRootPath;
      log.error(msg);
      return new IncrementalIndexResponse(0, 0, 0, 0, 0, 0, 0, 0,
          System.currentTimeMillis() - startMs, List.of(msg));
    }

    // Step 2 — Delete pass
    int classesDeleted = 0;
    int chunksDeletedForDeleted = 0;

    if (!request.deletedFiles().isEmpty()) {
      // Resolve deleted file paths to relative paths for Neo4j lookup
      List<String> deletedRelativePaths = new ArrayList<>();
      for (String deletedFile : request.deletedFiles()) {
        try {
          Path absolutePath = Path.of(deletedFile);
          String relativePath = sourceRootPath.relativize(absolutePath).toString().replace('\\', '/');
          deletedRelativePaths.add(relativePath);
        } catch (Exception e) {
          // Path may already be relative — use as-is
          deletedRelativePaths.add(deletedFile.replace('\\', '/'));
        }
      }

      // Look up FQNs for deleted files from Neo4j, then DETACH DELETE
      List<String> deletedFqns = resolveFqnsForPaths(deletedRelativePaths);
      DeleteResult dr = deleteClassesTransactional(deletedFqns);
      classesDeleted = dr.deletedCount();
      errors.addAll(dr.errors());

      // Remove Qdrant points for deleted FQNs (non-transactional; failures are non-fatal)
      for (String fqn : deletedFqns) {
        try {
          vectorIndexingService.deleteByClass(fqn);
          chunksDeletedForDeleted++;
        } catch (Exception e) {
          String msg = "Failed to delete Qdrant points for deleted class '" + fqn + "': " + e.getMessage();
          log.warn(msg);
          errors.add(msg);
        }
      }
    }

    // Step 3 — Hash filter
    List<Path> trulyChangedPaths = new ArrayList<>();
    Map<String, String> fileHashMap = new HashMap<>();
    int classesSkipped = 0;

    if (!request.changedFiles().isEmpty()) {
      // Compute SHA-256 for each changed file
      Map<String, String> computedHashes = new HashMap<>(); // relativePath -> hash
      Map<String, Path> relativeToAbsolute = new HashMap<>(); // relativePath -> absolute Path

      for (String changedFile : request.changedFiles()) {
        Path absolutePath = Path.of(changedFile);
        if (!absolutePath.isAbsolute()) {
          absolutePath = sourceRootPath.resolve(changedFile);
        }
        if (!Files.exists(absolutePath)) {
          log.warn("Changed file does not exist on disk, skipping: {}", absolutePath);
          errors.add("Changed file not found on disk: " + absolutePath);
          continue;
        }
        try {
          String hash = FileHashUtil.sha256(absolutePath);
          String relativePath = FileHashUtil.relativize(sourceRootPath, absolutePath);
          computedHashes.put(relativePath, hash);
          relativeToAbsolute.put(relativePath, absolutePath);
          fileHashMap.put(relativePath, hash);
        } catch (IOException e) {
          String msg = "Failed to hash file '" + changedFile + "': " + e.getMessage();
          log.warn(msg);
          errors.add(msg);
        }
      }

      // Batch-fetch stored hashes from Neo4j
      List<String> relativePaths = new ArrayList<>(computedHashes.keySet());
      Map<String, String> storedHashes = fetchStoredHashes(relativePaths);

      // Compare: skip files whose hash matches stored hash
      for (Map.Entry<String, String> entry : computedHashes.entrySet()) {
        String relativePath = entry.getKey();
        String computedHash = entry.getValue();
        String storedHash = storedHashes.get(relativePath);

        if (computedHash.equals(storedHash)) {
          log.debug("Skipping '{}' — hash unchanged ({})", relativePath, computedHash);
          classesSkipped++;
        } else {
          log.debug("Queuing '{}' for extraction — hash changed ({} -> {})",
              relativePath, storedHash, computedHash);
          trulyChangedPaths.add(relativeToAbsolute.get(relativePath));
        }
      }
    }

    log.info(
        "Hash filter: {} files to extract, {} skipped (unchanged), {} deleted.",
        trulyChangedPaths.size(), classesSkipped, classesDeleted);

    // Step 3b — Delete stale ClassNodes for changed files before re-extracting.
    // This prevents SDN @Version OptimisticLockingFailureException when a ClassNode already
    // exists in Neo4j (version > 0) and saveAll() tries to MERGE a new transient entity
    // (version = null) that the session cache interprets as a CREATE rather than an UPDATE.
    if (!trulyChangedPaths.isEmpty()) {
      List<String> changedRelativePaths = trulyChangedPaths.stream()
          .map(p -> FileHashUtil.relativize(sourceRootPath, p))
          .toList();
      List<String> changedFqnsToDelete = resolveFqnsForPaths(changedRelativePaths);
      if (!changedFqnsToDelete.isEmpty()) {
        log.info("Pre-deleting {} stale ClassNodes for changed files before re-extraction: {}",
            changedFqnsToDelete.size(), changedFqnsToDelete);
        DeleteResult preDeleteResult = deleteClassesTransactional(changedFqnsToDelete);
        errors.addAll(preDeleteResult.errors());
        log.info("Pre-delete complete: {} nodes removed.", preDeleteResult.deletedCount());
      } else {
        log.info("No stale ClassNodes found for changed relative paths: {}", changedRelativePaths);
      }
    }

    // Step 4 — Parse and persist (separate TX)
    ExtractionAccumulator accumulator = new ExtractionAccumulator();
    int nodesCreated = 0;
    int nodesUpdated = 0;

    if (!trulyChangedPaths.isEmpty()) {
      try {
        ExtractResult er = extractAndPersistTransactional(
            trulyChangedPaths, sourceRootPath, resolvedClasspathFile,
            resolvedSourceRoot, fileHashMap);
        accumulator = er.accumulator();
        nodesCreated = er.nodesCreated();
        nodesUpdated = er.nodesUpdated();
        errors.addAll(er.errors());
      } catch (Exception e) {
        String msg = "Extract-persist step failed: " + e.getMessage();
        log.error(msg, e);
        errors.add(msg);
      }
    }

    // Step 5 — Global link (always run — deletion may have invalidated edges)
    int edgesLinked = 0;
    try {
      LinkingResult lr = linkingService.linkAllRelationships(accumulator);
      edgesLinked = lr.extendsCount() + lr.dependsOnCount() + lr.mapsToTableCount()
          + lr.queriesCount() + lr.hasAnnotationCount() + lr.containsHierarchyCount()
          + lr.bindsToCount() + lr.usesTermCount() + lr.definesRuleCount();
      log.info("Linking complete: {} total edges merged.", edgesLinked);
    } catch (Exception e) {
      String msg = "Linking step failed: " + e.getMessage();
      log.error(msg, e);
      errors.add(msg);
    }

    // Step 6 — Global risk (always run after linking)
    try {
      riskService.computeAndPersistRiskScores();
      log.info("Risk computation complete.");
    } catch (Exception e) {
      String msg = "Risk computation step failed: " + e.getMessage();
      log.error(msg, e);
      errors.add(msg);
    }

    // Step 7 — Selective vector re-embed for changed classes
    int chunksReEmbedded = 0;
    int chunksDeletedForChanged = 0;

    List<String> changedFqns = new ArrayList<>(accumulator.getClasses().keySet());
    if (!changedFqns.isEmpty()) {
      // Remove old Qdrant points for changed classes (idempotent delete before upsert)
      for (String fqn : changedFqns) {
        try {
          vectorIndexingService.deleteByClass(fqn);
          chunksDeletedForChanged++;
        } catch (Exception e) {
          log.warn("Failed to delete old Qdrant points for '{}': {}", fqn, e.getMessage());
        }
      }

      // Chunk only the changed classes
      try {
        List<CodeChunk> chunks = chunkingService.chunkByFqns(changedFqns, resolvedSourceRoot);
        log.info("Produced {} chunks for {} changed classes.", chunks.size(), changedFqns.size());

        // Embed and upsert in batches
        List<List<CodeChunk>> batches = partition(chunks, vectorConfig.getBatchSize());
        for (List<CodeChunk> batch : batches) {
          try {
            int upserted = embedAndUpsert(batch);
            chunksReEmbedded += upserted;
          } catch (Exception e) {
            String msg = "Batch vector upsert failed for " + batch.size() + " chunks: " + e.getMessage();
            log.error(msg, e);
            errors.add(msg);
          }
        }
        log.info("Vector re-embed complete: {} chunks upserted.", chunksReEmbedded);
      } catch (Exception e) {
        String msg = "Chunking step failed for changed classes: " + e.getMessage();
        log.error(msg, e);
        errors.add(msg);
      }
    }

    // Step 7b — MCP cache eviction (after all graph/vector updates complete)
    if (mcpCacheEvictionService != null) {
      try {
        if (!changedFqns.isEmpty()) {
          mcpCacheEvictionService.evictForClasses(changedFqns);
        } else if (request.changedFiles().isEmpty() && !request.deletedFiles().isEmpty()) {
          // Full re-index or delete-only path — clear all caches
          mcpCacheEvictionService.evictAll();
        }
      } catch (Exception e) {
        log.warn("MCP cache eviction failed (non-fatal): {}", e.getMessage());
      }
    }

    // Step 8 — Build response
    int classesExtracted = trulyChangedPaths.size();
    int totalChunksDeleted = chunksDeletedForDeleted + chunksDeletedForChanged;
    long durationMs = System.currentTimeMillis() - startMs;

    log.info(
        "Incremental indexing complete in {}ms: extracted={}, deleted={}, skipped={}, "
            + "edges={}, chunksReEmbedded={}, chunksDeleted={}, errors={}",
        durationMs, classesExtracted, classesDeleted, classesSkipped,
        edgesLinked, chunksReEmbedded, totalChunksDeleted, errors.size());

    return new IncrementalIndexResponse(
        classesExtracted, classesDeleted, classesSkipped,
        nodesCreated, nodesUpdated, edgesLinked,
        chunksReEmbedded, totalChunksDeleted, durationMs, errors);
  }

  // -------------------------------------------------------------------------
  // Step 2: Delete classes (separate @Transactional — commits before extract starts)
  // -------------------------------------------------------------------------

  @Transactional("neo4jTransactionManager")
  DeleteResult deleteClassesTransactional(List<String> fqns) {
    int deleted = 0;
    List<String> errors = new ArrayList<>();

    for (String fqn : fqns) {
      try {
        neo4jClient.query(
                "MATCH (c:JavaClass {fullyQualifiedName: $fqn}) "
                    + "OPTIONAL MATCH (c)-[:DECLARES_METHOD]->(m:JavaMethod) "
                    + "OPTIONAL MATCH (c)-[:DECLARES_FIELD]->(f:JavaField) "
                    + "DETACH DELETE c, m, f")
            .bind(fqn).to("fqn")
            .run();
        deleted++;
        log.info("Deleted Neo4j nodes for class '{}'", fqn);
      } catch (Exception e) {
        String msg = "Failed to delete Neo4j nodes for FQN '" + fqn + "': " + e.getMessage();
        log.error(msg, e);
        errors.add(msg);
      }
    }
    return new DeleteResult(deleted, errors);
  }

  // -------------------------------------------------------------------------
  // Step 4: Extract and persist (separate @Transactional)
  // -------------------------------------------------------------------------

  @Transactional("neo4jTransactionManager")
  ExtractResult extractAndPersistTransactional(
      List<Path> changedPaths,
      Path sourceRootPath,
      String classpathFile,
      String sourceRoot,
      Map<String, String> fileHashMap) {

    List<String> errors = new ArrayList<>();

    // Parse changed files
    List<SourceFile> sourceFiles = javaSourceParser.parse(changedPaths, sourceRootPath, classpathFile);

    // Run all 7 visitors (same order as ExtractionService)
    ExtractionAccumulator accumulator = new ExtractionAccumulator();
    ClassMetadataVisitor classMetadataVisitor = new ClassMetadataVisitor();
    CallGraphVisitor callGraphVisitor = new CallGraphVisitor();
    VaadinPatternVisitor vaadinPatternVisitor = new VaadinPatternVisitor();
    DependencyVisitor dependencyVisitor = new DependencyVisitor();
    JpaPatternVisitor jpaPatternVisitor = new JpaPatternVisitor();
    LexiconVisitor lexiconVisitor = new LexiconVisitor();
    ComplexityVisitor complexityVisitor = new ComplexityVisitor();

    for (SourceFile sourceFile : sourceFiles) {
      try {
        classMetadataVisitor.visit(sourceFile, accumulator);
        callGraphVisitor.visit(sourceFile, accumulator);
        vaadinPatternVisitor.visit(sourceFile, accumulator);
        dependencyVisitor.visit(sourceFile, accumulator);
        jpaPatternVisitor.visit(sourceFile, accumulator);
        lexiconVisitor.visit(sourceFile, accumulator);
        complexityVisitor.visit(sourceFile, accumulator);
      } catch (Exception e) {
        String msg = "Error visiting " + sourceFile.getSourcePath() + ": " + e.getMessage();
        log.warn(msg, e);
        errors.add(msg);
      }
    }

    // Map accumulator to entity objects
    List<ClassNode> classNodes = mapper.mapToClassNodes(accumulator);
    List<AnnotationNode> annotationNodes = mapper.mapToAnnotationNodes(accumulator);
    List<PackageNode> packageNodes = mapper.mapToPackageNodes(accumulator);
    List<ModuleNode> moduleNodes = mapper.mapToModuleNodes(accumulator, sourceRoot);
    List<DBTableNode> dbTableNodes = mapper.mapToDBTableNodes(accumulator);
    List<BusinessTermNode> businessTermNodes = mapper.mapToBusinessTermNodes(accumulator);

    // Inject contentHash into each ClassNode BEFORE saveAll
    for (ClassNode classNode : classNodes) {
      String relativePath = classNode.getSourceFilePath();
      if (relativePath != null) {
        String hash = fileHashMap.get(relativePath);
        if (hash != null) {
          classNode.setContentHash(hash);
        }
      }
    }

    // Persist all entity types.
    //
    // ClassNode: pre-deleted in Step 3b, so always new — SDN saveAll() is safe.
    //
    // AnnotationNode, PackageNode, ModuleNode, DBTableNode: these are shared entities that may
    // already exist in Neo4j from prior extractions (with @Version > 0). Using SDN saveAll() on a
    // new transient instance (version=null) triggers an OptimisticLockingFailureException at TX
    // commit time (SDN's version check: WHERE version = coalesce($v, 0) fails for version > 0).
    // Fix: use neo4jClient raw Cypher MERGE for shared entity types to bypass the @Version check.
    int nodesCreated = 0;
    int nodesUpdated = 0;

    classNodeRepository.saveAll(classNodes);
    log.info("Persisted {} class nodes", classNodes.size());
    nodesCreated += classNodes.size();

    // AnnotationNode MERGE via raw Cypher — bypasses @Version conflict for existing annotations
    for (AnnotationNode node : annotationNodes) {
      neo4jClient.query(
              "MERGE (a:JavaAnnotation {fullyQualifiedName: $fqn}) "
                  + "ON CREATE SET a.simpleName = $simpleName, a.packageName = $pkg, "
                  + "  a.retention = $retention "
                  + "ON MATCH SET a.simpleName = $simpleName, a.packageName = $pkg, "
                  + "  a.retention = $retention")
          .bindAll(Map.of(
              "fqn", node.getFullyQualifiedName() != null ? node.getFullyQualifiedName() : "",
              "simpleName", node.getSimpleName() != null ? node.getSimpleName() : "",
              "pkg", node.getPackageName() != null ? node.getPackageName() : "",
              "retention", node.getRetention() != null ? node.getRetention() : ""))
          .run();
    }
    log.info("Persisted {} annotation nodes (Cypher MERGE)", annotationNodes.size());
    nodesCreated += annotationNodes.size();

    // PackageNode MERGE via raw Cypher — bypasses @Version conflict for existing packages
    for (PackageNode node : packageNodes) {
      neo4jClient.query(
              "MERGE (p:JavaPackage {packageName: $name}) "
                  + "ON CREATE SET p.simpleName = $simpleName, p.moduleName = $moduleName "
                  + "ON MATCH SET p.simpleName = $simpleName, p.moduleName = $moduleName")
          .bindAll(Map.of(
              "name", node.getPackageName() != null ? node.getPackageName() : "",
              "simpleName", node.getSimpleName() != null ? node.getSimpleName() : "",
              "moduleName", node.getModuleName() != null ? node.getModuleName() : ""))
          .run();
    }
    log.info("Persisted {} package nodes (Cypher MERGE)", packageNodes.size());
    nodesCreated += packageNodes.size();

    // ModuleNode MERGE via raw Cypher — bypasses @Version conflict for existing modules
    for (ModuleNode node : moduleNodes) {
      neo4jClient.query(
              "MERGE (m:JavaModule {moduleName: $name}) "
                  + "ON CREATE SET m.sourceRoot = $sourceRoot, "
                  + "  m.isMultiModuleSubproject = $multi "
                  + "ON MATCH SET m.sourceRoot = $sourceRoot, "
                  + "  m.isMultiModuleSubproject = $multi")
          .bindAll(Map.of(
              "name", node.getModuleName() != null ? node.getModuleName() : "",
              "sourceRoot", node.getSourceRoot() != null ? node.getSourceRoot() : "",
              "multi", node.isMultiModuleSubproject()))
          .run();
    }
    log.info("Persisted {} module nodes (Cypher MERGE)", moduleNodes.size());
    nodesCreated += moduleNodes.size();

    // DBTableNode MERGE via raw Cypher — bypasses @Version conflict for existing table nodes
    for (DBTableNode node : dbTableNodes) {
      neo4jClient.query(
              "MERGE (t:DBTable {tableName: $name}) "
                  + "ON CREATE SET t.schemaName = $schema "
                  + "ON MATCH SET t.schemaName = $schema")
          .bindAll(Map.of(
              "name", node.getTableName() != null ? node.getTableName() : "",
              "schema", node.getSchemaName() != null ? node.getSchemaName() : ""))
          .run();
    }
    log.info("Persisted {} DB table nodes (Cypher MERGE)", dbTableNodes.size());
    nodesCreated += dbTableNodes.size();

    // Persist business terms using curated-guard MERGE (LEX-02/LEX-04 compliance)
    persistBusinessTermNodes(businessTermNodes);
    nodesCreated += businessTermNodes.size();

    return new ExtractResult(accumulator, nodesCreated, nodesUpdated, errors);
  }

  // -------------------------------------------------------------------------
  // Neo4j helpers
  // -------------------------------------------------------------------------

  /**
   * Resolves fully-qualified class names for the given relative source file paths.
   *
   * @param relativePaths relative paths in the form {@code com/example/Foo.java}
   * @return list of FQNs found in Neo4j (may be shorter than {@code relativePaths} if some files
   *         have no stored node)
   */
  private List<String> resolveFqnsForPaths(List<String> relativePaths) {
    if (relativePaths.isEmpty()) {
      return List.of();
    }
    String cypher =
        "MATCH (c:JavaClass) WHERE c.sourceFilePath IN $paths "
            + "RETURN c.fullyQualifiedName AS fqn";
    Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
        .bind(relativePaths).to("paths")
        .fetch()
        .all();
    return rows.stream()
        .map(row -> (String) row.get("fqn"))
        .filter(fqn -> fqn != null && !fqn.isBlank())
        .collect(Collectors.toList());
  }

  /**
   * Batch-fetches stored content hashes from Neo4j for the given relative source file paths.
   *
   * @param relativePaths relative paths in the form {@code com/example/Foo.java}
   * @return map of relativePath -> contentHash for paths that have a stored hash
   */
  private Map<String, String> fetchStoredHashes(List<String> relativePaths) {
    if (relativePaths.isEmpty()) {
      return Map.of();
    }
    String cypher =
        "MATCH (c:JavaClass) WHERE c.sourceFilePath IN $paths "
            + "RETURN c.sourceFilePath AS path, c.contentHash AS hash";
    Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
        .bind(relativePaths).to("paths")
        .fetch()
        .all();
    Map<String, String> result = new HashMap<>();
    for (Map<String, Object> row : rows) {
      String path = (String) row.get("path");
      String hash = (String) row.get("hash");
      if (path != null && !path.isBlank() && hash != null && !hash.isBlank()) {
        result.put(path, hash);
      }
    }
    return result;
  }

  // -------------------------------------------------------------------------
  // Business term persistence (curated-guard MERGE — mirrors ExtractionService)
  // -------------------------------------------------------------------------

  private void persistBusinessTermNodes(List<BusinessTermNode> businessTermNodes) {
    String cypher =
        "MERGE (t:BusinessTerm {termId: $termId}) "
            + "ON CREATE SET "
            + "  t.displayName = $displayName, "
            + "  t.definition = $definition, "
            + "  t.criticality = $criticality, "
            + "  t.migrationSensitivity = $sensitivity, "
            + "  t.curated = false, "
            + "  t.status = 'auto', "
            + "  t.sourceType = $sourceType, "
            + "  t.primarySourceFqn = $fqn, "
            + "  t.usageCount = $usageCount, "
            + "  t.synonyms = [] "
            + "ON MATCH SET "
            + "  t.displayName = CASE WHEN t.curated THEN t.displayName ELSE $displayName END, "
            + "  t.definition = CASE WHEN t.curated THEN t.definition ELSE $definition END, "
            + "  t.criticality = CASE WHEN t.curated THEN t.criticality ELSE $criticality END, "
            + "  t.usageCount = $usageCount, "
            + "  t.status = CASE WHEN t.curated THEN 'curated' ELSE 'auto' END";

    for (BusinessTermNode node : businessTermNodes) {
      neo4jClient.query(cypher)
          .bindAll(Map.of(
              "termId", node.getTermId(),
              "displayName", node.getDisplayName() != null ? node.getDisplayName() : node.getTermId(),
              "definition", node.getDefinition() != null ? node.getDefinition() : "",
              "criticality", node.getCriticality(),
              "sensitivity", node.getMigrationSensitivity(),
              "sourceType", node.getSourceType() != null ? node.getSourceType() : "UNKNOWN",
              "fqn", node.getPrimarySourceFqn() != null ? node.getPrimarySourceFqn() : "",
              "usageCount", node.getUsageCount()))
          .run();
    }
    log.info("Persisted {} business term nodes (curated-guard MERGE)", businessTermNodes.size());
  }

  // -------------------------------------------------------------------------
  // Vector helpers — replicate embedAndUpsert from VectorIndexingService
  // -------------------------------------------------------------------------

  /**
   * Embeds a batch of chunks and upserts them to Qdrant.
   *
   * @param batch list of code chunks to embed and upsert
   * @return number of points upserted
   * @throws Exception if embedding or upsert fails
   */
  private int embedAndUpsert(List<CodeChunk> batch) throws Exception {
    List<String> texts = batch.stream().map(CodeChunk::text).toList();
    List<float[]> embeddings = embeddingModel.embed(texts);

    List<PointStruct> points = new ArrayList<>(batch.size());
    for (int i = 0; i < batch.size(); i++) {
      CodeChunk chunk = batch.get(i);
      float[] embedding = embeddings.get(i);
      points.add(buildPointStruct(chunk, embedding));
    }

    qdrantClient.upsertAsync(vectorConfig.getCollectionName(), points)
        .get(30, TimeUnit.SECONDS);
    return points.size();
  }

  private PointStruct buildPointStruct(CodeChunk chunk, float[] embedding) {
    return PointStruct.newBuilder()
        .setId(id(chunk.pointId()))
        .setVectors(vectors(embedding))
        .putPayload("classFqn", io.qdrant.client.ValueFactory.value(chunk.classFqn()))
        .putPayload("chunkType", io.qdrant.client.ValueFactory.value(chunk.chunkType().name()))
        .putPayload("methodId", io.qdrant.client.ValueFactory.value(
            chunk.methodId() != null ? chunk.methodId() : ""))
        .putPayload("classHeaderId", io.qdrant.client.ValueFactory.value(
            chunk.classHeaderId() != null ? chunk.classHeaderId() : ""))
        .putPayload("module", io.qdrant.client.ValueFactory.value(
            chunk.module() != null ? chunk.module() : ""))
        .putPayload("stereotype", io.qdrant.client.ValueFactory.value(
            chunk.stereotype() != null ? chunk.stereotype() : ""))
        .putPayload("contentHash", io.qdrant.client.ValueFactory.value(
            chunk.contentHash() != null ? chunk.contentHash() : ""))
        .putPayload("structuralRiskScore", io.qdrant.client.ValueFactory.value(chunk.structuralRiskScore()))
        .putPayload("enhancedRiskScore", io.qdrant.client.ValueFactory.value(chunk.enhancedRiskScore()))
        .putPayload("domainCriticality", io.qdrant.client.ValueFactory.value(chunk.domainCriticality()))
        .putPayload("securitySensitivity", io.qdrant.client.ValueFactory.value(chunk.securitySensitivity()))
        .putPayload("financialInvolvement", io.qdrant.client.ValueFactory.value(chunk.financialInvolvement()))
        .putPayload("businessRuleDensity", io.qdrant.client.ValueFactory.value(chunk.businessRuleDensity()))
        .putPayload("vaadin7Detected", io.qdrant.client.ValueFactory.value(chunk.vaadin7Detected()))
        .putPayload("callers", io.qdrant.client.ValueFactory.value(joinList(chunk.callers())))
        .putPayload("callees", io.qdrant.client.ValueFactory.value(joinList(chunk.callees())))
        .putPayload("dependencies", io.qdrant.client.ValueFactory.value(joinList(chunk.dependencies())))
        .putPayload("implementors", io.qdrant.client.ValueFactory.value(joinList(chunk.implementors())))
        .putPayload("vaadinPatterns", io.qdrant.client.ValueFactory.value(joinList(chunk.vaadinPatterns())))
        .putPayload("domainTerms", io.qdrant.client.ValueFactory.value(serializeTerms(chunk)))
        .build();
  }

  // -------------------------------------------------------------------------
  // Utility helpers
  // -------------------------------------------------------------------------

  private static String joinList(List<String> list) {
    if (list == null || list.isEmpty()) return "";
    return String.join(",", list);
  }

  private static String serializeTerms(CodeChunk chunk) {
    var terms = chunk.domainTerms();
    if (terms == null || terms.isEmpty()) return "[]";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < terms.size(); i++) {
      var t = terms.get(i);
      sb.append("{\"termId\":\"").append(escape(t.termId()))
          .append("\",\"displayName\":\"").append(escape(t.displayName())).append("\"}");
      if (i < terms.size() - 1) sb.append(",");
    }
    sb.append("]");
    return sb.toString();
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static <T> List<List<T>> partition(List<T> list, int size) {
    List<List<T>> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
      result.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return result;
  }

  // -------------------------------------------------------------------------
  // Internal result records
  // -------------------------------------------------------------------------

  record DeleteResult(int deletedCount, List<String> errors) {}

  record ExtractResult(
      ExtractionAccumulator accumulator,
      int nodesCreated,
      int nodesUpdated,
      List<String> errors) {}
}
