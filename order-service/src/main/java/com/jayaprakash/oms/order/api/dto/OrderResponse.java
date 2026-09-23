package com.jayaprakash.oms.order.api.dto;

import com.jayaprakash.oms.order.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What the API returns for an order.
 *
 * @param id            internal id, also the correlation key across services
 * @param orderNumber   public reference
 * @param customerId    who placed it
 * @param status        current saga state
 * @param totalAmount   order total
 * @param failureReason why it was cancelled, {@code null} otherwise
 * @param createdAt     when it was placed
 * @param updatedAt     when it last changed state
 * @param items         the line items
 */
public record OrderResponse(

        Long id,
        String orderNumber,
        String customerId,
        String status,
        BigDecimal totalAmount,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        List<OrderLineItemRequest> items
) {

    /**
     * Converts an entity into its API representation.
     *
     * <p>Input: a persisted {@link Order}. Processing: copies the scalar fields
     * and maps each line item. Output: the response DTO, so JPA entities never
     * leak out of the service layer.
     *
     * @param order the persisted order
     * @return the DTO to serialise
     */
    public static OrderResponse from(Order order) {
        List<OrderLineItemRequest> items = order.getItems().stream()
                .map(item -> new OrderLineItemRequest(
                        item.getSkuCode(), item.getQuantity(), item.getPrice()))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomerId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                items);
    }
}
