package com.jayaprakash.oms.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.PaymentCompletedEvent;
import com.jayaprakash.oms.common.events.PaymentFailedEvent;
import com.jayaprakash.oms.payment.domain.Payment;
import com.jayaprakash.oms.payment.domain.PaymentStatus;
import com.jayaprakash.oms.payment.messaging.PaymentEventPublisher;
import com.jayaprakash.oms.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PaymentService}.
 *
 * <p>The rule these tests protect: every path must end in exactly one published
 * event. A path that publishes nothing would strand the order in
 * INVENTORY_RESERVED with stock held against it forever.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentEventPublisher eventPublisher;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, paymentGateway, eventPublisher);
    }

    /**
     * Builds the event that authorises a charge.
     *
     * @param amount how much to charge
     * @return an {@link InventoryReservedEvent} for order 1
     */
    private InventoryReservedEvent reservedEvent(String amount) {
        return new InventoryReservedEvent(1L, 10L, "cust-1", new BigDecimal(amount));
    }

    @Test
    @DisplayName("a successful charge is stored as COMPLETED and announced")
    void successfulChargePublishesCompleted() {
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(paymentGateway.charge(anyString(), any(BigDecimal.class))).thenReturn(Optional.empty());

        paymentService.processPayment(reservedEvent("500.00"));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(saved.getValue().getFailureReason()).isNull();

        ArgumentCaptor<PaymentCompletedEvent> published =
                ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(eventPublisher).publishPaymentCompleted(published.capture());
        assertThat(published.getValue().orderId()).isEqualTo(1L);
        assertThat(published.getValue().paymentId()).startsWith("PAY-");

        verify(eventPublisher, never()).publishPaymentFailed(any());
    }

    @Test
    @DisplayName("a declined charge is stored as DECLINED and starts the unwind")
    void declinedChargePublishesFailed() {
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(paymentGateway.charge(anyString(), any(BigDecimal.class)))
                .thenReturn(Optional.of("Card declined: limit exceeded"));

        paymentService.processPayment(reservedEvent("50000.00"));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(saved.getValue().getFailureReason()).contains("declined");

        ArgumentCaptor<PaymentFailedEvent> published = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(eventPublisher).publishPaymentFailed(published.capture());
        assertThat(published.getValue().orderId()).isEqualTo(1L);

        verify(eventPublisher, never()).publishPaymentCompleted(any());
    }

    @Test
    @DisplayName("a redelivered event never charges the customer a second time")
    void duplicateEventDoesNotDoubleCharge() {
        Payment existing = new Payment("PAY-EXISTING", 1L, "cust-1",
                new BigDecimal("500.00"), PaymentStatus.COMPLETED, null);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(existing));

        paymentService.processPayment(reservedEvent("500.00"));

        // The gateway must not even be called: this is real money.
        verify(paymentGateway, never()).charge(anyString(), any());
        verify(paymentRepository, never()).save(any());
        verify(eventPublisher, never()).publishPaymentCompleted(any());
        verify(eventPublisher, never()).publishPaymentFailed(any());
    }

    @Test
    @DisplayName("the charged amount comes from the event, not from a callback")
    void chargesTheAmountCarriedByTheEvent() {
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(paymentGateway.charge(anyString(), any(BigDecimal.class))).thenReturn(Optional.empty());

        paymentService.processPayment(reservedEvent("1234.56"));

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(paymentGateway).charge(anyString(), amount.capture());
        assertThat(amount.getValue()).isEqualByComparingTo("1234.56");
    }
}
