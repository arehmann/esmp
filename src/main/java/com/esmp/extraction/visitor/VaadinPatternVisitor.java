package com.esmp.extraction.visitor;

import java.util.Set;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * OpenRewrite {@link JavaIsoVisitor} that detects Vaadin 7 patterns and emits secondary labels and
 * CONTAINS_COMPONENT edges into the {@link ExtractionAccumulator}.
 *
 * <p>Detects:
 *
 * <ul>
 *   <li><strong>VaadinView</strong>: classes implementing {@code com.vaadin.navigator.View} or
 *       extending {@code com.vaadin.ui.UI}
 *   <li><strong>VaadinComponent</strong>: classes that instantiate Vaadin UI component types via
 *       {@code new} expressions (e.g., {@code new Button()}, {@code new VerticalLayout()})
 *   <li><strong>VaadinDataBinding</strong>: classes that use {@code BeanFieldGroup}, {@code
 *       FieldGroup}, or {@code BeanItemContainer}
 *   <li><strong>CONTAINS_COMPONENT edges</strong>: parent-child component hierarchy captured from
 *       {@code addComponent()} / {@code addComponents()} call sites
 * </ul>
 *
 * <p>Type detection requires a type-attributed LST (classpath provided at parse time). When types
 * are unresolved, patterns are detected via simple name fallback where possible, but callee
 * resolution for {@code addComponent()} requires the Vaadin classpath.
 */
public class VaadinPatternVisitor extends JavaIsoVisitor<ExtractionAccumulator> {

  private static final Set<String> VAADIN_VIEW_TYPES =
      Set.of("com.vaadin.navigator.View", "com.vaadin.ui.UI");

  private static final String VAADIN_UI_PACKAGE = "com.vaadin.ui";

  private static final Set<String> VAADIN_DATA_BINDING_TYPES =
      Set.of(
          "com.vaadin.data.fieldgroup.BeanFieldGroup",
          "com.vaadin.data.fieldgroup.FieldGroup",
          "com.vaadin.data.util.BeanItemContainer");

  /**
   * Simple names of Vaadin data binding types that emit BINDS_TO edges when their FQN cannot be
   * resolved (i.e., when Vaadin JARs are absent from the parser classpath). BeanItemContainer is
   * intentionally excluded — it is a data source, not a form-to-entity binding mechanism.
   */
  private static final Set<String> VAADIN_BINDING_SIMPLE_NAMES =
      Set.of("BeanFieldGroup", "FieldGroup");

  /**
   * Simple names of common Vaadin UI component types used as a fallback when FQN resolution fails.
   */
  private static final Set<String> VAADIN_UI_SIMPLE_NAMES =
      Set.of(
          "Button", "TextField", "VerticalLayout", "HorizontalLayout", "Grid",
          "Table", "Label", "ComboBox", "DateField", "Panel", "FormLayout",
          "Window", "TabSheet");

  private static final Set<String> ADD_COMPONENT_METHODS = Set.of("addComponent", "addComponents");

  @Override
  public J.ClassDeclaration visitClassDeclaration(
      J.ClassDeclaration cd, ExtractionAccumulator acc) {
    JavaType.FullyQualified type = cd.getType();
    if (type != null) {
      String fqn = type.getFullyQualifiedName();

      // Check getImplements() for View interface
      if (cd.getImplements() != null) {
        for (var impl : cd.getImplements()) {
          if (impl.getType() instanceof JavaType.FullyQualified fq
              && VAADIN_VIEW_TYPES.contains(fq.getFullyQualifiedName())) {
            acc.markAsVaadinView(fqn);
            break;
          }
        }
      }

      // Check getExtends() for UI superclass
      if (cd.getExtends() != null
          && cd.getExtends().getType() instanceof JavaType.FullyQualified sq
          && VAADIN_VIEW_TYPES.contains(sq.getFullyQualifiedName())) {
        acc.markAsVaadinView(fqn);
      }
    }

    return super.visitClassDeclaration(cd, acc);
  }

  @Override
  public J.MethodInvocation visitMethodInvocation(
      J.MethodInvocation mi, ExtractionAccumulator acc) {
    String methodName = mi.getSimpleName();

    if (ADD_COMPONENT_METHODS.contains(methodName)) {
      // Detect addComponent() calls — check if declaring type is in com.vaadin.ui package
      // (type-attributed) or fall back to simple name heuristic
      boolean isVaadinAddComponent = isVaadinUiMethodCall(mi);
      if (isVaadinAddComponent) {
        // Find enclosing class (parent container)
        J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
        if (enclosingClass != null && enclosingClass.getType() != null) {
          String parentClassFqn = enclosingClass.getType().getFullyQualifiedName();
          String parentType = enclosingClass.getSimpleName();

          // Resolve child component type from the first argument if possible
          String childType = "Unknown";
          String childClassFqn = "Unknown";
          if (!mi.getArguments().isEmpty()) {
            var arg = mi.getArguments().get(0);
            if (arg.getType() instanceof JavaType.FullyQualified fq) {
              childClassFqn = fq.getFullyQualifiedName();
              childType = fq.getClassName();
            } else if (arg instanceof J.Identifier id) {
              childType = id.getSimpleName();
            }
          }

          acc.addComponentEdge(parentClassFqn, childClassFqn, parentType, childType);
        }
      }
    }

    // Detect data binding patterns — check field group method calls
    detectDataBindingCall(mi, acc);

    return super.visitMethodInvocation(mi, acc);
  }

