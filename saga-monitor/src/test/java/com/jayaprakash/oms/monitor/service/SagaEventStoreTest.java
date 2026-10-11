package com.jayaprakash.oms.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jayaprakash.oms.monitor.domain.SagaEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SagaEventStore}: numbering, eviction, and filtering.
 */
class SagaEventStoreTest {

    /**
     * Builds an unnumbered event for an order.
     *
     * @param orderId the order it belongs to
     * @param type    the event type
     * @return a draft event
     */
    private SagaEvent draft(long orderId, String type) {
        return new SagaEvent(0L, "topic", type, "service", orderId, false,
                JsonNodeFactory.instance.objectNode(), Instant.EPOCH, 0, 0L);
    }

    @Test
    @DisplayName("numbers events in arrival order starting at 1")
    void assignsIncreasingSequence() {
        SagaEventStore store = new SagaEventStore(10);

        SagaEvent first = store.append(draft(1L, "A"));
        SagaEvent second = store.append(draft(1L, "B"));

        assertThat(first.sequence()).isEqualTo(1L);
        assertThat(second.sequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("drops the oldest events once the buffer is full")
    void evictsOldest() {
        SagaEventStore store = new SagaEventStore(2);

        store.append(draft(1L, "A"));
        store.append(draft(1L, "B"));
        store.append(draft(1L, "C"));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.recent(null, 10)).extracting(SagaEvent::eventType).containsExactly("B", "C");
    }

    @Test
    @DisplayName("filters by order and returns the most recent matches, oldest first")
    void filtersByOrderAndLimits() {
        SagaEventStore store = new SagaEventStore(10);
        store.append(draft(1L, "A"));
        store.append(draft(2L, "B"));
        store.append(draft(1L, "C"));
        store.append(draft(1L, "D"));

        List<SagaEvent> recent = store.recent(1L, 2);

        assertThat(recent).extracting(SagaEvent::eventType).containsExactly("C", "D");
    }

    @Test
    @DisplayName("rejects a buffer size below one")
    void rejectsInvalidCapacity() {
        assertThatThrownBy(() -> new SagaEventStore(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
