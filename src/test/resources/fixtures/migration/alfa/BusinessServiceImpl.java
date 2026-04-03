package com.esmp.migration.alfa;

/**
 * Pure Layer 2 business class — extends AlfaButtonWrapper (which → AlfaButton → Button).
 * Has no own com.vaadin.* or com.alfa.* imports, no overrides of ancestor methods.
 * Expected: pureWrapper=true, vaadinAncestor=com.vaadin.ui.Button.
 */
public class BusinessServiceImpl extends AlfaButtonWrapper {
    public BusinessServiceImpl() {
        super("Execute");
    }

    public void execute() {
        // business logic only — no Vaadin or Alfa usage
    }
}
