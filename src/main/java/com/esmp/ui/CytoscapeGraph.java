package com.esmp.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.shared.Registration;

/**
 * Reusable Vaadin component wrapping Cytoscape.js for interactive graph visualization.
 *
 * <p>Use {@link #setGraphData(String)} to push a Cytoscape.js elements JSON array to the browser.
 * Register {@link #addNodeClickListener(ComponentEventListener)} to receive click events from the
 * browser.
 *
 * <p>The frontend JS bridge at {@code ./cytoscape-graph.js} initializes a Cytoscape instance on
 * the DOM element using a WeakMap for safe instance management, and dispatches {@code node-click}
 * CustomEvents that flow back to Java via {@link NodeClickEvent}.
 */
@Tag("div")
@NpmPackage(value = "cytoscape", version = "3.33.1")
@JsModule("./cytoscape-graph.js")
public class CytoscapeGraph extends Component implements HasSize {

  private String pendingData;
  private boolean initialized;

  public CytoscapeGraph() {
    setHeight("500px");
    setWidth("100%");
    addAttachListener(e -> {
      getElement().executeJs("window.__initCytoscape($0)", getElement());
      initialized = true;
      if (pendingData != null) {
        getElement()
            .executeJs("window.__setCytoscapeData($0, $1)", getElement(), pendingData);
        pendingData = null;
      }
    });
  }

  /**
   * Pushes graph data to the browser as a Cytoscape.js elements JSON array string. If called
   * before the component is attached, the data is queued and applied on attach.
   *
   * @param elementsJson JSON array of Cytoscape.js node and edge element descriptors
   */
  public void setGraphData(String elementsJson) {
    if (initialized) {
      getElement().executeJs("window.__setCytoscapeData($0, $1)", getElement(), elementsJson);
    } else {
      pendingData = elementsJson;
    }
  }

  /**
   * Registers a listener for {@code node-click} events dispatched from Cytoscape.js in the
   * browser.
   *
   * @param listener event listener receiving {@link NodeClickEvent}
   * @return registration handle for removing the listener
   */
  public Registration addNodeClickListener(ComponentEventListener<NodeClickEvent> listener) {
    return addListener(NodeClickEvent.class, listener);
  }

  /** Server-side event carrying the id, type, and label of the clicked Cytoscape node. */
  @DomEvent("node-click")
  public static class NodeClickEvent extends ComponentEvent<CytoscapeGraph> {

    private final String nodeId;
    private final String nodeType;
    private final String nodeLabel;

    public NodeClickEvent(
        CytoscapeGraph source,
        boolean fromClient,
        @EventData("event.detail.id") String nodeId,
        @EventData("event.detail.type") String nodeType,
        @EventData("event.detail.label") String nodeLabel) {
      super(source, fromClient);
      this.nodeId = nodeId;
      this.nodeType = nodeType;
      this.nodeLabel = nodeLabel;
    }

    public String getNodeId() {
      return nodeId;
    }

    public String getNodeType() {
      return nodeType;
    }

    public String getNodeLabel() {
      return nodeLabel;
    }
  }
}
