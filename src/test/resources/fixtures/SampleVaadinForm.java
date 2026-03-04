package com.example.sample;

import com.vaadin.data.fieldgroup.BeanFieldGroup;
import com.vaadin.data.fieldgroup.FieldGroup.CommitException;
import com.vaadin.ui.Button;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.Notification;
import com.vaadin.ui.TextField;

/**
 * Vaadin 7 data-bound form for editing a {@link SampleEntity}.
 *
 * <p>This form demonstrates: - Use of {@link BeanFieldGroup} for data binding - Field binding via
 * {@code fieldGroup.bind()} calls - VaadinDataBinding pattern for extraction labelling
 */
public class SampleVaadinForm extends FormLayout {

  private final BeanFieldGroup<SampleEntity> fieldGroup;

  private final TextField nameField;
  private final TextField emailField;
  private final Button saveButton;

  public SampleVaadinForm(SampleService service) {
    fieldGroup = new BeanFieldGroup<>(SampleEntity.class);

    nameField = new TextField("Name");
    emailField = new TextField("Email");
    saveButton = new Button("Save");

    fieldGroup.bind(nameField, "name");
    fieldGroup.bind(emailField, "email");

    addComponent(nameField);
    addComponent(emailField);
    addComponent(saveButton);

    saveButton.addClickListener(
        event -> {
          try {
            fieldGroup.commit();
            SampleEntity entity = fieldGroup.getItemDataSource().getBean();
            service.save(entity);
            Notification.show("Saved successfully");
          } catch (CommitException e) {
            Notification.show("Validation failed: " + e.getMessage(), Notification.Type.ERROR_MESSAGE);
          }
        });
  }

  /**
   * Binds this form to the given customer entity for editing.
   *
   * @param entity the entity to edit
   */
  public void edit(SampleEntity entity) {
    fieldGroup.setItemDataSource(entity);
  }
}
