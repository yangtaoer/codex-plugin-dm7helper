package io.dm7codex.plugin.execution;

import static io.dm7codex.plugin.execution.ExecutionModels.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;

public final class ExecutionEventBus {
    private final int capacity;
    private final int maxSessions;
    private final LinkedHashMap<String, SessionEvents> sessions = new LinkedHashMap<>(16, .75f, true);

    public ExecutionEventBus(int capacity) {
        this(capacity, 256);
    }

    public ExecutionEventBus(int capacity, int maxSessions) {
        if (capacity < 1 || capacity > 100_000) throw new IllegalArgumentException("capacity is invalid");
        if (maxSessions < 1 || maxSessions > 10_000) throw new IllegalArgumentException("maxSessions is invalid");
        this.capacity = capacity;
        this.maxSessions = maxSessions;
    }

    public ExecutionEvent publish(String sessionId, UUID executionId, ExecutionStatus status,
                                  Instant timestamp, String detail) {
        SessionEvents state;
        synchronized (sessions) {
            state = sessions.computeIfAbsent(ExecutionModels.text(sessionId, "sessionId", 512),
                    ignored -> new SessionEvents());
            while (sessions.size() > maxSessions) {
                var oldest = sessions.entrySet().iterator();
                oldest.next(); oldest.remove();
            }
        }
        synchronized (state) {
            var event = new ExecutionEvent(++state.sequence, sessionId, executionId, status, timestamp, detail);
            state.events.addLast(event);
            while (state.events.size() > capacity) state.events.removeFirst();
            return event;
        }
    }

    public List<ExecutionEvent> events(String sessionId, long afterSequence) {
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must not be negative");
        SessionEvents state;
        synchronized (sessions) { state = sessions.get(sessionId); }
        if (state == null) return List.of();
        synchronized (state) {
            var result = new ArrayList<ExecutionEvent>();
            for (var event : state.events) if (event.sequence() > afterSequence) result.add(event);
            return List.copyOf(result);
        }
    }

    public void remove(String sessionId) { synchronized (sessions) { sessions.remove(sessionId); } }
    public int sessionCount() { synchronized (sessions) { return sessions.size(); } }

    private static final class SessionEvents {
        private long sequence;
        private final ArrayDeque<ExecutionEvent> events = new ArrayDeque<>();
    }
}
