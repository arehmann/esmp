package com.esmp.extraction.util;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ModuleDeriverTest {

  @Test
  void derivesModuleFromStandardGradlePath() {
    assertThat(ModuleDeriver.fromSourceFilePath(
        "/mnt/source/adsuite-market/src/main/java/de/alfa/openMedia/Foo.java"))
        .isEqualTo("adsuite-market");
  }

  @Test
  void derivesModuleFromTestPath() {
    assertThat(ModuleDeriver.fromSourceFilePath(
        "/mnt/source/adsuite-server/src/test/java/de/alfa/Test.java"))
        .isEqualTo("adsuite-server");
  }

  @Test
  void derivesModuleFromWindowsPath() {
    assertThat(ModuleDeriver.fromSourceFilePath(
        "C:\\source\\adsuite-market\\src\\main\\java\\de\\alfa\\Foo.java"))
        .isEqualTo("adsuite-market");
  }

  @Test
  void derivesModuleFromNestedSubproject() {
    assertThat(ModuleDeriver.fromSourceFilePath(
        "/mnt/source/modules/adsuite-billing/src/main/java/de/alfa/Foo.java"))
        .isEqualTo("adsuite-billing");
  }

  @Test
  void fallsBackToPackageSegmentWhenNoSrcMainJava() {
    assertThat(ModuleDeriver.fromSourceFilePath(
        "/mnt/source/de/alfa/openMedia/adSuite/vaadin/Foo.java"))
        .isEqualTo("adSuite");
  }

  @Test
  void packageNameFallback() {
    assertThat(ModuleDeriver.fromPackageName(
        "de.alfa.openMedia.adSuite.vaadin.ui.panel"))
        .isEqualTo("adSuite");
  }

  @Test
  void returnsEmptyForNull() {
    assertThat(ModuleDeriver.fromSourceFilePath(null)).isEmpty();
    assertThat(ModuleDeriver.fromPackageName(null)).isEmpty();
  }
}
