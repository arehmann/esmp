package com.esmp.extraction.visitor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * OpenRewrite {@link JavaIsoVisitor} that computes cyclomatic complexity per method and detects
 * database write operations per class.
 *
 * <h3>Cyclomatic Complexity (CC)</h3>
 *
 * <p>CC is computed using the branch-counting approach: CC = 1 (baseline) + number of branch
 * points. Branch points are: {@code if}, ternary ({@code ?:}), {@code for}, {@code for-each},
 * {@code while}, {@code do-while}, non-default {@code switch case}, and {@code catch}.
 *
 * <p>A {@link Deque} stack of counters is used to safely handle nested methods and lambdas — a new
 * counter is pushed on entry to each {@code visitMethodDeclaration} and popped on exit, preventing
 * instance-state leaking between source files.
 *
 * <h3>DB Write Detection</h3>
 *
 * <p>A method is classified as a DB write if any of the following hold:
 * <ol>
 *   <li>The method has a {@code @Modifying} annotation (Spring Data JPA).
 *   <li>The method body contains an invocation of {@code persist}, {@code merge}, {@code remove},
 *       {@code delete}, {@code save}, {@code saveAll}, {@code deleteAll}, or {@code flush}.
 *   <li>The method has a {@code @Query} annotation whose string value contains the keywords
 *       {@code INSERT}, {@code UPDATE}, or {@code DELETE} (case-insensitive).
 * </ol>
 */
