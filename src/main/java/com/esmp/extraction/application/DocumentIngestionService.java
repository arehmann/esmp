package com.esmp.extraction.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Ingests pre-extracted legacy documentation (JSON) and enriches BusinessTerm nodes
 * with documentation context. Also generates per-class business descriptions.
 *
 * <p>The legacy docs were extracted offline via Apache Tika into structured JSON files
 * (section heading + body text). This service loads them from the classpath at startup,
 * then matches doc sections to existing BusinessTerms by keyword overlap.
 *
 * <p>Enrichment strategy:
 * <ul>
 *   <li>For each NLS BusinessTerm, find doc sections containing matching keywords
 *       (from German definition + English displayName)
 *   <li>Store the best-matching doc snippet as {@code documentContext} on the term
 *   <li>For each JavaClass, assemble a {@code businessDescription} from its linked terms
 * </ul>
 */
@Service
public class DocumentIngestionService {

  private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  /** Max chars for a single doc context snippet stored on a BusinessTerm. */
  private static final int MAX_DOC_CONTEXT_LENGTH = 500;

  /** Max chars for a class business description. */
  private static final int MAX_BUSINESS_DESC_LENGTH = 300;

  /** Minimum keyword overlap score to consider a section relevant. */
  private static final int MIN_MATCH_SCORE = 2;

  /** German stop words to exclude from keyword matching. */
  private static final Set<String> STOP_WORDS = Set.of(
      "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "eines", "einem", "einen",
      "und", "oder", "aber", "von", "für", "mit", "auf", "aus", "bei", "nach", "über", "unter",
      "durch", "bis", "als", "wie", "wenn", "dann", "auch", "noch", "nur", "schon", "kann",
      "wird", "ist", "sind", "hat", "haben", "sein", "werden", "nicht", "sich", "zur", "zum",
      "vom", "ins", "dieser", "diese", "dieses", "einem", "alle", "wird", "kann", "dass",
      "the", "and", "for", "with", "from", "this", "that", "are", "was", "were", "has", "have",
      "been", "will", "can", "not", "all", "but", "also", "into"
  );

  private final Neo4jClient neo4jClient;

  /** In-memory index: section heading → DocSection with body and keywords. */
  private final List<DocSection> allSections = new ArrayList<>();

  public DocumentIngestionService(Neo4jClient neo4jClient) {
    this.neo4jClient = neo4jClient;
  }

  @PostConstruct
  void loadDocuments() {
    try {
      PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
      Resource[] resources = resolver.getResources("classpath:bundles/legacy-docs/*.json");

      if (resources.length == 0) {
        log.info("No legacy doc JSON files found on classpath — documentation ingestion skipped");
        return;
      }

      for (Resource resource : resources) {
        try (InputStream is = resource.getInputStream()) {
          DocFile docFile = objectMapper.readValue(is, DocFile.class);
          if (docFile.sections != null) {
            for (RawSection raw : docFile.sections) {
              Set<String> keywords = extractKeywords(raw.body);
              if (keywords.size() >= 3) {
                allSections.add(new DocSection(
                    raw.heading,
                    docFile.sourceDocument,
                    raw.body,
                    keywords));
              }
            }
          }
          log.info("Loaded {} sections from {}", docFile.sectionCount, docFile.sourceDocument);
        } catch (IOException e) {
          log.warn("Failed to load doc file {}: {}", resource.getFilename(), e.getMessage());
        }
      }

      log.info("Documentation ingestion: loaded {} usable sections from {} files",
          allSections.size(), resources.length);
    } catch (IOException e) {
      log.warn("Failed to scan for legacy doc files: {}", e.getMessage());
    }
  }

