package com.esmp.extraction.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses AdSuite NLS (National Language Support) XML files into a lookup map.
 *
 * <p>NLS files contain multilingual translations keyed by resource ID. Each resource has mappings
 * for multiple languages (Deutsch, English, French, etc.). This parser extracts the key, English
 * value, and German value (as the primary business definition) for each resource.
 *
 * <p>The key prefix determines the term category:
 * <ul>
 *   <li>{@code lbl*} — UI labels: field names, column headers (core domain vocabulary)
 *   <li>{@code msg*} — validation/business rule messages (business logic in natural language)
 *   <li>{@code TypeText_*}, {@code TypeCode_*} — enum/status values (domain model terms)
 *   <li>{@code Function_*} — business operations
 *   <li>{@code ToolTipText_*} — detailed field descriptions
 * </ul>
 */
public class NlsXmlParser {

  private static final Logger log = LoggerFactory.getLogger(NlsXmlParser.class);

  /**
   * Parsed NLS entry with multilingual values and derived category.
   *
   * @param key        NLS resource key (e.g., "lblBranchOffice")
   * @param englishValue English translation (display name for the AI agent)
   * @param germanValue  German translation (primary business definition)
   * @param category     derived category: NLS_LABEL, NLS_MESSAGE, NLS_TYPE, NLS_FUNCTION, NLS_TOOLTIP, NLS_OTHER
   * @param sourceFile   name of the XML file this entry came from (e.g., "Order.xml")
   */
  public record NlsEntry(
      String key,
      String englishValue,
      String germanValue,
      String category,
      String sourceFile) {}

  /**
   * Scans for NLS XML files under the given source root and parses all entries.
   *
   * <p>Looks for XML files in common NLS locations:
   * <ul>
   *   <li>{@code {sourceRoot}/adsuite-runtime/nls-files/}</li>
   *   <li>{@code {sourceRoot}/nls-files/}</li>
   *   <li>{@code {sourceRoot}/nls/}</li>
   * </ul>
   *
   * @param sourceRoot the project root directory
   * @return map of NLS key → NlsEntry; empty map if no NLS files found
   */
  public Map<String, NlsEntry> parse(Path sourceRoot) {
    Path nlsDir = resolveNlsDirectory(sourceRoot);
    if (nlsDir == null) {
      log.info("No NLS directory found under {} — NLS lexicon extraction skipped", sourceRoot);
      return Collections.emptyMap();
    }

    Map<String, NlsEntry> entries = new HashMap<>();
    try (Stream<Path> files = Files.list(nlsDir)) {
      List<Path> xmlFiles = files
          .filter(p -> p.toString().endsWith(".xml"))
          .toList();

      log.info("Found {} NLS XML files in {}", xmlFiles.size(), nlsDir);

      for (Path xmlFile : xmlFiles) {
        try {
          Map<String, NlsEntry> fileEntries = parseFile(xmlFile);
          entries.putAll(fileEntries);
        } catch (Exception e) {
          log.warn("Failed to parse NLS file {}: {}", xmlFile.getFileName(), e.getMessage());
        }
      }
    } catch (IOException e) {
      log.warn("Failed to list NLS directory {}: {}", nlsDir, e.getMessage());
      return Collections.emptyMap();
    }

    log.info("Parsed {} NLS entries total ({} label, {} message, {} type, {} other)",
        entries.size(),
        entries.values().stream().filter(e -> "NLS_LABEL".equals(e.category())).count(),
        entries.values().stream().filter(e -> "NLS_MESSAGE".equals(e.category())).count(),
        entries.values().stream().filter(e -> "NLS_TYPE".equals(e.category())).count(),
        entries.values().stream().filter(e -> !e.category().startsWith("NLS_L")
            && !e.category().startsWith("NLS_M") && !e.category().startsWith("NLS_T")).count());

    return entries;
  }

  private Path resolveNlsDirectory(Path sourceRoot) {
    Path[] candidates = {
        sourceRoot.resolve("adsuite-runtime/nls-files"),
        sourceRoot.resolve("nls-files"),
        sourceRoot.resolve("nls"),
    };
    for (Path candidate : candidates) {
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  Map<String, NlsEntry> parseFile(Path xmlFile) throws Exception {
    Map<String, NlsEntry> entries = new HashMap<>();
    String sourceFileName = xmlFile.getFileName().toString();

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // Disable DTD loading to avoid network calls and missing DTD errors
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.parse(xmlFile.toFile());

    NodeList resources = doc.getElementsByTagName("NLSResource");
    for (int i = 0; i < resources.getLength(); i++) {
      Element resource = (Element) resources.item(i);
      String key = resource.getAttribute("key");
      if (key == null || key.isBlank()) continue;

      String english = null;
      String german = null;

      NodeList mappings = resource.getElementsByTagName("NLSMapping");
      for (int j = 0; j < mappings.getLength(); j++) {
        Element mapping = (Element) mappings.item(j);
        String lang = mapping.getAttribute("language");
        String value = mapping.getAttribute("value");
        if (value == null || value.isBlank()) continue;

        if ("English".equals(lang)) english = value;
        else if ("Deutsch".equals(lang)) german = value;
      }

      // Skip entries with no English value — can't serve as display name
      if (english == null || english.isBlank()) continue;
      // Skip trivial entries (single char, just numbers, "xxx" placeholders)
      if (english.length() <= 2 || "xxx".equalsIgnoreCase(english)) continue;

      String category = categorizeKey(key);
      entries.put(key, new NlsEntry(key, english, german, category, sourceFileName));
    }

    return entries;
  }

  /**
   * Derives a business term category from the NLS key prefix.
   */
  static String categorizeKey(String key) {
    if (key.startsWith("lbl") || key.startsWith("Label")) return "NLS_LABEL";
    if (key.startsWith("msg") || key.startsWith("Msg")) return "NLS_MESSAGE";
    if (key.startsWith("TypeText_") || key.startsWith("TypeCode_")) return "NLS_TYPE";
    if (key.startsWith("Function_")) return "NLS_FUNCTION";
    if (key.startsWith("ToolTipText_") || key.startsWith("ToolTip")) return "NLS_TOOLTIP";
    return "NLS_OTHER";
  }
}