public class ComplexityVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  /**
   * Stack of branch counters — one entry per active method declaration scope. Using a Deque
   * instead of a single field ensures nested methods and lambdas each get their own counter without
   * leaking into adjacent scopes.
   */
  private final Deque<int[]> counterStack = new ArrayDeque<>();

  /** Write-flagged method ids to avoid double-counting the same method twice (e.g. @Modifying + @Query). */
  private final java.util.Set<String> flaggedWriteMethods = new java.util.HashSet<>();

  private static final java.util.Set<String> JPA_WRITE_METHOD_NAMES = java.util.Set.of(
      "persist", "merge", "remove", "delete", "save", "saveAll", "deleteAll", "flush");

  private static final java.util.regex.Pattern WRITE_SQL_PATTERN =
      java.util.regex.Pattern.compile(
          "(?i)\\b(INSERT|UPDATE|DELETE)\\b");

  // =========================================================================
  // Complexity counting
  // =========================================================================

  @Override
  public J.MethodDeclaration visitMethodDeclaration(
      J.MethodDeclaration md, ExtractionAccumulator acc) {

    // Push a new branch counter for this method scope
    int[] counter = {0};
    counterStack.push(counter);

    // Recurse — this will visit the body and trigger visitIf, visitFor, etc.
    J.MethodDeclaration result = super.visitMethodDeclaration(md, acc);

    // Pop counter and compute CC = branches + 1 (baseline)
    counterStack.pop();
    int cc = counter[0] + 1;

    // Record complexity if we can resolve the method identity
    JavaType.Method methodType = md.getMethodType();
    if (methodType != null) {
      String declaringClassFqn = methodType.getDeclaringType().getFullyQualifiedName();
      List<String> paramTypes = methodType.getParameterTypes().stream()
          .map(ComplexityVisitor::typeToString)
          .collect(Collectors.toList());
      String methodId = ClassMetadataVisitor.buildMethodId(
          declaringClassFqn, md.getSimpleName(), paramTypes);
      acc.addMethodComplexity(methodId, declaringClassFqn, cc);

      // DB write detection: @Modifying annotation
      boolean hasModifying = md.getLeadingAnnotations().stream()
          .anyMatch(a -> isModifyingAnnotation(a));
      if (hasModifying && !flaggedWriteMethods.contains(methodId)) {
        flaggedWriteMethods.add(methodId);
        acc.incrementClassDbWrites(declaringClassFqn);
      }

      // DB write detection: @Query with write SQL keywords
      for (J.Annotation annotation : md.getLeadingAnnotations()) {
        if (isQueryAnnotation(annotation)) {
          String queryValue = extractAnnotationStringValue(annotation);
          if (queryValue != null && WRITE_SQL_PATTERN.matcher(queryValue).find()) {
            if (!flaggedWriteMethods.contains(methodId)) {
              flaggedWriteMethods.add(methodId);
              acc.incrementClassDbWrites(declaringClassFqn);
            }
          }
        }
      }
    }

    return result;
  }

  @Override
  public J.If visitIf(J.If ifStmt, ExtractionAccumulator acc) {
    incrementTopCounter();
    // Note: else-if is a nested J.If inside the else branch — it will be visited naturally
    // and increment the counter again. We do NOT count the else branch itself separately.
    return super.visitIf(ifStmt, acc);
  }

  @Override
  public J.Ternary visitTernary(J.Ternary ternary, ExtractionAccumulator acc) {
    incrementTopCounter();
    return super.visitTernary(ternary, acc);
  }

  @Override
  public J.WhileLoop visitWhileLoop(J.WhileLoop whileLoop, ExtractionAccumulator acc) {
    incrementTopCounter();
    return super.visitWhileLoop(whileLoop, acc);
  }

  @Override
  public J.DoWhileLoop visitDoWhileLoop(J.DoWhileLoop doWhileLoop, ExtractionAccumulator acc) {
    incrementTopCounter();
    return super.visitDoWhileLoop(doWhileLoop, acc);
  }

  @Override
  public J.ForLoop visitForLoop(J.ForLoop forLoop, ExtractionAccumulator acc) {
    incrementTopCounter();
    return super.visitForLoop(forLoop, acc);
  }

  @Override
  public J.ForEachLoop visitForEachLoop(J.ForEachLoop forEachLoop, ExtractionAccumulator acc) {
    incrementTopCounter();
    return super.visitForEachLoop(forEachLoop, acc);
  }

  @Override
  public J.Case visitCase(J.Case caseStmt, ExtractionAccumulator acc) {
    // Count non-default cases only. J.Case with an empty caseLabels list or with a J.Identifier
    // named "default" is a default case.
    if (!isDefaultCase(caseStmt)) {
      incrementTopCounter();
    }
    return super.visitCase(caseStmt, acc);
  }

  @Override
  public J.Try.Catch visitCatch(J.Try.Catch catchClause, ExtractionAccumulator acc) {
    incrementTopCounter();
    return super.visitCatch(catchClause, acc);
  }

  // =========================================================================
  // DB write detection via method invocations
  // =========================================================================

  @Override
  public J.MethodInvocation visitMethodInvocation(
      J.MethodInvocation mi, ExtractionAccumulator acc) {
    String methodName = mi.getSimpleName();
    if (JPA_WRITE_METHOD_NAMES.contains(methodName)) {
      // Find the enclosing class to attribute the write to
      J.ClassDeclaration enclosingClass =
          getCursor().firstEnclosing(J.ClassDeclaration.class);
      if (enclosingClass != null && enclosingClass.getType() != null) {
        // Find the enclosing method to build the write method id (for dedup)
        J.MethodDeclaration enclosingMethod =
            getCursor().firstEnclosing(J.MethodDeclaration.class);
        String classFqn = enclosingClass.getType().getFullyQualifiedName();
        String writeMethodId = classFqn + "#" + (enclosingMethod != null
            ? enclosingMethod.getSimpleName() : "_invocation_") + "_write";
        if (!flaggedWriteMethods.contains(writeMethodId)) {
          flaggedWriteMethods.add(writeMethodId);
          acc.incrementClassDbWrites(classFqn);
        }
      }
    }
    return super.visitMethodInvocation(mi, acc);
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /** Increments the branch counter at the top of the stack (current method scope). */
  private void incrementTopCounter() {
    if (!counterStack.isEmpty()) {
      counterStack.peek()[0]++;
    }
  }

  /** Returns true if the given annotation is @Modifying (by FQN or simple name). */
  private boolean isModifyingAnnotation(J.Annotation annotation) {
    String fqn = resolveAnnotationFqn(annotation);
    return "org.springframework.data.jpa.repository.Modifying".equals(fqn)
        || "Modifying".equals(fqn);
  }

  /** Returns true if the given annotation is @Query (by FQN or simple name). */
  private boolean isQueryAnnotation(J.Annotation annotation) {
    String fqn = resolveAnnotationFqn(annotation);
    return "org.springframework.data.jpa.repository.Query".equals(fqn)
        || "Query".equals(fqn);
  }

  /**
   * Resolves annotation FQN with simple-name fallback — mirrors JpaPatternVisitor.resolveAnnotationFqn().
   */
  private String resolveAnnotationFqn(J.Annotation annotation) {
    if (annotation.getAnnotationType() != null
        && annotation.getAnnotationType().getType() instanceof JavaType.FullyQualified fq) {
      String fqn = fq.getFullyQualifiedName();
      if (fqn != null && !fqn.startsWith("<")) {
        return fqn;
      }
    }
    String simpleName = annotation.getSimpleName();
    return switch (simpleName) {
      case "Modifying" -> "org.springframework.data.jpa.repository.Modifying";
      case "Query" -> "org.springframework.data.jpa.repository.Query";
      default -> simpleName;
    };
  }

  /**
   * Extracts the string value from a single-argument annotation (e.g., {@code @Query("SELECT ...")}).
   */
  private String extractAnnotationStringValue(J.Annotation annotation) {
    if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
      return null;
    }
    for (org.openrewrite.java.tree.Expression arg : annotation.getArguments()) {
      if (arg instanceof J.Literal literal && literal.getValue() instanceof String s) {
        return s;
      }
      if (arg instanceof J.Assignment assignment) {
        // Named arg: value = "..."
        if (assignment.getVariable() instanceof J.Identifier id
            && "value".equals(id.getSimpleName())) {
          if (assignment.getAssignment() instanceof J.Literal literal
              && literal.getValue() instanceof String s) {
            return s;
          }
        }
      }
    }
    return null;
  }

  /**
   * Returns true if the given {@code J.Case} is a default case.
   *
   * <p>A default case has no case labels (or the label is a {@code J.Identifier} for "default").
   * In OpenRewrite 8.x, {@code getCaseLabels()} returns {@code List<J>}.
   */
  private boolean isDefaultCase(J.Case caseStmt) {
    // getCaseLabels() returns List<J> in OpenRewrite 8.x
    List<J> labels = caseStmt.getCaseLabels();
    if (labels == null || labels.isEmpty()) {
      return true;
    }
    for (J label : labels) {
      if (label instanceof J.Identifier id && "default".equals(id.getSimpleName())) {
        return true;
      }
      // J.DefaultType is used in newer OpenRewrite versions for default case labels
      if (label.getClass().getSimpleName().equals("DefaultType")) {
        return true;
      }
    }
    return false;
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
}
