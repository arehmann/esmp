package com.esmp.extraction.visitor;

import com.esmp.extraction.parser.NlsXmlParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Javadoc;
import org.openrewrite.java.tree.Statement;

/**
 * OpenRewrite {@link JavaIsoVisitor} that extracts domain business terms from Java ASTs into an
 * {@link ExtractionAccumulator}.
 *
 * <p>Term sources:
 * <ul>
 *   <li><b>CLASS_NAME</b>: camelCase/PascalCase class simple names are split and technical
 *       suffixes (Service, Repository, Controller, etc.) are stripped.
 *   <li><b>ENUM_CONSTANT</b>: enum constant names are split on underscores, generic constants
 *       (ACTIVE, TRUE, etc.) are filtered by stop-words.
 *   <li><b>NLS</b>: {@code getNLS("key")} method invocations are detected and resolved against
 *       pre-loaded NLS XML files. Each NLS key becomes a business term with the English value as
 *       display name and the German value as definition (primary business language).
 *   <li>Class-level Javadoc is extracted and used to seed the {@code definition} field of
 *       extracted terms from that class.
 * </ul>
 *
 * <p>DB table name terms are handled in {@link com.esmp.extraction.application.AccumulatorToModelMapper}
 * after the visitor pass, using table mappings populated by JpaPatternVisitor.
 */
