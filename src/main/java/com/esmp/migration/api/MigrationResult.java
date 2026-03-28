package com.esmp.migration.api;

import java.util.List;

/**
 * Result of executing a composite OpenRewrite recipe on a single class.
 *
 * <p>Returned by both preview mode (no disk write) and apply mode (writes to disk). When
 * {@code hasChanges} is false, the {@code diff} and {@code modifiedSource} fields are null and
 * the factory method {@link #noChanges(String)} should be used.
 *
 * @param classFqn fully qualified name of the processed class
 * @param hasChanges true if the recipe produced any source modifications
 * @param diff unified diff text (null if no changes)
 * @param modifiedSource full modified source text after recipe application (null if no changes)
 * @param recipesApplied number of individual recipes that produced changes
 * @param remainingManual actions that could not be automated (PARTIAL or NO)
 * @param automationScore automation score from the migration plan for this class
 */
public record MigrationResult(
    String classFqn,
    boolean hasChanges,
    String diff,
    String modifiedSource,
    int recipesApplied,
    List<MigrationActionEntry> remainingManual,
    double automationScore) {

  /**
   * Factory method for the case where no automatable actions exist or the recipe produced no
   * changes.
   *
   * @param classFqn fully qualified name of the class
   * @return a MigrationResult indicating no changes were made
   */
  public static MigrationResult noChanges(String classFqn) {
    return new MigrationResult(classFqn, false, null, null, 0, List.of(), 0.0);
  }
}