  @Override
  public J.NewClass visitNewClass(J.NewClass nc, ExtractionAccumulator acc) {
    // Detect instantiation of Vaadin UI component types (FQN-based check)
    if (nc.getType() instanceof JavaType.FullyQualified fq
        && fq.getFullyQualifiedName().startsWith(VAADIN_UI_PACKAGE)) {
      // Mark the enclosing class as a VaadinComponent user
      J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
      if (enclosingClass != null && enclosingClass.getType() != null) {
        acc.markAsVaadinComponent(enclosingClass.getType().getFullyQualifiedName());
      }
    } else {
      // Simple-name fallback: when Vaadin JARs are absent, FQN cannot be resolved.
      // Match common Vaadin UI component simple names to still mark the enclosing class.
      String simpleName = extractNewClassSimpleName(nc);
      if (simpleName != null && VAADIN_UI_SIMPLE_NAMES.contains(simpleName)) {
        J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
        if (enclosingClass != null && enclosingClass.getType() != null) {
          acc.markAsVaadinComponent(enclosingClass.getType().getFullyQualifiedName());
        }
      }
    }

    // Also detect BeanFieldGroup / FieldGroup instantiation for data binding (FQN-based check)
    if (nc.getType() instanceof JavaType.FullyQualified fq
        && VAADIN_DATA_BINDING_TYPES.contains(fq.getFullyQualifiedName())) {
      J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
      if (enclosingClass != null && enclosingClass.getType() != null) {
        String enclosingFqn = enclosingClass.getType().getFullyQualifiedName();
        acc.markAsVaadinDataBinding(enclosingFqn);

        // Emit BINDS_TO edge for BeanFieldGroup and FieldGroup (not BeanItemContainer)
        String typeFqn = fq.getFullyQualifiedName();
        String bindingMechanism = null;
        if (typeFqn.contains("BeanFieldGroup")) {
          bindingMechanism = "BeanFieldGroup";
        } else if (typeFqn.contains("FieldGroup")) {
          bindingMechanism = "FieldGroup";
        }
        // BeanItemContainer is a data source, not a form binding — skip it

        if (bindingMechanism != null) {
          // Extract entity type from generic type argument (e.g., BeanFieldGroup<SampleEntity>)
          String entityFqn = "Unknown";
          if (nc.getType() instanceof JavaType.Parameterized pt
              && !pt.getTypeParameters().isEmpty()) {
            JavaType typeParam = pt.getTypeParameters().get(0);
            if (typeParam instanceof JavaType.FullyQualified entityType) {
              entityFqn = entityType.getFullyQualifiedName();
            }
          }
          acc.addBindsToEdge(enclosingFqn, entityFqn, bindingMechanism);
        }
      }
    } else {
      // Simple-name fallback for BINDS_TO: when Vaadin JARs are absent, type cannot be resolved.
      // Match BeanFieldGroup and FieldGroup by simple name to still emit BINDS_TO edge data.
      // BeanItemContainer is intentionally excluded (it's a data source, not a form binding).
      String simpleName = extractNewClassSimpleName(nc);
      if (simpleName != null && VAADIN_BINDING_SIMPLE_NAMES.contains(simpleName)) {
        J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
        if (enclosingClass != null && enclosingClass.getType() != null) {
          String enclosingFqn = enclosingClass.getType().getFullyQualifiedName();
          acc.markAsVaadinDataBinding(enclosingFqn);
          // Entity FQN falls back to "Unknown" — generic type params are unresolvable without JARs
          acc.addBindsToEdge(enclosingFqn, "Unknown", simpleName);
        }
      }
    }

    return super.visitNewClass(nc, acc);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Returns true if the method invocation is an {@code addComponent()} call on a Vaadin UI
   * component (type-attributed check) or — when type is unresolved — a heuristic fallback.
   */
  private boolean isVaadinUiMethodCall(J.MethodInvocation mi) {
    if (mi.getMethodType() != null && mi.getMethodType().getDeclaringType() != null) {
      String declaringFqn = mi.getMethodType().getDeclaringType().getFullyQualifiedName();
      return declaringFqn.startsWith(VAADIN_UI_PACKAGE);
    }
    // Heuristic fallback when type is unresolved: check if `this` is the receiver (implicit)
    // In Vaadin 7, addComponent() without explicit target is always called on `this` (the
    // container)
    return mi.getSelect() == null;
  }

  /**
   * Extracts the simple class name from a {@code new} expression. Handles both plain identifiers
   * ({@code new BeanFieldGroup()}) and parameterized types ({@code new BeanFieldGroup<Entity>()}).
   *
   * @param nc the new class expression
   * @return the simple name, or {@code null} if it cannot be determined
   */
  private static String extractNewClassSimpleName(J.NewClass nc) {
    if (nc.getClazz() instanceof J.Identifier id) {
      return id.getSimpleName();
    }
    if (nc.getClazz() instanceof J.ParameterizedType pt
        && pt.getClazz() instanceof J.Identifier id) {
      return id.getSimpleName();
    }
    return null;
  }

  /** Detects data binding calls (e.g., fieldGroup.bind(), fieldGroup.commit()) and marks class. */
  private void detectDataBindingCall(J.MethodInvocation mi, ExtractionAccumulator acc) {
    if (mi.getMethodType() != null && mi.getMethodType().getDeclaringType() != null) {
      String declaringFqn = mi.getMethodType().getDeclaringType().getFullyQualifiedName();
      if (VAADIN_DATA_BINDING_TYPES.contains(declaringFqn)) {
        J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
        if (enclosingClass != null && enclosingClass.getType() != null) {
          acc.markAsVaadinDataBinding(enclosingClass.getType().getFullyQualifiedName());
        }
      }
    }
  }
}
