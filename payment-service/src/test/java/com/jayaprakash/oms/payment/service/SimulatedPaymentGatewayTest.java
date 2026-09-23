package com.jayaprakash.oms.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SimulatedPaymentGateway}.
 *
 * <p>The failure rule is deliberately deterministic — a random gateway would
 * produce a test suite that fails one run in ten and teaches you nothing.
 */
class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway =
            new SimulatedPaymentGateway(new BigDecimal("1000"));

    @Test
    @DisplayName("accepts a charge below the threshold")
    void acceptsSmallCharge() {
        Optional<String> result = gateway.charge("cust-1", new BigDecimal("999.99"));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("accepts a charge exactly at the threshold")
    void acceptsChargeAtThreshold() {
        // The rule is "strictly above", so the boundary itself must pass.
        Optional<String> result = gateway.charge("cust-1", new BigDecimal("1000"));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("declines a charge above the threshold and explains why")
    void declinesLargeCharge() {
        Optional<String> result = gateway.charge("cust-1", new BigDecimal("1000.01"));

        assertThat(result).isPresent();
        assertThat(result.get()).contains("declined").contains("1000");
    }
}
