import cytoscape from 'cytoscape';

const instances = new WeakMap();

window.__initCytoscape = function (element) {
  if (instances.has(element)) return; // already initialized
  const cy = cytoscape({
    container: element,
    elements: [],
    style: [
      {
        selector: 'node',
        style: {
          label: 'data(label)',
          'background-color': 'data(color)',
          width: 'data(size)',
          height: 'data(size)',
          'font-size': '10px',
          'text-valign': 'center',
          'text-halign': 'center',
          'text-wrap': 'ellipsis',
          'text-max-width': '80px',
        },
      },
      {
        selector: 'edge',
        style: {
          width: 'data(width)',
          'line-color': '#94a3b8',
          'target-arrow-color': '#94a3b8',
          'target-arrow-shape': 'triangle',
          'curve-style': 'bezier',
        },
      },
      {
        selector: 'node:selected',
        style: { 'border-width': 3, 'border-color': '#2563eb' },
      },
    ],
    layout: { name: 'cose', animate: false, nodeOverlap: 20, idealEdgeLength: 100 },
  });
  instances.set(element, cy);

  cy.on('tap', 'node', function (evt) {
    const node = evt.target;
    element.dispatchEvent(
      new CustomEvent('node-click', {
        detail: { id: node.id(), type: node.data('type') || '', label: node.data('label') || '' },
        bubbles: true,
      })
    );
  });
};

// Called from Java via getElement().executeJs("window.__setCytoscapeData($0, $1)", element, json)
window.__setCytoscapeData = function (element, elementsJson) {
  const cy = instances.get(element);
  if (cy) {
    const elements = JSON.parse(elementsJson);
    cy.elements().remove();
    cy.add(elements);
    cy.layout({ name: 'cose', animate: false, nodeOverlap: 20, idealEdgeLength: 100 }).run();
    cy.fit(undefined, 30);
  }
};
