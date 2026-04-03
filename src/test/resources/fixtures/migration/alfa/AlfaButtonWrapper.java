package com.esmp.migration.alfa;

/**
 * Pure passthrough of AlfaButton — Layer 1.5 (wraps an Alfa* wrapper, no overrides).
 * Used to test multi-hop resolution: AlfaButtonWrapper → AlfaButton → com.vaadin.ui.Button.
 */
public class AlfaButtonWrapper extends AlfaButton {
    public AlfaButtonWrapper(String caption) {
        super(caption);
    }
}