  /**
   * Enriches all NLS-sourced BusinessTerm nodes with documentation context.
   * Matches doc sections to terms by keyword overlap, stores the best snippet.
   *
   * @return number of terms enriched
   */
  public int enrichBusinessTermsWithDocs() {
    if (allSections.isEmpty()) {
      log.info("No documentation sections loaded — skipping term enrichment");
      return 0;
    }

    // Fetch all NLS terms with their German definitions
    String fetchCypher = """
        MATCH (t:BusinessTerm)
        WHERE t.sourceType STARTS WITH 'NLS_'
          AND (t.documentContext IS NULL OR t.documentContext = '')
        RETURN t.termId AS termId, t.displayName AS displayName,
               t.definition AS definition, t.domainArea AS domainArea
        """;

    var terms = neo4jClient.query(fetchCypher).fetch().all();
    log.info("Found {} NLS terms without doc context to enrich", terms.size());

    int enriched = 0;
    List<Map<String, Object>> updates = new ArrayList<>();

    for (Map<String, Object> term : terms) {
      String termId = (String) term.get("termId");
      String displayName = (String) term.get("displayName");
      String definition = (String) term.get("definition");

      // Build search keywords from the term's display name and German definition
      Set<String> termKeywords = extractKeywords(
          (displayName != null ? displayName : "") + " " +
          (definition != null ? definition : ""));

      if (termKeywords.size() < 2) continue;

      // Find best matching doc section
      DocSection bestMatch = null;
      int bestScore = MIN_MATCH_SCORE - 1;

      for (DocSection section : allSections) {
        int score = countOverlap(termKeywords, section.keywords);
        if (score > bestScore) {
          bestScore = score;
          bestMatch = section;
        }
      }

      if (bestMatch != null) {
        String snippet = buildSnippet(bestMatch);
        updates.add(Map.of(
            "termId", termId,
            "documentContext", snippet,
            "documentSource", bestMatch.sourceDocument + " § " + bestMatch.heading));
        enriched++;
      }
    }

    // Batch update terms with doc context
    if (!updates.isEmpty()) {
      String updateCypher = """
          UNWIND $rows AS row
          MATCH (t:BusinessTerm {termId: row.termId})
          SET t.documentContext = row.documentContext,
              t.documentSource = row.documentSource
          """;

      int batchSize = 200;
      for (int i = 0; i < updates.size(); i += batchSize) {
        List<Map<String, Object>> batch = updates.subList(
            i, Math.min(i + batchSize, updates.size()));
        neo4jClient.query(updateCypher)
            .bind(batch).to("rows")
            .run();
      }
    }

    log.info("Enriched {} NLS terms with documentation context", enriched);
    return enriched;
  }

  /**
   * Generates a business description for each JavaClass based on its linked
   * NLS BusinessTerms and documentation context. The description is a compact
   * English summary suitable for the migration agent.
   *
   * @return number of classes that received a business description
   */
  public int generateClassBusinessDescriptions() {
    // Find classes with NLS terms but no business description yet
    String cypher = """
        MATCH (c:JavaClass)-[:USES_TERM]->(t:BusinessTerm)
        WHERE t.sourceType STARTS WITH 'NLS_'
          AND (c.businessDescription IS NULL OR c.businessDescription = '')
        WITH c, collect(DISTINCT {
          displayName: t.displayName,
          uiRole: t.uiRole,
          domainArea: t.domainArea,
          definition: t.definition,
          documentContext: t.documentContext
        }) AS terms
        RETURN c.fullyQualifiedName AS fqn, c.simpleName AS simpleName,
               c.packageName AS packageName, terms
        """;

    var classes = neo4jClient.query(cypher).fetch().all();
    log.info("Found {} classes with NLS terms needing business descriptions", classes.size());

    List<Map<String, Object>> updates = new ArrayList<>();

    for (Map<String, Object> cls : classes) {
      String fqn = (String) cls.get("fqn");
      String simpleName = (String) cls.get("simpleName");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> terms = (List<Map<String, Object>>) cls.get("terms");

      String description = assembleBusinessDescription(simpleName, terms);
      if (description != null && !description.isBlank()) {
        updates.add(Map.of("fqn", fqn, "businessDescription", description));
      }
    }

    // Batch update
    if (!updates.isEmpty()) {
      String updateCypher = """
          UNWIND $rows AS row
          MATCH (c:JavaClass {fullyQualifiedName: row.fqn})
          SET c.businessDescription = row.businessDescription
          """;

      int batchSize = 200;
      for (int i = 0; i < updates.size(); i += batchSize) {
        List<Map<String, Object>> batch = updates.subList(
            i, Math.min(i + batchSize, updates.size()));
        neo4jClient.query(updateCypher)
            .bind(batch).to("rows")
            .run();
      }
    }

    log.info("Generated business descriptions for {} classes", updates.size());
    return updates.size();
  }

  // ---------------------------------------------------------------------------
  // Description assembly
  // ---------------------------------------------------------------------------

