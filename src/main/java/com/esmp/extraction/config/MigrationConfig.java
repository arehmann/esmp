package com.esmp.extraction.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the migration pattern detection system.
 *
 * <p>Provides enterprise-specific Vaadin 7 → Vaadin 24 type mapping overrides that supplement the
 * recipe book loaded by {@link com.esmp.migration.application.RecipeBookRegistry}. Custom
 * mappings follow the same format: fully qualified Vaadin 7 source type → fully qualified Vaadin 24
 * target type.
 *
 * <p>Example YAML configuration:
 * <pre>
 * esmp:
 *   migration:
 *     custom-mappings:
 *       com.myapp.CustomButton: com.vaadin.flow.component.button.Button
 *     recipe-book-path: data/migration/vaadin-recipe-book.json
 *     custom-recipe-book-path: /opt/esmp/custom-recipes.json
 *     alfa-overlay-path: classpath:/migration/alfa-recipe-book-overlay.json
 *     transitive:
 *       override-weight: 0.3
 *       own-calls-weight: 0.3
 *       binding-weight: 0.2
 *       component-weight: 0.2
 *       ai-assisted-threshold: 0.4
 *       alfa-calls-weight: 0.2
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "esmp.migration")
public class MigrationConfig {

  /**
   * Enterprise-specific type mapping overrides. Keys are fully qualified Vaadin 7 source types;
   * values are fully qualified Vaadin 24 target types. These override or extend the built-in
   * rules in the recipe book.
   */
  private Map<String, String> customMappings = new LinkedHashMap<>();

  /**
   * Path to the runtime recipe book JSON file. If the file does not exist at startup, the seed
   * JSON from the classpath is copied here. Default: {@code data/migration/vaadin-recipe-book.json}.
   */
  private String recipeBookPath = "data/migration/vaadin-recipe-book.json";

  /**
   * Optional path to a custom overlay recipe book JSON file. Rules in this file replace seed
   * rules with the same source FQN. Empty string disables overlay loading.
   */
  private String customRecipeBookPath = "";

  /** Path to the built-in Alfa* overlay JSON. Prefix "classpath:" triggers classpath loading.
   *  Default: "classpath:/migration/alfa-recipe-book-overlay.json". */
  private String alfaOverlayPath = "classpath:/migration/alfa-recipe-book-overlay.json";

  /** Transitive detection weight configuration. */
  private TransitiveConfig transitive = new TransitiveConfig();

  // =========================================================================
  // Nested config
  // =========================================================================

  /**
   * Weight coefficients for the transitive complexity scoring algorithm.
   * Used in Plan 02 to compute {@code transitiveComplexity} on inherited migration actions.
   */
  public static class TransitiveConfig {

    /** Weight for the method override count component. Default: 0.3. */
    private double overrideWeight = 0.3;

    /** Weight for the own Vaadin call count component. Default: 0.3. */
    private double ownCallsWeight = 0.3;

    /** Weight for the data binding presence component. Default: 0.2. */
    private double bindingWeight = 0.2;

    /** Weight for the Vaadin UI component usage component. Default: 0.2. */
    private double componentWeight = 0.2;

    /**
     * Threshold above which the transitive complexity score triggers AI-assisted classification.
     * Default: 0.4.
     */
    private double aiAssistedThreshold = 0.4;

    /**
     * Weight for the own Alfa* wrapper call count component. Default: 0.2.
     * Applies when a Layer 2 class calls Alfa* types directly in addition to inheriting from them.
     */
    private double alfaCallsWeight = 0.2;

    public double getOverrideWeight() {
      return overrideWeight;
    }

    public void setOverrideWeight(double overrideWeight) {
      this.overrideWeight = overrideWeight;
    }

    public double getOwnCallsWeight() {
      return ownCallsWeight;
    }

    public void setOwnCallsWeight(double ownCallsWeight) {
      this.ownCallsWeight = ownCallsWeight;
    }

    public double getBindingWeight() {
      return bindingWeight;
    }

    public void setBindingWeight(double bindingWeight) {
      this.bindingWeight = bindingWeight;
    }

    public double getComponentWeight() {
      return componentWeight;
    }

    public void setComponentWeight(double componentWeight) {
      this.componentWeight = componentWeight;
    }

    public double getAiAssistedThreshold() {
      return aiAssistedThreshold;
    }

    public void setAiAssistedThreshold(double aiAssistedThreshold) {
      this.aiAssistedThreshold = aiAssistedThreshold;
    }

    public double getAlfaCallsWeight() {
      return alfaCallsWeight;
    }

    public void setAlfaCallsWeight(double alfaCallsWeight) {
      this.alfaCallsWeight = alfaCallsWeight;
    }
  }

  // =========================================================================
  // Getters and setters
  // =========================================================================

  public Map<String, String> getCustomMappings() {
    return customMappings;
  }

  public void setCustomMappings(Map<String, String> customMappings) {
    this.customMappings = customMappings;
  }

  public String getRecipeBookPath() {
    return recipeBookPath;
  }

  public void setRecipeBookPath(String recipeBookPath) {
    this.recipeBookPath = recipeBookPath;
  }

  public String getCustomRecipeBookPath() {
    return customRecipeBookPath;
  }

  public void setCustomRecipeBookPath(String customRecipeBookPath) {
    this.customRecipeBookPath = customRecipeBookPath;
  }

  public String getAlfaOverlayPath() {
    return alfaOverlayPath;
  }

  public void setAlfaOverlayPath(String alfaOverlayPath) {
    this.alfaOverlayPath = alfaOverlayPath;
  }

  public TransitiveConfig getTransitive() {
    return transitive;
  }

  public void setTransitive(TransitiveConfig transitive) {
    this.transitive = transitive;
  }
}
