package com.esmp.pilot;

import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener;
import com.vaadin.spring.annotation.SpringView;
import com.vaadin.ui.Button;
import com.vaadin.ui.Grid;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.VerticalLayout;

/**
 * Vaadin 7 view for displaying and managing invoices.
 * Implements the View interface for Vaadin Navigator integration.
 */
@SpringView(name = "invoice")
public class InvoiceView extends VerticalLayout implements View {

    private final InvoiceService invoiceService;
    private Grid invoiceGrid;

    public InvoiceView(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @Override
    public void enter(ViewChangeListener.ViewChangeEvent event) {
        init();
    }

    private void init() {
        removeAllComponents();
        Label title = new Label("Invoice Management");
        invoiceGrid = new Grid("Invoices");
        invoiceGrid.addColumn("id");
        invoiceGrid.addColumn("customerId");
        invoiceGrid.addColumn("amount");
        invoiceGrid.addColumn("status");

        Button createButton = new Button("Create Invoice");
        createButton.addClickListener(e -> openCreateDialog());

        Button refreshButton = new Button("Refresh");
        refreshButton.addClickListener(e -> refreshGrid());

        HorizontalLayout buttonLayout = new HorizontalLayout(createButton, refreshButton);
        addComponents(title, buttonLayout, invoiceGrid);
        refreshGrid();
    }

    private void refreshGrid() {
        invoiceGrid.setItems(invoiceService.findByStatus(InvoiceStatusEnum.SENT));
    }

    private void openCreateDialog() {
        // Open invoice creation form
    }
}
