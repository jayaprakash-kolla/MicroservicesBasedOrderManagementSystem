package com.jayaprakash.oms.payment.service;

import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stand-in card processor with a deliberately predictable failure rule.
 *
 * <p>A charge above {@code payment.decline-above-amount} is declined. That
 * makes the compensating half of the saga reproducible on demand: order
 * something cheap and watch it confirm, order something expensive and watch the
 * reserved stock come back.
 *
 * <p>Random failures were the obvious alternative and are much worse — a test
 * that fails one run in ten teaches you nothing.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final BigDecimal declineAboveAmount;

    /**
     * @param declineAboveAmount charges strictly above this are declined,
     *                           from {@code payment.decline-above-amount}
     */
    public SimulatedPaymentGateway(
            @Value("${payment.decline-above-amount:100000}") BigDecimal declineAboveAmount) {
        this.declineAboveAmount = declineAboveAmount;
    }

    /**
     * Decides whether this charge succeeds.
     *
     * <p>Input: customer and amount. Processing: compares the amount with the
     * configured threshold. Output: empty on success, or a decline reason.
     *
     * @param customerId who is being charged
     * @param amount     how much
     * @return the decline reason, or empty when the charge is accepted
     */
    @Override
    public Optional<String> charge(String customerId, BigDecimal amount) {

        if (amount.compareTo(declineAboveAmount) > 0) {
            String reason = "Card declined: amount " + amount + " exceeds limit " + declineAboveAmount;
            log.info("CHARGE DECLINED customer={} amount={}", customerId, amount);
            return Optional.of(reason);
        }

        log.info("CHARGE OK customer={} amount={}", customerId, amount);
        return Optional.empty();
    }
}
