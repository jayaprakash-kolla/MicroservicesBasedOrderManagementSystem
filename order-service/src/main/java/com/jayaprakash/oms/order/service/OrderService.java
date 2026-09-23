package com.jayaprakash.oms.order.service;

import com.jayaprakash.oms.common.events.OrderCancelledEvent;
import com.jayaprakash.oms.common.events.OrderCreatedEvent;
import com.jayaprakash.oms.common.events.OrderLineItemDto;
import com.jayaprakash.oms.order.api.dto.OrderResponse;
import com.jayaprakash.oms.order.api.dto.PlaceOrderRequest;
import com.jayaprakash.oms.order.domain.Order;
import com.jayaprakash.oms.order.domain.OrderLineItem;
import com.jayaprakash.oms.order.domain.OrderStatus;
import com.jayaprakash.oms.order.messaging.OrderEventPublisher;
import com.jayaprakash.oms.order.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the order lifecycle and drives the saga.
 *
 * <p>Every method that reacts to a downstream event first checks whether the
 * transition is legal. That guard is not optional decoration: Kafka delivers
 * at least once, so the same {@code PaymentCompletedEvent} can genuinely arrive
 * twice, and without the check a redelivery would re-confirm an order that had
 * already been cancelled.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;

    /**
     * @param orderRepository database access for orders
     * @param eventPublisher  publishes saga events to Kafka
     */
    public OrderService(OrderRepository orderRepository, OrderEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Places a new order and starts the saga.
     *
     * <p>Input: a validated {@link PlaceOrderRequest}. Processing: builds the
     * entity (which computes the total), saves it as PENDING, then publishes an
     * {@link OrderCreatedEvent}. Output: the saved order as a DTO.
     *
     * <p>Note the ordering: the row is committed before the event is published.
     * If publication failed after the save, the order would sit in PENDING —
     * which a scheduled reconciliation job would pick up. Publishing first would
     * be worse: downstream services could act on an order that does not exist.
     *
     * @param request what the customer wants to buy
     * @return the newly created order, in PENDING state
     */
    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request) {

        List<OrderLineItem> items = request.items().stream()
                .map(line -> new OrderLineItem(line.skuCode(), line.quantity(), line.price()))
                .toList();

        String orderNumber = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Order order = orderRepository.save(new Order(orderNumber, request.customerId(), items));

        log.info("Order placed: id={} number={} total={}",
                order.getId(), order.getOrderNumber(), order.getTotalAmount());

        List<OrderLineItemDto> eventItems = order.getItems().stream()
                .map(item -> new OrderLineItemDto(item.getSkuCode(), item.getQuantity(), item.getPrice()))
                .toList();

        eventPublisher.publishOrderCreated(new OrderCreatedEvent(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomerId(),
                eventItems,
                order.getTotalAmount()));

        return OrderResponse.from(order);
    }

    /**
     * Records that inventory has been reserved for an order.
     *
     * <p>Input: the order id from {@code InventoryReservedEvent}. Processing:
     * moves PENDING to INVENTORY_RESERVED, ignoring the message if the order is
     * already finished or has already advanced. Output: none.
     *
     * @param orderId the order whose stock is now held
     */
    @Transactional
    public void markInventoryReserved(Long orderId) {
        Order order = findOrThrow(orderId);

        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Ignoring inventory-reserved for order {}: status is already {}",
                    orderId, order.getStatus());
            return;
        }

        order.changeStatus(OrderStatus.INVENTORY_RESERVED);
        orderRepository.save(order);
        log.info("Order {} moved to INVENTORY_RESERVED", orderId);
    }

    /**
     * Completes the saga successfully.
     *
     * <p>Input: the order id from {@code PaymentCompletedEvent}. Processing:
     * moves the order to CONFIRMED unless it is already in a terminal state.
     * Output: none.
     *
     * @param orderId the order that has been paid for
     */
    @Transactional
    public void confirmOrder(Long orderId) {
        Order order = findOrThrow(orderId);

        if (order.isFinished()) {
            log.warn("Ignoring payment-completed for order {}: already {}", orderId, order.getStatus());
            return;
        }

        order.changeStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);
        log.info("Order {} CONFIRMED", orderId);
    }

    /**
     * Cancels an order because inventory could not be reserved.
     *
     * <p>Input: order id and the reason from {@code InventoryFailedEvent}.
     * Processing: marks the order CANCELLED. Output: none.
     *
     * <p>No compensating event is published here, and that is deliberate:
     * nothing was reserved and nothing was charged, so there is nothing to undo.
     *
     * @param orderId the order that cannot be fulfilled
     * @param reason  why inventory refused it
     */
    @Transactional
    public void cancelForInventoryFailure(Long orderId, String reason) {
        Order order = findOrThrow(orderId);

        if (order.isFinished()) {
            log.warn("Ignoring inventory-failed for order {}: already {}", orderId, order.getStatus());
            return;
        }

        order.changeStatus(OrderStatus.CANCELLED);
        order.setFailureReason(reason);
        orderRepository.save(order);
        log.info("Order {} CANCELLED (inventory): {}", orderId, reason);
    }

    /**
     * Cancels an order after a failed payment AND triggers compensation.
     *
     * <p>Input: order id and the decline reason from {@code PaymentFailedEvent}.
     * Processing: marks the order CANCELLED, then publishes an
     * {@link OrderCancelledEvent} so inventory-service releases the stock it is
     * still holding. Output: none.
     *
     * <p>This is the one place where the saga actively undoes earlier work, and
     * it is the difference between a correct distributed transaction and a
     * slow inventory leak.
     *
     * @param orderId the order whose payment was declined
     * @param reason  why it was declined
     */
    @Transactional
    public void cancelForPaymentFailure(Long orderId, String reason) {
        Order order = findOrThrow(orderId);

        if (order.isFinished()) {
            log.warn("Ignoring payment-failed for order {}: already {}", orderId, order.getStatus());
            return;
        }

        order.changeStatus(OrderStatus.CANCELLED);
        order.setFailureReason(reason);
        orderRepository.save(order);

        // Compensating action: give the reserved stock back.
        eventPublisher.publishOrderCancelled(new OrderCancelledEvent(orderId, reason));

        log.info("Order {} CANCELLED (payment) and compensation published: {}", orderId, reason);
    }

    /**
     * Fetches one order.
     *
     * <p>Input: the order id. Output: the order as a DTO.
     *
     * @param orderId the order to fetch
     * @return the order
     * @throws OrderNotFoundException if no such order exists
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        return OrderResponse.from(findOrThrow(orderId));
    }

    /**
     * Lists orders, optionally filtered by customer.
     *
     * <p>Input: a customer id, or {@code null} for all orders. Output: the
     * matching orders as DTOs.
     *
     * @param customerId customer to filter by, or {@code null}
     * @return matching orders
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders(String customerId) {
        List<Order> orders = (customerId == null || customerId.isBlank())
                ? orderRepository.findAll()
                : orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);

        return orders.stream().map(OrderResponse::from).toList();
    }

    /**
     * Loads an order or fails loudly.
     *
     * <p>Input: the order id. Output: the entity. Throwing here rather than
     * returning an Optional means a saga event referencing an unknown order
     * surfaces as an error and eventually lands on the dead-letter topic,
     * instead of being silently swallowed.
     *
     * @param orderId the order to load
     * @return the entity
     * @throws OrderNotFoundException when it does not exist
     */
    private Order findOrThrow(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("No order with id " + orderId));
    }
}
