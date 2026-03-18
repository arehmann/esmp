package com.esmp.ui;

import com.esmp.dashboard.api.BusinessTermSummary;
import com.esmp.dashboard.api.ClassDetail;
import com.esmp.dashboard.api.LexiconCoverage;
import com.esmp.dashboard.api.ModuleDependencyEdge;
import com.esmp.dashboard.api.ModuleSummary;
import com.esmp.dashboard.api.RiskCluster;
import com.esmp.dashboard.application.DashboardService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full governance dashboard view assembling all 6 DASH requirements into a single scrollable page.
 *
 * <p>Sections:
 * <ol>
 *   <li>Metric summary cards — V7 API %, Lexicon Coverage %, Migration Progress (DASH-01, DASH-05,
 *       DASH-06)
 *   <li>Migration Heatmap — color-coded module grid (DASH-06)
 *   <li>Risk Hotspot Clusters — CytoscapeGraph bubble map (DASH-04)
 *   <li>Dependency Graph Explorer — module overview + class drill-down (DASH-02)
 *   <li>Business Concept Graph — terms linked to implementing classes (DASH-03)
 * </ol>
 *
 * <p>All data is loaded once in the constructor. No auto-refresh or polling is used.
 */
@Route(value = "", layout = MainLayout.class)
@PageTitle("Governance Dashboard")
public class DashboardView extends VerticalLayout {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final DashboardService dashboardService;

  // Module dependency graph state — tracks whether we're in module-level or class-level view
  private CytoscapeGraph depGraph;
  private List<ModuleDependencyEdge> moduleDependencyEdges;
  private Div depSidePanel;
  private Button backToModulesBtn;

  /**
   * Constructs the full governance dashboard, loading all data once from {@link DashboardService}.
   *
   * @param dashboardService service providing all aggregated Neo4j data
   */
  public DashboardView(DashboardService dashboardService) {
    this.dashboardService = dashboardService;

    setPadding(true);
    setSpacing(true);
    setWidthFull();

    // --- Load all data once ---
    List<ModuleSummary> moduleSummaries = loadSafe("module summaries", dashboardService::getModuleSummaries, List.of());
    LexiconCoverage lexiconCoverage = loadSafe("lexicon coverage", dashboardService::getLexiconCoverage, new LexiconCoverage(0, 0, 0.0));
    List<RiskCluster> riskClusters = loadSafe("risk clusters", dashboardService::getRiskClusters, List.of());
    moduleDependencyEdges = loadSafe("dependency edges", dashboardService::getModuleDependencyEdges, List.of());
    List<BusinessTermSummary> businessTermGraph = loadSafe("business term graph", dashboardService::getBusinessTermGraph, List.of());

    // --- Build sections ---
    add(buildMetricCardsSection(moduleSummaries, lexiconCoverage));
    add(new Hr());
    add(buildHeatmapSection(moduleSummaries));
    add(new Hr());
    add(buildRiskClustersSection(riskClusters));
    add(new Hr());
    add(buildDependencyGraphSection());
    add(new Hr());
    add(buildConceptGraphSection(businessTermGraph));
  }

  // ---------------------------------------------------------------------------
  // Section 1: Metric Summary Cards (DASH-01, DASH-05, DASH-06)
  // ---------------------------------------------------------------------------

