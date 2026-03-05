package com.esmp.ui;

import com.esmp.extraction.model.BusinessTermNode;
import com.esmp.graph.application.LexiconService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.function.SerializablePredicate;
import java.util.List;

/**
 * Vaadin 24 view for browsing, filtering, and curating the domain lexicon.
 *
 * <p>Displays all extracted {@link BusinessTermNode} records in a sortable, filterable data grid.
 * Users can:
 *
 * <ul>
 *   <li>Filter by term name (case-insensitive substring)
 *   <li>Filter by criticality level
 *   <li>Filter by curation status
 *   <li>Click a usage count to see which classes reference the term
 *   <li>Edit a term's definition, criticality, and synonyms inline
 * </ul>
 */
@Route("lexicon")
@PageTitle("Domain Lexicon")
public class LexiconView extends VerticalLayout {

  private final LexiconService lexiconService;

  // Filter state — maintained as a single composite predicate to avoid accumulation
  private String filterName = "";
  private String filterCriticality = null;
  private String filterStatus = null;

  /**
   * Constructs the LexiconView, loading all terms and building the grid.
   *
   * @param lexiconService service providing term read/write operations
   */
  public LexiconView(LexiconService lexiconService) {
    this.lexiconService = lexiconService;

    setSizeFull();
    setPadding(true);

    List<BusinessTermNode> terms = lexiconService.findAll();
    ListDataProvider<BusinessTermNode> dataProvider = new ListDataProvider<>(terms);

    // --- Toolbar ---
    H2 title = new H2("Domain Lexicon");
    Span badge = new Span(terms.size() + " terms");
    badge.getElement().getThemeList().add("badge");
    HorizontalLayout toolbar = new HorizontalLayout(title, badge);
    toolbar.setAlignItems(Alignment.BASELINE);
    add(toolbar);

    // --- Grid ---
    Grid<BusinessTermNode> grid = new Grid<>();
    grid.setSizeFull();
    grid.setDataProvider(dataProvider);

    Grid.Column<BusinessTermNode> termColumn =
        grid.addColumn(BusinessTermNode::getDisplayName)
            .setHeader("Term")
            .setSortable(true)
            .setAutoWidth(true);

    grid.addColumn(BusinessTermNode::getDefinition)
        .setHeader("Definition")
        .setSortable(false)
        .setFlexGrow(3);

    Grid.Column<BusinessTermNode> criticalityColumn =
        grid.addColumn(BusinessTermNode::getCriticality)
            .setHeader("Criticality")
            .setSortable(true)
            .setAutoWidth(true);

    grid.addColumn(BusinessTermNode::getSourceType)
        .setHeader("Source")
        .setSortable(true)
        .setAutoWidth(true);

    // Usage count — clickable button opening TermDetailDialog
    grid.addComponentColumn(
            term -> {
              Button usageBtn =
                  new Button(term.getUsageCount() + " classes");
              usageBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
              usageBtn.addClickListener(
                  e ->
                      lexiconService
                          .findByTermId(term.getTermId())
                          .ifPresent(
                              response -> {
                                TermDetailDialog dialog =
                                    new TermDetailDialog(
                                        term.getDisplayName(), response.relatedClassFqns());
                                dialog.open();
                              }));
              return usageBtn;
            })
        .setHeader("Usage Count")
        .setSortable(true)
        .setComparator(BusinessTermNode::getUsageCount)
        .setAutoWidth(true);

    Grid.Column<BusinessTermNode> statusColumn =
        grid.addColumn(term -> term.isCurated() ? "Curated" : "Auto")
            .setHeader("Status")
            .setSortable(true)
            .setAutoWidth(true);

    // Edit action column
    grid.addComponentColumn(
            term -> {
              Button editBtn = new Button("Edit");
              editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL);
              editBtn.addClickListener(
                  e -> {
                    TermEditorDialog dialog =
                        new TermEditorDialog(
                            term,
                            result -> {
                              lexiconService
                                  .updateTerm(
                                      term.getTermId(),
                                      result.definition(),
                                      result.criticality(),
                                      result.synonyms())
                                  .ifPresent(
                                      updated -> {
                                        term.setDefinition(updated.definition());
                                        term.setCriticality(updated.criticality());
                                        term.setSynonyms(updated.synonyms());
                                        term.setCurated(updated.curated());
                                        term.setStatus(updated.status());
                                        dataProvider.refreshItem(term);
                                        Notification.show("Term updated");
                                      });
                            });
                    dialog.open();
                  });
              return editBtn;
            })
        .setHeader("Actions")
        .setAutoWidth(true);

    // --- Filter header row ---
    TextField nameFilter = new TextField();
    nameFilter.setPlaceholder("Filter by name…");
    nameFilter.setClearButtonVisible(true);
    nameFilter.addValueChangeListener(
        e -> {
          filterName = e.getValue();
          applyFilters(dataProvider);
        });

    ComboBox<String> criticalityFilter = new ComboBox<>();
    criticalityFilter.setItems("Low", "Medium", "High");
    criticalityFilter.setPlaceholder("All");
    criticalityFilter.setClearButtonVisible(true);
    criticalityFilter.addValueChangeListener(
        e -> {
          filterCriticality = e.getValue();
          applyFilters(dataProvider);
        });

    ComboBox<String> statusFilter = new ComboBox<>();
    statusFilter.setItems("Curated", "Auto");
    statusFilter.setPlaceholder("All");
    statusFilter.setClearButtonVisible(true);
    statusFilter.addValueChangeListener(
        e -> {
          filterStatus = e.getValue();
          applyFilters(dataProvider);
        });

    // Append the header row with filter components aligned to Term, Criticality, Status columns
    HeaderRow filterRow = grid.appendHeaderRow();
    filterRow.getCell(termColumn).setComponent(nameFilter);
    filterRow.getCell(criticalityColumn).setComponent(criticalityFilter);
    filterRow.getCell(statusColumn).setComponent(statusFilter);

    add(grid);
  }

  /**
   * Rebuilds and applies the composite filter predicate to the data provider.
   *
   * <p>Uses a single {@code setFilter()} call to avoid filter accumulation — each call replaces
   * the previous composite predicate entirely.
   *
   * @param dataProvider the data provider to filter
   */
  private void applyFilters(ListDataProvider<BusinessTermNode> dataProvider) {
    SerializablePredicate<BusinessTermNode> predicate = term -> true;

    if (filterName != null && !filterName.isBlank()) {
      String lower = filterName.toLowerCase();
      final SerializablePredicate<BusinessTermNode> prev = predicate;
      predicate =
          term ->
              prev.test(term)
                  && term.getDisplayName() != null
                  && term.getDisplayName().toLowerCase().contains(lower);
    }

    if (filterCriticality != null && !filterCriticality.isBlank()) {
      String crit = filterCriticality;
      final SerializablePredicate<BusinessTermNode> prev = predicate;
      predicate = term -> prev.test(term) && crit.equals(term.getCriticality());
    }

    if (filterStatus != null && !filterStatus.isBlank()) {
      boolean wantCurated = "Curated".equals(filterStatus);
      final SerializablePredicate<BusinessTermNode> prev = predicate;
      predicate = term -> prev.test(term) && term.isCurated() == wantCurated;
    }

    dataProvider.setFilter(predicate);
  }
}
