package com.jayaprakash.oms.order.api;

import com.jayaprakash.oms.order.api.dto.OrderResponse;
import com.jayaprakash.oms.order.api.dto.PlaceOrderRequest;
import com.jayaprakash.oms.order.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public API for orders. Reached through the gateway at
 * {@code http://localhost:8080/api/v1/orders}.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    /**
     * @param orderService the order lifecycle service
     */
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Places a new order.
     *
     * <p>Input: a validated {@link PlaceOrderRequest}. Processing: saves the
     * order and starts the saga. Output: {@code 202 Accepted} with the order in
     * PENDING state.
     *
     * <p>202 rather than 201 is deliberate: the order exists, but whether it
     * will be fulfilled is not yet known. Poll {@code GET /api/v1/orders/{id}}
     * to watch it reach CONFIRMED or CANCELLED.
     *
     * @param request the order to place
     * @return 202 with the pending order
     */
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        OrderResponse response = orderService.placeOrder(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Fetches one order, including its current saga status.
     *
     * <p>Input: the order id from the path. Output: 200 with the order, or 404
     * if it does not exist.
     *
     * @param id the order id
     * @return the order
     */
    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        return orderService.getOrder(id);
    }

    /**
     * Lists orders, optionally filtered by customer.
     *
     * <p>Input: an optional {@code customerId} query parameter. Output: the
     * matching orders.
     *
     * @param customerId optional customer filter
     * @return matching orders
     */
    @GetMapping
    public List<OrderResponse> listOrders(@RequestParam(required = false) String customerId) {
        return orderService.listOrders(customerId);
    }
}
