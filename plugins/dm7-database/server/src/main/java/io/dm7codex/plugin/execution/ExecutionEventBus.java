package io.dm7codex.plugin.execution;

import static io.dm7codex.plugin.execution.ExecutionModels.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ExecutionEventBus {
    private final int capacity;
    private final ConcurrentHashMap<String, SessionEvents> sessions = new ConcurrentHashMap<>();

    public ExecutionEventBus(int capacity) {
        if (capacity < 1 || capacity > 100_000) throw new IllegalArgumentException("capacity is invalid");
        this.capacity = capacity;
    }

    public ExecutionEvent publish(String sessionId, UUID executionId, ExecutionStatus status,
                                  Instant timestamp, String detail) {
        var state = sessions.computeIfAbsent(ExecutionModels.text(sessionId, "sessionId", 512),
                ignored -> new SessionEvents());
        synchronized (state) {
            var event = new ExecutionEvent(++state.sequence, sessionId, executionId, status, timestamp, detail);
            state.events.addLast(event);
            while (state.events.size() > capacity) state.events.removeFirst();
            return event;
        }
    }

    public List<ExecutionEvent> events(String sessionId, long afterSequence) {
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must not be negative");
        var state = sessions.get(sessionId);
        if (state == null) return List.of();
        synchronized (state) {
            var result = new ArrayList<ExecutionEvent>();
            for (var event : state.events) if (event.sequence() > afterSequence) result.add(event);
            return List.copyOf(result);
        }
    }

    private static final class SessionEvents {
        private long sequence;
        private final ArrayDeque<ExecutionEvent> events = new ArrayDeque<>();
    }
}
