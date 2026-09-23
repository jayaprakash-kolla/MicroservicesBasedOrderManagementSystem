package com.jayaprakash.oms.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayaprakash.oms.order.api.dto.OrderLineItemRequest;
import com.jayaprakash.oms.order.api.dto.OrderResponse;
import com.jayaprakash.oms.order.api.dto.PlaceOrderRequest;
import com.jayaprakash.oms.order.service.OrderNotFoundException;
import com.jayaprakash.oms.order.service.OrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests for {@link OrderController}.
 *
 * <p>Only the MVC slice loads and the service is mocked, so these check HTTP
 * behaviour — status codes, validation, error shape — with no Kafka or database
 * anywhere.
 */
@WebMvcTest(OrderController.class)
@ActiveProfiles("test")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    /**
     * Builds a stub response for the mocked service.
     *
     * <p>Input: none. Output: a PENDING {@link OrderResponse}, so tests can
     * focus on the HTTP layer rather than on constructing data.
     */
    private OrderResponse pendingOrder() {
        return new OrderResponse(
                1L, "ORD-ABC12345", "cust-1", "PENDING", new BigDecimal("1000.00"),
                null, Instant.now(), Instant.now(),
                List.of(new OrderLineItemRequest("LAPTOP-001", 2, new BigDecimal("500.00"))));
    }

    @Test
    @DisplayName("a valid order returns 202 Accepted with the order in PENDING")
    void placeOrderReturnsAccepted() throws Exception {
        when(orderService.placeOrder(any(PlaceOrderRequest.class))).thenReturn(pendingOrder());

        PlaceOrderRequest request = new PlaceOrderRequest("cust-1", List.of(
                new OrderLineItemRequest("LAPTOP-001", 2, new BigDecimal("500.00"))));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // 202, not 201: the order exists but fulfilment is not decided yet.
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.orderNumber").value("ORD-ABC12345"));
    }

    @Test
    @DisplayName("an order with no customerId is rejected with per-field detail")
    void rejectsMissingCustomerId() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest("", List.of(
                new OrderLineItemRequest("LAPTOP-001", 2, new BigDecimal("500.00"))));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.customerId").exists());

        verify(orderService, never()).placeOrder(any());
    }

    @Test
    @DisplayName("an order with no items is rejected")
    void rejectsEmptyItems() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest("cust-1", List.of());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.items").exists());
    }

    @Test
    @DisplayName("validation descends into the line items, so quantity 0 is rejected")
    void rejectsInvalidLineItem() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest("cust-1", List.of(
                new OrderLineItemRequest("LAPTOP-001", 0, new BigDecimal("500.00"))));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("fetching an order returns its current saga status")
    void getOrderReturnsStatus() throws Exception {
        when(orderService.getOrder(1L)).thenReturn(pendingOrder());

        mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("fetching a missing order returns 404 with a clear error code")
    void getMissingOrderReturns404() throws Exception {
        when(orderService.getOrder(999L)).thenThrow(new OrderNotFoundException("No order with id 999"));

        mockMvc.perform(get("/api/v1/orders/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));
    }
}
