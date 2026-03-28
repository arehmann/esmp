package com.esmp.extraction.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the migration pattern detection system.
 *
 * <p>Provides enterprise-specific Vaadin 7 → Vaadin 24 type mapping overrides that supplement the
 * built-in {@code TYPE_MAP} in {@link com.esmp.extraction.visitor.MigrationPatternVisitor}. Custom
 * mappings follow the same format: fully qualified Vaadin 7 source type → fully qualified Vaadin 24
 * target type.
 *
 * <p>Example YAML configuration:
 * <pre>
 * esmp:
 *   migration:
 *     custom-mappings:
 *       com.myapp.CustomButton: com.vaadin.flow.component.button.Button
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "esmp.migration")
public class MigrationConfig {

  /**
   * Enterprise-specific type mapping overrides. Keys are fully qualified Vaadin 7 source types;
   * values are fully qualified Vaadin 24 target types. These override or extend the built-in
   * TYPE_MAP in MigrationPatternVisitor.
   */
  private Map<String, String> customMappings = new LinkedHashMap<>();

  public Map<String, String> getCustomMappings() {
    return customMappings;
  }

  public void setCustomMappings(Map<String, String> customMappings) {
    this.customMappings = customMappings;
  }
}
