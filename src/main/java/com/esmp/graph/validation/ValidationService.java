package com.esmp.graph.validation;

import com.esmp.graph.api.ValidationQueryResult;
import com.esmp.graph.api.ValidationReport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

/**
 * Execution engine for graph validation queries.
 *
 * <p>Accepts all {@link ValidationQueryRegistry} beans registered in the application context,
 * enabling future phases to contribute additional validation queries without modifying this service.
 *
 * <p>Executes each query via {@link Neo4jClient} using {@code .query().fetch().all()} — the same
 * established pattern used in {@link com.esmp.graph.application.GraphQueryService}. All queries
 * are strictly read-only; no MERGE, CREATE, or DELETE statements are executed.
 *
 * <p>Status determination logic:
 * <ul>
 *   <li>count == 0 => PASS (no violations)
 *   <li>count > 0 + ERROR severity => FAIL
 *   <li>count > 0 + WARNING severity => WARN
 *   <li>CALLS_EDGE_COVERAGE is inverted: count > 0 => PASS, count == 0 => WARN
 * </ul>
 */
@Service
public class ValidationService {

  private final Neo4jClient neo4jClient;
  private final List<ValidationQueryRegistry> registries;

  public ValidationService(Neo4jClient neo4jClient, List<ValidationQueryRegistry> registries) {
    this.neo4jClient = neo4jClient;
    this.registries = registries;
  }

  /**
   * Runs all registered validation queries and returns a complete report.
   *
   * @return a {@link ValidationReport} with per-query results and aggregate pass/fail/warn counts
   */
  public ValidationReport runAllValidations() {
    List<ValidationQuery> allQueries = registries.stream()
        .flatMap(registry -> registry.getQueries().stream())
        .collect(Collectors.toList());

    List<ValidationQueryResult> results = new ArrayList<>();
    for (ValidationQuery query : allQueries) {
      results.add(executeQuery(query));
    }

    long errorCount = results.stream()
        .filter(r -> r.status() == ValidationStatus.FAIL)
        .count();
    long warnCount = results.stream()
        .filter(r -> r.status() == ValidationStatus.WARN)
        .count();
    long passCount = results.stream()
        .filter(r -> r.status() == ValidationStatus.PASS)
        .count();

    return new ValidationReport(
        Instant.now().toString(),
        results,
        errorCount,
        warnCount,
        passCount);
  }

  private ValidationQueryResult executeQuery(ValidationQuery query) {
    Collection<Map<String, Object>> rows = neo4jClient
        .query(query.cypher())
        .fetch()
        .all();

    long count = 0L;
    List<String> details = new ArrayList<>();

    if (!rows.isEmpty()) {
      Map<String, Object> row = rows.iterator().next();

      Object countObj = row.get("count");
      if (countObj instanceof Long l) {
        count = l;
      } else if (countObj instanceof Number n) {
        count = n.longValue();
      }

      Object detailsObj = row.get("details");
      if (detailsObj instanceof List<?> list) {
        details = list.stream()
            .filter(d -> d instanceof String)
            .map(d -> (String) d)
            .collect(Collectors.toList());
      }
    }

    ValidationStatus status = determineStatus(query, count);

    return new ValidationQueryResult(
        query.name(),
        query.description(),
        query.severity(),
        status,
        count,
        details);
  }

  /**
   * Determines the pass/fail/warn status for a query result.
   *
   * <p>Special case: CALLS_EDGE_COVERAGE uses inverted logic — count > 0 means PASS (there are
   * CALLS edges in the graph), count == 0 means WARN (no CALLS edges found after extraction).
   *
   * @param query the validation query definition
   * @param count the violation count returned by the query
   * @return PASS, FAIL, or WARN
   */
  private ValidationStatus determineStatus(ValidationQuery query, long count) {
    // CALLS_EDGE_COVERAGE: inverted semantics — coverage query, not a violation query
    if ("CALLS_EDGE_COVERAGE".equals(query.name())) {
      return count > 0 ? ValidationStatus.PASS : ValidationStatus.WARN;
    }

    if (count == 0) {
      return ValidationStatus.PASS;
    } else if (query.severity() == ValidationSeverity.WARNING) {
      return ValidationStatus.WARN;
    } else {
      return ValidationStatus.FAIL;
    }
  }
}
