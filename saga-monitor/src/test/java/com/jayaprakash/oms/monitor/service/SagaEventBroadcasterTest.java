package com.jayaprakash.oms.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jayaprakash.oms.monitor.domain.SagaEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unit tests for {@link SagaEventBroadcaster}, using emitters that record what
 * they are sent instead of writing to a real HTTP response.
 */
class SagaEventBroadcasterTest {

    /** An emitter that keeps every event it is asked to send. */
    private static class RecordingEmitter extends SseEmitter {

        private final List<Set<ResponseBodyEmitter.DataWithMediaType>> sent = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sent.add(builder.build());
        }

        /**
         * @return whether any sent event carried this object as its data
         */
        boolean received(Object data) {
            return sent.stream()
                    .flatMap(Set::stream)
                    .anyMatch(part -> data.equals(part.getData()));
        }
    }

    /** An emitter whose browser has gone away. */
    private static class BrokenEmitter extends SseEmitter {

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("Broken pipe");
        }
    }

    private SagaEvent event() {
        return new SagaEvent(1L, "order-created", "OrderCreatedEvent", "order-service", 1L, false,
                JsonNodeFactory.instance.objectNode(), Instant.EPOCH, 0, 0L);
    }

    @Test
    @DisplayName("sends each event to every connected browser")
    void broadcastsToAll() {
        SagaEventBroadcaster broadcaster = new SagaEventBroadcaster(60_000L);
        RecordingEmitter first = new RecordingEmitter();
        RecordingEmitter second = new RecordingEmitter();
        broadcaster.register(first);
        broadcaster.register(second);

        SagaEvent event = event();
        broadcaster.broadcast(event);

        assertThat(first.received(event)).isTrue();
        assertThat(second.received(event)).isTrue();
    }

    @Test
    @DisplayName("drops a disconnected browser without affecting the others")
    void dropsBrokenSubscriber() {
        SagaEventBroadcaster broadcaster = new SagaEventBroadcaster(60_000L);
        RecordingEmitter healthy = new RecordingEmitter();
        broadcaster.register(new BrokenEmitter());
        broadcaster.register(healthy);

        SagaEvent event = event();
        broadcaster.broadcast(event);

        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
        assertThat(healthy.received(event)).isTrue();
    }
}
