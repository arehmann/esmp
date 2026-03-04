package com.esmp.extraction.audit;

import com.esmp.extraction.visitor.ExtractionAccumulator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Generates a Vaadin pattern audit report from an {@link ExtractionAccumulator}.
 *
 * <p>This service addresses the STATE.md blocker: "OpenRewrite Vaadin 7 recipe coverage is LOW
 * confidence — hands-on audit required in Phase 2." It documents exactly what OpenRewrite CAN
 * statically detect in Vaadin 7 code, and explicitly lists what it CANNOT detect, so that
 * downstream migration tasks have an accurate confidence picture.
 */
@Service
public class VaadinAuditService {

  /**
   * Generates a {@link VaadinAuditReport} from the extracted accumulator data.
   *
   * @param acc the accumulator populated by visitor traversal of the source tree
   * @return an audit report with detected pattern counts and known limitations
   */
  public VaadinAuditReport generateReport(ExtractionAccumulator acc) {
    Set<String> vaadinViews = acc.getVaadinViews();
    Set<String> vaadinComponents = acc.getVaadinComponents();
    Set<String> vaadinDataBindings = acc.getVaadinDataBindings();
    long containsComponentEdges = acc.getComponentEdges().size();

    List<VaadinAuditReport.PatternEntry> detectedPatterns = new ArrayList<>();

    detectedPatterns.add(
        new VaadinAuditReport.PatternEntry(
            "VaadinView (implements View / extends UI)",
            vaadinViews.size(),
            List.copyOf(vaadinViews)));

    detectedPatterns.add(
        new VaadinAuditReport.PatternEntry(
            "VaadinComponent (new com.vaadin.ui.* expressions)",
            vaadinComponents.size(),
            List.copyOf(vaadinComponents)));

    detectedPatterns.add(
        new VaadinAuditReport.PatternEntry(
            "VaadinDataBinding (BeanFieldGroup usage)",
            vaadinDataBindings.size(),
            List.copyOf(vaadinDataBindings)));

    detectedPatterns.add(
        new VaadinAuditReport.PatternEntry(
            "CONTAINS_COMPONENT edges (addComponent() calls)",
            (int) containsComponentEdges,
            List.of()));

    List<String> knownLimitations =
        List.of(
            "Conditional component trees: addComponent() calls inside if/else branches are extracted"
                + " statically but the runtime condition is not evaluated — both branches are recorded.",
            "Reflective component instantiation: Class.forName() or reflection-based component"
                + " creation is invisible to static analysis and will not appear in the graph.",
            "Runtime push and widgetset configuration: @Push and widgetset settings are read from"
                + " web.xml or annotations at startup, not from Java AST — these require deployment"
                + " introspection, not static analysis.",
            "Dynamic navigation: Navigator.navigateTo() call targets are string literals — the"
                + " destination View class is not resolved by static analysis unless the string is"
                + " a constant.",
            "Inherited component additions: If a class inherits addComponent() calls from a"
                + " superclass, those inherited call sites will not appear under the subclass in"
                + " the call graph unless the subclass overrides the method.");

    String summary =
        String.format(
            "Detected %d VaadinView classes, %d VaadinComponent user classes,"
                + " %d VaadinDataBinding classes, and %d CONTAINS_COMPONENT edges via static"
                + " analysis. See knownLimitations for patterns that require runtime or deployment"
                + " introspection.",
            vaadinViews.size(),
            vaadinComponents.size(),
            vaadinDataBindings.size(),
            containsComponentEdges);

    return new VaadinAuditReport(detectedPatterns, knownLimitations, summary);
  }
}