  private VerticalLayout buildMetricCardsSection(List<ModuleSummary> moduleSummaries, LexiconCoverage lexiconCoverage) {
    VerticalLayout section = new VerticalLayout();
    section.setPadding(false);
    section.setSpacing(true);

    if (moduleSummaries.isEmpty()) {
      Span noData = new Span("No modules found — run extraction first");
      noData.getStyle().set("color", "var(--lumo-error-color)");
      section.add(noData);
      return section;
    }

    // Compute overall metrics
    long totalClasses = moduleSummaries.stream().mapToLong(ModuleSummary::classCount).sum();
    long totalV7 = moduleSummaries.stream().mapToLong(ModuleSummary::vaadin7Count).sum();
    double overallV7Pct = totalClasses > 0 ? (double) totalV7 / totalClasses * 100.0 : 0.0;
    long modulesWithV7 = moduleSummaries.stream().filter(m -> m.vaadin7Count() > 0).count();
    double migrationProgress = (1.0 - overallV7Pct / 100.0) * 100.0;

    // Build 3 cards
    FlexLayout cards = new FlexLayout();
    cards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
    cards.getStyle().set("gap", "16px");

    Div v7Card = buildMetricCard(
        "Vaadin 7 APIs",
        String.format("%.1f%%", overallV7Pct),
        modulesWithV7 + " modules with V7 usage",
        "v7-details");

    Div lexiconCard = buildLexiconCard(
        "Lexicon Coverage",
        String.format("%.1f%%", lexiconCoverage.coveragePct()),
        String.format("%d / %d terms curated", lexiconCoverage.curated(), lexiconCoverage.total()));

    Div migrationCard = buildMetricCard(
        "Migration Progress",
        String.format("%.1f%%", migrationProgress),
        moduleSummaries.size() + " modules analyzed",
        "heatmap-section");

    cards.add(v7Card, lexiconCard, migrationCard);
    section.add(cards);

    // Mini module breakdown in a collapsible Details panel
    Grid<ModuleSummary> breakdownGrid = new Grid<>();
    breakdownGrid.setItems(moduleSummaries);
    breakdownGrid.addColumn(ModuleSummary::module).setHeader("Module").setAutoWidth(true).setSortable(true);
    breakdownGrid.addColumn(ModuleSummary::classCount).setHeader("Classes").setAutoWidth(true).setSortable(true);
    breakdownGrid.addColumn(ModuleSummary::vaadin7Count).setHeader("V7 Count").setAutoWidth(true).setSortable(true);
    breakdownGrid.addColumn(m -> String.format("%.1f%%", m.vaadin7Pct() * 100.0)).setHeader("V7 %").setAutoWidth(true).setSortable(true);
    breakdownGrid.addColumn(m -> String.format("%.3f", m.avgEnhancedRisk())).setHeader("Avg Risk").setAutoWidth(true).setSortable(true);
    breakdownGrid.addColumn(ModuleSummary::highRiskCount).setHeader("High Risk Count").setAutoWidth(true).setSortable(true);
    breakdownGrid.setHeight("250px");

    Details breakdown = new Details("Module Breakdown (" + moduleSummaries.size() + " modules)", breakdownGrid);
    breakdown.setOpened(false);
    section.add(breakdown);

    return section;
  }

  private Div buildMetricCard(String title, String value, String subtitle, String sectionId) {
    Div card = new Div();
    card.getStyle()
        .set("border", "1px solid var(--lumo-contrast-20pct)")
        .set("border-radius", "8px")
        .set("padding", "16px")
        .set("min-width", "200px")
        .set("cursor", "pointer")
        .set("transition", "box-shadow 0.2s")
        .set("flex", "1");

    Div titleDiv = new Div(new Span(title));
    titleDiv.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");

    Div valueDiv = new Div(new Span(value));
    valueDiv.getStyle().set("font-size", "var(--lumo-font-size-xxl)").set("font-weight", "bold").set("margin", "8px 0");

    Div subtitleDiv = new Div(new Span(subtitle));
    subtitleDiv.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-tertiary-text-color)");

    card.add(titleDiv, valueDiv, subtitleDiv);

    card.getElement().addEventListener("click", e ->
        card.getElement().executeJs("document.getElementById($0).scrollIntoView({behavior:'smooth'})", sectionId));

