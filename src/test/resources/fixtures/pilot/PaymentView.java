package com.esmp.pilot;

import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener;
import com.vaadin.spring.annotation.SpringView;
import com.vaadin.ui.Button;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.Grid;
import com.vaadin.ui.Label;
import com.vaadin.ui.VerticalLayout;

/**
 * Vaadin 7 view for displaying and managing payment transactions.
 */
@SpringView(name = "payment")
public class PaymentView extends VerticalLayout implements View {

    private final PaymentService paymentService;
    private Grid paymentGrid;
    private ComboBox statusFilter;

    public PaymentView(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Override
    public void enter(ViewChangeListener.ViewChangeEvent event) {
        init();
    }

    private void init() {
        removeAllComponents();
        Label title = new Label("Payment Transactions");
        paymentGrid = new Grid("Payments");
        paymentGrid.addColumn("id");
        paymentGrid.addColumn("invoiceId");
        paymentGrid.addColumn("amount");
        paymentGrid.addColumn("currency");
        paymentGrid.addColumn("status");

        statusFilter = new ComboBox("Filter by Status");
        for (PaymentStatusEnum status : PaymentStatusEnum.values()) {
            statusFilter.addItem(status);
        }
        statusFilter.addValueChangeListener(e -> {
            if (e.getProperty().getValue() != null) {
                PaymentStatusEnum selected = (PaymentStatusEnum) e.getProperty().getValue();
                paymentGrid.setItems(paymentService.findByStatus(selected));
            }
        });

        Button refreshButton = new Button("Refresh All");
        refreshButton.addClickListener(e -> paymentGrid.setItems(paymentService.findByStatus(PaymentStatusEnum.COMPLETED)));

        addComponents(title, statusFilter, refreshButton, paymentGrid);
    }
}
