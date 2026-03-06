package com.esmp.pilot;

/**
 * Enumeration of customer roles defining access levels in the billing system.
 * Used by AuditService for authorization checks.
 */
public enum CustomerRole {

    /** Full system administrator with unrestricted access. */
    ADMIN,

    /** Standard customer account with basic billing access. */
    USER,

    /** Manager with elevated reporting and approval capabilities. */
    MANAGER,

    /** Auditor role with read-only access to audit trails and reports. */
    AUDITOR
}