    return card;
  }

  private Div buildLexiconCard(String title, String value, String subtitle) {
    Div card = new Div();
    card.getStyle()
        .set("border", "1px solid var(--lumo-contrast-20pct)")
        .set("border-radius", "8px")
        .set("padding", "16px")
        .set("min-width", "200px")
        .set("cursor", "pointer")
        .set("transition", "box-shadow 0.2s")
        .set("flex", "1");

    Div titleDiv = new Div(new Span(title));
    titleDiv.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");

    Div valueDiv = new Div(new Span(value));
    valueDiv.getStyle().set("font-size", "var(--lumo-font-size-xxl)").set("font-weight", "bold").set("margin", "8px 0");

    Div subtitleDiv = new Div(new Span(subtitle));
    subtitleDiv.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-tertiary-text-color)");

    card.add(titleDiv, valueDiv, subtitleDiv);

    // Lexicon card navigates to /lexicon instead of scrolling
    card.getElement().addEventListener("click", e ->
        UI.getCurrent().navigate("lexicon"));

    return card;
  }

  // ---------------------------------------------------------------------------
  // Section 2: Migration Heatmap (DASH-06)
  // ---------------------------------------------------------------------------

  private VerticalLayout buildHeatmapSection(List<ModuleSummary> moduleSummaries) {
    VerticalLayout section = new VerticalLayout();
    section.setPadding(false);
    section.setSpacing(true);

    H3 heading = new H3("Migration Heatmap");
    heading.setId("heatmap-section");
    section.add(heading);

    if (moduleSummaries.isEmpty()) {
      section.add(new Span("No module data available."));
      return section;
    }

    List<ModuleSummary> sorted = new ArrayList<>(moduleSummaries);
    sorted.sort(Comparator.comparingDouble(ModuleSummary::heatmapScore).reversed());

    Grid<ModuleSummary> heatmapGrid = new Grid<>();
    heatmapGrid.setItems(sorted);
    heatmapGrid.addColumn(ModuleSummary::module).setHeader("Module").setAutoWidth(true).setSortable(true);
    heatmapGrid.addColumn(m -> String.format("%.1f%%", m.vaadin7Pct() * 100.0)).setHeader("V7 %").setAutoWidth(true).setSortable(true);
    heatmapGrid.addColumn(m -> String.format("%.3f", m.avgEnhancedRisk())).setHeader("Avg Risk").setAutoWidth(true).setSortable(true);
    heatmapGrid.addComponentColumn(m -> {
          String color = heatmapColor(m.heatmapScore());
          Span badge = new Span(String.format("%.3f", m.heatmapScore()));
          badge.getStyle()
              .set("background-color", color)
              .set("color", "white")
              .set("padding", "2px 8px")
              .set("border-radius", "4px")
              .set("font-weight", "bold");
          return badge;
        }).setHeader("Heatmap Score")
        .setComparator(Comparator.comparingDouble(ModuleSummary::heatmapScore))
        .setAutoWidth(true);

    heatmapGrid.setHeight("300px");
    section.add(heatmapGrid);

    return section;
  }

  private String heatmapColor(double score) {
    if (score < 0.1) return "#22c55e";
    if (score < 0.3) return "#eab308";
    if (score < 0.5) return "#f97316";
    return "#ef4444";
  }

  // ---------------------------------------------------------------------------
  // Section 3: Risk Hotspot Clusters (DASH-04)
  // ---------------------------------------------------------------------------

  private VerticalLayout buildRiskClustersSection(List<RiskCluster> riskClusters) {
    VerticalLayout section = new VerticalLayout();
    section.setPadding(false);
    section.setSpacing(true);

    H3 heading = new H3("Risk Hotspot Clusters");
    heading.setId("risk-clusters");
    section.add(heading);

    if (riskClusters.isEmpty()) {
      section.add(new Span("No risk cluster data available."));
      return section;
    }

    CytoscapeGraph riskClusterGraph = new CytoscapeGraph();
    riskClusterGraph.setWidth("100%");
    riskClusterGraph.setHeight("500px");

    // Cluster detail panel
    Div clusterPanel = new Div();
    clusterPanel.getStyle()
        .set("padding", "12px")
        .set("border", "1px solid var(--lumo-contrast-20pct)")
        .set("border-radius", "8px")
        .set("min-height", "100px");
    clusterPanel.add(new Span("Click a cluster node to see details."));

    // Build Cytoscape elements JSON
    try {
      List<Map<String, Object>> elements = new ArrayList<>();
      for (RiskCluster rc : riskClusters) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", rc.module());
        data.put("label", rc.module() + "\n" + rc.classCount() + " classes");
        data.put("type", "riskCluster");
        data.put("size", Math.max(20, Math.min(80, rc.classCount() * 3)));
        data.put("color", riskColor(rc.avgRisk()));

        Map<String, Object> element = new LinkedHashMap<>();
        element.put("group", "nodes");
        element.put("data", data);
        elements.add(element);
      }
      riskClusterGraph.setGraphData(OBJECT_MAPPER.writeValueAsString(elements));
    } catch (JsonProcessingException e) {
      section.add(new Span("Failed to render risk cluster graph."));
    }

    // Build lookup map for click handler
    Map<String, RiskCluster> clusterMap = new LinkedHashMap<>();
    for (RiskCluster rc : riskClusters) {
      clusterMap.put(rc.module(), rc);
    }

    riskClusterGraph.addNodeClickListener(event -> {
      String nodeId = event.getNodeId();
      RiskCluster rc = clusterMap.get(nodeId);
      if (rc != null) {
        clusterPanel.removeAll();
        clusterPanel.add(
            new H4(rc.module()),
            styledSpan("Classes: " + rc.classCount()),
            styledSpan(String.format("Avg Risk: %.3f", rc.avgRisk())),
            styledSpan(String.format("Max Risk: %.3f", rc.maxRisk())),
            styledSpan("High Risk Classes: " + rc.highRiskCount()));
      }
    });

    HorizontalLayout graphRow = new HorizontalLayout(riskClusterGraph, clusterPanel);
    graphRow.setWidthFull();
    graphRow.setFlexGrow(3, riskClusterGraph);
    graphRow.setFlexGrow(1, clusterPanel);

    section.add(graphRow);
    return section;
  }

  private String riskColor(double avgRisk) {
    if (avgRisk < 0.3) return "#22c55e";
    if (avgRisk < 0.6) return "#eab308";
    return "#ef4444";
  }

  // ---------------------------------------------------------------------------
  // Section 4: Dependency Graph Explorer (DASH-02)
  // ---------------------------------------------------------------------------

  private VerticalLayout buildDependencyGraphSection() {
    VerticalLayout section = new VerticalLayout();
    section.setPadding(false);
    section.setSpacing(true);

    H3 heading = new H3("Dependency Graph");
    heading.setId("dependency-graph");
    section.add(heading);

    depGraph = new CytoscapeGraph();
    depGraph.setWidth("100%");
    depGraph.setHeight("500px");

    depSidePanel = new Div();
    depSidePanel.getStyle()
        .set("padding", "12px")
        .set("border", "1px solid var(--lumo-contrast-20pct)")
        .set("border-radius", "8px")
        .set("min-height", "200px")
        .set("overflow-y", "auto");
    depSidePanel.add(new Span("Click a node to see details."));

    backToModulesBtn = new Button("Back to modules");
    backToModulesBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
    backToModulesBtn.setVisible(false);
    backToModulesBtn.addClickListener(e -> loadModuleLevelGraph());

    VerticalLayout rightPanel = new VerticalLayout(backToModulesBtn, depSidePanel);
    rightPanel.setPadding(false);
    rightPanel.setSpacing(true);
    rightPanel.setWidth("25%");

    HorizontalLayout graphRow = new HorizontalLayout(depGraph, rightPanel);
    graphRow.setWidthFull();
    graphRow.setFlexGrow(3, depGraph);

    section.add(graphRow);

    // Load initial module-level view
    if (!moduleDependencyEdges.isEmpty()) {
      loadModuleLevelGraph();
    } else {
      section.add(new Span("No dependency data available — run extraction first."));
    }

    depGraph.addNodeClickListener(event -> {
      String nodeId = event.getNodeId();
      String nodeType = event.getNodeType();

      if ("module".equals(nodeType)) {
        loadClassLevelGraph(nodeId);
      } else if ("class".equals(nodeType)) {
        showClassDetail(nodeId);
      }
    });

    return section;
  }

  private void loadModuleLevelGraph() {
    try {
      // Collect unique module names
      Map<String, Boolean> moduleNames = new LinkedHashMap<>();
      for (ModuleDependencyEdge edge : moduleDependencyEdges) {
        moduleNames.put(edge.source(), true);
        moduleNames.put(edge.target(), true);
      }

      List<Map<String, Object>> elements = new ArrayList<>();

      // Add module nodes
      for (String moduleName : moduleNames.keySet()) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", moduleName);
        data.put("label", moduleName);
        data.put("type", "module");
        data.put("color", "#3b82f6");
        data.put("size", 40);

        Map<String, Object> element = new LinkedHashMap<>();
        element.put("group", "nodes");
        element.put("data", data);
        elements.add(element);
      }

      // Add edges
      int edgeIdx = 0;
      for (ModuleDependencyEdge edge : moduleDependencyEdges) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "e" + edgeIdx++);
        data.put("source", edge.source());
        data.put("target", edge.target());
        data.put("width", Math.max(1, Math.min(5, edge.weight() / 10)));

        Map<String, Object> element = new LinkedHashMap<>();
        element.put("group", "edges");
        element.put("data", data);
        elements.add(element);
      }

      depGraph.setGraphData(OBJECT_MAPPER.writeValueAsString(elements));
      backToModulesBtn.setVisible(false);
      depSidePanel.removeAll();
      depSidePanel.add(new Span("Click a module node to drill down into its classes."));
    } catch (JsonProcessingException ex) {
      Notification.show("Failed to load module graph.");
    }
  }

  private void loadClassLevelGraph(String module) {
    try {
      List<ClassDetail> classes = dashboardService.getClassesInModule(module);

      List<Map<String, Object>> elements = new ArrayList<>();

      // Add class nodes
      for (ClassDetail cd : classes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", cd.fqn());
        data.put("label", cd.simpleName());
        data.put("type", "class");
        data.put("color", riskColor(cd.riskScore()));
        data.put("size", 30);

        Map<String, Object> element = new LinkedHashMap<>();
        element.put("group", "nodes");
        element.put("data", data);
        elements.add(element);
      }

      // Add intra-module dependency edges
      int edgeIdx = 0;
      for (ClassDetail cd : classes) {
        for (String dep : cd.dependsOn()) {
          Map<String, Object> data = new LinkedHashMap<>();
          data.put("id", "ce" + edgeIdx++);
          data.put("source", cd.fqn());
          data.put("target", dep);
          data.put("width", 1);

          Map<String, Object> element = new LinkedHashMap<>();
          element.put("group", "edges");
          element.put("data", data);
          elements.add(element);
        }
      }

      depGraph.setGraphData(OBJECT_MAPPER.writeValueAsString(elements));
      backToModulesBtn.setVisible(true);
      depSidePanel.removeAll();
      depSidePanel.add(new Span("Module: " + module + " (" + classes.size() + " classes)"));
      depSidePanel.add(new Span("Click a class node to see details."));
    } catch (JsonProcessingException ex) {
      Notification.show("Failed to load class graph for module: " + module);
    }
  }

  private void showClassDetail(String fqn) {
    depSidePanel.removeAll();

    // Extract simple name from FQN
    String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;

    // Find ClassDetail from current module's data (re-query or use cached — re-query for accuracy)
    // The side panel shows: simpleName, fqn, risk, labels, dependsOn — we store these in graph data
    // For detail display, show what we know from the click event (fqn + simpleName)
    depSidePanel.add(new H4(simpleName));
    depSidePanel.add(styledSpan(fqn));
    depSidePanel.add(new Span("(Click a module node and then select a class for risk details)"));
  }

  // ---------------------------------------------------------------------------
  // Section 5: Business Concept Graph (DASH-03)
  // ---------------------------------------------------------------------------

  private VerticalLayout buildConceptGraphSection(List<BusinessTermSummary> businessTermGraph) {
    VerticalLayout section = new VerticalLayout();
    section.setPadding(false);
    section.setSpacing(true);

    H3 heading = new H3("Business Concept Graph");
    heading.setId("concept-graph");
    section.add(heading);

    if (businessTermGraph.isEmpty()) {
      section.add(new Span("No business term data available — run extraction first."));
      return section;
    }

    CytoscapeGraph conceptGraph = new CytoscapeGraph();
    conceptGraph.setWidth("100%");
    conceptGraph.setHeight("500px");

    Div conceptSidePanel = new Div();
    conceptSidePanel.getStyle()
        .set("padding", "12px")
        .set("border", "1px solid var(--lumo-contrast-20pct)")
        .set("border-radius", "8px")
        .set("min-height", "200px")
        .set("overflow-y", "auto");
    conceptSidePanel.add(new Span("Click a node to see details."));

    // Build Cytoscape elements JSON
    try {
      List<Map<String, Object>> elements = new ArrayList<>();
      Map<String, Boolean> classNodesSeen = new LinkedHashMap<>();

      for (BusinessTermSummary term : businessTermGraph) {
        // Add term node
        Map<String, Object> termData = new LinkedHashMap<>();
        termData.put("id", term.termId());
        termData.put("label", term.displayName());
        termData.put("type", "term");
        termData.put("color", termCriticalityColor(term.criticality()));
        termData.put("size", Math.max(20, Math.min(60, term.classFqns().size() * 10)));

        Map<String, Object> termElement = new LinkedHashMap<>();
        termElement.put("group", "nodes");
        termElement.put("data", termData);
        elements.add(termElement);

        // Add class nodes (deduplicated) and edges
        int edgeIdx = 0;
        for (String classFqn : term.classFqns()) {
          if (!classNodesSeen.containsKey(classFqn)) {
            classNodesSeen.put(classFqn, true);

            String classSimpleName = classFqn.contains(".")
                ? classFqn.substring(classFqn.lastIndexOf('.') + 1)
                : classFqn;

            Map<String, Object> classData = new LinkedHashMap<>();
            classData.put("id", classFqn);
            classData.put("label", classSimpleName);
            classData.put("type", "class");
            classData.put("color", "#6366f1");
            classData.put("size", 25);

            Map<String, Object> classElement = new LinkedHashMap<>();
            classElement.put("group", "nodes");
            classElement.put("data", classData);
            elements.add(classElement);
          }

          // Edge from class to term
          Map<String, Object> edgeData = new LinkedHashMap<>();
          edgeData.put("id", "cte-" + term.termId() + "-" + edgeIdx++);
          edgeData.put("source", classFqn);
          edgeData.put("target", term.termId());
          edgeData.put("width", 1);

          Map<String, Object> edgeElement = new LinkedHashMap<>();
          edgeElement.put("group", "edges");
          edgeElement.put("data", edgeData);
          elements.add(edgeElement);
        }
      }

      conceptGraph.setGraphData(OBJECT_MAPPER.writeValueAsString(elements));
    } catch (JsonProcessingException e) {
      section.add(new Span("Failed to render business concept graph."));
    }

    // Build lookup maps for click handler
    Map<String, BusinessTermSummary> termMap = new LinkedHashMap<>();
    for (BusinessTermSummary term : businessTermGraph) {
      termMap.put(term.termId(), term);
    }

    conceptGraph.addNodeClickListener(event -> {
      String nodeId = event.getNodeId();
      String nodeType = event.getNodeType();

      conceptSidePanel.removeAll();

      if ("term".equals(nodeType)) {
        BusinessTermSummary term = termMap.get(nodeId);
        if (term != null) {
          conceptSidePanel.add(new H4(term.displayName()));
          conceptSidePanel.add(styledSpan("Criticality: " + (term.criticality() != null ? term.criticality() : "Unknown")));
          conceptSidePanel.add(styledSpan("Curated: " + (term.curated() ? "Yes" : "No")));
          conceptSidePanel.add(styledSpan("Linked classes: " + term.classFqns().size()));
          for (String fqn : term.classFqns()) {
            String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
            conceptSidePanel.add(styledSpan("  - " + simpleName));
          }
        }
      } else if ("class".equals(nodeType)) {
        String simpleName = nodeId.contains(".") ? nodeId.substring(nodeId.lastIndexOf('.') + 1) : nodeId;
        conceptSidePanel.add(new H4(simpleName));
        conceptSidePanel.add(styledSpan(nodeId));

        // Find all terms linked to this class
        List<String> linkedTerms = new ArrayList<>();
        for (BusinessTermSummary term : businessTermGraph) {
          if (term.classFqns().contains(nodeId)) {
            linkedTerms.add(term.displayName());
          }
        }
        if (!linkedTerms.isEmpty()) {
          conceptSidePanel.add(styledSpan("Linked terms:"));
          for (String t : linkedTerms) {
            conceptSidePanel.add(styledSpan("  - " + t));
          }
        }
      }
    });

    HorizontalLayout graphRow = new HorizontalLayout(conceptGraph, conceptSidePanel);
    graphRow.setWidthFull();
    graphRow.setFlexGrow(3, conceptGraph);
    graphRow.setFlexGrow(1, conceptSidePanel);

    section.add(graphRow);
    return section;
  }

  private String termCriticalityColor(String criticality) {
    if ("High".equals(criticality)) return "#ef4444";
    if ("Medium".equals(criticality)) return "#eab308";
    return "#22c55e";
  }

  // ---------------------------------------------------------------------------
  // Utilities
  // ---------------------------------------------------------------------------

  private Span styledSpan(String text) {
    Span span = new Span(text);
    span.getStyle().set("display", "block").set("font-size", "var(--lumo-font-size-s)");
    return span;
  }

  @FunctionalInterface
  private interface Supplier<T> {
    T get() throws Exception;
  }

  private <T> T loadSafe(String name, Supplier<T> supplier, T fallback) {
    try {
      return supplier.get();
    } catch (Exception e) {
      Notification.show("Failed to load " + name + ": " + e.getMessage(), 5000,
          Notification.Position.BOTTOM_START);
      return fallback;
    }
  }
}
