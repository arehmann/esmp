package com.esmp.pilot;

import com.vaadin.data.fieldgroup.BeanFieldGroup;
import com.vaadin.data.fieldgroup.FieldGroup;
import com.vaadin.ui.Button;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.Notification;
import com.vaadin.ui.TextField;

/**
 * Vaadin 7 form for creating and editing customer records.
 * Uses BeanFieldGroup for data binding between form fields and CustomerEntity.
 */
public class CustomerForm extends FormLayout {

    private final BeanFieldGroup<CustomerEntity> binder;
    private final CustomerService customerService;

    private TextField nameField;
    private TextField emailField;
    private ComboBox roleCombo;

    public CustomerForm(CustomerService customerService) {
        this.customerService = customerService;
        this.binder = new BeanFieldGroup<>(CustomerEntity.class);
        buildLayout();
    }

    private void buildLayout() {
        nameField = new TextField("Name");
        emailField = new TextField("Email");
        roleCombo = new ComboBox("Role");

        for (CustomerRole role : CustomerRole.values()) {
            roleCombo.addItem(role);
            roleCombo.setItemCaption(role, role.name());
        }

        binder.bind(nameField, "name");
        binder.bind(emailField, "email");
        binder.bind(roleCombo, "role");

        Button saveButton = new Button("Save");
        saveButton.addClickListener(e -> save());

        Button clearButton = new Button("Clear");
        clearButton.addClickListener(e -> binder.discard());

        addComponents(nameField, emailField, roleCombo, saveButton, clearButton);
    }

    public void setCustomer(CustomerEntity customer) {
        binder.setItemDataSource(customer);
    }

    private void save() {
        try {
            binder.commit();
            CustomerEntity customer = binder.getItemDataSource().getBean();
            customerService.createCustomer(
                    customer.getName(),
                    customer.getEmail(),
                    customer.getRole());
            Notification.show("Customer saved successfully");
        } catch (FieldGroup.CommitException e) {
            Notification.show("Validation error: " + e.getMessage());
        }
    }
}
