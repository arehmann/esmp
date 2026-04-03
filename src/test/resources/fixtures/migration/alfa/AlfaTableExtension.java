package com.esmp.migration.alfa;

/**
 * Alfa* table extension with one override — Layer 1.5 (complex wrapper).
 * overrideCount > 0 in complexity profiling.
 */
public class AlfaTableExtension extends AlfaTable {
    @Override
    public void attach() {
        super.attach();
        // custom attach logic
    }
}
