package com.esmp.migration.api;

import java.util.List;

/**
 * Container record for a recipe book loaded from a JSON file.
 *
 * <p>Serialized shape:
 * <pre>
 * {
 *   "rules": [ { ...RecipeRule fields... }, ... ]
 * }
 * </pre>
 *
 * <p>The {@code isBase} field of each {@link RecipeRule} is NOT stored in JSON — it is set by
 * {@link com.esmp.migration.application.RecipeBookRegistry} at load time.
 */
public record RecipeBook(List<RecipeRule> rules) {}
