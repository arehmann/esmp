package com.esmp.pilot;

import java.util.ArrayList;
import java.util.List;

/**
 * Validator for invoice business rules.
 * Validates invoice data before persistence to enforce domain constraints.
 */
public class InvoiceValidator {

    private static final double MIN_AMOUNT = 0.01;
    private static final double MAX_AMOUNT = 1_000_000.00;

    /**
     * Validates an invoice entity and returns a list of validation errors.
     * High cyclomatic complexity due to multiple business rule branches.
     */
    public List<String> validate(InvoiceEntity invoice) {
        List<String> errors = new ArrayList<>();

        if (invoice == null) {
            errors.add("Invoice must not be null");
            return errors;
        }

        if (invoice.getCustomerId() == null || invoice.getCustomerId().isBlank()) {
            errors.add("Customer ID is required");
        }

        if (invoice.getAmount() < MIN_AMOUNT) {
            errors.add("Invoice amount must be at least " + MIN_AMOUNT);
        } else if (invoice.getAmount() > MAX_AMOUNT) {
            errors.add("Invoice amount exceeds maximum allowed: " + MAX_AMOUNT);
        }

        if (invoice.getStatus() == null) {
            errors.add("Invoice status is required");
        } else if (invoice.getStatus() == InvoiceStatusEnum.CANCELLED) {
            errors.add("Cannot validate a cancelled invoice");
        }

        if (invoice.getDescription() != null && invoice.getDescription().length() > 500) {
            errors.add("Description must not exceed 500 characters");
        }

        return errors;
    }

    /**
     * Returns true if the status transition is valid.
     */
    public boolean isValidStatusTransition(InvoiceStatusEnum from, InvoiceStatusEnum to) {
        if (from == null || to == null) {
            return false;
        }
        switch (from) {
            case DRAFT:
                return to == InvoiceStatusEnum.SENT || to == InvoiceStatusEnum.CANCELLED;
            case SENT:
                return to == InvoiceStatusEnum.PAID || to == InvoiceStatusEnum.OVERDUE || to == InvoiceStatusEnum.CANCELLED;
            case OVERDUE:
                return to == InvoiceStatusEnum.PAID || to == InvoiceStatusEnum.CANCELLED;
            case PAID:
                return false;
            case CANCELLED:
                return false;
            default:
                return false;
        }
    }
}
