package com.esmp.pilot;

/**
 * Enumeration of possible invoice lifecycle statuses.
 * Drives state machine transitions in InvoiceService and InvoiceValidator.
 */
public enum InvoiceStatusEnum {

    /** Invoice created but not yet sent to the customer. */
    DRAFT,

    /** Invoice has been sent to the customer and is awaiting payment. */
    SENT,

    /** Invoice has been fully paid. */
    PAID,

    /** Invoice is past its due date and has not been paid. */
    OVERDUE,

    /** Invoice has been cancelled and will not be processed. */
    CANCELLED
}
