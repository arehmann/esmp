package com.example.migration;

import com.vaadin.ui.TextField;
import com.vaadin.ui.Button;
import com.vaadin.ui.VerticalLayout;

/**
 * A Vaadin 7 view fixture used for testing OpenRewrite recipe execution.
 *
 * <p>This file contains Vaadin 7 UI component imports that map to Vaadin 24 FQNs
 * via CHANGE_TYPE migration actions (automatable=YES):
 * <ul>
 *   <li>com.vaadin.ui.TextField → com.vaadin.flow.component.textfield.TextField
 *   <li>com.vaadin.ui.Button → com.vaadin.flow.component.button.Button
 *   <li>com.vaadin.ui.VerticalLayout → com.vaadin.flow.component.orderedlayout.VerticalLayout
 * </ul>
 *
 * <p>After recipe execution, the imports and all type references should be updated to Vaadin 24.
 */
public class RecipeTargetView extends VerticalLayout {

    private TextField usernameField = new TextField("Username");
    private TextField emailField = new TextField("Email");
    private Button saveButton = new Button("Save");

    public RecipeTargetView() {
        usernameField.setWidth("300px");
        emailField.setWidth("300px");
        saveButton.addClickListener(event -> save());
        addComponent(usernameField);
        addComponent(emailField);
        addComponent(saveButton);
    }

    private void save() {
        String username = usernameField.getValue();
        String email = emailField.getValue();
        System.out.println("Saving user: " + username + " / " + email);
    }
}
