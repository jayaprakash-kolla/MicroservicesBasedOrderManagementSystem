package com.jayaprakash.oms.payment.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The external card processor.
 *
 * <p>An interface rather than a direct Stripe or Razorpay call, for two
 * reasons: the project runs with no third-party account, and tests can decline
 * a charge on demand without any network trickery.
 */
public interface PaymentGateway {

    /**
     * Attempts to charge a customer.
     *
     * <p>Input: who to charge and how much. Processing: hands the request to the
     * processor. Output: empty when the charge succeeded, or a decline reason
     * when it did not.
     *
     * <p>Returning a reason instead of throwing is deliberate: a decline is an
     * expected business outcome that the saga knows how to handle, not an error
     * worth retrying.
     *
     * @param customerId who to charge
     * @param amount     how much
     * @return the decline reason, or empty on success
     */
    Optional<String> charge(String customerId, BigDecimal amount);
}
