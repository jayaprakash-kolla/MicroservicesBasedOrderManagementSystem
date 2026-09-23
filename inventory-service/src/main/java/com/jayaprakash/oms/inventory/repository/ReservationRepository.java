package com.jayaprakash.oms.inventory.repository;

import com.jayaprakash.oms.inventory.domain.Reservation;
import com.jayaprakash.oms.inventory.domain.ReservationStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Database access for stock holds.
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * Finds every hold created for an order, whatever its state.
     *
     * <p>Input: an order id. Output: the holds. Used as the idempotency check —
     * if this comes back non-empty, the order has already been processed.
     *
     * @param orderId the order to look up
     * @return the holds for that order
     */
    List<Reservation> findByOrderId(Long orderId);

    /**
     * Finds only the holds that are still active for an order.
     *
     * <p>Input: order id and the status to match. Output: the matching holds.
     * Used during compensation so a redelivered cancellation cannot release the
     * same stock twice.
     *
     * @param orderId the order to look up
     * @param status  the status to filter on
     * @return the matching holds
     */
    List<Reservation> findByOrderIdAndStatus(Long orderId, ReservationStatus status);
}
