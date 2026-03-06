package com.esmp.pilot;

/**
 * Enumeration of possible payment transaction statuses.
 * Tracks the lifecycle of financial payment processing.
 */
public enum PaymentStatusEnum {

    /** Payment has been initiated but not yet processed. */
    PENDING,

    /** Payment has been successfully processed and confirmed. */
    COMPLETED,

    /** Payment processing failed due to an error or rejection. */
    FAILED,

    /** Payment has been reversed and funds returned to the payer. */
    REFUNDED
}
