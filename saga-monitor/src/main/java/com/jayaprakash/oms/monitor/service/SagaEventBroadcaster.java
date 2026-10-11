package com.jayaprakash.oms.monitor.service;

import com.jayaprakash.oms.monitor.domain.SagaEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes saga events to every connected browser over Server-Sent Events.
 *
 * <p>SSE rather than WebSockets because the traffic only flows one way, server
 * to browser. SSE is plain HTTP, passes through proxies unchanged, and the
 * browser's {@code EventSource} reconnects on its own if the connection drops.
 */
@Component
public class SagaEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SagaEventBroadcaster.class);

    /** SSE event name for saga events; the browser listens for exactly this name. */
    public static final String EVENT_NAME = "saga-event";

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final long emitterTimeoutMs;

    /**
     * @param emitterTimeoutMs how long one connection may stay open before the
     *                         browser is asked to reconnect, from
     *                         {@code saga-monitor.emitter-timeout-ms}
     */
    public SagaEventBroadcaster(@Value("${saga-monitor.emitter-timeout-ms:1800000}") long emitterTimeoutMs) {
        this.emitterTimeoutMs = emitterTimeoutMs;
    }

    /**
     * Opens a new stream for one browser.
     *
     * <p>Input: none. Processing: creates an emitter, registers it, and sends a
     * {@code connected} event so the browser knows the stream is live before
     * any order is placed. Output: the emitter for Spring MVC to hold open.
     *
     * @return the open stream
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        register(emitter);
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("subscribers", emitters.size())));
        } catch (IOException | IllegalStateException exception) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /**
     * Tracks an emitter and forgets it when its connection ends.
     *
     * <p>Package-private so tests can register an emitter they control.
     *
     * @param emitter the stream to track
     */
    void register(SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> emitters.remove(emitter));
    }

    /**
     * Sends one event to every connected browser.
     *
     * <p>Input: a stored event. Processing: writes it to each emitter as a
     * named SSE event whose id is the sequence number. Output: none. A browser
     * that has gone away makes {@code send} throw; that emitter is dropped and
     * the others still receive the event.
     *
     * @param event the event to push
     */
    public void broadcast(SagaEvent event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.sequence()))
                        .name(EVENT_NAME)
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException exception) {
                log.debug("Dropping disconnected subscriber: {}", exception.getMessage());
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Keeps idle connections open.
     *
     * <p>Some proxies close an HTTP response that has been silent for a while.
     * An SSE comment line is ignored by the browser but counts as traffic.
     */
    @Scheduled(fixedRateString = "${saga-monitor.heartbeat-ms:15000}")
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException exception) {
                emitters.remove(emitter);
            }
        }
    }

    /**
     * @return how many browsers are currently connected
     */
    public int subscriberCount() {
        return emitters.size();
    }
}
