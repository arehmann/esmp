package com.esmp.graph.api;

import java.util.List;

/**
 * Response record for a single method's complexity data within a class detail view.
 *
 * @param methodId             unique method identifier (e.g., {@code com.example.MyClass#doWork(String)})
 * @param simpleName           simple method name (e.g., {@code doWork})
 * @param cyclomaticComplexity cyclomatic complexity of this method (1 = no branches)
 * @param parameterTypes       ordered list of parameter type names
 */
public record MethodComplexityEntry(
    String methodId,
    String simpleName,
    int cyclomaticComplexity,
    List<String> parameterTypes) {}
