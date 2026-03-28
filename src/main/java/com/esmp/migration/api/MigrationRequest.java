package com.esmp.migration.api;

/**
 * Optional request body for migration endpoints that can override the default source root.
 *
 * <p>Both fields are optional. When {@code sourceRoot} is null, {@link
 * com.esmp.source.application.SourceAccessService} provides the resolved source root. When
 * {@code classpathFile} is null, no additional classpath entries are passed to the parser.
 *
 * @param sourceRoot optional override for the source root directory (null = use SourceAccessService default)
 * @param classpathFile optional classpath file path override (null = no classpath)
 */
public record MigrationRequest(
    String sourceRoot,
    String classpathFile) {}