public class LexiconVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  private final Map<String, NlsXmlParser.NlsEntry> nlsMap;

  /** Creates a visitor without NLS support (backward-compatible). */
  public LexiconVisitor() {
    this(Collections.emptyMap());
  }

  /**
   * Creates a visitor with NLS lookup support.
   *
   * @param nlsMap pre-loaded NLS entries keyed by resource key
   */
  public LexiconVisitor(Map<String, NlsXmlParser.NlsEntry> nlsMap) {
    this.nlsMap = nlsMap;
  }

  // Technical class name suffixes that are structural, not domain terms
  private static final Set<String> STOP_SUFFIXES =
      Set.of(
          "service", "repository", "controller", "impl", "abstract", "base",
          "dto", "entity", "helper", "util", "manager", "handler", "factory",
          "builder", "adapter", "facade", "wrapper", "processor", "validator",
          "converter", "mapper", "provider", "exception", "config", "configuration",
          "test", "tests", "spec", "enum");

  // Generic enum constant words that are not domain terms
  private static final Set<String> ENUM_STOP_WORDS =
      Set.of(
          "active", "inactive", "enabled", "disabled", "true", "false",
          "null", "default", "unknown", "none", "yes", "no");

  /**
   * Regex to split PascalCase/camelCase names into words.
   * Handles transitions like: "InvoicePaymentService" -> ["Invoice", "Payment", "Service"]
   * Also handles: "XMLParser" -> ["XML", "Parser"]
   */
  private static final Pattern CAMEL_SPLIT =
      Pattern.compile("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");

  @Override
  public J.ClassDeclaration visitClassDeclaration(
      J.ClassDeclaration cd, ExtractionAccumulator acc) {
    // Resolve FQN: prefer type-resolved FQN, fall back to simple name for unresolved types
    String fqn;
    boolean isEnum;
    if (cd.getType() != null) {
      fqn = cd.getType().getFullyQualifiedName();
      isEnum = cd.getType().getKind() == org.openrewrite.java.tree.JavaType.FullyQualified.Kind.Enum;
    } else {
      // Type unresolved (no classpath) — use simple name as FQN fallback
      fqn = cd.getSimpleName();
      // Detect enum by kind flag on the class declaration itself
      isEnum = cd.getKind() == J.ClassDeclaration.Kind.Type.Enum;
    }

    if (fqn != null) {
      String simpleName = cd.getSimpleName();

      // Extract Javadoc from class-level comments
      String javadoc = extractJavadoc(cd);

      // Split class name and extract terms
      String sourceType = isEnum ? "ENUM_NAME" : "CLASS_NAME";
      for (String term : splitAndFilter(simpleName)) {
        acc.addBusinessTerm(term, fqn, sourceType, javadoc);
      }

      // For enums, also extract constants
      if (isEnum) {
        for (Statement stmt : cd.getBody().getStatements()) {
          if (stmt instanceof J.EnumValueSet evs) {
            for (J.EnumValue ev : evs.getEnums()) {
              String constantName = ev.getName().getSimpleName();
              for (String term : splitEnumConstant(constantName)) {
                acc.addBusinessTerm(term, fqn, "ENUM_CONSTANT", null);
              }
            }
          }
        }
      }
    }

    // MUST recurse to process nested/inner classes
    return super.visitClassDeclaration(cd, acc);
  }

  /**
   * Detects {@code getNLS("key")} method invocations and resolves them against the NLS map.
   * Each resolved key produces a business term with the English value as display name and the
   * German value as definition.
   */
  @Override
  public J.MethodInvocation visitMethodInvocation(
      J.MethodInvocation method, ExtractionAccumulator acc) {
    if (!nlsMap.isEmpty()) {
      String methodName = method.getSimpleName();
      if ("getNLS".equals(methodName) || "nls".equals(methodName)) {
        List<Expression> args = method.getArguments();
        if (!args.isEmpty() && args.get(0) instanceof J.Literal literal) {
          Object value = literal.getValue();
          if (value instanceof String nlsKey) {
            NlsXmlParser.NlsEntry entry = nlsMap.get(nlsKey);
            if (entry != null) {
              // Resolve current class FQN from cursor
              String classFqn = resolveEnclosingClassFqn();
              if (classFqn != null) {
                acc.addBusinessTerm(
                    entry.key(),          // termId = NLS key
                    entry.englishValue(), // displayName = English value
                    classFqn,
                    entry.category(),     // sourceType = NLS_LABEL, NLS_MESSAGE, etc.
                    entry.germanValue(),  // definition = German value (business definition)
                    entry.uiRole(),       // uiRole = LABEL, MESSAGE, TOOLTIP, etc.
                    entry.domainArea(),   // domainArea = ORDER_MANAGEMENT, COMMON, etc.
                    entry.sourceFile()    // nlsFileName = Order.xml, Contract.xml, etc.
                );
              }
            }
          }
        }
      }
    }
    return super.visitMethodInvocation(method, acc);
  }

  /**
   * Walks the cursor stack to find the enclosing class declaration and returns its FQN.
   */
  private String resolveEnclosingClassFqn() {
    var cursor = getCursor();
    while (cursor != null) {
      Object val = cursor.getValue();
      if (val instanceof J.ClassDeclaration cd && cd.getType() != null) {
        return cd.getType().getFullyQualifiedName();
      }
      cursor = cursor.getParent();
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Attempts to extract class-level Javadoc text from the class declaration prefix comments.
   *
   * <p>OpenRewrite stores Javadoc as {@link Javadoc.DocComment} in the prefix comment list of
   * the class declaration. If the class has no Javadoc, returns null.
   *
   * @param cd the class declaration node
   * @return Javadoc text (stripped of asterisks), or null if not found
   */
  String extractJavadoc(J.ClassDeclaration cd) {
    // Try class declaration prefix comments first
    for (org.openrewrite.java.tree.Comment comment : cd.getPrefix().getComments()) {
      if (comment instanceof Javadoc.DocComment docComment) {
        return extractDocCommentText(docComment);
      }
    }
    // Fallback: try the class name prefix (research pitfall 3 from plan)
    for (org.openrewrite.java.tree.Comment comment : cd.getName().getPrefix().getComments()) {
      if (comment instanceof Javadoc.DocComment docComment) {
        return extractDocCommentText(docComment);
      }
    }
    return null;
  }

  private String extractDocCommentText(Javadoc.DocComment docComment) {
    StringBuilder sb = new StringBuilder();
    for (Javadoc element : docComment.getBody()) {
      if (element instanceof Javadoc.Text text) {
        sb.append(text.getText()).append(" ");
      } else if (element instanceof Javadoc.LineBreak) {
        sb.append(" ");
      }
    }
    String result = sb.toString().replaceAll("\\s+", " ").trim();
    return result.isEmpty() ? null : result;
  }

  /**
   * Splits a PascalCase/camelCase class name into terms, filtering stop suffixes and short
   * fragments.
   *
   * @param name the class simple name
   * @return list of unique terms (lowercased) in encounter order
   */
  List<String> splitAndFilter(String name) {
    String[] parts = CAMEL_SPLIT.split(name);
    Set<String> seen = new LinkedHashSet<>();
    List<String> result = new ArrayList<>();

    for (String part : parts) {
      String lower = part.toLowerCase();
      if (lower.length() <= 2) continue;
      if (STOP_SUFFIXES.contains(lower)) continue;
      if (seen.add(lower)) {
        result.add(lower);
      }
    }
    return result;
  }

  /**
   * Splits an UPPER_SNAKE_CASE enum constant name into terms, filtering stop words and short
   * fragments.
   *
   * @param constantName the enum constant name (e.g., "PENDING_APPROVAL")
   * @return list of unique terms (lowercased) in encounter order
   */
  List<String> splitEnumConstant(String constantName) {
    String[] parts = constantName.split("_");
    List<String> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();

    for (String part : parts) {
      String lower = part.toLowerCase();
      if (lower.length() <= 2) continue;
      if (ENUM_STOP_WORDS.contains(lower)) continue;
      if (seen.add(lower)) {
        result.add(lower);
      }
    }
    return result;
  }
}
