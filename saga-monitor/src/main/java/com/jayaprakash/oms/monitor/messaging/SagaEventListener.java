package com.jayaprakash.oms.monitor.messaging;

import com.jayaprakash.oms.monitor.service.SagaEventBroadcaster;
import com.jayaprakash.oms.monitor.service.SagaEventFactory;
import com.jayaprakash.oms.monitor.service.SagaEventStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads every saga topic, including the dead-letter topics.
 *
 * <p>One listener subscribes by pattern rather than by a fixed list, so a
 * {@code .DLT} topic that only appears after a failure is picked up as soon as
 * Kafka creates it. The consumer group is {@code saga-monitor}: separate from
 * every business service, so each of them still receives every message.
 */
@Component
public class SagaEventListener {

    private static final Logger log = LoggerFactory.getLogger(SagaEventListener.class);

    private final SagaEventFactory eventFactory;
    private final SagaEventStore eventStore;
    private final SagaEventBroadcaster broadcaster;

    /**
     * @param eventFactory turns records into events
     * @param eventStore   keeps recent events for late joiners
     * @param broadcaster  pushes events to connected browsers
     */
    public SagaEventListener(SagaEventFactory eventFactory,
                             SagaEventStore eventStore,
                             SagaEventBroadcaster broadcaster) {
        this.eventFactory = eventFactory;
        this.eventStore = eventStore;
        this.broadcaster = broadcaster;
    }

    /**
     * Handles one record from any saga topic.
     *
     * <p>Input: the raw record, body as a JSON string. Processing: converts it,
     * stores it, and pushes it to every connected browser. Output: none.
     *
     * @param record the Kafka record
     */
    @KafkaListener(topicPattern = "${saga-monitor.topic-pattern}", groupId = "saga-monitor")
    public void onRecord(ConsumerRecord<String, String> record) {
        eventFactory.fromRecord(
                        record.topic(),
                        record.key(),
                        record.value(),
                        record.timestamp(),
                        record.partition(),
                        record.offset())
                .map(eventStore::append)
                .ifPresent(event -> {
                    log.info("Observed {} on {} for order {} (#{})",
                            event.eventType(), event.topic(), event.orderId(), event.sequence());
                    broadcaster.broadcast(event);
                });
    }
}
