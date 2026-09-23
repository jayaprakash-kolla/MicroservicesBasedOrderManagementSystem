package com.jayaprakash.oms.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/orders}.
 *
 * <p>{@code @Valid} on the list is what makes Bean Validation descend into each
 * line item; without it, an invalid quantity inside the list would slip through.
 *
 * @param customerId who is ordering
 * @param items      what they want, at least one line
 */
public record PlaceOrderRequest(

        @NotBlank(message = "customerId is required")
        String customerId,

        @NotEmpty(message = "at least one item is required")
        List<@Valid OrderLineItemRequest> items
) {
}
