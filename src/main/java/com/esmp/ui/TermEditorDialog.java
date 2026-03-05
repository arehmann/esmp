package com.esmp.ui;

import com.esmp.extraction.model.BusinessTermNode;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Dialog for editing a single domain term's definition, criticality, and synonyms.
 *
 * <p>Opens pre-populated with the current term values. On save, invokes the provided {@link
 * Consumer} callback with an {@link UpdateResult} containing the new values.
 */
public class TermEditorDialog extends Dialog {

  /**
   * Holds the new values submitted by the user when saving the term editor.
   *
   * @param definition updated human-curated definition
   * @param criticality updated criticality level ("Low", "Medium", or "High")
   * @param synonyms updated list of synonyms
   */
  public record UpdateResult(String definition, String criticality, List<String> synonyms) {}

  /**
   * Creates an editor dialog pre-populated with the given term's current values.
   *
   * @param term the term to edit
   * @param onSave callback invoked with the updated values when the user clicks Save
   */
  public TermEditorDialog(BusinessTermNode term, Consumer<UpdateResult> onSave) {
    setHeaderTitle("Edit Term: " + term.getDisplayName());
    setWidth("500px");
    setResizable(true);

    TextArea definitionField = new TextArea("Definition");
    definitionField.setValue(term.getDefinition() != null ? term.getDefinition() : "");
    definitionField.setWidthFull();
    definitionField.setMinHeight("100px");

    ComboBox<String> criticalityBox = new ComboBox<>("Criticality");
    criticalityBox.setItems("Low", "Medium", "High");
    criticalityBox.setValue(term.getCriticality() != null ? term.getCriticality() : "Low");
    criticalityBox.setAllowCustomValue(false);

    TextField synonymsField = new TextField("Synonyms (comma-separated)");
    List<String> existingSynonyms = term.getSynonyms();
    synonymsField.setValue(
        existingSynonyms != null ? String.join(", ", existingSynonyms) : "");
    synonymsField.setWidthFull();

    VerticalLayout content = new VerticalLayout(definitionField, criticalityBox, synonymsField);
    content.setPadding(false);
    add(content);

    Button saveButton = new Button("Save");
    saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
    saveButton.addClickListener(
        e -> {
          String definition = definitionField.getValue();
          String criticality =
              criticalityBox.getValue() != null ? criticalityBox.getValue() : "Low";
          List<String> synonyms =
              Arrays.stream(synonymsField.getValue().split(","))
                  .map(String::trim)
                  .filter(s -> !s.isEmpty())
                  .collect(Collectors.toList());
          onSave.accept(new UpdateResult(definition, criticality, synonyms));
          close();
        });

    Button cancelButton = new Button("Cancel", e -> close());

    getFooter().add(new HorizontalLayout(cancelButton, saveButton));
  }
}
