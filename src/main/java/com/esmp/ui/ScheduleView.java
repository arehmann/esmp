package com.esmp.ui;

import com.esmp.dashboard.api.ModuleDependencyEdge;
import com.esmp.dashboard.application.DashboardService;
import com.esmp.scheduling.api.ModuleSchedule;
import com.esmp.scheduling.api.ScheduleResponse;
import com.esmp.scheduling.api.WaveGroup;
import com.esmp.scheduling.application.SchedulingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vaadin view displaying the risk-prioritized migration schedule.
 *
 * <p>Two view modes:
 * <ul>
 *   <li><b>Wave View</b> — modules grouped into wave lanes as color-coded cards
 *   <li><b>Table View</b> — sortable {@link Grid} with all module metrics
 * </ul>
 *
 * <p>Clicking a module card or grid row opens a drill-down panel showing the score breakdown
 * and a {@link CytoscapeGraph} with all modules colored by their wave relative to the selected
 * module (green = earlier wave, blue = current, red = later wave).
 */
@Route(value = "schedule", layout = MainLayout.class)
@PageTitle("Migration Schedule")
public class ScheduleView extends VerticalLayout {

  private static final Logger log = LoggerFactory.getLogger(ScheduleView.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final SchedulingService schedulingService;
  private final DashboardService dashboardService;

  private ScheduleResponse currentResponse;
  private List<ModuleDependencyEdge> dependencyEdges;

  private VerticalLayout contentArea;
  private Button waveViewBtn;
  private Button tableViewBtn;
  private HorizontalLayout toggleBtns;
  private Div detailPanel;
  private CytoscapeGraph drillDownGraph;
  private boolean isWaveView = true;

  /**
   * Constructs the schedule view.
   *
   * @param schedulingService provides wave-based scheduling recommendations
   * @param dashboardService  provides module dependency edges for the drill-down graph
   */
  public ScheduleView(SchedulingService schedulingService, DashboardService dashboardService) {
    this.schedulingService = schedulingService;
    this.dashboardService = dashboardService;

    setPadding(true);
    setSpacing(true);
    setWidthFull();

    // --- Header ---
    H3 header = new H3("Migration Schedule");

    // --- Generate button ---
    Button generateBtn = new Button("Generate Schedule");
    generateBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

    // --- Loading indicator ---
    Span loadingSpan = new Span("Generating schedule...");
    loadingSpan.setVisible(false);

    // --- View toggle buttons ---
    waveViewBtn = new Button("Wave View");
    waveViewBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
    tableViewBtn = new Button("Table View");

    toggleBtns = new HorizontalLayout(waveViewBtn, tableViewBtn);
    toggleBtns.setSpacing(true);
    toggleBtns.setVisible(false);

    // --- Content area ---
    contentArea = new VerticalLayout();
    contentArea.setPadding(false);
    contentArea.setSpacing(true);

    // --- Drill-down panel and graph ---
    drillDownGraph = new CytoscapeGraph();
    drillDownGraph.setWidth("100%");
    drillDownGraph.setHeight("400px");

    detailPanel = new Div();
    detailPanel.setWidthFull();
    detailPanel.setVisible(false);

    // --- Wire up actions ---
    generateBtn.addClickListener(e -> {
      generateBtn.setEnabled(false);
      loadingSpan.setVisible(true);
      try {
        currentResponse = schedulingService.recommend("");
        dependencyEdges = loadSafe("dependency edges", dashboardService::getModuleDependencyEdges, List.of());
        toggleBtns.setVisible(true);
        isWaveView = true;
        waveViewBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        tableViewBtn.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
        showWaveView();
      } catch (Exception ex) {
        Notification.show("Error generating schedule: " + ex.getMessage(), 5000,
            Notification.Position.MIDDLE);
        log.error("Error generating schedule", ex);
      } finally {
        generateBtn.setEnabled(true);
        loadingSpan.setVisible(false);
      }
    });

    waveViewBtn.addClickListener(e -> {
      isWaveView = true;
      waveViewBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
      tableViewBtn.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
      showWaveView();
    });

    tableViewBtn.addClickListener(e -> {
      isWaveView = false;
      tableViewBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
      waveViewBtn.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
      showTableView();
    });

    add(header, generateBtn, loadingSpan, toggleBtns, contentArea);
  }

  // ---------------------------------------------------------------------------
  // Wave view
  // ---------------------------------------------------------------------------

  private void showWaveView() {
    contentArea.removeAll();
    detailPanel.setVisible(false);

    if (currentResponse == null || currentResponse.waves().isEmpty()) {
      contentArea.add(new Span("No schedule data available — click Generate Schedule first."));
      contentArea.add(detailPanel);
      return;
    }

    for (WaveGroup wg : currentResponse.waves()) {
      H4 waveHeader = new H4("Wave " + wg.waveNumber());

      FlexLayout waveCards = new FlexLayout();
      waveCards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
      waveCards.getStyle().set("gap", "16px");

      for (ModuleSchedule ms : wg.modules()) {
        Div card = buildModuleCard(ms);
        card.addClickListener(ev -> showModuleDrillDown(ms));
        waveCards.add(card);
      }

      contentArea.add(waveHeader, waveCards);
    }

    contentArea.add(detailPanel);
  }

  private Div buildModuleCard(ModuleSchedule ms) {
    Div card = new Div();

    String bgColor;
    if (ms.finalScore() < 0.3) {
      bgColor = "#E8F5E9"; // light green — safe
    } else if (ms.finalScore() < 0.6) {
      bgColor = "#FFF8E1"; // light amber — moderate
    } else {
      bgColor = "#FFEBEE"; // light red — complex
    }

    card.getStyle()
        .set("background-color", bgColor)
        .set("padding", "12px")
        .set("border-radius", "8px")
        .set("box-shadow", "0 1px 3px rgba(0,0,0,0.15)")
        .set("min-width", "200px")
        .set("cursor", "pointer");

    Span nameSpan = new Span(ms.module());
    nameSpan.getStyle().set("font-weight", "bold").set("display", "block");

    Span scoreSpan = new Span(String.format("Score: %.3f", ms.finalScore()));
    scoreSpan.getStyle().set("display", "block").set("font-size", "var(--lumo-font-size-s)");

    Span classSpan = new Span("Classes: " + ms.classCount());
    classSpan.getStyle().set("display", "block").set("font-size", "var(--lumo-font-size-s)");

    String rationaleSnippet = ms.rationale().length() > 80
        ? ms.rationale().substring(0, 80) + "..."
        : ms.rationale();
    Span rationaleSpan = new Span(rationaleSnippet);
    rationaleSpan.getStyle().set("display", "block")
        .set("font-size", "var(--lumo-font-size-xs)")
        .set("color", "var(--lumo-secondary-text-color)");

    card.add(nameSpan, scoreSpan, classSpan, rationaleSpan);
    return card;
  }

  // ---------------------------------------------------------------------------
  // Table view
  // ---------------------------------------------------------------------------

  private void showTableView() {
    contentArea.removeAll();
    detailPanel.setVisible(false);

    if (currentResponse == null || currentResponse.flatRanking().isEmpty()) {
      contentArea.add(new Span("No schedule data available — click Generate Schedule first."));
      contentArea.add(detailPanel);
      return;
    }

    Grid<ModuleSchedule> grid = new Grid<>();
    grid.addColumn(ModuleSchedule::waveNumber).setHeader("Wave").setAutoWidth(true).setSortable(true);
    grid.addColumn(ModuleSchedule::module).setHeader("Module").setAutoWidth(true).setSortable(true);
    grid.addColumn(ms -> String.format("%.3f", ms.finalScore())).setHeader("Score").setAutoWidth(true).setSortable(true).setComparator(ModuleSchedule::finalScore);
    grid.addColumn(ms -> String.format("%.3f", ms.avgEnhancedRisk())).setHeader("Risk").setAutoWidth(true).setSortable(true).setComparator(ModuleSchedule::avgEnhancedRisk);
    grid.addColumn(ModuleSchedule::dependentCount).setHeader("Dependents").setAutoWidth(true).setSortable(true);
    grid.addColumn(ModuleSchedule::commitCount).setHeader("Commits").setAutoWidth(true).setSortable(true);
    grid.addColumn(ms -> String.format("%.1f", ms.avgComplexity())).setHeader("Avg CC").setAutoWidth(true).setSortable(true).setComparator(ModuleSchedule::avgComplexity);
    grid.addColumn(ModuleSchedule::classCount).setHeader("Classes").setAutoWidth(true).setSortable(true);
    grid.addColumn(ModuleSchedule::rationale).setHeader("Rationale").setAutoWidth(true);

    grid.setItems(currentResponse.flatRanking());
    grid.setSelectionMode(Grid.SelectionMode.SINGLE);
    grid.addSelectionListener(event ->
        event.getFirstSelectedItem().ifPresent(this::showModuleDrillDown));

    contentArea.add(grid, detailPanel);
  }

  // ---------------------------------------------------------------------------
  // Module drill-down
  // ---------------------------------------------------------------------------

  private void showModuleDrillDown(ModuleSchedule selected) {
    detailPanel.removeAll();
    detailPanel.setVisible(true);

    detailPanel.add(new H4("Module: " + selected.module() + " (Wave " + selected.waveNumber() + ")"));

    // Score breakdown
    Div breakdownDiv = new Div();
    breakdownDiv.getStyle().set("font-size", "var(--lumo-font-size-s)").set("margin-bottom", "8px");
    breakdownDiv.add(new Span(String.format(
        "Risk: %.3f | Dependency: %.3f | Frequency: %.3f | Complexity: %.3f | Final: %.3f",
        selected.riskContribution(),
        selected.dependencyContribution(),
        selected.frequencyContribution(),
        selected.complexityContribution(),
        selected.finalScore())));
    detailPanel.add(breakdownDiv);

    // Rationale
    Span rationaleSpan = new Span(selected.rationale());
    rationaleSpan.getStyle().set("font-style", "italic")
        .set("display", "block")
        .set("font-size", "var(--lumo-font-size-s)")
        .set("color", "var(--lumo-secondary-text-color)")
        .set("margin-bottom", "12px");
    detailPanel.add(rationaleSpan);

    // Build CytoscapeGraph JSON
    String graphJson = buildWaveGraphJson(selected);
    drillDownGraph.setGraphData(graphJson);
    detailPanel.add(drillDownGraph);

    // Legend
    Span legend = new Span("Green = earlier wave (safe dependency) | Blue = current wave | Red = later wave (risky)");
    legend.getStyle().set("font-size", "var(--lumo-font-size-xs)")
        .set("color", "var(--lumo-secondary-text-color)")
        .set("display", "block")
        .set("margin-top", "8px");
    detailPanel.add(legend);
  }

  private String buildWaveGraphJson(ModuleSchedule selected) {
    if (currentResponse == null) {
      return "[]";
    }

    try {
      List<Map<String, Object>> elements = new ArrayList<>();

      // Add a node for each module in the flat ranking
      for (ModuleSchedule ms : currentResponse.flatRanking()) {
        String color;
        if (ms.waveNumber() < selected.waveNumber()) {
          color = "#4CAF50"; // green — earlier wave, safe dependency
        } else if (ms.waveNumber() > selected.waveNumber()) {
          color = "#F44336"; // red — later wave, risky
        } else {
          color = "#2196F3"; // blue — same wave (current)
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", ms.module());
        data.put("label", ms.module() + " (W" + ms.waveNumber() + ")");
        data.put("color", color);
        data.put("size", 35);
        data.put("type", "module");

        Map<String, Object> element = new LinkedHashMap<>();
        element.put("group", "nodes");
        element.put("data", data);
        elements.add(element);
      }

      // Add dependency edges
      if (dependencyEdges != null) {
        int edgeIdx = 0;
        for (ModuleDependencyEdge edge : dependencyEdges) {
          Map<String, Object> edgeData = new LinkedHashMap<>();
          edgeData.put("id", "we" + edgeIdx++);
          edgeData.put("source", edge.source());
          edgeData.put("target", edge.target());
          edgeData.put("width", 1);

          Map<String, Object> edgeElement = new LinkedHashMap<>();
          edgeElement.put("group", "edges");
          edgeElement.put("data", edgeData);
          elements.add(edgeElement);
        }
      }

      return OBJECT_MAPPER.writeValueAsString(elements);
    } catch (JsonProcessingException e) {
      log.error("Failed to build wave graph JSON", e);
      return "[]";
    }
  }

  // ---------------------------------------------------------------------------
  // Utilities
  // ---------------------------------------------------------------------------

  @FunctionalInterface
  private interface Supplier<T> {
    T get() throws Exception;
  }

  private <T> T loadSafe(String name, Supplier<T> supplier, T fallback) {
    try {
      return supplier.get();
    } catch (Exception e) {
      log.error("Failed to load {}: {}", name, e.getMessage());
      return fallback;
    }
  }
}
