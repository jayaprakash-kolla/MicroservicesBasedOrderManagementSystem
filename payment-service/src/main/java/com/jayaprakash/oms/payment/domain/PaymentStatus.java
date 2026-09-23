package com.jayaprakash.oms.payment.domain;

/**
 * Outcome of a charge attempt.
 */
public enum PaymentStatus {

    /** The gateway accepted the charge. */
    COMPLETED,

    /** The gateway declined it. Terminal — order-service will compensate. */
    DECLINED
}
