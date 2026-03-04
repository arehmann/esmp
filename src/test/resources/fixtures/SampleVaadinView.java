package com.example.sample;

import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener.ViewChangeEvent;
import com.vaadin.spring.annotation.SpringView;
import com.vaadin.ui.Button;
import com.vaadin.ui.Label;
import com.vaadin.ui.Table;
import com.vaadin.ui.VerticalLayout;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Vaadin 7 Navigator View displaying a list of customers.
 *
 * <p>This view demonstrates: - {@code @SpringView} annotation for Vaadin Spring integration -
 * Implementation of {@code com.vaadin.navigator.View} - Component tree construction with {@code
 * addComponent()} calls - Cross-layer service invocation for call graph extraction
 */
@SpringView(name = "sample")
public class SampleVaadinView extends VerticalLayout implements View {

  @Autowired private SampleService sampleService;

  private final Label titleLabel;
  private final Button refreshButton;
  private final Table customerTable;

  public SampleVaadinView() {
    titleLabel = new Label("Customers");
    refreshButton = new Button("Refresh");
    customerTable = new Table("Customer List");

    customerTable.addContainerProperty("Name", String.class, null);
    customerTable.addContainerProperty("Email", String.class, null);

    addComponent(titleLabel);
    addComponent(refreshButton);
    addComponent(customerTable);

    refreshButton.addClickListener(
        event -> {
          loadCustomers();
        });
  }

  @Override
  public void enter(ViewChangeEvent event) {
    loadCustomers();
  }

  private void loadCustomers() {
    customerTable.removeAllItems();
    List<SampleEntity> customers = sampleService.findAll();
    for (SampleEntity customer : customers) {
      customerTable.addItem(new Object[] {customer.getName(), customer.getEmail()}, customer.getId());
    }
  }
}
