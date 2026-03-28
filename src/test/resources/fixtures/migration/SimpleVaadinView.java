package com.example.migration;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;

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
