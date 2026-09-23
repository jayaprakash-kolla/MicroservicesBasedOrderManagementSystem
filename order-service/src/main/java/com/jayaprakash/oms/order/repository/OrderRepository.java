package com.jayaprakash.oms.order.repository;

import com.jayaprakash.oms.order.domain.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Database access for orders. Order-service owns this schema outright — no
 * other service can read or write it, which is what forces the event-driven
 * design instead of cross-service joins.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Looks up an order by its public reference.
     *
     * <p>Input: the order number shown to customers. Output: the order if it
     * exists.
     *
     * @param orderNumber the public order reference
     * @return the matching order, if any
     */
    Optional<Order> findByOrderNumber(String orderNumber);

    /**
     * Lists a customer's orders, newest first.
     *
     * @param customerId the customer to filter by
     * @return that customer's orders
     */
    List<Order> findByCustomerIdOrderByCreatedAtDesc(String customerId);
}
