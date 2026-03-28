package com.esmp.migration.api;

/**
 * Request body for the batch module migration endpoint.
 *
 * @param module required module name (third segment of the package name, e.g., "billing")
 */
public record ModuleMigrationRequest(String module) {}
