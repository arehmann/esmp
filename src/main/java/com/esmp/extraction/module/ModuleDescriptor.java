package com.esmp.extraction.module;

import java.nio.file.Path;
import java.util.List;

/**
 * Metadata for a single module within a multi-module project.
 *
 * @param name              module name (e.g. {@code "adsuite-persistent"})
 * @param sourceDir         path to {@code src/main/java} under the module root
 * @param compiledClassesDir path to the compiled classes directory (e.g. {@code build/classes/java/main} for Gradle,
 *                          {@code target/classes} for Maven)
 * @param dependsOn         names of other modules this module declares a compile-time dependency on
 * @param javaFiles         all {@code .java} files discovered under {@code sourceDir}
 */
public record ModuleDescriptor(
    String name,
    Path sourceDir,
    Path compiledClassesDir,
    List<String> dependsOn,
    List<Path> javaFiles) {}
