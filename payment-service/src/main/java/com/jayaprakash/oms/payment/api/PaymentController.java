package com.jayaprakash.oms.payment.api;

import com.jayaprakash.oms.payment.api.dto.PaymentResponse;
import com.jayaprakash.oms.payment.service.PaymentService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of charge attempts, for verifying what the gateway decided.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * @param paymentService the payment service
     */
    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Lists every payment attempt.
     *
     * <p>Input: none. Output: all payments, successful and declined.
     *
     * @return all payments
     */
    @GetMapping
    public List<PaymentResponse> listPayments() {
        return paymentService.listPayments().stream().map(PaymentResponse::from).toList();
    }

    /**
     * Fetches the payment for one order.
     *
     * <p>Input: the order id from the path. Output: 200 with the payment, or
     * 404 if the order never reached the payment step.
     *
     * @param orderId the order to look up
     * @return the payment for that order
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResponse> getByOrder(@PathVariable Long orderId) {
        return paymentService.findByOrderId(orderId)
                .map(PaymentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
