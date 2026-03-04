package com.esmp.extraction.visitor;

import java.util.List;
import java.util.stream.Collectors;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * OpenRewrite {@link JavaIsoVisitor} that extracts method-call graph edges from Java LSTs.
 *
 * <p>Covers AST-03: for every statically-resolved method invocation, this visitor creates a
 * directed {@link ExtractionAccumulator.CallEdge} from the enclosing method (caller) to the
 * invoked method (callee) with fully-qualified method IDs.
 *
 * <p>Known limitations:
 *
 * <ul>
 *   <li>Reflective or dynamic dispatch calls are invisible to static analysis and are not captured.
 *   <li>Calls where {@code getMethodType()} returns null (unresolved types due to incomplete
 *       classpath) are silently skipped.
 *   <li>Calls into {@code java.lang.*} and {@code java.util.*} are filtered out to reduce noise.
 * </ul>
 */
public class CallGraphVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  private static final String JDK_LANG_PREFIX = "java.lang.";
  private static final String JDK_UTIL_PREFIX = "java.util.";
  private static final String JDK_IO_PREFIX = "java.io.";
  private static final String JDK_NIO_PREFIX = "java.nio.";

  @Override
  public J.MethodInvocation visitMethodInvocation(
      J.MethodInvocation mi, ExtractionAccumulator acc) {
    JavaType.Method calleeType = mi.getMethodType();
    if (calleeType != null && calleeType.getDeclaringType() != null) {
      String calleeClass = calleeType.getDeclaringType().getFullyQualifiedName();

      // Skip standard JDK calls — they add noise without actionable graph edges
      if (!isJdkClass(calleeClass)) {
        String calleeMethod = calleeType.getName();
        List<String> calleeParamTypes =
            calleeType.getParameterTypes().stream()
                .map(CallGraphVisitor::typeToString)
                .collect(Collectors.toList());
        String calleeMethodId =
            ClassMetadataVisitor.buildMethodId(calleeClass, calleeMethod, calleeParamTypes);

        // Find the enclosing method declaration (caller context)
        J.MethodDeclaration enclosingMethod =
            getCursor().firstEnclosing(J.MethodDeclaration.class);
        if (enclosingMethod != null && enclosingMethod.getMethodType() != null) {
          JavaType.Method callerType = enclosingMethod.getMethodType();
          String callerClass = callerType.getDeclaringType().getFullyQualifiedName();
          String callerMethod = enclosingMethod.getSimpleName();
          List<String> callerParamTypes =
              callerType.getParameterTypes().stream()
                  .map(CallGraphVisitor::typeToString)
                  .collect(Collectors.toList());
          String callerMethodId =
              ClassMetadataVisitor.buildMethodId(callerClass, callerMethod, callerParamTypes);

          String sourceFile = extractSourceFilePath();
          int lineNumber = mi.getPrefix().getLastWhitespace().lastIndexOf('\n') >= 0 ? -1 : -1;

          acc.addCall(callerMethodId, calleeMethodId, sourceFile, lineNumber);
        }
      }
    }

    return super.visitMethodInvocation(mi, acc);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private boolean isJdkClass(String fqn) {
    return fqn.startsWith(JDK_LANG_PREFIX)
        || fqn.startsWith(JDK_UTIL_PREFIX)
        || fqn.startsWith(JDK_IO_PREFIX)
        || fqn.startsWith(JDK_NIO_PREFIX);
  }

  private String extractSourceFilePath() {
    J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
    return cu != null ? cu.getSourcePath().toString() : null;
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
