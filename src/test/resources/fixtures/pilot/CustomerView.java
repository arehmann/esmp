package com.esmp.pilot;

import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener;
import com.vaadin.spring.annotation.SpringView;
import com.vaadin.ui.Button;
import com.vaadin.ui.Grid;
import com.vaadin.ui.Label;
import com.vaadin.ui.VerticalLayout;

/**
 * Vaadin 7 view for displaying and managing customer accounts.
 */
@SpringView(name = "customer")
public class CustomerView extends VerticalLayout implements View {

    private final CustomerService customerService;
    private Grid customerGrid;

    public CustomerView(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Override
    public void enter(ViewChangeListener.ViewChangeEvent event) {
        init();
    }

    private void init() {
        removeAllComponents();
        Label title = new Label("Customer Management");
        customerGrid = new Grid("Customers");
        customerGrid.addColumn("id");
        customerGrid.addColumn("name");
        customerGrid.addColumn("email");
        customerGrid.addColumn("role");

        Button refreshButton = new Button("Refresh");
        refreshButton.addClickListener(e -> refreshGrid());

        Button createButton = new Button("New Customer");
        createButton.addClickListener(e -> openCreateDialog());

        addComponents(title, refreshButton, createButton, customerGrid);
        refreshGrid();
    }

    private void refreshGrid() {
        customerGrid.setItems(customerService.findByRole(CustomerRole.USER));
    }

    private void openCreateDialog() {
        // Navigate to customer form
    }
}
