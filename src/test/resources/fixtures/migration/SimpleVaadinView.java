package com.example.migration;

import com.vaadin.ui.TextField;
import com.vaadin.ui.Button;
import com.vaadin.ui.VerticalLayout;

/**
 * A simple Vaadin 7 view with basic UI components that can be automatically migrated.
 */
public class SimpleVaadinView extends VerticalLayout {

    private TextField nameField = new TextField("Name");
    private Button submitButton = new Button("Submit");

    public SimpleVaadinView() {
        nameField.setInputPrompt("Enter your name");
        submitButton.addClickListener(event -> handleSubmit());
        addComponent(nameField);
        addComponent(submitButton);
    }

    private void handleSubmit() {
        String name = nameField.getValue();
        System.out.println("Submitted: " + name);
    }
}
