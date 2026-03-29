package com.esmp.extraction.module;

/** Identifies the build system used by a multi-module project. */
public enum BuildSystem {
  /** Gradle multi-module project (detected via {@code settings.gradle}). */
  GRADLE,
  /** Maven multi-module project (detected via root {@code pom.xml} with {@code <modules>}). */
  MAVEN,
  /** No recognized build file found — single-module fallback path. */
  NONE
}
