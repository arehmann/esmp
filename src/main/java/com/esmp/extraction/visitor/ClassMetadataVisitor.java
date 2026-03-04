package com.esmp.extraction.visitor;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * OpenRewrite {@link JavaIsoVisitor} that extracts class, method, and field metadata from Java LSTs
 * into an {@link ExtractionAccumulator}.
 *
 * <p>Covers AST-02: class metadata (name, package, annotations, modifiers, super, interfaces),
 * method signatures (name, return type, parameters, annotations), and field definitions (name,
 * type, annotations). Only class-level fields are recorded — local variables are excluded.
 *
 * <p>IMPORTANT: Every {@code visit*} override MUST call the corresponding {@code super.visit*()} to
 * ensure recursion into nested classes, inner classes, and anonymous classes.
 */
public class ClassMetadataVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  private static final Set<String> SERVICE_STEREOTYPES =
      Set.of(
          "org.springframework.stereotype.Service",
          "org.springframework.stereotype.Controller",
          "org.springframework.web.bind.annotation.RestController",
          "org.springframework.stereotype.Component",
          // Simple-name fallback: when OpenRewrite cannot resolve the annotation FQN
          // (e.g., Spring is not on the parse classpath), resolveAnnotationName() returns
          // the simple name. These entries ensure stereotype detection still works.
          "Service",
          "Controller",
          "RestController",
          "Component");

  private static final Set<String> REPOSITORY_STEREOTYPES =
      Set.of(
          "org.springframework.stereotype.Repository",
          // Simple-name fallback for unresolved annotation types
          "Repository");

  @Override
  public J.ClassDeclaration visitClassDeclaration(
      J.ClassDeclaration cd, ExtractionAccumulator acc) {
    JavaType.FullyQualified type = cd.getType();
    if (type != null) {
      String fqn = type.getFullyQualifiedName();
      String simpleName = cd.getSimpleName();
      String packageName = type.getPackageName();

      List<String> annotations =
          cd.getLeadingAnnotations().stream()
              .map(a -> resolveAnnotationName(a))
              .collect(Collectors.toList());

      List<String> modifiers =
          cd.getModifiers().stream()
              .map(m -> m.getType().name().toLowerCase())
              .collect(Collectors.toList());

      boolean isInterface = type.getKind() == JavaType.FullyQualified.Kind.Interface;
      boolean isEnum = type.getKind() == JavaType.FullyQualified.Kind.Enum;
      boolean isAbstract = cd.hasModifier(J.Modifier.Type.Abstract);

      String superClass = null;
      if (cd.getExtends() != null
          && cd.getExtends().getType() instanceof JavaType.FullyQualified sq) {
        superClass = sq.getFullyQualifiedName();
      }

      List<String> implementedInterfaces =
          cd.getImplements() == null
              ? Collections.emptyList()
              : cd.getImplements().stream()
                  .filter(impl -> impl.getType() instanceof JavaType.FullyQualified)
                  .map(impl -> ((JavaType.FullyQualified) impl.getType()).getFullyQualifiedName())
                  .collect(Collectors.toList());

      // Source file path — retrieved from the CompilationUnit cursor ancestor
      String sourceFilePath = extractSourceFilePath();

      acc.addClass(
          fqn,
          simpleName,
          packageName,
          annotations,
          modifiers,
          isInterface,
          isAbstract,
          isEnum,
          superClass,
          implementedInterfaces,
          sourceFilePath,
          null); // contentHash computed at a higher level if needed

      // Detect and apply stereotype labels based on annotation FQNs
      for (J.Annotation annotation : cd.getLeadingAnnotations()) {
        String annotFqn = resolveAnnotationName(annotation);
        if (SERVICE_STEREOTYPES.contains(annotFqn)) {
          acc.markAsService(fqn);
        } else if (REPOSITORY_STEREOTYPES.contains(annotFqn)) {
          acc.markAsRepository(fqn);
        }
        // Register annotation node data for non-unknown FQNs
        if (annotFqn != null && !annotFqn.startsWith("<") && annotFqn.contains(".")) {
          int lastDot = annotFqn.lastIndexOf('.');
          String annotSimple = annotFqn.substring(lastDot + 1);
          String annotPkg = annotFqn.substring(0, lastDot);
          acc.addAnnotation(annotFqn, annotSimple, annotPkg);
        }
      }
    }

    // MUST call super to recurse into nested/inner classes and class body members
    return super.visitClassDeclaration(cd, acc);
  }

  @Override
  public J.MethodDeclaration visitMethodDeclaration(
      J.MethodDeclaration md, ExtractionAccumulator acc) {
    JavaType.Method methodType = md.getMethodType();
    if (methodType != null) {
      String declaringClass = methodType.getDeclaringType().getFullyQualifiedName();
      String simpleName = md.getSimpleName();
      boolean isConstructor = md.isConstructor();

      String returnType =
          (md.getReturnTypeExpression() != null)
              ? md.getReturnTypeExpression().printTrimmed(getCursor())
              : "void";

      List<String> parameterTypes =
          methodType.getParameterTypes().stream()
              .map(ClassMetadataVisitor::typeToString)
              .collect(Collectors.toList());

      List<String> annotations =
          md.getLeadingAnnotations().stream()
              .map(a -> resolveAnnotationName(a))
              .collect(Collectors.toList());

      List<String> modifiers =
          md.getModifiers().stream()
              .map(m -> m.getType().name().toLowerCase())
              .collect(Collectors.toList());

      String methodId = buildMethodId(declaringClass, simpleName, parameterTypes);
      acc.addMethod(
          methodId,
          simpleName,
          returnType,
          declaringClass,
          parameterTypes,
          annotations,
          modifiers,
          isConstructor);
    }

    return super.visitMethodDeclaration(md, acc);
  }

  @Override
  public J.VariableDeclarations visitVariableDeclarations(
      J.VariableDeclarations vd, ExtractionAccumulator acc) {
    // Only record class-level fields — skip local variables inside methods
    boolean insideMethod = getCursor().firstEnclosing(J.MethodDeclaration.class) != null;
    if (!insideMethod) {
      // Get declaring class from enclosing ClassDeclaration
      J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
      if (enclosingClass != null && enclosingClass.getType() != null) {
        String declaringClass = enclosingClass.getType().getFullyQualifiedName();

        String fieldType =
            (vd.getTypeExpression() != null)
                ? vd.getTypeExpression().printTrimmed(getCursor())
                : "Unknown";

        List<String> annotations =
            vd.getLeadingAnnotations().stream()
                .map(a -> resolveAnnotationName(a))
                .collect(Collectors.toList());

        List<String> modifiers =
            vd.getModifiers().stream()
                .map(m -> m.getType().name().toLowerCase())
                .collect(Collectors.toList());

        for (J.VariableDeclarations.NamedVariable named : vd.getVariables()) {
          String fieldName = named.getSimpleName();
          String fieldId = declaringClass + "#" + fieldName;
          acc.addField(fieldId, fieldName, fieldType, declaringClass, annotations, modifiers);
        }
      }
    }

    return super.visitVariableDeclarations(vd, acc);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private String extractSourceFilePath() {
    // Walk cursor to find CompilationUnit which holds the source path
    J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
    if (cu != null) {
      return cu.getSourcePath().toString();
    }
    return null;
  }

  /**
   * Resolves the FQN for the given annotation. First tries OpenRewrite type resolution; if that
   * fails (type unresolved or unknown), applies a simple-name-to-FQN fallback switch covering
   * common Spring and JPA annotations. This ensures that annotation FQNs stored on class nodes
   * match those stored on JavaAnnotation nodes, enabling HAS_ANNOTATION edge creation.
   *
   * <p>This mirrors the same fallback logic in {@code JpaPatternVisitor.resolveAnnotationFqn()}.
   */
  private static String resolveAnnotationName(J.Annotation annotation) {
    if (annotation.getAnnotationType() != null
        && annotation.getAnnotationType().getType() instanceof JavaType.FullyQualified fq) {
      String fqn = fq.getFullyQualifiedName();
      // When type resolution fails, OpenRewrite returns "<unknown>" as FQN — fall through to
      // the simple-name switch below
      if (fqn != null && !fqn.startsWith("<")) {
        return fqn;
      }
    }
    // Simple-name-to-FQN fallback: covers common annotations that fail type resolution when
    // the annotation JARs are not on the parser classpath (e.g., javax.persistence, Spring)
    String simpleName = annotation.getSimpleName();
    return switch (simpleName) {
      case "Entity"         -> "javax.persistence.Entity";
      case "Table"          -> "javax.persistence.Table";
      case "Service"        -> "org.springframework.stereotype.Service";
      case "Repository"     -> "org.springframework.stereotype.Repository";
      case "Controller"     -> "org.springframework.stereotype.Controller";
      case "RestController" -> "org.springframework.web.bind.annotation.RestController";
      case "Component"      -> "org.springframework.stereotype.Component";
      case "Autowired"      -> "org.springframework.beans.factory.annotation.Autowired";
      case "Inject"         -> "javax.inject.Inject";
      case "Query"          -> "org.springframework.data.jpa.repository.Query";
      default               -> simpleName;
    };
  }

  private static String typeToString(JavaType type) {
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

  static String buildMethodId(String declaringClass, String simpleName, List<String> paramTypes) {
    String params = String.join(",", paramTypes);
    return declaringClass + "#" + simpleName + "(" + params + ")";
  }
}
