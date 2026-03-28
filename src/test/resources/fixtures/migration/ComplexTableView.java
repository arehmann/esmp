package com.example.migration;

import com.vaadin.ui.Table;
import com.vaadin.ui.Button;
import com.vaadin.data.util.BeanItemContainer;
import com.vaadin.data.fieldgroup.BeanFieldGroup;

import java.util.List;

/**
 * A complex Vaadin 7 view using Table, BeanItemContainer, and BeanFieldGroup
 * which require AI-assisted migration (auto=NO).
 */
public class ComplexTableView {

    private Table dataTable = new Table("Data");
    private BeanItemContainer<Object> container = new BeanItemContainer<>(Object.class);
    private BeanFieldGroup<Object> fieldGroup = new BeanFieldGroup<>(Object.class);

    public ComplexTableView() {
        dataTable.setContainerDataSource(container);
        dataTable.addContainerProperty("name", String.class, null);
        dataTable.addContainerProperty("value", Integer.class, 0);
    }

    public void setData(List<Object> items) {
        container.removeAllItems();
        container.addAll(items);
    }

    public Button createSaveButton() {
        Button saveButton = new Button("Save");
        saveButton.addClickListener(event -> {
            try {
                fieldGroup.commit();
            } catch (Exception e) {
                // handle commit exception
            }
        });
        return saveButton;
    }
}
