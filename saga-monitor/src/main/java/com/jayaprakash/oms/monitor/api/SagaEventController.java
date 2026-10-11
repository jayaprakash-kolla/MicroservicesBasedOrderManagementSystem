package com.jayaprakash.oms.monitor.api;

import com.jayaprakash.oms.monitor.domain.SagaEvent;
import com.jayaprakash.oms.monitor.service.SagaEventBroadcaster;
import com.jayaprakash.oms.monitor.service.SagaEventStore;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The saga monitor's public API: a live stream and a recent-events lookup.
 *
 * <p>Reached directly on port 18084. During development the frontend's dev
 * server proxies {@code /api/v1/saga} here, so the browser never makes a
 * cross-origin request.
 */
@RestController
@RequestMapping("/api/v1/saga")
public class SagaEventController {

    private static final int MAX_LIMIT = 500;

    private final SagaEventStore eventStore;
    private final SagaEventBroadcaster broadcaster;

    /**
     * @param eventStore  recent events
     * @param broadcaster live stream
     */
    public SagaEventController(SagaEventStore eventStore, SagaEventBroadcaster broadcaster) {
        this.eventStore = eventStore;
        this.broadcaster = broadcaster;
    }

    /**
     * Opens a live stream of saga events.
     *
     * <p>Input: none. Output: a {@code text/event-stream} response that stays
     * open; each saga event arrives as an SSE event named {@code saga-event}
     * with the {@link SagaEvent} as JSON.
     *
     * @return the open stream
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }

    /**
     * Lists recently observed events.
     *
     * <p>Input: an optional order filter and a limit (capped at 500). Output:
     * the matching events, oldest first. Useful for catching up on an order
     * whose events arrived before the browser connected.
     *
     * @param orderId only events for this order, if given
     * @param limit   maximum number of events
     * @return recent events, oldest first
     */
    @GetMapping("/events")
    public List<SagaEvent> recent(@RequestParam(required = false) Long orderId,
                                  @RequestParam(defaultValue = "100") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return eventStore.recent(orderId, bounded);
    }
}
