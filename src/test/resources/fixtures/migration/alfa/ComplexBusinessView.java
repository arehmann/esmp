package com.esmp.migration.alfa;

/**
 * Complex Layer 2 business class — extends AlfaButton directly and also uses AlfaTable.
 * Has own Alfa* usage (ownAlfaCalls >= 1).
 * Expected: pureWrapper=false, transitiveComplexity > 0.
 */
public class ComplexBusinessView extends AlfaButton {
    private AlfaTable resultsTable = new AlfaTable();

    public ComplexBusinessView() {
        super("Show Results");
    }

    public void showResults() {
        resultsTable.addItem("result");
    }
}
