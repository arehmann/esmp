package com.esmp.extraction.application;

import com.esmp.extraction.model.BusinessTermNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Extracts domain-specific abbreviations from class names and stores them as BusinessTerm nodes
 * with {@code sourceType=ABBREVIATION}.
 *
 * <p>Two strategies:
 * <ol>
 *   <li><b>Known glossary</b>: curated map of abbreviations with expansions derived from code
 *       analysis of the AdSuite codebase (BO, SC, AEP, DS, BP, etc.)
 *   <li><b>Auto-detection</b>: scans class simple names for 2-4 letter all-uppercase segments
 *       that aren't in the known glossary or common Java/tech acronyms. These are flagged with
 *       a "?" expansion for later curation.
 * </ol>
 *
 * <p>Abbreviation terms are stored with {@code curated=false} initially. The known glossary terms
 * get meaningful expansions; auto-detected ones get placeholder definitions until documentation
 * ingestion (Phase C) fills them in.
 */
@Component
public class AbbreviationExtractor {

  private static final Logger log = LoggerFactory.getLogger(AbbreviationExtractor.class);

  /** Regex matching 2-4 letter all-uppercase segments within PascalCase class names. */
  private static final Pattern ABBREV_PATTERN = Pattern.compile("(?<=[a-z])([A-Z]{2,4})(?=[A-Z][a-z]|$)|^([A-Z]{2,4})(?=[A-Z][a-z])");

  /** Common Java/tech acronyms that are NOT domain abbreviations. */
  private static final Set<String> TECH_ACRONYMS = Set.of(
      "BO", "PO", "DTO", "DAO", "API", "URL", "URI", "XML", "JSON", "HTML", "CSS",
      "HTTP", "HTTPS", "SQL", "JPA", "JMS", "JMX", "JDBC", "JNDI", "EJB", "CDI",
      "REST", "SOAP", "WSDL", "XSLT", "DOM", "SAX", "IO", "NIO", "TCP", "UDP",
      "SSL", "TLS", "SSH", "FTP", "SMTP", "LDAP", "SSO", "JWT", "SAML", "CORS",
      "CSV", "PDF", "PNG", "GIF", "SVG", "UUID", "ORM", "MVC", "MVP", "MVVM",
      "GUI", "CLI", "IDE", "SDK", "SPI", "RPC", "GRPC", "AMQP", "OIDC"
  );

  /**
   * Curated glossary of domain-specific abbreviations found in the AdSuite codebase.
   * Key = abbreviation (uppercase), Value = [expansion, evidence/context].
   */
  private static final Map<String, String[]> KNOWN_GLOSSARY = new LinkedHashMap<>();
  static {
    KNOWN_GLOSSARY.put("SC", new String[]{
        "Schedule Composition",
        "A single order line item within an edition — ScheduleCompositionBO, getSchedComps()"});
    KNOWN_GLOSSARY.put("AEP", new String[]{
        "Ad Edition Part",
        "Part of an advertisement assigned to a specific edition — AdEditionPartBO, getAEPCriteria()"});
    KNOWN_GLOSSARY.put("DS", new String[]{
        "Distribution Schedule",
        "Plan for how supplements are distributed across editions — DistributionScheduleBO, lblAdoptDS"});
    KNOWN_GLOSSARY.put("BP", new String[]{
        "Business Partner",
        "Customer, agency, or advertiser entity — BusinessPartnerBO, BPListEntry"});
    KNOWN_GLOSSARY.put("FS", new String[]{
        "Foreign Supplement",
        "Physical paper insert distributed with newspaper — ForeignSupplementPanel, ForeignSupplementTypeBO"});
    KNOWN_GLOSSARY.put("CO", new String[]{
        "Commercial Object",
        "Top-level advertising order entity — CommercialObjectBO"});
    KNOWN_GLOSSARY.put("PD", new String[]{
        "Production Detail",
        "Print production metadata — ProdDetailBO, ProdDetailPanel"});
    KNOWN_GLOSSARY.put("CRM", new String[]{
        "Customer Relationship Management",
        "Customer management subsystem — crm package"});
    KNOWN_GLOSSARY.put("NLS", new String[]{
        "National Language Support",
        "Multilingual string resource system — getNLS() calls, NLS XML files"});
    KNOWN_GLOSSARY.put("VAT", new String[]{
        "Value Added Tax",
        "Tax calculation subsystem — VATRegionBO, getVAT()"});
    KNOWN_GLOSSARY.put("HST", new String[]{
        "Harmonized Sales Tax",
        "Canadian tax variant — adaptForTargetNetPriceHST() in pricing"});
    KNOWN_GLOSSARY.put("AO", new String[]{
        "Ad Order",
        "Advertisement order entity — AdOrderBO, related to order management"});
    KNOWN_GLOSSARY.put("SR", new String[]{
        "Sales Representative",
        "Sales commission entity — SalesRepresentative package"});
  }

  /**
   * Extracts abbreviation terms from the given set of class simple names.
   *
   * <p>First emits all known glossary entries, then scans class names for unknown uppercase
   * segments (auto-detected abbreviations).
   *
   * @param classSimpleNames set of Java class simple names from the extraction accumulator
   * @return list of BusinessTermNode with sourceType=ABBREVIATION
   */
  public List<BusinessTermNode> extract(Set<String> classSimpleNames) {
    Map<String, BusinessTermNode> terms = new LinkedHashMap<>();

    // Step 1: emit known glossary entries
    for (Map.Entry<String, String[]> entry : KNOWN_GLOSSARY.entrySet()) {
      String abbrev = entry.getKey();
      String expansion = entry.getValue()[0];
      String evidence = entry.getValue()[1];

      BusinessTermNode node = new BusinessTermNode(abbrev);
      node.setDisplayName(abbrev + " — " + expansion);
      node.setDefinition(evidence);
      node.setSourceType("ABBREVIATION");
      node.setCriticality("Low");
      node.setMigrationSensitivity("None");
      node.setStatus("auto");
      node.setCurated(false);
      node.setUsageCount(0);
      node.setDomainArea("GLOSSARY");
      terms.put(abbrev, node);
    }

    // Step 2: auto-detect unknown abbreviations from class names
    for (String className : classSimpleNames) {
      Matcher matcher = ABBREV_PATTERN.matcher(className);
      while (matcher.find()) {
        String abbrev = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (abbrev == null) continue;
        if (terms.containsKey(abbrev)) continue;
        if (TECH_ACRONYMS.contains(abbrev)) continue;

        BusinessTermNode node = new BusinessTermNode(abbrev);
        node.setDisplayName(abbrev + " — ?");
        node.setDefinition("Auto-detected abbreviation from class: " + className + " — needs expansion");
        node.setSourceType("ABBREVIATION");
        node.setCriticality("Low");
        node.setMigrationSensitivity("None");
        node.setStatus("auto");
        node.setCurated(false);
        node.setUsageCount(0);
        node.setDomainArea("GLOSSARY");
        terms.put(abbrev, node);
      }
    }

    log.info("Extracted {} abbreviations ({} known, {} auto-detected)",
        terms.size(), KNOWN_GLOSSARY.size(), terms.size() - KNOWN_GLOSSARY.size());

    return new ArrayList<>(terms.values());
  }
}
