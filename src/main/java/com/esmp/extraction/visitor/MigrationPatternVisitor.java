package com.esmp.extraction.visitor;

import com.esmp.migration.api.RecipeRule;
import com.esmp.migration.application.RecipeBookRegistry;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.ActionType;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.Automatable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

/**
 * OpenRewrite {@link JavaIsoVisitor} that catalogs every Vaadin 7 type usage and javax package
 * usage per class, producing {@link MigrationActionData} entries in the {@link
 * ExtractionAccumulator}.
 *
 * <h3>Detection Strategy</h3>
 *
 * <p>Detection is import-based — imports are processed at the CompilationUnit level so that the
 * primary class FQN (from type declarations) is already known. Each import is checked against
 * the {@link RecipeBookRegistry}:
 * <ol>
 *   <li>Direct source FQN lookup — if a rule exists with matching source, use it
 *   <li>JAVAX_JAKARTA prefix rules — rules with CHANGE_PACKAGE action and JAVAX_JAKARTA category
 *       are applied as package prefix replacements
 *   <li>Unknown com.vaadin.* type (not in com.vaadin.flow.*) — produces COMPLEX_REWRITE/NO
 *   <li>Unknown com.alfa.* type (not in registry) — produces COMPLEX_REWRITE/NO
 * </ol>
 *
 * <p>Rules are captured as a snapshot from the registry at construction time for thread safety.
 * The registry itself may be reloaded concurrently; each visitor instance works on its snapshot.
 */
public class MigrationPatternVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  /** Rule lookup by source FQN — snapshot taken at construction time. */
  private final Map<String, RecipeRule> rulesBySource;

  /** Ordered list of JAVAX_JAKARTA rules for prefix-based matching. */
  private final List<RecipeRule> javaxJakartaRules;

  /**
   * Primary constructor: takes a snapshot of all rules from the registry.
   * Thread-safe: each visitor instance holds an immutable snapshot.
   *
   * @param registry the recipe book registry to snapshot rules from
   */
  public MigrationPatternVisitor(RecipeBookRegistry registry) {
    List<RecipeRule> snapshot = new ArrayList<>(registry.getRules());
    this.rulesBySource = new LinkedHashMap<>();
    this.javaxJakartaRules = new ArrayList<>();

    for (RecipeRule rule : snapshot) {
      rulesBySource.put(rule.source(), rule);
      if ("JAVAX_JAKARTA".equals(rule.category()) && "CHANGE_PACKAGE".equals(rule.actionType())) {
        javaxJakartaRules.add(rule);
      }
    }
  }

  // =========================================================================
  // Visitor overrides
  // =========================================================================

  /**
   * Processes the compilation unit to extract migration actions.
   *
   * <p>Imports are processed at CompilationUnit level because imports appear before class
   * declarations in the LST. We first resolve the primary class FQN from the type declarations,
   * then process all imports in that context.
   */
  @Override
  public J.CompilationUnit visitCompilationUnit(
      J.CompilationUnit cu, ExtractionAccumulator acc) {

    // Determine the primary class FQN from the first class declaration
    String classFqn = resolveClassFqn(cu);
    if (classFqn == null) {
      return super.visitCompilationUnit(cu, acc);
    }

    // Process each non-static import in context of the class FQN
    for (J.Import imp : cu.getImports()) {
      if (!imp.isStatic()) {
        processImport(imp.getTypeName(), classFqn, acc);
      }
    }

    // Continue recursion for nested class handling etc.
    return super.visitCompilationUnit(cu, acc);
  }

  // =========================================================================
  // Import processing
  // =========================================================================

  /**
   * Classifies a single import and adds the corresponding migration action to the accumulator.
   *
   * @param importFqn the fully qualified import name
   * @param classFqn the FQN of the class that contains this import
   * @param acc the accumulator to add actions to
   */
  private void processImport(String importFqn, String classFqn, ExtractionAccumulator acc) {

    // 1. Direct lookup by source FQN
    RecipeRule rule = rulesBySource.get(importFqn);
    if (rule != null && !"NEEDS_MAPPING".equals(rule.status())) {
      acc.addMigrationAction(classFqn, new MigrationActionData(
          ActionType.valueOf(rule.actionType()),
          importFqn,
          rule.target(),
          Automatable.valueOf(rule.automatable()),
          rule.context()
      ));
      return;
    }

    // 2. JAVAX_JAKARTA prefix matching — check package prefix rules
    for (RecipeRule r : javaxJakartaRules) {
      if (importFqn.startsWith(r.source())) {
        String target = r.target() + importFqn.substring(r.source().length());
        acc.addMigrationAction(classFqn, new MigrationActionData(
            ActionType.CHANGE_PACKAGE,
            importFqn,
            target,
            Automatable.YES,
            null
        ));
        return;
      }
    }

    // 3. Unknown com.vaadin.* type (not in com.vaadin.flow.* — those are already Vaadin 24)
    if (importFqn.startsWith("com.vaadin.") && !importFqn.startsWith("com.vaadin.flow.")) {
      acc.addMigrationAction(classFqn, new MigrationActionData(
          ActionType.COMPLEX_REWRITE,
          importFqn,
          "Unknown — manual investigation required",
          Automatable.NO,
          "Unknown Vaadin 7 type — manual migration required"
      ));
      return;
    }

    // 4. Unknown com.alfa.* type - not found in registry
    if (importFqn.startsWith("com.alfa.")) {
      acc.addMigrationAction(classFqn, new MigrationActionData(
          ActionType.COMPLEX_REWRITE,
          importFqn,
          "Unknown Alfa* type - manual investigation required",
          Automatable.NO,
          "Unknown Alfa* wrapper type - check if an Alfa* overlay rule covers this"
      ));
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /**
   * Resolves the primary class FQN from a compilation unit.
   *
   * <p>Tries type-attributed lookup first; falls back to package + simple name if types are
   * unresolved (e.g., when running without a full classpath).
   */
  private static String resolveClassFqn(J.CompilationUnit cu) {
    // Prefer type-attributed FQN from first class declaration
    for (J.ClassDeclaration cd : cu.getClasses()) {
      if (cd.getType() != null) {
        return cd.getType().getFullyQualifiedName();
      }
    }
    // Fall back to package + simple name
    if (!cu.getClasses().isEmpty()) {
      J.ClassDeclaration firstClass = cu.getClasses().get(0);
      String pkg = cu.getPackageDeclaration() != null
          ? cu.getPackageDeclaration().getExpression().toString()
          : "";
      String simpleName = firstClass.getSimpleName();
      return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }
    return null;
  }
}