  /**
   * Assembles a concise English business description from a class's linked NLS terms.
   * Format: "ClassName — manages [domain area]. UI elements: [roles]. Key terms: [terms]."
   */
  String assembleBusinessDescription(String simpleName, List<Map<String, Object>> terms) {
    if (terms == null || terms.isEmpty()) return null;

    // Determine primary domain area (most common among terms)
    Map<String, Long> areaCounts = terms.stream()
        .map(t -> (String) t.get("domainArea"))
        .filter(a -> a != null && !a.isBlank())
        .collect(Collectors.groupingBy(a -> a, Collectors.counting()));
    String primaryArea = areaCounts.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse(null);

    // Collect unique UI roles
    Set<String> uiRoles = terms.stream()
        .map(t -> (String) t.get("uiRole"))
        .filter(r -> r != null && !"GENERAL".equals(r) && !"LOG_MESSAGE".equals(r))
        .collect(Collectors.toSet());

    // Collect top display names (up to 5, prefer non-null)
    List<String> topTermNames = terms.stream()
        .map(t -> (String) t.get("displayName"))
        .filter(d -> d != null && d.length() > 2)
        .distinct()
        .limit(5)
        .toList();

    // Check for doc context
    String docSnippet = terms.stream()
        .map(t -> (String) t.get("documentContext"))
        .filter(d -> d != null && !d.isBlank())
        .findFirst()
        .orElse(null);

    StringBuilder desc = new StringBuilder();
    desc.append(simpleName);

    if (primaryArea != null) {
      desc.append(" — ").append(humanizeArea(primaryArea)).append(".");
    }

    if (!uiRoles.isEmpty()) {
      desc.append(" UI: ").append(String.join(", ",
          uiRoles.stream().sorted().limit(4).toList())).append(".");
    }

    if (!topTermNames.isEmpty()) {
      desc.append(" Terms: ").append(String.join(", ", topTermNames)).append(".");
    }

    if (docSnippet != null && desc.length() + docSnippet.length() < MAX_BUSINESS_DESC_LENGTH) {
      desc.append(" Context: ").append(docSnippet);
    }

    String result = desc.toString();
    if (result.length() > MAX_BUSINESS_DESC_LENGTH) {
      result = result.substring(0, MAX_BUSINESS_DESC_LENGTH - 3) + "...";
    }
    return result;
  }

  private String humanizeArea(String area) {
    return switch (area) {
      case "ORDER_MANAGEMENT" -> "Ad order management, scheduling, pricing";
      case "CONTRACT_MANAGEMENT" -> "Contracts, commissions, settlements";
      case "ADMINISTRATION" -> "System administration, user management";
      case "COMMON" -> "Shared UI vocabulary";
      case "UI_FRAMEWORK" -> "UI framework components";
      case "PRODUCTION" -> "Publishing production, editions, print";
      case "REPORTING" -> "Business reports and statistics";
      case "SALES" -> "Sales representatives, commissions";
      case "MIGRATION" -> "Migration-specific operations";
      case "DATA_IMPORT" -> "Data import operations";
      default -> area.toLowerCase().replace("_", " ");
    };
  }

  // ---------------------------------------------------------------------------
  // Keyword extraction and matching
  // ---------------------------------------------------------------------------

  Set<String> extractKeywords(String text) {
    if (text == null || text.isBlank()) return Set.of();
    String[] words = text.toLowerCase()
        .replaceAll("[^a-zäöüß\\s]", " ")
        .split("\\s+");
    Set<String> result = new java.util.HashSet<>();
    for (String w : words) {
      if (w.length() > 3 && !STOP_WORDS.contains(w)) {
        result.add(w);
      }
    }
    return result;
  }

  int countOverlap(Set<String> a, Set<String> b) {
    int count = 0;
    for (String word : a) {
      if (b.contains(word)) count++;
    }
    return count;
  }

  String buildSnippet(DocSection section) {
    String body = section.body;
    if (body.length() > MAX_DOC_CONTEXT_LENGTH) {
      // Take the first N chars, break at word boundary
      int end = MAX_DOC_CONTEXT_LENGTH;
      int lastSpace = body.lastIndexOf(' ', end);
      if (lastSpace > MAX_DOC_CONTEXT_LENGTH / 2) end = lastSpace;
      body = body.substring(0, end) + "...";
    }
    return body;
  }

  // ---------------------------------------------------------------------------
  // JSON model
  // ---------------------------------------------------------------------------

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class DocFile {
    public String sourceDocument;
    public int sectionCount;
    public List<RawSection> sections;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class RawSection {
    public String heading;
    public String body;
    public int charCount;
  }

  record DocSection(String heading, String sourceDocument, String body, Set<String> keywords) {}
}
