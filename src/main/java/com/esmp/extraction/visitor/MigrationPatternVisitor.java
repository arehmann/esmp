package com.esmp.extraction.visitor;

import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.ActionType;
import com.esmp.extraction.visitor.ExtractionAccumulator.MigrationActionData.Automatable;
import java.util.Map;
import java.util.Set;
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
 * primary class FQN (from type declarations) is already known. Each import is checked against:
 * <ol>
 *   <li>{@code TYPE_MAP} — direct Vaadin 7 → Vaadin 24 type renames (auto=YES)
 *   <li>{@code PARTIAL_MAP} — types where mechanical rename is possible but styling needs review
 *       (auto=PARTIAL)
 *   <li>{@code COMPLEX_TYPES} — types that need significant structural rewrite (auto=NO)
 *   <li>{@code JAVAX_PACKAGE_MAP} — javax.* → jakarta.* package renames (auto=YES)
 *   <li>Any unknown {@code com.vaadin.*} type not in {@code com.vaadin.flow.*} gets
 *       COMPLEX_REWRITE/NO
 * </ol>
 */
public class MigrationPatternVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  /**
   * Direct Vaadin 7 UI type → Vaadin 24 FQN mappings.
   * All types in this map can be mechanically renamed by an OpenRewrite recipe (auto=YES).
   */
  private static final Map<String, String> TYPE_MAP = Map.ofEntries(
      Map.entry("com.vaadin.ui.TextField",
          "com.vaadin.flow.component.textfield.TextField"),
      Map.entry("com.vaadin.ui.TextArea",
          "com.vaadin.flow.component.textfield.TextArea"),
      Map.entry("com.vaadin.ui.PasswordField",
          "com.vaadin.flow.component.textfield.PasswordField"),
      Map.entry("com.vaadin.ui.Button",
          "com.vaadin.flow.component.button.Button"),
      Map.entry("com.vaadin.ui.Label",
          "com.vaadin.flow.component.html.Span"),
      Map.entry("com.vaadin.ui.CheckBox",
          "com.vaadin.flow.component.checkbox.Checkbox"),
      Map.entry("com.vaadin.ui.ComboBox",
          "com.vaadin.flow.component.combobox.ComboBox"),
      Map.entry("com.vaadin.ui.DateField",
          "com.vaadin.flow.component.datepicker.DatePicker"),
      Map.entry("com.vaadin.ui.Image",
          "com.vaadin.flow.component.html.Image"),
      Map.entry("com.vaadin.ui.Link",
          "com.vaadin.flow.component.html.Anchor"),
      Map.entry("com.vaadin.ui.MenuBar",
          "com.vaadin.flow.component.menubar.MenuBar"),
      Map.entry("com.vaadin.ui.ProgressBar",
          "com.vaadin.flow.component.progressbar.ProgressBar"),
      Map.entry("com.vaadin.ui.Upload",
          "com.vaadin.flow.component.upload.Upload"),
      Map.entry("com.vaadin.ui.Notification",
          "com.vaadin.flow.component.notification.Notification"),
      Map.entry("com.vaadin.ui.VerticalLayout",
          "com.vaadin.flow.component.orderedlayout.VerticalLayout"),
      Map.entry("com.vaadin.ui.HorizontalLayout",
          "com.vaadin.flow.component.orderedlayout.HorizontalLayout"),
      Map.entry("com.vaadin.ui.FormLayout",
          "com.vaadin.flow.component.formlayout.FormLayout"),
      Map.entry("com.vaadin.ui.CssLayout",
          "com.vaadin.flow.component.html.Div")
  );

  /**
   * Vaadin 7 types where the type rename is mechanical (recipe can handle it) but the result
   * requires manual styling/configuration review. These get auto=PARTIAL.
   */
  private static final Map<String, String> PARTIAL_MAP = Map.ofEntries(
      Map.entry("com.vaadin.ui.Panel",
          "com.vaadin.flow.component.html.Div")
  );

  /**
   * Vaadin 7 types that require significant structural rewrite — AI assistance or manual developer
   * effort is required. These get auto=NO with COMPLEX_REWRITE action type.
   */
  private static final Set<String> COMPLEX_TYPES = Set.of(
      "com.vaadin.ui.Table",
      "com.vaadin.data.util.BeanItemContainer",
      "com.vaadin.data.fieldgroup.BeanFieldGroup",
      "com.vaadin.data.fieldgroup.FieldGroup",
      "com.vaadin.ui.Window",
      "com.vaadin.ui.TabSheet",
      "com.vaadin.ui.Tree",
      "com.vaadin.ui.TreeTable",
      "com.vaadin.ui.CustomComponent",
      "com.vaadin.navigator.View",
      "com.vaadin.ui.UI"
  );

  /**
   * Description of what each complex Vaadin 7 type maps to in Vaadin 24.
   * Used as the {@code target} field in the migration action.
   */
  private static final Map<String, String> COMPLEX_TARGETS = Map.ofEntries(
      Map.entry("com.vaadin.ui.Table",
          "com.vaadin.flow.component.grid.Grid"),
      Map.entry("com.vaadin.data.util.BeanItemContainer",
          "DataProvider"),
      Map.entry("com.vaadin.data.fieldgroup.BeanFieldGroup",
          "com.vaadin.flow.data.binder.Binder"),
      Map.entry("com.vaadin.data.fieldgroup.FieldGroup",
          "com.vaadin.flow.data.binder.Binder"),
      Map.entry("com.vaadin.ui.Window",
          "com.vaadin.flow.component.dialog.Dialog"),
      Map.entry("com.vaadin.ui.TabSheet",
          "com.vaadin.flow.component.tabs.Tabs"),
      Map.entry("com.vaadin.ui.Tree",
          "com.vaadin.flow.component.treegrid.TreeGrid"),
      Map.entry("com.vaadin.ui.TreeTable",
          "com.vaadin.flow.component.treegrid.TreeGrid"),
      Map.entry("com.vaadin.ui.CustomComponent",
          "com.vaadin.flow.component.Composite"),
      Map.entry("com.vaadin.navigator.View",
          "@Route annotation"),
      Map.entry("com.vaadin.ui.UI",
          "@Route + AppLayout")
  );

  /**
   * javax.* package prefixes that should be renamed to jakarta.* in Spring Boot 3 / Vaadin 24.
   * Key: javax package prefix, Value: jakarta equivalent prefix.
   */
  private static final Map<String, String> JAVAX_PACKAGE_MAP = Map.of(
      "javax.servlet", "jakarta.servlet",
      "javax.validation", "jakarta.validation"
  );

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

    // 1. Check TYPE_MAP — direct rename, fully automatable (YES)
    if (TYPE_MAP.containsKey(importFqn)) {
      String target = TYPE_MAP.get(importFqn);
      acc.addMigrationAction(classFqn, new MigrationActionData(
          ActionType.CHANGE_TYPE,
          importFqn,
          target,
          Automatable.YES,
          null
      ));
      return;
    }

    // 2. Check PARTIAL_MAP — mechanical rename but needs styling review (PARTIAL)
    if (PARTIAL_MAP.containsKey(importFqn)) {
      String target = PARTIAL_MAP.get(importFqn);
      acc.addMigrationAction(classFqn, new MigrationActionData(
          ActionType.CHANGE_TYPE,
          importFqn,
          target,
          Automatable.PARTIAL,
          "Styling and layout properties need manual adjustment after type rename"
      ));
      return;
    }

    // 3. Check COMPLEX_TYPES — requires structural rewrite (NO)
    if (COMPLEX_TYPES.contains(importFqn)) {
      String target = COMPLEX_TARGETS.getOrDefault(importFqn, "Manual migration required");
      acc.addMigrationAction(classFqn, new MigrationActionData(
          ActionType.COMPLEX_REWRITE,
          importFqn,
          target,
          Automatable.NO,
          buildComplexContext(importFqn)
      ));
      return;
    }

    // 4. Check javax.* package prefixes → jakarta.* (YES)
    for (Map.Entry<String, String> entry : JAVAX_PACKAGE_MAP.entrySet()) {
      String javaxPrefix = entry.getKey();
      if (importFqn.startsWith(javaxPrefix)) {
        String target = entry.getValue() + importFqn.substring(javaxPrefix.length());
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

    // 5. Unknown com.vaadin.* type (not in com.vaadin.flow.* — those are already Vaadin 24)
    if (importFqn.startsWith("com.vaadin.") && !importFqn.startsWith("com.vaadin.flow.")) {
      acc.addMigrationAction(classFqn, new MigrationActionData(
          ActionType.COMPLEX_REWRITE,
          importFqn,
          "Unknown — manual investigation required",
          Automatable.NO,
          "Unknown Vaadin 7 type — manual migration required"
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

  /**
   * Builds a descriptive context message for a known complex Vaadin 7 type, explaining what the
   * migration involves and why AI/manual effort is required.
   */
  private static String buildComplexContext(String sourceFqn) {
    return switch (sourceFqn) {
      case "com.vaadin.ui.Table" ->
          "Table has no direct equivalent in Vaadin 24. Migration requires replacing with Grid, "
              + "adding column definitions, and migrating container data sources to DataProvider.";
      case "com.vaadin.data.util.BeanItemContainer" ->
          "BeanItemContainer is replaced by DataProvider in Vaadin 24. Requires structural changes "
              + "to data loading and filtering patterns.";
      case "com.vaadin.data.fieldgroup.BeanFieldGroup",
          "com.vaadin.data.fieldgroup.FieldGroup" ->
          "FieldGroup/BeanFieldGroup is replaced by Binder in Vaadin 24. Binding declarations and "
              + "commit/discard patterns must be rewritten.";
      case "com.vaadin.ui.Window" ->
          "Window is replaced by Dialog in Vaadin 24. Layout and positioning API has changed.";
      case "com.vaadin.ui.TabSheet" ->
          "TabSheet is replaced by Tabs component in Vaadin 24. Tab content wiring pattern changed.";
      case "com.vaadin.ui.Tree", "com.vaadin.ui.TreeTable" ->
          "Tree/TreeTable are replaced by TreeGrid in Vaadin 24. Hierarchical data model must be "
              + "migrated to TreeDataProvider or HierarchicalDataProvider.";
      case "com.vaadin.ui.CustomComponent" ->
          "CustomComponent is replaced by Composite in Vaadin 24. Component composition pattern "
              + "may need structural changes.";
      case "com.vaadin.navigator.View" ->
          "View interface is replaced by @Route annotation in Vaadin 24. Navigator lifecycle "
              + "methods (enter/beforeLeave) must be replaced with BeforeEnterObserver/etc.";
      case "com.vaadin.ui.UI" ->
          "UI class extension is replaced by @Route + AppLayout pattern in Vaadin 24. Application "
              + "structure must be redesigned.";
      default -> "Complex Vaadin 7 type requiring structural migration to Vaadin 24.";
    };
  }
}
