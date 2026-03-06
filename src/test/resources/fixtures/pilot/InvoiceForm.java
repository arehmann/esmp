package com.esmp.pilot;

import com.vaadin.data.fieldgroup.BeanFieldGroup;
import com.vaadin.data.fieldgroup.FieldGroup;
import com.vaadin.ui.Button;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.Notification;
import com.vaadin.ui.TextField;

/**
 * Vaadin 7 form for creating and editing invoice records.
 * Uses BeanFieldGroup for data binding between form fields and InvoiceEntity.
 */
public class InvoiceForm extends FormLayout {

    private final BeanFieldGroup<InvoiceEntity> binder;
    private final InvoiceService invoiceService;

    private TextField customerIdField;
    private TextField amountField;
    private TextField descriptionField;

    public InvoiceForm(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
        this.binder = new BeanFieldGroup<>(InvoiceEntity.class);
        buildLayout();
    }

    private void buildLayout() {
        customerIdField = new TextField("Customer ID");
        amountField = new TextField("Amount");
        descriptionField = new TextField("Description");

        binder.bind(customerIdField, "customerId");
        binder.bind(amountField, "amount");
        binder.bind(descriptionField, "description");

        Button saveButton = new Button("Save");
        saveButton.addClickListener(e -> save());

        Button cancelButton = new Button("Cancel");
        cancelButton.addClickListener(e -> cancel());

        addComponents(customerIdField, amountField, descriptionField, saveButton, cancelButton);
    }

    public void setInvoice(InvoiceEntity invoice) {
        binder.setItemDataSource(invoice);
    }

    private void save() {
        try {
            binder.commit();
            InvoiceEntity invoice = binder.getItemDataSource().getBean();
            invoiceService.createInvoice(
                    invoice.getCustomerId(),
                    invoice.getAmount(),
                    invoice.getDescription());
            Notification.show("Invoice saved successfully");
        } catch (FieldGroup.CommitException e) {
            Notification.show("Validation error: " + e.getMessage());
        }
    }

    private void cancel() {
        binder.discard();
    }
}
