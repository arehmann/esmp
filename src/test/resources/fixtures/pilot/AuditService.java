package com.esmp.pilot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Security audit service for tracking authentication and authorization events.
 * Provides event logging, verification of user identity, and access control auditing.
 */
@Service
@Transactional
public class AuditService {

    private final List<String> auditLog = new ArrayList<>();

    /**
     * Logs a security event with actor identity and action description.
     * Security-sensitive: all login, logout, and access denial events must be logged.
     */
    public void logSecurityEvent(String actor, String action, String resource) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Actor must not be blank for security event");
        }
        String entry = Instant.now().toString() + " | " + actor + " | " + action + " | " + resource;
        auditLog.add(entry);

        if (action.contains("DENY") || action.contains("FAIL")) {
            escalateSecurityAlert(actor, action, resource);
        }
    }

    /**
     * Verifies authentication token and returns the authenticated principal name.
     * Returns null if authentication fails.
     */
    public String verifyAuthentication(String token) {
        if (token == null || token.isBlank()) {
            logSecurityEvent("SYSTEM", "AUTH_FAIL", "token=null");
            return null;
        }
        if (token.startsWith("valid_")) {
            String principal = token.substring(6);
            logSecurityEvent(principal, "AUTH_SUCCESS", "token");
            return principal;
        } else {
            logSecurityEvent("UNKNOWN", "AUTH_FAIL", "invalid_token");
            return null;
        }
    }

    /**
     * Checks whether the given principal has the specified permission.
     */
    public boolean checkAuthorization(String principal, CustomerRole requiredRole, CustomerRole userRole) {
        switch (requiredRole) {
            case ADMIN:
                return userRole == CustomerRole.ADMIN;
            case MANAGER:
                return userRole == CustomerRole.ADMIN || userRole == CustomerRole.MANAGER;
            case AUDITOR:
                return userRole == CustomerRole.ADMIN || userRole == CustomerRole.AUDITOR;
            default:
                return true;
        }
    }

    private void escalateSecurityAlert(String actor, String action, String resource) {
        String alert = "SECURITY_ALERT: " + actor + " " + action + " " + resource;
        auditLog.add(alert);
    }

    public List<String> getAuditLog() {
        return List.copyOf(auditLog);
    }
}
