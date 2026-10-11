package com.jayaprakash.oms.monitor.service;

import com.jayaprakash.oms.monitor.domain.SagaEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps the most recent saga events in memory.
 *
 * <p>Bounded on purpose: this is a viewing window, not a history. Kafka is the
 * history — anything older can still be read from the topics themselves, for
 * example in Kafka UI.
 */
@Component
public class SagaEventStore {

    private final int capacity;

    private final Deque<SagaEvent> events = new ArrayDeque<>();

    private long nextSequence = 1L;

    /**
     * @param capacity how many events to keep, from {@code saga-monitor.buffer-size}
     */
    public SagaEventStore(@Value("${saga-monitor.buffer-size:500}") int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("saga-monitor.buffer-size must be at least 1");
        }
        this.capacity = capacity;
    }

    /**
     * Adds an event, numbering it and evicting the oldest if the buffer is full.
     *
     * <p>Input: an unnumbered event from {@link SagaEventFactory}. Processing:
     * stamps the next sequence number, appends it, and drops the oldest event
     * once over capacity. Output: the stamped event.
     *
     * @param draft the event to store
     * @return the same event with its sequence number assigned
     */
    public synchronized SagaEvent append(SagaEvent draft) {
        SagaEvent stored = draft.withSequence(nextSequence++);
        events.addLast(stored);
        while (events.size() > capacity) {
            events.removeFirst();
        }
        return stored;
    }

    /**
     * Returns the most recent events, oldest first.
     *
     * <p>Input: an optional order filter and a maximum count. Processing: walks
     * the buffer from newest to oldest collecting matches until the limit is
     * reached. Output: the matches in the order they were received.
     *
     * @param orderId only events for this order, or {@code null} for all
     * @param limit   the maximum number of events to return
     * @return the matching events, oldest first
     */
    public synchronized List<SagaEvent> recent(Long orderId, int limit) {
        List<SagaEvent> newestFirst = new ArrayList<>();
        Iterator<SagaEvent> iterator = events.descendingIterator();

        while (iterator.hasNext() && newestFirst.size() < limit) {
            SagaEvent event = iterator.next();
            if (orderId == null || orderId.equals(event.orderId())) {
                newestFirst.add(event);
            }
        }

        List<SagaEvent> oldestFirst = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            oldestFirst.add(newestFirst.get(i));
        }
        return oldestFirst;
    }

    /**
     * @return how many events are currently held
     */
    public synchronized int size() {
        return events.size();
    }
}
