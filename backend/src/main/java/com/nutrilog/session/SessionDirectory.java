package com.nutrilog.session;

import java.time.Instant;
import java.sql.SQLException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 单进程会话目录。启用文件库时先提交数据库，再发布内存快照。 */
@Service
public class SessionDirectory implements AutoCloseable {
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
    private final SessionDatabase database;

    public SessionDirectory() { this(""); }

    @Autowired
    public SessionDirectory(@Value("${nutrilog.session.jdbc-url:}") String url) {
        if (url.isBlank()) {
            database = null;
            return;
        }
        SessionDatabase opened = null;
        try {
            opened = new SessionDatabase(url);
            for (var saved : opened.load()) {
                var entry = new Entry(saved.owner(), saved.view());
                entry.snapshot = saved.snapshot();
                entries.put(saved.view().sessionId(), entry);
            }
            database = opened;
        } catch (SQLException | RuntimeException error) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException closing) { error.addSuppressed(closing); }
            }
            throw new IllegalStateException("无法加载会话数据库", error);
        }
    }

    public View create(String owner, String title) {
        if (owner == null || owner.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (title == null || title.isBlank() || title.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"标题须为1至100个字符");
        var view = new View(UUID.randomUUID(),title.strip(),Instant.now());
        var entry = new Entry(owner, view);
        if (database != null) database.insert(owner, view, entry.snapshot);
        entries.put(view.sessionId(), entry);
        return view;
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
            var next = new Snapshot(turns, completed.state());
            if (database != null) database.update(id, owner, next);
            entry.snapshot = next;
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
            if (database != null) database.delete(id, owner);
            entries.remove(id, entry);
        } finally {
            entry.busy.set(false);
        }
    }
    @PreDestroy
    @Override public void close() throws SQLException {
        if (database != null) database.close();
    }
}
