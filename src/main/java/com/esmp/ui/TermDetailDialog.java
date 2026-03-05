package com.esmp.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.util.List;

/**
 * Read-only dialog showing the fully qualified names of all Java classes that reference a given
 * domain term via a USES_TERM graph edge.
 *
 * <p>Opened when a user clicks the usage-count button in the {@link LexiconView} grid.
 */
public class TermDetailDialog extends Dialog {

  /**
   * Creates a detail dialog for the given term.
   *
   * @param termDisplayName human-readable term name used in the dialog title
   * @param relatedClassFqns FQNs of all classes that reference this term; may be empty
   */
  public TermDetailDialog(String termDisplayName, List<String> relatedClassFqns) {
    setHeaderTitle("Classes using: " + termDisplayName);
    setWidth("600px");
    setResizable(true);

    VerticalLayout content = new VerticalLayout();
    content.setPadding(false);
    content.setSpacing(true);

    if (relatedClassFqns == null || relatedClassFqns.isEmpty()) {
      content.add(new Span("No related classes found"));
    } else {
      Grid<String> grid = new Grid<>();
      grid.addColumn(fqn -> fqn).setHeader("Fully Qualified Name").setAutoWidth(true);
      grid.setItems(relatedClassFqns);
      grid.setHeight("300px");
      content.add(grid);
    }

    add(content);

    Button closeButton = new Button("Close", e -> close());
    getFooter().add(closeButton);
  }
}
