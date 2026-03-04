package com.esmp.extraction.visitor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * OpenRewrite {@link JavaIsoVisitor} that detects Spring/CDI dependency injection and emits
 * DEPENDS_ON edges into the {@link ExtractionAccumulator}.
 *
 * <p>Detects:
 *
 * <ul>
 *   <li><strong>Field injection</strong>: class-level fields annotated with {@code @Autowired} or
 *       {@code @Inject} — captures the declaring class as the dependent and the field type as the
 *       dependency.
 *   <li><strong>Constructor injection</strong>: constructors annotated with {@code @Autowired} —
 *       captures one DEPENDS_ON edge per typed constructor parameter.
 * </ul>
 *
 * <p>JDK types (java.lang.*, java.util.*, java.io.*, java.nio.*, java.math.*) and Spring framework
 * types (org.springframework.*) are excluded to reduce noise. Only application-layer dependencies
 * produce edges.
 */
public class DependencyVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  private static final Set<String> INJECTION_ANNOTATIONS =
      Set.of(
          "org.springframework.beans.factory.annotation.Autowired",
          "javax.inject.Inject",
          "jakarta.inject.Inject");

  // Prefixes for types that should NOT generate dependency edges
  private static final List<String> EXCLUDED_PREFIXES =
      List.of(
          "java.lang.",
          "java.util.",
          "java.io.",
          "java.nio.",
          "java.math.",
          "java.time.",
          "java.net.",
          "java.sql.",
          "javax.",
          "jakarta.",
          "org.springframework.",
          "org.slf4j.",
          "org.apache.",
          "org.junit.",
          "com.google.",
          "io.micrometer.");

  @Override
  public J.VariableDeclarations visitVariableDeclarations(
      J.VariableDeclarations vd, ExtractionAccumulator acc) {
    // Only process class-level fields — skip local variables inside methods
    boolean insideMethod = getCursor().firstEnclosing(J.MethodDeclaration.class) != null;
    if (!insideMethod && hasInjectionAnnotation(vd.getLeadingAnnotations())) {
      J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
      if (enclosingClass != null && enclosingClass.getType() != null) {
        String fromFqn = enclosingClass.getType().getFullyQualifiedName();

        // Resolve target type FQN from the type expression
        String toFqn = resolveTypeFqn(vd.getTypeExpression());
        if (toFqn != null && !isExcludedType(toFqn)) {
          for (J.VariableDeclarations.NamedVariable named : vd.getVariables()) {
            acc.addDependencyEdge(fromFqn, toFqn, "field", named.getSimpleName());
          }
        }
      }
    }

    return super.visitVariableDeclarations(vd, acc);
  }

  @Override
  public J.MethodDeclaration visitMethodDeclaration(
      J.MethodDeclaration md, ExtractionAccumulator acc) {
    // Detect @Autowired constructors
    if (md.isConstructor() && hasInjectionAnnotation(md.getLeadingAnnotations())) {
      JavaType.Method methodType = md.getMethodType();
      if (methodType != null && methodType.getDeclaringType() != null) {
        String fromFqn = methodType.getDeclaringType().getFullyQualifiedName();

        List<String> paramTypes =
            methodType.getParameterTypes().stream()
                .map(this::typeToFqn)
                .collect(Collectors.toList());
        List<J.VariableDeclarations> params = md.getParameters().stream()
            .filter(p -> p instanceof J.VariableDeclarations)
            .map(p -> (J.VariableDeclarations) p)
            .collect(Collectors.toList());

        for (int i = 0; i < paramTypes.size(); i++) {
          String toFqn = paramTypes.get(i);
          if (toFqn != null && !isExcludedType(toFqn)) {
            // Use the parameter name from the AST if available
            String paramName = i < params.size() && !params.get(i).getVariables().isEmpty()
                ? params.get(i).getVariables().get(0).getSimpleName()
                : "param" + i;
            acc.addDependencyEdge(fromFqn, toFqn, "constructor", paramName);
          }
        }
      }
    }

    return super.visitMethodDeclaration(md, acc);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private boolean hasInjectionAnnotation(List<J.Annotation> annotations) {
    for (J.Annotation annotation : annotations) {
      String fqn = resolveAnnotationFqn(annotation);
      if (fqn != null && INJECTION_ANNOTATIONS.contains(fqn)) {
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
    // Fallback: try to match simple name for unresolved types
    String simpleName = annotation.getSimpleName();
    for (String injectionFqn : INJECTION_ANNOTATIONS) {
      if (injectionFqn.endsWith("." + simpleName)) {
        return injectionFqn;
      }
    }
    return null;
  }

  private String resolveTypeFqn(org.openrewrite.java.tree.TypeTree typeExpr) {
    if (typeExpr == null) {
      return null;
    }
    JavaType type = typeExpr.getType();
    return typeToFqn(type);
  }

  private String typeToFqn(JavaType type) {
    if (type instanceof JavaType.FullyQualified fq) {
      return fq.getFullyQualifiedName();
    }
    if (type instanceof JavaType.Array arr) {
      return typeToFqn(arr.getElemType());
    }
    if (type instanceof JavaType.Parameterized param) {
      return param.getType().getFullyQualifiedName();
    }
    return null;
  }

  private boolean isExcludedType(String fqn) {
    for (String prefix : EXCLUDED_PREFIXES) {
      if (fqn.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
