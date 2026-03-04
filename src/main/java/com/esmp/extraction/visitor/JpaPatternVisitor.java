package com.esmp.extraction.visitor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * OpenRewrite {@link JavaIsoVisitor} that detects JPA entity and query patterns and emits
 * MAPS_TO_TABLE and QUERIES edges into the {@link ExtractionAccumulator}.
 *
 * <p>Detects:
 *
 * <ul>
 *   <li><strong>MAPS_TO_TABLE</strong>: classes annotated with {@code @Entity} — derives table name
 *       from {@code @Table(name=...)} or JPA snake_case convention from the simple class name.
 *   <li><strong>QUERIES</strong>: methods annotated with {@code @Query} or following Spring Data
 *       derived query naming conventions ({@code findBy*}, {@code deleteBy*}, {@code countBy*},
 *       {@code existsBy*}).
 * </ul>
 */
public class JpaPatternVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  private static final Set<String> ENTITY_ANNOTATIONS =
      Set.of(
          "javax.persistence.Entity",
          "jakarta.persistence.Entity");

  private static final Set<String> TABLE_ANNOTATIONS =
      Set.of(
          "javax.persistence.Table",
          "jakarta.persistence.Table");

  private static final Set<String> QUERY_ANNOTATIONS =
      Set.of(
          "org.springframework.data.jpa.repository.Query");

  /** Spring Data derived query method prefixes. */
  private static final List<String> DERIVED_QUERY_PREFIXES =
      List.of("findBy", "findAllBy", "deleteBy", "removeBy", "countBy", "existsBy", "getBy",
          "readBy", "queryBy", "streamBy");

  @Override
  public J.ClassDeclaration visitClassDeclaration(
      J.ClassDeclaration cd, ExtractionAccumulator acc) {
    JavaType.FullyQualified type = cd.getType();
    if (type != null) {
      String fqn = type.getFullyQualifiedName();
      List<J.Annotation> annotations = cd.getLeadingAnnotations();

      // Check for @Entity annotation
      boolean hasEntity = annotations.stream()
          .anyMatch(a -> ENTITY_ANNOTATIONS.contains(resolveAnnotationFqn(a)));

      if (hasEntity) {
        // Find @Table annotation to get explicit table name
        String tableName = null;
        for (J.Annotation annotation : annotations) {
          String annotFqn = resolveAnnotationFqn(annotation);
          if (TABLE_ANNOTATIONS.contains(annotFqn)) {
            tableName = extractAnnotationStringAttribute(annotation, "name");
            break;
          }
        }

        // Fall back to JPA snake_case convention
        if (tableName == null || tableName.isBlank()) {
          tableName = toSnakeCase(cd.getSimpleName());
        }

        acc.addTableMapping(fqn, tableName.toLowerCase());

        // Also register annotation nodes for any annotations found on the class
        for (J.Annotation annotation : annotations) {
          String annotFqn = resolveAnnotationFqn(annotation);
          if (annotFqn != null && !annotFqn.startsWith("<")) {
            String simpleName = annotation.getSimpleName();
            String packageName = extractPackageName(annotFqn);
            acc.addAnnotation(annotFqn, simpleName, packageName);
          }
        }
      }
    }

    return super.visitClassDeclaration(cd, acc);
  }

  @Override
  public J.MethodDeclaration visitMethodDeclaration(
      J.MethodDeclaration md, ExtractionAccumulator acc) {
    JavaType.Method methodType = md.getMethodType();
    if (methodType != null) {
      String declaringClass = methodType.getDeclaringType().getFullyQualifiedName();
      String simpleName = md.getSimpleName();

      // Detect @Query annotation
      boolean hasQuery = md.getLeadingAnnotations().stream()
          .anyMatch(a -> QUERY_ANNOTATIONS.contains(resolveAnnotationFqn(a)));

      // Detect Spring Data derived query method names
      boolean isDerivedQuery = isDerivedQueryMethod(simpleName);

      if (hasQuery || isDerivedQuery) {
        // Build method ID with parameter types
        List<String> paramTypes = methodType.getParameterTypes().stream()
            .map(this::typeToString)
            .collect(Collectors.toList());
        String methodId = declaringClass + "#" + simpleName + "(" + String.join(",", paramTypes) + ")";
        acc.addQueryMethod(methodId, declaringClass);
      }
    }

    return super.visitMethodDeclaration(md, acc);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private boolean isDerivedQueryMethod(String methodName) {
    for (String prefix : DERIVED_QUERY_PREFIXES) {
      if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
        return true;
      }
    }
    return false;
  }

  private String resolveAnnotationFqn(J.Annotation annotation) {
    if (annotation.getAnnotationType() != null
        && annotation.getAnnotationType().getType() instanceof JavaType.FullyQualified fq) {
      String fqn = fq.getFullyQualifiedName();
      if (fqn != null && !fqn.startsWith("<")) {
        return fqn;
      }
    }
    // When type resolution fails, fall back to simple name matching
    // This allows detection when classpath doesn't include JPA jars explicitly
    String simpleName = annotation.getSimpleName();
    // Map known JPA simple names to their FQN for matching purposes
    return switch (simpleName) {
      case "Entity" -> "javax.persistence.Entity";
      case "Table" -> "javax.persistence.Table";
      case "Query" -> "org.springframework.data.jpa.repository.Query";
      default -> simpleName;
    };
  }

  /**
   * Extracts the value of a string attribute from an annotation (e.g., {@code name} in
   * {@code @Table(name="orders")}).
   */
  private String extractAnnotationStringAttribute(J.Annotation annotation, String attributeName) {
    if (annotation.getArguments() == null) {
      return null;
    }
    for (org.openrewrite.java.tree.Expression arg : annotation.getArguments()) {
      if (arg instanceof J.Assignment assignment) {
        // Named attribute: name = "value"
        if (assignment.getVariable() instanceof J.Identifier id
            && attributeName.equals(id.getSimpleName())) {
          org.openrewrite.java.tree.Expression value = assignment.getAssignment();
          return extractStringLiteral(value);
        }
      } else if (arg instanceof J.Literal literal) {
        // Single unnamed argument (value shorthand)
        return extractStringLiteral(literal);
      }
    }
    return null;
  }

  private String extractStringLiteral(org.openrewrite.java.tree.Expression expr) {
    if (expr instanceof J.Literal literal) {
      if (literal.getValue() instanceof String s) {
        return s;
      }
    }
    return null;
  }

  /**
   * Converts a CamelCase class name to JPA's default snake_case table name convention.
   *
   * <p>Examples:
   * <ul>
   *   <li>{@code CustomerOrder} → {@code customer_order}
   *   <li>{@code ProductCatalog} → {@code product_catalog}
   *   <li>{@code Order} → {@code order}
   *   <li>{@code XMLParser} → {@code x_m_l_parser} (coarse conversion matching JPA default)
   * </ul>
   */
  static String toSnakeCase(String camelCase) {
    if (camelCase == null || camelCase.isBlank()) {
      return camelCase;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < camelCase.length(); i++) {
      char c = camelCase.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        sb.append('_');
      }
      sb.append(Character.toLowerCase(c));
    }
    return sb.toString();
  }

  private String typeToString(JavaType type) {
    if (type instanceof JavaType.FullyQualified fq) {
      return fq.getFullyQualifiedName();
    }
    if (type instanceof JavaType.Primitive p) {
      return p.getKeyword();
    }
    if (type instanceof JavaType.Array arr) {
      return typeToString(arr.getElemType()) + "[]";
    }
    return type.toString();
  }

  private String extractPackageName(String fqn) {
    int lastDot = fqn.lastIndexOf('.');
    return lastDot > 0 ? fqn.substring(0, lastDot) : "";
  }
}
