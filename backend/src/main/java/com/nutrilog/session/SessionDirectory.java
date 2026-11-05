package com.nutrilog.session;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 单进程内存会话目录。每次只发布完整的已提交快照。 */
@Service
public class SessionDirectory {
    private static final class Entry {
        private final String owner;
        private final View view;
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile Snapshot snapshot = new Snapshot(List.of(), new State(List.of(), null, null, null));

        private Entry(String owner, View view) {
            this.owner = owner;
            this.view = view;
        }
    }

    public record State(List<Long> candidateFoodIds, Long selectedFoodId, BigDecimal weightGrams,
                        String weightBasis) {
        public State { candidateFoodIds = List.copyOf(candidateFoodIds); }
    }

    public record Turn(String message, String answer) {}
    public record Snapshot(List<Turn> turns, State state) {
        public Snapshot {
            turns = List.copyOf(turns);
            Objects.requireNonNull(state);
        }
    }
    public record CompletedTurn(String answer, State state) {
        public CompletedTurn {
            if (answer == null || answer.isBlank()) throw new IllegalArgumentException("回答不能为空");
            Objects.requireNonNull(state);
        }
    }
    public record View(UUID sessionId, String title, Instant createdAt) {}
    private final ConcurrentHashMap<UUID,Entry> entries = new ConcurrentHashMap<>();
    public View create(String owner, String title) {
        if (owner == null || owner.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (title == null || title.isBlank() || title.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"标题须为1至100个字符");
        var view = new View(UUID.randomUUID(),title.strip(),Instant.now());
        entries.put(view.sessionId(),new Entry(owner,view)); return view;
    }
    private Entry requireOwned(String owner, UUID id) {
        var entry = entries.get(id);
        if (entry == null || !entry.owner.equals(owner))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"会话不存在或无权访问");
        return entry;
    }
    public View get(String owner, UUID id) { return requireOwned(owner,id).view; }
    public List<View> list(String owner) {
        return entries.values().stream().filter(entry -> entry.owner.equals(owner)).map(entry -> entry.view)
            .sorted(Comparator.comparing(View::createdAt).thenComparing(View::sessionId)).toList();
    }
    public Snapshot snapshot(String owner, UUID id) {
        return requireOwned(owner, id).snapshot;
    }

    public CompletedTurn executeTurn(String owner, UUID id, String message,
                                     Function<Snapshot, CompletedTurn> execute) {
        Entry entry = acquire(owner, id);
        try {
            Snapshot previous = entry.snapshot;
            CompletedTurn completed = execute.apply(previous);
            var turns = new ArrayList<>(previous.turns());
            turns.add(new Turn(message, completed.answer()));
            entry.snapshot = new Snapshot(turns, completed.state());
            return completed;
        } finally {
            entry.busy.set(false);
        }
    }

    private Entry acquire(String owner, UUID id) {
        Entry entry = requireOwned(owner, id);
        if (!entry.busy.compareAndSet(false, true))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话正在处理中");
        if (entries.get(id) != entry) {
            entry.busy.set(false);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "会话不存在或无权访问");
        }
        return entry;
    }

    public void delete(String owner, UUID id) {
        Entry entry = acquire(owner, id);
        try {
            entries.remove(id, entry);
        } finally {
            entry.busy.set(false);
        }
    }
}
