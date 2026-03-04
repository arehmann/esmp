package com.esmp.extraction.visitor;

import java.util.Collections;
import java.util.List;
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

  private static String resolveAnnotationName(J.Annotation annotation) {
    if (annotation.getAnnotationType() != null
        && annotation.getAnnotationType().getType() instanceof JavaType.FullyQualified fq) {
      String fqn = fq.getFullyQualifiedName();
      // When type resolution fails, OpenRewrite returns "<unknown>" as FQN — fall back to simple
      // name so annotation-based tests can still match against the unqualified annotation name
      if (fqn != null && !fqn.startsWith("<")) {
        return fqn;
      }
    }
    return annotation.getSimpleName();
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
